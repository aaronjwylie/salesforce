package com.ordersync

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.scheduling.annotation.EnableScheduling

@SpringBootApplication
@EnableScheduling
class OrderSyncApplication

fun main(args: Array<String>) {
    runApplication<OrderSyncApplication>(*args)
}
