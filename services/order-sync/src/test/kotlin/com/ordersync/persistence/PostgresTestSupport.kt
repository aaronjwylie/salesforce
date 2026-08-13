package com.ordersync.persistence

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.springframework.jdbc.core.JdbcTemplate
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import javax.sql.DataSource

/**
 * Real Postgres, no Spring context.
 *
 * These tests exist to prove SQL behaviour — `ON CONFLICT`, `SKIP LOCKED`, the
 * monotonic checkpoint upsert. None of that survives being mocked, and none of it
 * needs an application context to exercise, so the setup stays this small.
 *
 * The container is started once for the whole suite (the Testcontainers singleton
 * pattern); each test gets a clean schema instead of a clean container.
 */
@Tag("integration")
abstract class PostgresTestSupport {

    companion object {
        private val postgres: PostgreSQLContainer<*> =
            PostgreSQLContainer(DockerImageName.parse("postgres:16-alpine"))
                .withDatabaseName("ordersync")
                .withUsername("ordersync")
                .withPassword("ordersync")
                .also { it.start() }

        val dataSource: DataSource by lazy {
            HikariDataSource(
                HikariConfig().apply {
                    jdbcUrl = postgres.jdbcUrl
                    username = postgres.username
                    password = postgres.password
                    maximumPoolSize = 8
                },
            )
        }
    }

    protected val jdbc = JdbcTemplate(dataSource)

    @BeforeEach
    fun resetSchema() {
        Flyway.configure()
            .dataSource(dataSource)
            .cleanDisabled(false)
            .load()
            .also { it.clean() }
            .migrate()
    }
}
