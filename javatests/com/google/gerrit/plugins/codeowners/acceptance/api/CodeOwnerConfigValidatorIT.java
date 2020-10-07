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

package com.google.gerrit.plugins.codeowners.acceptance.api;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.TruthJUnit.assume;
import static com.google.gerrit.acceptance.testsuite.project.TestProjectUpdate.allow;
import static com.google.gerrit.acceptance.testsuite.project.TestProjectUpdate.block;
import static com.google.gerrit.server.group.SystemGroupBackend.REGISTERED_USERS;
import static com.google.gerrit.testing.GerritJUnit.assertThrows;

import com.google.common.collect.ImmutableMap;
import com.google.gerrit.acceptance.PushOneCommit;
import com.google.gerrit.acceptance.TestAccount;
import com.google.gerrit.acceptance.config.GerritConfig;
import com.google.gerrit.acceptance.testsuite.project.ProjectOperations;
import com.google.gerrit.acceptance.testsuite.request.RequestScopeOperations;
import com.google.gerrit.entities.Permission;
import com.google.gerrit.entities.Project;
import com.google.gerrit.extensions.api.projects.BranchInput;
import com.google.gerrit.extensions.api.projects.ConfigInput;
import com.google.gerrit.extensions.client.ChangeStatus;
import com.google.gerrit.extensions.client.ProjectState;
import com.google.gerrit.extensions.common.ChangeInput;
import com.google.gerrit.extensions.common.MergeInput;
import com.google.gerrit.extensions.restapi.ResourceConflictException;
import com.google.gerrit.git.ObjectIds;
import com.google.gerrit.plugins.codeowners.JgitPath;
import com.google.gerrit.plugins.codeowners.acceptance.AbstractCodeOwnersIT;
import com.google.gerrit.plugins.codeowners.backend.CodeOwnerBackend;
import com.google.gerrit.plugins.codeowners.backend.CodeOwnerConfig;
import com.google.gerrit.plugins.codeowners.backend.CodeOwnerConfigImportMode;
import com.google.gerrit.plugins.codeowners.backend.CodeOwnerConfigImportType;
import com.google.gerrit.plugins.codeowners.backend.CodeOwnerConfigReference;
import com.google.gerrit.plugins.codeowners.backend.CodeOwnerSet;
import com.google.gerrit.plugins.codeowners.backend.findowners.FindOwnersBackend;
import com.google.gerrit.plugins.codeowners.backend.findowners.FindOwnersCodeOwnerConfigParser;
import com.google.gerrit.plugins.codeowners.backend.proto.ProtoBackend;
import com.google.gerrit.plugins.codeowners.backend.proto.ProtoCodeOwnerConfigParser;
import com.google.gerrit.plugins.codeowners.config.BackendConfig;
import com.google.gerrit.plugins.codeowners.config.StatusConfig;
import com.google.inject.Inject;
import org.eclipse.jgit.lib.AnyObjectId;
import org.eclipse.jgit.lib.ObjectId;
import org.junit.Before;
import org.junit.Test;

/** Tests for {@code com.google.gerrit.plugins.codeowners.validation.CodeOwnerConfigValidator}. */
public class CodeOwnerConfigValidatorIT extends AbstractCodeOwnersIT {
  private static final ObjectId TEST_REVISION =
      ObjectId.fromString("deadbeefdeadbeefdeadbeefdeadbeefdeadbeef");

  @Inject private RequestScopeOperations requestScopeOperations;
  @Inject private ProjectOperations projectOperations;

  private BackendConfig backendConfig;
  private FindOwnersCodeOwnerConfigParser findOwnersCodeOwnerConfigParser;
  private ProtoCodeOwnerConfigParser protoCodeOwnerConfigParser;

  @Before
  public void setUpCodeOwnersPlugin() throws Exception {
    backendConfig = plugin.getSysInjector().getInstance(BackendConfig.class);
    findOwnersCodeOwnerConfigParser =
        plugin.getSysInjector().getInstance(FindOwnersCodeOwnerConfigParser.class);
    protoCodeOwnerConfigParser =
        plugin.getSysInjector().getInstance(ProtoCodeOwnerConfigParser.class);
  }

  @Test
  public void nonCodeOwnerConfigFileIsNotValidated() throws Exception {
    PushOneCommit.Result r = createChange("Add arbitrary file", "arbitrary-file.txt", "INVALID");
    assertOkWithoutMessages(r);
  }

  @Test
  public void canUploadConfigWithoutIssues() throws Exception {
    CodeOwnerConfig.Key codeOwnerConfigKey = createCodeOwnerConfigKey("/");

    // Create a code owner config without issues.
    PushOneCommit.Result r =
        createChange(
            "Add code owners",
            JgitPath.of(getCodeOwnerConfigFilePath(codeOwnerConfigKey)).get(),
            format(
                CodeOwnerConfig.builder(codeOwnerConfigKey, TEST_REVISION)
                    .addCodeOwnerSet(CodeOwnerSet.createWithoutPathExpressions(admin.email()))
                    .build()));
    assertOkWithHints(r, "code owner config files validated, no issues found");
  }

  @Test
  public void canSubmitConfigWithoutIssues() throws Exception {
    CodeOwnerConfig.Key codeOwnerConfigKey = createCodeOwnerConfigKey("/");

    // Create a code owner config without issues.
    PushOneCommit.Result r =
        createChange(
            "Add code owners",
            JgitPath.of(getCodeOwnerConfigFilePath(codeOwnerConfigKey)).get(),
            format(
                CodeOwnerConfig.builder(codeOwnerConfigKey, TEST_REVISION)
                    .addCodeOwnerSet(CodeOwnerSet.createWithoutPathExpressions(admin.email()))
                    .build()));
    r.assertOkStatus();

    // Approve and submit the change.
    approve(r.getChangeId());
    gApi.changes().id(r.getChangeId()).current().submit();
    assertThat(gApi.changes().id(r.getChangeId()).get().status).isEqualTo(ChangeStatus.MERGED);
  }

  @Test
  public void canUploadConfigWithoutIssues_withImport() throws Exception {
    // imports are not supported for the proto backend
    assume().that(backendConfig.getDefaultBackend()).isNotInstanceOf(ProtoBackend.class);

    CodeOwnerConfig.Key codeOwnerConfigKey = createCodeOwnerConfigKey("/");

    // Create a code owner config to be imported.
    CodeOwnerConfig.Key keyOfImportedCodeOwnerConfig =
        codeOwnerConfigOperations
            .newCodeOwnerConfig()
            .project(project)
            .branch("master")
            .folderPath("/foo/")
            .addCodeOwnerEmail(user.email())
            .create();

    CodeOwnerConfigReference codeOwnerConfigReference =
        CodeOwnerConfigReference.create(
            CodeOwnerConfigImportMode.GLOBAL_CODE_OWNER_SETS_ONLY,
            getCodeOwnerConfigFilePath(keyOfImportedCodeOwnerConfig));

    // Create a code owner config with import and without issues.
    PushOneCommit.Result r =
        createChange(
            "Add code owners",
            JgitPath.of(getCodeOwnerConfigFilePath(codeOwnerConfigKey)).get(),
            format(
                CodeOwnerConfig.builder(codeOwnerConfigKey, TEST_REVISION)
                    .addImport(codeOwnerConfigReference)
                    .addCodeOwnerSet(
                        CodeOwnerSet.builder()
                            .addCodeOwnerEmail(admin.email())
                            .addPathExpression("foo")
                            .addImport(codeOwnerConfigReference)
                            .build())
                    .build()));
    assertOkWithHints(r, "code owner config files validated, no issues found");
  }

