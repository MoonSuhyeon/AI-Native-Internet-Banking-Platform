# Phase E — 심사원 대시보드 (Reviewer Dashboard Frontend) 실행 계획

<!-- plan-status -->
> **상태: 🗑 폐기 — 따르지 말 것** — `services/reviewer-dashboard/`(React+Vite)를 전제하는데 그 디렉터리가 없다. 프론트는 `web/`(Next.js) 하나로 갔다
>
> 상태 어휘는 넷뿐이다(구현됨 · 진행 중 · 설계만 · 폐기).
> 전체 목록은 [`docs/plan/README.md`](README.md).


> Last updated: 2026-05-26 (v1.0)
> 선행 완료: Phase A (A0~A10), Phase B (B1~B5 구현 완료 전제)
> 기술 스택: React 18 + TypeScript + Vite + shadcn/ui + Tailwind CSS + Zustand + TanStack Query v5 + SSE (native EventSource)
> 패키지 루트: `services/reviewer-dashboard/`

---

## 전체 목표

백엔드 AI 파이프라인(Phase A·B)이 산출한 `AgentOpinion`, `ReviewReport`, `AutoReviewEvaluateResponse` 를
심사원이 실제로 활용할 수 있는 웹 UI 로 전달한다. Track별 심사 카드, 실시간 SSE Push, 관리자 운영 패널,
PII 마스킹·RBAC 접근 제어를 포함한다.

---

## 프로젝트 디렉터리 구조

```
services/reviewer-dashboard/
├── index.html
├── vite.config.ts
├── tailwind.config.ts
├── tsconfig.json
├── vitest.config.ts
├── .env.local                        # VITE_API_BASE_URL, VITE_SSE_URL
├── public/
│   └── favicon.svg
└── src/
    ├── main.tsx
    ├── App.tsx
    ├── router.tsx                    # React Router v6 라우트 정의
    │
    ├── types/                        # 전체 도메인 타입 정의
    │   ├── agentOpinion.ts
    │   ├── reviewReport.ts
    │   ├── evaluateResponse.ts
    │   ├── sseEvent.ts
    │   ├── adminPanel.ts
    │   └── index.ts
    │
    ├── api/                          # Axios 인스턴스 + 엔드포인트 함수
    │   ├── axiosInstance.ts
    │   ├── autoReview.ts
    │   ├── adminApi.ts
    │   └── sseClient.ts
    │
    ├── hooks/                        # TanStack Query custom hooks
    │   ├── useQueueBoard.ts
    │   ├── useReviewDetail.ts
    │   ├── useAuditLog.ts
    │   ├── useAgentStatus.ts
    │   ├── useShadowReport.ts
    │   ├── useFairnessReport.ts
    │   ├── usePsiReport.ts
    │   └── useSseSubscription.ts
    │
    ├── store/                        # Zustand 전역 상태
    │   ├── authStore.ts
    │   ├── queueStore.ts
    │   └── adminStore.ts
    │
    ├── pages/
    │   ├── LoginPage.tsx
    │   ├── QueueBoardPage.tsx
    │   ├── ReviewDetailPage.tsx      # Track 카드 렌더 라우트
    │   └── AdminPanelPage.tsx
    │
    ├── components/
    │   ├── layout/
    │   │   ├── AppShell.tsx
    │   │   ├── Sidebar.tsx
    │   │   └── TopBar.tsx
    │   │
    │   ├── queue/
    │   │   ├── QueueBoard.tsx
    │   │   ├── QueueRow.tsx
    │   │   └── TrackBadge.tsx
    │   │
    │   ├── cards/
    │   │   ├── Track1Card.tsx
    │   │   ├── Track2Card.tsx
    │   │   ├── Track3Card.tsx
    │   │   ├── ScoreGauge.tsx
    │   │   ├── PolicyChecklist.tsx
    │   │   ├── RejectionDraftEditor.tsx
    │   │   ├── ShapBarChart.tsx
    │   │   ├── SimulationCardList.tsx
    │   │   ├── AgentOpinionSummary.tsx
    │   │   └── CitationList.tsx
    │   │
    │   ├── admin/
    │   │   ├── ShadowModeToggle.tsx
    │   │   ├── RpmGauge.tsx
    │   │   ├── FallbackPieChart.tsx
    │   │   ├── FairnessTable.tsx
    │   │   └── PsiDriftTable.tsx
    │   │
    │   └── shared/
    │       ├── ErrorBoundary.tsx
    │       ├── LoadingSkeleton.tsx
    │       ├── PiiMaskedText.tsx
    │       ├── DisagreementAlert.tsx
    │       └── SseStatusIndicator.tsx
    │
    └── test/
        ├── setup.ts
        ├── mocks/
        │   ├── handlers.ts           # MSW 핸들러
        │   └── server.ts
        └── fixtures/
            ├── agentOpinionFixtures.ts
            └── reviewReportFixtures.ts
```

---

## TypeScript 전체 도메인 타입 정의

```typescript
// src/types/agentOpinion.ts

export type RiskLevel = 'LOW' | 'MEDIUM' | 'HIGH';

export type FallbackReason =
  | 'LLM_RATE_LIMITED'
  | 'LLM_DAILY_CAP_EXCEEDED'
  | 'GROUNDING_FAILED'
  | 'LOOP_GUARD_HIT'
  | 'TOOL_ERROR'
  | 'AGENT_TIMEOUT'
  | 'AGENT_DISABLED';

export interface SimulationResult {
  scenario: string;
  mutated_amount_kw: number;
  mutated_period_mo: number;
  new_decision_score: number;
  new_pd_score: number;
  result: 'risk_reduced' | 'no_change' | 'risk_increased';
  suggestion: string;
  still_violates: boolean;
}

export interface AgentOpinion {
  schema_version: string;
  decision_score: number | null;
  pd_score: number | null;
  risk_level: RiskLevel | null;
  policy_flags: string[];
  reasoning_summary: string;
  simulation_results: SimulationResult[];
  disagreement: boolean;
  fallback_reason: FallbackReason | null;
}
```

