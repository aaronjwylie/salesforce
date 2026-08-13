/**
 * Single trigger per object. All logic lives in OrderChangePublisher so it stays
 * unit-testable without DML.
 */
trigger OrderChangeTrigger on Order (after insert, after update) {
    OrderChangePublisher.publish(Trigger.new, Trigger.oldMap);
}
