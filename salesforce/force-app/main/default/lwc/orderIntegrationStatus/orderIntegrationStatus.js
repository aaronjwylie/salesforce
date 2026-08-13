import { LightningElement, api, wire } from 'lwc';
import { getRecord, getFieldValue, notifyRecordUpdateAvailable } from 'lightning/uiRecordApi';
import { ShowToastEvent } from 'lightning/platformShowToastEvent';
import retrySync from '@salesforce/apex/OrderIntegrationStatusController.retrySync';

import STATUS from '@salesforce/schema/Order.Status';
import ORDER_NUMBER from '@salesforce/schema/Order.OrderNumber';
import ERP_ORDER_ID from '@salesforce/schema/Order.ERP_Order_Id__c';
import FULFILLMENT_STATUS from '@salesforce/schema/Order.Fulfillment_Status__c';
import LAST_MODIFIED from '@salesforce/schema/Order.LastModifiedDate';

const FIELDS = [STATUS, ORDER_NUMBER, ERP_ORDER_ID, FULFILLMENT_STATUS, LAST_MODIFIED];

/**
 * Reads through Lightning Data Service rather than an Apex controller.
 *
 * This is not a style preference. An `@wire` to Apex only re-runs when its arguments
 * change, and `recordId` does not change when the record does — so activating the order
 * left this panel insisting it was still a draft. LDS keeps a shared cache of the
 * record and pushes updates to every component watching it, so the panel now tracks the
 * record instead of a snapshot taken at page load.
 *
 * It also respects field-level security automatically, and costs no Apex.
 */
export default class OrderIntegrationStatus extends LightningElement {
    @api recordId;

    error;
    retrying = false;

    @wire(getRecord, { recordId: '$recordId', fields: FIELDS })
    order;

    get loading() {
        return !this.order.data && !this.order.error;
    }

    get loadError() {
        if (!this.order.error) {
            return undefined;
        }
        return this.order.error.body
            ? this.order.error.body.message
            : 'Could not load integration status.';
    }

    /** Draft orders are not meant to have synced, so the panel says so rather than alarming. */
    get eligible() {
        return getFieldValue(this.order.data, STATUS) !== 'Draft';
    }

    get erpOrderId() {
        return getFieldValue(this.order.data, ERP_ORDER_ID);
    }

    get reachedErp() {
        return !!this.erpOrderId;
    }

    get lastModified() {
        return getFieldValue(this.order.data, LAST_MODIFIED);
    }

    get fulfillmentLabel() {
        const status = getFieldValue(this.order.data, FULFILLMENT_STATUS);
        if (status) {
            return status;
        }
        return this.reachedErp ? 'Received' : 'Awaiting ERP';
    }

    get badgeClass() {
        return this.reachedErp ? 'slds-badge slds-theme_success' : 'slds-badge';
    }

    async handleRetry() {
        this.retrying = true;
        try {
            await retrySync({ orderId: this.recordId });
            // "Queued", not "Synced". The platform event publishes after commit and the
            // round trip through Kafka and the ERP takes a moment, so claiming success
            // here would be a lie roughly half the time.
            this.toast('Sync queued', 'The order has been republished to the ERP.', 'success');
            // Ask LDS to re-fetch. The ERP writes back through the API, which the
            // browser's cache has no way of knowing about on its own.
            notifyRecordUpdateAvailable([{ recordId: this.recordId }]);
        } catch (e) {
            this.toast('Could not retry', e.body ? e.body.message : 'Retry failed.', 'error');
        } finally {
            this.retrying = false;
        }
    }

    toast(title, message, variant) {
        this.dispatchEvent(new ShowToastEvent({ title, message, variant }));
    }
}
