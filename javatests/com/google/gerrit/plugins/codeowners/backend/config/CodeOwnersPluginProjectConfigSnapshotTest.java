// Copyright (C) 2021 The Android Open Source Project
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

package com.google.gerrit.plugins.codeowners.backend.config;

import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static com.google.common.truth.Truth.assertThat;
import static com.google.gerrit.plugins.codeowners.testing.CodeOwnerSetSubject.hasEmail;
import static com.google.gerrit.plugins.codeowners.testing.RequiredApprovalSubject.assertThat;
import static com.google.gerrit.testing.GerritJUnit.assertThrows;
import static com.google.gerrit.truth.OptionalSubject.assertThat;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.gerrit.acceptance.TestAccount;
import com.google.gerrit.acceptance.config.GerritConfig;
import com.google.gerrit.acceptance.testsuite.project.ProjectOperations;
import com.google.gerrit.common.Nullable;
import com.google.gerrit.entities.Project;
import com.google.gerrit.entities.Project.NameKey;
import com.google.gerrit.entities.RefNames;
import com.google.gerrit.extensions.common.LabelDefinitionInput;
import com.google.gerrit.extensions.registration.DynamicMap;
import com.google.gerrit.extensions.registration.PrivateInternals_DynamicMapImpl;
import com.google.gerrit.extensions.registration.RegistrationHandle;
import com.google.gerrit.plugins.codeowners.acceptance.AbstractCodeOwnersTest;
import com.google.gerrit.plugins.codeowners.backend.CodeOwnerBackend;
import com.google.gerrit.plugins.codeowners.backend.CodeOwnerConfig;
import com.google.gerrit.plugins.codeowners.backend.CodeOwnerConfigUpdate;
import com.google.gerrit.plugins.codeowners.backend.FallbackCodeOwners;
import com.google.gerrit.plugins.codeowners.backend.PathExpressionMatcher;
import com.google.gerrit.plugins.codeowners.backend.findowners.FindOwnersBackend;
import com.google.gerrit.plugins.codeowners.common.CodeOwnerConfigValidationPolicy;
import com.google.gerrit.plugins.codeowners.common.MergeCommitStrategy;
import com.google.gerrit.server.IdentifiedUser;
import com.google.inject.Inject;
import com.google.inject.Key;
import com.google.inject.util.Providers;
import java.nio.file.Path;
import java.util.Optional;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.revwalk.RevWalk;
import org.junit.Before;
import org.junit.Test;

/** Tests for {@link CodeOwnersPluginProjectConfigSnapshot}. */
public class CodeOwnersPluginProjectConfigSnapshotTest extends AbstractCodeOwnersTest {
  @Inject private ProjectOperations projectOperations;

  private CodeOwnersPluginProjectConfigSnapshot.Factory
      codeOwnersPluginProjectConfigSnapshotFactory;
  private DynamicMap<CodeOwnerBackend> codeOwnerBackends;

  @Before
  public void setUpCodeOwnersPlugin() throws Exception {
    codeOwnersPluginProjectConfigSnapshotFactory =
        plugin.getSysInjector().getInstance(CodeOwnersPluginProjectConfigSnapshot.Factory.class);
    codeOwnerBackends =
        plugin.getSysInjector().getInstance(new Key<DynamicMap<CodeOwnerBackend>>() {});
  }

  @Test
  @GerritConfig(name = "plugin.code-owners.fileExtension", value = "foo")
  public void getFileExtensionIfNoneIsConfiguredOnProjectLevel() throws Exception {
    assertThat(cfgSnapshot().getFileExtension()).value().isEqualTo("foo");
  }

  @Test
  @GerritConfig(name = "plugin.code-owners.fileExtension", value = "foo")
  public void fileExtensionOnProjectLevelOverridesDefaultFileExtension() throws Exception {
    configureFileExtension(project, "bar");
    assertThat(cfgSnapshot().getFileExtension()).value().isEqualTo("bar");
  }

  @Test
  @GerritConfig(name = "plugin.code-owners.fileExtension", value = "foo")
  public void fileExtensionIsInheritedFromParentProject() throws Exception {
    configureFileExtension(allProjects, "bar");
    assertThat(cfgSnapshot().getFileExtension()).value().isEqualTo("bar");
  }

  @Test
  public void inheritedFileExtensionCanBeOverridden() throws Exception {
    configureFileExtension(allProjects, "foo");
    configureFileExtension(project, "bar");
    assertThat(cfgSnapshot().getFileExtension()).value().isEqualTo("bar");
  }

  @Test
  public void getMergeCommitStrategyIfNoneIsConfigured() throws Exception {
    assertThat(cfgSnapshot().getMergeCommitStrategy())
        .isEqualTo(MergeCommitStrategy.ALL_CHANGED_FILES);
  }

  @Test
  @GerritConfig(
      name = "plugin.code-owners.mergeCommitStrategy",
      value = "FILES_WITH_CONFLICT_RESOLUTION")
  public void getMergeCommitStrategyIfNoneIsConfiguredOnProjectLevel() throws Exception {
    assertThat(cfgSnapshot().getMergeCommitStrategy())
        .isEqualTo(MergeCommitStrategy.FILES_WITH_CONFLICT_RESOLUTION);
  }

  @Test
  @GerritConfig(
      name = "plugin.code-owners.mergeCommitStrategy",
      value = "FILES_WITH_CONFLICT_RESOLUTION")
  public void mergeCommitStrategyOnProjectLevelOverridesGlobalMergeCommitStrategy()
      throws Exception {
    configureMergeCommitStrategy(project, MergeCommitStrategy.ALL_CHANGED_FILES);
    assertThat(cfgSnapshot().getMergeCommitStrategy())
        .isEqualTo(MergeCommitStrategy.ALL_CHANGED_FILES);
  }

  @Test
  @GerritConfig(
      name = "plugin.code-owners.mergeCommitStrategy",
      value = "FILES_WITH_CONFLICT_RESOLUTION")
  public void mergeCommitStrategyIsInheritedFromParentProject() throws Exception {
    configureMergeCommitStrategy(allProjects, MergeCommitStrategy.ALL_CHANGED_FILES);
    assertThat(cfgSnapshot().getMergeCommitStrategy())
        .isEqualTo(MergeCommitStrategy.ALL_CHANGED_FILES);
  }

  @Test
  public void inheritedMergeCommitStrategyCanBeOverridden() throws Exception {
    configureMergeCommitStrategy(allProjects, MergeCommitStrategy.FILES_WITH_CONFLICT_RESOLUTION);
    configureMergeCommitStrategy(project, MergeCommitStrategy.ALL_CHANGED_FILES);
    assertThat(cfgSnapshot().getMergeCommitStrategy())
        .isEqualTo(MergeCommitStrategy.ALL_CHANGED_FILES);
  }

  @Test
  public void getFallbackCodeOwnersIfNoneIsConfigured() throws Exception {
    assertThat(cfgSnapshot().getFallbackCodeOwners()).isEqualTo(FallbackCodeOwners.NONE);
  }

  @Test
  @GerritConfig(name = "plugin.code-owners.fallbackCodeOwners", value = "ALL_USERS")
  public void getFallbackCodeOwnersIfNoneIsConfiguredOnProjectLevel() throws Exception {
    assertThat(cfgSnapshot().getFallbackCodeOwners()).isEqualTo(FallbackCodeOwners.ALL_USERS);
  }

  @Test
  @GerritConfig(name = "plugin.code-owners.fallbackCodeOwners", value = "ALL_USERS")
  public void fallbackCodeOnwersOnProjectLevelOverridesGlobalFallbackCodeOwners() throws Exception {
    configureFallbackCodeOwners(project, FallbackCodeOwners.NONE);
    assertThat(cfgSnapshot().getFallbackCodeOwners()).isEqualTo(FallbackCodeOwners.NONE);
  }

  @Test
  @GerritConfig(name = "plugin.code-owners.fallbackCodeOwners", value = "ALL_USERS")
  public void fallbackCodeOwnersIsInheritedFromParentProject() throws Exception {
    configureFallbackCodeOwners(allProjects, FallbackCodeOwners.NONE);
    assertThat(cfgSnapshot().getFallbackCodeOwners()).isEqualTo(FallbackCodeOwners.NONE);
  }

  @Test
  public void inheritedFallbackCodeOwnersCanBeOverridden() throws Exception {
    configureFallbackCodeOwners(allProjects, FallbackCodeOwners.ALL_USERS);
    configureFallbackCodeOwners(project, FallbackCodeOwners.NONE);
    assertThat(cfgSnapshot().getFallbackCodeOwners()).isEqualTo(FallbackCodeOwners.NONE);
  }

  @Test
  public void getGlobalCodeOwnersIfNoneIsConfigured() throws Exception {
    assertThat(cfgSnapshot().getGlobalCodeOwners()).isEmpty();
  }

  @Test
  @GerritConfig(
      name = "plugin.code-owners.globalCodeOwner",
      values = {"global-code-owner-1@example.com", "global-code-owner-2@example.com"})
  public void getGlobalCodeOwnerssIfNoneIsConfiguredOnProjectLevel() throws Exception {
    TestAccount globalCodeOwner1 =
        accountCreator.create(
            "globalCodeOwner1",
            "global-code-owner-1@example.com",
            "Global Code Owner 1",
            /* displayName= */ null);
    TestAccount globalCodeOwner2 =
        accountCreator.create(
            "globalCodeOwner2",
            "global-code-owner-2@example.com",
            "Global Code Owner 2",
            /* displayName= */ null);
    assertThat(cfgSnapshot().getGlobalCodeOwners())
        .comparingElementsUsing(hasEmail())
        .containsExactly(globalCodeOwner1.email(), globalCodeOwner2.email());
  }

  @Test
  @GerritConfig(
      name = "plugin.code-owners.globalCodeOwner",
      values = {"global-code-owner-1@example.com", "global-code-owner-2@example.com"})
  public void globalCodeOwnersOnProjectLevelExtendsGloballyConfiguredGlobalCodeOwners()
      throws Exception {
    TestAccount globalCodeOwner1 =
        accountCreator.create(
            "globalCodeOwner1",
            "global-code-owner-1@example.com",
            "Global Code Owner 1",
            /* displayName= */ null);
    TestAccount globalCodeOwner2 =
        accountCreator.create(
            "globalCodeOwner2",
            "global-code-owner-2@example.com",
            "Global Code Owner 2",
            /* displayName= */ null);
    TestAccount globalCodeOwner3 =
        accountCreator.create(
            "globalCodeOwner3",
            "global-code-owner-3@example.com",
            "Global Code Owner 3",
            /* displayName= */ null);
    TestAccount globalCodeOwner4 =
        accountCreator.create(
            "globalCodeOwner4",
            "global-code-owner-4@example.com",
            "Global Code Owner 4",
            /* displayName= */ null);
    configureGlobalCodeOwners(allProjects, globalCodeOwner3.email(), globalCodeOwner4.email());
    assertThat(cfgSnapshot().getGlobalCodeOwners())
        .comparingElementsUsing(hasEmail())
        .containsExactly(
            globalCodeOwner1.email(),
            globalCodeOwner2.email(),
            globalCodeOwner3.email(),
            globalCodeOwner4.email());
  }

