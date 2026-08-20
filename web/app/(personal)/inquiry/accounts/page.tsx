'use client'
import { KB_BORDER, KB_DANGER, KB_MINT, KB_PRIMARY, KB_PRIMARY_BG, KB_PRIMARY_BORDER, KB_PRIMARY_SURFACE } from '@/lib/theme'

import Link from 'next/link'
import { useState, useEffect } from 'react'
import { formatNumber, type Account } from '@/lib/mock-data'
import InquirySidebar from '@/components/inquiry/InquirySidebar'
import { fetchDepositAccountViewModels, getCurrentDepositCustomerId, type DepositViewAccount } from '@/lib/deposit-api'
import SavingsPaymentStatusModal from '@/components/accounts/SavingsPaymentStatusModal'

const ACCOUNT_TABS = ['예금', '대출', '전체계좌']

/**
 * 계좌관리 드롭다운.
 *
 * 갈 곳이 있는 것과 없는 것을 나눈다. 예전에는 넷 다 <button> 이었고 아무 데도
 * 가지 않았다 — 누를 수 있다고 알리기만 하고 아무 일도 하지 않는 것이, 없는 것보다
 * 나쁘다. 아직 화면이 없는 둘은 그렇다고 말한다(docs/OPEN_ITEMS.md).
 */
const MANAGEMENT_ITEMS: { label: string; href?: string; note?: string }[] = [
  { label: '계좌별명관리',    href: '/accounts' },
  { label: '출금계좌등록/삭제', href: '/banking/withdrawal-account' },
  { label: '한도제한해제 신청', note: '영업점 방문 또는 서류 제출이 필요합니다.' },
  { label: '계좌개설확인서',   note: '준비 중입니다.' },
]

function EmptyState({ message, subMessage, actionHref, actionLabel }: {
  message: string
  subMessage?: string
  actionHref?: string
  actionLabel?: string
}) {
  return (
    <div className="rounded-xl px-6 py-5 flex items-center gap-5" style={{ backgroundColor: KB_PRIMARY_SURFACE, border: `1px solid ${KB_PRIMARY_BORDER}` }}>
      <div className="flex-shrink-0">
        <svg viewBox="0 0 56 56" fill="none" className="w-12 h-12">
          <rect x="8" y="6" width="32" height="40" rx="2" fill="#E2F5EF" stroke="#5BC9A8" strokeWidth="1.5"/>
          <line x1="14" y1="16" x2="34" y2="16" stroke="#5BC9A8" strokeWidth="2"/>
          <line x1="14" y1="22" x2="34" y2="22" stroke="#5BC9A8" strokeWidth="2"/>
          <line x1="14" y1="28" x2="26" y2="28" stroke="#5BC9A8" strokeWidth="2"/>
          <circle cx="38" cy="40" r="9" fill="white" stroke="#5BC9A8" strokeWidth="2"/>
          <line x1="34" y1="36" x2="42" y2="44" stroke="#5BC9A8" strokeWidth="2"/>
          <line x1="42" y1="36" x2="34" y2="44" stroke="#5BC9A8" strokeWidth="2"/>
        </svg>
      </div>
      <div className="flex-1 min-w-0">
        <p className="text-[13px] font-semibold text-kb-text">{message}</p>
        {subMessage && <p className="text-[12px] text-kb-text-muted mt-0.5 leading-relaxed">{subMessage}</p>}
      </div>
      {actionHref && actionLabel && (
        <Link href={actionHref}
          className="flex-shrink-0 px-5 py-1.5 text-[13px] font-semibold text-white rounded-lg hover:opacity-85 transition-opacity whitespace-nowrap"
          style={{ backgroundColor: KB_PRIMARY }}>
          {actionLabel}
        </Link>
      )}
    </div>
  )
}

function SectionHeader({ dotColor, label, count, balance, open, onToggle }: {
  dotColor: string
  label: string
  count: number
  balance: string
  open: boolean
  onToggle: () => void
}) {
  return (
    <div className="flex items-center justify-between mb-3 pb-2" style={{ borderBottom: `2px solid ${KB_PRIMARY_BORDER}` }}>
      <div className="flex items-center gap-2">
        <span className={`w-2.5 h-2.5 rounded-full inline-block flex-shrink-0 ${dotColor}`} />
        <span className="text-[14px] font-bold text-kb-text">{label}</span>
        <span className="text-[13px] text-kb-text-muted">({count}계좌)</span>
        <span className="text-[14px] font-semibold" style={{ color: KB_PRIMARY }}>잔액 {balance}원</span>
      </div>
      <button onClick={onToggle} className="text-[12px] text-kb-text-muted hover:text-kb-text px-2">
        {open ? '˄' : '˅'}
      </button>
    </div>
  )
}

