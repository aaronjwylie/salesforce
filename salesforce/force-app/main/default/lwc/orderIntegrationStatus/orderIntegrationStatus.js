import { LightningElement, api, wire } from 'lwc';
import { refreshApex } from '@salesforce/apex';
import { ShowToastEvent } from 'lightning/platformShowToastEvent';
import getStatus from '@salesforce/apex/OrderIntegrationStatusController.getStatus';
import retrySync from '@salesforce/apex/OrderIntegrationStatusController.retrySync';

export default class OrderIntegrationStatus extends LightningElement {
    @api recordId;

    status;
    error;
    loading = true;
    retrying = false;

    // Held so refreshApex can invalidate the cached wire after a retry.
    wiredResult;

    @wire(getStatus, { orderId: '$recordId' })
    wiredStatus(result) {
        this.wiredResult = result;
        this.loading = false;

        if (result.data) {
            this.status = result.data;
            this.error = undefined;
        } else if (result.error) {
            this.status = undefined;
            this.error = result.error.body ? result.error.body.message : 'Could not load integration status.';
        }
    }

    get fulfillmentLabel() {
        if (!this.status) {
            return '';
        }
        if (this.status.fulfillmentStatus) {
            return this.status.fulfillmentStatus;
        }
        return this.status.reachedErp ? 'Received' : 'Awaiting ERP';
    }

    get badgeClass() {
        if (!this.status || !this.status.reachedErp) {
            return 'slds-badge';
        }
        return 'slds-badge slds-theme_success';
    }

    async handleRetry() {
        this.retrying = true;
        try {
            await retrySync({ orderId: this.recordId });
            // The event is published after commit and the middleware round-trip takes a
            // moment, so the refresh below will usually still show the old value. Say
            // "queued" rather than implying it is already done.
            this.toast('Sync queued', 'The order has been republished to the ERP.', 'success');
            await refreshApex(this.wiredResult);
        } catch (e) {
            const message = e.body ? e.body.message : 'Retry failed.';
            this.toast('Could not retry', message, 'error');
        } finally {
            this.retrying = false;
        }
    }

    toast(title, message, variant) {
        this.dispatchEvent(new ShowToastEvent({ title, message, variant }));
    }
}