  @Test
  public void canUploadConfigWithoutIssues_withImportFromOtherProject() throws Exception {
    // imports are not supported for the proto backend
    assume().that(backendConfig.getDefaultBackend()).isNotInstanceOf(ProtoBackend.class);

    CodeOwnerConfig.Key codeOwnerConfigKey = createCodeOwnerConfigKey("/");

    // Create a code owner config to be imported.
    Project.NameKey otherProject = projectOperations.newProject().create();
    CodeOwnerConfig.Key keyOfImportedCodeOwnerConfig =
        codeOwnerConfigOperations
            .newCodeOwnerConfig()
            .project(otherProject)
            .branch("master")
            .folderPath("/foo/")
            .addCodeOwnerEmail(user.email())
            .create();

    CodeOwnerConfigReference codeOwnerConfigReference =
        CodeOwnerConfigReference.builder(
                CodeOwnerConfigImportMode.GLOBAL_CODE_OWNER_SETS_ONLY,
                getCodeOwnerConfigFilePath(keyOfImportedCodeOwnerConfig))
            .setProject(otherProject)
            .build();

    // Create a code owner config with import from other project, and without issues.
    PushOneCommit.Result r =
        createChange(
            "Add code owners",
            JgitPath.of(getCodeOwnerConfigFilePath(codeOwnerConfigKey)).get(),
            format(
                CodeOwnerConfig.builder(codeOwnerConfigKey, TEST_REVISION)
                    .addImport(codeOwnerConfigReference)
                    .addCodeOwnerSet(
                        CodeOwnerSet.builder()
                            .addCodeOwnerEmail(admin.email())
                            .addPathExpression("foo")
                            .addImport(codeOwnerConfigReference)
                            .build())
                    .build()));
    assertOkWithHints(r, "code owner config files validated, no issues found");
  }

  @Test
  public void canUploadConfigWithoutIssues_withImportFromOtherProjectAndBranch() throws Exception {
    // imports are not supported for the proto backend
    assume().that(backendConfig.getDefaultBackend()).isNotInstanceOf(ProtoBackend.class);

    CodeOwnerConfig.Key codeOwnerConfigKey = createCodeOwnerConfigKey("/");

    // Create a code owner config to be imported.
    String otherBranch = "foo";
    Project.NameKey otherProject = projectOperations.newProject().branches(otherBranch).create();
    CodeOwnerConfig.Key keyOfImportedCodeOwnerConfig =
        codeOwnerConfigOperations
            .newCodeOwnerConfig()
            .project(otherProject)
            .branch(otherBranch)
            .folderPath("/foo/")
            .addCodeOwnerEmail(user.email())
            .create();

    CodeOwnerConfigReference codeOwnerConfigReference =
        CodeOwnerConfigReference.builder(
                CodeOwnerConfigImportMode.GLOBAL_CODE_OWNER_SETS_ONLY,
                getCodeOwnerConfigFilePath(keyOfImportedCodeOwnerConfig))
            .setProject(otherProject)
            .setBranch(otherBranch)
            .build();

    // Create a code owner config with import from other project and branch, and without issues.
    PushOneCommit.Result r =
        createChange(
            "Add code owners",
            JgitPath.of(getCodeOwnerConfigFilePath(codeOwnerConfigKey)).get(),
            format(
                CodeOwnerConfig.builder(codeOwnerConfigKey, TEST_REVISION)
                    .addImport(codeOwnerConfigReference)
                    .addCodeOwnerSet(
                        CodeOwnerSet.builder()
                            .addCodeOwnerEmail(admin.email())
                            .addPathExpression("foo")
                            .addImport(codeOwnerConfigReference)
                            .build())
                    .build()));
    assertOkWithHints(r, "code owner config files validated, no issues found");
  }

  @Test
  @GerritConfig(name = "plugin.code-owners.backend", value = "non-existing-backend")
  public void canUploadNonParseableConfigIfCodeOwnersPluginConfigurationIsInvalid()
      throws Exception {
    PushOneCommit.Result r = createChange("Add code owners", "OWNERS", "INVALID");
    assertOkWithWarnings(
        r,
        "skipping validation of code owner config files",
        "code-owners plugin configuration is invalid, cannot validate code owner config files");
  }

  @Test
  public void canUploadNonParseableConfigIfCodeOwnersFunctionalityIsDisabled() throws Exception {
    disableCodeOwnersForProject(project);

    PushOneCommit.Result r =
        createChange(
            "Add code owners",
            JgitPath.of(getCodeOwnerConfigFilePath(createCodeOwnerConfigKey("/"))).get(),
            "INVALID");
    assertOkWithHints(
        r,
        "skipping validation of code owner config files",
        "code-owners functionality is disabled");
  }

  @Test
  @GerritConfig(name = "plugin.code-owners.readOnly", value = "true")
  public void cannotUploadConfigIfConfigsAreConfiguredToBeReadOnly() throws Exception {
    PushOneCommit.Result r =
        createChange(
            "Add code owners",
            JgitPath.of(getCodeOwnerConfigFilePath(createCodeOwnerConfigKey("/"))).get(),
            format(
                CodeOwnerConfig.builder(createCodeOwnerConfigKey("/"), TEST_REVISION)
                    .addCodeOwnerSet(CodeOwnerSet.createWithoutPathExpressions(admin.email()))
                    .build()));
    assertErrorWithMessages(
        r,
        "modifying code owner config files not allowed",
        "code owner config files are configured to be read-only");
  }

  @Test
  @GerritConfig(name = "plugin.code-owners.enableValidationOnCommitReceived", value = "false")
  public void onReceiveCommitValidationDisabled() throws Exception {
    // upload a change with a code owner config that has issues (non-resolvable code owners)
    String unknownEmail = "non-existing-email@example.com";
    CodeOwnerConfig.Key codeOwnerConfigKey = createCodeOwnerConfigKey("/");
    PushOneCommit.Result r =
        createChange(
            "Add code owners",
            JgitPath.of(getCodeOwnerConfigFilePath(codeOwnerConfigKey)).get(),
            format(
                CodeOwnerConfig.builder(codeOwnerConfigKey, TEST_REVISION)
                    .addCodeOwnerSet(CodeOwnerSet.createWithoutPathExpressions(unknownEmail))
                    .build()));
    assertOkWithHints(
        r,
        "skipping validation of code owner config files",
        "code owners config validation is disabled");

    // approve the change
    approve(r.getChangeId());

    // try to submit the change, we expect that this fails since the validation on submit is enabled
    ResourceConflictException exception =
        assertThrows(
            ResourceConflictException.class,
            () -> gApi.changes().id(r.getChangeId()).current().submit());
    assertThat(exception)
        .hasMessageThat()
        .isEqualTo(
            String.format(
                "Failed to submit 1 change due to the following problems:\n"
                    + "Change %d: invalid code owner config files:\n"
                    + "  ERROR: code owner email '%s' in '%s' cannot be resolved for %s",
                r.getChange().getId().get(),
                unknownEmail,
                getCodeOwnerConfigFilePath(codeOwnerConfigKey),
                identifiedUserFactory.create(admin.id()).getLoggableName()));
  }

