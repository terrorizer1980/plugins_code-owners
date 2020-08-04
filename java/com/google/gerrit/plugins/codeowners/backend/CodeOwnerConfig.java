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

import static java.util.Objects.requireNonNull;

import com.google.auto.value.AutoValue;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.gerrit.entities.BranchNameKey;
import com.google.gerrit.entities.Project;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Code owner configuration for a folder in a branch.
 *
 * <p>This representation of a code owner configuration is independent of any code owner backend and
 * hence independent of any specific syntax of the code owner configuration files.
 */
@AutoValue
public abstract class CodeOwnerConfig {
  /** Gets the key of this code owner config. */
  public abstract Key key();

  /**
   * Gets whether code owners from parent code owner configs (code owner configs in parent folders)
   * should be ignored.
   */
  public abstract boolean ignoreParentCodeOwners();

  /** Gets references to the code owner configs that should be imported. */
  public abstract ImmutableSet<CodeOwnerConfigReference> imports();

  /**
   * Gets the code owner sets of this code owner config.
   *
   * <p>A code owner set defines a set of code owners for a set of path expressions.
   *
   * <p>The code owner sets are stored in an {@link ImmutableSet} so that duplicate code owner sets
   * are filtered out.
   *
   * <p>The order of the code owner sets is the same in which they have been added ({@link
   * ImmutableSet} preserves the iteration order).
   */
  public abstract ImmutableSet<CodeOwnerSet> codeOwnerSets();

  /**
   * Gets the code owner sets of this code owner config as list.
   *
   * <p>For some callers it's better to retrieve the code owner sets as list since lists have a
   * defined order, e.g. for:
   *
   * <ul>
   *   <li>updating code owner sets where the updater must be aware that the order matters
   *   <li>doing test assertions where the order should be verified
   * </ul>
   *
   * @return the code owner sets of this code owner config as list
   */
  public ImmutableList<CodeOwnerSet> codeOwnerSetsAsList() {
    return codeOwnerSets().asList();
  }

  /**
   * Relativizes the given path in relation to the folder path of this code owner config.
   *
   * @param path the path that should be relativized
   * @return the relativized path of the given path in relation to the folder path of this code
   *     owner config
   */
  Path relativize(Path path) {
    return key().folderPath().relativize(requireNonNull(path, "path"));
  }

  /**
   * Creates a builder form this code owner config.
   *
   * @return builder that was created from this code owner config
   */
  public abstract Builder toBuilder();

  /**
   * Creates a builder for a code owner config.
   *
   * @param key the key of the code owner config
   * @return builder for a code owner config
   */
  public static Builder builder(Key key) {
    return new AutoValue_CodeOwnerConfig.Builder().setKey(key).setIgnoreParentCodeOwners(false);
  }

  @AutoValue.Builder
  public abstract static class Builder {
    /**
     * Sets the key of this code owner config.
     *
     * @param key the key of this code owner config
     * @return the Builder instance for chaining calls
     */
    public abstract Builder setKey(Key key);

    /**
     * Sets whether code owners from parent code owner configs (code owner configs in parent
     * folders) should be ignored.
     *
     * @param ignoreParentCodeOwners whether code owners from parent code owner configs should be
     *     ignored
     * @return the Builder instance for chaining calls
     */
    public abstract Builder setIgnoreParentCodeOwners(boolean ignoreParentCodeOwners);

    /** Gets a builder to add references to code owner configs that should be imported. */
    abstract ImmutableSet.Builder<CodeOwnerConfigReference> importsBuilder();

    /**
     * Adds a reference to code owner configs that should be imported.
     *
     * @param codeOwnerConfigReference reference to the code owner config that should be imported
     * @return the Builder instance for chaining calls
     */
    public Builder addImport(CodeOwnerConfigReference codeOwnerConfigReference) {
      importsBuilder().add(codeOwnerConfigReference);
      return this;
    }

    /**
     * Sets that code owners from parent code owner configs (code owner configs in parent folders)
     * should be ignored.
     *
     * @return the Builder instance for chaining calls
     */
    public Builder setIgnoreParentCodeOwners() {
      return setIgnoreParentCodeOwners(true);
    }

