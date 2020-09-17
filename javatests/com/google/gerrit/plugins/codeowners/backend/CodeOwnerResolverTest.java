// Copyright (C) 2020 The Android Open Source Project
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.gerrit.plugins.codeowners.backend;

import static com.google.common.truth.Truth.assertThat;
import static com.google.gerrit.plugins.codeowners.testing.CodeOwnerSubject.assertThat;
import static com.google.gerrit.plugins.codeowners.testing.CodeOwnerSubject.hasAccountId;
import static com.google.gerrit.testing.GerritJUnit.assertThrows;

import com.google.gerrit.acceptance.TestAccount;
import com.google.gerrit.acceptance.config.GerritConfig;
import com.google.gerrit.acceptance.testsuite.account.AccountOperations;
import com.google.gerrit.acceptance.testsuite.request.RequestScopeOperations;
import com.google.gerrit.entities.Account;
import com.google.gerrit.plugins.codeowners.acceptance.AbstractCodeOwnersTest;
import com.google.gerrit.server.ServerInitiated;
import com.google.gerrit.server.account.AccountsUpdate;
import com.google.gerrit.server.account.externalids.ExternalId;
import com.google.gerrit.server.account.externalids.ExternalIdNotes;
import com.google.gerrit.server.git.meta.MetaDataUpdate;
import com.google.inject.Inject;
import com.google.inject.Key;
import com.google.inject.Provider;
import java.nio.file.Paths;
import java.util.stream.Stream;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.junit.Before;
import org.junit.Test;

/** Tests for {@link CodeOwnerResolver}. */
public class CodeOwnerResolverTest extends AbstractCodeOwnersTest {
  private static final ObjectId TEST_REVISION =
      ObjectId.fromString("deadbeefdeadbeefdeadbeefdeadbeefdeadbeef");

  @Inject private RequestScopeOperations requestScopeOperations;
  @Inject @ServerInitiated private Provider<AccountsUpdate> accountsUpdate;
  @Inject private AccountOperations accountOperations;
  @Inject private ExternalIdNotes.Factory externalIdNotesFactory;

  private Provider<CodeOwnerResolver> codeOwnerResolver;

  @Before
  public void setUpCodeOwnersPlugin() throws Exception {
    codeOwnerResolver =
        plugin.getSysInjector().getInstance(new Key<Provider<CodeOwnerResolver>>() {});
  }

  @Test
  public void cannotResolveNullToCodeOwner() throws Exception {
    NullPointerException npe =
        assertThrows(NullPointerException.class, () -> codeOwnerResolver.get().resolve(null));
    assertThat(npe).hasMessageThat().isEqualTo("codeOwnerReference");
  }

  @Test
  public void resolveCodeOwnerReferenceForNonExistingEmail() throws Exception {
    assertThat(
            codeOwnerResolver.get().resolve(CodeOwnerReference.create("non-existing@example.com")))
        .isEmpty();
  }

  @Test
  public void resolveCodeOwnerReferenceForEmail() throws Exception {
    Stream<CodeOwner> codeOwner =
        codeOwnerResolver.get().resolve(CodeOwnerReference.create(admin.email()));
    assertThat(codeOwner).onlyElement().hasAccountIdThat().isEqualTo(admin.id());
  }

  @Test
  public void resolveCodeOwnerReferenceForStarAsEmail() throws Exception {
    TestAccount user2 = accountCreator.user2();
    Stream<CodeOwner> codeOwner =
        codeOwnerResolver
            .get()
            .resolve(CodeOwnerReference.create(CodeOwnerResolver.ALL_USERS_WILDCARD));
    assertThat(codeOwner)
        .comparingElementsUsing(hasAccountId())
        .containsExactly(admin.id(), user.id(), user2.id());
  }

  @Test
  @GerritConfig(name = "accounts.visibility", value = "SAME_GROUP")
  public void resolveCodeOwnerReferenceForStarAsEmailChecksAccountVisibility() throws Exception {
    // Create a new user that is not a member of any group. This means 'user' and 'admin' are not
    // visible to this user since they do not share any group.
    TestAccount user2 = accountCreator.user2();

    // Set user2 as current user.
    requestScopeOperations.setApiUser(user2.id());

    Stream<CodeOwner> codeOwner =
        codeOwnerResolver
            .get()
            .resolve(CodeOwnerReference.create(CodeOwnerResolver.ALL_USERS_WILDCARD));
    assertThat(codeOwner).comparingElementsUsing(hasAccountId()).containsExactly(user2.id());
  }