  @Test
  public void noValidationOnDeletionOfConfig() throws Exception {
    // Disable the code owners functionality so that we can upload an invalid config that we can
    // delete afterwards.
    disableCodeOwnersForProject(project);

    String path = JgitPath.of(getCodeOwnerConfigFilePath(createCodeOwnerConfigKey("/"))).get();
    PushOneCommit.Result r = createChange("Add code owners", path, "INVALID");
    r.assertOkStatus();

    // re-enable the code owners functionality for the project
    setCodeOwnersConfig(project, null, StatusConfig.KEY_DISABLED, "false");

    // delete the invalid code owner config file
    PushOneCommit push =
        pushFactory.create(admin.newIdent(), testRepo, "Delete code owner config", path, "");
    r = push.rm("refs/for/master");
    assertOkWithoutMessages(r);
  }

  @Test
  public void canUploadNonParseableConfigIfItWasAlreadyNonParseable() throws Exception {
    CodeOwnerConfig.Key codeOwnerConfigKey = createCodeOwnerConfigKey("/");

    // disable the code owners functionality so that we can upload an initial code owner config that
    // is not parseable
    disableCodeOwnersForProject(project);

    // upload an initial code owner config that is not parseable
    PushOneCommit.Result r =
        createChange(
            "Add code owners",
            JgitPath.of(getCodeOwnerConfigFilePath(codeOwnerConfigKey)).get(),
            "INVALID");
    r.assertOkStatus();

    // re-enable the code owners functionality for the project
    setCodeOwnersConfig(project, null, StatusConfig.KEY_DISABLED, "false");

    // update the code owner config so that it is still not parseable
    r =
        createChange(
            "Update code owners",
            JgitPath.of(getCodeOwnerConfigFilePath(codeOwnerConfigKey)).get(),
            "STILL INVALID");
    assertOkWithWarnings(
        r,
        "invalid code owner config files",
        String.format(
            "invalid code owner config file '%s':\n  %s",
            getCodeOwnerConfigFilePath(codeOwnerConfigKey),
            getParsingErrorMessage(
                ImmutableMap.of(
                    FindOwnersBackend.class,
                    "invalid line: STILL INVALID",
                    ProtoBackend.class,
                    "1:7: expected \"{\""))));
  }

  @Test
  public void canUploadConfigWithIssuesIfItWasNonParseableBefore() throws Exception {
    CodeOwnerConfig.Key codeOwnerConfigKey = createCodeOwnerConfigKey("/");

    // disable the code owners functionality so that we can upload an initial code owner config that
    // is not parseable
    disableCodeOwnersForProject(project);

    // upload an initial code owner config that is not parseable
    PushOneCommit.Result r =
        createChange(
            "Add code owners",
            JgitPath.of(getCodeOwnerConfigFilePath(codeOwnerConfigKey)).get(),
            "INVALID");
    r.assertOkStatus();

    // re-enable the code owners functionality for the project
    setCodeOwnersConfig(project, null, StatusConfig.KEY_DISABLED, "false");

    // update the code owner config so that it is parseable now, but has validation issues
    String unknownEmail1 = "non-existing-email@example.com";
    String unknownEmail2 = "another-unknown-email@example.com";
    r =
        createChange(
            "Update code owners",
            JgitPath.of(getCodeOwnerConfigFilePath(codeOwnerConfigKey)).get(),
            format(
                CodeOwnerConfig.builder(codeOwnerConfigKey, TEST_REVISION)
                    .addCodeOwnerSet(
                        CodeOwnerSet.createWithoutPathExpressions(
                            unknownEmail1, admin.email(), unknownEmail2))
                    .build()));
    assertOkWithWarnings(
        r,
        "invalid code owner config files",
        String.format(
            "code owner email '%s' in '%s' cannot be resolved for %s",
            unknownEmail1,
            getCodeOwnerConfigFilePath(codeOwnerConfigKey),
            identifiedUserFactory.create(admin.id()).getLoggableName()),
        String.format(
            "code owner email '%s' in '%s' cannot be resolved for %s",
            unknownEmail2,
            getCodeOwnerConfigFilePath(codeOwnerConfigKey),
            identifiedUserFactory.create(admin.id()).getLoggableName()));
  }

  @Test
  public void canUploadConfigWithIssuesIfTheyExistedBefore() throws Exception {
    CodeOwnerConfig.Key codeOwnerConfigKey = createCodeOwnerConfigKey("/");

    // disable the code owners functionality so that we can upload an initial code owner config that
    // has issues
    disableCodeOwnersForProject(project);

    // upload an initial code owner config that has issues (non-resolvable code owners)
    String unknownEmail = "non-existing-email@example.com";
    PushOneCommit.Result r =
        createChange(
            "Add code owners",
            JgitPath.of(getCodeOwnerConfigFilePath(codeOwnerConfigKey)).get(),
            format(
                CodeOwnerConfig.builder(codeOwnerConfigKey, TEST_REVISION)
                    .addCodeOwnerSet(CodeOwnerSet.createWithoutPathExpressions(unknownEmail))
                    .build()));
    r.assertOkStatus();

    // re-enable the code owners functionality for the project
    setCodeOwnersConfig(project, null, StatusConfig.KEY_DISABLED, "false");

    // update the code owner config so that the validation issue still exists, but no new issue is
    // introduced
    r =
        createChange(
            "Update code owners",
            JgitPath.of(getCodeOwnerConfigFilePath(codeOwnerConfigKey)).get(),
            format(
                CodeOwnerConfig.builder(codeOwnerConfigKey, TEST_REVISION)
                    .addCodeOwnerSet(
                        CodeOwnerSet.createWithoutPathExpressions(unknownEmail, admin.email()))
                    .build()));
    assertOkWithWarnings(
        r,
        "invalid code owner config files",
        String.format(
            "code owner email '%s' in '%s' cannot be resolved for %s",
            unknownEmail,
            getCodeOwnerConfigFilePath(codeOwnerConfigKey),
            identifiedUserFactory.create(admin.id()).getLoggableName()));
  }

  @Test
  public void cannotUploadNonParseableConfig() throws Exception {
    CodeOwnerConfig.Key codeOwnerConfigKey = createCodeOwnerConfigKey("/");

    PushOneCommit.Result r =
        createChange(
            "Add code owners",
            JgitPath.of(getCodeOwnerConfigFilePath(codeOwnerConfigKey)).get(),
            "INVALID");
    assertErrorWithMessages(
        r,
        "invalid code owner config files",
        String.format(
            "invalid code owner config file '%s':\n  %s",
            getCodeOwnerConfigFilePath(codeOwnerConfigKey),
            getParsingErrorMessage(
                ImmutableMap.of(
                    FindOwnersBackend.class,
                    "invalid line: INVALID",
                    ProtoBackend.class,
                    "1:8: expected \"{\""))));
  }

