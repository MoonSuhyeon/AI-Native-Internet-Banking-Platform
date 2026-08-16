'use client'

import { useState } from 'react'
import { useRouter } from 'next/navigation'
import Link from 'next/link'
import AdminSidebar from '@/components/admin/AdminSidebar'
import { adminReviewApi } from '@/lib/loan-api'
import { useAdminRoles } from '@/components/admin/RoleGate'
import { hasAnyRole, BankRole } from '@/lib/admin-auth'

export default function AdminReviewNewPage() {
  const router = useRouter()
  const [applId, setApplId]         = useState('')
  const [revType, setRevType]       = useState('MANUAL')
  const [revDecision, setRevDecision] = useState('APPROVED')
  const [busy, setBusy]             = useState(false)
  const [err, setErr]               = useState('')

  // 심사 실행 권한: 수동=심사역(DEPUTY)·운영(OPS), 자동=운영(OPS). (ROLE_ADMIN 항상 통과)
  const roles  = useAdminRoles()
  const canRun = hasAnyRole(roles, BankRole.DEPUTY_MANAGER, BankRole.OPS)

  async function submit() {
    if (!applId) return
    setBusy(true); setErr('')
    try {
      await adminReviewApi.run(parseInt(applId), {
        revTypeCd: revType,
        revDecisionCd: revDecision,
      })
      router.push(`/admin/loan/review/${applId}`)
    } catch (e: any) {
      setErr(e?.response?.data?.message ?? '심사 시작에 실패했습니다.')
      setBusy(false)
    }
  }

  return (
    <div className="flex min-h-screen bg-gray-50">
      <AdminSidebar />
      <main className="flex-1 overflow-auto">
        <div className="bg-white border-b border-gray-200 px-6 py-3 text-xs text-gray-500">
          대출 &gt; <Link href="/admin/loan/review" className="hover:underline">본심사 목록</Link> &gt;{' '}
          <span className="text-gray-800 font-medium">신청 직접 심사</span>
        </div>
        <div className="px-6 py-5 max-w-lg">
          <h1 className="text-lg font-bold text-gray-800 mb-6">신청 직접 심사</h1>

          {err && <div className="mb-4 px-4 py-2 bg-red-50 border border-red-300 text-red-700 text-sm rounded">{err}</div>}

          <div className="bg-white border border-gray-200 rounded-lg p-6 space-y-4">
            <div>
              <label className="block text-[13px] font-medium text-gray-700 mb-1">신청 ID *</label>
              <input type="number" value={applId} onChange={e => setApplId(e.target.value)}
                placeholder="applId를 입력하세요"
                className="w-full border border-gray-300 rounded px-3 py-2 text-[13px] focus:outline-none" />
            </div>
            <div>
              <label className="block text-[13px] font-medium text-gray-700 mb-1">심사 유형</label>
              <select value={revType} onChange={e => setRevType(e.target.value)}
                className="w-full border border-gray-300 rounded px-3 py-2 text-[13px] focus:outline-none">
                <option value="MANUAL">수동</option>
                <option value="AUTO">자동</option>
              </select>
            </div>
            <div>
              <label className="block text-[13px] font-medium text-gray-700 mb-1">심사 결정 *</label>
              <select value={revDecision} onChange={e => setRevDecision(e.target.value)}
                className="w-full border border-gray-300 rounded px-3 py-2 text-[13px] focus:outline-none">
                <option value="APPROVED">승인</option>
                <option value="REJECTED">거절</option>
              </select>
            </div>
            <div className="flex gap-3 pt-2">
              <Link href="/admin/loan/review"
                className="flex-1 text-center py-2.5 border border-gray-300 text-[13px] rounded hover:bg-gray-50">
                취소
              </Link>
              {canRun ? (
                <button onClick={submit} disabled={busy || !applId}
                  className="flex-1 py-2.5 bg-kb-admin text-white text-[13px] rounded hover:opacity-90 disabled:opacity-50">
                  {busy ? '처리 중...' : '심사 시작'}
                </button>
              ) : (
                <span className="flex-1 py-2.5 text-center text-[12px] text-gray-400 border border-gray-200 rounded">
                  심사 실행 권한 없음 (심사역·운영팀 전용)
                </span>
              )}
            </div>
          </div>
        </div>
      </main>
    </div>
  )
}
