Feature: Salesforce order changes reach the ERP

  As the fulfillment team
  We need activated Salesforce orders to show up in the ERP
  So that the warehouse can pick and ship them without anyone rekeying data

  Background:
    Given no order events have been published

  Scenario: An activated order is forwarded to the ERP
    When Salesforce publishes order "SO-1001" with status "ACTIVATED" at stream position "p100"
    Then order "SO-1001" is published to the ERP topic
    And the stream position is remembered as "p100"

  Scenario: A draft order is held back
    When Salesforce publishes order "SO-1002" with status "DRAFT" at stream position "p101"
    Then no order is published to the ERP topic

  # If we only checkpointed on success, every restart would re-read the drafts we
  # deliberately ignored and the stream position would never advance past them.
  Scenario: The stream position advances even when nothing is forwarded
    When Salesforce publishes order "SO-1002" with status "DRAFT" at stream position "p101"
    Then the stream position is remembered as "p101"

  Scenario: A redelivered event is forwarded only once
    Given Salesforce published order "SO-1003" with status "ACTIVATED" at stream position "p102"
    When Salesforce redelivers that event
    Then order "SO-1003" is published to the ERP topic exactly once

  # Reconciliation re-derives orders from SOQL when the stream's 72 hour replay window
  # has expired. Those orders were never on the stream, so recording a position for
  # them would move the checkpoint somewhere the subscription has never been.
  Scenario: An order recovered by reconciliation still reaches the ERP
    When reconciliation recovers order "SO-2001" with status "ACTIVATED"
    Then order "SO-2001" is published to the ERP topic

  Scenario: An order recovered by reconciliation does not move the stream position
    Given Salesforce published order "SO-1005" with status "ACTIVATED" at stream position "p200"
    When reconciliation recovers order "SO-2001" with status "ACTIVATED"
    Then the stream position is remembered as "p200"

  Scenario: Money survives the translation intact
    When Salesforce publishes order "SO-1004" for 2500.00 CAD with status "ACTIVATED" at stream position "p103"
    Then the published order carries 2500.00 CAD