  @Test
  public void issuesAreReportedForAllInvalidConfigs() throws Exception {
    CodeOwnerConfig.Key codeOwnerConfigKey1 = createCodeOwnerConfigKey("/");
    CodeOwnerConfig.Key codeOwnerConfigKey2 = createCodeOwnerConfigKey("/foo/bar/");

    PushOneCommit push =
        pushFactory.create(
            admin.newIdent(),
            testRepo,
            "Add code owners",
            ImmutableMap.of(
                JgitPath.of(getCodeOwnerConfigFilePath(codeOwnerConfigKey1)).get(),
                "INVALID",
                JgitPath.of(getCodeOwnerConfigFilePath(codeOwnerConfigKey2)).get(),
                "ALSO-INVALID"));
    PushOneCommit.Result r = push.to("refs/for/master");
    assertErrorWithMessages(
        r,
        "invalid code owner config files",
        String.format(
            "invalid code owner config file '%s':\n  %s",
            getCodeOwnerConfigFilePath(codeOwnerConfigKey1),
            getParsingErrorMessage(
                ImmutableMap.of(
                    FindOwnersBackend.class,
                    "invalid line: INVALID",
                    ProtoBackend.class,
                    "1:8: expected \"{\""))),
        String.format(
            "invalid code owner config file '%s':\n  %s",
            getCodeOwnerConfigFilePath(codeOwnerConfigKey2),
            getParsingErrorMessage(
                ImmutableMap.of(
                    FindOwnersBackend.class,
                    "invalid line: ALSO-INVALID",
                    ProtoBackend.class,
                    "1:1: expected identifier. found 'ALSO-INVALID'"))));
  }

  @Test
  public void cannotUploadConfigWithNonResolvableCodeOwners() throws Exception {
    CodeOwnerConfig.Key codeOwnerConfigKey = createCodeOwnerConfigKey("/");

    String unknownEmail1 = "non-existing-email@example.com";
    String unknownEmail2 = "another-unknown-email@example.com";
    PushOneCommit.Result r =
        createChange(
            "Add code owners",
            JgitPath.of(getCodeOwnerConfigFilePath(codeOwnerConfigKey)).get(),
            format(
                CodeOwnerConfig.builder(codeOwnerConfigKey, TEST_REVISION)
                    .addCodeOwnerSet(
                        CodeOwnerSet.createWithoutPathExpressions(
                            unknownEmail1, admin.email(), unknownEmail2))
                    .build()));
    assertErrorWithMessages(
        r,
        "invalid code owner config files",
        String.format(
            "code owner email '%s' in '%s' cannot be resolved for %s",
            unknownEmail1,
            getCodeOwnerConfigFilePath(codeOwnerConfigKey),
            identifiedUserFactory.create(admin.id()).getLoggableName()),
        String.format(
            "code owner email '%s' in '%s' cannot be resolved for %s",
            unknownEmail2,
            getCodeOwnerConfigFilePath(codeOwnerConfigKey),
            identifiedUserFactory.create(admin.id()).getLoggableName()));
  }

  @Test
  @GerritConfig(name = "plugin.code-owners.allowedEmailDomain", value = "example.com")
  public void cannotUploadConfigThatAssignsCodeOwnershipToAnEmailWithANonAllowedEmailDomain()
      throws Exception {
    CodeOwnerConfig.Key codeOwnerConfigKey = createCodeOwnerConfigKey("/");

    String emailWithNonAllowedDomain = "foo@example.net";
    PushOneCommit.Result r =
        createChange(
            "Add code owners",
            JgitPath.of(getCodeOwnerConfigFilePath(codeOwnerConfigKey)).get(),
            format(
                CodeOwnerConfig.builder(codeOwnerConfigKey, TEST_REVISION)
                    .addCodeOwnerSet(
                        CodeOwnerSet.createWithoutPathExpressions(
                            emailWithNonAllowedDomain, admin.email()))
                    .build()));
    assertErrorWithMessages(
        r,
        "invalid code owner config files",
        String.format(
            "the domain of the code owner email '%s' in '%s' is not allowed for" + " code owners",
            emailWithNonAllowedDomain, getCodeOwnerConfigFilePath(codeOwnerConfigKey)));
  }

  @Test
  public void cannotUploadConfigWithNewIssues() throws Exception {
    CodeOwnerConfig.Key codeOwnerConfigKey = createCodeOwnerConfigKey("/");

    // disable the code owners functionality so that we can upload an initial code owner config that
    // has issues
    disableCodeOwnersForProject(project);

    // upload an initial code owner config that has issues (non-resolvable code owners)
    String unknownEmail1 = "non-existing-email@example.com";
    PushOneCommit.Result r =
        createChange(
            "Add code owners",
            JgitPath.of(getCodeOwnerConfigFilePath(codeOwnerConfigKey)).get(),
            format(
                CodeOwnerConfig.builder(codeOwnerConfigKey, TEST_REVISION)
                    .addCodeOwnerSet(CodeOwnerSet.createWithoutPathExpressions(unknownEmail1))
                    .build()));
    r.assertOkStatus();

    // re-enable the code owners functionality for the project
    setCodeOwnersConfig(project, null, StatusConfig.KEY_DISABLED, "false");

    // update the code owner config so that the validation issue still exists and a new issue is
    // introduced
    String unknownEmail2 = "another-unknown-email@example.com";
    r =
        createChange(
            "Update code owners",
            JgitPath.of(getCodeOwnerConfigFilePath(codeOwnerConfigKey)).get(),
            format(
                CodeOwnerConfig.builder(codeOwnerConfigKey, TEST_REVISION)
                    .addCodeOwnerSet(
                        CodeOwnerSet.createWithoutPathExpressions(unknownEmail1, unknownEmail2))
                    .build()));

    String abbreviatedCommit = abbreviateName(r.getCommit());
    r.assertErrorStatus(
        String.format("commit %s: %s", abbreviatedCommit, "invalid code owner config files"));
    r.assertMessage(
        String.format(
            "error: commit %s: %s",
            abbreviatedCommit,
            String.format(
                "code owner email '%s' in '%s' cannot be resolved for %s",
                unknownEmail2,
                getCodeOwnerConfigFilePath(codeOwnerConfigKey),
                identifiedUserFactory.create(admin.id()).getLoggableName())));

    // the pre-existing issue is returned as warning
    r.assertMessage(
        String.format(
            "warning: commit %s: code owner email '%s' in '%s' cannot be resolved for %s",
            abbreviatedCommit,
            unknownEmail1,
            getCodeOwnerConfigFilePath(codeOwnerConfigKey),
            identifiedUserFactory.create(admin.id()).getLoggableName()));

    r.assertNotMessage("hint");
  }

