'use client'
import { KB_BORDER, KB_DANGER, KB_PRIMARY, KB_PRIMARY_BG, KB_PRIMARY_BORDER, KB_PRIMARY_SURFACE, KB_TEXT_BODY, KB_TEXT_LIGHT, KB_TEXT_MUTED } from '@/lib/theme'

import Link from 'next/link'
import { useEffect, useState, Suspense } from 'react'
import { useSearchParams } from 'next/navigation'
import DepositSidebar from '@/components/products/DepositSidebar'
import { formatNumber, MOCK_BANKS } from '@/lib/mock-data'
import {
  DepositViewAccount,
  fetchDepositAccountViewModels,
  getCurrentDepositCustomerId,
  terminateDepositContract,
} from '@/lib/deposit-api'
import MouseNumKeypad from '@/components/ui/MouseNumKeypad'
import TerminationEstimateModal from '@/components/accounts/TerminationEstimateModal'

type ReceiveMethod = 'internal' | 'external' | 'cash'

const STEP_LABELS = ['계좌조회/선택', '해지계좌 확인', '완료']
type Step = 1 | 2 | 3

export default function DepositTerminatePage() {
  return (
    <Suspense fallback={null}>
      <TerminatePageInner />
    </Suspense>
  )
}

function TerminatePageInner() {
  const searchParams = useSearchParams()
  const preselectedId = searchParams.get('accountId')

  const [step, setStep] = useState<Step>(preselectedId ? 2 : 1)
  const [selected, setSelected] = useState<DepositViewAccount | null>(null)
  // 해지상세조회 — 중도해지는 되돌릴 수 없는데 결과를 미리 볼 수 없었다.
  const [estimateFor, setEstimateFor] = useState<DepositViewAccount | null>(null)
  const [joinedAccounts, setJoinedAccounts] = useState<DepositViewAccount[]>([])
  const [password, setPassword] = useState('')
  const [mouseInput, setMouseInput] = useState(false)
  const [mousePassword, setMousePassword] = useState('')
  const [receiveMethod, setReceiveMethod] = useState<ReceiveMethod>('internal')
  const [depositNo, setDepositNo] = useState('')
  const [extBankCode, setExtBankCode] = useState('')
  const [extAccountNo, setExtAccountNo] = useState('')
  const [showBankModal, setShowBankModal] = useState(false)
  const [depositOpen,        setDepositOpen]        = useState(true)
  const [regularSavingsOpen, setRegularSavingsOpen] = useState(true)
  const [freeSavingsOpen,    setFreeSavingsOpen]    = useState(true)
  const [checkingOpen,       setCheckingOpen]       = useState(true)
  const [subscriptionOpen,   setSubscriptionOpen]   = useState(true)

  useEffect(() => {
    let fallbackAccounts: DepositViewAccount[] = []
    try {
      const raw = localStorage.getItem('joinedAccounts')
      if (raw) fallbackAccounts = JSON.parse(raw)
    } catch {}

    let cancelled = false
    async function loadAccounts() {
      try {
        const apiAccounts = await fetchDepositAccountViewModels(getCurrentDepositCustomerId())
        if (!cancelled) {
          const accounts = apiAccounts.length > 0 ? apiAccounts : fallbackAccounts
          setJoinedAccounts(accounts)
          if (preselectedId) {
            const found = accounts.find(a => String(a.id) === preselectedId)
            if (found) { setSelected(found); setStep(2) }
          }
        }
      } catch {
        if (!cancelled) {
          setJoinedAccounts(fallbackAccounts)
          if (preselectedId) {
            const found = fallbackAccounts.find(a => String(a.id) === preselectedId)
            if (found) { setSelected(found); setStep(2) }
          }
        }
      }
    }
    loadAccounts()
    return () => { cancelled = true }
  }, [])

  const allAccounts: DepositViewAccount[] = joinedAccounts
  const pureDepositAccounts    = allAccounts.filter(a => a.type === '예금')
  const regularSavingsAccounts = allAccounts.filter(a => a.type === '적금' && a.savingType === 'REGULAR')
  const freeSavingsAccounts    = allAccounts.filter(a => a.type === '적금' && a.savingType === 'FREE')
  const checkingAccounts       = allAccounts.filter(a => a.type === '입출금')
  const subscriptionAccounts   = allAccounts.filter(a => a.type === '청약')

  const totalBalance = allAccounts.reduce((s, a) => s + a.balance, 0)
  const totalCount   = allAccounts.length

  function handleSelect(acc: DepositViewAccount) {
    setSelected(acc)
    setStep(2)
  }

  async function handleConfirm() {
    if (!(mouseInput ? mousePassword : password)) { alert('해지계좌 비밀번호를 입력해주세요.'); return }
    if (receiveMethod === 'internal' && !depositNo) { alert('입금계좌를 선택해주세요.'); return }
    if (receiveMethod === 'external' && (!extBankCode || !extAccountNo)) { alert('은행과 계좌번호를 입력해주세요.'); return }
    if (selected) {
      try {
        if (selected.contractId) await terminateDepositContract(selected.contractId)
        const terminatedBalance = selected.balance
        let updated = joinedAccounts.filter(a => a.id !== selected.id)
        if (receiveMethod === 'internal' && depositNo) {
          // accountOverrides에 추가 — 계좌조회 페이지가 API 잔액 위에 합산
          const overrides = JSON.parse(localStorage.getItem('accountOverrides') || '{}')
          overrides[depositNo] = (overrides[depositNo] || 0) + terminatedBalance
          localStorage.setItem('accountOverrides', JSON.stringify(overrides))
          // in-memory도 반영 (localStorage fallback용)
          updated = updated.map(a => a.id === depositNo
            ? { ...a, balance: a.balance + terminatedBalance, availableBalance: a.availableBalance + terminatedBalance }
            : a)
        }
        localStorage.setItem('joinedAccounts', JSON.stringify(updated))
        setJoinedAccounts(updated)
      } catch {}
    }
    setStep(3)
  }

  function AccountRow({ acc }: { acc: DepositViewAccount }) {
    return (
      <tr className="border-b hover:bg-kb-primary-surface transition-colors" style={{ borderColor: KB_PRIMARY_BORDER }}>
        <td className="px-4 py-3.5">
          <p className="font-medium" style={{ color: KB_PRIMARY }}>{acc.number}</p>
          <p className="text-[11px] text-kb-text-muted mt-0.5">신규일 {acc.createdAt}</p>
        </td>
        <td className="px-4 py-3.5 text-kb-text">{acc.name}</td>
        <td className="px-4 py-3.5 text-right font-semibold" style={{ color: KB_PRIMARY }}>{formatNumber(acc.balance)}원</td>
        <td className="px-4 py-3.5 text-right">
          <div className="flex gap-1.5 justify-end">
            <button
              onClick={() => handleSelect(acc)}
              className="px-4 py-1.5 text-[12px] font-bold text-white rounded-lg hover:opacity-85 transition-opacity"
              style={{ backgroundColor: KB_PRIMARY }}>
              해지
            </button>
            <button
              onClick={() => setEstimateFor(acc)}
              disabled={!acc.apiAccountId}
              title={acc.apiAccountId ? undefined : '가입 직후에는 잠시 뒤 조회할 수 있습니다.'}
              className="px-4 py-1.5 text-[12px] font-medium rounded-lg border transition-colors hover:bg-kb-primary-bg disabled:opacity-40"
              style={{ borderColor: KB_PRIMARY_BORDER, color: KB_PRIMARY }}>
              해지상세조회
            </button>
          </div>
        </td>
      </tr>
    )
  }

  const rowStyle = "px-5 py-3.5 text-[13px]"
  const labelStyle = "font-semibold text-kb-text w-[150px] whitespace-nowrap"

  return (
    <div className="max-w-kb-container mx-auto px-6">
      <div className="flex">
        <DepositSidebar />

        <main className="flex-1 pl-8 pt-6 pb-12">
          <h1 className="text-[22px] font-bold text-kb-text mb-5">예금/적금 해지</h1>

          {/* 단계 표시 */}
          <div className="flex items-center gap-2 mb-6">
            {STEP_LABELS.map((label, i) => (
              <div key={i} className="flex items-center gap-2">
                {i > 0 && <span className="text-kb-text-muted">›</span>}
                <span
                  className="text-[13px]"
                  style={i + 1 === step
                    ? { color: KB_PRIMARY, fontWeight: 700, borderBottom: '2px solid #0D5C47', paddingBottom: '2px' }
                    : { color: KB_TEXT_LIGHT }}>
                  STEP {i + 1}. {label}
                </span>
              </div>
            ))}
          </div>

          {/* STEP 1 */}
          {step === 1 && (
            <>
              <div className="rounded-xl px-5 py-4 mb-5 text-[12px] space-y-1.5"
                style={{ backgroundColor: KB_PRIMARY_SURFACE, border: `1px solid ${KB_PRIMARY_BORDER}` }}>
                <p className="flex gap-1.5 text-kb-text-muted">
                  <span className="flex-shrink-0">·</span>
                  <span>인터넷으로 해지 가능한 예금은 우측 [도움말]을 참조하시기 바랍니다.</span>
                </p>
                <p className="flex gap-1.5 text-kb-text-muted">
                  <span className="flex-shrink-0">·</span>
                  <span>해지예상조회를 이용하여 해지 시 고객님의 해지계좌번호를 다시 한번 확인하여 선택하기 바랍니다.</span>
                </p>
                <p className="flex gap-1.5 font-medium" style={{ color: KB_DANGER }}>
                  <span className="flex-shrink-0">·</span>
                  <span>청약관련예금과 장기주택마련저축은 추가사항이 필요한 상품으로 인터넷뱅킹을 통한 해지가 제한됩니다.</span>
                </p>
              </div>

              {/* 총 잔액 요약 */}
              <div className="flex items-center justify-between mb-5 rounded-xl px-6 py-4"
                style={{ backgroundColor: KB_PRIMARY_SURFACE, border: `1px solid ${KB_PRIMARY_BORDER}` }}>
                <p className="text-[14px] font-bold text-kb-text">
                  총 예금 잔액{' '}
                  <span className="text-[18px]" style={{ color: KB_PRIMARY }}>{formatNumber(totalBalance)}</span>원
                  <span className="text-kb-text-muted font-normal text-[13px] ml-1">({totalCount}계좌)</span>
                </p>
              </div>

              {/* 섹션 공통 렌더 헬퍼 */}
              {(
                [
                  { label: '예금',    accounts: pureDepositAccounts,    open: depositOpen,        toggle: () => setDepositOpen(v => !v) },
                  { label: '정기적금', accounts: regularSavingsAccounts, open: regularSavingsOpen, toggle: () => setRegularSavingsOpen(v => !v) },
                  { label: '자유적금', accounts: freeSavingsAccounts,    open: freeSavingsOpen,    toggle: () => setFreeSavingsOpen(v => !v) },
                  { label: '입출금',  accounts: checkingAccounts,        open: checkingOpen,       toggle: () => setCheckingOpen(v => !v) },
                  { label: '청약',    accounts: subscriptionAccounts,    open: subscriptionOpen,   toggle: () => setSubscriptionOpen(v => !v) },
                ] as const
              ).map(({ label, accounts, open, toggle }) => (
                <div key={label} className="rounded-xl overflow-hidden mb-4" style={{ border: `1px solid ${KB_PRIMARY_BORDER}` }}>
                  <button
                    onClick={toggle}
                    className="flex items-center justify-between w-full px-5 py-3.5"
                    style={{ backgroundColor: KB_PRIMARY_BG, borderBottom: open ? `1px solid ${KB_PRIMARY_BORDER}` : 'none' }}>
                    <div className="flex items-center gap-2">
                      <span className="w-2 h-2 rounded-full inline-block" style={{ backgroundColor: KB_PRIMARY }} />
                      <span className="text-[14px] font-bold text-kb-text">{label}</span>
                      <span className="text-[13px] text-kb-text-muted">({accounts.length}계좌)</span>
                      <span className="text-[13px] font-semibold ml-1" style={{ color: KB_PRIMARY }}>
                        잔액 {formatNumber(accounts.reduce((s, a) => s + a.balance, 0))}원
                      </span>
                    </div>
                    <span className="text-[18px] text-kb-text-muted leading-none">{open ? '˄' : '˅'}</span>
                  </button>
                  {open && (
                    accounts.length === 0 ? (
                      <p className="px-5 py-5 text-[13px] text-kb-text-muted text-center">조회하실 내역이 없습니다.</p>
                    ) : (
                      <table className="w-full border-collapse text-[13px]">
                        <thead>
                          <tr style={{ backgroundColor: KB_PRIMARY_SURFACE }}>
                            {['계좌번호', '상품명', '잔액', ''].map((h, i) => (
                              <th key={i} className="px-4 py-2.5 text-[12px] font-semibold text-left"
                                style={{ borderBottom: `1px solid ${KB_PRIMARY_BORDER}`, color: KB_PRIMARY }}>{h}</th>
                            ))}
                          </tr>
                        </thead>
                        <tbody>
                          {accounts.map(acc => <AccountRow key={acc.id} acc={acc} />)}
                        </tbody>
                      </table>
                    )
                  )}
                </div>
              ))}
            </>
          )}

          {/* STEP 2 */}
          {step === 2 && selected && (
            <>
              <div className="rounded-xl px-5 py-4 mb-5 text-[12px]"
                style={{ backgroundColor: KB_PRIMARY_SURFACE, border: `1px solid ${KB_PRIMARY_BORDER}` }}>
                <p className="flex gap-1.5 text-kb-text-muted">
                  <span className="flex-shrink-0">·</span>
                  <span>해지 시 잔액이 선택하신 방법으로 지급됩니다.</span>
                </p>
              </div>

              <div className="rounded-xl overflow-hidden mb-4" style={{ border: `1px solid ${KB_PRIMARY_BORDER}` }}>
                <table className="w-full border-collapse text-[13px]">
                  <tbody>
                    {[
                      { label: '해지계좌번호', value: <span className="font-medium" style={{ color: KB_PRIMARY }}>{selected.number}</span> },
                      { label: '해지계좌명',   value: selected.name },
                      { label: '해지금액',     value: <span className="font-bold text-[15px]" style={{ color: KB_PRIMARY }}>{formatNumber(selected.balance)}원</span> },
                    ].map(({ label, value }, i) => (
                      <tr key={i} style={{ borderBottom: `1px solid ${KB_PRIMARY_BORDER}` }}>
                        <td className={`${rowStyle} ${labelStyle}`} style={{ backgroundColor: KB_PRIMARY_BG, width: 160 }}>{label}</td>
                        <td className={rowStyle}>{value}</td>
                      </tr>
                    ))}
                    <tr style={{ borderBottom: `1px solid ${KB_PRIMARY_BORDER}` }}>
                      <td className={`${rowStyle} ${labelStyle}`} style={{ backgroundColor: KB_PRIMARY_BG }}>해지계좌비밀번호</td>
                      <td className={rowStyle}>
                        <div className="flex flex-col gap-2">
                          {mouseInput ? (
                            <MouseNumKeypad value={mousePassword} onChange={setMousePassword} maxLength={4} dotCount={4} />
                          ) : (
                            <input
                              type="password"
                              value={password}
                              onChange={e => setPassword(e.target.value)}
                              maxLength={4}
                              placeholder="4자리 입력"
                              className="border rounded-lg px-3 py-1.5 text-[13px] w-28 outline-none focus:ring-1"
                              style={{ borderColor: KB_BORDER }}
                            />
                          )}
                          <label className="flex items-center gap-1.5 text-[12px] text-kb-text-muted cursor-pointer w-fit">
                            <input type="checkbox" checked={mouseInput} onChange={e => { setMouseInput(e.target.checked); setMousePassword(''); setPassword('') }} />
                            마우스로 입력
                          </label>
                        </div>
                      </td>
                    </tr>
                    {/* 지급 방법 선택 */}
                    <tr style={{ borderBottom: `1px solid ${KB_PRIMARY_BORDER}` }}>
                      <td className={`${rowStyle} ${labelStyle}`} style={{ backgroundColor: KB_PRIMARY_BG }}>지급 방법</td>
                      <td className={rowStyle}>
                        <div className="flex gap-3">
                          {([
                            { value: 'internal', label: '당행 계좌' },
                            { value: 'external', label: '타행 계좌' },
                            { value: 'cash',     label: '현금' },
                          ] as { value: ReceiveMethod; label: string }[]).map(opt => (
                            <label key={opt.value} className="flex items-center gap-1.5 text-[13px] cursor-pointer">
                              <input
                                type="radio"
                                name="receiveMethod"
                                value={opt.value}
                                checked={receiveMethod === opt.value}
                                onChange={() => { setReceiveMethod(opt.value); setDepositNo(''); setExtBankCode(''); setExtAccountNo('') }}
                              />
                              {opt.label}
                            </label>
                          ))}
                        </div>
                      </td>
                    </tr>
                    {/* 당행: 내 계좌 선택 */}
                    {receiveMethod === 'internal' && (
                      <tr style={{ borderBottom: `1px solid ${KB_PRIMARY_BORDER}` }}>
                        <td className={`${rowStyle} ${labelStyle}`} style={{ backgroundColor: KB_PRIMARY_BG }}>입금계좌</td>
                        <td className={rowStyle}>
                          <select
                            value={depositNo}
                            onChange={e => setDepositNo(e.target.value)}
                            className="border rounded-lg px-3 py-1.5 text-[13px] outline-none bg-white"
                            style={{ borderColor: KB_BORDER }}>
                            <option value="">- 선택 -</option>
                            {allAccounts.filter(a => a.id !== selected.id).map(a => (
                              <option key={a.id} value={a.id}>{a.number} ({a.name}) · {formatNumber(a.balance)}원</option>
                            ))}
                          </select>
                        </td>
                      </tr>
                    )}
                    {/* 타행: 은행 선택 + 계좌번호 입력 */}
                    {receiveMethod === 'external' && (
                      <>
                        <tr style={{ borderBottom: `1px solid ${KB_PRIMARY_BORDER}` }}>
                          <td className={`${rowStyle} ${labelStyle}`} style={{ backgroundColor: KB_PRIMARY_BG }}>은행</td>
                          <td className={rowStyle}>
                            <button
                              type="button"
                              onClick={() => setShowBankModal(true)}
                              className="border rounded-lg px-3 py-1.5 text-[13px] bg-white min-w-[120px] text-left"
                              style={{ borderColor: KB_BORDER, color: extBankCode ? '#111' : KB_TEXT_LIGHT }}>
                              {extBankCode ? MOCK_BANKS.find(b => b.code === extBankCode)?.name ?? '은행 선택' : '은행 선택'}
                            </button>
                          </td>
                        </tr>
                        <tr style={{ borderBottom: `1px solid ${KB_PRIMARY_BORDER}` }}>
                          <td className={`${rowStyle} ${labelStyle}`} style={{ backgroundColor: KB_PRIMARY_BG }}>계좌번호</td>
                          <td className={rowStyle}>
                            <input
                              type="text"
                              value={extAccountNo}
                              onChange={e => setExtAccountNo(e.target.value.replace(/\D/g, ''))}
                              placeholder="계좌번호 입력 (숫자만)"
                              className="border rounded-lg px-3 py-1.5 text-[13px] w-48 outline-none focus:ring-1"
                              style={{ borderColor: KB_BORDER }}
                            />
                          </td>
                        </tr>
                      </>
                    )}
                    {/* 현금: 안내 문구 */}
                    {receiveMethod === 'cash' && (
                      <tr style={{ borderBottom: `1px solid ${KB_PRIMARY_BORDER}` }}>
                        <td className={`${rowStyle} ${labelStyle}`} style={{ backgroundColor: KB_PRIMARY_BG }}>안내</td>
                        <td className={`${rowStyle} text-kb-text-muted text-[12px]`}>
                          해지 후 영업점 방문 시 현금으로 수령하실 수 있습니다.
                        </td>
                      </tr>
                    )}
                  </tbody>
                </table>
              </div>

              {/* 타행 은행 선택 모달 */}
              {showBankModal && (
                <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/40"
                  onClick={() => setShowBankModal(false)}>
                  <div className="bg-white rounded-2xl shadow-xl w-[360px] max-h-[70vh] overflow-y-auto p-5"
                    onClick={e => e.stopPropagation()}>
                    <h3 className="text-[15px] font-bold mb-4" style={{ color: KB_PRIMARY }}>은행 선택</h3>
                    <div className="grid grid-cols-3 gap-2">
                      {MOCK_BANKS.filter(b => b.code !== 'KB').map(bank => (
                        <button
                          key={bank.code}
                          onClick={() => { setExtBankCode(bank.code); setShowBankModal(false) }}
                          className="border rounded-lg py-2 text-[12px] font-medium transition-colors hover:bg-kb-primary-bg"
                          style={{
                            borderColor: extBankCode === bank.code ? KB_PRIMARY : KB_BORDER,
                            color: extBankCode === bank.code ? KB_PRIMARY : KB_TEXT_BODY,
                            backgroundColor: extBankCode === bank.code ? KB_PRIMARY_BG : 'white',
                          }}>
                          {bank.name}
                        </button>
                      ))}
                    </div>
                  </div>
                </div>
              )}

              <div className="flex justify-center gap-3">
                <button
                  onClick={() => setStep(1)}
                  className="border rounded-xl px-12 py-3 text-[14px] font-medium transition-colors hover:bg-kb-primary-bg"
                  style={{ borderColor: KB_BORDER, color: KB_TEXT_MUTED }}>
                  이전
                </button>
                <button
                  onClick={handleConfirm}
                  className="px-12 py-3 text-[14px] font-bold text-white rounded-xl hover:opacity-85 transition-opacity"
                  style={{ backgroundColor: KB_PRIMARY }}>
                  해지
                </button>
              </div>
            </>
          )}

          {/* STEP 3 */}
          {step === 3 && (
            <>
              <div className="rounded-xl p-6 mb-6 flex items-center gap-5"
                style={{ backgroundColor: KB_PRIMARY_BG, border: `1px solid ${KB_PRIMARY_BORDER}` }}>
                <div className="w-12 h-12 rounded-full flex items-center justify-center flex-shrink-0"
                  style={{ backgroundColor: KB_PRIMARY }}>
                  <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="white" strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round">
                    <polyline points="20 6 9 17 4 12"/>
                  </svg>
                </div>
                <div>
                  <p className="text-[16px] font-bold mb-1" style={{ color: KB_PRIMARY }}>해지가 완료되었습니다.</p>
                  <p className="text-[12px] text-kb-text-muted">해지 결과는 해지결과/내역 조회에서 확인하실 수 있습니다.</p>
                </div>
              </div>

              <div className="flex justify-center gap-3">
                <Link href="/products/deposit/inquiry/terminate-result"
                  className="px-8 py-2.5 text-[14px] font-bold text-white rounded-xl hover:opacity-85 transition-opacity"
                  style={{ backgroundColor: KB_PRIMARY }}>
                  해지결과 조회
                </Link>
                <Link href="/inquiry/accounts"
                  className="border rounded-xl px-8 py-2.5 text-[14px] font-medium transition-colors hover:bg-kb-primary-bg"
                  style={{ borderColor: KB_PRIMARY_BORDER, color: KB_PRIMARY }}>
                  계좌 조회
                </Link>
              </div>
            </>
          )}
        </main>
      </div>

      {estimateFor?.apiAccountId && (
        <TerminationEstimateModal
          accountId={estimateFor.apiAccountId}
          accountName={`${estimateFor.name} · ${estimateFor.number}`}
          onClose={() => setEstimateFor(null)}
        />
      )}
    </div>
  )
}
