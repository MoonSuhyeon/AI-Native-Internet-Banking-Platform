#!/usr/bin/env bash
#
# 백엔드 OpenAPI 스펙에서 TypeScript 타입을 생성한다.
#
# **왜 생성하는가.** 손으로 쓴 응답 인터페이스는 백엔드와 어긋나도 아무도 모른다.
# 실제로 상환 스케줄 화면은 전 필드가 어긋난 채(seq/scheduledDt/paidYn vs
# installmentNo/dueDate/rschStatusCd) 3개월 넘게 방치됐다. `api.get<any>` 로 받아
# 그대로 담으므로 TypeScript 는 선언을 검증하지 않는다 — 무엇을 적어도 통과한다.
#
# 생성하면 그 어긋남 자체가 생길 수 없다. 원본이 백엔드 코드이기 때문이다.
#
# **왜 커밋하는가.** 타입 검사(tsc)와 빌드가 백엔드 기동에 묶이면 안 된다.
# 생성물을 커밋해 두고, CI 의 contract 잡이 재생성 후 diff 를 봐서 어긋남을 잡는다.
#
# 사용:
#   docker compose -f infra/docker/docker-compose.e2e.yml up -d --wait
#   web/scripts/generate-api-types.sh
set -euo pipefail

cd "$(dirname "$0")/.."

# core-banking 은 context-path 가 /api 라 스펙 경로도 그 아래다.
declare -a SPECS=(
  "customer-service|http://localhost:8081/v3/api-docs"
  "core-banking|http://localhost:8082/api/v3/api-docs"
  "loan-service|http://localhost:8083/v3/api-docs"
)

mkdir -p lib/generated

for entry in "${SPECS[@]}"; do
  name="${entry%%|*}"
  url="${entry##*|}"

  if ! curl -sf -o /dev/null "$url"; then
    echo "✗ $name — $url 에 닿지 못했다. docker compose -f infra/docker/docker-compose.e2e.yml up -d --wait 를 먼저 실행한다." >&2
    exit 1
  fi

  npx --yes openapi-typescript "$url" -o "lib/generated/${name}.d.ts"
  echo "✓ lib/generated/${name}.d.ts"
done
