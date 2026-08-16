'use client'

import { useState, useEffect, useCallback } from 'react'
import Link from 'next/link'
import AdminSidebar from '@/components/admin/AdminSidebar'
import { adminReviewApi } from '@/lib/loan-api'
import { useAdminRoles } from '@/components/admin/RoleGate'
import { hasAnyRole, BankRole } from '@/lib/admin-auth'

const REV_STATUS: Record<string, { text: string; cls: string }> = {
  PENDING_APPROVAL:  { text: '확정 대기',   cls: 'bg-yellow-100 text-yellow-700 border-yellow-300' },
  PENDING_APPROVER:  { text: '승인자 대기', cls: 'bg-blue-100   text-blue-700   border-blue-300' },
  COMPLETED:         { text: '완료',         cls: 'bg-green-100  text-green-700  border-green-300' },
  EXPIRED:           { text: '만료',         cls: 'bg-gray-100   text-gray-500   border-gray-300' },
  BIAS_REVIEWING:    { text: '편향검토중',   cls: 'bg-red-100    text-red-700    border-red-300' },
}

const REV_DECISION: Record<string, string> = {
  APPROVED: '승인', REJECTED: '거절',
}

type ReviewItem = {
  revId: number
  applId: number
  revTypeCd: string
  revStatusCd: string
  revDecisionCd: string | null
  approvedAmount: number | null
  approvedRateBps: number | null
  rejectReasonCd: string | null
  reviewedAt: string | null
  biasSeverityCd: string | null
}

function ReviewTable({ items, emptyMsg }: { items: ReviewItem[]; emptyMsg: string }) {
  if (items.length === 0) return (
    <p className="py-10 text-center text-sm text-gray-400">{emptyMsg}</p>
  )
  return (
    <table className="w-full text-[13px]">
      <thead>
        <tr className="bg-gray-50 border-b border-gray-200">
          {['revId', '신청ID', '유형', '권고결정', '한도', '심사상태', '편향', '심사일시', ''].map(h => (
            <th key={h} className="px-4 py-3 text-left font-semibold text-gray-600">{h}</th>
          ))}
        </tr>
      </thead>
      <tbody className="divide-y divide-gray-100">
        {items.map(r => {
          const st = REV_STATUS[r.revStatusCd]
          return (
            <tr key={r.revId} className="hover:bg-gray-50 transition-colors">
              <td className="px-4 py-3 text-gray-400 text-xs">{r.revId}</td>
              <td className="px-4 py-3 font-mono font-bold text-gray-800">{r.applId}</td>
              <td className="px-4 py-3 text-gray-600">{r.revTypeCd === 'AUTO' ? '자동' : '수동'}</td>
              <td className="px-4 py-3 font-bold">
                {r.revDecisionCd
                  ? <span className={r.revDecisionCd === 'APPROVED' ? 'text-green-600' : 'text-red-500'}>
                      {REV_DECISION[r.revDecisionCd]}
                    </span>
                  : <span className="text-gray-400">-</span>}
              </td>
              <td className="px-4 py-3">
                {r.approvedAmount != null ? `${(r.approvedAmount / 10000).toLocaleString('ko-KR')}만원` : '-'}
              </td>
              <td className="px-4 py-3">
                <span className={`text-[11px] px-2 py-0.5 rounded border ${st?.cls ?? ''}`}>
                  {st?.text ?? r.revStatusCd}
                </span>
              </td>
              <td className="px-4 py-3">
                {r.biasSeverityCd
                  ? <span className={`text-[11px] px-2 py-0.5 rounded border ${r.biasSeverityCd === 'BLOCKED' ? 'bg-red-100 text-red-700 border-red-300' : 'bg-orange-100 text-orange-700 border-orange-300'}`}>
                      {r.biasSeverityCd}
                    </span>
                  : <span className="text-gray-300">-</span>}
              </td>
              <td className="px-4 py-3 text-gray-400 text-xs">
                {r.reviewedAt?.slice(0, 16).replace('T', ' ') ?? '-'}
              </td>
              <td className="px-4 py-3">
                <Link href={`/admin/loan/review/${r.applId}`}
                  className="px-3 py-1 text-[12px] bg-kb-admin text-white rounded hover:opacity-90">
                  상세
                </Link>
              </td>
            </tr>
          )
        })}
      </tbody>
    </table>
  )
}