  @Test
  @GerritConfig(
      name = "plugin.code-owners.globalCodeOwner",
      values = {"global-code-owner-1@example.com", "global-code-owner-2@example.com"})
  public void
      globalCodeOwnersOnProjectLevelExtendsGloballyConfiguredGlobalCodeOwners_duplicatesAreFilteredOut()
          throws Exception {
    TestAccount globalCodeOwner1 =
        accountCreator.create(
            "globalCodeOwner1",
            "global-code-owner-1@example.com",
            "Global Code Owner 1",
            /* displayName= */ null);
    TestAccount globalCodeOwner2 =
        accountCreator.create(
            "globalCodeOwner2",
            "global-code-owner-2@example.com",
            "Global Code Owner 2",
            /* displayName= */ null);
    TestAccount globalCodeOwner3 =
        accountCreator.create(
            "globalCodeOwner3",
            "global-code-owner-3@example.com",
            "Global Code Owner 3",
            /* displayName= */ null);
    configureGlobalCodeOwners(allProjects, globalCodeOwner1.email(), globalCodeOwner3.email());
    assertThat(cfgSnapshot().getGlobalCodeOwners())
        .comparingElementsUsing(hasEmail())
        .containsExactly(
            globalCodeOwner1.email(), globalCodeOwner2.email(), globalCodeOwner3.email());
  }

  @Test
  @GerritConfig(
      name = "plugin.code-owners.globalCodeOwner",
      values = {"global-code-owner-1@example.com", "global-code-owner-2@example.com"})
  public void globalCodeOwnersAreInheritedFromParentProject() throws Exception {
    TestAccount globalCodeOwner1 =
        accountCreator.create(
            "globalCodeOwner1",
            "global-code-owner-1@example.com",
            "Global Code Owner 1",
            /* displayName= */ null);
    TestAccount globalCodeOwner2 =
        accountCreator.create(
            "globalCodeOwner2",
            "global-code-owner-2@example.com",
            "Global Code Owner 2",
            /* displayName= */ null);
    TestAccount globalCodeOwner3 =
        accountCreator.create(
            "globalCodeOwner3",
            "global-code-owner-3@example.com",
            "Global Code Owner 3",
            /* displayName= */ null);
    TestAccount globalCodeOwner4 =
        accountCreator.create(
            "globalCodeOwner4",
            "global-code-owner-4@example.com",
            "Global Code Owner 4",
            /* displayName= */ null);
    configureGlobalCodeOwners(allProjects, globalCodeOwner3.email(), globalCodeOwner4.email());
    assertThat(cfgSnapshot().getGlobalCodeOwners())
        .comparingElementsUsing(hasEmail())
        .containsExactly(
            globalCodeOwner1.email(),
            globalCodeOwner2.email(),
            globalCodeOwner3.email(),
            globalCodeOwner4.email());
  }

  @Test
  @GerritConfig(
      name = "plugin.code-owners.globalCodeOwner",
      values = {"global-code-owner-1@example.com", "global-code-owner-2@example.com"})
  public void globalCodeOwnersAreInheritedFromParentProject_duplicatesAreFilteredOut()
      throws Exception {
    TestAccount globalCodeOwner1 =
        accountCreator.create(
            "globalCodeOwner1",
            "global-code-owner-1@example.com",
            "Global Code Owner 1",
            /* displayName= */ null);
    TestAccount globalCodeOwner2 =
        accountCreator.create(
            "globalCodeOwner2",
            "global-code-owner-2@example.com",
            "Global Code Owner 2",
            /* displayName= */ null);
    TestAccount globalCodeOwner3 =
        accountCreator.create(
            "globalCodeOwner3",
            "global-code-owner-3@example.com",
            "Global Code Owner 3",
            /* displayName= */ null);
    configureGlobalCodeOwners(allProjects, globalCodeOwner1.email(), globalCodeOwner3.email());
    assertThat(cfgSnapshot().getGlobalCodeOwners())
        .comparingElementsUsing(hasEmail())
        .containsExactly(
            globalCodeOwner1.email(), globalCodeOwner2.email(), globalCodeOwner3.email());
  }

  @Test
  public void inheritedGlobalCodeOwnersCanBeExtended() throws Exception {
    TestAccount globalCodeOwner1 =
        accountCreator.create(
            "globalCodeOwner1",
            "global-code-owner-1@example.com",
            "Global Code Owner 1",
            /* displayName= */ null);
    TestAccount globalCodeOwner2 =
        accountCreator.create(
            "globalCodeOwner2",
            "global-code-owner-2@example.com",
            "Global Code Owner 2",
            /* displayName= */ null);
    TestAccount globalCodeOwner3 =
        accountCreator.create(
            "globalCodeOwner3",
            "global-code-owner-3@example.com",
            "Global Code Owner 3",
            /* displayName= */ null);
    TestAccount globalCodeOwner4 =
        accountCreator.create(
            "globalCodeOwner4",
            "global-code-owner-4@example.com",
            "Global Code Owner 4",
            /* displayName= */ null);
    configureGlobalCodeOwners(allProjects, globalCodeOwner1.email(), globalCodeOwner2.email());
    configureGlobalCodeOwners(project, globalCodeOwner3.email(), globalCodeOwner4.email());
    assertThat(cfgSnapshot().getGlobalCodeOwners())
        .comparingElementsUsing(hasEmail())
        .containsExactly(
            globalCodeOwner1.email(),
            globalCodeOwner2.email(),
            globalCodeOwner3.email(),
            globalCodeOwner4.email());
  }

  @Test
  public void inheritedGlobalCodeOwnersCanBeExtended_duplicatesAreFilteredOut() throws Exception {
    TestAccount globalCodeOwner1 =
        accountCreator.create(
            "globalCodeOwner1",
            "global-code-owner-1@example.com",
            "Global Code Owner 1",
            /* displayName= */ null);
    TestAccount globalCodeOwner2 =
        accountCreator.create(
            "globalCodeOwner2",
            "global-code-owner-2@example.com",
            "Global Code Owner 2",
            /* displayName= */ null);
    TestAccount globalCodeOwner3 =
        accountCreator.create(
            "globalCodeOwner3",
            "global-code-owner-3@example.com",
            "Global Code Owner 3",
            /* displayName= */ null);
    configureGlobalCodeOwners(allProjects, globalCodeOwner1.email(), globalCodeOwner2.email());
    configureGlobalCodeOwners(project, globalCodeOwner1.email(), globalCodeOwner3.email());
    assertThat(cfgSnapshot().getGlobalCodeOwners())
        .comparingElementsUsing(hasEmail())
        .containsExactly(
            globalCodeOwner1.email(), globalCodeOwner2.email(), globalCodeOwner3.email());
  }

  @Test
  public void inheritedGlobalCodeOwnersCannotBeRemoved() throws Exception {
    TestAccount globalCodeOwner1 =
        accountCreator.create(
            "globalCodeOwner1",
            "global-code-owner-1@example.com",
            "Global Code Owner 1",
            /* displayName= */ null);
    TestAccount globalCodeOwner2 =
        accountCreator.create(
            "globalCodeOwner2",
            "global-code-owner-2@example.com",
            "Global Code Owner 2",
            /* displayName= */ null);
    configureGlobalCodeOwners(allProjects, globalCodeOwner1.email(), globalCodeOwner2.email());
    configureGlobalCodeOwners(project, "");
    assertThat(cfgSnapshot().getGlobalCodeOwners())
        .comparingElementsUsing(hasEmail())
        .containsExactly(globalCodeOwner1.email(), globalCodeOwner2.email());
  }

  @Test
  public void getExemptedAccountsIfNoneIsConfigured() throws Exception {
    assertThat(cfgSnapshot().getExemptedAccounts()).isEmpty();
  }

  @Test
  @GerritConfig(
      name = "plugin.code-owners.exemptedUser",
      values = {"exempted-user-1@example.com", "exempted-user-2@example.com"})
  public void getExemptedAccountsIfNoneIsConfiguredOnProjectLevel() throws Exception {
    TestAccount exemptedUser1 =
        accountCreator.create(
            "exemptedUser1",
            "exempted-user-1@example.com",
            "Exempted User 1",
            /* displayName= */ null);
    TestAccount exemptedUser2 =
        accountCreator.create(
            "exemptedUser2",
            "exempted-user-2@example.com",
            "Exempted User 2",
            /* displayName= */ null);
    assertThat(cfgSnapshot().getExemptedAccounts())
        .containsExactly(exemptedUser1.id(), exemptedUser2.id());
  }

  @Test
  @GerritConfig(
      name = "plugin.code-owners.exemptedUser",
      values = {"exempted-user-1@example.com", "exempted-user-2@example.com"})
  public void exemptedAccountsOnProjectLevelExtendsGloballyConfiguredExemptedAcounts()
      throws Exception {
    TestAccount exemptedUser1 =
        accountCreator.create(
            "exemptedUser1",
            "exempted-user-1@example.com",
            "Exempted User 1",
            /* displayName= */ null);
    TestAccount exemptedUser2 =
        accountCreator.create(
            "exemptedUser2",
            "exempted-user-2@example.com",
            "Exempted User 2",
            /* displayName= */ null);
    TestAccount exemptedUser3 =
        accountCreator.create(
            "exemptedUser3",
            "exempted-user-3@example.com",
            "Exempted User 3",
            /* displayName= */ null);
    TestAccount exemptedUser4 =
        accountCreator.create(
            "exemptedUser4",
            "exempted-user-4@example.com",
            "Exempted User 4",
            /* displayName= */ null);
    configureExemptedUsers(allProjects, exemptedUser3.email(), exemptedUser4.email());
    assertThat(cfgSnapshot().getExemptedAccounts())
        .containsExactly(
            exemptedUser1.id(), exemptedUser2.id(), exemptedUser3.id(), exemptedUser4.id());
  }

  @Test
  @GerritConfig(
      name = "plugin.code-owners.exemptedUser",
      values = {"exempted-user-1@example.com", "exempted-user-2@example.com"})
  public void
      exemptedAccountsOnProjectLevelExtendsGloballyConfiguredExemptedAcounts_duplicatesAreFilteredOut()
          throws Exception {
    TestAccount exemptedUser1 =
        accountCreator.create(
            "exemptedUser1",
            "exempted-user-1@example.com",
            "Exempted User 1",
            /* displayName= */ null);
    TestAccount exemptedUser2 =
        accountCreator.create(
            "exemptedUser2",
            "exempted-user-2@example.com",
            "Exempted User 2",
            /* displayName= */ null);
    TestAccount exemptedUser3 =
        accountCreator.create(
            "exemptedUser3",
            "exempted-user-3@example.com",
            "Exempted User 3",
            /* displayName= */ null);
    configureExemptedUsers(allProjects, exemptedUser1.email(), exemptedUser3.email());
    assertThat(cfgSnapshot().getExemptedAccounts())
        .containsExactly(exemptedUser1.id(), exemptedUser2.id(), exemptedUser3.id());
  }