function normalizeAccountType(account: Account): Account['type'] {
  const ext = account as Account & { savingType?: string }
  // savingType(REGULAR/FREE)이 있으면 무조건 적금
  if (ext.savingType === 'REGULAR' || ext.savingType === 'FREE') return '적금'
  // type이 이미 올바르게 분류된 경우
  if (['입출금', '적금', '예금', '청약'].includes(account.type)) return account.type
  // 상품명 키워드 fallback
  if (account.name.includes('청약')) return '청약'
  if (account.name.includes('적금')) return '적금'
  if (account.name.includes('통장') || account.name.includes('자유')) return '입출금'
  return '예금'
}

export default function AccountsPage() {
  const [activeTab, setActiveTab] = useState('예금')
  const [balanceVisible, setBalanceVisible] = useState(true)
  const [mgmtOpen, setMgmtOpen] = useState<string | null>(null)
  // 납입현황 — 회차별 스케줄은 이미 쌓이고 있었는데 볼 길이 없었다.
  const [paymentStatusFor, setPaymentStatusFor] = useState<DepositViewAccount | null>(null)
  const [userName, setUserName] = useState<string | null>(null)
  const [checkingOpen, setCheckingOpen] = useState(true)
  const [regularSavingsOpen, setRegularSavingsOpen] = useState(true)
  const [freeSavingsOpen, setFreeSavingsOpen] = useState(true)
  const [depositOpen, setDepositOpen]           = useState(true)
  const [subscriptionOpen, setSubscriptionOpen] = useState(true)
  const [creditLoanOpen, setCreditLoanOpen]     = useState(true)
  const [mortgageLoanOpen, setMortgageLoanOpen] = useState(true)
  const [jeonseOpen, setJeonseOpen]             = useState(true)
  const [etcLoanOpen, setEtcLoanOpen]           = useState(true)
  const [allDepOpen, setAllDepOpen] = useState(true)
  const [allLoanAllOpen, setAllLoanAllOpen] = useState(true)
  const [joinedAccounts, setJoinedAccounts] = useState<DepositViewAccount[]>([])
  const [accountOverrides, setAccountOverrides] = useState<Record<string, number>>({})

  useEffect(() => {
    try {
      const stored = localStorage.getItem('user')
      if (stored) setUserName(JSON.parse(stored).name)
    } catch {}
    let fallbackAccounts: DepositViewAccount[] = []
    try {
      const raw = localStorage.getItem('joinedAccounts')
      if (raw) {
        const parsed = (JSON.parse(raw) as DepositViewAccount[]).map(a => ({ ...a, type: normalizeAccountType(a) }))
        fallbackAccounts = parsed
        localStorage.setItem('joinedAccounts', JSON.stringify(parsed))
      }
    } catch {}
    try {
      const raw = localStorage.getItem('accountOverrides')
      if (raw) setAccountOverrides(JSON.parse(raw))
    } catch {}

    let cancelled = false
    async function load() {
      try {
        const api = await fetchDepositAccountViewModels(getCurrentDepositCustomerId())
        if (!cancelled) setJoinedAccounts(api.length > 0 ? api : fallbackAccounts)
      } catch {
        if (!cancelled) setJoinedAccounts(fallbackAccounts)
      }
    }
    load()
    return () => { cancelled = true }
  }, [])

  const now = new Date()
  const datetime = `${now.getFullYear()}.${String(now.getMonth()+1).padStart(2,'0')}.${String(now.getDate()).padStart(2,'0')} ${String(now.getHours()).padStart(2,'0')}:${String(now.getMinutes()).padStart(2,'0')}:${String(now.getSeconds()).padStart(2,'0')}`

  const allAccounts = [...joinedAccounts].map(a => ({
    ...a,
    type: normalizeAccountType(a),
    balance: a.balance + (accountOverrides[a.id] || 0),
    availableBalance: a.availableBalance + (accountOverrides[a.id] || 0),
  }))
  const pureDepositAccounts   = allAccounts.filter(a => a.type === '예금')
  const savingsAccounts       = allAccounts.filter(a => a.type === '적금')
  const regularSavingsAccounts = savingsAccounts.filter(
    a => (a as Account & { savingType?: string }).savingType === 'REGULAR'
  )
  const freeSavingsAccounts    = savingsAccounts.filter(
    a => (a as Account & { savingType?: string }).savingType === 'FREE'
  )
  const checkingAccounts      = allAccounts.filter(a => a.type === '입출금')
  const subscriptionAccounts  = allAccounts.filter(a => a.type === '청약')
  const depositTabAccounts    = allAccounts.filter(a => ['예금', '입출금', '적금', '청약'].includes(a.type))
  const depositTabBalance   = depositTabAccounts.reduce((s, a) => s + a.balance, 0)
  const depositTabCount     = depositTabAccounts.length
  const totalBalance        = depositTabBalance

  const bal = (n: number) => balanceVisible ? formatNumber(n) : '●●●●●●●'

  const accountCard = (account: DepositViewAccount, buttons: React.ReactNode) => (
    <div key={account.id} className="rounded-xl p-5 mb-3 bg-white shadow-sm" style={{ border: `1px solid ${KB_PRIMARY_BORDER}` }}>
      <div className="flex items-start justify-between gap-4">
        <div className="flex-1 min-w-0">
          <div className="flex items-center gap-2 mb-1">
            <Link href={`/inquiry/transactions`} className="text-[14px] font-bold hover:underline" style={{ color: KB_PRIMARY }}>
              {account.number}
            </Link>
            {account.badge && (
              <span className="text-[11px] px-2 py-0.5 rounded-full" style={{ backgroundColor: KB_PRIMARY_BORDER, color: KB_PRIMARY }}>
                {account.badge}
              </span>
            )}
          </div>
          <p className="text-[12px] text-kb-text-muted mb-2">{account.name}</p>
          {(account.createdAt || account.maturityDate) && (
            <div className="flex gap-4 text-[12px] text-kb-text-muted mb-2">
              {account.createdAt && <span>신규일 {account.createdAt}</span>}
              {account.maturityDate && <span>만기일 {account.maturityDate}</span>}
            </div>
          )}
          <p className="text-[13px] text-kb-text">
            잔액 <span className="font-bold text-[17px]">{bal(account.balance)}</span>원
          </p>
        </div>
        <div className="flex-shrink-0">{buttons}</div>
      </div>
    </div>
  )

  return (
    <div className="max-w-kb-container mx-auto px-6">
      <div className="flex">
        <InquirySidebar />

        <main className="flex-1 pl-8 pt-6 pb-12">

          {/* 제목 */}
          <h1 className="text-[22px] font-bold text-kb-text mb-4">AXful Bank 계좌조회</h1>

          {/* 탭 */}
          <div className="flex border-b mb-4" style={{ borderColor: KB_PRIMARY_BORDER }}>
            {ACCOUNT_TABS.map(tab => (
              <button key={tab} onClick={() => setActiveTab(tab)}
                className={`px-6 py-2.5 text-[14px] font-medium border-b-2 -mb-px transition-colors ${
                  activeTab === tab ? 'font-bold' : 'border-transparent text-kb-text-muted hover:text-kb-text'
                }`}
                style={activeTab === tab ? { borderColor: KB_PRIMARY, color: KB_PRIMARY } : {}}>
                {tab}
              </button>
            ))}
          </div>

          {/* 조회기준 + 인사 + 잔액 */}
          <p className="text-[12px] text-kb-text-muted mb-4">조회기준일시 : {datetime}</p>

          <div className="flex items-center justify-between mb-6 rounded-xl px-6 py-4" style={{ backgroundColor: KB_PRIMARY_SURFACE, border: `1px solid ${KB_PRIMARY_BORDER}` }}>
            <p className="text-[16px] font-bold text-kb-text">{userName ? `${userName} 고객님` : '고객님'}</p>
            <div className="text-right">
              <div className="flex items-center justify-end gap-2 mb-1">
                <span className="text-[12px] text-kb-text-muted">잔액보기</span>
                <button
                  onClick={() => setBalanceVisible(v => !v)}
                  className="relative w-10 h-5 rounded-full transition-colors"
                  style={{ backgroundColor: balanceVisible ? KB_PRIMARY : KB_BORDER }}
                >
                  <span className={`absolute top-0.5 w-4 h-4 bg-white rounded-full shadow transition-transform ${balanceVisible ? 'translate-x-5' : 'translate-x-0.5'}`} />
                  <span className={`absolute text-[9px] font-bold text-white ${balanceVisible ? 'left-1.5 top-0.5' : 'right-1 top-0.5'}`}>
                    {balanceVisible ? 'ON' : 'OFF'}
                  </span>
                </button>
              </div>
              <p className="text-[12px] text-kb-text-muted">총 잔액(예금)</p>
              <p className="text-[22px] font-bold" style={{ color: KB_PRIMARY }}>{bal(totalBalance)}원</p>
            </div>
          </div>

          {/* ── 예금 탭 ── */}
          {activeTab === '예금' && (
            <>
              <div className="flex items-center justify-between mb-5">
                <p className="text-[14px] font-bold text-kb-text">
                  총 예금 잔액{' '}
                  <span className="text-[18px] font-bold" style={{ color: KB_PRIMARY }}>{bal(depositTabBalance)}</span>원
                  <span className="text-kb-text-muted font-normal text-[13px] ml-1">({depositTabCount}계좌)</span>
                </p>
              </div>

              {/* 예금 */}
              <div className="mb-6">
                <SectionHeader dotColor="bg-kb-mint" label="예금"
                  count={pureDepositAccounts.length}
                  balance={bal(pureDepositAccounts.reduce((s, a) => s + a.balance, 0))}
                  open={depositOpen} onToggle={() => setDepositOpen(v => !v)} />
                {depositOpen && (
                  pureDepositAccounts.length === 0
                    ? <EmptyState message="가입된 예금 계좌가 없습니다." subMessage="여유자금을 예금에 맡겨보세요." actionHref="/products/deposit/list?tab=deposit" actionLabel="가입하기" />
                    : pureDepositAccounts.map(account =>
                        accountCard(account,
                          <div className="grid grid-cols-2 gap-1">
                            <Link href="/inquiry/transactions"
                              className="px-3 py-1.5 text-[12px] font-semibold rounded-lg border text-center transition-colors hover:bg-kb-primary-bg"
                              style={{ borderColor: KB_MINT, color: KB_PRIMARY }}>
                              조회
                            </Link>
                            <Link href={`/accounts/${account.id}`}
                              className="px-3 py-1.5 text-[12px] font-semibold rounded-lg border text-center transition-colors hover:bg-kb-primary-bg"
                              style={{ borderColor: KB_MINT, color: KB_PRIMARY }}>계좌관리</Link>
                            <Link href={`/products/deposit/inquiry/terminate?accountId=${account.id}`}
                              className="px-3 py-1.5 text-[12px] font-semibold rounded-lg border text-center transition-colors hover:bg-red-50"
                              style={{ borderColor: KB_DANGER, color: KB_DANGER }}>
                              해지
                            </Link>
                          </div>
                        )
                      )
                )}
              </div>

              {/* 정기적금 */}
              <div className="mb-6">
                <SectionHeader dotColor="bg-kb-mint" label="정기적금"
                  count={regularSavingsAccounts.length}
                  balance={bal(regularSavingsAccounts.reduce((s, a) => s + a.balance, 0))}
                  open={regularSavingsOpen} onToggle={() => setRegularSavingsOpen(v => !v)} />
                {regularSavingsOpen && (
                  regularSavingsAccounts.length === 0
                    ? <EmptyState message="가입된 정기적금 계좌가 없습니다." subMessage="매월 일정액을 납입하는 정기적금을 시작해보세요." actionHref="/products/deposit/list?tab=regular-savings" actionLabel="가입하기" />
                    : regularSavingsAccounts.map(account =>
                        accountCard(account,
                          <div className="grid grid-cols-2 gap-1">
                            <Link href="/inquiry/transactions"
                              className="px-3 py-1.5 text-[12px] font-semibold rounded-lg border text-center transition-colors hover:bg-kb-primary-bg"
                              style={{ borderColor: KB_MINT, color: KB_PRIMARY }}>
                              조회
                            </Link>
                            <button
                              onClick={() => setPaymentStatusFor(account)}
                              disabled={!account.apiAccountId}
                              title={account.apiAccountId ? undefined : '가입 직후에는 잠시 뒤 조회할 수 있습니다.'}
                              className="px-3 py-1.5 text-[12px] font-semibold rounded-lg border transition-colors hover:bg-kb-primary-bg disabled:opacity-40"
                              style={{ borderColor: KB_MINT, color: KB_PRIMARY }}>
                              납입현황
                            </button>
                            <Link href={`/accounts/${account.id}`}
                              className="px-3 py-1.5 text-[12px] font-semibold rounded-lg border text-center transition-colors hover:bg-kb-primary-bg"
                              style={{ borderColor: KB_MINT, color: KB_PRIMARY }}>
                              계좌관리
                            </Link>
                            <Link href={`/products/deposit/inquiry/terminate?accountId=${account.id}`}
                              className="px-3 py-1.5 text-[12px] font-semibold rounded-lg border text-center transition-colors hover:bg-red-50"
                              style={{ borderColor: KB_DANGER, color: KB_DANGER }}>
                              해지
                            </Link>
                          </div>
                        )
                      )
                )}
              </div>

              {/* 자유적금 */}
              <div className="mb-6">
                <SectionHeader dotColor="bg-kb-mint" label="자유적금"
                  count={freeSavingsAccounts.length}
                  balance={bal(freeSavingsAccounts.reduce((s, a) => s + a.balance, 0))}
                  open={freeSavingsOpen} onToggle={() => setFreeSavingsOpen(v => !v)} />
                {freeSavingsOpen && (
                  freeSavingsAccounts.length === 0
                    ? <EmptyState message="가입된 자유적금 계좌가 없습니다." subMessage="목돈 마련의 첫걸음, 자유적금을 시작해보세요." actionHref="/products/deposit/list?tab=free-savings" actionLabel="가입하기" />
                    : freeSavingsAccounts.map(account =>
                        accountCard(account,
                          <div className="grid grid-cols-2 gap-1">
                            <Link href="/inquiry/transactions"
                              className="px-3 py-1.5 text-[12px] font-semibold rounded-lg border text-center transition-colors hover:bg-kb-primary-bg"
                              style={{ borderColor: KB_MINT, color: KB_PRIMARY }}>
                              조회
                            </Link>
                            {/* 입금(적금 납입)과 계좌관리는 계좌 상세에서 한다.
                                여기에 같은 기능을 또 만들면 한쪽만 고치게 된다. */}
                            {['입금', '계좌관리'].map(label => (
                              <Link key={label} href={`/accounts/${account.id}`}
                                className="px-3 py-1.5 text-[12px] font-semibold rounded-lg border text-center transition-colors hover:bg-kb-primary-bg"
                                style={{ borderColor: KB_MINT, color: KB_PRIMARY }}>{label}</Link>
                            ))}
                            <Link href={`/products/deposit/inquiry/terminate?accountId=${account.id}`}
                              className="px-3 py-1.5 text-[12px] font-semibold rounded-lg border text-center transition-colors hover:bg-red-50"
                              style={{ borderColor: KB_DANGER, color: KB_DANGER }}>
                              해지
                            </Link>
                          </div>
                        )
                      )
                )}
              </div>

              {/* 입출금 */}
              <div className="mb-6">
                <SectionHeader dotColor="bg-kb-mint" label="입출금"
                  count={checkingAccounts.length}
                  balance={bal(checkingAccounts.reduce((s, a) => s + a.balance, 0))}
                  open={checkingOpen} onToggle={() => setCheckingOpen(v => !v)} />
                {checkingOpen && (checkingAccounts.length === 0
                ? <EmptyState message="가입된 입출금 계좌가 없습니다." subMessage="자유롭게 입출금할 수 있는 통장을 개설해보세요." actionHref="/products/deposit/list?tab=checking" actionLabel="가입하기" />
                : checkingAccounts.map(account =>
                  accountCard(account,
                    <div className="flex flex-col gap-1">
                      <div className="flex gap-1">
                        <Link href="/inquiry/transactions"
                          className="px-4 py-1.5 text-[12px] font-semibold rounded-lg border transition-colors hover:bg-kb-primary-bg"
                          style={{ borderColor: KB_PRIMARY, color: KB_PRIMARY }}>
                          조회
                        </Link>
                        <Link href={`/transfer/account?from=${account.number}`}
                          className="px-4 py-1.5 text-[12px] font-semibold text-white rounded-lg hover:opacity-85 transition-opacity"
                          style={{ backgroundColor: KB_PRIMARY }}>
                          이체
                        </Link>
                        <Link href={`/products/deposit/inquiry/terminate?accountId=${account.id}`}
                          className="px-4 py-1.5 text-[12px] font-semibold rounded-lg border transition-colors hover:bg-red-50"
                          style={{ borderColor: KB_DANGER, color: KB_DANGER }}>
                          해지
                        </Link>
                      </div>
                      <div className="relative">
                        <button
                          onClick={() => setMgmtOpen(mgmtOpen === account.id ? null : account.id)}
                          className="w-full px-4 py-1.5 text-[12px] font-semibold rounded-lg border transition-colors hover:bg-kb-primary-bg"
                          style={{ borderColor: KB_MINT, color: KB_PRIMARY }}>
                          계좌관리
                        </button>
                        {mgmtOpen === account.id && (
                          <div className="absolute right-0 top-full mt-1 bg-white rounded-xl shadow-lg z-50 w-[180px] py-1.5 overflow-hidden" style={{ border: `1px solid ${KB_PRIMARY_BORDER}` }}>
                            {MANAGEMENT_ITEMS.map(item => item.href ? (
                              <Link key={item.label}
                                href={item.label === '계좌별명관리' ? `/accounts/${account.id}` : item.href}
                                className="block w-full text-left px-4 py-2 text-[12px] text-kb-text-body hover:bg-kb-primary-bg">
                                {item.label}
                              </Link>
                            ) : (
                              <span key={item.label} title={item.note}
                                className="block w-full text-left px-4 py-2 text-[12px] text-kb-text-muted cursor-default">
                                {item.label}
                              </span>
                            ))}
                          </div>
                        )}
                      </div>
                    </div>
                  )
                ))}
              </div>

              {/* 청약 */}
              <div className="mb-8">
                <SectionHeader dotColor="bg-kb-mint" label="청약"
                  count={subscriptionAccounts.length}
                  balance={bal(subscriptionAccounts.reduce((s, a) => s + a.balance, 0))}
                  open={subscriptionOpen} onToggle={() => setSubscriptionOpen(v => !v)} />
                {subscriptionOpen && (
                  subscriptionAccounts.length === 0
                    ? <EmptyState message="가입된 청약 계좌가 없습니다." subMessage="내 집 마련의 첫걸음, 주택청약을 시작해보세요." actionHref="/products/deposit/list?tab=subscription" actionLabel="가입하기" />
                    : subscriptionAccounts.map(account =>
                        accountCard(account,
                          <div className="grid grid-cols-2 gap-1">
                            <Link href="/inquiry/transactions"
                              className="px-3 py-1.5 text-[12px] font-semibold rounded-lg border text-center transition-colors hover:bg-kb-primary-bg"
                              style={{ borderColor: KB_MINT, color: KB_PRIMARY }}>조회</Link>
                            <Link href={`/accounts/${account.id}`}
                              className="px-3 py-1.5 text-[12px] font-semibold rounded-lg border text-center transition-colors hover:bg-kb-primary-bg"
                              style={{ borderColor: KB_MINT, color: KB_PRIMARY }}>계좌관리</Link>
                            {/* '납입현황' 은 납입 이력 API 가 없어 뺐다 — 백엔드 신설 목록에 있다 */}
                            <Link href={`/products/deposit/inquiry/terminate?accountId=${account.id}`}
                              className="px-3 py-1.5 text-[12px] font-semibold rounded-lg border text-center transition-colors hover:bg-red-50"
                              style={{ borderColor: KB_DANGER, color: KB_DANGER }}>
                              해지
                            </Link>
                          </div>
                        )
                      )
                )}
              </div>
            </>
          )}

          {/* ── 대출 탭 ── */}
          {activeTab === '대출' && (
            <div className="mb-8 space-y-6">
              {[
                { label: '신용대출', open: creditLoanOpen, toggle: () => setCreditLoanOpen(v => !v), href: '/products/loan/credit' },
                { label: '담보대출', open: mortgageLoanOpen, toggle: () => setMortgageLoanOpen(v => !v), href: '/products/loan/mortgage' },
                { label: '전월세 대출', open: jeonseOpen, toggle: () => setJeonseOpen(v => !v), href: '/products/loan/jeonse' },
                { label: '기타 대출', open: etcLoanOpen, toggle: () => setEtcLoanOpen(v => !v), href: '/loans/apply' },
              ].map(({ label, open, toggle, href }) => (
                <div key={label}>
                  <SectionHeader dotColor="bg-kb-mint" label={label}
                    count={0} balance="0"
                    open={open} onToggle={toggle} />
                  {open && (
                    <EmptyState
                      message={`가입된 ${label} 내역이 없습니다.`}
                      subMessage="AXful Bank 대출 상품을 확인해보세요."
                      actionHref={href}
                      actionLabel="상품 보기" />
                  )}
                </div>
              ))}
            </div>
          )}

          {/* ── 전체계좌 탭 ── */}
          {activeTab === '전체계좌' && (
            <div className="mb-8 space-y-3">
              {[
                { label: '예금', open: allDepOpen, toggle: () => setAllDepOpen(v => !v), accounts: depositTabAccounts, cols: ['계좌번호', '계좌명', '신규일', '만기일', '잔액'] },
                { label: '대출', open: allLoanAllOpen, toggle: () => setAllLoanAllOpen(v => !v), accounts: [] as Account[], cols: ['계좌번호', '계좌명', '만기일', '대출잔액'] },
              ].map(({ label, open, toggle, accounts, cols }) => (
                <div key={label} className="rounded-xl overflow-hidden" style={{ border: `1px solid ${KB_PRIMARY_BORDER}` }}>
                  <button onClick={toggle}
                    className="w-full flex items-center justify-between px-5 py-3 text-left"
                    style={{ backgroundColor: KB_PRIMARY_BG }}>
                    <div className="flex items-center gap-2">
                      <span className="w-2.5 h-2.5 rounded-full bg-kb-mint inline-block" />
                      <span className="text-[14px] font-bold text-kb-text">{label}</span>
                    </div>
                    <span className="text-[12px] text-kb-text-muted">{open ? '˄' : '˅'}</span>
                  </button>
                  {open && (
                    <table className="w-full border-collapse">
                      <thead>
                        <tr style={{ backgroundColor: KB_PRIMARY_SURFACE }}>
                          {cols.map(col => (
                            <th key={col} className="px-4 py-2.5 text-[12px] font-semibold text-center" style={{ color: KB_PRIMARY, borderBottom: `1px solid ${KB_PRIMARY_BORDER}` }}>{col}</th>
                          ))}
                        </tr>
                      </thead>
                      <tbody>
                        {accounts.length === 0
                          ? <tr><td colSpan={cols.length} className="text-center py-8 text-[13px] text-kb-text-muted">조회된 계좌가 없습니다.</td></tr>
                          : accounts.map(acc => (
                            <tr key={acc.id} className="border-b hover:bg-kb-primary-surface" style={{ borderColor: KB_PRIMARY_BORDER }}>
                              <td className="px-4 py-3 text-[13px] text-center">
                                <Link href="/inquiry/transactions" className="hover:underline font-medium" style={{ color: KB_PRIMARY }}>{acc.number}</Link>
                              </td>
                              <td className="px-4 py-3 text-[13px] text-center text-kb-text">{acc.name}</td>
                              <td className="px-4 py-3 text-[13px] text-center text-kb-text-muted">{acc.createdAt}</td>
                              <td className="px-4 py-3 text-[13px] text-center text-kb-text-muted">{acc.maturityDate || '-'}</td>
                              <td className="px-4 py-3 text-[13px] text-right font-semibold" style={{ color: KB_PRIMARY }}>{bal(acc.balance)}원</td>
                            </tr>
                          ))
                        }
                      </tbody>
                    </table>
                  )}
                </div>
              ))}
            </div>
          )}

        </main>
      </div>

      {paymentStatusFor?.apiAccountId && (
        <SavingsPaymentStatusModal
          accountId={paymentStatusFor.apiAccountId}
          accountName={`${paymentStatusFor.name} · ${paymentStatusFor.number}`}
          onClose={() => setPaymentStatusFor(null)}
        />
      )}
    </div>
  )
}
