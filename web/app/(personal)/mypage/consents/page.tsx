'use client'
import { KB_PRIMARY, KB_PRIMARY_BG, KB_PRIMARY_BORDER, KB_PRIMARY_SURFACE, KB_DANGER } from '@/lib/theme'

import Link from 'next/link'
import { useEffect, useState } from 'react'
import { fetchConsentHistory, withdrawConsent, type ConsentRecord } from '@/lib/consent-api'

/**
 * 내 약관 동의 이력.
 *
 * 기록만 하고 볼 수 없으면 반쪽이다. 고객은 자기가 무엇에 동의했는지 확인할 수 있어야
 * 하고, 선택 항목은 거둘 수 있어야 한다.
 *
 * 철회해도 목록에서 사라지지 않는다 — 동의했던 사실은 남고 효력만 없어지기 때문이다.
 * 사라지게 만들면 "언제 동의했다가 언제 거뒀는지" 를 아무도 답할 수 없다.
 */

const CATEGORY_LABEL: Record<string, string> = {
  PRIVACY: '개인정보',
  THIRD_PARTY: '제3자 제공',
  MARKETING: '마케팅',
  AGREEMENT: '약관',
  NOTICE: '안내 확인',
}

const BIZ_LABEL: Record<string, string> = {
  CERT: '인증서 발급',
  BIZ_CERT: '기업 인증서 발급',
  BANKING: '전자금융',
  LOAN: '대출',
  CORP_JOIN: '법인 가입',
  CLOSE: '해지·탈퇴',
  MARKETING: '마케팅',
}

function formatDate(iso?: string) {
  if (!iso) return '-'
  const d = new Date(iso)
  if (Number.isNaN(d.getTime())) return '-'
  return `${d.getFullYear()}.${String(d.getMonth() + 1).padStart(2, '0')}.${String(d.getDate()).padStart(2, '0')}`
}

export default function ConsentHistoryPage() {
  const [rows, setRows] = useState<ConsentRecord[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')
  const [busyId, setBusyId] = useState<number | null>(null)

  useEffect(() => { void load() }, [])

  async function load() {
    setLoading(true)
    setError('')
    try {
      setRows(await fetchConsentHistory())
    } catch {
      setError('동의 이력을 불러오지 못했습니다.')
    } finally {
      setLoading(false)
    }
  }

  async function handleWithdraw(row: ConsentRecord) {
    // 필수 항목을 거두면 그 업무를 계속할 수 없다. 버튼을 아예 두지 않는다.
    if (row.required) return
    if (!window.confirm(`'${row.termsName}' 동의를 철회하시겠습니까?\n철회 후에는 다시 동의해야 이용할 수 있습니다.`)) return

    setBusyId(row.consentId)
    setError('')
    try {
      const updated = await withdrawConsent(row.consentId, '고객 요청')
      setRows(prev => prev.map(r => (r.consentId === updated.consentId ? updated : r)))
    } catch {
      setError('철회 처리에 실패했습니다. 잠시 후 다시 시도해 주세요.')
    } finally {
      setBusyId(null)
    }
  }

  return (
    <div className="max-w-kb-container mx-auto px-6 py-8">
      <div className="flex items-center gap-1 text-[12px] text-kb-text-muted mb-6">
        <Link href="/" className="hover:underline">홈</Link>
        <span>›</span>
        <Link href="/mypage" className="hover:underline">마이페이지</Link>
        <span>›</span>
        <span className="text-kb-text">약관 동의 이력</span>
      </div>

      <h1 className="text-2xl font-bold text-kb-text mb-2">약관 동의 이력</h1>
      <p className="text-[13px] text-kb-text-muted mb-6">
        동의한 약관과 시점을 확인할 수 있습니다. 선택 항목은 철회할 수 있으며,
        철회해도 동의했던 기록은 남습니다.
      </p>

      {error && <p className="text-[13px] mb-4" style={{ color: KB_DANGER }}>{error}</p>}

      {loading ? (
        <p className="text-[13px] text-kb-text-muted py-10 text-center">불러오는 중…</p>
      ) : rows.length === 0 ? (
        <div className="rounded-xl px-6 py-12 text-center" style={{ border: `1px solid ${KB_PRIMARY_BORDER}`, backgroundColor: KB_PRIMARY_SURFACE }}>
          <p className="text-[13px] text-kb-text-muted">기록된 동의 이력이 없습니다.</p>
        </div>
      ) : (
        <div className="rounded-xl overflow-hidden" style={{ border: `1px solid ${KB_PRIMARY_BORDER}` }}>
          <div className="px-5 py-3 text-[13px] font-semibold text-kb-text"
            style={{ backgroundColor: KB_PRIMARY_BG, borderBottom: `1px solid ${KB_PRIMARY_BORDER}` }}>
            전체 {rows.length}건
          </div>
          {rows.map((row, i) => (
            <div key={row.consentId}
              className="px-5 py-4 flex items-start justify-between gap-4"
              style={{ borderBottom: i < rows.length - 1 ? `1px solid ${KB_PRIMARY_BORDER}` : 'none' }}>
              <div className="min-w-0">
                <div className="flex items-center gap-2 flex-wrap mb-1">
                  <span className="text-[11px] px-2 py-0.5 rounded"
                    style={{ backgroundColor: KB_PRIMARY_BG, color: KB_PRIMARY }}>
                    {CATEGORY_LABEL[row.termsCategoryCd] ?? row.termsCategoryCd}
                  </span>
                  <span className="text-[11px] text-kb-text-muted">
                    {BIZ_LABEL[row.bizDivCd] ?? row.bizDivCd}
                  </span>
                  {row.required
                    ? <span className="text-[11px] text-kb-text-muted">필수</span>
                    : <span className="text-[11px] text-kb-text-muted">선택</span>}
                </div>
                <p className="text-[13px] font-semibold text-kb-text">{row.termsName}</p>
                <p className="text-[12px] text-kb-text-muted mt-0.5">
                  {row.agreed ? '동의' : '거절'} · {formatDate(row.agreedAt)} · v{row.termsVersion}
                  {row.withdrawn && ` · ${formatDate(row.withdrawnAt)} 철회`}
                </p>
              </div>

              <div className="flex-shrink-0 flex items-center gap-3">
                <span className="text-[12px] font-semibold"
                  style={{ color: row.effective ? KB_PRIMARY : '#8593A0' }}>
                  {row.effective ? '유효' : row.withdrawn ? '철회됨' : '거절'}
                </span>
                {row.effective && !row.required && (
                  <button onClick={() => handleWithdraw(row)} disabled={busyId === row.consentId}
                    className="border border-kb-border px-3 py-1 text-[12px] text-kb-text-body hover:bg-kb-beige-light disabled:opacity-50">
                    {busyId === row.consentId ? '처리 중…' : '철회'}
                  </button>
                )}
              </div>
            </div>
          ))}
        </div>
      )}
    </div>
  )
}