  @Test
  @GerritConfig(
      name = "plugin.code-owners.exemptedUser",
      values = {"exempted-user-1@example.com", "exempted-user-2@example.com"})
  public void exemptedAccountsAreInheritedFromParentProject() throws Exception {
    TestAccount exemptedUser1 =
        accountCreator.create(
            "exemptedUser1",
            "exempted-user-1@example.com",
            "Exempted User 1",
            /* displayName= */ null);
    TestAccount exemptedUser2 =
        accountCreator.create(
            "exemptedUser2",
            "exempted-user-2@example.com",
            "Exempted User 2",
            /* displayName= */ null);
    TestAccount exemptedUser3 =
        accountCreator.create(
            "exemptedUser3",
            "exempted-user-3@example.com",
            "Exempted User 3",
            /* displayName= */ null);
    TestAccount exemptedUser4 =
        accountCreator.create(
            "exemptedUser4",
            "exempted-user-4@example.com",
            "Exempted User 4",
            /* displayName= */ null);
    configureExemptedUsers(allProjects, exemptedUser3.email(), exemptedUser4.email());
    assertThat(cfgSnapshot().getExemptedAccounts())
        .containsExactly(
            exemptedUser1.id(), exemptedUser2.id(), exemptedUser3.id(), exemptedUser4.id());
  }

  @Test
  @GerritConfig(
      name = "plugin.code-owners.exemptedUser",
      values = {"exempted-user-1@example.com", "exempted-user-2@example.com"})
  public void exemptedAccountsAreInheritedFromParentProject_duplicatesAreFilteredOut()
      throws Exception {
    TestAccount exemptedUser1 =
        accountCreator.create(
            "exemptedUser1",
            "exempted-user-1@example.com",
            "Exempted User 1",
            /* displayName= */ null);
    TestAccount exemptedUser2 =
        accountCreator.create(
            "exemptedUser2",
            "exempted-user-2@example.com",
            "Exempted User 2",
            /* displayName= */ null);
    TestAccount exemptedUser3 =
        accountCreator.create(
            "exemptedUser3",
            "exempted-user-3@example.com",
            "Exempted User 3",
            /* displayName= */ null);
    configureExemptedUsers(allProjects, exemptedUser1.email(), exemptedUser3.email());
    assertThat(cfgSnapshot().getExemptedAccounts())
        .containsExactly(exemptedUser1.id(), exemptedUser2.id(), exemptedUser3.id());
  }

  @Test
  public void inheritedExemptedAccountsCanBeExtended() throws Exception {
    TestAccount exemptedUser1 =
        accountCreator.create(
            "exemptedUser1",
            "exempted-user-1@example.com",
            "Exempted User 1",
            /* displayName= */ null);
    TestAccount exemptedUser2 =
        accountCreator.create(
            "exemptedUser2",
            "exempted-user-2@example.com",
            "Exempted User 2",
            /* displayName= */ null);
    TestAccount exemptedUser3 =
        accountCreator.create(
            "exemptedUser3",
            "exempted-user-3@example.com",
            "Exempted User 3",
            /* displayName= */ null);
    TestAccount exemptedUser4 =
        accountCreator.create(
            "exemptedUser4",
            "exempted-user-4@example.com",
            "Exempted User 4",
            /* displayName= */ null);
    configureExemptedUsers(allProjects, exemptedUser1.email(), exemptedUser2.email());
    configureExemptedUsers(project, exemptedUser3.email(), exemptedUser4.email());
    assertThat(cfgSnapshot().getExemptedAccounts())
        .containsExactly(
            exemptedUser1.id(), exemptedUser2.id(), exemptedUser3.id(), exemptedUser4.id());
  }

  @Test
  public void inheritedExemptedAccountsCanBeExtended_duplicatesAreFilteredOut() throws Exception {
    TestAccount exemptedUser1 =
        accountCreator.create(
            "exemptedUser1",
            "exempted-user-1@example.com",
            "Exempted User 1",
            /* displayName= */ null);
    TestAccount exemptedUser2 =
        accountCreator.create(
            "exemptedUser2",
            "exempted-user-2@example.com",
            "Exempted User 2",
            /* displayName= */ null);
    TestAccount exemptedUser3 =
        accountCreator.create(
            "exemptedUser3",
            "exempted-user-3@example.com",
            "Exempted User 3",
            /* displayName= */ null);
    configureExemptedUsers(allProjects, exemptedUser1.email(), exemptedUser2.email());
    configureExemptedUsers(project, exemptedUser1.email(), exemptedUser3.email());
    assertThat(cfgSnapshot().getExemptedAccounts())
        .containsExactly(exemptedUser1.id(), exemptedUser2.id(), exemptedUser3.id());
  }

  @Test
  public void inheritedExemptedAccountsCannotBeRemoved() throws Exception {
    TestAccount exemptedUser1 =
        accountCreator.create(
            "exemptedUser1",
            "exempted-user-1@example.com",
            "Exempted User 1",
            /* displayName= */ null);
    TestAccount exemptedUser2 =
        accountCreator.create(
            "exemptedUser2",
            "exempted-user-2@example.com",
            "Exempted User 2",
            /* displayName= */ null);
    configureExemptedUsers(allProjects, exemptedUser1.email(), exemptedUser2.email());
    configureExemptedUsers(project, "");
    assertThat(cfgSnapshot().getExemptedAccounts())
        .containsExactly(exemptedUser1.id(), exemptedUser2.id());
  }

  @Test
  public void nonResolvableExemptedAccountsAreIgnored() throws Exception {
    TestAccount exemptedUser =
        accountCreator.create(
            "exemptedUser", "exempted-user@example.com", "Exempted User", /* displayName= */ null);
    configureExemptedUsers(project, exemptedUser.email(), "non-resolveable@example.com");
    assertThat(cfgSnapshot().getExemptedAccounts()).containsExactly(exemptedUser.id());
  }

  @Test
  public void exemptedAccountsByIdAreIgnored() throws Exception {
    TestAccount exemptedUser1 =
        accountCreator.create(
            "exemptedUser1",
            "exempted-user-1@example.com",
            "Exempted User 1",
            /* displayName= */ null);
    TestAccount exemptedUser2 =
        accountCreator.create(
            "exemptedUser2",
            "exempted-user-2@example.com",
            "Exempted User 2",
            /* displayName= */ null);
    configureExemptedUsers(
        project, exemptedUser1.email(), Integer.toString(exemptedUser2.id().get()));
    assertThat(cfgSnapshot().getExemptedAccounts()).containsExactly(exemptedUser1.id());
  }

  @Test
  public void getMaxPathsInChangeMessagesIfNoneIsConfigured() throws Exception {
    assertThat(cfgSnapshot().getMaxPathsInChangeMessages())
        .isEqualTo(GeneralConfig.DEFAULT_MAX_PATHS_IN_CHANGE_MESSAGES);
  }

  @Test
  @GerritConfig(name = "plugin.code-owners.maxPathsInChangeMessages", value = "50")
  public void getMaxPathsInChangeMessagesIfNoneIsConfiguredOnProjectLevel() throws Exception {
    assertThat(cfgSnapshot().getMaxPathsInChangeMessages()).isEqualTo(50);
  }

  @Test
  @GerritConfig(name = "plugin.code-owners.maxPathsInChangeMessages", value = "50")
  public void maxPathInChangeMessagesOnProjectLevelOverridesGlobalMaxPathInChangeMessages()
      throws Exception {
    configureMaxPathsInChangeMessages(project, 20);
    assertThat(cfgSnapshot().getMaxPathsInChangeMessages()).isEqualTo(20);
  }

  @Test
  @GerritConfig(name = "plugin.code-owners.maxPathsInChangeMessages", value = "50")
  public void maxPathInChangeMessagesIsInheritedFromParentProject() throws Exception {
    configureMaxPathsInChangeMessages(allProjects, 20);
    assertThat(cfgSnapshot().getMaxPathsInChangeMessages()).isEqualTo(20);
  }

  @Test
  public void inheritedMaxPathInChangeMessagesCanBeOverridden() throws Exception {
    configureMaxPathsInChangeMessages(allProjects, 50);
    configureMaxPathsInChangeMessages(project, 20);
    assertThat(cfgSnapshot().getMaxPathsInChangeMessages()).isEqualTo(20);
  }

  @Test
  public void cannotCheckForNullBranchIfCodeOwnersFunctionalityIsDisabled() throws Exception {
    NullPointerException npe =
        assertThrows(
            NullPointerException.class, () -> cfgSnapshot().isDisabled(/* branchName= */ null));
    assertThat(npe).hasMessageThat().isEqualTo("branchName");
  }

  @Test
  public void checkIfCodeOwnersFunctionalityIsDisabledForNonExistingBranch() throws Exception {
    assertThat(cfgSnapshot().isDisabled("non-existing")).isFalse();
  }

  @Test
  public void checkIfCodeOwnersFunctionalityIsDisabledForProjectWithEmptyConfig() throws Exception {
    assertThat(cfgSnapshot().isDisabled()).isFalse();
  }

  @Test
  public void checkIfCodeOwnersFunctionalityIsDisabledForBranchWithEmptyConfig() throws Exception {
    assertThat(cfgSnapshot().isDisabled("master")).isFalse();
  }

  @Test
  public void codeOwnersFunctionalityIsDisabledForProject() throws Exception {
    disableCodeOwnersForProject(project);
    assertThat(cfgSnapshot().isDisabled()).isTrue();
  }

  @Test
  public void codeOwnersFunctionalityIsDisabledForBranchIfItIsDisabledForProject()
      throws Exception {
    disableCodeOwnersForProject(project);
    assertThat(cfgSnapshot().isDisabled("master")).isTrue();
  }

  @Test
  public void codeOwnersFunctionalityIsDisabledForBranch_exactRef() throws Exception {
    configureDisabledBranch(project, "refs/heads/master");
    assertThat(cfgSnapshot().isDisabled("master")).isTrue();
    assertThat(cfgSnapshot().isDisabled("other")).isFalse();
  }

  @Test
  public void codeOwnersFunctionalityIsDisabledForBranch_refPattern() throws Exception {
    configureDisabledBranch(project, "refs/heads/*");
    assertThat(cfgSnapshot().isDisabled("master")).isTrue();
    assertThat(cfgSnapshot().isDisabled("other")).isTrue();
    assertThat(cfgSnapshot().isDisabled(RefNames.REFS_META)).isFalse();
  }

  @Test
  public void codeOwnersFunctionalityIsDisabledForBranch_regularExpression() throws Exception {
    configureDisabledBranch(project, "^refs/heads/.*");
    assertThat(cfgSnapshot().isDisabled("master")).isTrue();
    assertThat(cfgSnapshot().isDisabled("other")).isTrue();
    assertThat(cfgSnapshot().isDisabled(RefNames.REFS_META)).isFalse();
  }

  @Test
  public void codeOwnersFunctionalityIsDisabledForBranch_invalidRegularExpression()
      throws Exception {
    configureDisabledBranch(project, "^refs/heads/[");
    assertThat(cfgSnapshot().isDisabled("master")).isFalse();
  }

  @Test
  public void disabledIsInheritedFromParentProject() throws Exception {
    disableCodeOwnersForProject(allProjects);
    assertThat(cfgSnapshot().isDisabled()).isTrue();
  }

  @Test
  public void inheritedDisabledAlsoCountsForBranch() throws Exception {
    disableCodeOwnersForProject(allProjects);
    assertThat(cfgSnapshot().isDisabled("master")).isTrue();
  }

  @Test
  public void inheritedDisabledValueIsIgnoredIfInvalid() throws Exception {
    configureDisabled(project, "invalid");
    assertThat(cfgSnapshot().isDisabled()).isFalse();
  }

  @Test
  public void inheritedDisabledValueIsIgnoredForBranchIfInvalid() throws Exception {
    configureDisabled(project, "invalid");
    assertThat(cfgSnapshot().isDisabled("master")).isFalse();
  }