```typescript
// src/types/reviewReport.ts

export interface RiskFactor {
  code: string;
  description: string;
  weight: number;
  citationId: string | null;
}

export interface Strength {
  code: string;
  description: string;
  citationId: string | null;
}

export interface Citation {
  id: string;
  source: string;
  text: string;
}

export type Track = 'TRACK_1' | 'TRACK_2' | 'TRACK_3';

export interface ReviewReport {
  track: Track;
  summary: string;
  riskFactors: RiskFactor[];
  strengths: Strength[];
  recommendation: string;
  citations: Citation[];
  fallbackReason: string | null;
}
```

```typescript
// src/types/evaluateResponse.ts

export interface AutoReviewEvaluateResponse {
  modelVersion: string;
  pdModelVersion: string | null;
  pd: number;
  decisionScore: number | null;
  proba: Record<string, number>;
  track: Track;
  trackDisplayName: string;
  pdThreshold: number;
  safetyMarginThreshold: number;
  hardFailCodes: string[];
  hardFailMessages: string[];
  rationale: string;
  reportStatus: 'PENDING' | 'DONE' | 'FAILED';
  shadow: boolean;
}
```

```typescript
// src/types/sseEvent.ts

export type SseEventType =
  | 'REPORT_READY'
  | 'COMPLIANCE_REQUIRED'
  | 'AGENT_STATUS_CHANGED'
  | 'QUEUE_UPDATED'
  | 'HEARTBEAT';

export interface SsePayload<T = unknown> {
  eventType: SseEventType;
  revId: number;
  timestamp: string;
  data: T;
}

export interface ReportReadyData {
  track: Track;
  reportStatus: 'DONE' | 'FAILED';
  reportJson: ReviewReport;
  agentOpinionJson: AgentOpinion;
}

export interface QueueUpdatedData {
  revId: number;
  applicantName: string;   // PII — 마스킹 처리 필수
  track: Track | null;
  reportStatus: string;
  disagreement: boolean;
}
```

```typescript
// src/types/adminPanel.ts

export interface AgentStatusResponse {
  rpdRemaining: number;
  rpdTotal: number;
  rpmRemaining: number;
  rpmTotal: number;
  fallbackDistribution: Record<string, number>;
  shadowModeEnabled: boolean;
}

export interface ShadowMatchRate {
  track: Track;
  totalCases: number;
  matchCount: number;
  matchRate: number;
}

export interface ShadowReportResponse {
  from: string;
  to: string;
  rates: ShadowMatchRate[];
}

export interface FairnessCell {
  protectedAttr: string;
  attrValue: string;
  track1Rate: number;
  track2Rate: number;
  totalCases: number;
  fourFifthsPass: boolean;
}

export interface FairnessReportResponse {
  week: string;
  cells: FairnessCell[];
}

export interface PsiFeatureDrift {
  featureName: string;
  psi: number;
  alertThreshold: number;
  isAlert: boolean;
}

export interface PsiReportResponse {
  date: string;
  features: PsiFeatureDrift[];
}
```

---

## E1. 프로젝트 설정 + 빌드 파이프라인

### 1. 목표

Vite 기반 React 18 TypeScript 앱을 `services/reviewer-dashboard/` 에 구성하고,
개발 프록시, 환경 변수, Tailwind + shadcn/ui, Vitest + Testing Library, MSW 핸들러까지
CI에서 바로 실행 가능한 상태로 완성한다.

### 2. 파일/컴포넌트 구조

```
vite.config.ts            — proxy: /api → http://localhost:8090, /events → http://localhost:8090
tailwind.config.ts        — content: ["./src/**/*.{ts,tsx}"], shadcn 경로 포함
tsconfig.json             — strict: true, paths: { "@/*": ["./src/*"] }
vitest.config.ts          — environment: jsdom, setupFiles: ["./src/test/setup.ts"]
.env.local                — VITE_API_BASE_URL=http://localhost:8090
```

### 3. 핵심 설정 설계

```typescript
// vite.config.ts (요약)
export default defineConfig({
  plugins: [react()],
  server: {
    proxy: {
      '/api': { target: 'http://localhost:8090', changeOrigin: true },
      '/events': { target: 'http://localhost:8090', changeOrigin: true }
    }
  },
  resolve: { alias: { '@': path.resolve(__dirname, './src') } }
});
```

```typescript
// src/test/setup.ts
import '@testing-library/jest-dom';
import { server } from './mocks/server';
beforeAll(() => server.listen({ onUnhandledRequest: 'warn' }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());
```

### 4. 단계별 커밋 테이블

| # | 작업 | 타입 | 커밋 메시지 |
|---|------|------|------------|
| E1-1 | Vite + React 18 + TS 스캐폴딩 + 환경변수 설정 | feat | `feat(dashboard): E1 Vite·React18·TS 프로젝트 초기 설정` |
| E1-2 | Tailwind + shadcn/ui 설치 + theme 토큰 | feat | `feat(dashboard): E1 Tailwind·shadcn/ui 테마 설정` |
| E1-3 | Vitest + Testing Library + MSW 설치 + setup.ts | feat | `feat(dashboard): E1 Vitest·MSW 테스트 환경 설정` |
| E1-4 | smoke 테스트 (App 렌더 + 라우터 동작) | test | `test(dashboard): E1 앱 기동 smoke 테스트` |

### 5. 완료 기준

- `pnpm dev` → 브라우저 http://localhost:5173 접속 가능
- `pnpm test` → 0 fail
- `pnpm build` → dist/ 번들 에러 없음
- shadcn Button 컴포넌트 storybook 렌더 확인

---

## E2. SSE 실시간 Push 연동

### 1. 목표

`GET /api/ai/events/stream` SSE 엔드포인트를 구독해 리포트 완료, 큐 갱신, 에이전트 상태 변경을
실시간으로 UI에 반영한다. 연결 끊김 시 지수 백오프 자동 재연결, 이벤트 중복 방지(lastEventId 전달)를 구현한다.

### 2. 백엔드 SSE 엔드포인트 설계 (Spring — SseEmitter 방식)

```java
// AutoReviewController 추가 엔드포인트
@GetMapping(value = "/events/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
public SseEmitter stream(@RequestHeader(value = "Last-Event-ID", required = false) String lastEventId) {
    SseEmitter emitter = new SseEmitter(30_000L);   // 30초 타임아웃, 클라이언트가 재연결
    reviewerSseRegistry.register(emitter);
    emitter.onCompletion(() -> reviewerSseRegistry.remove(emitter));
    emitter.onTimeout(emitter::complete);
    // lastEventId 있으면 missed 이벤트 replay (인메모리 circular buffer 100건)
    if (lastEventId != null) reviewerSseRegistry.replay(emitter, lastEventId);
    return emitter;
}
```

