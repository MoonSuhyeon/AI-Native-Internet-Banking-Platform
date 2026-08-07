/**
 * 백엔드 스키마 타입의 진입점.
 *
 * `*.d.ts` 는 openapi-typescript 가 각 서비스의 /v3/api-docs 에서 생성한다
 * (web/scripts/generate-api-types.sh). 직접 고치지 않는다 — 다음 생성에서 지워진다.
 *
 * **왜 생성물을 쓰는가.** 손으로 쓴 응답 인터페이스는 백엔드와 어긋나도 아무도 모른다.
 * 상환 스케줄 화면이 전 필드가 어긋난 채 3개월 넘게 방치된 것이 그래서였다.
 * 원본을 백엔드 코드로 두면 그 어긋남이 생길 수 없다.
 *
 * 쓰는 법:
 *   import type { Loan } from '@/lib/generated'
 *   type Schedule = Loan['RepaymentScheduleResponse']
 *
 * 스키마 이름은 백엔드 DTO 클래스 이름 그대로다(RepaymentScheduleResponse 등).
 */
import type { components as CustomerComponents } from './customer-service'
import type { components as CoreBankingComponents } from './core-banking'
import type { components as LoanComponents } from './loan-service'

/**
 * 생성 타입의 optional 을 걷어낸다.
 *
 * **왜 필요한가.** springdoc 은 자바 record 의 nullability 를 알 수 없어 모든 필드를
 * optional(`field?`)로 낸다. 그대로 쓰면 화면마다 `s.scheduledPrincipal!` 같은 단언이
 * 번지고, 정작 중요한 "이 필드가 오긴 하는가"는 여전히 검증되지 않는다.
 *
 * 그래서 여기서 "온다"고 못박고, 실제로 오는지는 계약 E2E 가 실제 백엔드로 확인한다
 * (web/e2e/*.contract.spec.ts). 타입은 가정을 적는 자리, 테스트는 그 가정을 검증하는 자리다.
 *
 * 정말 null 이 올 수 있는 필드가 있다면 그 화면에서 좁혀 쓴다 — 전역으로 optional 을
 * 되살리면 다시 단언 범벅이 된다.
 */
type DeepRequired<T> = T extends (infer U)[]
  ? DeepRequired<U>[]
  : T extends object
    ? { [K in keyof T]-?: DeepRequired<T[K]> }
    : T

type Schemas<T> = { [K in keyof T]: DeepRequired<T[K]> }

export type Customer = Schemas<CustomerComponents['schemas']>
export type CoreBanking = Schemas<CoreBankingComponents['schemas']>
export type Loan = Schemas<LoanComponents['schemas']>
