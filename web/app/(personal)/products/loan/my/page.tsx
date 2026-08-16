'use client'
import { KB_DANGER, KB_PRIMARY, KB_PRIMARY_BG, KB_PRIMARY_BORDER, KB_PRIMARY_SURFACE } from '@/lib/theme'

import Link from 'next/link'
import { useEffect, useState } from 'react'
import LoanSidebar from '@/components/inquiry/LoanSidebar'
import AutoBreadcrumb from '@/components/layout/AutoBreadcrumb'
import { loanContractApi, getCustomerId } from '@/lib/loan-api'

type LoanContract = {
  cntrId: number
  cntrNo: string
  contractedAmount: number
  contractedPeriodMo: number
  totalRateBps: number
  rateTypeCd: string
  repaymentMethodCd: string
  cntrStatusCd: string
  cntrStartDate: string
  cntrEndDate: string
}

const STATUS = {
  SIGNED:    { label: '약정완료', bg: '#EFF6FF', color: '#1A56DB' },
  ACTIVE:    { label: '대출중',   bg: '#ECFDF5', color: KB_PRIMARY },
  CLOSED:    { label: '상환완료', bg: '#F3F4F6', color: '#6B7280' },
  CANCELLED: { label: '취소',     bg: '#F3F4F6', color: '#9CA3AF' },
}

const REPAY_LABEL: Record<string, string> = {
  EQUAL_PRINCIPAL_INTEREST: '원리금균등상환',
  EQUAL_PRINCIPAL:          '원금균등상환',
  BULLET:                   '만기일시상환',
  REVOLVING:                '한도대출',
}

const RATE_TYPE_LABEL: Record<string, string> = {
  FIXED: '고정금리', VARIABLE: '변동금리', MIXED: '혼합금리',
}

function calcMonthlyPayment(amount: number, bps: number, months: number, method: string): number {
  if (!amount || !months) return 0
  const r = bps / 100 / 100 / 12
  if (method === 'EQUAL_PRINCIPAL_INTEREST' && r > 0)
    return Math.round(amount * r / (1 - Math.pow(1 + r, -months)))
  if (method === 'EQUAL_PRINCIPAL')
    return Math.round(amount / months + amount * r)
  return 0
}

function calcProgress(start: string, end: string): number {
  if (!start || !end) return 0
  const s = new Date(start).getTime()
  const e = new Date(end).getTime()
  const n = Date.now()
  if (e <= s) return 100
  return Math.min(100, Math.max(0, Math.round((n - s) / (e - s) * 100)))
}

function calcRemainingMonths(end: string): number {
  if (!end) return 0
  const e = new Date(end)
  const n = new Date()
  return Math.max(0, (e.getFullYear() - n.getFullYear()) * 12 + e.getMonth() - n.getMonth())
}

function fmt(n: number) { return n.toLocaleString('ko-KR') }
function fmtDate(s: string) { return s?.replace(/-/g, '.') ?? '-' }

