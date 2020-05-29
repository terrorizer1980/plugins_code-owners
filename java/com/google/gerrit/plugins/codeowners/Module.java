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

package com.google.gerrit.plugins.codeowners;

import com.google.gerrit.extensions.config.FactoryModule;
import com.google.gerrit.plugins.codeowners.api.ApiModule;
import com.google.gerrit.plugins.codeowners.backend.BackendModule;
import com.google.gerrit.plugins.codeowners.restapi.RestApiModule;

/** Guice module that registers the extensions of the code-owners plugin. */
public class Module extends FactoryModule {
  @Override
  protected void configure() {
    install(new ApiModule());
    install(new BackendModule());
    install(new RestApiModule());
  }
}
