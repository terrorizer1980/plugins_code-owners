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

package com.google.gerrit.plugins.codeowners.backend;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.gerrit.acceptance.testsuite.project.TestProjectUpdate.allowLabel;
import static com.google.gerrit.plugins.codeowners.testing.FileCodeOwnerStatusSubject.assertThat;
import static com.google.gerrit.plugins.codeowners.testing.FileCodeOwnerStatusSubject.assertThatStream;
import static com.google.gerrit.server.group.SystemGroupBackend.REGISTERED_USERS;

import com.google.common.collect.ImmutableMap;
import com.google.gerrit.acceptance.TestAccount;
import com.google.gerrit.acceptance.config.GerritConfig;
import com.google.gerrit.acceptance.testsuite.project.ProjectOperations;
import com.google.gerrit.acceptance.testsuite.request.RequestScopeOperations;
import com.google.gerrit.entities.Change;
import com.google.gerrit.extensions.api.changes.ReviewInput;
import com.google.gerrit.plugins.codeowners.acceptance.AbstractCodeOwnersTest;
import com.google.gerrit.plugins.codeowners.backend.config.GeneralConfig;
import com.google.gerrit.plugins.codeowners.common.CodeOwnerStatus;
import com.google.gerrit.plugins.codeowners.testing.FileCodeOwnerStatusSubject;
import com.google.gerrit.plugins.codeowners.util.JgitPath;
import com.google.gerrit.server.notedb.ChangeNotes;
import com.google.gerrit.testing.ConfigSuite;
import com.google.inject.Inject;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.stream.Stream;
import org.eclipse.jgit.internal.storage.dfs.InMemoryRepository;
import org.eclipse.jgit.junit.TestRepository;
import org.eclipse.jgit.lib.Config;
import org.junit.Before;
import org.junit.Test;

