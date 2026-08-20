'use client'

import { useEffect, useState } from 'react'

import { MOCK_BANKS } from '@/lib/mock-data'
import {
  deleteFavorite,
  fetchFavorites,
  registerFavorite,
  type FavoriteTransfer,
  type FavoriteType,
} from '@/lib/banking-api'
import { KB_BORDER, KB_PRIMARY, KB_TEXT_MUTED } from '@/lib/theme'

const TITLE: Record<FavoriteType, string> = {
  FAVORITE_ACCOUNT: '자주쓰는계좌 등록/삭제',
  QUICK_TRANSFER: '단축이체 등록/삭제',
}

function won(n: number) {
  return n.toLocaleString('ko-KR') + '원'
}

/**
 * 이체 즐겨찾기 관리.
 *
 * <p>이 자리에 "자주쓰는계좌 등록/삭제" 버튼이 있었지만 핸들러가 없어 눌러도 아무
 * 일이 없었다. 두 탭 모두 "등록되어 있지 않습니다" 만 보여 줬는데, 등록할 방법이
 * 없었으니 영원히 비어 있는 화면이었다.
 *
 * <p>단축이체는 금액까지 받는다. 금액이 없으면 목록에 보여도 보낼 수 없기 때문에
 * 서버가 거절한다 — 화면에서도 같은 규칙으로 막아 왕복을 줄인다.
 */
