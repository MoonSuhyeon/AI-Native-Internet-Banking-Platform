# AI Agents Under Banking Internal Controls

*Team project · 5 people*

A bank is a two-sided market: **depositors** on one side, **borrowers** on the
other. Between them sits **risk** — will this borrower repay, is this transfer
fraud, did the money actually settle.

AI agents can do that risk work. But **an agent making risk decisions is itself
a risk** — wrong in ways nobody notices, acting on an identity nobody verified,
leaving no evidence of why.

So each agent runs under the same internal controls a bank applies to a person:
verified identity, segregation of duties, four-eyes approval, an audit trail
that cannot be rewritten, decisions that reproduce.

**That constraint is the project.**

---

## What we built

```
                    ┌──────────────────────────┐
                    │        CHANNELS          │
                    │ Customer │ Employee │ AI │
                    │   Web    │  Admin   │Bot │
                    └────────────┬─────────────┘
                                 │
                                 ▼
                    ┌──────────────────────────┐
                    │       API GATEWAY        │
                    │  JWT · Identity · RBAC   │
                    │  Routing · CORS · Guard  │
                    └────────────┬─────────────┘
                                 │
              ┌──────────────────┴──────────────────┐
              ▼                                     ▼
    ┌──────────────────┐              ┌──────────────────────────┐
    │  CUSTOMER / AUTH │◀─── look up ─│      CORE BANKING        │
    │                  │   (limits,   │                          │
    │  CIF · Party     │    holder)   │ Deposit · Payment · Loan │
    │  Auth · Role     │──────────────▶│                          │
    │  Audit           │              │ ├ Double-entry Ledger    │
    └──────────────────┘              │ ├ Outbox / Saga          │
                                      │ └ Orchestration          │
                                      └────────────┬─────────────┘
                                                   │
                        ┌──────────────────────────┼───────────────────┐
                        ▼                          ▼                   ▼
              ┌──────────────────┐      ┌──────────────────┐  ┌───────────────┐
              │       FDS        │      │      KAFKA       │  │ AI / DECISION │
              │                  │      │                  │  │               │
              │ PreCheck Gate    │      │  Events / MQ     │  │ Loan Review   │
              │ Detector         │      └────────┬─────────┘  │ Review AI     │
              │ Fraud Agent      │               │            │ Document Agent│
              │ HITL             │               │            │ Consultation  │
              └──────────────────┘               │            └───────────────┘
                                                 │
                        ┌────────────────────────┼────────────────────────┐
                        ▼                        ▼                        ▼
              ┌──────────────────┐    ┌──────────────────┐    ┌──────────────────┐
              │ EXTERNAL SYSTEMS │    │  FDS Detector    │    │      SPARK       │
              │                  │    │                  │    │                  │
              │ KFTC · BOK       │    │ post-hoc         │    │ windowed         │
              │ Clearing/Settle  │    │ detection        │    │ aggregation      │
              │ Reconciliation   │    └──────────────────┘    └──────────────────┘
              └──────────────────┘

              ┌──────────────────────────────────────────────────┐
              │                    DATA                          │
              │  PostgreSQL 16 · pgvector · Redis 7              │
              └──────────────────────────────────────────────────┘

              ┌──────────────────────────────────────────────────┐
              │                OBSERVABILITY                     │
              │  Prometheus · Grafana · Loki · Alertmanager      │
              │  Langfuse · Phoenix (LLM / agent tracing)        │
              └──────────────────────────────────────────────────┘
```

---

## My role

**My assigned scope** was the frontend, the customer domain, authentication
and security, and the fraud investigation agent.

| | |
|---|---|
| Customer / auth | Customer, party and contract model · login and JWT issuance · certificate auth · step-up approval tokens for fund movement · `BankRole` · access audit logging · per-customer transfer limits |
| Fraud investigation agent | Bounded investigation loop · HITL approval gating · gateway-verified identity for approvals |
| Frontend | Customer and admin screens |

**Beyond that scope** — this repo is my fork and I kept working after the team
phase. These touch other people's domains: refactors of their work, not
original ownership.

