import com.google.protobuf.gradle.id

plugins {
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.protobuf)
}

/**
 * The Salesforce Pub/Sub API is gRPC. `src/main/proto/pubsub_api.proto` is Salesforce's
 * published contract, vendored rather than fetched at build time so the build is
 * reproducible and works offline.
 */
protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:${libs.versions.protobuf.get()}"
    }
    plugins {
        id("grpc") {
            artifact = "io.grpc:protoc-gen-grpc-java:${libs.versions.grpc.get()}"
        }
    }
    generateProtoTasks {
        all().forEach { task ->
            task.plugins { id("grpc") }
        }
    }
}

// Kotlin compiles against the generated Java stubs, so it has to wait for them.
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    dependsOn(tasks.named("generateProto"))
}

/**
 * Two test lanes.
 *
 * `test` is the one you run constantly: acceptance and unit tests, no Docker, seconds.
 * `integrationTest` needs a Docker daemon for Testcontainers. Keeping them apart means
 * a developer without Docker running still gets a meaningful signal instead of a hang.
 */
tasks.test {
    useJUnitPlatform { excludeTags("integration") }
    description = "Fast tests. No Docker required."
}

val integrationTest = tasks.register<Test>("integrationTest") {
    description = "Testcontainers-backed tests. Requires a running Docker daemon."
    group = "verification"
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    useJUnitPlatform { includeTags("integration") }

    // The Cucumber suite is a JUnit Platform @Suite, and tag filtering applies to the
    // scenarios inside it rather than to the suite class. Discovered here it finds
    // nothing tagged "integration" and fails outright with NoTestsDiscoveredException
    // instead of skipping. It belongs to the `test` lane; keep it out of this one.
    filter { excludeTestsMatching("com.ordersync.acceptance.*") }

    shouldRunAfter(tasks.test)
}

tasks.check {
    dependsOn(integrationTest)
}

dependencies {
    implementation(libs.spring.boot.starter.web)
    implementation(libs.spring.boot.starter.actuator)
    implementation(libs.spring.boot.starter.validation)
    implementation(libs.spring.boot.starter.jdbc)
    implementation(libs.spring.kafka)
    implementation(libs.jackson.module.kotlin)
    implementation(libs.kotlin.reflect)
    implementation(libs.flyway.core)
    implementation(libs.flyway.postgres)
    implementation(libs.resilience4j.spring.boot3)
    implementation(libs.httpclient5)

    implementation(libs.grpc.netty.shaded)
    implementation(libs.grpc.protobuf)
    implementation(libs.grpc.stub)
    implementation(libs.protobuf.java)
    implementation(libs.avro)
    compileOnly(libs.tomcat.annotations)
    implementation(libs.micrometer.tracing.bridge.otel)
    runtimeOnly(libs.postgresql)
    runtimeOnly(libs.micrometer.prometheus)
    runtimeOnly(libs.otel.exporter.otlp)

    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.spring.kafka.test)
    testImplementation(libs.kotest.assertions)
    testImplementation(libs.mockk)
    testImplementation(libs.awaitility)
    testImplementation(libs.cucumber.java)
    testImplementation(libs.cucumber.junit.platform)
    // libs.cucumber.spring is deliberately absent: its mere presence on the classpath
    // makes Cucumber demand a @CucumberContextConfiguration class. The acceptance
    // tests run against in-memory adapters and want no Spring context. Add it back
    // when a slice genuinely needs one.
    testImplementation(libs.junit.platform.suite)
    testImplementation(libs.testcontainers.junit)
    testImplementation(libs.testcontainers.kafka)
    testImplementation(libs.testcontainers.postgres)
    testImplementation(libs.wiremock)
}