> **SseEmitter vs Flux**: 이 프로젝트는 Spring MVC 기반(`spring-boot-starter-web`)이므로 `SseEmitter` 사용.
> WebFlux 마이그레이션 없이 단순 멀티스레드 push가 가능하다.
> `ReviewerSseRegistry` 는 `CopyOnWriteArrayList<SseEmitter>` + circular buffer(LinkedList 100건)로 구현.
> 이벤트 발행: `AutoReviewEventListener.onApplicationEvent` → `reviewerSseRegistry.broadcast(event)`.

### 3. 프론트엔드 SSE 클라이언트

```typescript
// src/api/sseClient.ts
export function createSseConnection(
  onEvent: (payload: SsePayload) => void,
  onError?: () => void
): () => void {
  let es: EventSource;
  let retryDelay = 1000;

  function connect(lastId?: string) {
    const url = lastId
      ? `/api/ai/events/stream?lastEventId=${lastId}`
      : '/api/ai/events/stream';
    es = new EventSource(url, { withCredentials: true });

    es.onmessage = (e) => {
      retryDelay = 1000;
      const payload: SsePayload = JSON.parse(e.data);
      onEvent(payload);
    };
    es.onerror = () => {
      es.close();
      onError?.();
      setTimeout(() => connect(lastId), retryDelay);
      retryDelay = Math.min(retryDelay * 2, 30_000);
    };
  }

  connect();
  return () => es?.close();
}
```

### 4. Zustand 연동 hook

```typescript
// src/hooks/useSseSubscription.ts
export function useSseSubscription() {
  const updateQueue = useQueueStore((s) => s.updateFromSse);
  const setAgentStatus = useAdminStore((s) => s.setAgentStatus);

  useEffect(() => {
    const disconnect = createSseConnection((payload) => {
      if (payload.eventType === 'QUEUE_UPDATED') updateQueue(payload.data as QueueUpdatedData);
      if (payload.eventType === 'REPORT_READY') updateQueue(payload.data as QueueUpdatedData);
      if (payload.eventType === 'AGENT_STATUS_CHANGED') setAgentStatus(payload.data as AgentStatusResponse);
    });
    return disconnect;
  }, []);
}
```

### 5. UI 레이아웃

`SseStatusIndicator` 컴포넌트: TopBar 우상단에 원형 인디케이터.
- 연결됨: `bg-green-500 animate-pulse`
- 재연결 중: `bg-yellow-400 animate-ping`
- 오류: `bg-red-500`

### 6. 테스트 전략

| 테스트명 | 검증 내용 |
|---------|---------|
| `SSE 연결 시 onmessage 콜백 호출` | MockEventSource → payload 수신 확인 |
| `onerror 시 지수 백오프 재연결` | 3회 에러 → delay 1s→2s→4s 순서 확인 |
| `QUEUE_UPDATED 이벤트 → Zustand queueStore 갱신` | store 상태 변경 검증 |
| `REPORT_READY 이벤트 → revId 카드 reportStatus DONE 변경` | 컴포넌트 리렌더 확인 |

### 7. 단계별 커밋 테이블

| # | 작업 | 타입 | 커밋 메시지 |
|---|------|------|------------|
| E2-1 | 백엔드 `ReviewerSseRegistry` + `/events/stream` 엔드포인트 | feat | `feat(sse): E2 ReviewerSseRegistry·SSE 스트림 엔드포인트 추가` |
| E2-2 | 프론트 `sseClient.ts` + 재연결 로직 | feat | `feat(dashboard): E2 SSE 클라이언트·지수백오프 재연결` |
| E2-3 | `useSseSubscription` + Zustand 연동 | feat | `feat(dashboard): E2 useSseSubscription Zustand 연동` |
| E2-4 | `SseStatusIndicator` 컴포넌트 | feat | `feat(dashboard): E2 SseStatusIndicator 연결 상태 표시` |
| E2-5 | SSE 단위 테스트 | test | `test(dashboard): E2 SSE 재연결·이벤트 라우팅 테스트` |

### 8. 완료 기준

- `GET /api/ai/events/stream` 에 브라우저 연결 → 30초 무응답 후 자동 재연결
- 백엔드에서 `REPORT_READY` 이벤트 publish → 프론트 카드 리렌더 (3초 이내)
- 네트워크 오프라인 10초 후 복귀 → 자동 재연결 성공

---

## E3. 심사 대기 목록 화면 (Queue Board)

### 1. 목표

심사 대기 중인 신청 목록을 Track 뱃지, 리포트 생성 상태, disagreement 경고와 함께 표시하고,
SSE로 실시간 갱신한다. 심사원은 행 클릭 → 해당 Track 카드로 이동한다.

### 2. 파일/컴포넌트 구조

```typescript
// src/store/queueStore.ts
interface QueueItem {
  revId: number;
  applicantMasked: string;   // "김**" 형태 — 서버에서 마스킹 후 전달
  requestedAmountKw: number;
  track: Track | null;
  reportStatus: 'PENDING' | 'DONE' | 'FAILED';
  disagreement: boolean;
  createdAt: string;
  shadow: boolean;
}

interface QueueStore {
  items: QueueItem[];
  setItems: (items: QueueItem[]) => void;
  updateFromSse: (data: QueueUpdatedData) => void;
}
```

### 3. TanStack Query hook

```typescript
// src/hooks/useQueueBoard.ts
export function useQueueBoard() {
  return useQuery<QueueItem[]>({
    queryKey: ['queue-board'],
    queryFn: () => axiosInstance.get('/api/ai/admin/queue').then(r => r.data),
    staleTime: 30_000,
    refetchInterval: 60_000  // SSE 보조용 폴백 폴링
  });
}
```

### 4. 핵심 컴포넌트 설계

```typescript
// src/components/queue/QueueRow.tsx
interface QueueRowProps {
  item: QueueItem;
  onClick: (revId: number) => void;
}

// src/components/queue/TrackBadge.tsx
interface TrackBadgeProps {
  track: Track | null;
  shadow?: boolean;
}
// TRACK_1 → bg-green-100 text-green-800
// TRACK_2 → bg-red-100 text-red-800
// TRACK_3 → bg-orange-100 text-orange-800
// shadow=true → 접두 "SHADOW" + 점선 테두리 border-dashed
```