function StatsPanel() {
  const today = new Date()
  const fmt = (d: Date) => d.toISOString().slice(0, 10).replace(/-/g, '')
  const [from, setFrom] = useState(fmt(new Date(today.getFullYear(), today.getMonth(), 1)))
  const [to,   setTo]   = useState(fmt(today))
  const [stats, setStats] = useState<any>(null)
  const [loading, setLoading] = useState(false)

  async function load() {
    setLoading(true)
    try {
      const { data: res } = await adminReviewApi.stats(from, to)
      setStats(res.data)
    } catch {} finally { setLoading(false) }
  }

  return (
    <div className="space-y-4">
      <div className="flex items-center gap-3">
        <input type="text" value={from} onChange={e => setFrom(e.target.value)} placeholder="yyyyMMdd"
          className="border border-gray-300 px-3 py-1.5 text-[13px] rounded w-32 focus:outline-none" />
        <span className="text-gray-400">~</span>
        <input type="text" value={to} onChange={e => setTo(e.target.value)} placeholder="yyyyMMdd"
          className="border border-gray-300 px-3 py-1.5 text-[13px] rounded w-32 focus:outline-none" />
        <button onClick={load} disabled={loading}
          className="px-5 py-1.5 bg-kb-admin text-white text-[13px] rounded hover:opacity-90 disabled:opacity-50">
          {loading ? '조회 중...' : '조회'}
        </button>
      </div>
      {stats && (
        <div className="grid grid-cols-3 gap-4">
          <div className="bg-white border border-gray-200 rounded-lg p-4">
            <p className="text-xs text-gray-400 mb-3 font-semibold uppercase">결정 유형</p>
            {Object.entries(stats.byTypeDecision ?? {}).map(([k, v]) => (
              <div key={k} className="flex justify-between text-[13px] py-1 border-b border-gray-100 last:border-0">
                <span className="text-gray-600">{k}</span>
                <span className="font-bold text-gray-800">{String(v)}</span>
              </div>
            ))}
          </div>
          <div className="bg-white border border-gray-200 rounded-lg p-4">
            <p className="text-xs text-gray-400 mb-3 font-semibold uppercase">상태</p>
            {Object.entries(stats.byStatus ?? {}).map(([k, v]) => (
              <div key={k} className="flex justify-between text-[13px] py-1 border-b border-gray-100 last:border-0">
                <span className="text-gray-600">{REV_STATUS[k]?.text ?? k}</span>
                <span className="font-bold text-gray-800">{String(v)}</span>
              </div>
            ))}
          </div>
          <div className="bg-white border border-gray-200 rounded-lg p-4">
            <p className="text-xs text-gray-400 mb-3 font-semibold uppercase">거절 사유</p>
            {Object.entries(stats.byRejectReason ?? {}).length === 0
              ? <p className="text-[13px] text-gray-400">없음</p>
              : Object.entries(stats.byRejectReason ?? {}).map(([k, v]) => (
                <div key={k} className="flex justify-between text-[13px] py-1 border-b border-gray-100 last:border-0">
                  <span className="text-gray-600">{k}</span>
                  <span className="font-bold text-gray-800">{String(v)}</span>
                </div>
              ))}
          </div>
        </div>
      )}
    </div>
  )
}

