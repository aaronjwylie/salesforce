package com.ordersync.application

import com.ordersync.domain.OrderChangeProcessor
import com.ordersync.domain.ProcessResult
import com.ordersync.domain.SalesforceOrderChange
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * The transaction boundary.
 *
 * Everything the processor does — the dedupe insert, the outbox write, the checkpoint
 * update — commits together or not at all. That single fact is what makes the whole
 * design safe: a crash anywhere in here leaves the event unprocessed and unacknowledged,
 * so Salesforce redelivers it and we start again cleanly.
 */
@Service
class OrderChangeService(private val processor: OrderChangeProcessor) {

    @Transactional
    fun handle(change: SalesforceOrderChange): ProcessResult = processor.process(change)
}
