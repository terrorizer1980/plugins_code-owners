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

package com.google.gerrit.plugins.codeowners.api;

import com.google.gerrit.common.Nullable;
import com.google.gerrit.extensions.restapi.NotImplementedException;
import com.google.gerrit.extensions.restapi.RestApiException;
import java.util.List;

/**
 * Branch-level Java API of the code-owners plugin.
 *
 * <p>To create an instance for a branch use {@link ProjectCodeOwners#branch(String)}.
 */
public interface BranchCodeOwners {
  /** Returns the code owner project configuration. */
  CodeOwnerBranchConfigInfo getConfig() throws RestApiException;

  /** Create a request to retrieve code owner config files from the branch. */
  CodeOwnerConfigFilesRequest codeOwnerConfigFiles() throws RestApiException;

  abstract class CodeOwnerConfigFilesRequest {
    private boolean includeNonParsableFiles;
    private String email;

    /** Includes non-parsable code owner config files into the result. */
    public CodeOwnerConfigFilesRequest includeNonParsableFiles(boolean includeNonParsableFiles) {
      this.includeNonParsableFiles = includeNonParsableFiles;
      return this;
    }

    /** Whether non-parsable code owner config files should be included into the result. */
    public boolean getIncludeNonParsableFiles() {
      return includeNonParsableFiles;
    }

    /**
     * Limits the returned code owner config files to those that contain the given email.
     *
     * @param email the email that should appear in the returned code owner config files
     */
    public CodeOwnerConfigFilesRequest withEmail(String email) {
      this.email = email;
      return this;
    }

    /** Returns the email that should appear in the returned code owner config files/ */
    @Nullable
    public String getEmail() {
      return email;
    }

    /** Executes the request and retrieves the paths of the requested code owner config file */
    public abstract List<String> paths() throws RestApiException;
  }

  /** Renames an email in the code owner config files of the branch. */
  RenameEmailResultInfo renameEmailInCodeOwnerConfigFiles(RenameEmailInput input)
      throws RestApiException;

  /**
   * A default implementation which allows source compatibility when adding new methods to the
   * interface.
   */
  class NotImplemented implements BranchCodeOwners {
    @Override
    public CodeOwnerBranchConfigInfo getConfig() throws RestApiException {
      throw new NotImplementedException();
    }

    @Override
    public CodeOwnerConfigFilesRequest codeOwnerConfigFiles() throws RestApiException {
      throw new NotImplementedException();
    }

    @Override
    public RenameEmailResultInfo renameEmailInCodeOwnerConfigFiles(RenameEmailInput input)
        throws RestApiException {
      throw new NotImplementedException();
    }
  }
}