### 5. UI 레이아웃

```
┌─────────────────────────────────────────────────────────────────┐
│ 심사 대기 목록                          [새로고침] [SseIndicator] │
│ 전체 42건  TRACK_1 18  TRACK_2 9  TRACK_3 15  ⚠ 불일치 3건      │
├─────┬──────────┬──────────┬───────────┬──────────┬─────────────┤
│ 신청번호 │ 신청자 │ 금액(만원) │ 트랙     │ 리포트    │ 불일치     │
├─────┼──────────┼──────────┼───────────┼──────────┼─────────────┤
│ REV-001 │ 김** │ 3,000    │ [TRACK_1] │ ✓ 완료   │             │
│ REV-002 │ 이** │ 8,500    │ [TRACK_3] │ ⏳ 대기  │ ⚠          │
└─────┴──────────┴──────────┴───────────┴──────────┴─────────────┘
```

Tailwind: `table-auto w-full`, 행 hover `hover:bg-slate-50 cursor-pointer`,
PENDING 행 `animate-pulse bg-slate-50`.

### 6. 테스트 전략

| 테스트명 | 검증 내용 |
|---------|---------|
| `QueueBoard 초기 로딩 → 스켈레톤 표시` | LoadingSkeleton 렌더 확인 |
| `API 응답 후 QueueRow 목록 렌더` | 3건 fixture → 3개 tr 렌더 |
| `TRACK_1 뱃지 green 색상` | TrackBadge data-testid 확인 |
| `disagreement=true 행 경고 아이콘` | ⚠ 아이콘 렌더 |
| `SSE QUEUE_UPDATED → 신규 행 추가` | store update 후 DOM 변화 |
| `행 클릭 → /review/:revId 네비게이션` | navigate 호출 확인 |

### 7. 단계별 커밋 테이블

| # | 작업 | 타입 | 커밋 메시지 |
|---|------|------|------------|
| E3-1 | `queueStore` + `useQueueBoard` hook | feat | `feat(dashboard): E3 QueueStore·useQueueBoard 훅` |
| E3-2 | `TrackBadge` + `QueueRow` + `QueueBoard` 컴포넌트 | feat | `feat(dashboard): E3 QueueBoard 심사 대기 목록 화면` |
| E3-3 | QueueBoard 단위 테스트 | test | `test(dashboard): E3 QueueBoard 렌더·SSE 갱신 테스트` |

### 8. 완료 기준

- 42건 목록 50ms 이내 렌더
- SSE 이벤트 수신 → 1초 이내 목록 갱신 (리렌더)
- disagreement 행 강조 표시 접근성 검사 통과 (aria-label)

---

## E4. Track 1 심사 카드 (자동 승인 권고)

### 1. 목표

TRACK_1 신청에 대해 스코어 게이지, 정책 룰 통과 체크리스트, LLM 리포트 요약, Sign-off 버튼을 제공한다.
심사원의 목표 처리 시간은 5분/건.

### 2. 핵심 컴포넌트 설계

```typescript
// src/components/cards/Track1Card.tsx
interface Track1CardProps {
  revId: number;
  evaluate: AutoReviewEvaluateResponse;
  report: ReviewReport;
  opinion: AgentOpinion;
  onSignOff: (revId: number) => Promise<void>;
}

// src/components/cards/ScoreGauge.tsx
interface ScoreGaugeProps {
  label: string;
  value: number;            // 0~1
  threshold: number;
  warningZone?: [number, number];
  colorScheme: 'pd' | 'decision';
}
// pd 게이지: 낮을수록 좋음(초록), value > threshold → 빨강
// decision 게이지: 높을수록 좋음(초록)

// src/components/cards/PolicyChecklist.tsx
interface PolicyChecklistProps {
  hardFailCodes: string[];      // 비어있으면 모두 통과
  hardFailMessages: string[];
  policyFlags: string[];        // AgentOpinion.policy_flags (소프트 경고)
}
```

### 3. TanStack Query hook

```typescript
// src/hooks/useReviewDetail.ts
export function useReviewDetail(revId: number) {
  return useQuery<{
    evaluate: AutoReviewEvaluateResponse;
    report: ReviewReport;
    opinion: AgentOpinion;
  }>({
    queryKey: ['review-detail', revId],
    queryFn: () => axiosInstance.get(`/api/ai/auto-review/evaluate?revId=${revId}`).then(r => r.data),
    staleTime: 5 * 60_000
  });
}
```

### 4. UI 레이아웃

```
┌─────────────────────────────────────────────────────┐
│ TRACK_1  자동 승인 권고            [shadow 뱃지]      │
│ REV-001  김**  신청금액 3,000만원                    │
├──────────────────────┬──────────────────────────────┤
│ PD 스코어  0.032     │ Decision 스코어  0.891        │
│ [████████░░] 임계 0.07│ [████████████░] 임계 0.75   │
├──────────────────────┴──────────────────────────────┤
│ ✓ DSR 정책 통과                                      │
│ ✓ 신용점수 임계 초과                                  │
│ ✓ 모든 Hard Constraint 통과                          │
│ ⚠ DSR_THRESHOLD_WARNING (소프트 경고)                │
├─────────────────────────────────────────────────────┤
│ AI 리포트                                            │
│ "본 신청은 PD 0.032로 매트릭스 임계(0.07) 대비..."   │
│ 강점: [신용점수 우수] [안정적 소득]                   │
├─────────────────────────────────────────────────────┤
│                           [Sign-off 승인 확정]       │
└─────────────────────────────────────────────────────┘
```

Tailwind: 카드 `rounded-xl shadow-md p-6 bg-white`, 게이지 `w-full h-3 rounded-full bg-slate-200`,
Sign-off 버튼 `bg-green-600 hover:bg-green-700 text-white px-8 py-3 rounded-lg font-semibold`.

### 5. 테스트 전략

| 테스트명 | 검증 내용 |
|---------|---------|
| `Track1Card 렌더 — 정상 opinion` | ScoreGauge·PolicyChecklist 렌더 확인 |
| `disagreement=true → DisagreementAlert 표시` | Alert 컴포넌트 렌더 |
| `hardFailCodes 비어있으면 체크리스트 모두 ✓` | all-pass 렌더 |
| `fallback opinion → 경고 배너` | fallback 배너 aria-role="alert" |
| `Sign-off 클릭 → onSignOff 호출` | mock fn 호출 확인 |
| `Sign-off 중 버튼 disabled + 스피너` | loading 상태 확인 |

