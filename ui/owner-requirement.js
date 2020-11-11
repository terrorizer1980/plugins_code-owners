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

/**
 * Owner requirement control for `submit-requirement-item-code-owners` endpoint.
 *
 * This will show the status and suggest owners button next to
 * the code-owners submit requirement.
 */
export class OwnerRequirementValue extends Polymer.Element {
  static get is() {
    return 'owner-requirement-value';
  }

  static get template() {
    return Polymer.html`
        <style include="shared-styles">
        :host {
          --gr-button: {
            padding: 0px;
          }
        }
        p.loading {
          display: flex;
          align-content: center;
          align-items: center;
          justify-content: center;
        }
        .loadingSpin {
          display: inline-block;
          margin-right: var(--spacing-m);
          width: 18px;
          height: 18px;
        }
        gr-button {
          padding-left: var(--spacing-m);
        }
        a {
          text-decoration: none;
        }
        </style>
        <p class="loading" hidden="[[!_isLoading]]">
          <span class="loadingSpin"></span>
          Loading status ...
        </p>
        <template is="dom-if" if="[[!_isLoading]]">
          <span>[[_computeStatusText(_statusCount, _isOverriden)]]</span>
          <template is="dom-if" if="[[_overrideInfoUrl]]">
            <a on-click="_reportDocClick" href="[[_overrideInfoUrl]]" target="_blank">
              <iron-icon icon="gr-icons:help-outline" title="Documentation for overriding code owners"></iron-icon>
            </a>
          </template>
          <template is="dom-if" if="[[!_allApproved]]">
            <gr-button link on-click="_openReplyDialog">
            Suggest owners
          </gr-button>
          </template>
        </template>
      `;
  }

  static get properties() {
    return {
      change: Object,
      reporting: Object,
      restApi: Object,

      ownerService: Object,

      _statusCount: Object,
      _isLoading: {
        type: Boolean,
        value: true,
      },
      _allApproved: {
        type: Boolean,
        computed: '_computeAllApproved(_statusCount)',
      },
      _isOverriden: Boolean,
      _overrideInfoUrl: String,
    };
  }

  static get observers() {
    return [
      'onInputChanged(restApi, change, reporting)',
    ];
  }

  _checkIfOverriden() {
    this.ownerService.getBranchConfig().then(res => {
      if (!res['override_approval']) {
        // no override label configured
        this._isOverriden = false;
        return;
      }

      const overridenLabel = res['override_approval'].label;
      const overridenValue = res['override_approval'].value;

      if (this.change.labels[overridenLabel]) {
        const votes = this.change.labels[overridenLabel].all || [];
        if (votes.find(v => `${v.value}` === `${overridenValue}`)) {
          this._isOverriden = true;
          return;
        }
      }

      // otherwise always reset it to false
      this._isOverriden = false;
    });
  }

  _updateStatus() {
    this._isLoading = true;
    this.reporting.reportLifeCycle('owners-submit-requirement-summary-start');

    return this.ownerService.getStatus()
        .then(({rawStatuses}) => {
          this._statusCount = this._getStatusCount(rawStatuses);
          this.ownerService.getLoggedInUserInitialRole().then(role => {
            // Send a metric with overall summary when code owners submit
            // requirement shown and finished fetching status
            this.reporting.reportLifeCycle(
                'owners-submit-requirement-summary-shown',
                {...this._statusCount, user_role: role}
            );
          });
        })
        .finally(() => {
          this._isLoading = false;
        });
  }

  _updateOverrideInfoUrl() {
    this.ownerService.getBranchConfig().then(config => {
      this._overrideInfoUrl = config.general && config.general.override_info_url
        ?
        config.general.override_info_url : '';
    });
  }

  _computeAllApproved(statusCount) {
    return statusCount.missing === 0
            && statusCount.pending === 0;
  }

  _getStatusCount(rawStatuses) {
    return rawStatuses
        .reduce((prev, cur) => {
          const oldPathStatus = cur.old_path_status;
          const newPathStatus = cur.new_path_status;
          if (newPathStatus && this._isMissing(newPathStatus.status)) {
            prev.missing ++;
          } else if (newPathStatus && this._isPending(newPathStatus.status)) {
            prev.pending ++;
          } else if (oldPathStatus) {
            // check oldPath if newPath approved or the file is deleted
            if (this._isMissing(oldPathStatus.status)) {
              prev.missing ++;
            } else if (this._isPending(oldPathStatus.status)) {
              prev.pending ++;
            }
          } else {
            prev.approved ++;
          }
          return prev;
        }, {missing: 0, pending: 0, approved: 0});
  }

  _computeStatusText(statusCount, isOverriden) {
    const statusText = [];
    if (statusCount.missing) {
      statusText.push(`${statusCount.missing} missing`);
    }

    if (statusCount.pending) {
      statusText.push(`${statusCount.pending} pending`);
    }

    if (!statusText.length) {
      statusText.push(isOverriden ? 'Approved (Owners-Override)' : 'Approved');
    }

    return statusText.join(', ');
  }

  _isMissing(status) {
    return status === OwnerStatus.INSUFFICIENT_REVIEWERS;
  }

  _isPending(status) {
    return status === OwnerStatus.PENDING;
  }

  onInputChanged(restApi, change, reporting) {
    if ([restApi, change, reporting].includes(undefined)) return;
    this.ownerService = CodeOwnerService.getOwnerService(this.restApi, change);
    this._updateStatus();
    this._checkIfOverriden();
    this._updateOverrideInfoUrl();
  }

  _openReplyDialog() {
    this.dispatchEvent(
        new CustomEvent('open-reply-dialog', {
          detail: {},
          composed: true,
          bubbles: true,
        })
    );
    ownerState.expandSuggestion = true;
    this.ownerService.getLoggedInUserInitialRole().then(role => {
      this.reporting.reportInteraction(
          'suggest-owners-from-submit-requirement', {user_role: role});
    });
  }
}

customElements.define(OwnerRequirementValue.is, OwnerRequirementValue);