    /**
     * Sets the code owner sets of this code owner config.
     *
     * @param codeOwnerSets the code owner sets of this code owner config
     * @return the Builder instance for chaining calls
     */
    public Builder setCodeOwnerSets(ImmutableList<CodeOwnerSet> codeOwnerSets) {
      return setCodeOwnerSets(ImmutableSet.copyOf(codeOwnerSets));
    }

    /**
     * Sets the code owner sets of this code owner config.
     *
     * @param codeOwnerSets the code owner sets of this code owner config
     * @return the Builder instance for chaining calls
     */
    abstract Builder setCodeOwnerSets(ImmutableSet<CodeOwnerSet> codeOwnerSets);

    /** Gets a builder to add code owner sets. */
    abstract ImmutableSet.Builder<CodeOwnerSet> codeOwnerSetsBuilder();

    /**
     * Adds a code owner set.
     *
     * @param codeOwnerSet the code owner set
     * @return the Builder instance for chaining calls
     */
    public Builder addCodeOwnerSet(CodeOwnerSet codeOwnerSet) {
      codeOwnerSetsBuilder().add(requireNonNull(codeOwnerSet, "codeOwnerSet"));
      return this;
    }

    /**
     * Builds the {@link CodeOwnerConfig} instance.
     *
     * @return the {@link CodeOwnerConfig} instance
     */
    public abstract CodeOwnerConfig build();
  }

  /**
   * Key of a {@link CodeOwnerConfig}.
   *
   * <p>The folder in a branch for which the code owner config defines code owners.
   */
  @AutoValue
  public abstract static class Key {
    /** Gets the branch to which the code owner config belongs. */
    public abstract BranchNameKey branch();

    /** Gets the path of the folder to which the code owner config belongs. */
    public abstract Path folderPath();

    /**
     * Gets the project to which the code owner config belongs.
     *
     * @return the project to which the code owner config belongs
     */
    public Project.NameKey project() {
      return branch().project();
    }

    /**
     * Gets the short name of the branch of this owner config key.
     *
     * @return the short name of the branch of this owner config key
     */
    public String shortBranchName() {
      return branch().shortName();
    }

    /**
     * Gets the ref name of the branch to which the code owner config belongs.
     *
     * @return the ref name of the branch to which the code owner config belongs
     */
    public String ref() {
      return branch().branch();
    }

    /**
     * Gets the path for the given code owner config file name.
     *
     * @param codeOwnerConfigFileName the name of the code owner config file for which the path
     *     should be returned
     * @return the path for the given file code owner config file name
     */
    public Path filePath(String codeOwnerConfigFileName) {
      return folderPath()
          .resolve(requireNonNull(codeOwnerConfigFileName, "codeOwnerConfigFileName"));
    }

    /**
     * Creates a code owner config key.
     *
     * @param project the project to which the code owner config belongs
     * @param branch the branch to which the code owner config belongs
     * @param folderPath the path of the folder to which the code owner config belongs
     * @return the code owner config key
     */
    public static Key create(Project.NameKey project, String branch, String folderPath) {
      return create(BranchNameKey.create(project, branch), Paths.get(folderPath));
    }

    /**
     * Creates a code owner config key.
     *
     * @param branch the branch to which the code owner config belongs
     * @param folderPath the path of the folder to which the code owner config belongs
     * @return the code owner config key
     */
    public static Key create(BranchNameKey branch, Path folderPath) {
      return new AutoValue_CodeOwnerConfig_Key.Builder()
          .setBranch(branch)
          .setFolderPath(folderPath)
          .build();
    }

    @AutoValue.Builder
    abstract static class Builder {
      /**
       * Sets the branch for this owner config key.
       *
       * @param branch the branch for this owner config key
       * @return the Builder instance for chaining calls
       */
      public abstract Builder setBranch(BranchNameKey branch);

      /**
       * Sets the folder path for this owner config key.
       *
       * @param folderPath the folder path for this owner config key
       * @return the Builder instance for chaining calls
       */
      public abstract Builder setFolderPath(Path folderPath);

      /**
       * Builds the {@link Key} instance.
       *
       * @return the {@link Key} instance
       */
      abstract Key build();
    }
  }
}