### 6. 단계별 커밋 테이블

| # | 작업 | 타입 | 커밋 메시지 |
|---|------|------|------------|
| E4-1 | `ScoreGauge` + `PolicyChecklist` + `CitationList` | feat | `feat(dashboard): E4 ScoreGauge·PolicyChecklist 공통 컴포넌트` |
| E4-2 | `Track1Card` 전체 + Sign-off 버튼 연동 | feat | `feat(dashboard): E4 Track1 심사 카드 Sign-off UI` |
| E4-3 | Track1Card 단위 테스트 | test | `test(dashboard): E4 Track1Card 렌더·Sign-off 테스트` |

### 7. 완료 기준

- PD/Decision 게이지 수치 정확도 ±0.001
- Sign-off 후 큐 목록에서 해당 항목 제거 확인
- shadow=true 건 별도 표시, 실제 결정 처리 불가 (버튼 disabled)

---

## E5. Track 2 심사 카드 (자동 반려 권고 + 거절 통보문 편집)

### 1. 목표

TRACK_2 신청에 대해 Hard Fail 사유 목록, LLM 생성 거절 통보문 초안(편집 가능), 정책 인용,
거절 확정 버튼을 제공한다. 심사원의 목표 처리 시간 3분/건.

### 2. 핵심 컴포넌트 설계

```typescript
// src/components/cards/RejectionDraftEditor.tsx
interface RejectionDraftEditorProps {
  initialDraft: string;          // ReviewReport.summary (Track 2: 통보문 초안 포함)
  citations: Citation[];
  onSubmit: (finalText: string) => Promise<void>;
  readOnly?: boolean;
}
// 내부 상태: localDraft (string) — initialDraft로 초기화
// 편집 시 charCount 표시 (min 100 / max 2000)
// 인용 클릭 → 해당 조항 툴팁 popover

// src/components/cards/Track2Card.tsx
interface Track2CardProps {
  revId: number;
  evaluate: AutoReviewEvaluateResponse;
  report: ReviewReport;
  opinion: AgentOpinion;
  onConfirmRejection: (revId: number, finalNotice: string) => Promise<void>;
}
```

### 3. UI 레이아웃

```
┌─────────────────────────────────────────────────────┐
│ TRACK_2  자동 반려 권고             [shadow 뱃지]     │
│ REV-007  이**  신청금액 8,500만원                    │
├─────────────────────────────────────────────────────┤
│ ■ Hard Fail 사유                                     │
│   ✗ DSR_EXCEEDED — DSR 71.3%, 기준 60% 초과          │
│   ✗ OVERDUE_HISTORY — 최근 12개월 연체 이력           │
├─────────────────────────────────────────────────────┤
│ AI 위험 요인 (weight 순)                             │
│   DSR 과다 [■■■■■■■░░░] 0.73                        │
│   연체 이력 [■■■■░░░░░░] 0.48                        │
├─────────────────────────────────────────────────────┤
│ 거절 통보문 초안 (편집 가능)                          │
│ ┌───────────────────────────────────────────────┐   │
│ │ 귀하의 대출 신청에 대해 심사한 결과…           │   │
│ │ (편집 가능 textarea)                           │   │
│ └───────────────────────────────────────────────┘   │
│ 인용 근거: [여신전문금융업법 §52] [내부정책 MORT_001] │
├─────────────────────────────────────────────────────┤
│                        [거절 확정 + 통보문 발송]     │
└─────────────────────────────────────────────────────┘
```

Tailwind: Hard Fail 항목 `bg-red-50 border-l-4 border-red-500 p-3`,
textarea `min-h-[160px] w-full border rounded-lg p-3 text-sm focus:ring-2 focus:ring-blue-500`,
거절 확정 버튼 `bg-red-600 hover:bg-red-700 text-white`.

### 4. 테스트 전략

| 테스트명 | 검증 내용 |
|---------|---------|
| `Track2Card Hard Fail 목록 렌더` | hardFailMessages 수 == 렌더된 li 수 |
| `통보문 textarea 초기값 == summary` | value 일치 확인 |
| `textarea 편집 → charCount 갱신` | 입력 후 count 변화 |
| `charCount < 100 → 제출 버튼 disabled` | 짧은 텍스트 입력 |
| `거절 확정 → onConfirmRejection(revId, finalText) 호출` | mock fn 인수 검증 |
| `shadow=true → 버튼 disabled + 안내 메시지` | shadow 처리 확인 |

### 5. 단계별 커밋 테이블

| # | 작업 | 타입 | 커밋 메시지 |
|---|------|------|------------|
| E5-1 | `RejectionDraftEditor` 컴포넌트 | feat | `feat(dashboard): E5 RejectionDraftEditor 통보문 편집기` |
| E5-2 | `Track2Card` + Hard Fail 목록 + 인용 popover | feat | `feat(dashboard): E5 Track2 심사 카드 반려 권고 UI` |
| E5-3 | Track2Card 단위 테스트 | test | `test(dashboard): E5 Track2Card 편집·거절확정 테스트` |

### 6. 완료 기준

- 심사원이 통보문을 편집 후 거절 확정 → 백엔드 저장 확인
- 통보문 길이 유효성 검사 (100~2000자) 동작
- 인용 조항 툴팁 접근성 (aria-describedby) 통과

---

## E6. Track 3 심사 카드 (SHAP 차트 + 시뮬레이션 + 의견 작성)

### 1. 목표

TRACK_3 신청에 대해 이중 스코어 대시, SHAP top-5 기여 요인 바 차트,
What-if 시뮬레이션 시나리오 카드, 3단락 LLM 리포트 편집, 최종 심사원 의견 입력 폼을 제공한다.

### 2. 핵심 컴포넌트 설계

