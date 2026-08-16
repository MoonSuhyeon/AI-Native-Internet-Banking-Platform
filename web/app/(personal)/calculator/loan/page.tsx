'use client'
import { KB_PRIMARY } from '@/lib/theme'

import { useState } from 'react'

const CALC_TABS = ['원리금균등상환', '원금균등상환', '원금만기일시상환']

const CALC_TERMS = [
  '대출원금과 이자를 대출기간 동안 매달 같은 금액으로 나누어 갚아나가는 방식',
  '대출원금을 대출기간으로 나눈 할부 상환금에 월별 이자를 합하여 갚는 방식',
  '대출기간 동안 이자만 내고 만기에 대출금을 모두 갚는 방식',
]

const CALC_FOOTNOTES = [
  '원리금균등상환방식은 매월 동일한 월 납부금액(상환원금+이자금액)이 부과됩니다. (원금이 상환됨에 따라 상환원금은 늘어나고 이자금액은 줄어들어 합계금액인 월 납부금액을 일정하게 유지하는 방식입니다.)',
  '원금균등상환방식은 매월 동일한 원금을 상환하는 방식으로 원금이 상환됨에 따라 이자금액이 줄어들어 합계금액인 월 납부금액도 줄어드는 방식입니다.',
  '원금만기일시상환방식은 매월 이자를 납부하고 원금은 만기에 일시상환하는 방식입니다.',
]

const AMT_BTNS  = ['+100만', '+500만', '+1000만', '+5000만', '초기화']
const YEAR_BTNS = ['+1년', '+2년', '+5년', '+10년', '초기화']
const RATE_BTNS = ['+0.1%', '+1%', '+5%', '초기화']

type CalcResult = { principal: number; interest: number; total: number }

