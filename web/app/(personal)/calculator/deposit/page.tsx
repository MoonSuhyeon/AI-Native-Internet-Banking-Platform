'use client'
import { KB_GOLD, KB_PRIMARY } from '@/lib/theme'

import { useState } from 'react'

const CALC_TABS = [
  '열심히 모은 목돈을 예치할 때',
  '매월 일정금액을 저축할 때',
  '목표금액을 모을 때',
]

type DepositResult =
  | { type: 0; amount: number; interest: number; afterTax: number }
  | { type: 1; principal: number; interest: number; total: number }
  | { type: 2; monthlyNeeded: number }

export default function DepositCalculatorPage() {
  const [calcTab, setCalcTab] = useState(0)
  const [amount, setAmount] = useState('')
  const [monthly, setMonthly] = useState('')
  const [target, setTarget] = useState('')
  const [months, setMonths] = useState('')
  const [rate, setRate] = useState('')
  const [calcResult, setCalcResult] = useState<DepositResult | null>(null)

  function handleCalc() {
    const m = parseInt(months) || 0
    const r = (parseFloat(rate) || 2.4) / 100
    if (calcTab === 0) {
      const a = parseFloat(amount.replace(/,/g, '')) || 0
      const interest = Math.floor(a * r * (m / 12))
      setCalcResult({ type: 0, amount: a, interest, afterTax: Math.floor(interest * 0.846) })
    } else if (calcTab === 1) {
      const mo = parseFloat(monthly.replace(/,/g, '')) || 0
      const principal = mo * m
      const interest = Math.floor(principal * r * 0.5)
      setCalcResult({ type: 1, principal, interest, total: principal + interest })
    } else {
      const tgt = parseFloat(target.replace(/,/g, '')) || 0
      const monthlyNeeded = m > 0 ? Math.ceil(tgt / m) : 0
      setCalcResult({ type: 2, monthlyNeeded })
    }
  }

  function fmt(n: number) { return n.toLocaleString('ko-KR') }

  return (
    <div className="max-w-kb-container mx-auto px-8 py-10">
      <h1 className="text-[24px] font-bold text-kb-text mb-1">예금 계산기</h1>
      <p className="text-[13px] text-kb-text-muted mb-8">원하시는 정보를 입력하신 후, 예상계산결과를 확인하세요.</p>

      <div className="border border-kb-border">
        <div className="flex">
          {/* 탭 (좌) */}
          <div className="w-[300px] flex-shrink-0 border-r border-kb-border bg-[#F5F3F0] divide-y divide-kb-border">
            {[
              { icon: <svg viewBox="0 0 20 20" fill="none" className="w-5 h-5 flex-shrink-0" stroke="currentColor" strokeWidth="1.5"><ellipse cx="10" cy="11" rx="7" ry="5"/><path d="M3 11V9c0-2.8 3.1-5 7-5s7 2.2 7 5v2"/><line x1="10" y1="4" x2="10" y2="2"/></svg> },
              { icon: <svg viewBox="0 0 20 20" fill="none" className="w-5 h-5 flex-shrink-0" stroke="currentColor" strokeWidth="1.5"><rect x="3" y="2" width="14" height="16" rx="1"/><line x1="6" y1="7" x2="14" y2="7"/><line x1="6" y1="10" x2="14" y2="10"/><line x1="6" y1="13" x2="10" y2="13"/></svg> },
              { icon: <svg viewBox="0 0 20 20" fill="none" className="w-5 h-5 flex-shrink-0" stroke="currentColor" strokeWidth="1.5"><circle cx="10" cy="10" r="7"/><circle cx="10" cy="10" r="3"/><line x1="10" y1="1" x2="10" y2="3"/><line x1="10" y1="17" x2="10" y2="19"/><line x1="1" y1="10" x2="3" y2="10"/><line x1="17" y1="10" x2="19" y2="10"/></svg> },
            ].map(({ icon }, i) => (
              <button key={i} onClick={() => { setCalcTab(i); setCalcResult(null) }}
                className={`flex items-center gap-3 w-full px-5 py-5 text-left text-[13px] leading-snug transition-colors ${
                  calcTab === i ? 'bg-white font-bold text-kb-text' : 'text-kb-text-muted hover:text-kb-text'
                }`}
                style={calcTab === i ? { borderLeft: '3px solid #C09B3A' } : { borderLeft: '3px solid transparent' }}>
                <span style={{ color: calcTab === i ? KB_GOLD : undefined }}>{icon}</span>
                <span>{CALC_TABS[i]}</span>
              </button>
            ))}
          </div>

          {/* 입력 (우) */}
          <div className="flex-1 px-8 py-6 flex gap-4 items-stretch">
            <div className="flex-1">
              <p className="text-[14px] text-kb-text mb-6">{CALC_TABS[calcTab]},</p>

              {calcTab === 0 && (
                <div className="space-y-4">
                  <div className="flex items-center gap-2 text-[13px]">
                    <input type="text" value={amount} onChange={e => setAmount(e.target.value)}
                      placeholder="예치금액"
                      className="border border-kb-border px-3 py-2 w-32 outline-none text-right text-[13px] focus:border-kb-primary" />
                    <span className="text-kb-text whitespace-nowrap">원을</span>
                    <input type="text" value={months} onChange={e => setMonths(e.target.value)}
                      placeholder="기간"
                      className="border border-kb-border px-3 py-2 w-16 outline-none text-right text-[13px] focus:border-kb-primary" />
                    <span className="text-kb-text whitespace-nowrap">개월 간</span>
                  </div>
                  <div className="flex items-center gap-2 text-[13px]">
                    <input type="text" value={rate} onChange={e => setRate(e.target.value)}
                      placeholder="금리"
                      className="border border-kb-border px-3 py-2 w-16 outline-none text-right text-[13px] focus:border-kb-primary" />
                    <span className="text-kb-text">%의 예금상품에 저축하면?</span>
                  </div>
                </div>
              )}

              {calcTab === 1 && (
                <div className="space-y-4">
                  <div className="flex items-center gap-2 text-[13px]">
                    <input type="text" value={monthly} onChange={e => setMonthly(e.target.value)}
                      placeholder="월저축액"
                      className="border border-kb-border px-3 py-2 w-32 outline-none text-right text-[13px] focus:border-kb-primary" />
                    <span className="text-kb-text whitespace-nowrap">원씩</span>
                    <input type="text" value={months} onChange={e => setMonths(e.target.value)}
                      placeholder="기간"
                      className="border border-kb-border px-3 py-2 w-16 outline-none text-right text-[13px] focus:border-kb-primary" />
                    <span className="text-kb-text whitespace-nowrap">개월 간</span>
                  </div>
                  <div className="flex items-center gap-2 text-[13px]">
                    <input type="text" value={rate} onChange={e => setRate(e.target.value)}
                      placeholder="금리"
                      className="border border-kb-border px-3 py-2 w-16 outline-none text-right text-[13px] focus:border-kb-primary" />
                    <span className="text-kb-text">%의 적금상품에 저축하면?</span>
                  </div>
                </div>
              )}

              {calcTab === 2 && (
                <div className="space-y-4">
                  <div className="flex items-center gap-2 text-[13px]">
                    <input type="text" value={target} onChange={e => setTarget(e.target.value)}
                      placeholder="목표금액"
                      className="border border-kb-border px-3 py-2 w-32 outline-none text-right text-[13px] focus:border-kb-primary" />
                    <span className="text-kb-text whitespace-nowrap">원을 목표로</span>
                    <input type="text" value={months} onChange={e => setMonths(e.target.value)}
                      placeholder="기간"
                      className="border border-kb-border px-3 py-2 w-16 outline-none text-right text-[13px] focus:border-kb-primary" />
                    <span className="text-kb-text whitespace-nowrap">개월 간</span>
                  </div>
                  <div className="flex items-center gap-2 text-[13px]">
                    <input type="text" value={rate} onChange={e => setRate(e.target.value)}
                      placeholder="금리"
                      className="border border-kb-border px-3 py-2 w-16 outline-none text-right text-[13px] focus:border-kb-primary" />
                    <span className="text-kb-text">%로 저축한다면?</span>
                  </div>
                </div>
              )}

              {/* 결과 카드 */}
              {calcResult && (
                <div className="mt-6 rounded overflow-hidden border border-kb-primary/20">
                  <div className="px-4 py-2.5 flex items-center gap-2" style={{ backgroundColor: KB_PRIMARY }}>
                    <svg viewBox="0 0 16 16" fill="none" className="w-3.5 h-3.5" stroke="white" strokeWidth="1.8">
                      <polyline points="2 10 6 6 10 9 14 4"/>
                    </svg>
                    <p className="text-[12px] font-semibold text-white">예상 계산 결과</p>
                  </div>
                  <div className="bg-kb-primary-bg px-5 py-4 space-y-2.5">
                    {calcResult.type === 0 && (
                      <>
                        <div className="flex justify-between items-center">
                          <span className="text-[12px] text-kb-text-muted">예상 이자</span>
                          <span className="text-[15px] font-bold text-kb-text">{fmt(calcResult.interest)}원</span>
                        </div>
                        <div className="flex justify-between items-center border-t border-kb-primary/10 pt-2.5">
                          <span className="text-[12px] text-kb-text-muted">세후 이자 <span className="text-[11px]">(이자소득세 15.4%)</span></span>
                          <span className="text-[18px] font-bold" style={{ color: KB_PRIMARY }}>{fmt(calcResult.afterTax)}원</span>
                        </div>
                      </>
                    )}
                    {calcResult.type === 1 && (
                      <>
                        <div className="flex justify-between items-center">
                          <span className="text-[12px] text-kb-text-muted">납입 원금</span>
                          <span className="text-[15px] font-bold text-kb-text">{fmt(calcResult.principal)}원</span>
                        </div>
                        <div className="flex justify-between items-center">
                          <span className="text-[12px] text-kb-text-muted">예상 이자</span>
                          <span className="text-[15px] font-bold text-kb-text">{fmt(calcResult.interest)}원</span>
                        </div>
                        <div className="flex justify-between items-center border-t border-kb-primary/10 pt-2.5">
                          <span className="text-[12px] text-kb-text-muted">만기 수령액</span>
                          <span className="text-[18px] font-bold" style={{ color: KB_PRIMARY }}>{fmt(calcResult.total)}원</span>
                        </div>
                      </>
                    )}
                    {calcResult.type === 2 && (
                      <div className="flex justify-between items-center">
                        <span className="text-[12px] text-kb-text-muted">월 저축 필요금액</span>
                        <span className="text-[18px] font-bold" style={{ color: KB_PRIMARY }}>{fmt(calcResult.monthlyNeeded)}원</span>
                      </div>
                    )}
                  </div>
                </div>
              )}

              <p className="text-[12px] text-kb-text-muted mt-6 flex items-center gap-1.5">
                <span className="border border-kb-border rounded-full w-4 h-4 flex items-center justify-center text-[8px] flex-shrink-0">i</span>
                원하시는 정보를 입력하신 후, 예상계산결과를 확인하세요.
              </p>
            </div>

            <div className="flex-shrink-0 self-stretch flex">
              <button onClick={handleCalc}
                className="self-stretch px-8 text-white text-[15px] font-bold rounded-lg hover:opacity-90 transition-opacity min-w-[90px]"
                style={{ backgroundColor: KB_PRIMARY }}>
                결과보기
              </button>
            </div>
          </div>
        </div>
      </div>
    </div>
  )
}