  @Test
  public void disabledForOtherProjectHasNoEffect() throws Exception {
    Project.NameKey otherProject = projectOperations.newProject().create();
    disableCodeOwnersForProject(otherProject);
    assertThat(cfgSnapshot().isDisabled()).isFalse();
  }

  @Test
  public void disabledBranchForOtherProjectHasNoEffect() throws Exception {
    Project.NameKey otherProject = projectOperations.newProject().create();
    configureDisabledBranch(otherProject, "refs/heads/master");
    assertThat(cfgSnapshot().isDisabled("master")).isFalse();
  }

  @Test
  public void disabledBranchIsInheritedFromParentProject() throws Exception {
    configureDisabledBranch(allProjects, "refs/heads/master");
    assertThat(cfgSnapshot().isDisabled("master")).isTrue();
  }

  @Test
  public void inheritedDisabledCanBeOverridden() throws Exception {
    disableCodeOwnersForProject(allProjects);
    enableCodeOwnersForProject(project);
    assertThat(cfgSnapshot().isDisabled("master")).isFalse();
  }

  @Test
  public void inheritedDisabledBranchCanBeExtended() throws Exception {
    configureDisabledBranch(allProjects, "refs/heads/master");
    configureDisabledBranch(project, "refs/heads/test");
    assertThat(cfgSnapshot().isDisabled("master")).isTrue();
    assertThat(cfgSnapshot().isDisabled("test")).isTrue();
  }

  @Test
  public void inheritedDisabledBranchCannotBeRemoved() throws Exception {
    configureDisabledBranch(allProjects, "refs/heads/master");

    // trying to override the inherited config with an empty value to enable code owners for all
    // branches doesn't work because the empty string is added to the inherited value list so that
    // disabledBranch is ["refs/heads/master", ""] now
    configureDisabledBranch(project, "");

    assertThat(cfgSnapshot().isDisabled("master")).isTrue();
  }

  @Test
  public void cannotGetBackendForNullBranch() throws Exception {
    NullPointerException npe =
        assertThrows(
            NullPointerException.class, () -> cfgSnapshot().getBackend(/* branchName= */ null));
    assertThat(npe).hasMessageThat().isEqualTo("branchName");
  }

  @Test
  public void getBackendForNonExistingBranch() throws Exception {
    assertThat(cfgSnapshot().getBackend("non-existing")).isInstanceOf(FindOwnersBackend.class);
  }

  @Test
  public void getDefaultBackendWhenNoBackendIsConfigured() throws Exception {
    assertThat(cfgSnapshot().getBackend("master")).isInstanceOf(FindOwnersBackend.class);
  }

  @Test
  @GerritConfig(name = "plugin.code-owners.backend", value = TestCodeOwnerBackend.ID)
  public void getConfiguredDefaultBackend() throws Exception {
    try (AutoCloseable registration = registerTestBackend()) {
      assertThat(cfgSnapshot().getBackend("master")).isInstanceOf(TestCodeOwnerBackend.class);
    }
  }

  @Test
  @GerritConfig(name = "plugin.code-owners.backend", value = "non-existing-backend")
  public void cannotGetBackendIfNonExistingBackendIsConfigured() throws Exception {
    InvalidPluginConfigurationException exception =
        assertThrows(
            InvalidPluginConfigurationException.class, () -> cfgSnapshot().getBackend("master"));
    assertThat(exception)
        .hasMessageThat()
        .isEqualTo(
            "Invalid configuration of the code-owners plugin. Code owner backend"
                + " 'non-existing-backend' that is configured in gerrit.config (parameter"
                + " plugin.code-owners.backend) not found.");
  }

  @Test
  public void getBackendConfiguredOnProjectLevel() throws Exception {
    configureBackend(project, TestCodeOwnerBackend.ID);
    try (AutoCloseable registration = registerTestBackend()) {
      assertThat(cfgSnapshot().getBackend("master")).isInstanceOf(TestCodeOwnerBackend.class);
    }
  }

  @Test
  @GerritConfig(name = "plugin.code-owners.backend", value = FindOwnersBackend.ID)
  public void backendConfiguredOnProjectLevelOverridesDefaultBackend() throws Exception {
    configureBackend(project, TestCodeOwnerBackend.ID);
    try (AutoCloseable registration = registerTestBackend()) {
      assertThat(cfgSnapshot().getBackend("master")).isInstanceOf(TestCodeOwnerBackend.class);
    }
  }

  @Test
  public void backendIsInheritedFromParentProject() throws Exception {
    configureBackend(allProjects, TestCodeOwnerBackend.ID);
    try (AutoCloseable registration = registerTestBackend()) {
      assertThat(cfgSnapshot().getBackend("master")).isInstanceOf(TestCodeOwnerBackend.class);
    }
  }

  @Test
  @GerritConfig(name = "plugin.code-owners.backend", value = FindOwnersBackend.ID)
  public void inheritedBackendOverridesDefaultBackend() throws Exception {
    configureBackend(allProjects, TestCodeOwnerBackend.ID);
    try (AutoCloseable registration = registerTestBackend()) {
      assertThat(cfgSnapshot().getBackend("master")).isInstanceOf(TestCodeOwnerBackend.class);
    }
  }

  @Test
  public void projectLevelBackendOverridesInheritedBackend() throws Exception {
    configureBackend(allProjects, TestCodeOwnerBackend.ID);
    configureBackend(project, FindOwnersBackend.ID);
    try (AutoCloseable registration = registerTestBackend()) {
      assertThat(cfgSnapshot().getBackend("master")).isInstanceOf(FindOwnersBackend.class);
    }
  }

  @Test
  public void cannotGetBackendIfNonExistingBackendIsConfiguredOnProjectLevel() throws Exception {
    configureBackend(project, "non-existing-backend");
    InvalidPluginConfigurationException exception =
        assertThrows(
            InvalidPluginConfigurationException.class, () -> cfgSnapshot().getBackend("master"));
    assertThat(exception)
        .hasMessageThat()
        .isEqualTo(
            String.format(
                "Invalid configuration of the code-owners plugin. Code owner backend"
                    + " 'non-existing-backend' that is configured for project %s in"
                    + " code-owners.config (parameter codeOwners.backend) not found.",
                project));
  }

  @Test
  public void projectLevelBackendForOtherProjectHasNoEffect() throws Exception {
    Project.NameKey otherProject = projectOperations.newProject().create();
    configureBackend(otherProject, TestCodeOwnerBackend.ID);
    try (AutoCloseable registration = registerTestBackend()) {
      assertThat(cfgSnapshot().getBackend("master")).isInstanceOf(FindOwnersBackend.class);
    }
  }

  @Test
  public void getBackendConfiguredOnBranchLevel() throws Exception {
    configureBackend(project, "refs/heads/master", TestCodeOwnerBackend.ID);
    try (AutoCloseable registration = registerTestBackend()) {
      assertThat(cfgSnapshot().getBackend("master")).isInstanceOf(TestCodeOwnerBackend.class);
    }
  }

  @Test
  public void getBackendConfiguredOnBranchLevelShortName() throws Exception {
    configureBackend(project, "master", TestCodeOwnerBackend.ID);
    try (AutoCloseable registration = registerTestBackend()) {
      assertThat(cfgSnapshot().getBackend("master")).isInstanceOf(TestCodeOwnerBackend.class);
    }
  }

  @Test
  public void branchLevelBackendOnFullNameTakesPrecedenceOverBranchLevelBackendOnShortName()
      throws Exception {
    configureBackend(project, "master", TestCodeOwnerBackend.ID);
    configureBackend(project, "refs/heads/master", FindOwnersBackend.ID);
    try (AutoCloseable registration = registerTestBackend()) {
      assertThat(cfgSnapshot().getBackend("master")).isInstanceOf(FindOwnersBackend.class);
    }
  }

  @Test
  public void branchLevelBackendOverridesProjectLevelBackend() throws Exception {
    configureBackend(project, TestCodeOwnerBackend.ID);
    configureBackend(project, "master", FindOwnersBackend.ID);
    try (AutoCloseable registration = registerTestBackend()) {
      assertThat(cfgSnapshot().getBackend("master")).isInstanceOf(FindOwnersBackend.class);
    }
  }

  @Test
  public void cannotGetBackendIfNonExistingBackendIsConfiguredOnBranchLevel() throws Exception {
    configureBackend(project, "master", "non-existing-backend");
    InvalidPluginConfigurationException exception =
        assertThrows(
            InvalidPluginConfigurationException.class, () -> cfgSnapshot().getBackend("master"));
    assertThat(exception)
        .hasMessageThat()
        .isEqualTo(
            String.format(
                "Invalid configuration of the code-owners plugin. Code owner backend"
                    + " 'non-existing-backend' that is configured for project %s in"
                    + " code-owners.config (parameter codeOwners.master.backend) not found.",
                project));
  }

  @Test
  public void branchLevelBackendForOtherBranchHasNoEffect() throws Exception {
    configureBackend(project, "foo", TestCodeOwnerBackend.ID);
    try (AutoCloseable registration = registerTestBackend()) {
      assertThat(cfgSnapshot().getBackend("master")).isInstanceOf(FindOwnersBackend.class);
    }
  }

  @Test
  public void getDefaultRequiredApprovalWhenNoRequiredApprovalIsConfigured() throws Exception {
    RequiredApproval requiredApproval = cfgSnapshot().getRequiredApproval();
    assertThat(requiredApproval).hasLabelNameThat().isEqualTo(RequiredApprovalConfig.DEFAULT_LABEL);
    assertThat(requiredApproval).hasValueThat().isEqualTo(RequiredApprovalConfig.DEFAULT_VALUE);
  }

  @Test
  @GerritConfig(name = "plugin.code-owners.requiredApproval", value = "Code-Review+2")
  public void getGloballyConfiguredRequiredApproval() throws Exception {
    RequiredApproval requiredApproval = cfgSnapshot().getRequiredApproval();
    assertThat(requiredApproval).hasLabelNameThat().isEqualTo("Code-Review");
    assertThat(requiredApproval).hasValueThat().isEqualTo(2);
  }

  @Test
  @GerritConfig(name = "plugin.code-owners.requiredApproval", value = "Foo-Bar+1")
  public void cannotGetRequiredApprovalIfNonExistingLabelIsConfiguredAsRequiredApproval()
      throws Exception {
    InvalidPluginConfigurationException exception =
        assertThrows(
            InvalidPluginConfigurationException.class, () -> cfgSnapshot().getRequiredApproval());
    assertThat(exception)
        .hasMessageThat()
        .isEqualTo(
            String.format(
                "Invalid configuration of the code-owners plugin. Required approval 'Foo-Bar+1'"
                    + " that is configured in gerrit.config (parameter"
                    + " plugin.code-owners.requiredApproval) is invalid: Label Foo-Bar doesn't exist"
                    + " for project %s.",
                project.get()));
  }

  @Test
  @GerritConfig(name = "plugin.code-owners.requiredApproval", value = "Code-Review+3")
  public void cannotGetRequiredApprovalIfNonExistingLabelValueIsConfiguredAsRequiredApproval()
      throws Exception {
    InvalidPluginConfigurationException exception =
        assertThrows(
            InvalidPluginConfigurationException.class, () -> cfgSnapshot().getRequiredApproval());
    assertThat(exception)
        .hasMessageThat()
        .isEqualTo(
            String.format(
                "Invalid configuration of the code-owners plugin. Required approval"
                    + " 'Code-Review+3' that is configured in gerrit.config (parameter"
                    + " plugin.code-owners.requiredApproval) is invalid: Label Code-Review on"
                    + " project %s doesn't allow value 3.",
                project.get()));
  }

