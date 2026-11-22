# syntax=docker/dockerfile:1.7

# ---- Build stage -----------------------------------------------------------
# The whole reactor is built from source so the produced jar matches the local
# build exactly. Only cache-server is packaged (it bundles cache-core).
FROM maven:3.9-eclipse-temurin-17 AS build

WORKDIR /workspace

# Poms first: dependency resolution then caches across source-only changes.
COPY pom.xml ./
COPY cache-core/pom.xml cache-core/pom.xml
COPY cache-server/pom.xml cache-server/pom.xml
COPY cache-bench/pom.xml cache-bench/pom.xml

# Warm the local repository so the source copy below is the only rebuild cost.
RUN mvn -B -q -pl cache-server -am dependency:go-offline

COPY cache-core/src cache-core/src
COPY cache-server/src cache-server/src

RUN mvn -B -pl cache-server -am package -DskipTests

# ---- Runtime stage ---------------------------------------------------------
FROM eclipse-temurin:22-jre-jammy AS runtime

# curl is only used by the container healthcheck.
RUN apt-get update \
 && apt-get install -y --no-install-recommends curl \
 && rm -rf /var/lib/apt/lists/* \
 && groupadd --system --gid 1001 cache \
 && useradd --system --uid 1001 --gid cache --no-create-home cache

WORKDIR /app

COPY --from=build --chown=cache:cache /workspace/cache-server/target/cache-server-*.jar /app/app.jar

USER cache

EXPOSE 8080

ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError" \
    SPRING_PROFILES_ACTIVE=""

# start-period covers JVM start plus Spring context refresh.
HEALTHCHECK --interval=15s --timeout=3s --start-period=40s --retries=3 \
  CMD curl -fsS http://localhost:8080/actuator/health/readiness || exit 1

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
