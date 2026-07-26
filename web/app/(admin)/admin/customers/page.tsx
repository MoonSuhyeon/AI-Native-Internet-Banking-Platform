'use client'

import { useCallback, useEffect, useState } from 'react'
import { AdminUser } from '@/lib/admin-mock-data'
import AdminSidebar from '@/components/admin/AdminSidebar'
import { useAdminRoles } from '@/components/admin/RoleGate'
import { isMaskingRole, requiresReason, primaryRoleLabel } from '@/lib/admin-auth'
import { searchCustomers, recordAccess, CustomerSummary, STATUS_LABEL, errMsg } from '@/lib/admin-customer-api'
import { maskPhone, maskEmail } from '@/lib/masking'

export default function CustomersPage() {
  const roles = useAdminRoles()
  const [adminUser, setAdminUser] = useState<AdminUser | null>(null)
  const [rows, setRows] = useState<CustomerSummary[]>([])
  const [search, setSearch] = useState('')
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [reasonModal, setReasonModal] = useState<{ customerId: number } | null>(null)
  const [reasonInput, setReasonInput] = useState('')
  const [unlockedIds, setUnlockedIds] = useState<Set<number>>(new Set())

  useEffect(() => {
    try {
      const stored = localStorage.getItem('admin_user')
      if (stored) setAdminUser(JSON.parse(stored))
    } catch { /* noop */ }
  }, [])

  const load = useCallback(() => {
    setLoading(true)
    searchCustomers({ keyword: search.trim() || undefined, size: 50 })
      .then(res => setRows(res.content))
      .catch(e => setError(errMsg(e, '고객 목록을 불러오지 못했습니다.')))
      .finally(() => setLoading(false))
  }, [search])

  useEffect(() => { if (adminUser) load() }, [adminUser]) // eslint-disable-line react-hooks/exhaustive-deps

  if (!adminUser) return null

  const masking = isMaskingRole(roles)
  const needsReason = requiresReason(roles)

  function unlock() {
    if (!reasonInput.trim() || !reasonModal) return
    const cid = reasonModal.customerId
    const reason = reasonInput.trim()
    setUnlockedIds(prev => new Set([...Array.from(prev), cid]))
    setReasonModal(null); setReasonInput('')
    // 연락처 열람을 사유와 함께 접근 감사로그에 기록(행위 직원은 토큰에서 식별).
    // 감사 기록 실패가 조회 자체를 막지 않도록 best-effort 로 처리한다.
    recordAccess(cid, { actionCode: 'CONTACT_VIEW', reason }).catch(() => { /* noop */ })
  }

  return (
    <div className="flex min-h-screen bg-kb-beige-light">
      <AdminSidebar />
      <main className="flex-1 overflow-auto">
        <div className="bg-white border-b border-kb-border px-8 py-4 flex items-center justify-between">
          <h1 className="text-lg font-bold text-gray-800">고객 조회</h1>
          <span className="text-xs px-2 py-1 rounded-full font-medium bg-blue-100 text-blue-700">{primaryRoleLabel(roles)}</span>
        </div>

        <div className="px-8 py-6">
          <>
              {masking && (
                <div className="mb-4 bg-yellow-50 border border-yellow-200 rounded px-4 py-3 text-sm text-yellow-700 flex items-center gap-2">
                  <span>🔒</span><span>개인식별정보(PII)가 마스킹 처리되어 표시됩니다.</span>
                </div>
              )}
              {needsReason && (
                <div className="mb-4 bg-blue-50 border border-blue-200 rounded px-4 py-3 text-sm text-blue-700 flex items-center gap-2">
                  <span>ℹ️</span><span>연락처 조회 시 조회 사유를 입력해야 합니다 (행 클릭).</span>
                </div>
              )}

              <div className="mb-4 flex gap-2">
                <input value={search} onChange={e => setSearch(e.target.value)} onKeyDown={e => { if (e.key === 'Enter') load() }}
                  placeholder="이름 또는 휴대폰 검색" className="border border-gray-300 px-3 py-2 text-sm rounded outline-none focus:border-blue-400 w-64" />
                <button onClick={load} disabled={loading} className="px-3 py-2 bg-kb-yellow text-white text-sm font-bold rounded hover:bg-kb-yellow-dark disabled:opacity-50">{loading ? '조회 중…' : '조회'}</button>
              </div>

              {error && <div className="mb-3 bg-red-50 border border-red-200 rounded px-4 py-2.5 text-xs text-red-700">{error}</div>}

              <div className="bg-white border border-kb-border rounded-lg overflow-hidden shadow-sm">
                <table className="w-full text-sm">
                  <thead>
                    <tr className="bg-kb-beige-light text-xs text-kb-text-muted uppercase border-b border-kb-border">
                      {['이름', '연락처', '이메일', '등급', '상태', 'Party ID'].map(h => <th key={h} className="px-5 py-3 text-left font-medium">{h}</th>)}
                    </tr>
                  </thead>
                  <tbody className="divide-y divide-gray-100">
                    {rows.map(c => {
                      const locked = needsReason && !unlockedIds.has(c.customerId)
                      const phone = masking || locked ? maskPhone(c.phone) : (c.phone ?? '-')
                      const email = masking || locked ? maskEmail(c.email) : (c.email ?? '-')
                      const label = STATUS_LABEL[c.customerStatusCode] ?? c.customerStatusCode
                      return (
                        <tr key={c.customerId}
                          onClick={() => { if (locked) { setReasonModal({ customerId: c.customerId }); setReasonInput('') } }}
                          className={`transition-colors ${locked ? 'cursor-pointer hover:bg-blue-50' : 'hover:bg-kb-beige-light'}`}>
                          <td className="px-5 py-3 font-medium text-gray-800">
                            {c.partyName}{locked && <span className="ml-1.5 text-xs text-blue-500">🔒 클릭하여 조회</span>}
                          </td>
                          <td className="px-5 py-3 text-gray-500">{phone}</td>
                          <td className="px-5 py-3 text-gray-500 text-xs">{email}</td>
                          <td className="px-5 py-3 text-gray-600 text-xs">{c.customerGradeCode ?? '-'}</td>
                          <td className="px-5 py-3 text-gray-600 text-xs">{label}</td>
                          <td className="px-5 py-3 text-gray-400 font-mono text-xs">{c.partyId}</td>
                        </tr>
                      )
                    })}
                    {!loading && rows.length === 0 && !error && (
                      <tr><td colSpan={6} className="px-5 py-8 text-center text-gray-400 text-sm">검색 결과가 없습니다.</td></tr>
                    )}
                  </tbody>
                </table>
              </div>
              <p className="mt-2 text-xs text-gray-400">총 {rows.length}건 · 주민번호·계좌·잔액은 별 도메인(deposit)으로 본 화면 미표시</p>
          </>
        </div>
      </main>

      {reasonModal && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/40">
          <div className="bg-white shadow-xl rounded w-[400px]">
            <div className="px-6 py-4 border-b border-gray-200">
              <p className="text-sm font-bold text-gray-800">조회 사유 입력</p>
              <p className="text-xs text-gray-500 mt-0.5">지점 직원의 연락처 조회는 사유 입력이 필수입니다.</p>
            </div>
            <div className="px-6 py-4">
              <textarea value={reasonInput} onChange={e => setReasonInput(e.target.value)}
                placeholder="조회 사유 (예: 대출 상담 요청, 민원 처리 등)"
                className="w-full border border-gray-300 rounded px-3 py-2 text-sm outline-none focus:border-blue-400 resize-none h-24" />
            </div>
            <div className="px-6 pb-5 flex justify-end gap-2">
              <button onClick={() => setReasonModal(null)} className="px-4 py-2 text-sm border border-gray-300 rounded text-gray-600 hover:bg-kb-beige-light">취소</button>
              <button onClick={unlock} disabled={!reasonInput.trim()} className="px-4 py-2 text-sm bg-blue-600 text-white rounded font-medium hover:bg-blue-700 disabled:opacity-50">확인 및 조회</button>
            </div>
          </div>
        </div>
      )}
    </div>
  )
}