```typescript
// src/components/cards/ShapBarChart.tsx
interface ShapBarChartProps {
  features: Array<{
    name: string;
    shapValue: number;  // 양수: 승인 방향 기여, 음수: 반려 기여
    baselineValue: number;
  }>;
  // Recharts HorizontalBarChart 사용
}

// src/components/cards/SimulationCardList.tsx
interface SimulationCardListProps {
  simulations: SimulationResult[];
}
// 각 카드: scenario 이름, 금액/기간 변경값, 결과 아이콘
// risk_reduced → 초록 화살표 ↓, no_change → 회색, risk_increased → 빨강 ↑
// still_violates=true → "⚠ Hard Fail 유지" 경고

// src/components/cards/AgentOpinionSummary.tsx
interface AgentOpinionSummaryProps {
  opinion: AgentOpinion;
}
// risk_level 뱃지: LOW→초록, MEDIUM→노랑, HIGH→빨강
// fallback_reason 있으면 경고 배너 전체 표시

// src/components/cards/Track3Card.tsx
interface Track3CardProps {
  revId: number;
  evaluate: AutoReviewEvaluateResponse;
  report: ReviewReport;
  opinion: AgentOpinion;
  onSubmitDecision: (revId: number, decision: ReviewerDecision) => Promise<void>;
}

interface ReviewerDecision {
  finalDecision: 'APPROVE' | 'REJECT' | 'REQUEST_MORE_INFO';
  reviewerComment: string;   // min 50자 필수
  editedSummary?: string;    // 심사원이 리포트 수정한 경우
}
```

### 3. 재평가 hook

```typescript
// src/hooks/useReEvaluate.ts
export function useReEvaluate() {
  const queryClient = useQueryClient();
  return useMutation<void, Error, number>({
    mutationFn: (revId) =>
      axiosInstance.post(`/api/ai/admin/re-evaluate/${revId}`),
    onSuccess: (_, revId) => {
      queryClient.invalidateQueries({ queryKey: ['review-detail', revId] });
    }
  });
}
```

### 4. UI 레이아웃

```
┌─────────────────────────────────────────────────────┐
│ TRACK_3  사람 심사 필수    [HIGH 뱃지] [⚠ 불일치]   │
├──────────────────┬──────────────────────────────────┤
│ PD 0.089 (임계±) │ Decision 0.641 (safety zone)     │
│ [게이지]          │ [게이지]                          │
├──────────────────┴──────────────────────────────────┤
│ SHAP 위험 기여 요인 (top 5)                          │
│ dsr          ████████████░  +0.31                   │
│ credit_score ████████░░░░░  +0.22                   │
│ purpose      ██░░░░░░░░░░░  -0.09 (완화 요인)       │
├─────────────────────────────────────────────────────┤
│ What-if 시뮬레이션                                   │
│ [대출금 20%↓] risk_reduced  / [대출금 40%↓] ...    │
│ [기간 6개월↑] no_change     / [결합 시나리오] ...   │
├─────────────────────────────────────────────────────┤
│ AI 리포트 (3단락, 편집 가능)                         │
│ [위험 단락] / [강점 단락] / [권고 단락]              │
├─────────────────────────────────────────────────────┤
│ AgentOpinion 요약   [HIGH] fallback: 없음            │
│ "PD가 안전 마진 경계에..."                           │
├─────────────────────────────────────────────────────┤
│ 심사원 최종 의견                                     │
│ [APPROVE] [REJECT] [추가 정보 요청]                  │
│ ┌─────────────────────────────────────────────────┐ │
│ │ 심사 의견 입력 (최소 50자)                      │ │
│ └─────────────────────────────────────────────────┘ │
│                         [최종 결정 제출]             │
└─────────────────────────────────────────────────────┘
```

Tailwind: SHAP 바 `h-4 rounded bg-gradient-to-r from-blue-400 to-blue-600`,
시뮬레이션 카드 `flex gap-3 flex-wrap`, 각 카드 `border rounded-lg p-4 w-48`.

### 5. 테스트 전략

| 테스트명 | 검증 내용 |
|---------|---------|
| `ShapBarChart top-5 피처 렌더` | 5개 바 렌더, SHAP 값 표시 |
| `SimulationCardList risk_reduced → 초록 아이콘` | 색상 클래스 확인 |
| `still_violates → 경고 표시` | 경고 텍스트 렌더 |
| `AgentOpinionSummary HIGH 뱃지 렌더` | badge text 확인 |
| `fallback opinion → 전체 경고 배너` | role="alert" 확인 |
| `reviewerComment 50자 미만 → 제출 버튼 disabled` | validation 확인 |
| `최종 결정 제출 → onSubmitDecision 호출` | mock fn 인수 검증 |
| `재평가 버튼 클릭 → PENDING 상태 진입` | mutation 확인 |

### 6. 단계별 커밋 테이블

| # | 작업 | 타입 | 커밋 메시지 |
|---|------|------|------------|
| E6-1 | `ShapBarChart` (Recharts) + `SimulationCardList` | feat | `feat(dashboard): E6 ShapBarChart·SimulationCardList 컴포넌트` |
| E6-2 | `AgentOpinionSummary` + `Track3Card` 전체 | feat | `feat(dashboard): E6 Track3 심사 카드 SHAP·시뮬레이션·의견 UI` |
| E6-3 | `useReEvaluate` mutation hook | feat | `feat(dashboard): E6 useReEvaluate 재평가 mutation 훅` |
| E6-4 | Track3Card 단위 테스트 | test | `test(dashboard): E6 Track3Card SHAP·시뮬레이션·의견 테스트` |

### 7. 완료 기준

- SHAP 바 차트 수치 ±0.001 정확도
- 의견 미입력 시 제출 불가 (UX 검사 통과)
- 재평가 요청 후 SSE `REPORT_READY` 수신 → 카드 자동 갱신

---

## E7. 관리자 패널 (Shadow Mode, RPD/RPM, 공정성 리포트)

### 1. 목표

운영자가 코드 배포 없이 Shadow Mode 토글, RPD/RPM 잔량 모니터링, Fallback 분포 확인,
공정성·PSI 리포트 조회를 수행하는 전용 페이지를 제공한다.
접근은 `ROLE_ADMIN` 로 제한한다.

### 2. 핵심 컴포넌트 설계

