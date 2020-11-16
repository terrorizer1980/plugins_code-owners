/**
 * @license
 * Copyright (C) 2020 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

/**
 * Maintain the state of code-owners.
 * Raises 'model-property-changed' event when a property is changed.
 * The plugin shares the same model between all UI elements (thought it is not
 * required).
 * UI elements use values from this model to display information
 * and listens for the model-property-changed event. To do so, UI elements
 * add CodeOwnersModelMixin, which is doing the listening and the translation
 * from model-property-changed event to Polymer property-changed-event. The
 * translation allows to use model properties in observables, bindings,
 * computed properties, etc...
 * The CodeOwnersModelLoader updates the model.
 *
 * It would be good to use RxJs Observable for implementing model properties.
 * However, RxJs library is imported by Gerrit and there is no
 * good way to reuse the same library in the plugin.
 */
export class CodeOwnersModel extends EventTarget {
  constructor(change) {
    super();
    this.change = change;
    this.branchConfig = undefined;
    this.status = undefined;
    this.userRole = undefined;
    this.isCodeOwnerEnabled = undefined;
    this.areAllFilesApproved = undefined;
  }

  setBranchConfig(config) {
    if (this.branchConfig === config) return;
    this.branchConfig = config;
    this._firePropertyChanged('branchConfig');
  }

  setStatus(status) {
    if (this.status === status) return;
    this.status = status;
    this._firePropertyChanged('status');
  }

  setUserRole(userRole) {
    if (this.userRole === userRole) return;
    this.userRole = userRole;
    this._firePropertyChanged('userRole');
  }

  setIsCodeOwnerEnabled(enabled) {
    if (this.isCodeOwnerEnabled === enabled) return;
    this.isCodeOwnerEnabled = enabled;
    this._firePropertyChanged('isCodeOwnerEnabled');
  }

  setAreAllFilesApproved(approved) {
    if (this.areAllFilesApproved === approved) return;
    this.areAllFilesApproved = approved;
    this._firePropertyChanged('areAllFilesApproved');
  }

  _firePropertyChanged(propertyName) {
    this.dispatchEvent(new CustomEvent('model-property-changed', {
      detail: {
        propertyName,
      },
    }));
  }

  static getModel(change) {
    if (!this.model || this.model.change !== change) {
      this.model = new CodeOwnersModel(change);
    }
    return this.model;
  }
}