  @Test
  @GerritConfig(name = "accounts.visibility", value = "SAME_GROUP")
  public void nonVisibleCodeOwnerCanBeResolvedForStarAsEmailIfVisibilityIsNotEnforced()
      throws Exception {
    // Create a new user that is not a member of any group. This means 'user' and 'admin' are not
    // visible to this user since they do not share any group.
    TestAccount user2 = accountCreator.user2();

    // Set user2 as current user.
    requestScopeOperations.setApiUser(user2.id());

    Stream<CodeOwner> codeOwner =
        codeOwnerResolver
            .get()
            .enforceVisibility(false)
            .resolve(CodeOwnerReference.create(CodeOwnerResolver.ALL_USERS_WILDCARD));
    assertThat(codeOwner)
        .comparingElementsUsing(hasAccountId())
        .containsExactly(admin.id(), user.id(), user2.id());
  }

  @Test
  public void resolveCodeOwnerReferenceForAmbiguousEmail() throws Exception {
    // Create an external ID for 'user' account that has the same email as the 'admin' account.
    accountsUpdate
        .get()
        .update(
            "Test update",
            user.id(),
            (a, u) ->
                u.addExternalId(ExternalId.create("foo", "bar", user.id(), admin.email(), null)));

    assertThat(codeOwnerResolver.get().resolve(CodeOwnerReference.create(admin.email()))).isEmpty();
  }

  @Test
  public void resolveCodeOwnerReferenceForOrphanedEmail() throws Exception {
    // Create an external ID with an email for a non-existing account.
    String email = "foo.bar@example.com";
    try (Repository allUsersRepo = repoManager.openRepository(allUsers);
        MetaDataUpdate md = metaDataUpdateFactory.create(allUsers)) {
      ExternalIdNotes extIdNotes = externalIdNotesFactory.load(allUsersRepo);
      extIdNotes.upsert(ExternalId.createEmail(Account.id(999999), email));
      extIdNotes.commit(md);
    }

    assertThat(codeOwnerResolver.get().resolve(CodeOwnerReference.create(email))).isEmpty();
  }

  @Test
  public void resolveCodeOwnerReferenceForInactiveUser() throws Exception {
    accountOperations.account(user.id()).forUpdate().inactive().update();
    assertThat(codeOwnerResolver.get().resolve(CodeOwnerReference.create(user.email()))).isEmpty();
  }

  @Test
  @GerritConfig(name = "accounts.visibility", value = "SAME_GROUP")
  public void resolveCodeOwnerReferenceForNonVisibleAccount() throws Exception {
    TestAccount user2 = accountCreator.user2();

    // Set user2 as current user.
    requestScopeOperations.setApiUser(user2.id());

    // user2 cannot see the admin account since they do not share any group and
    // "accounts.visibility" is set to "SAME_GROUP".
    assertThat(codeOwnerResolver.get().resolve(CodeOwnerReference.create(admin.email()))).isEmpty();
  }

  @Test
  public void resolveCodeOwnerReferenceForSecondaryEmail() throws Exception {
    // add secondary email to user account
    String secondaryEmail = "user@foo.bar";
    accountOperations.account(user.id()).forUpdate().addSecondaryEmail(secondaryEmail).update();

    // admin has the "Modify Account" global capability and hence can see the secondary email of the
    // user account.
    Stream<CodeOwner> codeOwner =
        codeOwnerResolver.get().resolve(CodeOwnerReference.create(secondaryEmail));
    assertThat(codeOwner).onlyElement().hasAccountIdThat().isEqualTo(user.id());

    // user can see its own secondary email.
    requestScopeOperations.setApiUser(user.id());
    codeOwner = codeOwnerResolver.get().resolve(CodeOwnerReference.create(secondaryEmail));
    assertThat(codeOwner).onlyElement().hasAccountIdThat().isEqualTo(user.id());
  }

