# Dockerfile for running integration tests inside Docker network
# V2: No executor module — only credential-proxy and integration-tests
FROM eclipse-temurin:17-jdk

WORKDIR /app

# Copy Gradle wrapper and build files
COPY gradlew settings.gradle.kts build.gradle.kts gradle.properties ./
COPY gradle/ gradle/

# Copy module build files
COPY integration-tests/build.gradle.kts integration-tests/

# Copy source code
COPY integration-tests/src integration-tests/src

# Copy config files
COPY configs/ configs/

# Make gradlew executable
RUN chmod +x gradlew

# Default command runs NPL integration tests
# Environment variables override the test config to use Docker hostnames
ENV NPL_URL=http://npl-engine:12000
ENV KEYCLOAK_URL=http://keycloak:11000
ENV GATEWAY_URL=http://envoy-gateway:8000

ENTRYPOINT ["./gradlew", ":integration-tests:test", "--no-daemon"]
