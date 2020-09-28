# User Guide

The `@PLUGIN@` plugin allows to define [code owners](#codeOwners) for
directories and files and requires their [approval](#codeOwnerApproval) for
changes that touch these files.

This user guide explains the functionality of the `@PLUGIN@` plugin. For a
walkthrough of the UI please refer to the [intro](how-to-use.html) page.

**NOTE:** How to setup the code owners functionality is explained in the
[setup guide](setup-guide.html).

## <a id="codeOwners">What are code owners?

A code owner is a user that is configured as owner of a path (directory or file)
and whose approval is required to modify the path or files under that path.

Who is a code owner of a path is controlled via [code owner config
files](#codeOwnerConfigFiles).

## <a id="codeOwnerConfigFiles">Code owner config files

Code owner config files are stored in the source tree of the repository and
define the [code owners](#codeOwners) for a path.

In which files code owners are defined and which syntax is used depends on the
configured [code owner backend](backends.html). Example: if the
[find-owners](backend-find-owners.html) backend is used, code owners are defined
in [OWNERS](backend-find-owners.html#syntax) files.

To create/edit code owner config files, clone the repository, edit the code
owner config files locally and then push the new commit to the remote repository
in Gerrit. This the same as creating/editing any other source files.

On push, Gerrit [validates](validation.html) any code owner config file that is
touched by the new commits. Commits that make code owner config files invalid
are rejected.

**NOTE:** There is no dedicated editor for code owner config files in the Gerrit
UI.

**NOTE:** It is the responsibility of the project owners to maintain the code
owner config files (e.g. take care to remove code owners that leave the team).

## <a id="codeOwnerApproval">Code owner approval

For a change to be submittable Gerrit requires that all files that are touched
in the change are approved by a code owner.

Code owners apply their approval by voting on the change. By default, voting
with `Code-Review+1` counts as code owner approval, but it's possible that the
host administrators or the project owners have [configured a different label/vote
that is required as code owner approval](setup-guide.html#configureCodeOwnerApproval).

The code owner check for a file is satisfied as soon as one of its code owners
grants the code owner approval. Negative votes from other code owners do not
block the submission (unless it's a veto vote which is configured independently
of the `@PLUGIN@` plugin).

It's possible to configure that for changes/patch-sets that are uploaded by a
code owner an implict code owner approval from the uploader is assumed. In this
case, if a code owner only touches files that they own, no approval from other
code owners is required. If this is configured, it is important that code owners
are aware of their implicit approval when they upload new patch sets for other
users (e.g. if a contributor pushes a change to a wrong branch and a code owner
helps them to get it rebased onto the correct branch, the rebased change has
implicit approvals from the code owner, since the code owner is the uploader).

For files that are [renamed/moved](#renames) Gerrit requires a code owner
approval for the old and the new path of the files.

If code owner approvals are missing, it is possible to submit the change with a
[code owner override](#codeOwnerOverride), but this should only be done in
exceptional cases.

**NOTE:** It is possible that users are code owners, but miss permissions to
vote on the required label. This is a configuration issue that should be
reported to the project owners (who should either
[grant the permission](setup-guide.html#grantCodeOwnerPermissions) or remove
the code owner).

**NOTE:** It's possible that the change submission is still blocked after all
necessary code owner approvals have been granted. This is the case, if futher
non-code-owner approvals are required and missing, or if further non-code-owner
submit requirements are not fulfilled yet.

## <a id="codeOwnerOverride">Code owner override

Usually some privileged users, such as sheriffs, are allowed to override the
code owner approval check to make changes submittable, even if code owner
approvals for some or all files are missing.

Overrides are intended to be used in emergency cases only. What qualifies as an
emergency depends on the project. Usually overrides are used when changes need
to be submitted quickly to address an urgent production issue and there is no
time to await all required code owner approvals.

A code owner override is applied by voting on the change. Which label/vote
counts as code owner override depends on the
[configuration](setup-guide.html#configureCodeOwnerOverrides).

**NOTE:** It's possible that overrides are disabled for a project.

## <a id="codeOwnerSuggestion">Code owner Suggestion

As a change owner, you need to request code owner approvals for the files that
are modified in the change, so that your change can become submittable. This is
done by selecting a code owner for each of the files and adding them as reviewer
to the change. To help you with this task, Gerrit suggests you suitable code
owners for the files in the change and lets you pick which of them should be
added as reviewers (how this looks in the UI is shown in the
[intro](how-to-use.html#addCodeOwnersAsReviewers) page).

When suggesting code owners for a file, Gerrit filters out code owners that:

* are inactive
* are not visible to you (according to
[accounts.visibility](../../../Documentation/config-gerrit.html#accounts.visibility)
setting),
* are referenced by non-visible secondary emails
* are not resolvable (emails for which no Gerrit account exists)
* are ambiguous (the same email is assigned to multiple accounts)
* are referenced by an email with a disallowed domain (see
  [allowedEmailDomain configuration](config.html#pluginCodeOwnersAllowedEmailDomain))
* do not have read access to the destination branch of the change
* are service users (members of the `Service Users` group)

The suggested code owners are sorted by score, so that the best suitable code
owners appear first. The following criteria are taken into account for computing
the score:

* The distance of the [code owner config file](#codeOwnerConfigFiles) that
  defines the code owner from the owned path.\
  The smaller the distance the better we consider the code owner as
  reviewer/approver for the path.

## <a id="renames">Renames

When files/folders get renamed, their code owner configuration should stay
intact. Renaming a file/folder should normally not result in a situation where
the code owner configuration for this file/folder no longer applies, because it
was renamed.

Mostly this is not a problem because [code owner config
files](#codeOwnerConfigFiles) are stored inside the folders to which they apply.
This means, if a folder gets renamed, the code owner config files in it still
apply.

However if a file/folder is renamed for which specific code owners are defined
via [path expressions](path-expressions.html), it is possible that the code
ownership changes. For example, this can happen if the old name is matched by
a path expression that makes user A a code owner, but the new name is only
matched by another path expression that makes user B a code owner. E.g. '*.md'
is owned by user A, '*.txt' is owned by user B and 'config.md' is renamed to
'config.txt'. In this case it is the responsibility of the author doing the
rename and the current code owners to ensure that the file/folder has the proper
code owners at the new path. This is why for files that are renamed Gerrit
requires a code owner approval for the old and the new path of the files (also
see [code owner approval](#codeOwnerApproval) section). Also this is the reason
why [matching subfolders via path expressions is
discouraged](backend-find-owners.html#doNotUsePathExpressionsForSubdirectories).

## <a id="mergeCommits">Merge commits

By default, changes for merge commits require code owner approvals for all files
that differ between the merge commit that is being reviewed and the tip of the
destination branch (the first parent commit). This includes all files that have
been touched in other branches and that are now being integrated into the
destination branch (regardless of whether there was a conflict resolution or
whether the auto-merge succeeded without conflicts). To see these files in the
change screen, `Parent 1` needs to be selected as base for the comparison
(instead of the `Auto Merge` that is selected as base by default).

By [configuration](config.html#codeOwnersMergeCommitStrategy) it is possible,
that changes for merge commits only require code owner approvals for files that
differ between the merge commit that is being reviewed and the `Auto Merge`. In
this case, code owners only need to approve the files for which a conflict
resolution was done. These are the files that are shown in the change screen
when `Auto Merge` is selected as base. Using this configuration makes sense if
all branches require code owner approvals and the code owners of all branches
are trusted, as it prevents that code owners need to approve the same changes
multiple times, but for different branches.

---

Back to [@PLUGIN@ documentation index](index.html)

Part of [Gerrit Code Review](../../../Documentation/index.html)