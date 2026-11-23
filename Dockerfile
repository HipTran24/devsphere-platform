# syntax=docker/dockerfile:1.7

# ---------------------------------------------------------------------------
# Stage 1: build the Spring Boot executable jar with the Maven wrapper (JDK 27)
# ---------------------------------------------------------------------------
FROM eclipse-temurin:27-jdk AS build
WORKDIR /workspace

# Resolve dependencies first so the layer is cached until pom.xml changes.
COPY mvnw mvnw.cmd pom.xml ./
COPY .mvn .mvn
RUN chmod +x mvnw
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -q -DskipTests dependency:go-offline

COPY src src
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -q -DskipTests package \
    && cp target/*.jar /workspace/app.jar

# ---------------------------------------------------------------------------
# Stage 2: slim runtime image, non-root user
# ---------------------------------------------------------------------------
FROM eclipse-temurin:27-jre AS runtime

RUN apt-get update \
    && apt-get install -y --no-install-recommends curl tini \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --system --gid 10001 devsphere \
    && useradd --system --uid 10001 --gid devsphere --home-dir /app --shell /usr/sbin/nologin devsphere

WORKDIR /app
COPY --from=build --chown=devsphere:devsphere /workspace/app.jar /app/app.jar

ENV SPRING_PROFILES_ACTIVE=prod \
    JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError -Dmanagement.metrics.distribution.percentiles-histogram.http.server.requests=true"

USER devsphere:devsphere
EXPOSE 8080 8081

# The prod profile serves actuator on the internal management port 8081 only.
HEALTHCHECK --interval=30s --timeout=5s --start-period=90s --retries=5 \
    CMD curl -fsS http://127.0.0.1:8081/actuator/health/readiness || exit 1

ENTRYPOINT ["/usr/bin/tini", "--", "sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
