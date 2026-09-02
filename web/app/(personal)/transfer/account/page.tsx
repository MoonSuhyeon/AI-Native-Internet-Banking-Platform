'use client'
import { KB_BORDER, KB_MINT, KB_PRIMARY, KB_PRIMARY_BG, KB_PRIMARY_BORDER, KB_PRIMARY_SURFACE, KB_TEXT_LIGHT } from '@/lib/theme'

import { useState, useEffect } from 'react'
import { useRouter, useSearchParams } from 'next/navigation'
import { MOCK_BANKS, formatNumber } from '@/lib/mock-data'
import TransferSidebar from '@/components/inquiry/TransferSidebar'
import FavoriteManageModal from '@/components/transfer/FavoriteManageModal'
import { fetchFavorites, type FavoriteTransfer, type FavoriteType } from '@/lib/banking-api'
import { fetchDepositAccountViewModels, getCurrentDepositCustomerId, DepositViewAccount, fetchTransactions, DepositTransaction } from '@/lib/deposit-api'

/**
 * 홈 "시연 2 · 위험 안내 받기" 로 들어왔을 때 미리 채우는 값과 조건 안내.
 *
 * <p>위험 안내는 신호 둘이 쌓여야 뜬다 — 짧은 시간 내 반복 이체(fds 기본값 5건
 * 초과)와 1천만원 이상 고액. 조건을 모르면 이 화면을 평범한 이체 폼으로 보고
 * 지나가므로, <b>안내를 이 화면에서</b> 한다. 홈 카드에 적으면 그 칸만 두 줄이
 * 되어 띠가 두꺼워지고, 정작 읽어야 할 자리에서는 다시 안 보인다.
 *
 * <p>받는 계좌는 시드된 데모 계좌이고 금액은 촬영 대본과 같은 값이다. 심사자가
 * 채워야 할 칸이 남아 있으면 그 자리에서 시연이 끊긴다.
 */
const DEMO_RISK_PRESET = {
  to: '001-2000-0000017',
  bank: 'AXful',
  amount: '12000000',
  notice: '소액을 여러 번 이체한 뒤 1천만원 이상을 시도하면 위험 안내가 표시됩니다.',
}

const AMOUNT_SHORTCUTS = ['100만', '50만', '10만', '5만', '1만', '전액', '정결']

const labelCell = "px-4 py-3 text-[13px] font-semibold text-kb-text whitespace-nowrap w-[130px]"
const valueCell = "px-4 py-3"
const inputCls  = "border rounded-lg px-3 py-1.5 text-[13px] outline-none focus:ring-1 transition-all"
const inputStyle = { borderColor: KB_BORDER }