export default function MyLoanPage() {
  const [contracts, setContracts] = useState<LoanContract[]>([])
  const [loading, setLoading]     = useState(true)
  const [error, setError]         = useState('')

  useEffect(() => {
    const cid = getCustomerId()
    if (!cid) { setLoading(false); return }
    loanContractApi.list({ customerId: cid, size: 50 })
      .then(({ data: res }) => setContracts(res.data?.items ?? []))
      .catch(() => setError('대출 계약 정보를 불러오지 못했습니다.'))
      .finally(() => setLoading(false))
  }, [])

  const active           = contracts.filter(c => c.cntrStatusCd === 'ACTIVE')
  const totalDebt        = active.reduce((s, c) => s + c.contractedAmount, 0)
  const totalInterest    = active.reduce((s, c) => s + Math.round(c.contractedAmount * c.totalRateBps / 100 / 100 / 12), 0)
  const avgRate          = active.length > 0
    ? (active.reduce((s, c) => s + c.totalRateBps, 0) / active.length / 100).toFixed(2)
    : null

  return (
    <main className="pb-16">
      <div className="max-w-kb-container mx-auto px-6 pt-6">
        <AutoBreadcrumb as="/products/loan/manage/rate" leaf="내 대출 현황" />

        <div className="flex gap-8">
          <LoanSidebar />
          <div className="flex-1 min-w-0">
            <h1 className="text-[22px] font-bold text-kb-text mb-2">내 대출 현황</h1>
            <div className="border-t-2 mb-5" style={{ borderColor: KB_PRIMARY }} />

            {/* 요약 카드 */}
            {!loading && !error && contracts.length > 0 && (
              <div className="grid grid-cols-3 gap-3 mb-6">
                {[
                  {
                    label: '총 대출 잔액',
                    value: `${fmt(totalDebt)}원`,
                    sub: `전체 ${contracts.length}건 · 활성 ${active.length}건`,
                    icon: (
                      <svg viewBox="0 0 24 24" fill="none" className="w-5 h-5" stroke="#0D5C47" strokeWidth="1.8">
                        <rect x="2" y="5" width="20" height="14" rx="2"/>
                        <line x1="2" y1="10" x2="22" y2="10"/>
                        <line x1="6" y1="15" x2="10" y2="15"/>
                      </svg>
                    ),
                  },
                  {
                    label: '이번 달 예상 이자',
                    value: `${fmt(totalInterest)}원`,
                    sub: '활성 대출 월 이자 합계',
                    icon: (
                      <svg viewBox="0 0 24 24" fill="none" className="w-5 h-5" stroke="#0D5C47" strokeWidth="1.8">
                        <circle cx="12" cy="12" r="9"/>
                        <path d="M9 10.5h3.5a1.5 1.5 0 010 3H9m0 2h5"/>
                        <path d="M12 7.5V9m0 6v1.5"/>
                      </svg>
                    ),
                  },
                  {
                    label: '평균 적용금리',
                    value: avgRate ? `연 ${avgRate}%` : '-',
                    sub: '활성 대출 평균',
                    icon: (
                      <svg viewBox="0 0 24 24" fill="none" className="w-5 h-5" stroke="#0D5C47" strokeWidth="1.8">
                        <polyline points="22 7 13.5 15.5 8.5 10.5 2 17"/>
                        <polyline points="16 7 22 7 22 13"/>
                      </svg>
                    ),
                  },
                ].map(({ label, value, sub, icon }) => (
                  <div key={label} className="rounded-xl p-4"
                    style={{ border: `1px solid ${KB_PRIMARY_BORDER}`, backgroundColor: KB_PRIMARY_SURFACE }}>
                    <div className="flex items-center gap-2 mb-2">
                      <div className="w-8 h-8 rounded-full flex items-center justify-center flex-shrink-0"
                        style={{ backgroundColor: '#E8F7F3' }}>
                        {icon}
                      </div>
                      <span className="text-[12px] text-kb-text-muted">{label}</span>
                    </div>
                    <p className="text-[18px] font-bold text-kb-text">{value}</p>
                    <p className="text-[11px] text-kb-text-muted mt-0.5">{sub}</p>
                  </div>
                ))}
              </div>
            )}

            {/* 로딩 */}
            {loading && (
              <div className="py-12 text-center">
                <div className="inline-block w-7 h-7 border-2 border-t-transparent rounded-full animate-spin mb-3"
                  style={{ borderColor: KB_PRIMARY, borderTopColor: 'transparent' }} />
                <p className="text-[13px] text-kb-text-muted">불러오는 중...</p>
              </div>
            )}
            {error && <p className="text-[13px] py-10 text-center" style={{ color: KB_DANGER }}>{error}</p>}

            {/* 대출 카드 목록 */}
            {!loading && !error && contracts.map(c => {
              const st             = STATUS[c.cntrStatusCd as keyof typeof STATUS] ?? { label: c.cntrStatusCd, bg: '#F3F4F6', color: '#6B7280' }
              const isActive       = c.cntrStatusCd === 'ACTIVE'
              const monthlyPayment = calcMonthlyPayment(c.contractedAmount, c.totalRateBps, c.contractedPeriodMo, c.repaymentMethodCd)
              const progress       = calcProgress(c.cntrStartDate, c.cntrEndDate)
              const remaining      = calcRemainingMonths(c.cntrEndDate)

              return (
                <div key={c.cntrId} className="rounded-xl mb-4 overflow-hidden"
                  style={{ border: '1px solid #E2F5EF', borderLeft: `4px solid ${st.color}` }}>

                  {/* 카드 헤더 */}
                  <div className="px-6 py-4 flex items-start justify-between"
                    style={{ borderBottom: `1px solid ${KB_PRIMARY_BORDER}`, backgroundColor: isActive ? '#FAFFFE' : '#FAFAFA' }}>
                    <div>
                      <div className="flex items-center gap-2 mb-1">
                        <span className="text-[15px] font-bold text-kb-text">{c.cntrNo}</span>
                        <span className="text-[11px] font-bold px-2.5 py-0.5 rounded-full"
                          style={{ color: st.color, backgroundColor: st.bg }}>
                          {st.label}
                        </span>
                      </div>
                      <p className="text-[12px] text-kb-text-muted">
                        {REPAY_LABEL[c.repaymentMethodCd] ?? c.repaymentMethodCd}
                        {' · '}
                        {RATE_TYPE_LABEL[c.rateTypeCd] ?? c.rateTypeCd}
                      </p>
                    </div>
                    <div className="text-right">
                      <p className="text-[11px] text-kb-text-muted mb-0.5">대출금액</p>
                      <p className="text-[22px] font-bold" style={{ color: isActive ? KB_PRIMARY : '#6B7280' }}>
                        {fmt(c.contractedAmount)}원
                      </p>
                    </div>
                  </div>

                  {/* 카드 바디 */}
                  <div className="px-6 py-4">

                    {/* 정보 그리드 */}
                    <div className="grid grid-cols-4 gap-3 mb-4">
                      {[
                        { label: '적용금리',   value: `연 ${(c.totalRateBps / 100).toFixed(2)}%`, highlight: true },
                        { label: '대출기간',   value: `${c.contractedPeriodMo}개월` },
                        { label: '대출실행일', value: fmtDate(c.cntrStartDate) },
                        { label: '만기일',     value: fmtDate(c.cntrEndDate) },
                      ].map(({ label, value, highlight }) => (
                        <div key={label} className="rounded-lg px-3 py-2.5 text-center"
                          style={{ backgroundColor: highlight ? KB_PRIMARY_BG : '#F5F6F8' }}>
                          <p className="text-[11px] text-kb-text-muted mb-1">{label}</p>
                          <p className="text-[14px] font-bold"
                            style={{ color: highlight ? KB_PRIMARY : '#1F2937' }}>
                            {value}
                          </p>
                        </div>
                      ))}
                    </div>

                    {/* 진행률 바 */}
                    {isActive && (
                      <div className="mb-4">
                        <div className="flex justify-between text-[11px] text-kb-text-muted mb-1.5">
                          <span>대출 진행률 <span className="font-semibold text-kb-text">{progress}%</span></span>
                          <span>잔여 <span className="font-semibold text-kb-text">{remaining}개월</span></span>
                        </div>
                        <div className="h-2 rounded-full overflow-hidden" style={{ backgroundColor: KB_PRIMARY_BORDER }}>
                          <div className="h-full rounded-full transition-all duration-500"
                            style={{ width: `${progress}%`, backgroundColor: KB_PRIMARY }} />
                        </div>
                      </div>
                    )}

                    {/* 월 납부 예정액 */}
                    {isActive && monthlyPayment > 0 && (
                      <div className="rounded-lg px-4 py-3 mb-4 flex items-center justify-between"
                        style={{ backgroundColor: KB_PRIMARY_BG, border: `1px solid ${KB_PRIMARY_BORDER}` }}>
                        <div className="flex items-center gap-2">
                          <svg viewBox="0 0 20 20" fill="none" className="w-4 h-4 flex-shrink-0" stroke="#0D5C47" strokeWidth="1.8">
                            <rect x="3" y="4" width="14" height="13" rx="2"/>
                            <line x1="3" y1="8" x2="17" y2="8"/>
                            <line x1="7" y1="2" x2="7" y2="6"/>
                            <line x1="13" y1="2" x2="13" y2="6"/>
                          </svg>
                          <span className="text-[12px] font-medium" style={{ color: KB_PRIMARY }}>이번 달 납부 예정액</span>
                        </div>
                        <span className="text-[16px] font-bold" style={{ color: KB_PRIMARY }}>{fmt(monthlyPayment)}원</span>
                      </div>
                    )}

                    {/* 액션 버튼 */}
                    {isActive && (
                      <div className="flex gap-2 flex-wrap pt-1">
                        {[
                          { label: '이자납입',      href: '/products/loan/manage/payment' },
                          { label: '대출금상환',    href: '/products/loan/manage/repay' },
                          { label: '적용금리조회',  href: '/products/loan/manage/rate' },
                          { label: '기한연장',      href: '/products/loan/manage/extend' },
                          { label: '금리인하요구권', href: '/products/loan/manage/rate-cut' },
                        ].map(({ label, href }) => (
                          <Link key={label} href={`${href}?cntrId=${c.cntrId}`}
                            className="px-4 py-1.5 text-[12px] font-medium rounded-lg border transition-colors hover:bg-kb-primary-bg"
                            style={{ borderColor: KB_PRIMARY_BORDER, color: KB_PRIMARY }}>
                            {label}
                          </Link>
                        ))}
                      </div>
                    )}
                  </div>
                </div>
              )
            })}

            {/* 빈 상태 */}
            {!loading && !error && contracts.length === 0 && (
              <div className="rounded-xl px-6 py-14 text-center"
                style={{ backgroundColor: KB_PRIMARY_SURFACE, border: `1px solid ${KB_PRIMARY_BORDER}` }}>
                <div className="w-14 h-14 rounded-full flex items-center justify-center mx-auto mb-4"
                  style={{ backgroundColor: '#E8F7F3' }}>
                  <svg viewBox="0 0 24 24" fill="none" className="w-7 h-7" stroke="#0D5C47" strokeWidth="1.5">
                    <rect x="3" y="3" width="18" height="18" rx="2"/>
                    <path d="M3 9h18M9 21V9"/>
                  </svg>
                </div>
                <p className="text-[15px] font-semibold text-kb-text mb-1">보유 중인 대출이 없습니다.</p>
                <p className="text-[13px] text-kb-text-muted mb-5">AXful Bank의 다양한 대출 상품을 확인해 보세요.</p>
                <Link href="/products/loan/credit"
                  className="inline-block px-8 py-2.5 text-[13px] font-bold text-white rounded-xl hover:opacity-85"
                  style={{ backgroundColor: KB_PRIMARY }}>
                  대출 상품 보기
                </Link>
              </div>
            )}
          </div>
        </div>
      </div>
    </main>
  )
}
