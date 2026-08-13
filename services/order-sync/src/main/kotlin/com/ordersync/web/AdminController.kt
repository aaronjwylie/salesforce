package com.ordersync.web

import com.ordersync.config.TopicProperties
import com.ordersync.kafka.DlqReplayService
import com.ordersync.kafka.ReplayReport
import com.ordersync.persistence.OutboxRepository
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

data class OutboxStatus(val depth: Long, val oldestAgeSeconds: Double)

/**
 * Operator endpoints.
 *
 * Unauthenticated here because this is a demo stack. In a real deployment these sit
 * behind the platform's auth and are not exposed outside the cluster — a public
 * "replay everything in the DLQ" button is an incident waiting to happen.
 */
@RestController
@RequestMapping("/admin")
class AdminController(
    private val dlqReplay: DlqReplayService,
    private val outbox: OutboxRepository,
    private val topics: TopicProperties,
) {

    @GetMapping("/outbox")
    fun outboxStatus() = OutboxStatus(outbox.depth(), outbox.oldestAgeSeconds())

    @PostMapping("/dlq/orders/replay")
    fun replayOrders(@RequestParam(defaultValue = "100") max: Int): ReplayReport =
        dlqReplay.replay(topics.ordersDlq, topics.orders, max)

    @PostMapping("/dlq/fulfillment/replay")
    fun replayFulfillment(@RequestParam(defaultValue = "100") max: Int): ReplayReport =
        dlqReplay.replay(topics.fulfillmentDlq, topics.fulfillment, max)
}