  @Test
  public void cannotSubmitConfigWithNewIssues() throws Exception {
    CodeOwnerConfig.Key codeOwnerConfigKey = createCodeOwnerConfigKey("/");

    // disable the code owners functionality so that we can upload a a change with a code owner
    // config that has issues
    disableCodeOwnersForProject(project);

    // upload a change with a code owner config that has issues (non-resolvable code owners)
    String unknownEmail = "non-existing-email@example.com";
    PushOneCommit.Result r =
        createChange(
            "Add code owners",
            JgitPath.of(getCodeOwnerConfigFilePath(codeOwnerConfigKey)).get(),
            format(
                CodeOwnerConfig.builder(codeOwnerConfigKey, TEST_REVISION)
                    .addCodeOwnerSet(CodeOwnerSet.createWithoutPathExpressions(unknownEmail))
                    .build()));
    r.assertOkStatus();

    // re-enable the code owners functionality for the project
    setCodeOwnersConfig(project, null, StatusConfig.KEY_DISABLED, "false");

    // approve the change
    approve(r.getChangeId());

    // try to submit the change
    ResourceConflictException exception =
        assertThrows(
            ResourceConflictException.class,
            () -> gApi.changes().id(r.getChangeId()).current().submit());
    assertThat(exception)
        .hasMessageThat()
        .isEqualTo(
            String.format(
                "Failed to submit 1 change due to the following problems:\n"
                    + "Change %d: invalid code owner config files:\n"
                    + "  ERROR: code owner email '%s' in '%s' cannot be resolved for %s",
                r.getChange().getId().get(),
                unknownEmail,
                getCodeOwnerConfigFilePath(codeOwnerConfigKey),
                identifiedUserFactory.create(admin.id()).getLoggableName()));
  }

  @Test
  @GerritConfig(name = "accounts.visibility", value = "SAME_GROUP")
  public void cannotSubmitConfigWithCodeOwnersThatAreNotVisibleToThePatchSetUploader()
      throws Exception {
    // Create a new user that is not a member of any group. This means 'user' and 'admin' are not
    // visible to this user since they do not share any group.
    TestAccount user2 = accountCreator.user2();

    CodeOwnerConfig.Key codeOwnerConfigKey = createCodeOwnerConfigKey("/");

    // disable the code owners functionality so that we can upload a change with a code owner
    // config that has issues
    disableCodeOwnersForProject(project);

    // upload a change as user2 with a code owner config that contains a code owner that is not
    // visible to user2
    PushOneCommit.Result r =
        createChange(
            user2,
            "Add code owners",
            JgitPath.of(getCodeOwnerConfigFilePath(codeOwnerConfigKey)).get(),
            format(
                CodeOwnerConfig.builder(codeOwnerConfigKey, TEST_REVISION)
                    .addCodeOwnerSet(CodeOwnerSet.createWithoutPathExpressions(admin.email()))
                    .build()));
    r.assertOkStatus();

    // re-enable the code owners functionality for the project
    setCodeOwnersConfig(project, null, StatusConfig.KEY_DISABLED, "false");

    // approve the change
    approve(r.getChangeId());

    // try to submit the change as admin who can see the code owners in the config, the submit still
    // fails because it is checked that the uploader (user2) can see the code owners
    ResourceConflictException exception =
        assertThrows(
            ResourceConflictException.class,
            () -> gApi.changes().id(r.getChangeId()).current().submit());
    assertThat(exception)
        .hasMessageThat()
        .isEqualTo(
            String.format(
                "Failed to submit 1 change due to the following problems:\n"
                    + "Change %d: invalid code owner config files:\n"
                    + "  ERROR: code owner email '%s' in '%s' cannot be resolved for %s",
                r.getChange().getId().get(),
                admin.email(),
                getCodeOwnerConfigFilePath(codeOwnerConfigKey),
                identifiedUserFactory.create(user2.id()).getLoggableName()));
  }

  @Test
  @GerritConfig(name = "accounts.visibility", value = "SAME_GROUP")
  public void canSubmitConfigWithCodeOwnersThatAreNotVisibleToTheSubmitterButVisibleToTheUploader()
      throws Exception {
    // Create a new user that is not a member of any group. This means 'user' and 'admin' are not
    // visible to this user since they do not share any group.
    TestAccount user2 = accountCreator.user2();

    CodeOwnerConfig.Key codeOwnerConfigKey = createCodeOwnerConfigKey("/");

    // upload a change as admin with a code owner config that contains a code owner that is not
    // visible to user2
    PushOneCommit.Result r =
        createChange(
            "Add code owners",
            JgitPath.of(getCodeOwnerConfigFilePath(codeOwnerConfigKey)).get(),
            format(
                CodeOwnerConfig.builder(codeOwnerConfigKey, TEST_REVISION)
                    .addCodeOwnerSet(CodeOwnerSet.createWithoutPathExpressions(user.email()))
                    .build()));
    r.assertOkStatus();

    // approve the change
    approve(r.getChangeId());

    // grant user2 submit permissions
    projectOperations
        .project(project)
        .forUpdate()
        .add(allow(Permission.SUBMIT).ref("refs/*").group(REGISTERED_USERS))
        .update();

    // submit the change as user2 who cannot see the code owners in the config, the submit succeeds
    // because it is checked that the uploader (admin) can see the code owners
    requestScopeOperations.setApiUser(user2.id());
    gApi.changes().id(r.getChangeId()).current().submit();
    assertThat(gApi.changes().id(r.getChangeId()).get().status).isEqualTo(ChangeStatus.MERGED);
  }

  @Test
  public void cannotUploadConfigWithGlobalImportFromNonExistingProject() throws Exception {
    testUploadConfigWithImportFromNonExistingProject(CodeOwnerConfigImportType.GLOBAL);
  }

  @Test
  public void cannotUploadConfigWithPerFileImportFromNonExistingProject() throws Exception {
    testUploadConfigWithImportFromNonExistingProject(CodeOwnerConfigImportType.PER_FILE);
  }

  private void testUploadConfigWithImportFromNonExistingProject(
      CodeOwnerConfigImportType importType) throws Exception {
    // imports are not supported for the proto backend
    assume().that(backendConfig.getDefaultBackend()).isNotInstanceOf(ProtoBackend.class);

    // create a code owner config that imports a code owner config from a non-existing project
    CodeOwnerConfig.Key keyOfImportingCodeOwnerConfig = createCodeOwnerConfigKey("/");
    Project.NameKey nonExistingProject = Project.nameKey("non-existing");
    CodeOwnerConfigReference codeOwnerConfigReference =
        CodeOwnerConfigReference.builder(
                CodeOwnerConfigImportMode.GLOBAL_CODE_OWNER_SETS_ONLY,
                getCodeOwnerConfigFilePath(
                    CodeOwnerConfig.Key.create(nonExistingProject, "master", "/")))
            .setProject(nonExistingProject)
            .build();
    CodeOwnerConfig codeOwnerConfig =
        createCodeOwnerConfigWithImport(
            keyOfImportingCodeOwnerConfig, importType, codeOwnerConfigReference);

    PushOneCommit.Result r =
        createChange(
            "Add code owners",
            JgitPath.of(getCodeOwnerConfigFilePath(keyOfImportingCodeOwnerConfig)).get(),
            format(codeOwnerConfig));
    assertErrorWithMessages(
        r,
        "invalid code owner config files",
        String.format(
            "invalid %s import in '%s': project '%s' not found",
            importType.getType(),
            getCodeOwnerConfigFilePath(keyOfImportingCodeOwnerConfig),
            nonExistingProject.get()));
  }

  @Test
  public void cannotUploadConfigWithGlobalImportFromNonVisibleProject() throws Exception {
    testUploadConfigWithImportFromNonVisibleProject(CodeOwnerConfigImportType.GLOBAL);
  }

  @Test
  public void cannotUploadConfigWithPerFileImportFromNonVisibleProject() throws Exception {
    testUploadConfigWithImportFromNonVisibleProject(CodeOwnerConfigImportType.PER_FILE);
  }

