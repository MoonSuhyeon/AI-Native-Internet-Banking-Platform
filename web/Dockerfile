# 프론트(Next.js) 운영 이미지.
#
# 빌드 인자가 10개였다. 그중 아홉은 서비스 직통 주소
# (NEXT_PUBLIC_DEPOSIT_API_URL·NEXT_PUBLIC_LOAN_API_URL 등)로, 게이트웨이를
# 건너뛰면 검증된 신원 헤더가 붙지 않아 인가가 통째로 무의미해진다.
# 지금 코드에서 그 변수들을 읽는 곳은 0곳이고, FrontendGatewayOnlyTest 가
# 되살아나는 것을 막는다. 그래서 이미지에서도 없앴다 — 남겨 두면
# 빌드 인자 한 줄로 우회로가 되살아난다.
#
# NEXT_PUBLIC_* 은 **빌드 시점에 번들에 박힌다.** 런타임 환경변수로 바꿀 수 없어
# 도메인이 바뀌면 이미지를 다시 빌드해야 한다. 그래서 기본값을 상대경로로 둔다 —
# Caddy 가 같은 오리진에서 /api 를 게이트웨이로 넘기므로 도메인을 몰라도 된다.

FROM node:20-alpine AS deps
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci

FROM node:20-alpine AS builder
WORKDIR /app
COPY --from=deps /app/node_modules ./node_modules
COPY . .
ENV NEXT_TELEMETRY_DISABLED=1

# 게이트웨이 주소. 비우면 같은 오리진의 상대경로가 된다(Caddy 가 /api 를 넘긴다).
ARG NEXT_PUBLIC_API_URL=""
ENV NEXT_PUBLIC_API_URL=$NEXT_PUBLIC_API_URL

# Grafana 임베드(대시보드 iframe). API 가 아니다.
ARG NEXT_PUBLIC_GRAFANA_URL=""
ENV NEXT_PUBLIC_GRAFANA_URL=$NEXT_PUBLIC_GRAFANA_URL

RUN npm run build

FROM node:20-alpine AS runner
WORKDIR /app
ENV NODE_ENV=production
ENV NEXT_TELEMETRY_DISABLED=1
RUN addgroup --system --gid 1001 nodejs && adduser --system --uid 1001 nextjs
COPY --from=builder /app/public ./public
COPY --from=builder --chown=nextjs:nodejs /app/.next/standalone ./
COPY --from=builder --chown=nextjs:nodejs /app/.next/static ./.next/static
USER nextjs
EXPOSE 3000
ENV PORT=3000
ENV HOSTNAME=0.0.0.0

# 서버측 프록시(app/api/**)가 컨테이너 안에서 부를 주소. 런타임 값이라
# 도메인이 바뀌어도 재빌드가 필요 없다.
ENV CUSTOMER_API_URL=http://api-gateway:8080
ENV CONSULTATION_API_URL=http://consultation-service:8087

CMD ["node", "server.js"]
