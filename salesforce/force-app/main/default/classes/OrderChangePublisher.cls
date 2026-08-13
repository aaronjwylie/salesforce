/**
 * Publishes Order_Change__e platform events for orders the ERP needs to know about.
 *
 * Bulkified throughout: one SOQL for account external ids regardless of batch size,
 * and one EventBus.publish call for the whole trigger context.
 */
public with sharing class OrderChangePublisher {

    /** Draft orders are Sales' business until they activate; the ERP never sees them. */
    private static final Set<String> ERP_RELEVANT_STATUSES = new Set<String>{
        'Activated', 'Fulfilled', 'Cancelled'
    };

    public static void publish(List<Order> orders, Map<Id, Order> oldOrders) {
        List<Order> candidates = new List<Order>();
        Set<Id> accountIds = new Set<Id>();

        for (Order o : orders) {
            if (!isRelevant(o, oldOrders)) {
                continue;
            }
            candidates.add(o);
            if (o.AccountId != null) {
                accountIds.add(o.AccountId);
            }
        }

        if (candidates.isEmpty()) {
            return;
        }

        Map<Id, String> externalIds = accountExternalIds(accountIds);
        List<Order_Change__e> events = buildEvents(candidates, externalIds);
        publishAll(events);
    }

    /**
     * Only publish when the order is ERP-relevant AND something the ERP cares about
     * actually changed. Without the second check, any unrelated field edit would
     * republish the order and burn platform event allocation.
     */
    @TestVisible
    private static Boolean isRelevant(Order o, Map<Id, Order> oldOrders) {
        if (!ERP_RELEVANT_STATUSES.contains(o.Status)) {
            return false;
        }
        if (oldOrders == null) {
            return true;
        }
        Order previous = oldOrders.get(o.Id);
        if (previous == null) {
            return true;
        }
        return o.Status != previous.Status || o.TotalAmount != previous.TotalAmount;
    }

    private static Map<Id, String> accountExternalIds(Set<Id> accountIds) {
        Map<Id, String> externalIds = new Map<Id, String>();
        if (accountIds.isEmpty()) {
            return externalIds;
        }
        for (Account a : [
            SELECT Id, External_Id__c
            FROM Account
            WHERE Id IN :accountIds
            WITH USER_MODE
        ]) {
            externalIds.put(a.Id, a.External_Id__c);
        }
        return externalIds;
    }

    @TestVisible
    private static List<Order_Change__e> buildEvents(List<Order> orders, Map<Id, String> accountExternalIds) {
        List<Order_Change__e> events = new List<Order_Change__e>();
        for (Order o : orders) {
            events.add(new Order_Change__e(
                Event_Uuid__c = newUuid(),
                Order_Id__c = o.Id,
                Order_Number__c = o.OrderNumber,
                Account_External_Id__c = accountExternalIds.get(o.AccountId),
                Status__c = o.Status,
                Total_Amount__c = o.TotalAmount == null ? 0 : o.TotalAmount,
                Currency_Code__c = currencyCodeFor(o),
                Occurred_At__c = System.now()
            ));
        }
        return events;
    }

    /** Multi-currency orgs expose CurrencyIsoCode; single-currency orgs do not. */
    private static String currencyCodeFor(Order o) {
        Map<String, Object> fields = o.getPopulatedFieldsAsMap();
        if (fields.containsKey('CurrencyIsoCode')) {
            return (String) fields.get('CurrencyIsoCode');
        }
        return UserInfo.getDefaultCurrency();
    }

    private static void publishAll(List<Order_Change__e> events) {
        List<Database.SaveResult> results = EventBus.publish(events);
        for (Integer i = 0; i < results.size(); i++) {
            Database.SaveResult result = results[i];
            if (result.isSuccess()) {
                continue;
            }
            // Do not throw: one bad event must not roll back the order transaction.
            for (Database.Error err : result.getErrors()) {
                System.debug(LoggingLevel.ERROR, String.format(
                    'Order_Change__e publish failed for order {0}: {1} {2}',
                    new List<String>{ events[i].Order_Number__c, String.valueOf(err.getStatusCode()), err.getMessage() }
                ));
            }
        }
    }

    /** Apex has no UUID primitive; a 128-bit AES key hex-formatted is the usual stand-in. */
    private static String newUuid() {
        String hex = EncodingUtil.convertToHex(Crypto.generateAesKey(128));
        return hex.substring(0, 8) + '-' +
            hex.substring(8, 12) + '-' +
            hex.substring(12, 16) + '-' +
            hex.substring(16, 20) + '-' +
            hex.substring(20);
    }
}