export default function FavoriteManageModal({
  type,
  onClose,
  onChanged,
}: {
  type: FavoriteType
  onClose: () => void
  /** 등록·삭제로 목록이 바뀌면 부모가 다시 읽도록 알린다. */
  onChanged: () => void
}) {
  const needsAmount = type === 'QUICK_TRANSFER'

  const [items, setItems] = useState<FavoriteTransfer[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')

  const [alias, setAlias] = useState('')
  const [bankCode, setBankCode] = useState(MOCK_BANKS[0]?.code ?? '')
  const [accountNumber, setAccountNumber] = useState('')
  const [holderName, setHolderName] = useState('')
  const [amount, setAmount] = useState('')
  const [saving, setSaving] = useState(false)

  async function load() {
    setLoading(true)
    try {
      setItems(await fetchFavorites(type))
      setError('')
    } catch {
      setError('목록을 불러오지 못했습니다. 잠시 후 다시 시도해 주세요.')
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => {
    load()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [type])

  async function submit() {
    const bank = MOCK_BANKS.find(b => b.code === bankCode)
    if (!alias.trim()) return setError('별칭을 입력해 주세요.')
    if (!accountNumber.trim()) return setError('계좌번호를 입력해 주세요.')
    if (!bank) return setError('은행을 선택해 주세요.')

    const amountValue = Number(amount.replace(/[^0-9]/g, ''))
    if (needsAmount && !(amountValue > 0)) {
      // 서버도 같은 이유로 막는다. 금액 없는 단축이체는 눌러도 보낼 수 없다.
      return setError('단축이체는 보낼 금액을 입력해 주세요.')
    }

    setSaving(true)
    try {
      await registerFavorite({
        favoriteType: type,
        alias: alias.trim(),
        bankCode: bank.code,
        bankName: bank.name,
        accountNumber: accountNumber.trim(),
        accountHolderName: holderName.trim() || undefined,
        amount: needsAmount ? amountValue : null,
      })
      setAlias(''); setAccountNumber(''); setHolderName(''); setAmount('')
      setError('')
      await load()
      onChanged()
    } catch (e: unknown) {
      const msg = (e as { response?: { data?: { message?: string } } })?.response?.data?.message
      setError(msg || '등록하지 못했습니다.')
    } finally {
      setSaving(false)
    }
  }

  async function remove(favoriteId: number) {
    try {
      await deleteFavorite(favoriteId)
      await load()
      onChanged()
    } catch {
      setError('삭제하지 못했습니다.')
    }
  }

  return (
    <div className="fixed inset-0 z-[200] flex items-center justify-center bg-black/40 p-4" onClick={onClose}>
      <div className="bg-white rounded-xl shadow-2xl w-full max-w-[560px] max-h-[86vh] flex flex-col"
        onClick={e => e.stopPropagation()}>

        <div className="flex items-center justify-between px-5 py-3 rounded-t-xl"
          style={{ backgroundColor: KB_PRIMARY }}>
          <span className="text-[15px] font-bold text-white">{TITLE[type]}</span>
          <button onClick={onClose} aria-label="닫기" className="text-white text-[18px] leading-none">×</button>
        </div>

        <div className="overflow-y-auto px-5 py-4 space-y-5">
          {/* 등록된 목록 */}
          <div>
            <p className="text-[13px] font-semibold text-kb-text mb-2">등록된 목록</p>
            {loading ? (
              <p className="text-[13px] py-3" style={{ color: KB_TEXT_MUTED }}>불러오는 중…</p>
            ) : items.length === 0 ? (
              <p className="text-[13px] py-3" style={{ color: KB_TEXT_MUTED }}>아직 등록된 계좌가 없습니다.</p>
            ) : (
              <ul className="space-y-2">
                {items.map(f => (
                  <li key={f.favoriteId}
                    className="flex items-center justify-between gap-3 border rounded-lg px-3 py-2"
                    style={{ borderColor: KB_BORDER }}>
                    <div className="text-[12px] min-w-0">
                      <p className="font-semibold text-kb-text truncate">{f.alias}</p>
                      <p style={{ color: KB_TEXT_MUTED }}>
                        {f.bankName} {f.accountNumber}
                        {f.amount != null && ` · ${won(f.amount)}`}
                      </p>
                    </div>
                    <button onClick={() => remove(f.favoriteId)}
                      className="shrink-0 border rounded-lg px-3 py-1 text-[12px] text-kb-text-body hover:bg-kb-primary-bg transition-colors"
                      style={{ borderColor: KB_BORDER }}>
                      삭제
                    </button>
                  </li>
                ))}
              </ul>
            )}
          </div>

          {/* 새로 등록 */}
          <div className="border-t pt-4 space-y-2" style={{ borderColor: KB_BORDER }}>
            <p className="text-[13px] font-semibold text-kb-text mb-1">새로 등록</p>

            <div className="flex gap-2">
              <input value={alias} onChange={e => setAlias(e.target.value)} maxLength={50}
                placeholder="별칭 (예: 월세)"
                className="border rounded-lg px-3 py-1.5 text-[13px] w-[160px] outline-none"
                style={{ borderColor: KB_BORDER }} />
              <select value={bankCode} onChange={e => setBankCode(e.target.value)}
                className="border rounded-lg px-3 py-1.5 text-[13px] outline-none"
                style={{ borderColor: KB_BORDER }}>
                {MOCK_BANKS.map(b => <option key={b.code} value={b.code}>{b.name}</option>)}
              </select>
              <input value={accountNumber} onChange={e => setAccountNumber(e.target.value)}
                placeholder="계좌번호"
                className="border rounded-lg px-3 py-1.5 text-[13px] flex-1 outline-none"
                style={{ borderColor: KB_BORDER }} />
            </div>

            <div className="flex gap-2">
              <input value={holderName} onChange={e => setHolderName(e.target.value)}
                placeholder="예금주 (선택)"
                className="border rounded-lg px-3 py-1.5 text-[13px] w-[160px] outline-none"
                style={{ borderColor: KB_BORDER }} />
              {needsAmount && (
                <input value={amount} inputMode="numeric"
                  onChange={e => setAmount(e.target.value.replace(/[^0-9]/g, ''))}
                  placeholder="보낼 금액"
                  className="border rounded-lg px-3 py-1.5 text-[13px] flex-1 outline-none"
                  style={{ borderColor: KB_BORDER }} />
              )}
            </div>

            {error && <p className="text-[12px] text-kb-red">{error}</p>}

            <button onClick={submit} disabled={saving}
              className="w-full mt-1 rounded-lg py-2 text-[13px] font-bold text-white disabled:opacity-50"
              style={{ backgroundColor: KB_PRIMARY }}>
              {saving ? '등록 중…' : '등록'}
            </button>
          </div>
        </div>
      </div>
    </div>
  )
}
