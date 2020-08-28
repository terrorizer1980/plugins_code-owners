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
import {CodeOwnerService, OwnerStatus} from './code-owners-service.js';
import {ownerState} from './owner-ui-state.js';

export class SuggestOwnersTrigger extends Polymer.Element {
  static get is() {
    return 'suggest-owners-trigger';
  }

  static get properties() {
    return {
      change: Object,
      expanded: {
        type: Boolean,
        value: false,
      },
      restApi: Object,
      hidden: {
        type: Boolean,
        value: true,
        reflectToAttribute: true,
      },
    };
  }

  static get observers() {
    return ['onInputChanged(restApi, change)'];
  }

  static get template() {
    return Polymer.html`
        <style include="shared-styles">
          iron-icon {
            padding-left: var(--spacing-m);
          }
        </style>
        <gr-button
          on-click="toggleControlContent"
          has-tooltip
          title="Suggest owners for your change"
        >
          [[computeButtonText(expanded)]]
          <iron-icon icon="gr-icons:info-outline"></iron-icon>
        </gr-button>
      `;
  }

  connectedCallback() {
    super.connectedCallback();
    ownerState.onExpandSuggestionChange(expanded => {
      this.expanded = expanded;
    });
  }

  onInputChanged(restApi, change) {
    if ([restApi, change].includes(undefined)) return;
    this.ownerService = CodeOwnerService.getOwnerService(
        this.restApi,
        this.change
    );
    this.ownerService.getStatus().then(({rawStatuses}) => {
      const notAllApproved = rawStatuses.some(status => {
        const oldPathStatus = status.old_path_status;
        const newPathStatus = status.new_path_status;
        if (newPathStatus.status !== OwnerStatus.APPROVED) {
          return true;
        }
        return oldPathStatus && oldPathStatus.status !== OwnerStatus.APPROVED;
      });
      this.hidden = !notAllApproved;
    });
  }

  toggleControlContent() {
    this.expanded = !this.expanded;
    ownerState.expandSuggestion = this.expanded;
  }

  computeButtonText(expanded) {
    return expanded ? 'Hide owners' : 'Suggest owners';
  }
}

customElements.define(SuggestOwnersTrigger.is, SuggestOwnersTrigger);