  private void testUploadConfigWithImportFromNonVisibleProject(CodeOwnerConfigImportType importType)
      throws Exception {
    // imports are not supported for the proto backend
    assume().that(backendConfig.getDefaultBackend()).isNotInstanceOf(ProtoBackend.class);

    // create a non-visible project with a code owner config file that we try to import
    Project.NameKey nonVisibleProject =
        projectOperations.newProject().name(name("non-visible-project")).create();
    projectOperations
        .project(nonVisibleProject)
        .forUpdate()
        .add(block(Permission.READ).ref("refs/*").group(REGISTERED_USERS))
        .update();
    codeOwnerConfigOperations
        .newCodeOwnerConfig()
        .project(nonVisibleProject)
        .branch("master")
        .folderPath("/")
        .addCodeOwnerEmail(user.email())
        .create();

    // create a code owner config that imports a code owner config from a non-visible project
    CodeOwnerConfig.Key keyOfImportingCodeOwnerConfig = createCodeOwnerConfigKey("/");
    CodeOwnerConfigReference codeOwnerConfigReference =
        CodeOwnerConfigReference.builder(
                CodeOwnerConfigImportMode.GLOBAL_CODE_OWNER_SETS_ONLY,
                getCodeOwnerConfigFilePath(
                    CodeOwnerConfig.Key.create(nonVisibleProject, "master", "/")))
            .setProject(nonVisibleProject)
            .build();
    CodeOwnerConfig codeOwnerConfig =
        createCodeOwnerConfigWithImport(
            keyOfImportingCodeOwnerConfig, importType, codeOwnerConfigReference);

    PushOneCommit.Result r =
        createChange(
            user,
            "Add code owners",
            JgitPath.of(getCodeOwnerConfigFilePath(keyOfImportingCodeOwnerConfig)).get(),
            format(codeOwnerConfig));
    assertErrorWithMessages(
        r,
        "invalid code owner config files",
        String.format(
            "invalid %s import in '%s': project '%s' not found",
            importType.getType(),
            getCodeOwnerConfigFilePath(keyOfImportingCodeOwnerConfig),
            nonVisibleProject.get()));
  }

  @Test
  public void cannotUploadConfigWithGlobalImportFromHiddenProject() throws Exception {
    testUploadConfigWithImportFromHiddenProject(CodeOwnerConfigImportType.GLOBAL);
  }

  @Test
  public void cannotUploadConfigWithPerFileImportFromHiddenProject() throws Exception {
    testUploadConfigWithImportFromHiddenProject(CodeOwnerConfigImportType.PER_FILE);
  }

  private void testUploadConfigWithImportFromHiddenProject(CodeOwnerConfigImportType importType)
      throws Exception {
    // imports are not supported for the proto backend
    assume().that(backendConfig.getDefaultBackend()).isNotInstanceOf(ProtoBackend.class);

    // create a hidden project with a code owner config file
    Project.NameKey hiddenProject =
        projectOperations.newProject().name(name("hidden-project")).create();
    ConfigInput configInput = new ConfigInput();
    configInput.state = ProjectState.HIDDEN;
    gApi.projects().name(hiddenProject.get()).config(configInput);
    codeOwnerConfigOperations
        .newCodeOwnerConfig()
        .project(hiddenProject)
        .branch("master")
        .folderPath("/")
        .addCodeOwnerEmail(user.email())
        .create();

    // create a code owner config that imports a code owner config from a hidden project
    CodeOwnerConfig.Key keyOfImportingCodeOwnerConfig = createCodeOwnerConfigKey("/");
    CodeOwnerConfigReference codeOwnerConfigReference =
        CodeOwnerConfigReference.builder(
                CodeOwnerConfigImportMode.GLOBAL_CODE_OWNER_SETS_ONLY,
                getCodeOwnerConfigFilePath(
                    CodeOwnerConfig.Key.create(hiddenProject, "master", "/")))
            .setProject(hiddenProject)
            .build();
    CodeOwnerConfig codeOwnerConfig =
        createCodeOwnerConfigWithImport(
            keyOfImportingCodeOwnerConfig, importType, codeOwnerConfigReference);

    PushOneCommit.Result r =
        createChange(
            "Add code owners",
            JgitPath.of(getCodeOwnerConfigFilePath(keyOfImportingCodeOwnerConfig)).get(),
            format(codeOwnerConfig));
    assertErrorWithMessages(
        r,
        "invalid code owner config files",
        String.format(
            "invalid %s import in '%s': project '%s' has state 'hidden' that doesn't permit read",
            importType.getType(),
            getCodeOwnerConfigFilePath(keyOfImportingCodeOwnerConfig),
            hiddenProject.get()));
  }

  @Test
  public void cannotUploadConfigWithGlobalImportFromNonExistingBranch() throws Exception {
    testUploadConfigWithImportFromNonExistingBranch(CodeOwnerConfigImportType.GLOBAL);
  }

  @Test
  public void cannotUploadConfigWithPerFileImportFromNonExistingBranch() throws Exception {
    testUploadConfigWithImportFromNonExistingBranch(CodeOwnerConfigImportType.PER_FILE);
  }

  private void testUploadConfigWithImportFromNonExistingBranch(CodeOwnerConfigImportType importType)
      throws Exception {
    // imports are not supported for the proto backend
    assume().that(backendConfig.getDefaultBackend()).isNotInstanceOf(ProtoBackend.class);

    // create a code owner config that imports a code owner config from a non-existing branch
    CodeOwnerConfig.Key keyOfImportingCodeOwnerConfig = createCodeOwnerConfigKey("/");
    Project.NameKey otherProject = projectOperations.newProject().name(name("other")).create();
    CodeOwnerConfigReference codeOwnerConfigReference =
        CodeOwnerConfigReference.builder(
                CodeOwnerConfigImportMode.GLOBAL_CODE_OWNER_SETS_ONLY,
                getCodeOwnerConfigFilePath(
                    CodeOwnerConfig.Key.create(otherProject, "non-existing", "/")))
            .setProject(otherProject)
            .setBranch("non-existing")
            .build();
    CodeOwnerConfig codeOwnerConfig =
        createCodeOwnerConfigWithImport(
            keyOfImportingCodeOwnerConfig, importType, codeOwnerConfigReference);

    PushOneCommit.Result r =
        createChange(
            "Add code owners",
            JgitPath.of(getCodeOwnerConfigFilePath(keyOfImportingCodeOwnerConfig)).get(),
            format(codeOwnerConfig));
    assertErrorWithMessages(
        r,
        "invalid code owner config files",
        String.format(
            "invalid %s import in '%s': branch 'non-existing' not found in project '%s'",
            importType.getType(),
            getCodeOwnerConfigFilePath(keyOfImportingCodeOwnerConfig),
            otherProject.get()));
  }

  @Test
  public void cannotUploadConfigWithGlobalImportFromNonVisibleBranch() throws Exception {
    testUploadConfigWithImportFromNonVisibleBranch(CodeOwnerConfigImportType.GLOBAL);
  }

  @Test
  public void cannotUploadConfigWithPerFileImportFromNonVisibleBranch() throws Exception {
    testUploadConfigWithImportFromNonVisibleBranch(CodeOwnerConfigImportType.PER_FILE);
  }