  @Test
  @GerritConfig(name = "plugin.code-owners.requiredApproval", value = "INVALID")
  public void cannotGetRequiredApprovalIfInvalidRequiredApprovalIsConfigured() throws Exception {
    InvalidPluginConfigurationException exception =
        assertThrows(
            InvalidPluginConfigurationException.class, () -> cfgSnapshot().getRequiredApproval());
    assertThat(exception)
        .hasMessageThat()
        .isEqualTo(
            "Invalid configuration of the code-owners plugin. Required approval 'INVALID' that is"
                + " configured in gerrit.config (parameter plugin.code-owners.requiredApproval) is"
                + " invalid: Invalid format, expected '<label-name>+<label-value>'.");
  }

  @Test
  public void getRequiredApprovalConfiguredOnProjectLevel() throws Exception {
    configureRequiredApproval(project, "Code-Review+2");
    RequiredApproval requiredApproval = cfgSnapshot().getRequiredApproval();
    assertThat(requiredApproval).hasLabelNameThat().isEqualTo("Code-Review");
    assertThat(requiredApproval).hasValueThat().isEqualTo(2);
  }

  @Test
  public void getRequiredApprovalMultipleConfiguredOnProjectLevel() throws Exception {
    setCodeOwnersConfig(
        project,
        /* subsection= */ null,
        RequiredApprovalConfig.KEY_REQUIRED_APPROVAL,
        ImmutableList.of("Code-Review+2", "Code-Review+1"));

    // If multiple values are set for a key, the last value wins.
    RequiredApproval requiredApproval = cfgSnapshot().getRequiredApproval();
    assertThat(requiredApproval).hasLabelNameThat().isEqualTo("Code-Review");
    assertThat(requiredApproval).hasValueThat().isEqualTo(1);
  }

  @Test
  @GerritConfig(name = "plugin.code-owners.requiredApproval", value = "Code-Review+1")
  public void requiredApprovalConfiguredOnProjectLevelOverridesGloballyConfiguredRequiredApproval()
      throws Exception {
    configureRequiredApproval(project, "Code-Review+2");
    RequiredApproval requiredApproval = cfgSnapshot().getRequiredApproval();
    assertThat(requiredApproval).hasLabelNameThat().isEqualTo("Code-Review");
    assertThat(requiredApproval).hasValueThat().isEqualTo(2);
  }

  @Test
  public void requiredApprovalIsInheritedFromParentProject() throws Exception {
    configureRequiredApproval(allProjects, "Code-Review+2");
    RequiredApproval requiredApproval = cfgSnapshot().getRequiredApproval();
    assertThat(requiredApproval).hasLabelNameThat().isEqualTo("Code-Review");
    assertThat(requiredApproval).hasValueThat().isEqualTo(2);
  }

  @Test
  @GerritConfig(name = "plugin.code-owners.requiredApproval", value = "Code-Review+1")
  public void inheritedRequiredApprovalOverridesGloballyConfiguredRequiredApproval()
      throws Exception {
    configureRequiredApproval(allProjects, "Code-Review+2");
    RequiredApproval requiredApproval = cfgSnapshot().getRequiredApproval();
    assertThat(requiredApproval).hasLabelNameThat().isEqualTo("Code-Review");
    assertThat(requiredApproval).hasValueThat().isEqualTo(2);
  }

  @Test
  public void projectLevelRequiredApprovalOverridesInheritedRequiredApproval() throws Exception {
    configureRequiredApproval(allProjects, "Code-Review+1");
    configureRequiredApproval(project, "Code-Review+2");
    RequiredApproval requiredApproval = cfgSnapshot().getRequiredApproval();
    assertThat(requiredApproval).hasLabelNameThat().isEqualTo("Code-Review");
    assertThat(requiredApproval).hasValueThat().isEqualTo(2);
  }

  @Test
  public void
      cannotGetRequiredApprovalIfNonExistingLabelIsConfiguredAsRequiredApprovalOnProjectLevel()
          throws Exception {
    configureRequiredApproval(project, "Foo-Bar+1");
    InvalidPluginConfigurationException exception =
        assertThrows(
            InvalidPluginConfigurationException.class, () -> cfgSnapshot().getRequiredApproval());
    assertThat(exception)
        .hasMessageThat()
        .isEqualTo(
            String.format(
                "Invalid configuration of the code-owners plugin. Required approval 'Foo-Bar+1'"
                    + " that is configured in code-owners.config (parameter"
                    + " codeOwners.requiredApproval) is invalid: Label Foo-Bar doesn't exist for"
                    + " project %s.",
                project.get()));
  }

  @Test
  public void
      cannotGetRequiredApprovalIfNonExistingLabelValueIsConfiguredAsRequiredApprovalOnProjectLevel()
          throws Exception {
    configureRequiredApproval(project, "Code-Review+3");
    InvalidPluginConfigurationException exception =
        assertThrows(
            InvalidPluginConfigurationException.class, () -> cfgSnapshot().getRequiredApproval());
    assertThat(exception)
        .hasMessageThat()
        .isEqualTo(
            String.format(
                "Invalid configuration of the code-owners plugin. Required approval"
                    + " 'Code-Review+3' that is configured in code-owners.config (parameter"
                    + " codeOwners.requiredApproval) is invalid: Label Code-Review on project %s"
                    + " doesn't allow value 3.",
                project.get()));
  }

  @Test
  public void cannotGetRequiredApprovalIfInvalidRequiredApprovalIsConfiguredOnProjectLevel()
      throws Exception {
    configureRequiredApproval(project, "INVALID");
    InvalidPluginConfigurationException exception =
        assertThrows(
            InvalidPluginConfigurationException.class, () -> cfgSnapshot().getRequiredApproval());
    assertThat(exception)
        .hasMessageThat()
        .isEqualTo(
            "Invalid configuration of the code-owners plugin. Required approval 'INVALID' that is"
                + " configured in code-owners.config (parameter codeOwners.requiredApproval) is"
                + " invalid: Invalid format, expected '<label-name>+<label-value>'.");
  }

  @Test
  public void projectLevelRequiredApprovalForOtherProjectHasNoEffect() throws Exception {
    Project.NameKey otherProject = projectOperations.newProject().create();
    configureRequiredApproval(otherProject, "Code-Review+2");
    RequiredApproval requiredApproval = cfgSnapshot().getRequiredApproval();
    assertThat(requiredApproval).hasLabelNameThat().isEqualTo("Code-Review");
    assertThat(requiredApproval).hasValueThat().isEqualTo(1);
  }

  @Test
  public void getOverrideApprovalWhenNoRequiredApprovalIsConfigured() throws Exception {
    assertThat(cfgSnapshot().getOverrideApprovals()).isEmpty();
  }

  @Test
  @GerritConfig(name = "plugin.code-owners.overrideApproval", value = "Owners-Override+1")
  public void getConfiguredOverrideApproval() throws Exception {
    createOwnersOverrideLabel();
    ImmutableSortedSet<RequiredApproval> requiredApprovals = cfgSnapshot().getOverrideApprovals();
    assertThat(requiredApprovals).hasSize(1);
    assertThat(requiredApprovals).element(0).hasLabelNameThat().isEqualTo("Owners-Override");
    assertThat(requiredApprovals).element(0).hasValueThat().isEqualTo(1);
  }

  @Test
  @GerritConfig(name = "plugin.code-owners.overrideApproval", value = "Foo-Bar+1")
  public void getOverrideApprovalIfNonExistingLabelIsConfiguredAsOverrideApproval()
      throws Exception {
    assertThat(cfgSnapshot().getOverrideApprovals()).isEmpty();
  }

  @Test
  @GerritConfig(name = "plugin.code-owners.overrideApproval", value = "Code-Review+3")
  public void getOverrideApprovalIfNonExistingLabelValueIsConfiguredAsOverrideApproval()
      throws Exception {
    assertThat(cfgSnapshot().getOverrideApprovals()).isEmpty();
  }

  @Test
  @GerritConfig(name = "plugin.code-owners.overrideApproval", value = "INVALID")
  public void getOverrideApprovalIfInvalidOverrideApprovalIsConfigured() throws Exception {
    assertThat(cfgSnapshot().getOverrideApprovals()).isEmpty();
  }

  @Test
  public void getOverrideApprovalConfiguredOnProjectLevel() throws Exception {
    createOwnersOverrideLabel();
    configureOverrideApproval(project, "Owners-Override+1");
    ImmutableSortedSet<RequiredApproval> requiredApprovals = cfgSnapshot().getOverrideApprovals();
    assertThat(requiredApprovals).hasSize(1);
    assertThat(requiredApprovals).element(0).hasLabelNameThat().isEqualTo("Owners-Override");
    assertThat(requiredApprovals).element(0).hasValueThat().isEqualTo(1);
  }

  @Test
  public void getOverrideApprovalMultipleConfiguredOnProjectLevel() throws Exception {
    createOwnersOverrideLabel();
    createOwnersOverrideLabel("Other-Override");

    setCodeOwnersConfig(
        project,
        /* subsection= */ null,
        OverrideApprovalConfig.KEY_OVERRIDE_APPROVAL,
        ImmutableList.of("Owners-Override+1", "Other-Override+1"));

    ImmutableSet<RequiredApproval> requiredApprovals = cfgSnapshot().getOverrideApprovals();
    assertThat(
            requiredApprovals.stream()
                .map(requiredApproval -> requiredApproval.toString())
                .collect(toImmutableSet()))
        .containsExactly("Owners-Override+1", "Other-Override+1");
  }

  @Test
  @GerritConfig(name = "plugin.code-owners.overrideApproval", value = "Owners-Override+1")
  public void overrideApprovalConfiguredOnProjectLevelExtendsGloballyConfiguredOverrideApproval()
      throws Exception {
    createOwnersOverrideLabel();
    createOwnersOverrideLabel("Other-Override");

    configureOverrideApproval(project, "Other-Override+1");
    ImmutableSortedSet<RequiredApproval> requiredApprovals = cfgSnapshot().getOverrideApprovals();
    assertThat(requiredApprovals).hasSize(2);
    assertThat(requiredApprovals).element(0).hasLabelNameThat().isEqualTo("Other-Override");
    assertThat(requiredApprovals).element(0).hasValueThat().isEqualTo(1);
    assertThat(requiredApprovals).element(1).hasLabelNameThat().isEqualTo("Owners-Override");
    assertThat(requiredApprovals).element(1).hasValueThat().isEqualTo(1);
  }

  @Test
  @GerritConfig(name = "plugin.code-owners.overrideApproval", value = "Owners-Override+1")
  public void
      overrideApprovalConfiguredOnProjectLevelExtendsGloballyConfiguredOverrideApproval_duplicatesAreFilteredOut()
          throws Exception {
    createOwnersOverrideLabel();

    configureOverrideApproval(project, "Owners-Override+1");
    ImmutableSortedSet<RequiredApproval> requiredApprovals = cfgSnapshot().getOverrideApprovals();
    assertThat(requiredApprovals).hasSize(1);
    assertThat(requiredApprovals).element(0).hasLabelNameThat().isEqualTo("Owners-Override");
    assertThat(requiredApprovals).element(0).hasValueThat().isEqualTo(1);
  }

