'use client'

import { useState } from 'react'
import AdminSidebar from '@/components/admin/AdminSidebar'
import { auditApi } from '@/lib/loan-api'
import { useAdminRoles } from '@/components/admin/RoleGate'
import { hasAnyRole, BankRole } from '@/lib/admin-auth'

type AuditLog = {
  logId: number
  actorId: number | null
  targetType: string | null
  targetId: number | null
  actionCd: string | null
  branchId: string | null
  breakGlassReason: string | null
  loggedAt: string | null
}

function AuditTable({ rows }: { rows: AuditLog[] }) {
  if (rows.length === 0) return <p className="py-10 text-center text-sm text-gray-400">조회 결과가 없습니다.</p>
  return (
    <table className="w-full text-[13px]">
      <thead>
        <tr className="bg-gray-50 border-b border-gray-200">
          {['logId', '행위자', '대상', '액션', '지점', 'break-glass 사유', '일시'].map(h => (
            <th key={h} className="px-4 py-3 text-left font-semibold text-gray-600">{h}</th>
          ))}
        </tr>
      </thead>
      <tbody className="divide-y divide-gray-100">
        {rows.map(r => (
          <tr key={r.logId} className="hover:bg-gray-50">
            <td className="px-4 py-3 text-gray-400 text-xs">{r.logId}</td>
            <td className="px-4 py-3 font-mono text-gray-800">{r.actorId ?? '-'}</td>
            <td className="px-4 py-3 text-gray-600">{r.targetType ? `${r.targetType} #${r.targetId}` : '-'}</td>
            <td className="px-4 py-3">
              <span className="text-[11px] px-2 py-0.5 rounded border bg-gray-100 text-gray-700 border-gray-300">{r.actionCd ?? '-'}</span>
            </td>
            <td className="px-4 py-3 text-gray-600">{r.branchId ?? '-'}</td>
            <td className="px-4 py-3 text-gray-600 max-w-[260px] truncate" title={r.breakGlassReason ?? ''}>
              {r.breakGlassReason || <span className="text-gray-300">-</span>}
            </td>
            <td className="px-4 py-3 text-gray-400 text-xs">{r.loggedAt?.slice(0, 19).replace('T', ' ') ?? '-'}</td>
          </tr>
        ))}
      </tbody>
    </table>
  )
}

export default function LoanAuditPage() {
  const roles = useAdminRoles()
  const canView = hasAnyRole(roles, BankRole.COMPLIANCE) // 감사로그 — 컴플라이언스/감사 전용

  const [tab, setTab] = useState<'break-glass' | 'target'>('break-glass')
  const [actorId, setActorId] = useState('')
  const [targetType, setTargetType] = useState('LOAN_APPLICATION')
  const [targetId, setTargetId] = useState('')
  const [rows, setRows] = useState<AuditLog[]>([])
  const [loading, setLoading] = useState(false)
  const [err, setErr] = useState('')

  function fail(m: string) { setErr(m); setTimeout(() => setErr(''), 5000) }

  async function loadBreakGlass() {
    setLoading(true); setErr('')
    try {
      const res = await auditApi.listBreakGlass(actorId ? parseInt(actorId) : undefined)
      setRows(res.data ?? [])
    } catch (e: any) {
      fail(e?.response?.data?.message ?? '조회 실패')
    } finally { setLoading(false) }
  }

  async function loadByTarget() {
    if (!targetType || !targetId) { fail('대상 유형과 ID를 입력하세요.'); return }
    setLoading(true); setErr('')
    try {
      const res = await auditApi.listByTarget(targetType, parseInt(targetId))
      setRows(res.data ?? [])
    } catch (e: any) {
      fail(e?.response?.data?.message ?? '조회 실패')
    } finally { setLoading(false) }
  }

  return (
    <div className="flex min-h-screen bg-gray-50">
      <AdminSidebar />
      <main className="flex-1 overflow-auto">
        <div className="bg-white border-b border-gray-200 px-6 py-3 text-xs text-gray-500">
          대출 &gt; <span className="text-gray-800 font-medium">감사로그</span>
        </div>

        <div className="px-6 py-5 max-w-5xl">
          <h1 className="text-lg font-bold text-gray-800 mb-1">대출 감사로그</h1>
          <p className="text-[12px] text-gray-500 mb-5">break-glass 긴급 접근 이벤트와 건별 접근 이력을 조회합니다. (컴플라이언스/감사 전용)</p>

          {roles.length === 0 ? (
            <p className="py-10 text-center text-sm text-gray-400">권한 확인 중...</p>
          ) : !canView ? (
            <div className="px-4 py-3 bg-yellow-50 border border-yellow-300 text-yellow-800 text-sm rounded">
              이 기능은 컴플라이언스/감사(ROLE_COMPLIANCE) 권한이 필요합니다.
            </div>
          ) : (
            <>
              {err && <div className="mb-4 px-4 py-2 bg-red-50 border border-red-300 text-red-700 text-sm rounded">{err}</div>}

              <div className="flex border-b border-gray-200 mb-5">
                {([
                  { id: 'break-glass', label: 'break-glass 이벤트' },
                  { id: 'target',      label: '건별 접근 이력' },
                ] as const).map(t => (
                  <button key={t.id} onClick={() => { setTab(t.id); setRows([]) }}
                    className={`px-5 py-2.5 text-[13px] font-medium border-b-2 transition-colors ${
                      tab === t.id ? 'border-kb-admin text-kb-admin' : 'border-transparent text-gray-500 hover:text-gray-700'}`}>
                    {t.label}
                  </button>
                ))}
              </div>

              {tab === 'break-glass' ? (
                <div className="flex items-end gap-3 mb-4">
                  <label className="text-[12px] text-gray-600">
                    행위자 ID(선택)
                    <input type="number" value={actorId} onChange={e => setActorId(e.target.value)} placeholder="전체"
                      className="ml-2 border border-gray-300 rounded px-3 py-1.5 text-[13px] w-32 focus:outline-none" />
                  </label>
                  <button onClick={loadBreakGlass} disabled={loading}
                    className="px-5 py-1.5 bg-kb-admin text-white text-[13px] rounded hover:opacity-90 disabled:opacity-50">
                    {loading ? '조회 중...' : '조회'}
                  </button>
                </div>
              ) : (
                <div className="flex items-end gap-3 mb-4">
                  <label className="text-[12px] text-gray-600">
                    대상 유형
                    <input type="text" value={targetType} onChange={e => setTargetType(e.target.value)} placeholder="LOAN_APPLICATION"
                      className="ml-2 border border-gray-300 rounded px-3 py-1.5 text-[13px] w-48 focus:outline-none" />
                  </label>
                  <label className="text-[12px] text-gray-600">
                    대상 ID
                    <input type="number" value={targetId} onChange={e => setTargetId(e.target.value)}
                      className="ml-2 border border-gray-300 rounded px-3 py-1.5 text-[13px] w-32 focus:outline-none" />
                  </label>
                  <button onClick={loadByTarget} disabled={loading}
                    className="px-5 py-1.5 bg-kb-admin text-white text-[13px] rounded hover:opacity-90 disabled:opacity-50">
                    {loading ? '조회 중...' : '조회'}
                  </button>
                </div>
              )}

              <div className="bg-white border border-gray-200 rounded-lg overflow-hidden">
                {loading ? <p className="py-10 text-center text-sm text-gray-400">불러오는 중...</p> : <AuditTable rows={rows} />}
              </div>
            </>
          )}
        </div>
      </main>
    </div>
  )
}
