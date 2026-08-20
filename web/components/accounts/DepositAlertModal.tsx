'use client'

import { useEffect, useState } from 'react'

import {
  fetchDepositAlerts,
  subscribeDepositAlert,
  unsubscribeDepositAlert,
  type AlertChannel,
  type DepositAlertSubscription,
} from '@/lib/banking-api'
import { KB_BORDER, KB_PRIMARY, KB_TEXT_MUTED } from '@/lib/theme'

const CHANNELS: { code: AlertChannel; label: string; hint: string }[] = [
  { code: 'PUSH', label: '앱 알림', hint: '알림함으로 받습니다.' },
  { code: 'SMS', label: '문자', hint: '휴대폰번호가 필요합니다.' },
  { code: 'EMAIL', label: '이메일', hint: '이메일 주소가 필요합니다.' },
]

const CHANNEL_LABEL: Record<AlertChannel, string> = {
  PUSH: '앱 알림', SMS: '문자', EMAIL: '이메일',
}

/**
 * 입금통보 신청.
 *
 * <p>계좌 상세와 다온 계좌 화면의 "입금통보 신청" 버튼에 핸들러가 없었다.
 *
 * <p>기준금액을 받는 이유는, 소액까지 전부 통보하면 고객이 알림 자체를 꺼 버려
 * 정작 중요한 입금도 놓치기 때문이다. 0 이면 전액 통보다.
 */
export default function DepositAlertModal({
  accountNumber,
  onClose,
}: {
  accountNumber: string
  onClose: () => void
}) {
  const [items, setItems] = useState<DepositAlertSubscription[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')

  const [channel, setChannel] = useState<AlertChannel>('PUSH')
  const [contact, setContact] = useState('')
  const [minAmount, setMinAmount] = useState('')
  const [saving, setSaving] = useState(false)

  const needsContact = channel !== 'PUSH'

  async function load() {
    setLoading(true)
    try {
      const all = await fetchDepositAlerts()
      setItems(all.filter(s => s.accountNumber === accountNumber && s.active))
      setError('')
    } catch {
      setError('신청 내역을 불러오지 못했습니다.')
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => {
    load()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [accountNumber])

  async function submit() {
    if (needsContact && !contact.trim()) {
      // 서버도 같은 이유로 막는다. 연락처 없는 신청은 발송 때 아무 데도 가지 않는다.
      return setError('통보받을 연락처를 입력해 주세요.')
    }
    setSaving(true)
    try {
      await subscribeDepositAlert({
        accountNumber,
        channel,
        contact: needsContact ? contact.trim() : undefined,
        minAmount: Number(minAmount.replace(/[^0-9]/g, '')) || 0,
      })
      setContact(''); setMinAmount(''); setError('')
      await load()
    } catch (e: unknown) {
      const msg = (e as { response?: { data?: { message?: string } } })?.response?.data?.message
      setError(msg || '신청하지 못했습니다.')
    } finally {
      setSaving(false)
    }
  }

  async function cancel(subscriptionId: number) {
    try {
      await unsubscribeDepositAlert(subscriptionId)
      await load()
    } catch {
      setError('해지하지 못했습니다.')
    }
  }

  return (
    <div className="fixed inset-0 z-[200] flex items-center justify-center bg-black/40 p-4" onClick={onClose}>
      <div className="bg-white rounded-xl shadow-2xl w-full max-w-[480px] max-h-[86vh] flex flex-col"
        onClick={e => e.stopPropagation()}>

        <div className="flex items-center justify-between px-5 py-3 rounded-t-xl"
          style={{ backgroundColor: KB_PRIMARY }}>
          <span className="text-[15px] font-bold text-white">입금통보 신청</span>
          <button onClick={onClose} aria-label="닫기" className="text-white text-[18px] leading-none">×</button>
        </div>

        <div className="overflow-y-auto px-5 py-4 space-y-5">
          <p className="text-[12px]" style={{ color: KB_TEXT_MUTED }}>{accountNumber}</p>

          <div>
            <p className="text-[13px] font-semibold text-kb-text mb-2">신청 중인 통보</p>
            {loading ? (
              <p className="text-[13px] py-2" style={{ color: KB_TEXT_MUTED }}>불러오는 중…</p>
            ) : items.length === 0 ? (
              <p className="text-[13px] py-2" style={{ color: KB_TEXT_MUTED }}>신청한 통보가 없습니다.</p>
            ) : (
              <ul className="space-y-2">
                {items.map(s => (
                  <li key={s.subscriptionId}
                    className="flex items-center justify-between gap-3 border rounded-lg px-3 py-2"
                    style={{ borderColor: KB_BORDER }}>
                    <div className="text-[12px] min-w-0">
                      <p className="font-semibold text-kb-text">{CHANNEL_LABEL[s.channel]}</p>
                      <p className="truncate" style={{ color: KB_TEXT_MUTED }}>
                        {s.minAmount > 0
                          ? `${s.minAmount.toLocaleString('ko-KR')}원 이상 입금 시`
                          : '모든 입금 통보'}
                      </p>
                    </div>
                    <button onClick={() => cancel(s.subscriptionId)}
                      className="shrink-0 border rounded-lg px-3 py-1 text-[12px] text-kb-text-body hover:bg-kb-primary-bg transition-colors"
                      style={{ borderColor: KB_BORDER }}>
                      해지
                    </button>
                  </li>
                ))}
              </ul>
            )}
          </div>

          <div className="border-t pt-4 space-y-2" style={{ borderColor: KB_BORDER }}>
            <p className="text-[13px] font-semibold text-kb-text mb-1">새로 신청</p>

            <div className="flex gap-2">
              {CHANNELS.map(c => (
                <button key={c.code} onClick={() => setChannel(c.code)}
                  className="flex-1 border rounded-lg py-1.5 text-[12px] transition-colors"
                  style={channel === c.code
                    ? { borderColor: KB_PRIMARY, color: KB_PRIMARY, fontWeight: 700 }
                    : { borderColor: KB_BORDER, color: KB_TEXT_MUTED }}>
                  {c.label}
                </button>
              ))}
            </div>
            <p className="text-[11px]" style={{ color: KB_TEXT_MUTED }}>
              {CHANNELS.find(c => c.code === channel)?.hint}
            </p>

            {needsContact && (
              <input value={contact} onChange={e => setContact(e.target.value)}
                placeholder={channel === 'SMS' ? '010-0000-0000' : 'name@example.com'}
                className="w-full border rounded-lg px-3 py-1.5 text-[13px] outline-none"
                style={{ borderColor: KB_BORDER }} />
            )}

            <input value={minAmount} inputMode="numeric"
              onChange={e => setMinAmount(e.target.value.replace(/[^0-9]/g, ''))}
              placeholder="통보 기준금액 (비우면 전액 통보)"
              className="w-full border rounded-lg px-3 py-1.5 text-[13px] outline-none"
              style={{ borderColor: KB_BORDER }} />

            {error && <p className="text-[12px] text-kb-red">{error}</p>}

            <button onClick={submit} disabled={saving}
              className="w-full mt-1 rounded-lg py-2 text-[13px] font-bold text-white disabled:opacity-50"
              style={{ backgroundColor: KB_PRIMARY }}>
              {saving ? '신청 중…' : '신청'}
            </button>
          </div>
        </div>
      </div>
    </div>
  )
}
