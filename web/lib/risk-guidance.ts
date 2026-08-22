/**
 * 이상거래로 이체가 멈췄을 때의 안내.
 *
 * **무엇을 고치는가.** 지금까지 고객이 본 것은 고정된 한 줄이었다.
 *
 * > "이상거래로 판단되어 이체가 제한되었습니다. 고객센터로 문의해 주세요."
 *
 * 탐지기는 "고액 이체 12000000원" 같은 근거를 이미 만들어 보내고 있었는데, 결제계
 * 게이트가 그걸 로그에만 쓰고 버렸다. 그래서 화면에는 **왜 막혔는지도, 지금 무엇을
 * 해야 하는지도** 없었다.
 *
 * **두 단계로 받는다.**
 *
 * 1. 이체 응답에 실려 오는 **기본 안내** — 규칙으로 확정된 것이라 항상 있다.
 *    돈이 움직이는 경로 안에서 만들어지므로 빠르고, 모델에 기대지 않는다.
 * 2. 조사 에이전트가 **연령대에 맞춰 다듬은 안내** — 따로 부른다.
 *
 * 화면은 1을 즉시 그리고, 2가 오면 갈아끼운다. **2가 늦게 와도, 안 와도 화면은
 * 성립한다.** 안내가 비어 있는 화면은 차단만 되고 이유가 없는 화면이라, 고치려던
 * 문제보다 나쁘기 때문이다.
 */

import { api } from '@/lib/api'

export type RiskChoice = 'STEP_UP' | 'CANCEL' | 'CONSULT'

export type RiskGuidance = {
  /** 왜 멈췄는지 한 줄. */
  headline: string
  /** 판단 근거. 심사원이 보는 것과 같은 문장이다. */
  evidence: string[]
  /** 지금 할 일. 첫 줄이 가장 급한 것이다. */
  actionSteps: string[]
  /** 화면이 줄 선택지. */
  choices: string[]
}

/** 이상거래로 거래가 멈춘 오류인가. */
const RISK_CODES = new Set([
  'TRANSFER_BLOCKED_BY_RISK',
  'TRANSFER_APPROVAL_REQUIRED',
])

type ErrorLike = {
  response?: { data?: { code?: string; message?: string; guidance?: unknown } }
}

/**
 * 이체 오류에서 안내를 꺼낸다.
 *
 * **안내가 없을 수도 있다.** 탐지기가 축소 판정했거나(신호를 못 본 경우) 구버전이면
 * 비어 온다. 그때 `null` 을 주는 이유는, 없는 근거로 문구를 지어내는 것이 아무것도
 * 안 보여 주는 것보다 나쁘기 때문이다 — 화면은 예전처럼 메시지 한 줄로 되돌아간다.
 */
export function extractGuidance(error: unknown): RiskGuidance | null {
  const data = (error as ErrorLike)?.response?.data
  if (!data?.code || !RISK_CODES.has(data.code)) return null

  const g = data.guidance as Partial<RiskGuidance> | undefined
  if (!g?.headline) return null

  return {
    headline: g.headline,
    evidence: g.evidence ?? [],
    actionSteps: g.actionSteps ?? [],
    choices: g.choices ?? ['CANCEL', 'CONSULT'],
  }
}

/** 이 오류가 추가 인증으로 이어질 수 있는가. */
export function isStepUp(error: unknown): boolean {
  return (error as ErrorLike)?.response?.data?.code === 'TRANSFER_APPROVAL_REQUIRED'
}

/**
 * 연령대에 맞춰 다듬은 안내를 받아 온다.
 *
 * **실패하면 기본 안내를 그대로 돌려준다.** 이 호출은 화면이 이미 그려진 뒤에
 * 일어나고, 바뀌는 것은 말투뿐이다. 실패했다고 화면을 오류로 만들면 그게 더 나쁘다.
 *
 * 고객 번호를 보내지 않는다 — 게이트웨이가 토큰에서 꺼내 주입한다. 본문에 받으면
 * 아무나 남의 번호를 적어 그 사람의 연령대를 알아낼 수 있다.
 */
export async function fetchTailoredGuidance(base: RiskGuidance): Promise<RiskGuidance> {
  try {
    const { data } = await api.post('/api/v1/fraud/guidance', {
      headline: base.headline,
      evidence: base.evidence,
      action_steps: base.actionSteps,
      choices: base.choices,
    }, { timeout: 6000 })

    const steps: string[] = data?.action_steps ?? []
    if (!steps.length) return base

    return { ...base, actionSteps: steps }
  } catch {
    return base
  }
}