  @Test
  public void overrideApprovalIsInheritedFromParentProject() throws Exception {
    createOwnersOverrideLabel();

    configureOverrideApproval(allProjects, "Owners-Override+1");
    ImmutableSortedSet<RequiredApproval> requiredApprovals = cfgSnapshot().getOverrideApprovals();
    assertThat(requiredApprovals).hasSize(1);
    assertThat(requiredApprovals).element(0).hasLabelNameThat().isEqualTo("Owners-Override");
    assertThat(requiredApprovals).element(0).hasValueThat().isEqualTo(1);
  }

  @Test
  @GerritConfig(name = "plugin.code-owners.overrideApproval", value = "Owners-Override+1")
  public void inheritedOverrideApprovalExtendsGloballyConfiguredOverrideApproval()
      throws Exception {
    createOwnersOverrideLabel();
    createOwnersOverrideLabel("Other-Override");

    configureOverrideApproval(allProjects, "Other-Override+1");
    ImmutableSortedSet<RequiredApproval> requiredApprovals = cfgSnapshot().getOverrideApprovals();
    assertThat(requiredApprovals).hasSize(2);
    assertThat(requiredApprovals).element(0).hasLabelNameThat().isEqualTo("Other-Override");
    assertThat(requiredApprovals).element(0).hasValueThat().isEqualTo(1);
    assertThat(requiredApprovals).element(1).hasLabelNameThat().isEqualTo("Owners-Override");
    assertThat(requiredApprovals).element(1).hasValueThat().isEqualTo(1);
  }

  @Test
  @GerritConfig(name = "plugin.code-owners.overrideApproval", value = "Owners-Override+1")
  public void
      inheritedOverrideApprovalExtendsGloballyConfiguredOverrideApproval_duplicatesAreFilteredOut()
          throws Exception {
    createOwnersOverrideLabel();

    configureOverrideApproval(allProjects, "Owners-Override+1");
    ImmutableSortedSet<RequiredApproval> requiredApprovals = cfgSnapshot().getOverrideApprovals();
    assertThat(requiredApprovals).hasSize(1);
    assertThat(requiredApprovals).element(0).hasLabelNameThat().isEqualTo("Owners-Override");
    assertThat(requiredApprovals).element(0).hasValueThat().isEqualTo(1);
  }

  @Test
  public void projectLevelOverrideApprovalExtendsInheritedOverrideApproval() throws Exception {
    createOwnersOverrideLabel();
    createOwnersOverrideLabel("Other-Override");

    configureOverrideApproval(allProjects, "Owners-Override+1");
    configureOverrideApproval(project, "Other-Override+1");
    ImmutableSortedSet<RequiredApproval> requiredApprovals = cfgSnapshot().getOverrideApprovals();
    assertThat(requiredApprovals).hasSize(2);
    assertThat(requiredApprovals).element(0).hasLabelNameThat().isEqualTo("Other-Override");
    assertThat(requiredApprovals).element(0).hasValueThat().isEqualTo(1);
    assertThat(requiredApprovals).element(1).hasLabelNameThat().isEqualTo("Owners-Override");
    assertThat(requiredApprovals).element(1).hasValueThat().isEqualTo(1);
  }

  @Test
  public void
      projectLevelOverrideApprovalExtendsInheritedOverrideApproval_duplicatesAreFilteredOut()
          throws Exception {
    createOwnersOverrideLabel();

    configureOverrideApproval(allProjects, "Owners-Override+1");
    configureOverrideApproval(project, "Owners-Override+1");
    ImmutableSortedSet<RequiredApproval> requiredApprovals = cfgSnapshot().getOverrideApprovals();
    assertThat(requiredApprovals).hasSize(1);
    assertThat(requiredApprovals).element(0).hasLabelNameThat().isEqualTo("Owners-Override");
    assertThat(requiredApprovals).element(0).hasValueThat().isEqualTo(1);
  }

  @Test
  public void projectLevelOverrideApprovalExtendsInheritedOverrideApprovalWithDifferentLabelValue()
      throws Exception {
    LabelDefinitionInput input = new LabelDefinitionInput();
    input.values = ImmutableMap.of("+2", "Super-Override", "+1", "Override", " 0", "No Override");
    gApi.projects().name(project.get()).label("Owners-Override").create(input).get();

    configureOverrideApproval(allProjects, "Owners-Override+1");
    configureOverrideApproval(project, "Owners-Override+2");

    // if the same label is configured multiple times as override approval, only the definition with
    // the lowest value is returned (since all higher values are implicitly considered as overrides
    // as well)
    ImmutableSortedSet<RequiredApproval> requiredApprovals = cfgSnapshot().getOverrideApprovals();
    assertThat(requiredApprovals).hasSize(1);
    assertThat(requiredApprovals).element(0).hasLabelNameThat().isEqualTo("Owners-Override");
    assertThat(requiredApprovals).element(0).hasValueThat().isEqualTo(1);
  }

  @Test
  public void getOverrideApprovalIfNonExistingLabelIsConfiguredAsOverrideApprovalOnProjectLevel()
      throws Exception {
    configureOverrideApproval(project, "Foo-Bar+1");
    assertThat(cfgSnapshot().getOverrideApprovals()).isEmpty();
  }

  @Test
  public void
      getOverrideApprovalIfNonExistingLabelValueIsConfiguredAsOverrideApprovalOnProjectLevel()
          throws Exception {
    createOwnersOverrideLabel();
    configureOverrideApproval(project, "Owners-Override+2");
    assertThat(cfgSnapshot().getOverrideApprovals()).isEmpty();
  }

  @Test
  public void getOverrideApprovalIfInvalidOverrideApprovalIsConfiguredOnProjectLevel()
      throws Exception {
    configureOverrideApproval(project, "INVALID");
    assertThat(cfgSnapshot().getOverrideApprovals()).isEmpty();
  }

  @Test
  public void projectLevelOverrideApprovalForOtherProjectHasNoEffect() throws Exception {
    createOwnersOverrideLabel();
    Project.NameKey otherProject = projectOperations.newProject().create();
    configureOverrideApproval(otherProject, "Owners-Override+1");
    assertThat(cfgSnapshot().getOverrideApprovals()).isEmpty();
  }

  @Test
  public void getOverrideApprovalDuplicatesAreFilteredOut() throws Exception {
    setCodeOwnersConfig(
        project,
        /* subsection= */ null,
        OverrideApprovalConfig.KEY_OVERRIDE_APPROVAL,
        ImmutableList.of("Code-Review+2", "Code-Review+1", "Code-Review+2"));

    // If multiple values are set for a key, the last value wins.
    ImmutableSortedSet<RequiredApproval> requiredApprovals = cfgSnapshot().getOverrideApprovals();
    assertThat(requiredApprovals).hasSize(1);
    assertThat(requiredApprovals).element(0).hasLabelNameThat().isEqualTo("Code-Review");
    assertThat(requiredApprovals).element(0).hasValueThat().isEqualTo(1);
  }

  @Test
  @GerritConfig(name = "plugin.code-owners.enableImplicitApprovals", value = "true")
  public void implicitApprovalsAreDisabledIfRequiredLabelIgnoresSelfApprovals() throws Exception {
    assertThat(cfgSnapshot().areImplicitApprovalsEnabled()).isTrue();

    LabelDefinitionInput input = new LabelDefinitionInput();
    input.ignoreSelfApproval = true;
    gApi.projects().name(allProjects.get()).label("Code-Review").update(input);
    assertThat(cfgSnapshot().areImplicitApprovalsEnabled()).isFalse();
  }

  @Test
  public void cannotGetCodeOwnerConfigValidationPolicyForCommitReceivedForNullBranch()
      throws Exception {
    NullPointerException npe =
        assertThrows(
            NullPointerException.class,
            () ->
                cfgSnapshot()
                    .getCodeOwnerConfigValidationPolicyForCommitReceived(/* branchName= */ null));
    assertThat(npe).hasMessageThat().isEqualTo("branchName");
  }

  @Test
  public void getCodeOwnerConfigValidationPolicyForCommitReceived_notConfigured() throws Exception {
    assertThat(cfgSnapshot().getCodeOwnerConfigValidationPolicyForCommitReceived("master"))
        .isEqualTo(CodeOwnerConfigValidationPolicy.TRUE);
    assertThat(cfgSnapshot().getCodeOwnerConfigValidationPolicyForCommitReceived("non-existing"))
        .isEqualTo(CodeOwnerConfigValidationPolicy.TRUE);
  }

  @Test
  public void getCodeOwnerConfigValidationPolicyForCommitReceived_configuredOnProjectLevel()
      throws Exception {
    configureEnableValidationOnCommitReceived(project, CodeOwnerConfigValidationPolicy.FALSE);
    assertThat(cfgSnapshot().getCodeOwnerConfigValidationPolicyForCommitReceived("master"))
        .isEqualTo(CodeOwnerConfigValidationPolicy.FALSE);
    assertThat(cfgSnapshot().getCodeOwnerConfigValidationPolicyForCommitReceived("non-existing"))
        .isEqualTo(CodeOwnerConfigValidationPolicy.FALSE);
  }

  @Test
  public void getCodeOwnerConfigValidationPolicyForCommitReceived_configuredOnBranchLevel()
      throws Exception {
    configureEnableValidationOnCommitReceivedForBranch(
        project, "refs/heads/master", CodeOwnerConfigValidationPolicy.FALSE);
    assertThat(cfgSnapshot().getCodeOwnerConfigValidationPolicyForCommitReceived("master"))
        .isEqualTo(CodeOwnerConfigValidationPolicy.FALSE);
    assertThat(
            cfgSnapshot().getCodeOwnerConfigValidationPolicyForCommitReceived("refs/heads/master"))
        .isEqualTo(CodeOwnerConfigValidationPolicy.FALSE);
    assertThat(cfgSnapshot().getCodeOwnerConfigValidationPolicyForCommitReceived("foo"))
        .isEqualTo(CodeOwnerConfigValidationPolicy.TRUE);
  }

  @Test
  public void getCodeOwnerConfigValidationPolicyForCommitReceived_branchLevelConfigTakesPrecedence()
      throws Exception {
    updateCodeOwnersConfig(
        project,
        codeOwnersConfig -> {
          codeOwnersConfig.setEnum(
              CodeOwnersPluginConfiguration.SECTION_CODE_OWNERS,
              /* subsection= */ null,
              GeneralConfig.KEY_ENABLE_VALIDATION_ON_COMMIT_RECEIVED,
              CodeOwnerConfigValidationPolicy.DRY_RUN);
          codeOwnersConfig.setEnum(
              GeneralConfig.SECTION_VALIDATION,
              "refs/heads/master",
              GeneralConfig.KEY_ENABLE_VALIDATION_ON_COMMIT_RECEIVED,
              CodeOwnerConfigValidationPolicy.FALSE);
        });
    assertThat(cfgSnapshot().getCodeOwnerConfigValidationPolicyForCommitReceived("master"))
        .isEqualTo(CodeOwnerConfigValidationPolicy.FALSE);
    assertThat(
            cfgSnapshot().getCodeOwnerConfigValidationPolicyForCommitReceived("refs/heads/master"))
        .isEqualTo(CodeOwnerConfigValidationPolicy.FALSE);
    assertThat(cfgSnapshot().getCodeOwnerConfigValidationPolicyForCommitReceived("foo"))
        .isEqualTo(CodeOwnerConfigValidationPolicy.DRY_RUN);
  }

