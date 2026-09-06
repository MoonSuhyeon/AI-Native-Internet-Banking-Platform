'use client'

import { Suspense, useCallback, useEffect, useState } from 'react'
import Link from 'next/link'
import { useSearchParams } from 'next/navigation'
import AdminSidebar from '@/components/admin/AdminSidebar'
import { AuditLogPanel, AuditScope } from '@/components/admin/fraud/AuditLogPanel'
import { listAuditLog, AuditLogEntry, errMsg } from '@/lib/fraud-agent-api'

/**
 * 감사 로그 독립 진입점.
 *
 * `/admin/fraud`(조사 콘솔) 안에도 같은 패널이 있지만, 그건 지금 조사 중인 사건에
 * 맞춰 좁혀진다. 이 화면은 조사를 열지 않고도 — 또는 `?alert_id=` 로 특정 사건을
 * 딥링크해 — 감사 이력만 바로 훑어보려는 사람을 위한 자리다.
 */
export default function FraudAuditLogPage() {
  return (
    <Suspense>
      <FraudAuditLogView />
    </Suspense>
  )
}

function FraudAuditLogView() {
  const searchParams = useSearchParams()
  const linkedAlertId = searchParams.get('alert_id')

  const [entries, setEntries] = useState<AuditLogEntry[]>([])
  const [loading, setLoading] = useState(false)
  const [error, setError]     = useState<string | null>(null)
  const [scope, setScope]     = useState<AuditScope>(linkedAlertId ? 'case' : 'all')

  const load = useCallback(async (s: AuditScope) => {
    setLoading(true); setError(null)
    try {
      setEntries(await listAuditLog(s === 'case' ? (linkedAlertId ?? undefined) : undefined))
    } catch (e) {
      setError(errMsg(e, '감사 로그 조회에 실패했습니다.'))
    } finally {
      setLoading(false)
    }
  }, [linkedAlertId])

  useEffect(() => { load(scope) }, [load, scope])

  return (
    <div className="flex min-h-screen bg-gray-50">
      <AdminSidebar />
      <main className="flex-1 overflow-auto">
        <div className="bg-white border-b border-gray-200 px-6 py-3 text-xs text-gray-500 flex items-center justify-between">
          <span>
            이상거래 조사 &gt; <span className="text-gray-800 font-medium">감사 로그</span>
          </span>
          <Link href="/" className="text-gray-500 hover:text-gray-800 transition-colors">
            고객 화면으로 ↗
          </Link>
        </div>

        <div className="px-6 py-5 max-w-4xl">
          <div className="flex items-center justify-between mb-1">
            <h1 className="text-lg font-bold text-gray-800">감사 로그</h1>
            <Link href="/admin/fraud" className="text-[13px] text-kb-admin hover:underline">
              조사 콘솔로 이동 →
            </Link>
          </div>
          <p className="text-xs text-gray-500 mb-4">
            AI 조사 에이전트의 권고와 직원의 승인·반려·실행 판단을 한곳에서 조회합니다.
            {linkedAlertId && <> 지금은 사건 <span className="font-mono">{linkedAlertId}</span> 로 좁혀져 있습니다.</>}
          </p>

          {error && <div className="mb-4 px-4 py-2 bg-red-50 border border-red-300 text-red-700 text-sm rounded">{error}</div>}

          <AuditLogPanel
            entries={entries}
            loading={loading}
            scope={scope}
            caseAlertId={linkedAlertId}
            onScopeChange={setScope}
            onRefresh={() => load(scope)}
          />
        </div>
      </main>
    </div>
  )
}
