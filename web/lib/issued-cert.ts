/**
 * 이 브라우저에 발급된 인증서 읽기.
 *
 * **왜 필요한가.** 로그인 화면과 챗봇이 인증서 일련번호를 **상수로 박아** 두고
 * 있었다. `FINCERT-TEST-2024-000001`·`COMMON-TEST-2024-000001` 이다.
 *
 * 이 상수들은 전부 **고객 9001 의 인증서인데, 9001 은 직원(지점장)이다.**
 * 그래서 인증서 로그인은 성공하지만 그 토큰에는 `ROLE_CUSTOMER` 가 없고,
 * 게이트웨이의 `CUSTOMER_ONLY_PATHS` 가드가 `/api/v1/customers/me` 를 403 으로
 * 막는다. 결과는 **"로그인은 됐는데 마이페이지가 안 뜨는"** 상태였다.
 *
 * 상수를 고객 인증서로 바꾸는 것은 증상만 옮긴다 — 여전히 아무 방문자나 PIN
 * 여섯 자리만 맞히면 **남의 계정**으로 들어온다.
 *
 * **읽을 곳은 이미 있었다.** 발급 화면(`/cert/fin-cert-issue`,
 * `/cert/joint-cert-issue`)이 진짜 발급 API 를 부르고 그 결과를 여기 키에
 * 저장한다. joint 쪽에는 "발급된 인증서를 localStorage에 저장해 로그인 모달에서
 * 사용" 이라는 주석까지 달려 있다. 쓰기만 하고 읽지를 않았다.
 *
 * **보지 않는 것.** 여기 저장된 값은 편의를 위한 것이지 자격 증명이 아니다.
 * 일련번호가 실재하는지, 소유자가 맞는지, PIN 이 맞는지는 전부
 * customer-service 가 검증한다. 브라우저가 값을 지어내도 로그인은 실패한다.
 */

export type IssuedCertKind = 'CERT_FIN' | 'CERT_COMMON'

export type IssuedCert = {
  serialNumber: string
  certType: string
  user?: string
  expiry?: string
  issuer?: string
}

const STORAGE_KEY: Record<IssuedCertKind, string> = {
  CERT_FIN: 'issuedFinCert',
  CERT_COMMON: 'issuedJointCert',
}

/** 발급 화면 경로. 인증서가 없을 때 안내할 곳. */
export const CERT_ISSUE_PATH: Record<IssuedCertKind, string> = {
  CERT_FIN: '/cert/fin-cert-issue',
  CERT_COMMON: '/cert/joint-cert-issue',
}

const CERT_LABEL: Record<IssuedCertKind, string> = {
  CERT_FIN: '금융인증서',
  CERT_COMMON: '공동인증서',
}

/** 발급된 인증서가 없을 때 보여줄 문구. */
export function noCertMessage(kind: IssuedCertKind): string {
  return `이 브라우저에 발급된 ${CERT_LABEL[kind]}가 없습니다.\n${CERT_LABEL[kind]} 발급 후 이용해 주세요.`
}

/** 이 브라우저에 발급된 인증서. 없거나 깨졌으면 `null`. */
export function readIssuedCert(kind: IssuedCertKind): IssuedCert | null {
  if (typeof window === 'undefined') return null
  try {
    const raw = localStorage.getItem(STORAGE_KEY[kind])
    if (!raw) return null
    const parsed = JSON.parse(raw)
    if (!parsed?.serialNumber) return null
    return {
      serialNumber: String(parsed.serialNumber),
      certType: String(parsed.certType ?? kind),
      user: parsed.user,
      expiry: parsed.expiry,
      issuer: parsed.issuer,
    }
  } catch {
    return null
  }
}

/** 인증서를 지웠을 때. 남겨 두면 목록에서 지워도 다시 진입할 때 되살아난다. */
export function forgetIssuedCert(kind: IssuedCertKind): void {
  if (typeof window === 'undefined') return
  try { localStorage.removeItem(STORAGE_KEY[kind]) } catch {}
}
