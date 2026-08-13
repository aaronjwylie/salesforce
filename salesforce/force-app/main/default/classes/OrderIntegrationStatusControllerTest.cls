@IsTest
private class OrderIntegrationStatusControllerTest {

    @TestSetup
    static void setUp() {
        Account account = new Account(Name = 'Northwind Traders', External_Id__c = 'ACCT-42');
        insert account;

        insert new Order(
            AccountId = account.Id,
            EffectiveDate = Date.today(),
            Status = 'Draft'
        );
    }

    private static Order theOrder() {
        return [SELECT Id, OrderNumber, Status FROM Order LIMIT 1];
    }

    @IsTest
    static void reportsAnOrderThatHasNotReachedTheErp() {
        Order order = theOrder();

        Test.startTest();
        OrderIntegrationStatusController.IntegrationStatus status =
            OrderIntegrationStatusController.getStatus(order.Id);
        Test.stopTest();

        Assert.isFalse(status.reachedErp, 'no ERP id has been written back yet');
        Assert.areEqual(order.OrderNumber, status.orderNumber);
    }

    @IsTest
    static void marksDraftOrdersAsNotYetEligible() {
        Order order = theOrder();

        OrderIntegrationStatusController.IntegrationStatus status =
            OrderIntegrationStatusController.getStatus(order.Id);

        Assert.isFalse(status.eligible, 'a draft is not expected to have synced');
    }

    @IsTest
    static void reportsAnOrderTheErpHasAcknowledged() {
        Order order = theOrder();
        order.ERP_Order_Id__c = 'ERP-8891';
        order.Fulfillment_Status__c = 'Shipped';
        update order;

        OrderIntegrationStatusController.IntegrationStatus status =
            OrderIntegrationStatusController.getStatus(order.Id);

        Assert.isTrue(status.reachedErp);
        Assert.areEqual('ERP-8891', status.erpOrderId);
        Assert.areEqual('Shipped', status.fulfillmentStatus);
    }

    @IsTest
    static void refusesToRetryADraftOrder() {
        Order order = theOrder();

        Test.startTest();
        try {
            OrderIntegrationStatusController.retrySync(order.Id);
            Assert.fail('expected a draft order to be refused');
        } catch (AuraHandledException e) {
            // AuraHandledException masks getMessage() in tests, so assert the type only.
            Assert.isNotNull(e, 'a draft order must not be republished');
        }
        Test.stopTest();
    }

    @IsTest
    static void refusesAnOrderThatDoesNotExist() {
        Id missingId = '801000000000001AAA';

        Test.startTest();
        try {
            OrderIntegrationStatusController.getStatus(missingId);
            Assert.fail('expected a missing order to be refused');
        } catch (AuraHandledException e) {
            Assert.isNotNull(e);
        }
        Test.stopTest();
    }
}
