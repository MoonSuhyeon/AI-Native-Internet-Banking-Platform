'use client'
import { KB_PRIMARY } from '@/lib/theme'

import Link from 'next/link'
import { useState } from 'react'
import LoanSidebar from '@/components/inquiry/LoanSidebar'

const CATEGORIES = [
  { no: '01', desc: '빠르고 간편하게 바로 지금',    label: '신용대출',    href: '/products/loan/credit',   key: '신용' },
  { no: '02', desc: '부동산을 활용한 합리적 금리',   label: '담보대출',    href: '/products/loan/mortgage', key: '담보' },
  { no: '03', desc: '내 집 마련의 첫걸음',          label: '전월세 대출', href: '/products/loan/jeonse',   key: '전월세' },
  // 예전에는 "기타 대출" 한 장이 /products/loan/other 를 가리켰는데 그 페이지가 없어
  // 404 였다. 정작 아래 세 종류는 페이지가 있는데 여기서 링크되지 않아 들어갈 길이
  // 없었다. 죽은 카드 하나를 실제로 있는 셋으로 바꾼다.
  { no: '04', desc: '차량 구입 자금을 나누어',        label: '자동차대출',            href: '/products/loan/auto',  key: '자동차' },
  { no: '05', desc: '입주 전 중도금과 이주 비용',     label: '집단중도금·이주비대출', href: '/products/loan/group', key: '집단' },
  { no: '06', desc: '주택도시기금을 재원으로',        label: '주택도시기금대출',      href: '/products/loan/khfc',  key: '기금' },
]

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

const SLIDES = [
  {
    badge: '신용', category: '신용',
    sub: '근로소득이 발생되는 국민 누구나',
    title: 'AXful Smart신용대출',
    term: '최대 10년', limit: '3.5억원 이내',
    rate: '연 4.5% ~ 7.8%', rateNote: '2026.05.25 기준, 우대금리포함',
    href: '/products/loan/credit',
  },
  {
    badge: '담보', category: '담보',
    sub: '내 집을 담보로 유리한 조건의 대출',
    title: 'AXful 아파트담보대출',
    term: '최대 30년', limit: '10억원 이내',
    rate: '연 3.2% ~ 5.1%', rateNote: '2026.05.25 기준, 우대금리포함',
    href: '/products/loan/mortgage',
  },
  {
    badge: '전월세', category: '전월세',
    sub: '안전한 전세 생활을 위한 자금 지원',
    title: 'AXful 전세자금대출',
    term: '최대 2년', limit: '2억원 이내',
    rate: '연 2.8% ~ 4.2%', rateNote: '2026.05.25 기준, 우대금리포함',
    href: '/products/loan/jeonse',
  },
  {
    badge: '기타', category: '기타',
    sub: '내 차 마련을 위한 합리적 금리',
    title: 'AXful 자동차대출',
    term: '최대 7년', limit: '5천만원 이내',
    rate: '연 5.1% ~ 8.3%', rateNote: '2026.05.25 기준, 우대금리포함',
    href: '/products/loan/auto',
  },
  {
    badge: '기타', category: '기타',
    sub: '주거 안정을 위한 정책금융 대출',
    title: 'AXful 주택도시기금대출',
    term: '최대 30년', limit: '한도 내',
    rate: '연 1.5% ~ 3.3%', rateNote: '2026.05.25 기준, 정책금리',
    href: '/products/loan/khfc',
  },
]

type CalcResult = { principal: number; interest: number; total: number }

