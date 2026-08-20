'use client'

import { useState } from 'react'

import Link from 'next/link'

import { updateAccountAlias } from '@/lib/deposit-api'
import { KB_BORDER, KB_PRIMARY, KB_TEXT_MUTED } from '@/lib/theme'

/**
 * 계좌관리.
 *
 * <p>계좌 상세의 "계좌관리" 버튼에 핸들러가 없었다. 계좌 설정 API 는 이미 있었고
 * (별칭·한도·상태) 화면에서 닿을 길이 없었다.
 *
 * <p>여기서 다루는 것은 별칭까지다. 한도 변경은 이체한도 화면이 따로 있고, 계좌 상태
 * 변경(해지·정지)은 되돌리기 어려워 각자의 화면에서 확인 절차를 거친다 — 한 모달에
 * 몰아넣으면 실수로 누르기 쉬운 자리에 위험한 조작이 놓인다.
 */
export default function AccountSettingsModal({
  accountId,
  accountNumber,
  currentAlias,
  onClose,
  onSaved,
}: {
  accountId: number
  accountNumber: string
  currentAlias: string
  onClose: () => void
  onSaved: () => void
}) {
  const [alias, setAlias] = useState(currentAlias)
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState('')
  const [done, setDone] = useState(false)

  async function save() {
    if (!alias.trim()) return setError('계좌 별칭을 입력해 주세요.')
    setSaving(true)
    try {
      await updateAccountAlias(accountId, alias.trim())
      setError('')
      setDone(true)
      onSaved()
    } catch (e: unknown) {
      const msg = (e as { response?: { data?: { message?: string } } })?.response?.data?.message
      setError(msg ?? '변경하지 못했습니다.')
    } finally {
      setSaving(false)
    }
  }

  return (
    <div className="fixed inset-0 z-[200] flex items-center justify-center bg-black/40 p-4" onClick={onClose}>
      <div className="bg-white rounded-xl shadow-2xl w-full max-w-[440px]" onClick={e => e.stopPropagation()}>

        <div className="flex items-center justify-between px-5 py-3 rounded-t-xl"
          style={{ backgroundColor: KB_PRIMARY }}>
          <span className="text-[15px] font-bold text-white">계좌관리</span>
          <button onClick={onClose} aria-label="닫기" className="text-white text-[18px] leading-none">×</button>
        </div>

        <div className="px-5 py-4 space-y-4">
          <p className="text-[12px]" style={{ color: KB_TEXT_MUTED }}>{accountNumber}</p>

          <div className="space-y-2">
            <label className="block text-[13px] font-semibold text-kb-text">계좌 별칭</label>
            <input value={alias} onChange={e => { setAlias(e.target.value); setDone(false) }} maxLength={100}
              placeholder="예: 생활비 통장"
              className="w-full border rounded-lg px-3 py-2 text-[13px] outline-none"
              style={{ borderColor: KB_BORDER }} />
            <p className="text-[11px]" style={{ color: KB_TEXT_MUTED }}>
              계좌가 여럿이면 번호만으로는 구분이 어렵습니다. 조회·이체 화면에 이 이름이 보입니다.
            </p>

            {error && <p className="text-[12px] text-kb-red">{error}</p>}
            {done && <p className="text-[12px]" style={{ color: KB_PRIMARY }}>변경되었습니다.</p>}

            <button onClick={save} disabled={saving}
              className="w-full rounded-lg py-2 text-[13px] font-bold text-white disabled:opacity-50"
              style={{ backgroundColor: KB_PRIMARY }}>
              {saving ? '변경 중…' : '별칭 변경'}
            </button>
          </div>

          <div className="border-t pt-3 space-y-2" style={{ borderColor: KB_BORDER }}>
            {/* 위험하거나 절차가 있는 조작은 각자의 화면으로 보낸다. 한 모달에 몰아넣으면
                실수로 누르기 쉬운 자리에 되돌리기 어려운 조작이 놓인다. */}
            <Link href="/banking/transfer-limit" onClick={onClose}
              className="block border rounded-lg px-3 py-2 text-[13px] text-kb-text-body hover:bg-kb-primary-bg transition-colors"
              style={{ borderColor: KB_BORDER }}>
              이체한도 조회/변경
            </Link>
            <Link href="/banking/withdrawal-account" onClick={onClose}
              className="block border rounded-lg px-3 py-2 text-[13px] text-kb-text-body hover:bg-kb-primary-bg transition-colors"
              style={{ borderColor: KB_BORDER }}>
              출금계좌 등록/삭제
            </Link>
            <Link href="/products/deposit/inquiry/terminate" onClick={onClose}
              className="block border rounded-lg px-3 py-2 text-[13px] text-kb-text-body hover:bg-kb-primary-bg transition-colors"
              style={{ borderColor: KB_BORDER }}>
              예금/적금 해지
            </Link>
          </div>
        </div>
      </div>
    </div>
  )
}
