# =============================================================================
# Stage 1: Builder — Gradle 8.9 + JDK 17 (호스트 Gradle/JDK 불필요)
# Build context: project root (../../)
# =============================================================================
FROM gradle:8.9-jdk17-alpine AS builder
WORKDIR /workspace

# 의존성 레이어 캐시 (build.gradle 변경 없으면 재사용)
COPY build.gradle settings.gradle gradle.properties ./
COPY common/build.gradle common/build.gradle
# harness-core: auto-loan-review 가 의존한다(에이전트 실행 골격).
# 이게 빠져 있어서 이미지 빌드가 com.bank.harness.validation 을 못 찾고 깨졌다 —
# Gradle 로는 통과하므로 이미지를 만들어 보기 전까지 드러나지 않았다.
COPY agents/harness-core/build.gradle agents/harness-core/build.gradle
COPY agents/auto-loan-review/build.gradle agents/auto-loan-review/build.gradle

# gradle.properties의 Windows JDK 경로는 컨테이너 내에서 유효하지 않으므로 제거
RUN sed -i '/org\.gradle\.java\.home/d' gradle.properties

RUN gradle :agents:auto-loan-review:dependencies --no-daemon -q 2>/dev/null || true

# 소스 복사 + bootJar 빌드
COPY common/src common/src
COPY agents/harness-core/src agents/harness-core/src
COPY agents/auto-loan-review/src agents/auto-loan-review/src

RUN gradle :agents:auto-loan-review:bootJar --no-daemon -q

# =============================================================================
# Stage 2: Runtime — JRE 17 최소 이미지
# =============================================================================
FROM eclipse-temurin:17-jre-alpine AS runtime
WORKDIR /app

COPY --from=builder /workspace/agents/auto-loan-review/build/libs/*.jar app.jar

EXPOSE 8089

ENTRYPOINT ["java", \
  "-XX:MaxRAMPercentage=75", \
  "-XX:+UseG1GC", \
  "-Djava.security.egd=file:/dev/./urandom", \
  "-jar", "app.jar"]
