/**
 * 고객 PII 마스킹 유틸.
 *
 * admin 고객목록 등 화면 컴포넌트에 인라인돼 있던 함수를, 테스트(회귀 방지) 가능하도록
 * 독립 모듈로 분리했다. (테스트 하네스 도입 — 순수 함수부터)
 */

/** 전화번호 가운데 4자리를 마스킹. 예: `010-1234-5678` → `010-****-5678`. 하이픈 유무 무관. null → `-`. */
export const maskPhone = (phone: string | null): string =>
  phone ? phone.replace(/^(\d{2,3})-?\d{3,4}-?(\d{4})$/, '$1-****-$2') : '-'

/** 이메일 로컬파트를 첫 글자만 남기고 마스킹. 예: `hong@axful.com` → `h****@axful.com`. null → `-`. */
export const maskEmail = (email: string | null): string =>
  email ? email.replace(/^(.).*(@.*)$/, '$1****$2') : '-'