export default function LoanReviewListPage() {
  const roles = useAdminRoles()
  // 본사 상신 건은 ROLE_HQ_REVIEWER 전용 (ROLE_ADMIN 은 hasAnyRole 에서 항상 통과)
  const canViewEscalated = hasAnyRole(roles, BankRole.HQ_REVIEWER)

  const [tab, setTab] = useState<'pending' | 'approver' | 'escalated' | 'stats'>('pending')
  const [pending, setPending] = useState<ReviewItem[]>([])
  const [pendingApprover, setPendingApprover] = useState<ReviewItem[]>([])
  const [escalated, setEscalated] = useState<ReviewItem[]>([])
  const [loading, setLoading] = useState(true)

  const load = useCallback(async () => {
    setLoading(true)
    try {
      const [p, pa] = await Promise.all([
        adminReviewApi.listPending(),
        adminReviewApi.listPendingApprover(),
      ])
      setPending(p.data?.data ?? [])
      setPendingApprover(pa.data?.data ?? [])
    } catch {} finally { setLoading(false) }
  }, [])

  useEffect(() => { load() }, [load])

  // 상신 건은 HQ_REVIEWER 권한이 있을 때만 별도 로드 (Page 응답 → content)
  useEffect(() => {
    if (!canViewEscalated) return
    adminReviewApi.listEscalated()
      .then(res => setEscalated(res.data?.data?.content ?? []))
      .catch(() => {})
  }, [canViewEscalated])

  // 권한이 사라지면 상신 탭에 머무르지 않도록 보정
  useEffect(() => {
    if (!canViewEscalated && tab === 'escalated') setTab('pending')
  }, [canViewEscalated, tab])

  const TABS = [
    { id: 'pending',  label: `확정 대기 (${pending.length})` },
    { id: 'approver', label: `승인자 대기 (${pendingApprover.length})` },
    ...(canViewEscalated ? [{ id: 'escalated', label: `상신 건 (${escalated.length})` } as const] : []),
    { id: 'stats',    label: '통계' },
  ] as const

  return (
    <div className="flex min-h-screen bg-gray-50">
      <AdminSidebar />
      <main className="flex-1 overflow-auto">
        <div className="bg-white border-b border-gray-200 px-6 py-3 text-xs text-gray-500">
          대출 &gt; <span className="text-gray-800 font-medium">본심사 목록</span>
        </div>
        <div className="px-6 py-5">
          <div className="flex justify-between items-center mb-5">
            <h1 className="text-lg font-bold text-gray-800">본심사 관리</h1>
            <Link href="/admin/loan/review/new"
              className="px-4 py-2 bg-kb-admin text-white text-[13px] rounded hover:opacity-90">
              + 신청 직접 심사
            </Link>
          </div>

          <div className="flex border-b border-gray-200 mb-5">
            {TABS.map(t => (
              <button key={t.id} onClick={() => setTab(t.id)}
                className={`px-5 py-2.5 text-[13px] font-medium border-b-2 transition-colors ${
                  tab === t.id
                    ? 'border-kb-admin text-kb-admin'
                    : 'border-transparent text-gray-500 hover:text-gray-700'}`}>
                {t.label}
              </button>
            ))}
          </div>

          {loading && tab !== 'stats' ? (
            <p className="py-10 text-center text-sm text-gray-400">불러오는 중...</p>
          ) : tab === 'pending' ? (
            <div className="bg-white border border-gray-200 rounded-lg overflow-hidden">
              <ReviewTable items={pending} emptyMsg="확정 대기 건이 없습니다." />
            </div>
          ) : tab === 'approver' ? (
            <div className="bg-white border border-gray-200 rounded-lg overflow-hidden">
              <ReviewTable items={pendingApprover} emptyMsg="승인자 대기 건이 없습니다." />
            </div>
          ) : tab === 'escalated' ? (
            <div className="bg-white border border-gray-200 rounded-lg overflow-hidden">
              <ReviewTable items={escalated} emptyMsg="본사 상신 건이 없습니다." />
            </div>
          ) : (
            <StatsPanel />
          )}
        </div>
      </main>
    </div>
  )
}
