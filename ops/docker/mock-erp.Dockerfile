# Builds the ERP stub from source so `docker compose --profile full up` needs nothing
# on the host but Docker.
FROM eclipse-temurin:17-jdk AS build
WORKDIR /workspace
COPY gradlew gradlew.bat settings.gradle.kts build.gradle.kts gradle.properties ./
COPY gradle gradle
COPY services/mock-erp services/mock-erp
COPY services/order-sync/build.gradle.kts services/order-sync/build.gradle.kts
RUN chmod +x gradlew && ./gradlew :services:mock-erp:bootJar --no-daemon

FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /workspace/services/mock-erp/build/libs/*.jar app.jar
EXPOSE 8081
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
