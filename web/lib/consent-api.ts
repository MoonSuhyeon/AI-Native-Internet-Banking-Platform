import { api } from '@/lib/api'

/**
 * 약관 동의 API 클라이언트.
 *
 * 화면은 동의 체크박스를 받아 놓고 어디에도 보내지 않았다 — React 상태로만 있다가
 * 사라졌다. 그래서 "이 고객이 무엇에 언제 동의했는가" 를 물으면 답할 방법이 없었고,
 * 규제 관점에서 그것은 동의를 받지 않은 것과 같다.
 *
 * 정본은 customer-service 다(고객에 대한 사실이므로).
 */

/** 어느 업무의 약관인가. 서버 시드(V43)의 biz_div_cd 와 같은 값이어야 한다. */
export type ConsentBizDiv =
  | 'CERT' | 'BANKING' | 'LOAN' | 'MARKETING'
  | 'BIZ_CERT' | 'CORP_JOIN' | 'CLOSE'

export interface TermsTemplate {
  termsTemplateId: number
  termsNo: string
  termsName: string
  termsCategoryCd: string
  termsVersion: string
  required: boolean
  description?: string
}

export interface ConsentRecord {
  consentId: number
  termsNo: string
  termsName: string
  termsCategoryCd: string
  termsVersion: string
  required: boolean
  bizDivCd: string
  consentTargetId?: number
  agreed: boolean
  agreedAt: string
  consentMethodCd: string
  withdrawn: boolean
  withdrawnAt?: string
  withdrawnReason?: string
  /** 지금 효력이 있는가. 동의했고 철회하지 않았을 때만 참이다. */
  effective: boolean
}

interface ApiEnvelope<T> {
  data: T
}

/** 이 업무에서 받아야 할 약관 목록. */
export async function fetchTerms(bizDivCd: ConsentBizDiv): Promise<TermsTemplate[]> {
  const res = await api.get<ApiEnvelope<TermsTemplate[]>>('/api/v1/customers/me/consents/terms', {
    params: { bizDivCd },
  })
  return res.data.data ?? []
}

/**
 * 한 화면에서 받은 동의를 한 번에 기록한다.
 *
 * 항목을 하나씩 보내지 않는 이유는, 중간에 실패하면 일부만 동의한 상태가 남기
 * 때문이다. 화면에서는 한 번의 행위였는데 기록은 반만 남는다.
 */
export async function recordConsents(input: {
  bizDivCd: ConsentBizDiv
  consentTargetId?: number
  consentMethodCd?: 'WEB' | 'MOBILE' | 'BRANCH'
  items: { termsNo: string; agreed: boolean }[]
}): Promise<ConsentRecord[]> {
  const res = await api.post<ApiEnvelope<ConsentRecord[]>>('/api/v1/customers/me/consents', {
    bizDivCd: input.bizDivCd,
    consentTargetId: input.consentTargetId ?? null,
    consentMethodCd: input.consentMethodCd ?? 'WEB',
    consentTool: typeof navigator !== 'undefined' ? navigator.userAgent.slice(0, 500) : null,
    items: input.items,
  })
  return res.data.data ?? []
}

/** 내 동의 이력. */
export async function fetchConsentHistory(bizDivCd?: ConsentBizDiv): Promise<ConsentRecord[]> {
  const res = await api.get<ApiEnvelope<ConsentRecord[]>>('/api/v1/customers/me/consents', {
    params: bizDivCd ? { bizDivCd } : undefined,
  })
  return res.data.data ?? []
}

/** 철회. 동의했던 사실은 남고 효력만 사라진다. */
export async function withdrawConsent(consentId: number, reason: string): Promise<ConsentRecord> {
  const res = await api.post<ApiEnvelope<ConsentRecord>>(
    `/api/v1/customers/me/consents/${consentId}/withdrawal`,
    { reason },
  )
  return res.data.data
}
