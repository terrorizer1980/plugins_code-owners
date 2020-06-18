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

package com.google.gerrit.plugins.codeowners.testing;

import static com.google.common.truth.Truth.assertAbout;

import com.google.common.truth.Correspondence;
import com.google.common.truth.FailureMetadata;
import com.google.common.truth.IterableSubject;
import com.google.common.truth.Subject;
import com.google.gerrit.plugins.codeowners.backend.CodeOwnerConfig;
import com.google.gerrit.plugins.codeowners.backend.CodeOwnerReference;
import java.util.Objects;
import java.util.Optional;

/** {@link Subject} for doing assertions on {@link CodeOwnerConfig}s. */
public class CodeOwnerConfigSubject extends Subject {
  /** {@link Correspondence} that maps {@link CodeOwnerReference}s to emails. */
  public static final Correspondence<CodeOwnerReference, String> CODE_OWNER_REFERENCE_TO_EMAIL =
      Correspondence.from(
          (actualCodeOwnerReference, expectedEmail) -> {
            String email =
                Optional.ofNullable(actualCodeOwnerReference)
                    .map(CodeOwnerReference::email)
                    .orElse(null);
            return Objects.equals(email, expectedEmail);
          },
          "has email");

  /**
   * Starts fluent chain to do assertions on a {@link CodeOwnerConfig}.
   *
   * @param codeOwnerConfig the code owner config on which assertions should be done
   * @return the created {@link CodeOwnerConfigSubject}
   */
  public static CodeOwnerConfigSubject assertThat(CodeOwnerConfig codeOwnerConfig) {
    return assertAbout(CodeOwnerConfigSubject::new).that(codeOwnerConfig);
  }

  private final CodeOwnerConfig codeOwnerConfig;

  private CodeOwnerConfigSubject(FailureMetadata metadata, CodeOwnerConfig codeOwnerConfig) {
    super(metadata, codeOwnerConfig);
    this.codeOwnerConfig = codeOwnerConfig;
  }

  /**
   * Returns an {@link IterableSubject} for the code owners in the code owner config.
   *
   * @return {@link IterableSubject} for the code owners in the code owner config
   */
  public IterableSubject hasCodeOwnersThat() {
    return check("codeOwners()").that(codeOwnerConfig().codeOwners());
  }

  /**
   * Returns an {@link IterableSubject} for the code owner emails in the code owner config.
   *
   * @return {@link IterableSubject} for the code owner emails in the code owner config
   */
  public IterableSubject.UsingCorrespondence<CodeOwnerReference, String> hasCodeOwnersEmailsThat() {
    return hasCodeOwnersThat().comparingElementsUsing(CODE_OWNER_REFERENCE_TO_EMAIL);
  }

  private CodeOwnerConfig codeOwnerConfig() {
    isNotNull();
    return codeOwnerConfig;
  }
}