  @Test
  public void resolveCodeOwnerReferenceForNonVisibleSecondaryEmail() throws Exception {
    // add secondary email to admin account
    String secondaryEmail = "admin@foo.bar";
    accountOperations.account(admin.id()).forUpdate().addSecondaryEmail(secondaryEmail).update();

    // user doesn't have the "Modify Account" global capability and hence cannot see the secondary
    // email of the admin account.
    requestScopeOperations.setApiUser(user.id());
    assertThat(codeOwnerResolver.get().resolve(CodeOwnerReference.create(secondaryEmail)))
        .isEmpty();
  }

  @Test
  public void resolvePathCodeOwnersForEmptyCodeOwnerConfig() throws Exception {
    CodeOwnerConfig codeOwnerConfig =
        CodeOwnerConfig.builder(CodeOwnerConfig.Key.create(project, "master", "/"), TEST_REVISION)
            .build();
    assertThat(
            codeOwnerResolver.get().resolvePathCodeOwners(codeOwnerConfig, Paths.get("/README.md")))
        .isEmpty();
  }

  @Test
  public void resolvePathCodeOwners() throws Exception {
    CodeOwnerConfig codeOwnerConfig =
        CodeOwnerConfig.builder(CodeOwnerConfig.Key.create(project, "master", "/"), TEST_REVISION)
            .addCodeOwnerSet(CodeOwnerSet.createWithoutPathExpressions(admin.email(), user.email()))
            .build();
    assertThat(
            codeOwnerResolver.get().resolvePathCodeOwners(codeOwnerConfig, Paths.get("/README.md")))
        .comparingElementsUsing(hasAccountId())
        .containsExactly(admin.id(), user.id());
  }

  @Test
  public void resolvePathCodeOwnersNonResolvableCodeOwnersAreFilteredOut() throws Exception {
    CodeOwnerConfig codeOwnerConfig =
        CodeOwnerConfig.builder(CodeOwnerConfig.Key.create(project, "master", "/"), TEST_REVISION)
            .addCodeOwnerSet(
                CodeOwnerSet.createWithoutPathExpressions(
                    admin.email(), "non-existing@example.com"))
            .build();
    assertThat(
            codeOwnerResolver.get().resolvePathCodeOwners(codeOwnerConfig, Paths.get("/README.md")))
        .comparingElementsUsing(hasAccountId())
        .containsExactly(admin.id());
  }

  @Test
  public void cannotResolvePathCodeOwnersOfNullCodeOwnerConfig() throws Exception {
    NullPointerException npe =
        assertThrows(
            NullPointerException.class,
            () -> codeOwnerResolver.get().resolvePathCodeOwners(null, Paths.get("/README.md")));
    assertThat(npe).hasMessageThat().isEqualTo("codeOwnerConfig");
  }

  @Test
  public void cannotResolvePathCodeOwnersForNullPath() throws Exception {
    CodeOwnerConfig codeOwnerConfig =
        CodeOwnerConfig.builder(CodeOwnerConfig.Key.create(project, "master", "/"), TEST_REVISION)
            .addCodeOwnerSet(CodeOwnerSet.createWithoutPathExpressions(admin.email()))
            .build();
    NullPointerException npe =
        assertThrows(
            NullPointerException.class,
            () -> codeOwnerResolver.get().resolvePathCodeOwners(codeOwnerConfig, null));
    assertThat(npe).hasMessageThat().isEqualTo("absolutePath");
  }

  @Test
  public void cannotResolvePathCodeOwnersForRelativePath() throws Exception {
    String relativePath = "foo/bar.md";
    CodeOwnerConfig codeOwnerConfig =
        CodeOwnerConfig.builder(CodeOwnerConfig.Key.create(project, "master", "/"), TEST_REVISION)
            .addCodeOwnerSet(CodeOwnerSet.createWithoutPathExpressions(admin.email()))
            .build();
    IllegalStateException npe =
        assertThrows(
            IllegalStateException.class,
            () ->
                codeOwnerResolver
                    .get()
                    .resolvePathCodeOwners(codeOwnerConfig, Paths.get(relativePath)));
    assertThat(npe)
        .hasMessageThat()
        .isEqualTo(String.format("path %s must be absolute", relativePath));
  }