export default function LoanMainPage() {
  const [slide, setSlide] = useState(0)
  const [calcTab, setCalcTab] = useState(0)
  const [principal, setPrincipal] = useState('')
  const [years, setYears] = useState('')
  const [rate, setRate] = useState('')
  const [calcResult, setCalcResult] = useState<CalcResult | null>(null)
  const [focusedInput, setFocusedInput] = useState<'principal' | 'years' | 'rate' | null>(null)
  const [termTooltip, setTermTooltip] = useState<number | null>(null)

  const current = SLIDES[slide]

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

  return (
    <div className="max-w-kb-container mx-auto px-6">
      <div className="flex">
        <LoanSidebar />
        <main className="flex-1 pl-8 pt-4 pb-12 min-w-0">

      {/* ===== 히어로 영역 ===== */}
      <div className="flex rounded-xl overflow-hidden mb-6" style={{ border: '1px solid #E2F5EF' }}>

        {/* 좌: 슬라이드 배너 */}
        <div className="bg-white px-12 pt-9 pb-7 relative min-h-[340px] flex flex-col justify-between"
          style={{ width: 'calc(66.667% - 8px)', flexShrink: 0 }}>
          <div>
            <span className="inline-block text-white text-[12px] font-bold px-3 py-0.5 rounded-sm mb-3"
              style={{ backgroundColor: KB_PRIMARY }}>
              {current.badge}
            </span>
            <p className="text-[13px] text-kb-text-muted mb-2">{current.sub}</p>
            <h2 className="text-[38px] font-bold text-kb-text leading-tight mb-5">{current.title}</h2>
            <hr className="border-t-4 border-kb-primary-border mb-5" />
            <div className="flex gap-14 mb-5">
              <div className="flex items-center gap-3">
                <div className="w-12 h-12 rounded-full flex items-center justify-center flex-shrink-0"
                  style={{ backgroundColor: '#C09B3A' }}>
                  <svg viewBox="0 0 24 24" fill="none" className="w-6 h-6" stroke="white" strokeWidth="1.8">
                    <rect x="3" y="4" width="18" height="17" rx="2"/>
                    <line x1="3" y1="9" x2="21" y2="9"/>
                    <line x1="8" y1="2" x2="8" y2="6"/>
                    <line x1="16" y1="2" x2="16" y2="6"/>
                  </svg>
                </div>
                <div>
                  <p className="text-[12px] text-kb-text-muted">기간</p>
                  <p className="text-[19px] font-bold text-kb-text">{current.term}</p>
                </div>
              </div>
              <div className="flex items-center gap-3">
                <div className="w-12 h-12 rounded-full flex items-center justify-center flex-shrink-0"
                  style={{ backgroundColor: '#C09B3A' }}>
                  <svg viewBox="0 0 24 24" fill="none" className="w-6 h-6" stroke="white" strokeWidth="1.8">
                    <circle cx="12" cy="12" r="9"/>
                    <text x="12" y="16" textAnchor="middle" fontSize="11" fill="white" stroke="none" fontWeight="bold">₩</text>
                  </svg>
                </div>
                <div>
                  <p className="text-[12px] text-kb-text-muted">한도</p>
                  <p className="text-[19px] font-bold text-kb-text">{current.limit}</p>
                </div>
              </div>
            </div>
            <div className="flex items-center gap-3">
              <div className="w-12 h-12 rounded-full flex items-center justify-center flex-shrink-0"
                style={{ backgroundColor: '#C09B3A' }}>
                <svg viewBox="0 0 24 24" fill="none" className="w-6 h-6" stroke="white" strokeWidth="2">
                  <circle cx="12" cy="12" r="9"/>
                  <line x1="12" y1="7" x2="12" y2="17"/>
                  <line x1="7" y1="12" x2="17" y2="12"/>
                </svg>
              </div>
              <div>
                <p className="text-[22px] font-bold text-kb-text leading-none">
                  {current.rate}
                  <span className="text-[11px] text-kb-text-muted font-normal ml-2">{current.rateNote}</span>
                </p>
              </div>
            </div>
          </div>
          <div className="flex items-center justify-between mt-5">
            <div className="flex items-center gap-2">
              <button onClick={() => setSlide(s => (s - 1 + SLIDES.length) % SLIDES.length)}
                className="text-kb-text-muted hover:text-kb-text text-xl leading-none">‹</button>
              <div className="flex gap-1.5">
                {SLIDES.map((_, i) => (
                  <button key={i} onClick={() => setSlide(i)}
                    className={`w-2 h-2 rounded-full transition-colors ${
                      i === slide ? 'bg-kb-text' : 'bg-kb-border'
                    }`} />
                ))}
              </div>
              <button onClick={() => setSlide(s => (s + 1) % SLIDES.length)}
                className="text-kb-text-muted hover:text-kb-text text-xl leading-none">›</button>
              <span className="ml-2 text-[11px] text-kb-text-muted border border-kb-primary-border px-1.5 py-0.5">II</span>
            </div>
            <Link href={current.href} className="text-[14px] font-bold text-kb-text underline hover:opacity-70">
              바로가기
            </Link>
          </div>
        </div>

        {/* 우: 카테고리 4개 */}
        <div className="flex-1 bg-kb-primary-bg flex flex-col gap-2 p-3">
          {CATEGORIES.map(cat => {
            const isActive = cat.key === current.category
            return (
              <Link key={cat.no} href={cat.href}
                className={`flex items-center justify-between px-5 py-4 flex-1 transition-colors ${
                  isActive ? 'bg-kb-primary' : 'bg-white hover:bg-kb-primary-bg'
                }`}>
                <div className="flex items-start gap-4">
                  <span className={`text-[22px] font-bold leading-none mt-0.5 ${
                    isActive ? 'text-white/70' : 'text-kb-gold'
                  }`}>{cat.no}</span>
                  <div>
                    <p className={`text-[11px] leading-relaxed ${isActive ? 'text-white/80' : 'text-kb-text-muted'}`}>
                      {cat.desc}
                    </p>
                    <p className={`text-[14px] font-bold ${isActive ? 'text-white' : 'text-kb-text'}`}>
                      {cat.label}
                    </p>
                  </div>
                </div>
                {isActive && <span className="text-white text-lg">›</span>}
              </Link>
            )
          })}
        </div>
      </div>

      {/* ===== 상품 검색 ===== */}
      <div className="bg-kb-primary-bg px-10 py-5 flex items-center gap-4">
        <p className="text-[20px] font-bold text-kb-text whitespace-nowrap">원하시는 대출을 찾아보세요.</p>
        <div className="flex-1 flex items-center border border-kb-primary-border bg-white rounded-full overflow-hidden">
          <input type="text" placeholder="대출상품명을 입력하세요." className="flex-1 px-5 py-2.5 text-[20px] outline-none bg-transparent" />
          <button className="px-5 py-2.5 text-kb-text-muted hover:text-kb-text">
            <svg viewBox="0 0 20 20" fill="none" className="w-5 h-5" stroke="currentColor" strokeWidth="2">
              <circle cx="9" cy="9" r="6"/><line x1="14" y1="14" x2="18" y2="18"/>
            </svg>
          </button>
        </div>
      </div>

      <div className="px-10 py-8">
        {/* ===== 주요 서비스 ===== */}
        <div className="flex items-center gap-14 mb-8 pb-7 border-b border-kb-primary-border">
          <h3 className="text-[18px] font-bold text-kb-text whitespace-nowrap">주요 서비스</h3>
          <div className="flex flex-1 justify-evenly">
            {[
              {
                label: '대출진행조회', href: '/products/loan/status',
                icon: (
                  <svg viewBox="0 0 48 48" fill="none" className="w-12 h-12" stroke="currentColor" strokeWidth="1.5">
                    <rect x="8" y="6" width="26" height="33" rx="2"/>
                    <line x1="14" y1="15" x2="28" y2="15"/>
                    <line x1="14" y1="21" x2="28" y2="21"/>
                    <line x1="14" y1="27" x2="22" y2="27"/>
                    <circle cx="35" cy="35" r="9" fill="white" stroke="#C09B3A" strokeWidth="1.8"/>
                    <line x1="29" y1="35" x2="41" y2="35" stroke="#C09B3A" strokeWidth="1.8" strokeLinecap="round"/>
                    <polyline points="36,30 41,35 36,40" stroke="#C09B3A" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" fill="none"/>
                  </svg>
                ),
              },
              {
                label: '대출한도변경', href: '/products/loan/manage/limit',
                icon: (
                  <svg viewBox="0 0 48 48" fill="none" className="w-12 h-12" stroke="currentColor" strokeWidth="1.5">
                    <rect x="5" y="30" width="8" height="13" rx="1"/>
                    <rect x="17" y="22" width="8" height="21" rx="1"/>
                    <rect x="29" y="14" width="8" height="29" rx="1"/>
                    <line x1="5" y1="28" x2="41" y2="10" strokeDasharray="3 2"/>
                    <circle cx="38" cy="11" r="7" fill="white" stroke="#C09B3A" strokeWidth="1.8"/>
                    <line x1="35" y1="11" x2="41" y2="11" stroke="#C09B3A" strokeWidth="1.8" strokeLinecap="round"/>
                    <line x1="38" y1="8" x2="38" y2="14" stroke="#C09B3A" strokeWidth="1.8" strokeLinecap="round"/>
                  </svg>
                ),
              },
              {
                label: '이자/월부금 입금', href: '/products/loan/manage/payment',
                icon: (
                  <svg viewBox="0 0 48 48" fill="none" className="w-12 h-12" stroke="currentColor" strokeWidth="1.5">
                    <rect x="6" y="10" width="36" height="30" rx="2"/>
                    <line x1="6" y1="18" x2="42" y2="18"/>
                    <line x1="15" y1="10" x2="15" y2="6"/>
                    <line x1="33" y1="10" x2="33" y2="6"/>
                    <circle cx="24" cy="30" r="7" fill="white" stroke="#C09B3A" strokeWidth="1.8"/>
                    <line x1="24" y1="26" x2="24" y2="34" stroke="#C09B3A" strokeWidth="1.8" strokeLinecap="round"/>
                    <line x1="20" y1="30" x2="28" y2="30" stroke="#C09B3A" strokeWidth="1.8" strokeLinecap="round"/>
                  </svg>
                ),
              },
              {
                label: '대출금상환', href: '/products/loan/manage/repay',
                icon: (
                  <svg viewBox="0 0 48 48" fill="none" className="w-12 h-12" stroke="currentColor" strokeWidth="1.5">
                    <path d="M38 14 A16 16 0 1 0 40 26" strokeLinecap="round"/>
                    <polyline points="38,8 38,14 44,14" strokeLinecap="round" strokeLinejoin="round"/>
                    <circle cx="24" cy="26" r="8" fill="white" stroke="#C09B3A" strokeWidth="1.8"/>
                    <text x="24" y="30" textAnchor="middle" fontSize="9" fill="#C09B3A" stroke="none" fontWeight="bold">₩</text>
                  </svg>
                ),
              },
            ].map(s => (
              <Link key={s.label} href={s.href}
                className="flex flex-col items-center gap-2 hover:opacity-75 transition-opacity"
                style={{ color: '#333333' }}>
                {s.icon}
                <span className="text-[12px] text-kb-text-body text-center leading-tight">{s.label}</span>
              </Link>
            ))}
          </div>
        </div>

        {/* ===== 대출 계산기 ===== */}
        <div className="mb-8">
          <h3 className="text-[17px] font-bold text-kb-text mb-4">대출 계산기</h3>
          <div className="border border-kb-primary-border rounded-xl">

            {/* 수평 탭 */}
            <div className="flex border-b border-kb-primary-border">
              {CALC_TABS.map((t, i) => (
                <div key={t} className="relative">
                  <button onClick={() => { setCalcTab(i); setCalcResult(null); setTermTooltip(null) }}
                    className={`flex items-center gap-2 px-6 py-3 text-[13px] transition-colors border-b-2 -mb-px ${
                      calcTab === i
                        ? 'border-kb-primary font-bold text-kb-primary bg-white'
                        : 'border-transparent text-kb-text-muted bg-kb-primary-bg hover:text-kb-text'
                    }`}>
                    {t}
                    <span
                      onClick={e => { e.stopPropagation(); setTermTooltip(termTooltip === i ? null : i) }}
                      className="text-kb-blue text-[11px] font-normal cursor-pointer hover:opacity-70">
                      용어설명
                    </span>
                  </button>
                  {termTooltip === i && (
                    <div className="absolute left-0 top-full z-20 bg-white border border-kb-primary-border rounded-lg shadow-md p-3 w-64 text-[12px] text-kb-text-body leading-relaxed">
                      <button
                        onClick={() => setTermTooltip(null)}
                        className="absolute top-2 right-2 text-kb-text-muted hover:text-kb-text leading-none text-[14px]">
                        ✕
                      </button>
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
                  className={`border rounded-lg px-3 py-1.5 w-28 outline-none text-right text-[13px] transition-colors ${
                    focusedInput === 'principal' ? 'border-kb-primary bg-kb-primary-bg' : 'border-kb-primary-border'
                  }`} />
                <span className="text-kb-text">대출금액 만원을</span>

                <span className="text-kb-text-muted font-bold ml-4">기간</span>
                <input type="text" value={years} onChange={e => setYears(e.target.value)}
                  placeholder="기간"
                  onFocus={() => setFocusedInput('years')}
                  onBlur={() => setFocusedInput(null)}
                  className={`border rounded-lg px-3 py-1.5 w-16 outline-none text-right text-[13px] transition-colors ${
                    focusedInput === 'years' ? 'border-kb-primary bg-kb-primary-bg' : 'border-kb-primary-border'
                  }`} />
                <span className="text-kb-text">년 동안</span>

                <span className="text-kb-text-muted font-bold ml-4">이자</span>
                <input type="text" value={rate} onChange={e => setRate(e.target.value)}
                  placeholder="금리"
                  onFocus={() => setFocusedInput('rate')}
                  onBlur={() => setFocusedInput(null)}
                  className={`border rounded-lg px-3 py-1.5 w-16 outline-none text-right text-[13px] transition-colors ${
                    focusedInput === 'rate' ? 'border-kb-primary bg-kb-primary-bg' : 'border-kb-primary-border'
                  }`} />
                <span className="text-kb-text">%로 대출 받으면?</span>

                <button onClick={handleCalc}
                  className="ml-auto text-white text-[12px] font-bold px-5 py-1.5 hover:opacity-90 transition-opacity"
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
                        className="border border-kb-primary-border rounded-lg px-3 py-1 text-[11px] text-kb-text-body hover:bg-kb-primary-bg">{b}</button>
                    ))}
                  </div>
                )}
                {focusedInput === 'years' && (
                  <div className="flex gap-1">
                    {YEAR_BTNS.map(b => (
                      <button key={b} onClick={() => handleYearBtn(b)}
                        className="border border-kb-primary-border rounded-lg px-3 py-1 text-[11px] text-kb-text-body hover:bg-kb-primary-bg">{b}</button>
                    ))}
                  </div>
                )}
                {focusedInput === 'rate' && (
                  <div className="flex gap-1">
                    {RATE_BTNS.map(b => (
                      <button key={b} onClick={() => handleRateBtn(b)}
                        className="border border-kb-primary-border rounded-lg px-3 py-1 text-[11px] text-kb-text-body hover:bg-kb-primary-bg">{b}</button>
                    ))}
                  </div>
                )}
              </div>

              {/* 안내문 + 다시하기 */}
              <div className="flex items-center justify-between mt-3">
                <p className="text-[12px] text-kb-text-muted flex items-center gap-1.5">
                  <span className="border border-kb-primary-border rounded-full w-4 h-4 flex items-center justify-center text-[8px] flex-shrink-0">i</span>
                  원하시는 정보를 입력하신 후, 예상계산결과를 확인하세요.
                </p>
                <button
                  onClick={() => { setPrincipal(''); setYears(''); setRate(''); setCalcResult(null) }}
                  className="border border-kb-primary-border rounded-lg px-4 py-1 text-[12px] text-kb-text-body hover:bg-kb-primary-bg">
                  다시하기
                </button>
              </div>
            </div>

            {/* 결과 영역 */}
            {calcResult && (
              <div className="border-t border-kb-primary-border">
                <p className="px-6 py-4 text-[13px] text-kb-text-body">
                  상환 예정 금액은 <span className="font-bold text-kb-text">{CALC_TABS[calcTab]}</span> 기준 매월 약{' '}
                  <span className="font-bold text-kb-text">{fmtN(calcResult.total)}원</span>이에요.
                </p>
                <div className="mx-6 mb-2 border border-kb-primary-border rounded-xl overflow-hidden">
                  <div className="bg-kb-primary-bg px-4 py-2 border-b border-kb-primary-border flex items-center justify-between">
                    <p className="text-[13px] font-bold text-kb-text">상환예정금액</p>
                    <p className="text-[11px] text-kb-text-muted">대출계산결과</p>
                  </div>
                  <div className="grid grid-cols-3 divide-x divide-kb-primary-border border-b border-kb-primary-border bg-[#f8f8f8]">
                    {['상환원금', '이자금액', '월 납부금액'].map(label => (
                      <p key={label} className="px-4 py-2 text-[12px] text-kb-text-muted text-center">{label}</p>
                    ))}
                  </div>
                  <div className="grid grid-cols-3 divide-x divide-kb-primary-border">
                    {[calcResult.principal, calcResult.interest, calcResult.total].map((val, i) => (
                      <p key={i} className="px-4 py-3 text-[14px] font-bold text-kb-text text-right">{fmtN(val)}원</p>
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

        {/* ===== 하단 안내 ===== */}
        <div className="flex gap-4">
          <div className="flex-1 border border-kb-primary-border rounded-xl p-6">
            <p className="text-[15px] font-bold text-kb-text mb-4">이용시간 안내</p>
            <div className="text-[13px] space-y-2">
              <p className="flex items-baseline gap-4">
                <span className="text-kb-text-body whitespace-nowrap flex-shrink-0">· 대출조회</span>
                <span className="text-kb-text-body">24시간, 365일</span>
              </p>
              <p className="flex items-baseline gap-4">
                <span className="text-kb-text-body whitespace-nowrap flex-shrink-0">· 거래내역 조회</span>
                <span className="text-kb-text-body">24시간&nbsp;
                  <span style={{ color: '#C05050' }}>(토요일 및 공휴일 제외)</span>
                </span>
              </p>
            </div>
          </div>
          <div className="flex-1 border border-kb-primary-border rounded-xl p-6">
            <p className="text-[15px] font-bold text-kb-text mb-4">대출가이드</p>
            <div className="text-[13px] space-y-2">
              <Link href="/products/loan/manage/rate" className="block text-kb-text-body hover:underline">· 가계대출금리</Link>
              <Link href="/products/loan/manage/rate-detail" className="block text-kb-text-body hover:underline">· 금리산정내역서</Link>
            </div>
          </div>
          <div className="flex-1 border border-kb-primary-border rounded-xl p-6">
            <p className="text-[15px] font-bold text-kb-text mb-4">KB부동산</p>
            <div className="text-[13px] space-y-2">
              <Link href="/products/loan/credit" className="block text-kb-text-body hover:underline">· 신용대출 상품 보기</Link>
              <Link href="/products/loan/mortgage" className="block text-kb-text-body hover:underline">· 담보대출 상품 보기</Link>
            </div>
          </div>
        </div>
      </div>
        </main>
      </div>
    </div>
  )
}
