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

package com.google.gerrit.plugins.codeowners.metrics;

import com.google.gerrit.metrics.Description;
import com.google.gerrit.metrics.Description.Units;
import com.google.gerrit.metrics.MetricMaker;
import com.google.gerrit.metrics.Timer0;
import com.google.inject.Inject;
import com.google.inject.Singleton;

/** Metrics of the code-owners plugin. */
@Singleton
public class CodeOwnerMetrics {
  public final Timer0 computeChangedFiles;
  public final Timer0 computeFileStatuses;
  public final Timer0 computeFileStatusesForAccount;
  public final Timer0 resolveCodeOwnerConfig;
  public final Timer0 resolveCodeOwnerConfigImport;
  public final Timer0 resolveCodeOwnerConfigImports;
  public final Timer0 resolvePathCodeOwners;
  public final Timer0 runCodeOwnerSubmitRule;

  private final MetricMaker metricMaker;

  @Inject
  CodeOwnerMetrics(MetricMaker metricMaker) {
    this.metricMaker = metricMaker;

    this.computeChangedFiles =
        createLatencyTimer("compute_changed_files", "Latency for computing changed files");
    this.computeFileStatuses =
        createLatencyTimer("compute_file_statuses", "Latency for computing file statuses");
    this.computeFileStatusesForAccount =
        createLatencyTimer(
            "compute_file_statuses_for_account",
            "Latency for computing file statuses for an account");
    this.resolveCodeOwnerConfig =
        createLatencyTimer(
            "resolve_code_owner_config", "Latency for resolving a code owner config file");
    this.resolveCodeOwnerConfigImport =
        createLatencyTimer(
            "resolve_code_owner_config_import",
            "Latency for resolving an import of a code owner config file");
    this.resolveCodeOwnerConfigImports =
        createLatencyTimer(
            "resolve_code_owner_config_imports",
            "Latency for resolving all imports of a code owner config file");
    this.resolvePathCodeOwners =
        createLatencyTimer(
            "resolve_path_code_owners", "Latency for resolving the code owners of a path");
    this.runCodeOwnerSubmitRule =
        createLatencyTimer(
            "run_code_owner_submit_rule", "Latency for running the code owner submit rule");
  }

  private Timer0 createLatencyTimer(String name, String description) {
    return metricMaker.newTimer(
        "code_owners/" + name,
        new Description(description).setCumulative().setUnit(Units.MILLISECONDS));
  }
}