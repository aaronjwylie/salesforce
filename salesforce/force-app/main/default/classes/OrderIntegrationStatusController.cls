/**
 * Backs the Integration Status panel on the Order record page.
 *
 * The first question any business user asks about an integration is "did my order
 * actually go through?". Answering it in the UI they already work in beats sending
 * them to a Kafka console.
 */
public with sharing class OrderIntegrationStatusController {

    public class IntegrationStatus {
        @AuraEnabled public String orderNumber;
        @AuraEnabled public String erpOrderId;
        @AuraEnabled public String fulfillmentStatus;
        @AuraEnabled public Datetime lastModified;
        @AuraEnabled public Boolean reachedErp;
        @AuraEnabled public Boolean eligible;
    }

    @AuraEnabled(cacheable=true)
    public static IntegrationStatus getStatus(Id orderId) {
        List<Order> orders = [
            SELECT Id, OrderNumber, Status, ERP_Order_Id__c, Fulfillment_Status__c, LastModifiedDate
            FROM Order
            WHERE Id = :orderId
            WITH USER_MODE
            LIMIT 1
        ];

        if (orders.isEmpty()) {
            throw new AuraHandledException('Order not found or not visible to you.');
        }

        Order o = orders[0];
        IntegrationStatus status = new IntegrationStatus();
        status.orderNumber = o.OrderNumber;
        status.erpOrderId = o.ERP_Order_Id__c;
        status.fulfillmentStatus = o.Fulfillment_Status__c;
        status.lastModified = o.LastModifiedDate;
        status.reachedErp = String.isNotBlank(o.ERP_Order_Id__c);
        // Draft orders are not meant to sync, so the panel says "not yet applicable"
        // rather than showing a scary red "not synced".
        status.eligible = o.Status != 'Draft';
        return status;
    }

    /**
     * Republishes the order change event.
     *
     * Safe to press twice: the event carries a fresh Event_Uuid__c, but the middleware
     * upserts into the ERP by order number, so a duplicate is a no-op rather than a
     * second order.
     */
    @AuraEnabled
    public static void retrySync(Id orderId) {
        List<Order> orders = [
            SELECT Id, OrderNumber, AccountId, Status, TotalAmount
            FROM Order
            WHERE Id = :orderId
            WITH USER_MODE
            LIMIT 1
        ];

        if (orders.isEmpty()) {
            throw new AuraHandledException('Order not found or not visible to you.');
        }

        if (orders[0].Status == 'Draft') {
            throw new AuraHandledException('Draft orders are not sent to the ERP. Activate the order first.');
        }

        // null oldMap: treat this as a fresh publish rather than a field comparison.
        OrderChangePublisher.publish(orders, null);
    }
}