  @Test
  public void
      getCodeOwnerConfigValidationPolicyForCommitReceived_inheritedBranchLevelConfigTakesPrecedence()
          throws Exception {
    configureEnableValidationOnCommitReceivedForBranch(
        allProjects, "refs/heads/master", CodeOwnerConfigValidationPolicy.FALSE);
    configureEnableValidationOnCommitReceived(project, CodeOwnerConfigValidationPolicy.DRY_RUN);
    assertThat(cfgSnapshot().getCodeOwnerConfigValidationPolicyForCommitReceived("master"))
        .isEqualTo(CodeOwnerConfigValidationPolicy.FALSE);
    assertThat(
            cfgSnapshot().getCodeOwnerConfigValidationPolicyForCommitReceived("refs/heads/master"))
        .isEqualTo(CodeOwnerConfigValidationPolicy.FALSE);
    assertThat(cfgSnapshot().getCodeOwnerConfigValidationPolicyForCommitReceived("foo"))
        .isEqualTo(CodeOwnerConfigValidationPolicy.DRY_RUN);
  }

  @Test
  public void
      getCodeOwnerConfigValidationPolicyForCommitReceived_inheritedBranchLevelCanBeOverridden()
          throws Exception {
    configureEnableValidationOnCommitReceivedForBranch(
        allProjects, "refs/heads/master", CodeOwnerConfigValidationPolicy.FALSE);
    configureEnableValidationOnCommitReceivedForBranch(
        project, "refs/heads/master", CodeOwnerConfigValidationPolicy.DRY_RUN);
    assertThat(cfgSnapshot().getCodeOwnerConfigValidationPolicyForCommitReceived("master"))
        .isEqualTo(CodeOwnerConfigValidationPolicy.DRY_RUN);
  }

  @Test
  public void cannotGetCodeOwnerConfigValidationPolicyForSubmitForNullBranch() throws Exception {
    NullPointerException npe =
        assertThrows(
            NullPointerException.class,
            () ->
                cfgSnapshot().getCodeOwnerConfigValidationPolicyForSubmit(/* branchName= */ null));
    assertThat(npe).hasMessageThat().isEqualTo("branchName");
  }

  @Test
  public void getCodeOwnerConfigValidationPolicyForSubmitd_notConfigured() throws Exception {
    assertThat(cfgSnapshot().getCodeOwnerConfigValidationPolicyForSubmit("master"))
        .isEqualTo(CodeOwnerConfigValidationPolicy.FALSE);
    assertThat(cfgSnapshot().getCodeOwnerConfigValidationPolicyForSubmit("non-existing"))
        .isEqualTo(CodeOwnerConfigValidationPolicy.FALSE);
  }

  @Test
  public void getCodeOwnerConfigValidationPolicyForSubmit_configuredOnProjectLevel()
      throws Exception {
    configureEnableValidationOnSubmit(project, CodeOwnerConfigValidationPolicy.TRUE);
    assertThat(cfgSnapshot().getCodeOwnerConfigValidationPolicyForSubmit("master"))
        .isEqualTo(CodeOwnerConfigValidationPolicy.TRUE);
    assertThat(cfgSnapshot().getCodeOwnerConfigValidationPolicyForSubmit("non-existing"))
        .isEqualTo(CodeOwnerConfigValidationPolicy.TRUE);
  }

  @Test
  public void getCodeOwnerConfigValidationPolicyForSubmit_configuredOnBranchLevel()
      throws Exception {
    configureEnableValidationOnSubmitForBranch(
        project, "refs/heads/master", CodeOwnerConfigValidationPolicy.TRUE);
    assertThat(cfgSnapshot().getCodeOwnerConfigValidationPolicyForSubmit("master"))
        .isEqualTo(CodeOwnerConfigValidationPolicy.TRUE);
    assertThat(cfgSnapshot().getCodeOwnerConfigValidationPolicyForSubmit("refs/heads/master"))
        .isEqualTo(CodeOwnerConfigValidationPolicy.TRUE);
    assertThat(cfgSnapshot().getCodeOwnerConfigValidationPolicyForSubmit("foo"))
        .isEqualTo(CodeOwnerConfigValidationPolicy.FALSE);
  }

  @Test
  public void getCodeOwnerConfigValidationPolicyForSubmit_branchLevelConfigTakesPrecedence()
      throws Exception {
    updateCodeOwnersConfig(
        project,
        codeOwnersConfig -> {
          codeOwnersConfig.setEnum(
              CodeOwnersPluginConfiguration.SECTION_CODE_OWNERS,
              /* subsection= */ null,
              GeneralConfig.KEY_ENABLE_VALIDATION_ON_SUBMIT,
              CodeOwnerConfigValidationPolicy.DRY_RUN);
          codeOwnersConfig.setEnum(
              GeneralConfig.SECTION_VALIDATION,
              "refs/heads/master",
              GeneralConfig.KEY_ENABLE_VALIDATION_ON_SUBMIT,
              CodeOwnerConfigValidationPolicy.TRUE);
        });
    assertThat(cfgSnapshot().getCodeOwnerConfigValidationPolicyForSubmit("master"))
        .isEqualTo(CodeOwnerConfigValidationPolicy.TRUE);
    assertThat(cfgSnapshot().getCodeOwnerConfigValidationPolicyForSubmit("refs/heads/master"))
        .isEqualTo(CodeOwnerConfigValidationPolicy.TRUE);
    assertThat(cfgSnapshot().getCodeOwnerConfigValidationPolicyForSubmit("foo"))
        .isEqualTo(CodeOwnerConfigValidationPolicy.DRY_RUN);
  }

  @Test
  public void
      getCodeOwnerConfigValidationPolicyForSubmit_inheritedBranchLevelConfigTakesPrecedence()
          throws Exception {
    configureEnableValidationOnSubmitForBranch(
        allProjects, "refs/heads/master", CodeOwnerConfigValidationPolicy.TRUE);
    configureEnableValidationOnSubmit(project, CodeOwnerConfigValidationPolicy.DRY_RUN);
    assertThat(cfgSnapshot().getCodeOwnerConfigValidationPolicyForSubmit("master"))
        .isEqualTo(CodeOwnerConfigValidationPolicy.TRUE);
    assertThat(cfgSnapshot().getCodeOwnerConfigValidationPolicyForSubmit("refs/heads/master"))
        .isEqualTo(CodeOwnerConfigValidationPolicy.TRUE);
    assertThat(cfgSnapshot().getCodeOwnerConfigValidationPolicyForSubmit("foo"))
        .isEqualTo(CodeOwnerConfigValidationPolicy.DRY_RUN);
  }

  @Test
  public void getCodeOwnerConfigValidationPolicyForSubmit_inheritedBranchLevelCanBeOverridden()
      throws Exception {
    configureEnableValidationOnSubmitForBranch(
        allProjects, "refs/heads/master", CodeOwnerConfigValidationPolicy.TRUE);
    configureEnableValidationOnSubmitForBranch(
        project, "refs/heads/master", CodeOwnerConfigValidationPolicy.DRY_RUN);
    assertThat(cfgSnapshot().getCodeOwnerConfigValidationPolicyForSubmit("master"))
        .isEqualTo(CodeOwnerConfigValidationPolicy.DRY_RUN);
  }

  @Test
  public void cannotGetRejectNonResolvableCodeOwnersForNullBranch() throws Exception {
    NullPointerException npe =
        assertThrows(
            NullPointerException.class,
            () -> cfgSnapshot().rejectNonResolvableCodeOwners(/* branchName= */ null));
    assertThat(npe).hasMessageThat().isEqualTo("branchName");
  }

  @Test
  public void getRejectNonResolvableCodeOwners_notConfigured() throws Exception {
    assertThat(cfgSnapshot().rejectNonResolvableCodeOwners("master")).isTrue();
    assertThat(cfgSnapshot().rejectNonResolvableCodeOwners("non-existing")).isTrue();
  }

  @Test
  public void getRejectNonResolvableCodeOwners_configuredOnProjectLevel() throws Exception {
    configureRejectNonResolvableCodeOwners(project, false);
    assertThat(cfgSnapshot().rejectNonResolvableCodeOwners("master")).isFalse();
    assertThat(cfgSnapshot().rejectNonResolvableCodeOwners("non-existing")).isFalse();
  }

  @Test
  public void getRejectNonResolvableCodeOwners_configuredOnBranchLevel() throws Exception {
    configureRejectNonResolvableCodeOwnersForBranch(project, "refs/heads/master", false);
    assertThat(cfgSnapshot().rejectNonResolvableCodeOwners("master")).isFalse();
    assertThat(cfgSnapshot().rejectNonResolvableCodeOwners("refs/heads/master")).isFalse();
    assertThat(cfgSnapshot().rejectNonResolvableCodeOwners("foo")).isTrue();
  }

  @Test
  public void getRejectNonResolvableCodeOwners_branchLevelConfigTakesPrecedence() throws Exception {
    updateCodeOwnersConfig(
        project,
        codeOwnersConfig -> {
          codeOwnersConfig.setBoolean(
              CodeOwnersPluginConfiguration.SECTION_CODE_OWNERS,
              /* subsection= */ null,
              GeneralConfig.KEY_REJECT_NON_RESOLVABLE_CODE_OWNERS,
              /* value= */ false);
          codeOwnersConfig.setBoolean(
              GeneralConfig.SECTION_VALIDATION,
              "refs/heads/master",
              GeneralConfig.KEY_REJECT_NON_RESOLVABLE_CODE_OWNERS,
              /* value= */ true);
        });
    assertThat(cfgSnapshot().rejectNonResolvableCodeOwners("master")).isTrue();
    assertThat(cfgSnapshot().rejectNonResolvableCodeOwners("refs/heads/master")).isTrue();
    assertThat(cfgSnapshot().rejectNonResolvableCodeOwners("foo")).isFalse();
  }

  @Test
  public void getRejectNonResolvableCodeOwners_inheritedBranchLevelConfigTakesPrecedence()
      throws Exception {
    configureRejectNonResolvableCodeOwnersForBranch(allProjects, "refs/heads/master", true);
    configureRejectNonResolvableCodeOwners(project, false);
    assertThat(cfgSnapshot().rejectNonResolvableCodeOwners("master")).isTrue();
    assertThat(cfgSnapshot().rejectNonResolvableCodeOwners("refs/heads/master")).isTrue();
    assertThat(cfgSnapshot().rejectNonResolvableCodeOwners("foo")).isFalse();
  }

  @Test
  public void getRejectNonResolvableCodeOwners_inheritedBranchLevelCanBeOverridden()
      throws Exception {
    configureRejectNonResolvableCodeOwnersForBranch(allProjects, "refs/heads/master", true);
    configureRejectNonResolvableCodeOwnersForBranch(project, "refs/heads/master", false);
    assertThat(cfgSnapshot().rejectNonResolvableCodeOwners("master")).isFalse();
  }

  @Test
  public void cannotGetRejectNonResolvableImportsForNullBranch() throws Exception {
    NullPointerException npe =
        assertThrows(
            NullPointerException.class,
            () -> cfgSnapshot().rejectNonResolvableImports(/* branchName= */ null));
    assertThat(npe).hasMessageThat().isEqualTo("branchName");
  }

