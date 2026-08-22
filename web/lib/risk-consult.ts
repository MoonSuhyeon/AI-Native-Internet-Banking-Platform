/**
 * 막힌 이체를 상담원에게 넘긴다.
 *
 * **왜 필요한가.** 지금까지 사전 점검에 막힌 고객은 갈 곳이 없었다. 화면에
 * "고객센터로 문의해 주세요" 만 떴고, 그 문의는 이 시스템 밖으로 나갔다 — 상담원은
 * 무엇이 왜 막혔는지 모른 채 전화를 받았고, 탐지기가 든 근거는 어디에도 따라가지
 * 않았다.
 *
 * 이제 케이스로 열린다. 근거가 함께 실리고, 심사원 콘솔의 조사 큐에 올라간다.
 *
 * **조사를 지금 돌리지 않는다.** 큐에 올리기만 한다. 버튼을 누른 그 자리에서
 * 조사 루프를 태우면 고객이 수십 초를 기다리게 되고, 버튼 한 번이 LLM 호출을 부른다.
 * 조사는 심사원이 케이스를 열 때 돈다 — 사람이 먼저라는 HITL 순서 그대로다.
 */

import { api } from '@/lib/api'

export type ConsultResult =
  | { ok: true; caseId: string }
  | { ok: false; message: string }

export async function openRiskConsultation(input: {
  amount: number
  payee: string
  evidence: string[]
}): Promise<ConsultResult> {
  try {
    const { data } = await api.post('/api/v1/fraud/consult', {
      amount: input.amount,
      payee: input.payee,
      channel: 'WEB',
      evidence: input.evidence,
    })
    return { ok: true, caseId: data?.case_id ?? '' }
  } catch (e: unknown) {
    // 접수 실패를 성공으로 보여주지 않는다. 고객이 상담이 접수된 줄 알고
    // 기다리는 것이, 실패를 아는 것보다 나쁘다.
    const message =
      (e as { response?: { data?: { detail?: string; message?: string } } })
        ?.response?.data?.detail ??
      '상담 접수에 실패했습니다. 고객센터(1588-9999)로 연락해 주세요.'
    return { ok: false, message }
  }
}
