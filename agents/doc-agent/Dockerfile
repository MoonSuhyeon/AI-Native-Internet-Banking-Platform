FROM gradle:8.9-jdk17-alpine AS builder
WORKDIR /workspace

COPY build.gradle settings.gradle gradle.properties ./
COPY common/build.gradle common/build.gradle
COPY agents/doc-agent/build.gradle agents/doc-agent/build.gradle

RUN sed -i '/org\.gradle\.java\.home/d' gradle.properties

RUN gradle :agents:doc-agent:dependencies --no-daemon -q 2>/dev/null || true

COPY common/src common/src
COPY agents/doc-agent/src agents/doc-agent/src

RUN gradle :agents:doc-agent:bootJar --no-daemon -q

FROM eclipse-temurin:17-jre-alpine AS runtime
WORKDIR /app

COPY --from=builder /workspace/agents/doc-agent/build/libs/*.jar app.jar

EXPOSE 8087

ENTRYPOINT ["java", \
  "-Xmx384m", \
  "-XX:+UseSerialGC", \
  "-Djava.security.egd=file:/dev/./urandom", \
  "-jar", "app.jar"]