  @Test
  @GerritConfig(name = "accounts.visibility", value = "SAME_GROUP")
  public void nonVisibleCodeOwnerCanBeResolvedIfVisibilityIsNotEnforced() throws Exception {
    TestAccount user2 = accountCreator.user2();

    // Set user2 as current user.
    requestScopeOperations.setApiUser(user2.id());

    CodeOwnerReference adminCodeOwnerReference = CodeOwnerReference.create(admin.email());

    // user2 cannot see the admin account since they do not share any group and
    // "accounts.visibility" is set to "SAME_GROUP".
    assertThat(codeOwnerResolver.get().resolve(adminCodeOwnerReference)).isEmpty();

    // if visibility is not enforced the code owner reference can be resolved regardless
    Stream<CodeOwner> codeOwner =
        codeOwnerResolver.get().enforceVisibility(false).resolve(adminCodeOwnerReference);
    assertThat(codeOwner).onlyElement().hasAccountIdThat().isEqualTo(admin.id());
  }

  @Test
  @GerritConfig(name = "accounts.visibility", value = "SAME_GROUP")
  public void codeOwnerVisibilityIsCheckedForGivenAccount() throws Exception {
    // Create a new user that is not a member of any group. This means 'user' and 'admin' are not
    // visible to this user since they do not share any group.
    TestAccount user2 = accountCreator.user2();

    // admin is the current user and can see the account
    assertThat(codeOwnerResolver.get().resolve(CodeOwnerReference.create(user.email())))
        .isNotEmpty();
    assertThat(
            codeOwnerResolver
                .get()
                .forUser(identifiedUserFactory.create(admin.id()))
                .resolve(CodeOwnerReference.create(user.email())))
        .isNotEmpty();

    // user2 cannot see the account
    assertThat(
            codeOwnerResolver
                .get()
                .forUser(identifiedUserFactory.create(user2.id()))
                .resolve(CodeOwnerReference.create(user.email())))
        .isEmpty();
  }

  @Test
  @GerritConfig(name = "plugin.code-owners.allowedEmailDomain", value = "example.net")
  public void resolveCodeOwnerReferenceForEmailWithNonAllowedEmailDomain() throws Exception {
    assertThat(codeOwnerResolver.get().resolve(CodeOwnerReference.create("foo@example.com")))
        .isEmpty();
  }

  @Test
  @GerritConfig(
      name = "plugin.code-owners.allowedEmailDomain",
      values = {"example.com", "example.net"})
  public void configuredEmailDomainsAreAllowed() throws Exception {
    assertThat(codeOwnerResolver.get().isEmailDomainAllowed("foo@example.com")).isTrue();
    assertThat(codeOwnerResolver.get().isEmailDomainAllowed("foo@example.net")).isTrue();
    assertThat(codeOwnerResolver.get().isEmailDomainAllowed("foo@example.org@example.com"))
        .isTrue();
    assertThat(codeOwnerResolver.get().isEmailDomainAllowed("foo@example.org")).isFalse();
    assertThat(codeOwnerResolver.get().isEmailDomainAllowed("foo")).isFalse();
    assertThat(codeOwnerResolver.get().isEmailDomainAllowed("foo@example.com@example.org"))
        .isFalse();
  }

  @Test
  public void allEmailDomainsAreAllowed() throws Exception {
    assertThat(codeOwnerResolver.get().isEmailDomainAllowed("foo@example.com")).isTrue();
    assertThat(codeOwnerResolver.get().isEmailDomainAllowed("foo@example.net")).isTrue();
    assertThat(codeOwnerResolver.get().isEmailDomainAllowed("foo@example.org@example.com"))
        .isTrue();
    assertThat(codeOwnerResolver.get().isEmailDomainAllowed("foo@example.org")).isTrue();
    assertThat(codeOwnerResolver.get().isEmailDomainAllowed("foo")).isTrue();
    assertThat(codeOwnerResolver.get().isEmailDomainAllowed("foo@example.com@example.org"))
        .isTrue();
  }
}
