package com.ordersync.erp

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.math.BigDecimal
import java.util.concurrent.ConcurrentHashMap

/**
 * Stands in for Oracle EBS. Deliberately dumb — it exists so the integration has a
 * real HTTP peer to talk to in docker-compose, not to model an ERP.
 */
@SpringBootApplication
class MockErpApplication

fun main(args: Array<String>) {
    runApplication<MockErpApplication>(*args)
}

data class ErpOrderRequest(
    val orderNumber: String,
    val accountExternalId: String,
    val status: String,
    val totalAmount: BigDecimal,
    val currencyCode: String,
)

data class ErpOrder(val erpOrderId: String, val request: ErpOrderRequest)

@RestController
@RequestMapping("/api/orders")
class ErpOrderController {

    private val orders = ConcurrentHashMap<String, ErpOrder>()

    /** Upsert by order number so a redelivered event is harmless at this end too. */
    @PostMapping
    fun receive(@RequestBody request: ErpOrderRequest): ResponseEntity<ErpOrder> {
        val existing = orders[request.orderNumber]
        val order = ErpOrder(
            erpOrderId = existing?.erpOrderId ?: "ERP-${orders.size + 1}",
            request = request,
        )
        orders[request.orderNumber] = order
        return ResponseEntity.status(if (existing == null) HttpStatus.CREATED else HttpStatus.OK).body(order)
    }

    @GetMapping
    fun list(): Collection<ErpOrder> = orders.values
}