/** Tests for {@link CodeOwnerApprovalCheck} with PROJECT_OWNERS as fallback code owners. */
public class CodeOwnerApprovalCheckWithProjectOwnersAsFallbackCodeOwnersTest
    extends AbstractCodeOwnersTest {
  @Inject private ChangeNotes.Factory changeNotesFactory;
  @Inject private RequestScopeOperations requestScopeOperations;
  @Inject private ProjectOperations projectOperations;

  private CodeOwnerApprovalCheck codeOwnerApprovalCheck;

  /** Returns a {@code gerrit.config} that configures all users as fallback code owners. */
  @ConfigSuite.Default
  public static Config defaultConfig() {
    Config cfg = new Config();
    cfg.setEnum(
        "plugin",
        "code-owners",
        GeneralConfig.KEY_FALLBACK_CODE_OWNERS,
        FallbackCodeOwners.PROJECT_OWNERS);
    return cfg;
  }

  @Before
  public void setUpCodeOwnersPlugin() throws Exception {
    codeOwnerApprovalCheck = plugin.getSysInjector().getInstance(CodeOwnerApprovalCheck.class);
  }

  @Test
  @GerritConfig(name = "plugin.code-owners.globalCodeOwner", value = "bot@example.com")
  public void approvedByGlobalCodeOwner() throws Exception {
    // Create a bot user that is a global code owner.
    TestAccount bot =
        accountCreator.create("bot", "bot@example.com", "Bot", /* displayName= */ null);

    // Create a change as a user that is not a code owner.
    Path path = Paths.get("/foo/bar.baz");
    String changeId =
        createChange(user, "Change Adding A File", JgitPath.of(path).get(), "file content")
            .getChangeId();

    // Verify that the file is not approved yet.
    Stream<FileCodeOwnerStatus> fileCodeOwnerStatuses =
        codeOwnerApprovalCheck.getFileStatuses(getChangeNotes(changeId));
    FileCodeOwnerStatusSubject fileCodeOwnerStatusSubject =
        assertThatStream(fileCodeOwnerStatuses).onlyElement();
    fileCodeOwnerStatusSubject.hasNewPathStatus().value().hasPathThat().isEqualTo(path);
    fileCodeOwnerStatusSubject
        .hasNewPathStatus()
        .value()
        .hasStatusThat()
        .isEqualTo(CodeOwnerStatus.INSUFFICIENT_REVIEWERS);

    // Let the bot approve the change.
    projectOperations
        .project(project)
        .forUpdate()
        .add(allowLabel("Code-Review").ref("refs/heads/*").group(REGISTERED_USERS).range(-2, +2))
        .update();
    requestScopeOperations.setApiUser(bot.id());
    approve(changeId);

    // Check that the file is approved now.
    requestScopeOperations.setApiUser(admin.id());
    fileCodeOwnerStatuses = codeOwnerApprovalCheck.getFileStatuses(getChangeNotes(changeId));
    fileCodeOwnerStatusSubject = assertThatStream(fileCodeOwnerStatuses).onlyElement();
    fileCodeOwnerStatusSubject.hasNewPathStatus().value().hasPathThat().isEqualTo(path);
    fileCodeOwnerStatusSubject
        .hasNewPathStatus()
        .value()
        .hasStatusThat()
        .isEqualTo(CodeOwnerStatus.APPROVED);
  }

  @Test
  @GerritConfig(name = "plugin.code-owners.globalCodeOwner", value = "bot@example.com")
  public void globalCodeOwner_noImplicitApproval() throws Exception {
    testImplicitlyApprovedByGlobalCodeOwner(/* implicitApprovalsEnabled= */ false);
  }

  @Test
  @GerritConfig(name = "plugin.code-owners.globalCodeOwner", value = "bot@example.com")
  @GerritConfig(name = "plugin.code-owners.enableImplicitApprovals", value = "true")
  public void globalCodeOwner_withImplicitApproval() throws Exception {
    testImplicitlyApprovedByGlobalCodeOwner(/* implicitApprovalsEnabled= */ true);
  }

  private void testImplicitlyApprovedByGlobalCodeOwner(boolean implicitApprovalsEnabled)
      throws Exception {
    TestAccount bot =
        accountCreator.create("bot", "bot@example.com", "Bot", /* displayName= */ null);

    Path path = Paths.get("/foo/bar.baz");
    String changeId =
        createChange(bot, "Change Adding A File", JgitPath.of(path).get(), "file content")
            .getChangeId();

    Stream<FileCodeOwnerStatus> fileCodeOwnerStatuses =
        codeOwnerApprovalCheck.getFileStatuses(getChangeNotes(changeId));
    FileCodeOwnerStatusSubject fileCodeOwnerStatusSubject =
        assertThatStream(fileCodeOwnerStatuses).onlyElement();
    fileCodeOwnerStatusSubject.hasNewPathStatus().value().hasPathThat().isEqualTo(path);
    fileCodeOwnerStatusSubject
        .hasNewPathStatus()
        .value()
        .hasStatusThat()
        .isEqualTo(
            implicitApprovalsEnabled
                ? CodeOwnerStatus.APPROVED
                : CodeOwnerStatus.INSUFFICIENT_REVIEWERS);
  }

  @Test
  @GerritConfig(name = "plugin.code-owners.globalCodeOwner", value = "bot@example.com")
  public void globalCodeOwnerAsReviewer() throws Exception {
    // Create a bot user that is a global code owner.
    TestAccount bot =
        accountCreator.create("bot", "bot@example.com", "Bot", /* displayName= */ null);

    // Create a change as a user that is not a code owner.
    Path path = Paths.get("/foo/bar.baz");
    String changeId =
        createChange(user, "Change Adding A File", JgitPath.of(path).get(), "file content")
            .getChangeId();

    // Verify that the status of the file is INSUFFICIENT_REVIEWERS.
    Stream<FileCodeOwnerStatus> fileCodeOwnerStatuses =
        codeOwnerApprovalCheck.getFileStatuses(getChangeNotes(changeId));
    FileCodeOwnerStatusSubject fileCodeOwnerStatusSubject =
        assertThatStream(fileCodeOwnerStatuses).onlyElement();
    fileCodeOwnerStatusSubject.hasNewPathStatus().value().hasPathThat().isEqualTo(path);
    fileCodeOwnerStatusSubject
        .hasNewPathStatus()
        .value()
        .hasStatusThat()
        .isEqualTo(CodeOwnerStatus.INSUFFICIENT_REVIEWERS);

    // Add the bot approve as reviewer.
    gApi.changes().id(changeId).addReviewer(bot.email());

    // Check that the status of the file is PENDING now.
    requestScopeOperations.setApiUser(admin.id());
    fileCodeOwnerStatuses = codeOwnerApprovalCheck.getFileStatuses(getChangeNotes(changeId));
    fileCodeOwnerStatusSubject = assertThatStream(fileCodeOwnerStatuses).onlyElement();
    fileCodeOwnerStatusSubject.hasNewPathStatus().value().hasPathThat().isEqualTo(path);
    fileCodeOwnerStatusSubject
        .hasNewPathStatus()
        .value()
        .hasStatusThat()
        .isEqualTo(CodeOwnerStatus.PENDING);

    // Let the bot approve the change.
    projectOperations
        .project(project)
        .forUpdate()
        .add(allowLabel("Code-Review").ref("refs/heads/*").group(REGISTERED_USERS).range(-2, +2))
        .update();
    requestScopeOperations.setApiUser(bot.id());
    approve(changeId);

    // Check that the file is approved now.
    requestScopeOperations.setApiUser(admin.id());
    fileCodeOwnerStatuses = codeOwnerApprovalCheck.getFileStatuses(getChangeNotes(changeId));
    fileCodeOwnerStatusSubject = assertThatStream(fileCodeOwnerStatuses).onlyElement();
    fileCodeOwnerStatusSubject.hasNewPathStatus().value().hasPathThat().isEqualTo(path);
    fileCodeOwnerStatusSubject
        .hasNewPathStatus()
        .value()
        .hasStatusThat()
        .isEqualTo(CodeOwnerStatus.APPROVED);
  }

  @Test
  @GerritConfig(name = "plugin.code-owners.globalCodeOwner", value = "*")
  public void approvedByAnyoneWhenEveryoneIsGlobalCodeOwner() throws Exception {
    // Create a change.
    Path path = Paths.get("/foo/bar.baz");
    String changeId =
        createChange(user, "Change Adding A File", JgitPath.of(path).get(), "file content")
            .getChangeId();

    // Verify that the file is not approved yet (the change owner is a global code owner, but
    // implicit approvals are disabled).
    Stream<FileCodeOwnerStatus> fileCodeOwnerStatuses =
        codeOwnerApprovalCheck.getFileStatuses(getChangeNotes(changeId));
    FileCodeOwnerStatusSubject fileCodeOwnerStatusSubject =
        assertThatStream(fileCodeOwnerStatuses).onlyElement();
    fileCodeOwnerStatusSubject.hasNewPathStatus().value().hasPathThat().isEqualTo(path);
    fileCodeOwnerStatusSubject
        .hasNewPathStatus()
        .value()
        .hasStatusThat()
        .isEqualTo(CodeOwnerStatus.INSUFFICIENT_REVIEWERS);

    // Add an approval by a user that is a code owner only through the global code ownership.
    approve(changeId);

    // Check that the file is approved now.
    requestScopeOperations.setApiUser(admin.id());
    fileCodeOwnerStatuses = codeOwnerApprovalCheck.getFileStatuses(getChangeNotes(changeId));
    fileCodeOwnerStatusSubject = assertThatStream(fileCodeOwnerStatuses).onlyElement();
    fileCodeOwnerStatusSubject.hasNewPathStatus().value().hasPathThat().isEqualTo(path);
    fileCodeOwnerStatusSubject
        .hasNewPathStatus()
        .value()
        .hasStatusThat()
        .isEqualTo(CodeOwnerStatus.APPROVED);
  }

  @Test
  @GerritConfig(name = "plugin.code-owners.globalCodeOwner", value = "*")
  public void everyoneIsGlobalCodeOwner_noImplicitApproval() throws Exception {
    testImplicitlyApprovedByGlobalCodeOwnerWhenEveryoneIsGlobalCodeOwner(
        /* implicitApprovalsEnabled= */ false);
  }

  @Test
  @GerritConfig(name = "plugin.code-owners.globalCodeOwner", value = "*")
  @GerritConfig(name = "plugin.code-owners.enableImplicitApprovals", value = "true")
  public void everyoneIsGlobalCodeOwner_withImplicitApproval() throws Exception {
    testImplicitlyApprovedByGlobalCodeOwnerWhenEveryoneIsGlobalCodeOwner(
        /* implicitApprovalsEnabled= */ true);
  }

  private void testImplicitlyApprovedByGlobalCodeOwnerWhenEveryoneIsGlobalCodeOwner(
      boolean implicitApprovalsEnabled) throws Exception {
    // Create a change as a user that is a code owner only through the global code ownership.
    Path path = Paths.get("/foo/bar.baz");
    String changeId =
        createChange("Change Adding A File", JgitPath.of(path).get(), "file content").getChangeId();

    Stream<FileCodeOwnerStatus> fileCodeOwnerStatuses =
        codeOwnerApprovalCheck.getFileStatuses(getChangeNotes(changeId));
    FileCodeOwnerStatusSubject fileCodeOwnerStatusSubject =
        assertThatStream(fileCodeOwnerStatuses).onlyElement();
    fileCodeOwnerStatusSubject.hasNewPathStatus().value().hasPathThat().isEqualTo(path);
    fileCodeOwnerStatusSubject
        .hasNewPathStatus()
        .value()
        .hasStatusThat()
        .isEqualTo(
            implicitApprovalsEnabled
                ? CodeOwnerStatus.APPROVED
                : CodeOwnerStatus.INSUFFICIENT_REVIEWERS);
  }

  @Test
  @GerritConfig(name = "plugin.code-owners.globalCodeOwner", value = "*")
  public void anyReviewerWhenEveryoneIsGlobalCodeOwner() throws Exception {
    // Create a change as a user that is a code owner only through the global code ownership.
    Path path = Paths.get("/foo/bar.baz");
    String changeId =
        createChange("Change Adding A File", JgitPath.of(path).get(), "file content").getChangeId();

    // Verify that the status of the file is INSUFFICIENT_REVIEWERS (since there is no implicit
    // approval by default).
    Stream<FileCodeOwnerStatus> fileCodeOwnerStatuses =
        codeOwnerApprovalCheck.getFileStatuses(getChangeNotes(changeId));
    FileCodeOwnerStatusSubject fileCodeOwnerStatusSubject =
        assertThatStream(fileCodeOwnerStatuses).onlyElement();
    fileCodeOwnerStatusSubject.hasNewPathStatus().value().hasPathThat().isEqualTo(path);
    fileCodeOwnerStatusSubject
        .hasNewPathStatus()
        .value()
        .hasStatusThat()
        .isEqualTo(CodeOwnerStatus.INSUFFICIENT_REVIEWERS);

    // Add a user as reviewer that is a code owner only through the global code ownership.
    gApi.changes().id(changeId).addReviewer(user.email());

    // Check that the status of the file is PENDING now.
    fileCodeOwnerStatuses = codeOwnerApprovalCheck.getFileStatuses(getChangeNotes(changeId));
    fileCodeOwnerStatusSubject = assertThatStream(fileCodeOwnerStatuses).onlyElement();
    fileCodeOwnerStatusSubject.hasNewPathStatus().value().hasPathThat().isEqualTo(path);
    fileCodeOwnerStatusSubject
        .hasNewPathStatus()
        .value()
        .hasStatusThat()
        .isEqualTo(CodeOwnerStatus.PENDING);
  }

  @Test
  public void getStatus_insufficientReviewers() throws Exception {
    TestAccount user2 = accountCreator.user2();
    TestAccount user3 =
        accountCreator.create("user3", "user3@example.com", "User3", /* displayName= */ null);

    // Create change with a user that is not a project owner.
    Path path = Paths.get("/foo/bar.baz");
    String changeId =
        createChange(user, "Change Adding A File", JgitPath.of(path).get(), "file content")
            .getChangeId();

    // Add a reviewer that is not a project owner.
    gApi.changes().id(changeId).addReviewer(user2.email());

    // Add a Code-Review+1 (= code owner approval) from a user that is not a project owner.
    requestScopeOperations.setApiUser(user3.id());
    recommend(changeId);

    Stream<FileCodeOwnerStatus> fileCodeOwnerStatuses =
        codeOwnerApprovalCheck.getFileStatuses(getChangeNotes(changeId));
    FileCodeOwnerStatusSubject fileCodeOwnerStatusSubject =
        assertThatStream(fileCodeOwnerStatuses).onlyElement();
    fileCodeOwnerStatusSubject.hasNewPathStatus().value().hasPathThat().isEqualTo(path);
    fileCodeOwnerStatusSubject
        .hasNewPathStatus()
        .value()
        .hasStatusThat()
        .isEqualTo(CodeOwnerStatus.INSUFFICIENT_REVIEWERS);
    fileCodeOwnerStatusSubject.hasOldPathStatus().isEmpty();
    fileCodeOwnerStatusSubject.hasChangedFile().isNoRename();
    fileCodeOwnerStatusSubject.hasChangedFile().isNoDeletion();
  }

  @Test
  public void getStatus_pending() throws Exception {
    TestAccount user2 = accountCreator.user2();

    // Create change with a user that is not a project owner.
    Path path = Paths.get("/foo/bar.baz");
    String changeId =
        createChange(user, "Change Adding A File", JgitPath.of(path).get(), "file content")
            .getChangeId();

    // Add a reviewer that is a project owner.
    gApi.changes().id(changeId).addReviewer(admin.email());

    // Add a Code-Review+1 (= code owner approval) from a user that is not a project owner.
    requestScopeOperations.setApiUser(user2.id());
    recommend(changeId);

    Stream<FileCodeOwnerStatus> fileCodeOwnerStatuses =
        codeOwnerApprovalCheck.getFileStatuses(getChangeNotes(changeId));
    FileCodeOwnerStatusSubject fileCodeOwnerStatusSubject =
        assertThatStream(fileCodeOwnerStatuses).onlyElement();
    fileCodeOwnerStatusSubject.hasNewPathStatus().value().hasPathThat().isEqualTo(path);
    fileCodeOwnerStatusSubject
        .hasNewPathStatus()
        .value()
        .hasStatusThat()
        .isEqualTo(CodeOwnerStatus.PENDING);
    fileCodeOwnerStatusSubject.hasOldPathStatus().isEmpty();
    fileCodeOwnerStatusSubject.hasChangedFile().isNoRename();
    fileCodeOwnerStatusSubject.hasChangedFile().isNoDeletion();
  }

  @Test
  public void getStatus_approved() throws Exception {
    // Create change with a user that is not a project owner.
    Path path = Paths.get("/foo/bar.baz");
    String changeId =
        createChange(user, "Change Adding A File", JgitPath.of(path).get(), "file content")
            .getChangeId();

    // Add a Code-Review+1 from a project owner (by default this counts as code owner approval).
    requestScopeOperations.setApiUser(admin.id());
    recommend(changeId);

    Stream<FileCodeOwnerStatus> fileCodeOwnerStatuses =
        codeOwnerApprovalCheck.getFileStatuses(getChangeNotes(changeId));
    FileCodeOwnerStatusSubject fileCodeOwnerStatusSubject =
        assertThatStream(fileCodeOwnerStatuses).onlyElement();
    fileCodeOwnerStatusSubject.hasNewPathStatus().value().hasPathThat().isEqualTo(path);
    fileCodeOwnerStatusSubject
        .hasNewPathStatus()
        .value()
        .hasStatusThat()
        .isEqualTo(CodeOwnerStatus.APPROVED);
    fileCodeOwnerStatusSubject.hasOldPathStatus().isEmpty();
    fileCodeOwnerStatusSubject.hasChangedFile().isNoRename();
    fileCodeOwnerStatusSubject.hasChangedFile().isNoDeletion();
  }

  @Test
  public void getStatus_noImplicitApprovalByPatchSetUploader() throws Exception {
    testImplicitApprovalByPatchSetUploaderOnGetStatus(/* implicitApprovalsEnabled= */ false);
  }

  @Test
  @GerritConfig(name = "plugin.code-owners.enableImplicitApprovals", value = "true")
  public void getStatus_withImplicitApprovalByPatchSetUploader() throws Exception {
    testImplicitApprovalByPatchSetUploaderOnGetStatus(/* implicitApprovalsEnabled= */ true);
  }

  private void testImplicitApprovalByPatchSetUploaderOnGetStatus(boolean implicitApprovalsEnabled)
      throws Exception {
    // Create change with a user that is not a project owner.
    Path path = Paths.get("/foo/bar.baz");
    String changeId =
        createChange(user, "Change Adding A File", JgitPath.of(path).get(), "file content")
            .getChangeId();

    // Amend change with a user that is a project owner.
    amendChange(admin, changeId);

    Stream<FileCodeOwnerStatus> fileCodeOwnerStatuses =
        codeOwnerApprovalCheck.getFileStatuses(getChangeNotes(changeId));
    FileCodeOwnerStatusSubject fileCodeOwnerStatusSubject =
        assertThatStream(fileCodeOwnerStatuses).onlyElement();
    fileCodeOwnerStatusSubject.hasNewPathStatus().value().hasPathThat().isEqualTo(path);
    fileCodeOwnerStatusSubject
        .hasNewPathStatus()
        .value()
        .hasStatusThat()
        .isEqualTo(
            implicitApprovalsEnabled
                ? CodeOwnerStatus.APPROVED
                : CodeOwnerStatus.INSUFFICIENT_REVIEWERS);
    fileCodeOwnerStatusSubject.hasOldPathStatus().isEmpty();
    fileCodeOwnerStatusSubject.hasChangedFile().isNoRename();
    fileCodeOwnerStatusSubject.hasChangedFile().isNoDeletion();
  }

  @Test
  @GerritConfig(name = "plugin.code-owners.enableImplicitApprovals", value = "true")
  public void getStatus_noImplicitlyApprovalByChangeOwner() throws Exception {
    // Create change with a user that is a project owner.
    Path path = Paths.get("/foo/bar.baz");
    String changeId =
        createChange("Change Adding A File", JgitPath.of(path).get(), "file content").getChangeId();

    // Amend change with a user that is not a project owner.
    amendChange(user, changeId);

    Stream<FileCodeOwnerStatus> fileCodeOwnerStatuses =
        codeOwnerApprovalCheck.getFileStatuses(getChangeNotes(changeId));
    FileCodeOwnerStatusSubject fileCodeOwnerStatusSubject =
        assertThatStream(fileCodeOwnerStatuses).onlyElement();
    fileCodeOwnerStatusSubject.hasNewPathStatus().value().hasPathThat().isEqualTo(path);
    fileCodeOwnerStatusSubject
        .hasNewPathStatus()
        .value()
        .hasStatusThat()
        .isEqualTo(CodeOwnerStatus.INSUFFICIENT_REVIEWERS);
    fileCodeOwnerStatusSubject.hasOldPathStatus().isEmpty();
    fileCodeOwnerStatusSubject.hasChangedFile().isNoRename();
    fileCodeOwnerStatusSubject.hasChangedFile().isNoDeletion();
  }

  @Test
  @GerritConfig(name = "plugin.code-owners.overrideApproval", value = "Owners-Override+1")
  public void getStatus_overrideApprovesAllFiles() throws Exception {
    createOwnersOverrideLabel();

    // Create a change with a user that is not a project owner.
    TestRepository<InMemoryRepository> testRepo = cloneProject(project, user);
    String changeId =
        pushFactory
            .create(
                user.newIdent(),
                testRepo,
                "Test Change",
                ImmutableMap.of(
                    "foo/baz.config", "content",
                    "bar/baz.config", "other content"))
            .to("refs/for/master")
            .getChangeId();

    // Without Owners-Override approval the expected status is INSUFFICIENT_REVIEWERS.
    for (FileCodeOwnerStatus fileCodeOwnerStatus :
        codeOwnerApprovalCheck
            .getFileStatuses(getChangeNotes(changeId))
            .collect(toImmutableList())) {
      assertThat(fileCodeOwnerStatus)
          .hasNewPathStatus()
          .value()
          .hasStatusThat()
          .isEqualTo(CodeOwnerStatus.INSUFFICIENT_REVIEWERS);
    }

    // Add an override approval.
    gApi.changes().id(changeId).current().review(new ReviewInput().label("Owners-Override", 1));

    // With Owners-Override approval the expected status is APPROVED.
    for (FileCodeOwnerStatus fileCodeOwnerStatus :
        codeOwnerApprovalCheck
            .getFileStatuses(getChangeNotes(changeId))
            .collect(toImmutableList())) {
      assertThat(fileCodeOwnerStatus)
          .hasNewPathStatus()
          .value()
          .hasStatusThat()
          .isEqualTo(CodeOwnerStatus.APPROVED);
    }
  }

  @Test
  @GerritConfig(
      name = "plugin.code-owners.overrideApproval",
      values = {"Owners-Override+1", "Another-Override+1"})
  public void getStatus_anyOverrideApprovesAllFiles() throws Exception {
    createOwnersOverrideLabel();
    createOwnersOverrideLabel("Another-Override");

    // Create a change with a user that is not a project owner.
    TestRepository<InMemoryRepository> testRepo = cloneProject(project, user);
    String changeId =
        pushFactory
            .create(
                user.newIdent(),
                testRepo,
                "Test Change",
                ImmutableMap.of(
                    "foo/baz.config", "content",
                    "bar/baz.config", "other content"))
            .to("refs/for/master")
            .getChangeId();

    // Without override approval the expected status is INSUFFICIENT_REVIEWERS.
    for (FileCodeOwnerStatus fileCodeOwnerStatus :
        codeOwnerApprovalCheck
            .getFileStatuses(getChangeNotes(changeId))
            .collect(toImmutableList())) {
      assertThat(fileCodeOwnerStatus)
          .hasNewPathStatus()
          .value()
          .hasStatusThat()
          .isEqualTo(CodeOwnerStatus.INSUFFICIENT_REVIEWERS);
    }

    // Add an override approval (by a user that is not a project owners, and hence no code owner).
    requestScopeOperations.setApiUser(user.id());
    gApi.changes().id(changeId).current().review(new ReviewInput().label("Owners-Override", 1));

    // With override approval the expected status is APPROVED.
    for (FileCodeOwnerStatus fileCodeOwnerStatus :
        codeOwnerApprovalCheck
            .getFileStatuses(getChangeNotes(changeId))
            .collect(toImmutableList())) {
      assertThat(fileCodeOwnerStatus)
          .hasNewPathStatus()
          .value()
          .hasStatusThat()
          .isEqualTo(CodeOwnerStatus.APPROVED);
    }

    // Delete the override approval.
    gApi.changes().id(changeId).current().review(new ReviewInput().label("Owners-Override", 0));

    // Without override approval the expected status is INSUFFICIENT_REVIEWERS.
    for (FileCodeOwnerStatus fileCodeOwnerStatus :
        codeOwnerApprovalCheck
            .getFileStatuses(getChangeNotes(changeId))
            .collect(toImmutableList())) {
      assertThat(fileCodeOwnerStatus)
          .hasNewPathStatus()
          .value()
          .hasStatusThat()
          .isEqualTo(CodeOwnerStatus.INSUFFICIENT_REVIEWERS);
    }

    // Add another override approval.
    gApi.changes().id(changeId).current().review(new ReviewInput().label("Another-Override", 1));

    // With override approval the expected status is APPROVED.
    for (FileCodeOwnerStatus fileCodeOwnerStatus :
        codeOwnerApprovalCheck
            .getFileStatuses(getChangeNotes(changeId))
            .collect(toImmutableList())) {
      assertThat(fileCodeOwnerStatus)
          .hasNewPathStatus()
          .value()
          .hasStatusThat()
          .isEqualTo(CodeOwnerStatus.APPROVED);
    }
  }

  private ChangeNotes getChangeNotes(String changeId) throws Exception {
    return changeNotesFactory.create(project, Change.id(gApi.changes().id(changeId).get()._number));
  }
}