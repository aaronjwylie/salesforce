@IsTest
private class OrderChangePublisherTest {

    private static final Id ACCOUNT_ID = fakeId(Account.SObjectType, 1);

    @IsTest
    static void buildsOneEventPerOrder() {
        List<Order> orders = new List<Order>{
            orderFixture(fakeId(Order.SObjectType, 1), '00000101', 'Activated', 2500.00),
            orderFixture(fakeId(Order.SObjectType, 2), '00000102', 'Cancelled', 40.00)
        };

        List<Order_Change__e> events = OrderChangePublisher.buildEvents(orders, accountExternalIds());

        Assert.areEqual(2, events.size(), 'expected one event per order');
        Assert.areEqual('00000101', events[0].Order_Number__c);
        Assert.areEqual('Activated', events[0].Status__c);
        Assert.areEqual(2500.00, events[0].Total_Amount__c);
        Assert.areEqual('ACCT-42', events[0].Account_External_Id__c);
    }

    @IsTest
    static void givesEveryEventADistinctIdempotencyKey() {
        List<Order> orders = new List<Order>{
            orderFixture(fakeId(Order.SObjectType, 1), '00000101', 'Activated', 10.00),
            orderFixture(fakeId(Order.SObjectType, 2), '00000102', 'Activated', 10.00)
        };

        List<Order_Change__e> events = OrderChangePublisher.buildEvents(orders, accountExternalIds());

        Assert.areNotEqual(events[0].Event_Uuid__c, events[1].Event_Uuid__c, 'event uuids must be unique');
        Assert.areEqual(36, events[0].Event_Uuid__c.length(), 'expected a 36 character uuid');
    }

    @IsTest
    static void treatsMissingTotalAsZeroRatherThanNull() {
        List<Order> orders = new List<Order>{
            orderFixture(fakeId(Order.SObjectType, 1), '00000101', 'Activated', null)
        };

        List<Order_Change__e> events = OrderChangePublisher.buildEvents(orders, accountExternalIds());

        Assert.areEqual(0, events[0].Total_Amount__c, 'a null total would break the ERP contract');
    }

    @IsTest
    static void skipsDraftOrders() {
        Order draft = orderFixture(fakeId(Order.SObjectType, 1), '00000101', 'Draft', 10.00);

        Assert.isFalse(OrderChangePublisher.isRelevant(draft, null), 'drafts must not reach the ERP');
    }

    @IsTest
    static void skipsUpdatesThatChangeNothingTheErpCaresAbout() {
        Id orderId = fakeId(Order.SObjectType, 1);
        Order current = orderFixture(orderId, '00000101', 'Activated', 100.00);
        Order previous = orderFixture(orderId, '00000101', 'Activated', 100.00);

        Boolean relevant = OrderChangePublisher.isRelevant(current, new Map<Id, Order>{ orderId => previous });

        Assert.isFalse(relevant, 'an unrelated field edit must not burn event allocation');
    }

    @IsTest
    static void publishesWhenTheTotalChanges() {
        Id orderId = fakeId(Order.SObjectType, 1);
        Order current = orderFixture(orderId, '00000101', 'Activated', 250.00);
        Order previous = orderFixture(orderId, '00000101', 'Activated', 100.00);

        Boolean relevant = OrderChangePublisher.isRelevant(current, new Map<Id, Order>{ orderId => previous });

        Assert.isTrue(relevant, 'the ERP needs to know the order total moved');
    }

    @IsTest
    static void staysWithinOneQueryForALargeBatch() {
        Account account = new Account(Name = 'Northwind', External_Id__c = 'ACCT-42');
        insert account;

        List<Order> orders = new List<Order>();
        for (Integer i = 0; i < 200; i++) {
            orders.add(new Order(
                AccountId = account.Id,
                EffectiveDate = Date.today(),
                Status = 'Draft'
            ));
        }
        insert orders;

        // Re-read so Status and AccountId are populated the way a trigger would see them.
        List<Order> reloaded = [SELECT Id, OrderNumber, AccountId, Status, TotalAmount FROM Order];
        for (Order o : reloaded) {
            o.Status = 'Activated';
        }

        Test.startTest();
        OrderChangePublisher.publish(reloaded, null);
        Integer queries = Limits.getQueries();
        Test.stopTest();

        Assert.isTrue(queries <= 1, 'account lookup must be bulkified, used ' + queries + ' queries');
    }

    private static Map<Id, String> accountExternalIds() {
        return new Map<Id, String>{ ACCOUNT_ID => 'ACCT-42' };
    }

    /**
     * OrderNumber and TotalAmount are read-only, so they cannot be assigned directly.
     * Deserializing from JSON is the standard way to build a realistic in-memory
     * fixture without paying for DML.
     */
    private static Order orderFixture(Id orderId, String orderNumber, String status, Decimal total) {
        Map<String, Object> fields = new Map<String, Object>{
            'attributes' => new Map<String, Object>{ 'type' => 'Order' },
            'Id' => orderId,
            'OrderNumber' => orderNumber,
            'AccountId' => ACCOUNT_ID,
            'Status' => status,
            'TotalAmount' => total
        };
        return (Order) JSON.deserialize(JSON.serialize(fields), Order.class);
    }

    private static Id fakeId(Schema.SObjectType sobjectType, Integer index) {
        String prefix = sobjectType.getDescribe().getKeyPrefix();
        return Id.valueOf(prefix + '0'.repeat(12 - String.valueOf(index).length()) + index);
    }
}