  private void testUploadConfigWithImportFromNonVisibleBranch(CodeOwnerConfigImportType importType)
      throws Exception {
    // imports are not supported for the proto backend
    assume().that(backendConfig.getDefaultBackend()).isNotInstanceOf(ProtoBackend.class);

    CodeOwnerConfig.Key keyOfImportingCodeOwnerConfig = createCodeOwnerConfigKey("/");

    // create a project with a non-visible branch that contains a code owner config file
    Project.NameKey otherProject =
        projectOperations.newProject().name(name("non-visible-project")).create();
    projectOperations
        .project(otherProject)
        .forUpdate()
        .add(block(Permission.READ).ref("refs/heads/master").group(REGISTERED_USERS))
        .update();
    codeOwnerConfigOperations
        .newCodeOwnerConfig()
        .project(otherProject)
        .branch("master")
        .folderPath("/")
        .addCodeOwnerEmail(user.email())
        .create();

    // create a code owner config that imports a code owner config from a non-visible branch
    CodeOwnerConfigReference codeOwnerConfigReference =
        CodeOwnerConfigReference.builder(
                CodeOwnerConfigImportMode.GLOBAL_CODE_OWNER_SETS_ONLY,
                getCodeOwnerConfigFilePath(CodeOwnerConfig.Key.create(otherProject, "master", "/")))
            .setProject(otherProject)
            .setBranch("master")
            .build();
    CodeOwnerConfig codeOwnerConfig =
        createCodeOwnerConfigWithImport(
            keyOfImportingCodeOwnerConfig, importType, codeOwnerConfigReference);

    PushOneCommit.Result r =
        createChange(
            user,
            "Add code owners",
            JgitPath.of(getCodeOwnerConfigFilePath(keyOfImportingCodeOwnerConfig)).get(),
            format(codeOwnerConfig));
    assertErrorWithMessages(
        r,
        "invalid code owner config files",
        String.format(
            "invalid %s import in '%s': branch 'master' not found in project '%s'",
            importType.getType(),
            getCodeOwnerConfigFilePath(keyOfImportingCodeOwnerConfig),
            otherProject.get()));
  }

  @Test
  public void cannotUploadConfigWithGlobalImportOfNonCodeOwnerConfigFile() throws Exception {
    testUploadConfigWithImportOfNonCodeOwnerConfigFile(CodeOwnerConfigImportType.GLOBAL);
  }

  @Test
  public void cannotUploadConfigWithPerFileImportOfNonCodeOwnerConfigFile() throws Exception {
    testUploadConfigWithImportOfNonCodeOwnerConfigFile(CodeOwnerConfigImportType.PER_FILE);
  }

  private void testUploadConfigWithImportOfNonCodeOwnerConfigFile(
      CodeOwnerConfigImportType importType) throws Exception {
    // imports are not supported for the proto backend
    assume().that(backendConfig.getDefaultBackend()).isNotInstanceOf(ProtoBackend.class);

    // create a code owner config that imports a non code owner config file
    CodeOwnerConfig.Key keyOfImportingCodeOwnerConfig = createCodeOwnerConfigKey("/");
    CodeOwnerConfigReference codeOwnerConfigReference =
        CodeOwnerConfigReference.builder(
                CodeOwnerConfigImportMode.GLOBAL_CODE_OWNER_SETS_ONLY, "non-code-owner-config.txt")
            .build();
    CodeOwnerConfig codeOwnerConfig =
        createCodeOwnerConfigWithImport(
            keyOfImportingCodeOwnerConfig, importType, codeOwnerConfigReference);

    PushOneCommit.Result r =
        createChange(
            user,
            "Add code owners",
            JgitPath.of(getCodeOwnerConfigFilePath(keyOfImportingCodeOwnerConfig)).get(),
            format(codeOwnerConfig));
    assertErrorWithMessages(
        r,
        "invalid code owner config files",
        String.format(
            "invalid %s import in '%s':"
                + " 'non-code-owner-config.txt' is not a code owner config file",
            importType.getType(), getCodeOwnerConfigFilePath(keyOfImportingCodeOwnerConfig)));
  }

  @Test
  public void cannotUploadConfigWithGlobalImportOfNonExistingCodeOwnerConfig() throws Exception {
    testUploadConfigWithImportOfNonExistingCodeOwnerConfig(CodeOwnerConfigImportType.GLOBAL);
  }

  @Test
  public void cannotUploadConfigWithPerFileImportOfNonExistingCodeOwnerConfig() throws Exception {
    testUploadConfigWithImportOfNonExistingCodeOwnerConfig(CodeOwnerConfigImportType.PER_FILE);
  }

  private void testUploadConfigWithImportOfNonExistingCodeOwnerConfig(
      CodeOwnerConfigImportType importType) throws Exception {
    // imports are not supported for the proto backend
    assume().that(backendConfig.getDefaultBackend()).isNotInstanceOf(ProtoBackend.class);

    // create a code owner config that imports a non-existing code owner config
    CodeOwnerConfig.Key keyOfImportingCodeOwnerConfig = createCodeOwnerConfigKey("/");
    CodeOwnerConfig.Key keyOfNonExistingCodeOwnerConfig =
        CodeOwnerConfig.Key.create(project, "master", "/foo/");
    CodeOwnerConfigReference codeOwnerConfigReference =
        CodeOwnerConfigReference.builder(
                CodeOwnerConfigImportMode.GLOBAL_CODE_OWNER_SETS_ONLY,
                getCodeOwnerConfigFilePath(keyOfNonExistingCodeOwnerConfig))
            .build();
    CodeOwnerConfig codeOwnerConfig =
        createCodeOwnerConfigWithImport(
            keyOfImportingCodeOwnerConfig, importType, codeOwnerConfigReference);

    PushOneCommit.Result r =
        createChange(
            user,
            "Add code owners",
            JgitPath.of(getCodeOwnerConfigFilePath(keyOfImportingCodeOwnerConfig)).get(),
            format(codeOwnerConfig));
    assertErrorWithMessages(
        r,
        "invalid code owner config files",
        String.format(
            "invalid %s import in '%s': '%s' does not exist (project = %s, branch = master)",
            importType.getType(),
            getCodeOwnerConfigFilePath(keyOfImportingCodeOwnerConfig),
            getCodeOwnerConfigFilePath(keyOfNonExistingCodeOwnerConfig),
            project.get()));
  }

  @Test
  public void cannotUploadConfigWithGlobalImportOfNonParseableCodeOwnerConfig() throws Exception {
    testUploadConfigWithImportOfNonParseableCodeOwnerConfig(CodeOwnerConfigImportType.GLOBAL);
  }

  @Test
  public void cannotUploadConfigWithPerFileImportOfNonParseableCodeOwnerConfig() throws Exception {
    testUploadConfigWithImportOfNonParseableCodeOwnerConfig(CodeOwnerConfigImportType.PER_FILE);
  }