```typescript
// src/components/admin/ShadowModeToggle.tsx
interface ShadowModeToggleProps {
  enabled: boolean;
  onToggle: (nextValue: boolean) => Promise<void>;
  isLoading: boolean;
}
// 확인 다이얼로그: "Shadow Mode를 OFF로 전환하면 자동 결정이 실제로 적용됩니다."
// shadcn AlertDialog 사용

// src/components/admin/RpmGauge.tsx
interface RpmGaugeProps {
  type: 'RPD' | 'RPM';
  remaining: number;
  total: number;
}
// remaining/total 비율로 색상: >50% 초록, 20~50% 노랑, <20% 빨강

// src/components/admin/FallbackPieChart.tsx
interface FallbackPieChartProps {
  distribution: Record<string, number>;
}
// Recharts PieChart, 색상 맵: LLM_RATE_LIMITED 빨강, TIMEOUT 주황 등

// src/components/admin/FairnessTable.tsx
interface FairnessTableProps {
  data: FairnessReportResponse;
}
// fourFifthsPass=false 행 → bg-red-50 강조
```

### 3. TanStack Query hooks

```typescript
// src/hooks/useAgentStatus.ts
export function useAgentStatus() {
  return useQuery<AgentStatusResponse>({
    queryKey: ['agent-status'],
    queryFn: () => axiosInstance.get('/api/ai/admin/agent-status').then(r => r.data),
    refetchInterval: 30_000
  });
}

// src/hooks/useShadowToggle.ts
export function useShadowToggle() {
  const queryClient = useQueryClient();
  return useMutation<void, Error, boolean>({
    mutationFn: (enabled) =>
      axiosInstance.post('/api/ai/admin/toggle-shadow', { enabled }),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['agent-status'] });
    }
  });
}

// src/hooks/useFairnessReport.ts
export function useFairnessReport(week: string) {
  return useQuery<FairnessReportResponse>({
    queryKey: ['fairness-report', week],
    queryFn: () => axiosInstance.get(`/api/ai/admin/fairness-report?week=${week}`).then(r => r.data),
    enabled: !!week
  });
}

// src/hooks/usePsiReport.ts
export function usePsiReport() {
  return useQuery<PsiReportResponse>({
    queryKey: ['psi-report'],
    queryFn: () => axiosInstance.get('/api/ai/admin/psi-report').then(r => r.data)
  });
}
```

### 4. UI 레이아웃

```
┌─────────────────────────────────────────────────────────────────┐
│ 관리자 패널                              [ROLE_ADMIN 전용]       │
├───────────────────────┬─────────────────────────────────────────┤
│ Shadow Mode           │ 에이전트 리소스 잔량                    │
│ [●─────] OFF          │ RPD [████████░░] 1,247 / 1,500          │
│ 일치율 (이번 달)       │ RPM [██████████] 13 / 15               │
│ TRACK_1: 94.2%        │                                         │
│ TRACK_2: 87.6%        │ Fallback 분포 (최근 24h)               │
│ TRACK_3: 81.3%        │ [파이 차트]                             │
├───────────────────────┴─────────────────────────────────────────┤
│ 공정성 리포트 (주간 4/5ths Rule)   [주차 선택: 2026-W23]        │
│ 속성      값   TRACK_1 진입률  TRACK_2 진입률  4/5ths           │
│ 성별      남성  43.2%          21.1%          ✓                │
│ 성별      여성  38.9%  ←(0.90) 24.3%          ✓                │
│ 연령대    20대  29.1%          28.7%          ⚠ 실패            │
├─────────────────────────────────────────────────────────────────┤
│ PSI Drift 현황 (오늘)                                           │
│ decision_score  PSI 0.043  [정상]                               │
│ dsr             PSI 0.218  [⚠ 경고 — 0.2 초과]                 │
└─────────────────────────────────────────────────────────────────┘
```

### 5. 테스트 전략

| 테스트명 | 검증 내용 |
|---------|---------|
| `ShadowModeToggle ON→OFF 확인 다이얼로그 표시` | AlertDialog 렌더 |
| `확인 클릭 → onToggle(false) 호출` | mock fn 확인 |
| `RpmGauge < 20% → 빨강 색상` | 클래스 확인 |
| `FairnessTable 4/5ths 실패 행 bg-red-50` | 행 배경 클래스 |
| `PSI > 0.2 → 경고 아이콘 표시` | isAlert 렌더 |
| `useFairnessReport week 변경 → 재쿼리` | queryKey 변화 |

### 6. 단계별 커밋 테이블

| # | 작업 | 타입 | 커밋 메시지 |
|---|------|------|------------|
| E7-1 | `ShadowModeToggle` + `useShadowToggle` | feat | `feat(dashboard): E7 ShadowModeToggle 운영 토글 패널` |
| E7-2 | `RpmGauge` + `FallbackPieChart` + `useAgentStatus` | feat | `feat(dashboard): E7 RPD·RPM 잔량·Fallback 분포 패널` |
| E7-3 | `FairnessTable` + `PsiDriftTable` + hooks | feat | `feat(dashboard): E7 공정성·PSI Drift 리포트 패널` |
| E7-4 | 관리자 패널 단위 테스트 | test | `test(dashboard): E7 관리자 패널 토글·리포트 테스트` |

### 7. 완료 기준

- Shadow Mode 토글 → 백엔드 상태 변경 확인 후 UI 반영
- 4/5ths Rule 실패 항목 강조 표시
- PSI 경고 항목 즉각 식별 가능 (색상 + 아이콘)

---

## E8. 접근성 + 보안 (PII 마스킹, RBAC, 심사원 권한)

### 1. 목표

개인정보보호법·신용정보법 준수를 위해 PII 화면 표시 규칙을 일원화하고,
심사원(ROLE_REVIEWER)과 관리자(ROLE_ADMIN) 권한을 프론트에서 강제한다.

### 2. PII 마스킹 표시 규칙

```typescript
// src/components/shared/PiiMaskedText.tsx
interface PiiMaskedTextProps {
  value: string;          // 원본 (서버에서 이미 마스킹된 값 — "김**", "010-****-5678")
  label: string;          // 스크린리더용 aria-label
  revealable?: boolean;   // ROLE_ADMIN 만 true 가능
  onReveal?: () => void;
}
```

**마스킹 정책 (서버 + 클라이언트 이중 적용)**:
- 이름: 첫 글자만 노출 `홍**(3자)` / `홍*(2자)`
- 전화번호: `010-****-5678`
- 주민등록번호: 전체 마스킹, 절대 프론트 전달 금지
- 신청금액, 스코어: 마스킹 없음 (비PII)