  @Test
  public void getRejectNonResolvableImports_notConfigured() throws Exception {
    assertThat(cfgSnapshot().rejectNonResolvableImports("master")).isTrue();
    assertThat(cfgSnapshot().rejectNonResolvableImports("non-existing")).isTrue();
  }

  @Test
  public void getRejectNonResolvableImports_configuredOnProjectLevel() throws Exception {
    configureRejectNonResolvableImports(project, false);
    assertThat(cfgSnapshot().rejectNonResolvableImports("master")).isFalse();
    assertThat(cfgSnapshot().rejectNonResolvableImports("non-existing")).isFalse();
  }

  @Test
  public void getRejectNonResolvableImports_configuredOnBranchLevel() throws Exception {
    configureRejectNonResolvableImportsForBranch(project, "refs/heads/master", false);
    assertThat(cfgSnapshot().rejectNonResolvableImports("master")).isFalse();
    assertThat(cfgSnapshot().rejectNonResolvableImports("refs/heads/master")).isFalse();
    assertThat(cfgSnapshot().rejectNonResolvableImports("foo")).isTrue();
  }

  @Test
  public void getRejectNonResolvableImports_branchLevelConfigTakesPrecedence() throws Exception {
    updateCodeOwnersConfig(
        project,
        codeOwnersConfig -> {
          codeOwnersConfig.setBoolean(
              CodeOwnersPluginConfiguration.SECTION_CODE_OWNERS,
              /* subsection= */ null,
              GeneralConfig.KEY_REJECT_NON_RESOLVABLE_IMPORTS,
              /* value= */ false);
          codeOwnersConfig.setBoolean(
              GeneralConfig.SECTION_VALIDATION,
              "refs/heads/master",
              GeneralConfig.KEY_REJECT_NON_RESOLVABLE_IMPORTS,
              /* value= */ true);
        });
    assertThat(cfgSnapshot().rejectNonResolvableImports("master")).isTrue();
    assertThat(cfgSnapshot().rejectNonResolvableImports("refs/heads/master")).isTrue();
    assertThat(cfgSnapshot().rejectNonResolvableImports("foo")).isFalse();
  }

  @Test
  public void getRejectNonResolvableImports_inheritedBranchLevelConfigTakesPrecedence()
      throws Exception {
    configureRejectNonResolvableImportsForBranch(allProjects, "refs/heads/master", true);
    configureRejectNonResolvableImports(project, false);
    assertThat(cfgSnapshot().rejectNonResolvableImports("master")).isTrue();
    assertThat(cfgSnapshot().rejectNonResolvableImports("refs/heads/master")).isTrue();
    assertThat(cfgSnapshot().rejectNonResolvableImports("foo")).isFalse();
  }

  @Test
  public void getRejectNonResolvableImports_inheritedBranchLevelCanBeOverridden() throws Exception {
    configureRejectNonResolvableImportsForBranch(allProjects, "refs/heads/master", true);
    configureRejectNonResolvableImportsForBranch(project, "refs/heads/master", false);
    assertThat(cfgSnapshot().rejectNonResolvableImports("master")).isFalse();
  }

  private CodeOwnersPluginProjectConfigSnapshot cfgSnapshot() {
    return codeOwnersPluginProjectConfigSnapshotFactory.create(project);
  }

  private void configureFileExtension(Project.NameKey project, String fileExtension)
      throws Exception {
    setCodeOwnersConfig(
        project, /* subsection= */ null, GeneralConfig.KEY_FILE_EXTENSION, fileExtension);
  }

  private void configureMergeCommitStrategy(
      Project.NameKey project, MergeCommitStrategy mergeCommitStrategy) throws Exception {
    setCodeOwnersConfig(
        project,
        /* subsection= */ null,
        GeneralConfig.KEY_MERGE_COMMIT_STRATEGY,
        mergeCommitStrategy.name());
  }

  private void configureFallbackCodeOwners(
      Project.NameKey project, FallbackCodeOwners fallbackCodeOwners) throws Exception {
    setCodeOwnersConfig(
        project,
        /* subsection= */ null,
        GeneralConfig.KEY_FALLBACK_CODE_OWNERS,
        fallbackCodeOwners.name());
  }

  private void configureGlobalCodeOwners(Project.NameKey project, String... globalCodeOwners)
      throws Exception {
    setCodeOwnersConfig(
        project,
        /* subsection= */ null,
        GeneralConfig.KEY_GLOBAL_CODE_OWNER,
        ImmutableList.copyOf(globalCodeOwners));
  }

  private void configureExemptedUsers(Project.NameKey project, String... exemptedUsers)
      throws Exception {
    setCodeOwnersConfig(
        project,
        /* subsection= */ null,
        GeneralConfig.KEY_EXEMPTED_USER,
        ImmutableList.copyOf(exemptedUsers));
  }

  private void configureMaxPathsInChangeMessages(
      Project.NameKey project, int maxPathsInChangeMessages) throws Exception {
    setCodeOwnersConfig(
        project,
        /* subsection= */ null,
        GeneralConfig.KEY_MAX_PATHS_IN_CHANGE_MESSAGES,
        Integer.toString(maxPathsInChangeMessages));
  }

  private void configureDisabled(Project.NameKey project, String disabled) throws Exception {
    setCodeOwnersConfig(project, /* subsection= */ null, StatusConfig.KEY_DISABLED, disabled);
  }

  private void configureDisabledBranch(Project.NameKey project, String disabledBranch)
      throws Exception {
    setCodeOwnersConfig(
        project, /* subsection= */ null, StatusConfig.KEY_DISABLED_BRANCH, disabledBranch);
  }

  private void configureBackend(Project.NameKey project, String backendName) throws Exception {
    configureBackend(project, /* branch= */ null, backendName);
  }

  private void configureBackend(
      Project.NameKey project, @Nullable String branch, String backendName) throws Exception {
    setCodeOwnersConfig(project, branch, BackendConfig.KEY_BACKEND, backendName);
  }

  private void configureRequiredApproval(Project.NameKey project, String requiredApproval)
      throws Exception {
    setCodeOwnersConfig(
        project,
        /* subsection= */ null,
        RequiredApprovalConfig.KEY_REQUIRED_APPROVAL,
        requiredApproval);
  }

  private void configureOverrideApproval(Project.NameKey project, String requiredApproval)
      throws Exception {
    setCodeOwnersConfig(
        project,
        /* subsection= */ null,
        OverrideApprovalConfig.KEY_OVERRIDE_APPROVAL,
        requiredApproval);
  }

  private void configureEnableValidationOnCommitReceived(
      Project.NameKey project, CodeOwnerConfigValidationPolicy codeOwnerConfigValidationPolicy)
      throws Exception {
    setCodeOwnersConfig(
        project,
        /* subsection= */ null,
        GeneralConfig.KEY_ENABLE_VALIDATION_ON_COMMIT_RECEIVED,
        codeOwnerConfigValidationPolicy.name());
  }

  private void configureEnableValidationOnCommitReceivedForBranch(
      Project.NameKey project,
      String branchSubsection,
      CodeOwnerConfigValidationPolicy codeOwnerConfigValidationPolicy)
      throws Exception {
    updateCodeOwnersConfig(
        project,
        codeOwnersConfig ->
            codeOwnersConfig.setString(
                GeneralConfig.SECTION_VALIDATION,
                branchSubsection,
                GeneralConfig.KEY_ENABLE_VALIDATION_ON_COMMIT_RECEIVED,
                codeOwnerConfigValidationPolicy.name()));
  }

  private void configureEnableValidationOnSubmit(
      Project.NameKey project, CodeOwnerConfigValidationPolicy codeOwnerConfigValidationPolicy)
      throws Exception {
    setCodeOwnersConfig(
        project,
        /* subsection= */ null,
        GeneralConfig.KEY_ENABLE_VALIDATION_ON_SUBMIT,
        codeOwnerConfigValidationPolicy.name());
  }

  private void configureEnableValidationOnSubmitForBranch(
      Project.NameKey project,
      String branchSubsection,
      CodeOwnerConfigValidationPolicy codeOwnerConfigValidationPolicy)
      throws Exception {
    updateCodeOwnersConfig(
        project,
        codeOwnersConfig ->
            codeOwnersConfig.setString(
                GeneralConfig.SECTION_VALIDATION,
                branchSubsection,
                GeneralConfig.KEY_ENABLE_VALIDATION_ON_SUBMIT,
                codeOwnerConfigValidationPolicy.name()));
  }

  private void configureRejectNonResolvableCodeOwners(Project.NameKey project, boolean value)
      throws Exception {
    setCodeOwnersConfig(
        project,
        /* subsection= */ null,
        GeneralConfig.KEY_REJECT_NON_RESOLVABLE_CODE_OWNERS,
        Boolean.toString(value));
  }

  private void configureRejectNonResolvableCodeOwnersForBranch(
      Project.NameKey project, String branchSubsection, boolean value) throws Exception {
    updateCodeOwnersConfig(
        project,
        codeOwnersConfig ->
            codeOwnersConfig.setString(
                GeneralConfig.SECTION_VALIDATION,
                branchSubsection,
                GeneralConfig.KEY_REJECT_NON_RESOLVABLE_CODE_OWNERS,
                Boolean.toString(value)));
  }

  private void configureRejectNonResolvableImports(Project.NameKey project, boolean value)
      throws Exception {
    setCodeOwnersConfig(
        project,
        /* subsection= */ null,
        GeneralConfig.KEY_REJECT_NON_RESOLVABLE_IMPORTS,
        Boolean.toString(value));
  }

  private void configureRejectNonResolvableImportsForBranch(
      Project.NameKey project, String branchSubsection, boolean value) throws Exception {
    updateCodeOwnersConfig(
        project,
        codeOwnersConfig ->
            codeOwnersConfig.setString(
                GeneralConfig.SECTION_VALIDATION,
                branchSubsection,
                GeneralConfig.KEY_REJECT_NON_RESOLVABLE_IMPORTS,
                Boolean.toString(value)));
  }

  private AutoCloseable registerTestBackend() {
    RegistrationHandle registrationHandle =
        ((PrivateInternals_DynamicMapImpl<CodeOwnerBackend>) codeOwnerBackends)
            .put("gerrit", TestCodeOwnerBackend.ID, Providers.of(new TestCodeOwnerBackend()));
    return registrationHandle::remove;
  }

  private static class TestCodeOwnerBackend implements CodeOwnerBackend {
    static final String ID = "test-backend";

    @Override
    public Optional<CodeOwnerConfig> getCodeOwnerConfig(
        CodeOwnerConfig.Key codeOwnerConfigKey,
        @Nullable RevWalk revWalk,
        @Nullable ObjectId revision) {
      throw new UnsupportedOperationException("not implemented");
    }

    @Override
    public Optional<CodeOwnerConfig> upsertCodeOwnerConfig(
        CodeOwnerConfig.Key codeOwnerConfigKey,
        CodeOwnerConfigUpdate codeOwnerConfigUpdate,
        @Nullable IdentifiedUser currentUser) {
      throw new UnsupportedOperationException("not implemented");
    }

    @Override
    public boolean isCodeOwnerConfigFile(NameKey project, String fileName) {
      throw new UnsupportedOperationException("not implemented");
    }

    @Override
    public Path getFilePath(CodeOwnerConfig.Key codeOwnerConfigKey) {
      throw new UnsupportedOperationException("not implemented");
    }

    @Override
    public Optional<PathExpressionMatcher> getPathExpressionMatcher() {
      return Optional.empty();
    }
  }
}