  private void testUploadConfigWithImportOfNonParseableCodeOwnerConfig(
      CodeOwnerConfigImportType importType) throws Exception {
    // imports are not supported for the proto backend
    assume().that(backendConfig.getDefaultBackend()).isNotInstanceOf(ProtoBackend.class);

    CodeOwnerConfig.Key keyOfImportedCodeOwnerConfig =
        CodeOwnerConfig.Key.create(project, "master", "/foo/");

    // disable the code owners functionality so that we can upload a non-parseable code owner config
    // that we then try to import
    disableCodeOwnersForProject(project);

    // upload a non-parseable code owner config that we then try to import
    PushOneCommit.Result r =
        createChange(
            "Add invalid code owner config",
            JgitPath.of(getCodeOwnerConfigFilePath(keyOfImportedCodeOwnerConfig)).get(),
            "INVALID");
    r.assertOkStatus();
    approve(r.getChangeId());
    gApi.changes().id(r.getChangeId()).current().submit();

    // re-enable the code owners functionality for the project
    setCodeOwnersConfig(project, null, StatusConfig.KEY_DISABLED, "false");

    // create a code owner config that imports a non-parseable code owner config
    CodeOwnerConfig.Key keyOfImportingCodeOwnerConfig = createCodeOwnerConfigKey("/");
    CodeOwnerConfigReference codeOwnerConfigReference =
        CodeOwnerConfigReference.builder(
                CodeOwnerConfigImportMode.GLOBAL_CODE_OWNER_SETS_ONLY,
                getCodeOwnerConfigFilePath(keyOfImportedCodeOwnerConfig))
            .build();
    CodeOwnerConfig codeOwnerConfig =
        createCodeOwnerConfigWithImport(
            keyOfImportingCodeOwnerConfig, importType, codeOwnerConfigReference);

    r =
        createChange(
            "Add code owners",
            JgitPath.of(getCodeOwnerConfigFilePath(keyOfImportingCodeOwnerConfig)).get(),
            format(codeOwnerConfig));
    assertErrorWithMessages(
        r,
        "invalid code owner config files",
        String.format(
            "invalid %s import in '%s': '%s' is not parseable (project = %s, branch = master)",
            importType.getType(),
            getCodeOwnerConfigFilePath(keyOfImportingCodeOwnerConfig),
            getCodeOwnerConfigFilePath(keyOfImportedCodeOwnerConfig),
            project.get()));
  }

  @Test
  public void validateMergeCommitCreatedViaTheCreateChangeRestApi() throws Exception {
    // Create another branch.
    String branchName = "stable";
    gApi.projects().name(project.get()).branch(branchName).create(new BranchInput());

    // Create a code owner config file in the other branch.
    codeOwnerConfigOperations
        .newCodeOwnerConfig()
        .project(project)
        .branch(branchName)
        .folderPath("/")
        .addCodeOwnerEmail(user.email())
        .create();

    // Create a change that merges the other branch into master. The code owner config files in the
    // created merge commit will be validated. This only works if CodeOwnerConfigValidator uses the
    // same RevWalk instance that inserted the new merge commit. If it doesn't, the create change
    // call below would fail with a MissingObjectException.
    ChangeInput changeInput = new ChangeInput();
    changeInput.project = project.get();
    changeInput.branch = "master";
    changeInput.subject = "A change";
    changeInput.status = ChangeStatus.NEW;
    MergeInput mergeInput = new MergeInput();
    mergeInput.source = gApi.projects().name(project.get()).branch(branchName).get().revision;
    changeInput.merge = mergeInput;
    gApi.changes().create(changeInput);
  }

  private CodeOwnerConfig createCodeOwnerConfigWithImport(
      CodeOwnerConfig.Key keyOfImportingCodeOwnerConfig,
      CodeOwnerConfigImportType importType,
      CodeOwnerConfigReference codeOwnerConfigReference) {
    CodeOwnerConfig.Builder codeOwnerConfigBuilder =
        CodeOwnerConfig.builder(keyOfImportingCodeOwnerConfig, TEST_REVISION);
    switch (importType) {
      case GLOBAL:
        codeOwnerConfigBuilder.addImport(codeOwnerConfigReference);
        break;
      case PER_FILE:
        codeOwnerConfigBuilder.addCodeOwnerSet(
            CodeOwnerSet.builder()
                .addPathExpression("foo")
                .addImport(codeOwnerConfigReference)
                .build());
        break;
      default:
        throw new IllegalStateException("unknown import type: " + importType);
    }
    return codeOwnerConfigBuilder.build();
  }

  private CodeOwnerConfig.Key createCodeOwnerConfigKey(String folderPath) {
    return CodeOwnerConfig.Key.create(project, "master", folderPath);
  }

  private String getCodeOwnerConfigFilePath(CodeOwnerConfig.Key codeOwnerConfigKey) {
    return backendConfig.getDefaultBackend().getFilePath(codeOwnerConfigKey).toString();
  }

  private String format(CodeOwnerConfig codeOwnerConfig) throws Exception {
    if (backendConfig.getDefaultBackend() instanceof FindOwnersBackend) {
      return findOwnersCodeOwnerConfigParser.formatAsString(codeOwnerConfig);
    } else if (backendConfig.getDefaultBackend() instanceof ProtoBackend) {
      return protoCodeOwnerConfigParser.formatAsString(codeOwnerConfig);
    }

    throw new IllegalStateException(
        String.format(
            "unknown code owner backend: %s",
            backendConfig.getDefaultBackend().getClass().getName()));
  }

  private String getParsingErrorMessage(
      ImmutableMap<Class<? extends CodeOwnerBackend>, String> messagesByBackend) {
    CodeOwnerBackend codeOwnerBackend = backendConfig.getDefaultBackend();
    assertThat(messagesByBackend).containsKey(codeOwnerBackend.getClass());
    return messagesByBackend.get(codeOwnerBackend.getClass());
  }

  private String abbreviateName(AnyObjectId id) throws Exception {
    return ObjectIds.abbreviateName(id, testRepo.getRevWalk().getObjectReader());
  }

  private static void assertOkWithoutMessages(PushOneCommit.Result pushResult) {
    pushResult.assertOkStatus();
    pushResult.assertNotMessage("error");
    pushResult.assertNotMessage("warning");
    pushResult.assertNotMessage("hint");
  }

  private void assertOkWithHints(PushOneCommit.Result pushResult, String... hints)
      throws Exception {
    pushResult.assertOkStatus();
    for (String hint : hints) {
      pushResult.assertMessage(
          String.format("hint: commit %s: %s", abbreviateName(pushResult.getCommit()), hint));
    }
    pushResult.assertNotMessage("error");
    pushResult.assertNotMessage("warning");
  }

  private void assertOkWithWarnings(PushOneCommit.Result pushResult, String... warnings)
      throws Exception {
    pushResult.assertOkStatus();
    for (String warning : warnings) {
      pushResult.assertMessage(
          String.format("warning: commit %s: %s", abbreviateName(pushResult.getCommit()), warning));
    }
    pushResult.assertNotMessage("error");
    pushResult.assertNotMessage("hint");
  }

  private void assertErrorWithMessages(
      PushOneCommit.Result pushResult, String summaryMessage, String... errors) throws Exception {
    String abbreviatedCommit = abbreviateName(pushResult.getCommit());
    pushResult.assertErrorStatus(String.format("commit %s: %s", abbreviatedCommit, summaryMessage));
    for (String error : errors) {
      pushResult.assertMessage(String.format("error: commit %s: %s", abbreviatedCommit, error));
    }
    pushResult.assertNotMessage("warning");
    pushResult.assertNotMessage("hint");
  }
}