| | |
|---|---|
| Payment ledger | Made the double-entry check actually verify — it re-reads persisted rows instead of comparing a variable with itself. Built reconciliation against KFTC/BOK clearing records |
| Fraud detection | Added the inline pre-check so a transfer can be blocked *before* the money moves; detection had been after the fact only |
| Agent platform | Split `harness-core` out so agents share one contract for tracing, audit and retry |
| Service boundaries | deposit + payment → `core-banking` (one transaction instead of a two-database saga) · advisory → `loan-service` (it had never been in the build) |
| Security wiring | All traffic through the gateway · sidecar host ports closed · identity moved out of request bodies across 24 endpoints |
| Conventions | Amounts to `BIGINT`, timestamps to `TIMESTAMPTZ`, `*_yn CHAR(1)` to `BOOLEAN` — 52 columns across 4 schemas |

*Sections below describe the whole system, not only my part.*

---

## How the controls work

| Control | Mechanism |
|---|---|
| **Verified identity** | Gateway strips client headers, injects claims from a verified JWT. No service reads identity from a request body. Sidecars publish no host ports |
| **Agents propose, humans dispose** | Investigation agent stops at a recommendation. Payment freeze and STR need human approval + RBAC. Contract signing blocks on an unacknowledged CRITICAL advisory (four-eyes) |
| **LLM does not decide** | ML gives probability of default, LLM gives signals — a rule engine and policy matrix decide. Same input, same track. Policy is retrieved (BM25 + vector), not recalled |
| **Bounded autonomy** | The loop ends on a decisive fact, on confidence, or on budget. It cannot spin |
| **Self-proving ledger** | Double-entry checked by re-reading persisted rows. Daily reconciliation against KFTC/BOK clearing |
| **Fraud in three time bands** | Inline (before the money moves) · post-hoc (Kafka) · windowed (Spark) |

---

## How we verified it

A check that cannot fail is worse than no check — it reads as protection.
So every guard was verified by breaking it.

| Broken on purpose | Tests that failed |
|---|---|
| Ledger check compares a variable with itself | 6 |
| Remove the daily cumulative sum from transfer limits | 6 |
| Remove the identity guard from the consultation service | 5 |
| Delete a gateway route | 3 |

Most of what broke was never in a `.java` file. Six tests read deployment
config and fail the build:

`SidecarExposureTest` · `ComposeNetworkReachabilityTest` ·
`FrontendGatewayOnlyTest` · `ScrapeTargetConsistencyTest` ·
`DockerBuildContextTest` · `InternalRouteConfigTest`

Ledger and reconciliation run against real PostgreSQL (Testcontainers).
Network isolation was confirmed by booting the stack.

> The recurring failure mode was **silence** — code compiled, screens worked,
> tests passed, and the thing did nothing.

---

## Why it is built this way

| Decision | Reason |
|---|---|
| [deposit + payment → one service](docs/decisions/core-banking-merge.md) | A transfer is one fact. Split, it needed a saga whose compensation could itself fail |
| [Security zones](docs/decisions/security-zone-topology.md) | Gateway unification applies to north-south traffic only. East-west needs different controls |
| [Shared agent harness](docs/decisions/agent-harness-consolidation.md) | An agent's evidence should not depend on who wrote it |

---

## Stack

| | |
|---|---|
| Backend | Java 17 · Spring Boot 3.3.5 · Gradle multi-module |
| Agents | Python 3.11 · FastAPI · LangGraph |
| Frontend | Next.js · TypeScript |
| Data | PostgreSQL 16 (pgvector) · Redis 7 · Kafka 3.8 (KRaft) |
| Runtime | Docker Compose |

## Run locally

```bash
./gradlew build
docker compose up -d

cd web && npm run dev
```

```bash
docker compose --profile doc up -d    # document agent (MinIO, Vault)
docker compose --profile rag up -d    # Elasticsearch, Kibana
```

## Operating it

Prometheus · Grafana · Loki for metrics and logs; Langfuse · Phoenix for
agent traces.

The metric that matters most is **adoption rate** — how often a human accepts
what the agent recommended. An agent that is always overridden is not working,
and without this you cannot tell.

---

## Docs

| | |
|---|---|
| [docs/README.md](docs/README.md) | Documentation index |
| [docs/OPEN_ITEMS.md](docs/OPEN_ITEMS.md) | What is not done yet |
| [docs/decisions/](docs/decisions/) | Why things are built this way |