export default function LoanCalculatorPage() {
  const [calcTab, setCalcTab] = useState(0)
  const [principal, setPrincipal] = useState('')
  const [years, setYears] = useState('')
  const [rate, setRate] = useState('')
  const [calcResult, setCalcResult] = useState<CalcResult | null>(null)
  const [focusedInput, setFocusedInput] = useState<'principal' | 'years' | 'rate' | null>(null)
  const [termTooltip, setTermTooltip] = useState<number | null>(null)

  function handleAmtBtn(b: string) {
    const map: Record<string, number> = { '+100만': 100, '+500만': 500, '+1000만': 1000, '+5000만': 5000 }
    if (b === '초기화') { setPrincipal(''); return }
    const cur = parseFloat(principal.replace(/,/g, '')) || 0
    setPrincipal((cur + (map[b] ?? 0)).toLocaleString('ko-KR'))
  }
  function handleYearBtn(b: string) {
    const map: Record<string, number> = { '+1년': 1, '+2년': 2, '+5년': 5, '+10년': 10 }
    if (b === '초기화') { setYears(''); return }
    setYears(y => String((parseInt(y) || 0) + (map[b] ?? 0)))
  }
  function handleRateBtn(b: string) {
    const map: Record<string, number> = { '+0.1%': 0.1, '+1%': 1, '+5%': 5 }
    if (b === '초기화') { setRate(''); return }
    setRate(r => String(Math.round(((parseFloat(r) || 0) + (map[b] ?? 0)) * 10) / 10))
  }

  function handleCalc() {
    const p = (parseFloat(principal.replace(/,/g, '')) || 0) * 10000
    const m = (parseInt(years) || 0) * 12
    const r = (parseFloat(rate) || 0) / 100 / 12
    if (!p || !m || !r) return
    if (calcTab === 0) {
      const total = Math.round(p * r / (1 - Math.pow(1 + r, -m)))
      const interest = Math.round(p * r)
      setCalcResult({ principal: total - interest, interest, total })
    } else if (calcTab === 1) {
      const mp = Math.round(p / m)
      const interest = Math.round(p * r)
      setCalcResult({ principal: mp, interest, total: mp + interest })
    } else {
      const interest = Math.round(p * r)
      setCalcResult({ principal: 0, interest, total: interest })
    }
  }

  function fmtN(n: number) { return n.toLocaleString('ko-KR') }

  const focusCls = 'border-kb-primary bg-kb-primary-bg'
  const blurCls  = 'border-kb-border'

  return (
    <div className="max-w-kb-container mx-auto px-8 py-10">
      <h1 className="text-[24px] font-bold text-kb-text mb-1">대출 계산기</h1>
      <p className="text-[13px] text-kb-text-muted mb-8">원하시는 정보를 입력하신 후, 예상계산결과를 확인하세요.</p>

      <div className="border border-kb-border">
        {/* 수평 탭 */}
        <div className="flex border-b border-kb-border">
          {CALC_TABS.map((t, i) => (
            <div key={t} className="relative">
              <button onClick={() => { setCalcTab(i); setCalcResult(null); setTermTooltip(null) }}
                className={`flex items-center gap-2 px-6 py-3 text-[13px] transition-colors border-b-2 -mb-px ${
                  calcTab === i
                    ? 'border-kb-gold font-bold text-kb-text bg-white'
                    : 'border-transparent text-kb-text-muted bg-kb-beige-light hover:text-kb-text'
                }`}>
                {t}
                <span
                  onClick={e => { e.stopPropagation(); setTermTooltip(termTooltip === i ? null : i) }}
                  className="text-kb-blue text-[11px] font-normal cursor-pointer hover:opacity-70">
                  용어설명
                </span>
              </button>
              {termTooltip === i && (
                <div className="absolute left-0 top-full z-20 bg-white border border-kb-border shadow-md p-3 w-64 text-[12px] text-kb-text-body leading-relaxed">
                  <button onClick={() => setTermTooltip(null)}
                    className="absolute top-2 right-2 text-kb-text-muted hover:text-kb-text leading-none text-[14px]">✕</button>
                  <p className="pr-4">{CALC_TERMS[i]}</p>
                </div>
              )}
            </div>
          ))}
        </div>

        {/* 입력 영역 */}
        <div className="px-6 pt-5 pb-4">
          <div className="flex items-center gap-2 text-[13px] flex-wrap">
            <span className="text-kb-text-muted font-bold">금액</span>
            <input type="text" value={principal} onChange={e => setPrincipal(e.target.value)}
              placeholder="대출금액"
              onFocus={() => setFocusedInput('principal')}
              onBlur={() => setFocusedInput(null)}
              className={`border px-3 py-1.5 w-28 outline-none text-right text-[13px] transition-colors ${
                focusedInput === 'principal' ? focusCls : blurCls
              }`} />
            <span className="text-kb-text">대출금액 만원을</span>

            <span className="text-kb-text-muted font-bold ml-4">기간</span>
            <input type="text" value={years} onChange={e => setYears(e.target.value)}
              placeholder="기간"
              onFocus={() => setFocusedInput('years')}
              onBlur={() => setFocusedInput(null)}
              className={`border px-3 py-1.5 w-16 outline-none text-right text-[13px] transition-colors ${
                focusedInput === 'years' ? focusCls : blurCls
              }`} />
            <span className="text-kb-text">년 동안</span>

            <span className="text-kb-text-muted font-bold ml-4">이자</span>
            <input type="text" value={rate} onChange={e => setRate(e.target.value)}
              placeholder="금리"
              onFocus={() => setFocusedInput('rate')}
              onBlur={() => setFocusedInput(null)}
              className={`border px-3 py-1.5 w-16 outline-none text-right text-[13px] transition-colors ${
                focusedInput === 'rate' ? focusCls : blurCls
              }`} />
            <span className="text-kb-text">%로 대출 받으면?</span>

            <button onClick={handleCalc}
              className="ml-auto text-white text-[12px] font-bold px-5 py-1.5 rounded-lg hover:opacity-90 transition-opacity"
              style={{ backgroundColor: KB_PRIMARY }}>
              결과보기
            </button>
          </div>

          {/* 간편입력 버튼 */}
          <div className="mt-2 min-h-[26px]" onMouseDown={e => e.preventDefault()}>
            {focusedInput === 'principal' && (
              <div className="flex gap-1">
                {AMT_BTNS.map(b => (
                  <button key={b} onClick={() => handleAmtBtn(b)}
                    className="border border-kb-border px-3 py-1 text-[11px] text-kb-text-body hover:bg-kb-beige-light">{b}</button>
                ))}
              </div>
            )}
            {focusedInput === 'years' && (
              <div className="flex gap-1">
                {YEAR_BTNS.map(b => (
                  <button key={b} onClick={() => handleYearBtn(b)}
                    className="border border-kb-border px-3 py-1 text-[11px] text-kb-text-body hover:bg-kb-beige-light">{b}</button>
                ))}
              </div>
            )}
            {focusedInput === 'rate' && (
              <div className="flex gap-1">
                {RATE_BTNS.map(b => (
                  <button key={b} onClick={() => handleRateBtn(b)}
                    className="border border-kb-border px-3 py-1 text-[11px] text-kb-text-body hover:bg-kb-beige-light">{b}</button>
                ))}
              </div>
            )}
          </div>

          <div className="flex items-center justify-between mt-3">
            <p className="text-[12px] text-kb-text-muted flex items-center gap-1.5">
              <span className="border border-kb-border rounded-full w-4 h-4 flex items-center justify-center text-[8px] flex-shrink-0">i</span>
              원하시는 정보를 입력하신 후, 예상계산결과를 확인하세요.
            </p>
            <button onClick={() => { setPrincipal(''); setYears(''); setRate(''); setCalcResult(null) }}
              className="border border-kb-border px-4 py-1 text-[12px] text-kb-text-body hover:bg-kb-beige-light">
              다시하기
            </button>
          </div>
        </div>

        {/* 결과 영역 */}
        {calcResult && (
          <div className="border-t border-kb-border">
            <div className="px-6 py-4 flex items-center gap-3">
              <svg viewBox="0 0 16 16" fill="none" className="w-4 h-4 flex-shrink-0" stroke="#0D5C47" strokeWidth="1.8">
                <polyline points="2 10 6 6 10 9 14 4"/>
              </svg>
              <p className="text-[13px] text-kb-text-body">
                상환 예정 금액은 <span className="font-bold text-kb-text">{CALC_TABS[calcTab]}</span> 기준 매월 약{' '}
                <span className="text-[16px] font-bold" style={{ color: KB_PRIMARY }}>{fmtN(calcResult.total)}원</span>이에요.
              </p>
            </div>
            <div className="mx-6 mb-2 border border-kb-border overflow-hidden rounded-sm">
              <div className="px-4 py-2.5 flex items-center justify-between" style={{ backgroundColor: KB_PRIMARY }}>
                <p className="text-[13px] font-bold text-white">상환예정금액</p>
                <p className="text-[11px] text-white/70">대출계산결과</p>
              </div>
              <div className="grid grid-cols-3 divide-x divide-kb-border border-b border-kb-border bg-[#F5F6F8]">
                {['상환원금', '이자금액', '월 납부금액'].map(label => (
                  <p key={label} className="px-4 py-2 text-[12px] text-kb-text-muted text-center font-medium">{label}</p>
                ))}
              </div>
              <div className="grid grid-cols-3 divide-x divide-kb-border bg-white">
                {[calcResult.principal, calcResult.interest, calcResult.total].map((val, i) => (
                  <p key={i} className={`px-4 py-3 text-right font-bold ${
                    i === 2 ? 'text-[16px]' : 'text-[14px] text-kb-text'
                  }`}
                    style={i === 2 ? { color: KB_PRIMARY, fontSize: '16px' } : undefined}>
                    {fmtN(val)}원
                  </p>
                ))}
              </div>
            </div>
            <p className="px-6 pb-4 text-[11px] text-kb-text-muted leading-relaxed">
              * {CALC_FOOTNOTES[calcTab]}
            </p>
          </div>
        )}
      </div>
    </div>
  )
}