> 백엔드 `PiiMaskingFilter`(기구현)가 SSE 페이로드 포함 모든 응답에 적용됨을 전제.
> 프론트는 `PiiMaskedText` 컴포넌트 이외의 방법으로 이름·연락처 직접 렌더 금지.

### 3. RBAC 설계

```typescript
// src/store/authStore.ts
interface AuthState {
  token: string | null;
  roles: Array<'ROLE_REVIEWER' | 'ROLE_ADMIN' | 'ROLE_COMPLIANCE'>;
  reviewerId: string;
  setAuth: (token: string, roles: string[]) => void;
  clearAuth: () => void;
}

// src/router.tsx — 보호 라우트
function ProtectedRoute({ roles, children }: { roles: string[]; children: ReactNode }) {
  const userRoles = useAuthStore((s) => s.roles);
  const hasAccess = roles.some(r => userRoles.includes(r as any));
  if (!hasAccess) return <Navigate to="/unauthorized" replace />;
  return <>{children}</>;
}

// 라우트 규칙
// /queue                  → ROLE_REVIEWER, ROLE_ADMIN
// /review/:revId          → ROLE_REVIEWER, ROLE_ADMIN
// /admin                  → ROLE_ADMIN only
// /compliance/:revId      → ROLE_COMPLIANCE, ROLE_ADMIN
```

### 4. ErrorBoundary + LoadingSkeleton 패턴

```typescript
// src/components/shared/ErrorBoundary.tsx
// class 컴포넌트 — React 18 기준
// hasError=true 시: "데이터를 불러오는 중 오류가 발생했습니다." + 재시도 버튼
// TanStack Query ErrorBoundary 연동: throwOnError: true 설정 시 catch

// src/components/shared/LoadingSkeleton.tsx
interface LoadingSkeletonProps {
  variant: 'card' | 'row' | 'gauge' | 'chart';
  rows?: number;
}
// Tailwind animate-pulse + bg-slate-200 rounded 블록
// card 변형: 헤더 + 2개 게이지 + 리스트 스켈레톤
```

### 5. 보안 추가 요건

| 항목 | 구현 방법 |
|------|---------|
| JWT 토큰 저장 | `sessionStorage` (localStorage 금지 — XSS 최소화) |
| CSRF | Axios 인터셉터에 `X-Requested-With: XMLHttpRequest` 헤더 자동 추가 |
| CSP 헤더 | Vite plugin-ssr 또는 Nginx conf `Content-Security-Policy: default-src 'self'` |
| 세션 만료 | 401 응답 → 자동 로그아웃 + 로그인 페이지 리다이렉트 |
| 감사 로그 | 심사원 Sign-off/거절확정/의견제출 액션 → 백엔드 `POST /api/ai/admin/reviewer-action-log` |
| Shadow 결정 차단 | `shadow=true` 건에 대해 프론트 CTA 버튼 disabled + aria-disabled |

### 6. 테스트 전략

| 테스트명 | 검증 내용 |
|---------|---------|
| `ROLE_REVIEWER → /admin 접근 → /unauthorized 리다이렉트` | Navigate 호출 확인 |
| `PiiMaskedText revealable=false → 마스킹 유지` | DOM 텍스트 확인 |
| `ErrorBoundary 하위 에러 → 에러 UI 렌더` | hasError fallback 렌더 |
| `LoadingSkeleton card variant → animate-pulse 클래스` | 클래스 확인 |
| `401 응답 → authStore.clearAuth 호출` | Axios 인터셉터 테스트 |
| `shadow=true 카드 → Sign-off 버튼 disabled` | button[disabled] 확인 |

### 7. 단계별 커밋 테이블

| # | 작업 | 타입 | 커밋 메시지 |
|---|------|------|------------|
| E8-1 | `authStore` + `ProtectedRoute` + RBAC 라우팅 | feat | `feat(dashboard): E8 RBAC ProtectedRoute 접근 제어` |
| E8-2 | `PiiMaskedText` + 전체 컴포넌트 PII 적용 | feat | `feat(dashboard): E8 PiiMaskedText PII 마스킹 컴포넌트` |
| E8-3 | `ErrorBoundary` + `LoadingSkeleton` + Axios 인터셉터 | feat | `feat(dashboard): E8 ErrorBoundary·LoadingSkeleton·보안 인터셉터` |
| E8-4 | 접근제어·PII·에러 경계 단위 테스트 | test | `test(dashboard): E8 RBAC·PII·ErrorBoundary 테스트` |

### 8. 완료 기준

- ROLE_REVIEWER 계정으로 `/admin` 직접 URL 입력 → `/unauthorized` 리다이렉트
- 모든 카드에서 이름·연락처 `PiiMaskedText` 통해서만 노출 (코드 리뷰 체크리스트)
- axe-core 접근성 검사 0 critical violation
- shadow 건 CTA 비활성화 E2E 확인

---

## 전체 Phase E 일정 요약

| 서브 항목 | 예상 기간 | 선행 조건 |
|---------|---------|---------|
| E1 프로젝트 설정 | 1일 | — |
| E2 SSE 연동 | 3일 | B5 Admin Endpoint |
| E3 Queue Board | 3일 | E1, E2 |
| E4 Track 1 카드 | 3일 | E3 |
| E5 Track 2 카드 | 3일 | E3 |
| E6 Track 3 카드 | 5일 | E3, C3 SHAP 통합 |
| E7 관리자 패널 | 3일 | B3 Shadow, B4 공정성 |
| E8 접근성 + 보안 | 2일 | E1~E7 전체 |
| **합계** | **~23 영업일 (~5주)** | |

---

## MVP 완성 기준

- [ ] `pnpm test` 전체 0 fail (단위 + 통합)
- [ ] axe-core 접근성 0 critical
- [ ] Shadow Mode=ON 상태에서 모든 CTA 비활성화
- [ ] PII 마스킹 코드 리뷰 체크리스트 통과
- [ ] SSE 재연결 시나리오 (30s 타임아웃) 동작 확인
- [ ] ROLE_REVIEWER / ROLE_ADMIN 분리 RBAC 동작 확인
- [ ] Track 1·2·3 카드 E2E: MSW mock → 각 CTA 클릭 → 성공 응답

---

> 참조: `next-phase-roadmap.md §Phase E`, `pre-review-agent-plan.md §출력`, `banking-review-llm.md §5·§9`
