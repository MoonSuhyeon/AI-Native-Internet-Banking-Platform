# =============================================================================
# fds-detector — 이상거래 탐지
# Stage 1: Builder — Gradle 8.9 + JDK 17 (호스트 Gradle/JDK 불필요)
# Build context: project root (../../)
# =============================================================================
FROM gradle:8.9-jdk17-alpine AS builder
WORKDIR /workspace

# 의존성 레이어 캐시 (build.gradle 변경 없으면 재사용)
COPY build.gradle settings.gradle gradle.properties ./
COPY common/build.gradle common/build.gradle
COPY services/fds-detector/build.gradle services/fds-detector/build.gradle

# gradle.properties의 Windows JDK 경로는 컨테이너 내에서 유효하지 않으므로 제거
RUN sed -i '/org\.gradle\.java\.home/d' gradle.properties

RUN gradle :services:fds-detector:dependencies --no-daemon -q 2>/dev/null || true

COPY common/src common/src
COPY services/fds-detector/src services/fds-detector/src

RUN gradle :services:fds-detector:bootJar --no-daemon -q

# =============================================================================
# Stage 2: Runtime — JRE 17 최소 이미지
# =============================================================================
FROM eclipse-temurin:17-jre-alpine AS runtime
WORKDIR /app

COPY --from=builder /workspace/services/fds-detector/build/libs/*.jar app.jar

EXPOSE 8092

ENTRYPOINT ["java", \
  "-XX:MaxRAMPercentage=75", \
  "-XX:+UseG1GC", \
  "-Djava.security.egd=file:/dev/./urandom", \
  "-jar", "app.jar"]