export default function TransferAccountPage() {
  const router = useRouter()
  const searchParams = useSearchParams()
  const requestedFromAccount = searchParams.get('from') || ''
  const [activeRecipientTab, setActiveRecipientTab] = useState('최근입금계좌')
  const [fromAccount, setFromAccount] = useState('')
  const [toBank, setToBank]     = useState('AXful')
  const [toAccount, setToAccount] = useState('')
  const [amount, setAmount]     = useState('')
  const [password, setPassword] = useState('')
  const [showBankModal, setShowBankModal] = useState(false)
  const [bankTab, setBankTab]   = useState('은행')
  const [innerBankTab, setInnerBankTab] = useState<'own' | 'other'>('own')
  const [ownSubTab, setOwnSubTab] = useState<'myAccount' | 'direct'>('myAccount')
  const [accounts, setAccounts] = useState<DepositViewAccount[]>([])
  const [recentAccounts, setRecentAccounts] = useState<{ bank: string; name: string; number: string }[]>([])
  const [validationMessage, setValidationMessage] = useState('')
  const [demoNotice, setDemoNotice] = useState('')

  // 이체 즐겨찾기 — 자주쓰는계좌·단축이체. 등록/삭제 버튼에 핸들러가 없어
  // 두 탭 모두 영원히 "등록되어 있지 않습니다" 였다.
  const [favorites, setFavorites] = useState<Record<FavoriteType, FavoriteTransfer[]>>({
    FAVORITE_ACCOUNT: [], QUICK_TRANSFER: [],
  })
  const [favoriteModal, setFavoriteModal] = useState<FavoriteType | null>(null)

  useEffect(() => {
    const to   = searchParams.get('to')
    const bank = searchParams.get('bank')
    if (to)   setToAccount(to)
    if (bank) setToBank(bank)
    // 홈 "위험 안내 받기" 로 들어온 경우. 받는 곳·금액을 채우고 조건을 알린다.
    //
    // 탭까지 같이 옮긴다. 기본 탭("내 계좌")은 내 계좌 목록에서 고르는 select 라,
    // 값만 넣으면 목록에 없는 계좌라 화면에 아무것도 안 나타난다 — 심사자 눈에는
    // 받는 곳이 비어 있는 것과 같다.
    if (searchParams.get('demo') === 'risk') {
      setInnerBankTab('own')
      setOwnSubTab('direct')
      setToBank(DEMO_RISK_PRESET.bank)
      setToAccount(DEMO_RISK_PRESET.to)
      setAmount(DEMO_RISK_PRESET.amount)
      setDemoNotice(DEMO_RISK_PRESET.notice)
    }
  }, [searchParams])

  useEffect(() => {
    async function load() {
      let fallback: DepositViewAccount[] = []
      try { const raw = localStorage.getItem('joinedAccounts'); if (raw) fallback = JSON.parse(raw) as DepositViewAccount[] } catch {}
      let accs: DepositViewAccount[] = []
      try {
        const customerId = getCurrentDepositCustomerId()
        const fetched = await fetchDepositAccountViewModels(customerId)
        accs = fetched.length > 0 ? fetched : fallback
        setAccounts(accs)
        if (accs.length > 0) {
          // ?from= 으로 진입한 경우 해당 출금계좌 자동 선택 (입출금 계좌일 때만)
          const requested = requestedFromAccount
            ? accs.find(a =>
                a.id === requestedFromAccount ||
                a.number === requestedFromAccount ||
                String(a.apiAccountId) === requestedFromAccount)
            : undefined
          if (requested?.type === '입출금') {
            setFromAccount(requested.id)
          } else {
            const firstTransferable = accs.find(a => a.type === '입출금') ?? accs[0]
            setFromAccount(firstTransferable.id)
          }
        }
      } catch { setAccounts(fallback) }
      try {
        const txResults = await Promise.allSettled(
          accs.filter(a => a.apiAccountId).map(a => fetchTransactions({ accountId: a.apiAccountId }))
        )
        const txs = txResults.flatMap(r => r.status === 'fulfilled' ? r.value : [])
        const seen = new Set<string>()
        const recent = txs
          .filter((t: DepositTransaction) => t.transactionType === 'TRANSFER' && t.directionType === 'OUT' && t.counterpartyAccountNo)
          .reduce((acc: { bank: string; name: string; number: string }[], t: DepositTransaction) => {
            if (!seen.has(t.counterpartyAccountNo!)) {
              seen.add(t.counterpartyAccountNo!)
              acc.push({ bank: t.counterpartyBankName || 'AXful', name: t.counterpartyName || '수취인', number: t.counterpartyAccountNo! })
            }
            return acc
          }, [])
          .slice(0, 5)
        setRecentAccounts(recent)
      } catch {}
    }
    load()
  }, [requestedFromAccount])

  async function loadFavorites() {
    // 로그인 전이거나 서버가 없으면 빈 목록으로 둔다 — 이체 화면 자체가 막히면 안 된다.
    try {
      const [fav, quick] = await Promise.all([
        fetchFavorites('FAVORITE_ACCOUNT'),
        fetchFavorites('QUICK_TRANSFER'),
      ])
      setFavorites({ FAVORITE_ACCOUNT: fav, QUICK_TRANSFER: quick })
    } catch {
      setFavorites({ FAVORITE_ACCOUNT: [], QUICK_TRANSFER: [] })
    }
  }

  useEffect(() => {
    loadFavorites()
  }, [])

  // 입출금(이체 가능) 계좌만 출금계좌로 노출
  const transferableAccounts = accounts.filter(a => a.type === '입출금')
  const fromAcc = transferableAccounts.find(a => a.id === fromAccount) ?? transferableAccounts[0]
  const isFromAccountLocked = Boolean(requestedFromAccount) && Boolean(fromAcc)

  function handleAmountShortcut(label: string) {
    const map: Record<string, number> = { '100만': 1_000_000, '50만': 500_000, '10만': 100_000, '5만': 50_000, '1만': 10_000 }
    if (label === '전액') {
      setAmount(String(fromAcc?.availableBalance ?? 0))
    } else if (label === '정결') {
      const n = parseInt(amount.replace(/,/g, '')) || 0
      setAmount(String(Math.floor(n / 10000) * 10000))
    } else if (map[label]) {
      setAmount(String((parseInt(amount.replace(/,/g, '')) || 0) + map[label]))
    }
  }

  function handleConfirm() {
    const amountNum = parseInt(amount.replace(/,/g, '')) || 0
    setValidationMessage('')
    if (!fromAcc) {
      setValidationMessage('출금계좌를 선택해 주세요.')
      return
    }
    if (!toAccount) {
      setValidationMessage('입금계좌번호를 입력해 주세요.')
      return
    }
    if (amountNum === 0) {
      setValidationMessage('이체금액을 입력해 주세요.')
      return
    }
    if (amountNum > fromAcc.availableBalance) {
      setValidationMessage('이체금액이 출금가능금액을 초과했습니다.')
      return
    }
    // 라우팅은 받는 은행 방향으로 판정한다 — 당행(AXful)이면 타인 계좌라도 INTERNAL,
    // 그 외 매핑된 은행은 EXTERNAL. (백엔드가 toAccountNo로 입금계좌를 조회하므로 내 계좌목록에 없어도 안전)
    // 매핑 안 된 은행명(최근이체 등 외부 출처)은 INTERNAL 로 폴백하지 않고 명시 차단 —
    // 외부 이체가 내부로 오분류돼 타행 자금이 잘못 라우팅되는 것을 방지한다.
    const targetAccount = accounts.find(acc => acc.number === toAccount)
    const isOwnBank = toBank === 'AXful'
    const matchedBank = MOCK_BANKS.find(b => b.name === toBank)
    if (!isOwnBank && !matchedBank) {
      setValidationMessage('지원하지 않는 은행입니다. 기관을 다시 선택해 주세요.')
      return
    }
    const toBankCode = isOwnBank ? 'KB' : matchedBank!.code
    const transferType: 'INTERNAL' | 'EXTERNAL' = isOwnBank ? 'INTERNAL' : 'EXTERNAL'
    sessionStorage.setItem('pendingTransfer', JSON.stringify({
      fromAccountId: fromAcc?.apiAccountId,
      toAccountId: targetAccount?.apiAccountId,
      transferType,
      fromNumber: fromAcc?.number ?? '',
      fromName: fromAcc?.name ?? '',
      toBank,
      toBankCode,
      toAccount,
      amount: amountNum,
      receiverName: recentAccounts.find(r => r.number === toAccount)?.name ?? '수취인',
      fee: 0,
    }))
    router.push('/transfer/confirm')
  }

  const recipientCard = (acc: { bank: string; name: string; number: string }) => {
    const selected = toAccount === acc.number
    return (
      <button key={acc.number} onClick={() => { setToBank(acc.bank); setToAccount(acc.number) }}
        className="border rounded-xl px-4 py-3 text-left text-[12px] transition-colors"
        style={{
          minWidth: 160,
          borderColor: selected ? KB_PRIMARY : KB_PRIMARY_BORDER,
          backgroundColor: selected ? KB_PRIMARY_BG : 'white',
        }}>
        <p className="font-semibold text-kb-text">{acc.name}</p>
        <p className="text-kb-text-muted mt-0.5">{acc.bank}</p>
        <p className="text-kb-text-muted">{acc.number}</p>
      </button>
    )
  }

  return (
    <div className="max-w-kb-container mx-auto px-6">
      <div className="flex">
        <TransferSidebar />

        <main className="flex-1 pl-8 pt-6 pb-12">
          <h1 className="text-[22px] font-bold text-kb-text mb-5">계좌이체</h1>

          {demoNotice && (
            <div className="rounded-xl px-4 py-3 mb-5 flex items-start gap-2.5 text-[13px]"
              style={{ backgroundColor: KB_PRIMARY_BG, border: `1px solid ${KB_PRIMARY_BORDER}` }}>
              <svg viewBox="0 0 24 24" fill="none" className="w-4 h-4 flex-shrink-0 mt-0.5"
                stroke={KB_PRIMARY} strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round">
                <circle cx="12" cy="12" r="9" />
                <line x1="12" y1="8" x2="12" y2="13" />
                <line x1="12" y1="16" x2="12.01" y2="16" />
              </svg>
              <p className="text-kb-text-body leading-relaxed">{demoNotice}</p>
            </div>
          )}

          {/* 수취인 탭 */}
          <div className="rounded-xl overflow-hidden mb-5" style={{ border: `1px solid ${KB_PRIMARY_BORDER}` }}>
            <div className="flex border-b" style={{ borderColor: KB_PRIMARY_BORDER }}>
              {['최근입금계좌', '자주쓰는계좌', '내계좌', '단축이체'].map(tab => (
                <button key={tab} onClick={() => setActiveRecipientTab(tab)}
                  className="px-5 py-2.5 text-[13px] font-medium transition-colors"
                  style={activeRecipientTab === tab
                    ? { backgroundColor: 'white', color: KB_PRIMARY, fontWeight: 700, borderBottom: '2px solid #0D5C47' }
                    : { backgroundColor: KB_PRIMARY_SURFACE, color: KB_TEXT_LIGHT }}>
                  {tab}
                </button>
              ))}
            </div>

            {activeRecipientTab === '최근입금계좌' && (
              <div className="p-4">
                <p className="text-[12px] text-kb-text-muted mb-3">최근 3개월 이내 이체된 입금계좌입니다.</p>
                <div className="flex gap-3 flex-wrap">
                  {recentAccounts.length === 0
                    ? <p className="text-[13px] text-kb-text-muted py-4">최근 이체 내역이 없습니다.</p>
                    : recentAccounts.map(acc => recipientCard(acc))
                  }
                </div>
              </div>
            )}

            {activeRecipientTab === '자주쓰는계좌' && (
              <div className="p-4 flex flex-col items-center gap-3">
                {favorites.FAVORITE_ACCOUNT.length === 0 ? (
                  <p className="text-[13px] text-kb-text-muted pt-4">자주쓰는계좌가 등록되어 있지 않습니다.</p>
                ) : (
                  <div className="flex gap-3 flex-wrap self-stretch">
                    {favorites.FAVORITE_ACCOUNT.map(f =>
                      recipientCard({ bank: f.bankName, name: f.alias, number: f.accountNumber }))}
                  </div>
                )}
                <button onClick={() => setFavoriteModal('FAVORITE_ACCOUNT')}
                  className="mt-2 border rounded-lg px-6 py-2 text-[12px] transition-colors hover:bg-kb-primary-bg"
                  style={{ borderColor: KB_MINT, color: KB_PRIMARY }}>
                  자주쓰는계좌 등록/삭제
                </button>
              </div>
            )}

            {activeRecipientTab === '내계좌' && (
              <div className="p-4">
                <p className="text-[12px] mb-3" style={{ color: KB_PRIMARY }}>
                  AXful Bank 내 계좌로 이체 시 추가 인증 없이 이체 가능합니다.
                </p>
                <div className="flex gap-3 flex-wrap">
                  {accounts.map(acc => recipientCard({ bank: 'AXful', name: acc.name, number: acc.number }))}
                </div>
              </div>
            )}

            {activeRecipientTab === '단축이체' && (
              <div className="p-4 flex flex-col items-center gap-3">
                {favorites.QUICK_TRANSFER.length === 0 ? (
                  <p className="text-[13px] text-kb-text-muted pt-4">단축이체가 등록되어 있지 않습니다.</p>
                ) : (
                  <div className="flex gap-3 flex-wrap self-stretch">
                    {favorites.QUICK_TRANSFER.map(f => {
                      const selected = toAccount === f.accountNumber
                      return (
                        <button key={f.favoriteId}
                          onClick={() => {
                            // 단축이체는 금액까지 미리 정해 둔 것이라 금액도 같이 채운다.
                            setToBank(f.bankName)
                            setToAccount(f.accountNumber)
                            if (f.amount != null) setAmount(String(f.amount))
                          }}
                          className="border rounded-xl px-4 py-3 text-left text-[12px] transition-colors"
                          style={{
                            minWidth: 160,
                            borderColor: selected ? KB_PRIMARY : KB_PRIMARY_BORDER,
                            backgroundColor: selected ? KB_PRIMARY_BG : 'white',
                          }}>
                          <p className="font-semibold text-kb-text">{f.alias}</p>
                          <p className="text-kb-text-muted mt-0.5">{f.bankName} {f.accountNumber}</p>
                          <p className="font-semibold" style={{ color: KB_PRIMARY }}>
                            {formatNumber(f.amount ?? 0)}원
                          </p>
                        </button>
                      )
                    })}
                  </div>
                )}
                <button onClick={() => setFavoriteModal('QUICK_TRANSFER')}
                  className="mt-2 border rounded-lg px-6 py-2 text-[12px] transition-colors hover:bg-kb-primary-bg"
                  style={{ borderColor: KB_MINT, color: KB_PRIMARY }}>
                  단축이체 등록/삭제
                </button>
              </div>
            )}
          </div>

          {/* 이체 폼 */}
          <div className="rounded-xl overflow-hidden mb-6" style={{ border: `1px solid ${KB_PRIMARY_BORDER}` }}>
            <table className="w-full text-[13px]">
              <tbody>
                <tr style={{ borderBottom: `1px solid ${KB_PRIMARY_BORDER}` }}>
                  <td className={labelCell} style={{ backgroundColor: KB_PRIMARY_BG }}>출금계좌번호</td>
                  <td className={valueCell}>
                    {isFromAccountLocked && fromAcc ? (
                      <input type="text" readOnly
                        value={`${fromAcc.number} (${fromAcc.name})`}
                        className={inputCls + " w-[280px]"} style={{ ...inputStyle, backgroundColor: KB_PRIMARY_SURFACE }} />
                    ) : (
                      <select value={fromAccount} onChange={e => setFromAccount(e.target.value)}
                        className={inputCls + " w-[280px]"} style={inputStyle}>
                        {transferableAccounts.map(a => <option key={a.id} value={a.id}>{a.number} ({a.name})</option>)}
                      </select>
                    )}
                    {fromAcc && (
                      <span className="ml-3 text-[12px] text-kb-text-muted">출금가능 {formatNumber(fromAcc.availableBalance)}원</span>
                    )}
                  </td>
                </tr>
                <tr style={{ borderBottom: `1px solid ${KB_PRIMARY_BORDER}` }}>
                  <td className={labelCell + " align-top pt-4"} style={{ backgroundColor: KB_PRIMARY_BG }}>입금계좌</td>
                  <td className={valueCell}>
                    {/* 당행 / 타행 토글 */}
                    <div className="inline-flex rounded-lg overflow-hidden mb-3" style={{ border: `1px solid ${KB_PRIMARY}` }}>
                      <button type="button"
                        onClick={() => { setInnerBankTab('own'); setOwnSubTab('myAccount'); setToBank('AXful'); setToAccount('') }}
                        className="px-6 py-1.5 text-[13px] font-bold transition-colors"
                        style={innerBankTab === 'own' ? { backgroundColor: KB_PRIMARY, color: 'white' } : { backgroundColor: 'white', color: KB_PRIMARY }}>
                        당행
                      </button>
                      <button type="button"
                        onClick={() => { setInnerBankTab('other'); setToBank(''); setToAccount('') }}
                        className="px-6 py-1.5 text-[13px] font-bold transition-colors"
                        style={innerBankTab === 'other' ? { backgroundColor: KB_PRIMARY, color: 'white' } : { backgroundColor: 'white', color: KB_PRIMARY }}>
                        타행
                      </button>
                    </div>

                    {innerBankTab === 'own' ? (
                      <div>
                        {/* 내 계좌 / 직접 입력 서브 탭 */}
                        <div className="inline-flex rounded-lg overflow-hidden mb-3" style={{ border: `1px solid ${KB_PRIMARY_BORDER}` }}>
                          <button type="button"
                            onClick={() => { setOwnSubTab('myAccount'); setToBank('AXful'); setToAccount('') }}
                            className="px-5 py-1 text-[12px] font-medium transition-colors"
                            style={ownSubTab === 'myAccount' ? { backgroundColor: KB_PRIMARY, color: 'white' } : { backgroundColor: 'white', color: KB_PRIMARY }}>
                            내 계좌
                          </button>
                          <button type="button"
                            onClick={() => { setOwnSubTab('direct'); setToBank('AXful'); setToAccount('') }}
                            className="px-5 py-1 text-[12px] font-medium transition-colors"
                            style={ownSubTab === 'direct' ? { backgroundColor: KB_PRIMARY, color: 'white' } : { backgroundColor: 'white', color: KB_PRIMARY }}>
                            계좌번호 직접 입력
                          </button>
                        </div>
                        {ownSubTab === 'myAccount' ? (
                          <select value={toAccount} onChange={e => { setToBank('AXful'); setToAccount(e.target.value) }}
                            className={inputCls + " w-[300px] block"} style={inputStyle}>
                            <option value="">입금받을 계좌 선택</option>
                            {accounts.filter(a => a.id !== fromAcc?.id).map(a => (
                              <option key={a.id} value={a.number}>{a.name} — {a.number}</option>
                            ))}
                          </select>
                        ) : (
                          <input type="text" value={toAccount} onChange={e => { setToBank('AXful'); setToAccount(e.target.value) }}
                            placeholder="AXful Bank 계좌번호 입력"
                            className={inputCls + " w-[300px] block"} style={inputStyle} />
                        )}
                      </div>
                    ) : (
                      <div className="flex items-center gap-2">
                        <input type="text" value={toBank} readOnly placeholder="은행 선택"
                          className={inputCls + " w-28"} style={{ ...inputStyle, backgroundColor: KB_PRIMARY_SURFACE }} />
                        <button onClick={() => setShowBankModal(true)}
                          className="border rounded-lg px-3 py-1.5 text-[12px] font-medium transition-colors hover:bg-kb-primary-bg"
                          style={{ borderColor: KB_MINT, color: KB_PRIMARY }}>
                          기관선택
                        </button>
                        <input type="text" value={toAccount} onChange={e => setToAccount(e.target.value)}
                          placeholder="계좌번호 입력" className={inputCls + " w-52"} style={inputStyle} />
                      </div>
                    )}
                  </td>
                </tr>
                <tr style={{ borderBottom: `1px solid ${KB_PRIMARY_BORDER}` }}>
                  <td className={labelCell + " align-top pt-4"} style={{ backgroundColor: KB_PRIMARY_BG }}>이체금액</td>
                  <td className={valueCell}>
                    <div className="flex gap-1.5 mb-2 flex-wrap">
                      {AMOUNT_SHORTCUTS.map(s => (
                        <button key={s} onClick={() => handleAmountShortcut(s)}
                          className="border rounded-lg px-3 py-1 text-[12px] font-medium transition-colors hover:bg-kb-primary-bg"
                          style={{ borderColor: KB_MINT, color: KB_PRIMARY }}>
                          {s}
                        </button>
                      ))}
                    </div>
                    <div className="flex items-center gap-2">
                      <input type="text"
                        value={amount ? formatNumber(parseInt(amount.replace(/,/g,''))||0) : ''}
                        onChange={e => setAmount(e.target.value.replace(/,/g,''))}
                        placeholder="0"
                        className={inputCls + " w-52 text-right"}
                        style={{
                          ...inputStyle,
                          borderColor: fromAcc && (parseInt(amount.replace(/,/g,''))||0) > fromAcc.availableBalance ? '#EF4444' : inputStyle.borderColor,
                        }} />
                      <span className="text-[13px] text-kb-text-muted">원</span>
                    </div>
                    {fromAcc && (parseInt(amount.replace(/,/g,''))||0) > fromAcc.availableBalance && (
                      <p className="mt-1 text-[12px] text-red-500">
                        출금가능금액({formatNumber(fromAcc.availableBalance)}원)을 초과했습니다.
                      </p>
                    )}
                  </td>
                </tr>
                <tr>
                  <td className={labelCell} style={{ backgroundColor: KB_PRIMARY_BG }}>계좌비밀번호</td>
                  <td className={valueCell}>
                    <input type="password" value={password} onChange={e => setPassword(e.target.value)}
                      maxLength={4} placeholder="4자리 입력"
                      className={inputCls + " w-28"} style={inputStyle} />
                  </td>
                </tr>
              </tbody>
            </table>
          </div>

          {/* 확인 버튼 */}
          <div className="flex flex-col items-center gap-3">
            {validationMessage && (
              <p className="text-[13px] font-medium text-kb-red">{validationMessage}</p>
            )}
            <button onClick={handleConfirm}
              className="px-24 py-3 text-[15px] font-bold text-white rounded-xl hover:opacity-85 transition-opacity"
              style={{ backgroundColor: KB_PRIMARY }}>
              확인
            </button>
          </div>
        </main>
      </div>

      {/* 기관선택 모달 */}
      {showBankModal && (
        <div className="fixed inset-0 bg-black/40 z-[200] flex items-center justify-center">
          <div className="bg-white rounded-2xl w-[520px] shadow-2xl overflow-hidden">
            <div className="flex items-center justify-between px-5 py-4" style={{ backgroundColor: KB_PRIMARY }}>
              <span className="font-bold text-[15px] text-white">기관선택</span>
              <button onClick={() => setShowBankModal(false)} className="text-white text-xl leading-none hover:opacity-75">✕</button>
            </div>
            <div className="p-5">
              <div className="flex border-b mb-4" style={{ borderColor: KB_PRIMARY_BORDER }}>
                {['은행', '증권사', '국세납부', '지방세입납부'].map(t => (
                  <button key={t} onClick={() => setBankTab(t)}
                    className="px-5 py-2 text-[13px] border-b-2 -mb-px transition-colors"
                    style={bankTab === t
                      ? { borderColor: KB_PRIMARY, color: KB_PRIMARY, fontWeight: 700 }
                      : { borderColor: 'transparent', color: KB_TEXT_LIGHT }}>
                    {t}
                  </button>
                ))}
              </div>
              <div className="grid grid-cols-3 gap-2">
                {MOCK_BANKS.map(bank => (
                  <button key={bank.code}
                    onClick={() => { setToBank(bank.name); setShowBankModal(false) }}
                    className="flex items-center gap-2 px-3 py-2 border rounded-lg text-[13px] text-kb-text-body transition-colors hover:bg-kb-primary-bg text-left"
                    style={{ borderColor: KB_PRIMARY_BORDER }}>
                    <span className="w-5 h-5 rounded-full flex-shrink-0" style={{ backgroundColor: KB_PRIMARY_BORDER }} />
                    {bank.name}
                  </button>
                ))}
              </div>
              <div className="mt-5 flex justify-center">
                <button onClick={() => setShowBankModal(false)}
                  className="border rounded-lg px-12 py-2 text-[13px] font-medium transition-colors hover:bg-kb-primary-bg"
                  style={{ borderColor: KB_MINT, color: KB_PRIMARY }}>
                  닫기
                </button>
              </div>
            </div>
          </div>
        </div>
      )}

      {favoriteModal && (
        <FavoriteManageModal
          type={favoriteModal}
          onClose={() => setFavoriteModal(null)}
          onChanged={loadFavorites}
        />
      )}
    </div>
  )
}
