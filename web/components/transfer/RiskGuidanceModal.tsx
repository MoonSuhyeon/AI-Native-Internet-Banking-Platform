'use client'

/**
 * 이상거래 안내 팝업.
 *
 * **왜 이 화면이 필요한가.** 지금까지 이체가 막히면 결과 페이지에 한 줄이 떴다 —
 * "이상거래로 판단되어 이체가 제한되었습니다. 고객센터로 문의해 주세요." 왜 막혔는지도
 * 없고, 지금 무엇을 해야 하는지도 없었다. 보이스피싱 피해자에게 이 공백은 특히
 * 비싸다. 그 순간 필요한 것은 고객센터 번호가 아니라 **"보내기 전에 받는 분에게
 * 직접 전화하세요"** 한 줄이다.
 *
 * **막다른 골목을 만들지 않는다.** 선택지가 항상 있다. 취소하거나, 상담으로
 * 넘기거나, (추가 인증으로 풀 수 있는 등급이면) 인증한다. 닫기만 있는 화면은
 * 고치려던 문제를 그대로 남긴다.
 *
 * **단계적으로 채워진다.** 기본 안내는 이체 응답에 실려 와서 즉시 그려지고, 연령대에
 * 맞춘 문구가 도착하면 갈아끼운다. 도착하지 않아도 화면은 그대로 성립한다.
 */

import { useEffect, useState } from 'react'
import { KB_BORDER, KB_PRIMARY, KB_PRIMARY_BG, KB_TEXT_MUTED } from '@/lib/theme'
import { fetchTailoredGuidance, type RiskGuidance } from '@/lib/risk-guidance'

type Props = {
  guidance: RiskGuidance
  /** 추가 인증으로 계속하기. 선택지에 STEP_UP 이 있을 때만 쓰인다. */
  onStepUp?: () => void
  /** 보내지 않는다. */
  onCancel: () => void
  /** 상담원에게 넘긴다. */
  onConsult: () => void
}

export default function RiskGuidanceModal({ guidance, onStepUp, onCancel, onConsult }: Props) {
  const [shown, setShown] = useState(guidance)
  const [tailoring, setTailoring] = useState(true)

  useEffect(() => {
    let alive = true
    fetchTailoredGuidance(guidance).then((g) => {
      // 화면을 떠난 뒤 도착한 응답으로 상태를 건드리지 않는다.
      if (!alive) return
      setShown(g)
      setTailoring(false)
    })
    return () => { alive = false }
    // guidance 는 한 번 열릴 때 고정이다. 의존성에 넣으면 갈아끼운 값이 다시
    // 요청을 부르고, 그게 또 갈아끼워 루프가 된다.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  const canStepUp = guidance.choices.includes('STEP_UP') && Boolean(onStepUp)

  return (
    <div className="fixed inset-0 z-[120] flex items-center justify-center bg-black/60 px-4">
      <div className="w-full max-w-[520px] overflow-hidden rounded-2xl bg-white shadow-2xl">

        {/* 헤더 — 경고색을 쓰되 겁주지 않는다. 아직 돈은 나가지 않았다. */}
        <div className="px-6 py-5" style={{ backgroundColor: '#FFF4E5', borderBottom: `1px solid ${KB_BORDER}` }}>
          <div className="flex items-start gap-3">
            <span className="text-[22px] leading-none" aria-hidden="true">⚠️</span>
            <div>
              <h2 className="text-[17px] font-bold text-kb-text leading-snug">{shown.headline}</h2>
              <p className="mt-1 text-[12px]" style={{ color: KB_TEXT_MUTED }}>
                아직 돈은 나가지 않았습니다.
              </p>
            </div>
          </div>
        </div>

        <div className="max-h-[60vh] overflow-y-auto px-6 py-5">

          {/* 지금 할 일 — 근거보다 먼저다. 당황한 상태에서 읽는 화면이라
              "무엇을 해야 하나" 가 "왜 막혔나" 보다 급하다. */}
          {shown.actionSteps.length > 0 && (
            <section>
              <h3 className="mb-2 text-[13px] font-bold text-kb-text">지금 확인하세요</h3>
              <ol className="space-y-2">
                {shown.actionSteps.map((step, i) => (
                  <li key={i} className="flex gap-2 text-[13px] leading-relaxed text-kb-text">
                    <span className="mt-[2px] flex h-[18px] w-[18px] flex-shrink-0 items-center justify-center rounded-full text-[11px] font-bold text-white"
                          style={{ backgroundColor: KB_PRIMARY }}>
                      {i + 1}
                    </span>
                    <span>{step}</span>
                  </li>
                ))}
              </ol>
            </section>
          )}

          {/* 판단 근거 — 심사원이 보는 것과 같은 문장이다. 여기서 다른 말을 쓰면
              나중에 분쟁에서 설명이 갈린다. */}
          {shown.evidence.length > 0 && (
            <section className="mt-5 rounded-lg p-4" style={{ backgroundColor: KB_PRIMARY_BG }}>
              <h3 className="mb-2 text-[12px] font-bold" style={{ color: KB_PRIMARY }}>
                이렇게 판단했습니다
              </h3>
              <ul className="space-y-1">
                {shown.evidence.map((e, i) => (
                  <li key={i} className="text-[12px] leading-relaxed text-kb-text">· {e}</li>
                ))}
              </ul>
            </section>
          )}

          {tailoring && (
            <p className="mt-3 text-[11px]" style={{ color: KB_TEXT_MUTED }}>
              맞춤 안내를 불러오는 중입니다…
            </p>
          )}
        </div>

        {/* 선택지 — 닫기만 있는 화면을 만들지 않는다. */}
        <div className="flex flex-col gap-2 border-t px-6 py-4" style={{ borderColor: KB_BORDER }}>
          {canStepUp && (
            <button
              onClick={onStepUp}
              className="w-full rounded-lg py-3 text-[14px] font-bold text-white hover:opacity-90"
              style={{ backgroundColor: KB_PRIMARY }}
            >
              본인 확인하고 계속하기
            </button>
          )}
          <button
            onClick={onCancel}
            className="w-full rounded-lg border py-3 text-[14px] font-bold text-kb-text hover:bg-gray-50"
            style={{ borderColor: KB_BORDER }}
          >
            보내지 않기
          </button>
          <button
            onClick={onConsult}
            className="w-full py-2 text-[13px] font-semibold hover:underline"
            style={{ color: KB_PRIMARY }}
          >
            상담원에게 확인 요청하기
          </button>
        </div>
      </div>
    </div>
  )
}
