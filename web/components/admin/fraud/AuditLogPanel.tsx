'use client'

import { AuditLogEntry, DECISION_KIND_LABEL, STATUS_LABEL } from '@/lib/fraud-agent-api'

/**
 * 감사 로그 조회 화면 — 조사(AI 권고)와 승인 후 실행(직원 판단)을 함께 보여준다.
 *
 * `/admin/fraud` 페이지에서는 선택된 사건으로 좁혀 인라인으로, `/admin/fraud/audit`
 * 에서는 독립 진입점으로 쓴다. 두 자리가 같은 계약(GET /api/audit)을 보므로
 * 컴포넌트를 나누지 않고 하나로 공유한다 — 갈라두면 필드 하나가 한쪽에서만 바뀐다.
 */
export type AuditScope = 'case' | 'all'

export function AuditLogPanel({ entries, loading, scope, caseAlertId, onScopeChange, onRefresh }: {
  entries: AuditLogEntry[]
  loading: boolean
  scope: AuditScope
  /** 이 값이 있어야 "이 사건만" 토글이 켜진다. 없으면 전체 최신만 볼 수 있다. */
  caseAlertId?: string | null
  onScopeChange: (scope: AuditScope) => void
  onRefresh: () => void
}) {
  const hasCase = !!caseAlertId

  return (
    <div className="bg-white border border-gray-200 rounded-lg p-5">
      <div className="flex items-center justify-between mb-1 pb-2 border-b border-gray-100">
        <h2 className="text-[13px] font-semibold text-gray-700">감사 로그</h2>
        <div className="flex items-center gap-2">
          <div className="flex rounded border border-gray-200 overflow-hidden">
            {([['case', '이 사건만'], ['all', '전체 최신']] as const).map(([k, label]) => (
              <button key={k} onClick={() => onScopeChange(k)} disabled={k === 'case' && !hasCase}
                className={`px-2 py-1 text-[11px] transition-colors disabled:opacity-40 ${
                  scope === k ? 'bg-kb-admin text-white' : 'bg-white text-gray-500 hover:bg-gray-50'}`}>
                {label}
              </button>
            ))}
          </div>
          <button onClick={onRefresh} disabled={loading}
            className="px-2 py-1 text-[11px] border border-gray-300 text-gray-600 rounded hover:bg-gray-100 disabled:opacity-50">
            {loading ? '조회 중...' : '새로고침'}
          </button>
        </div>
      </div>
      <p className="text-[11px] text-gray-400 mb-2">
        조사(AI 권고)와 승인 후 실행(직원 판단)이 추가만 되는 감사 저장소에 남는다. 여기 보이는 것이 전부다 — 수정·삭제 경로가 없다.
      </p>

      <div className="space-y-2 max-h-96 overflow-y-auto">
        {entries.map((e, i) => <AuditLogRow key={i} entry={e} />)}
        {!loading && entries.length === 0 && (
          <p className="text-[12px] text-gray-400 py-4 text-center">
            {scope === 'case' ? '이 사건에 대한 기록이 아직 없습니다.' : '기록이 없습니다.'}
          </p>
        )}
      </div>
    </div>
  )
}

function AuditLogRow({ entry }: { entry: AuditLogEntry }) {
  const isExecution = entry.decision_kind === 'ACTION_EXECUTION'
  const executedActions = Array.isArray(entry.output?.executed_actions)
    ? (entry.output.executed_actions as string[]) : []
  const recommendation = entry.output?.recommendation as { status?: string } | undefined

  return (
    <div className={`border rounded px-3 py-2 ${isExecution ? 'border-blue-200 bg-blue-50/40' : 'border-gray-200 bg-gray-50/60'}`}>
      <div className="flex items-center gap-2 mb-1">
        <span className={`text-[10px] px-1.5 py-0.5 rounded font-semibold ${
          isExecution ? 'bg-blue-100 text-blue-700' : 'bg-purple-100 text-purple-700'}`}>
          {DECISION_KIND_LABEL[entry.decision_kind] ?? entry.decision_kind}
        </span>
        <span className="text-[11px] font-mono text-gray-600">{entry.alert_id}</span>
        <span className="ml-auto text-[10px] text-gray-400">
          {new Date(entry.recorded_at).toLocaleString('ko-KR')}
        </span>
      </div>
      {isExecution ? (
        <>
          <p className="text-[11px] text-gray-600 mb-0.5">
            <span className="text-gray-400 mr-1">행위자</span>
            {entry.actor_id ?? <span className="text-amber-600">미확인(게이트웨이 미경유)</span>}
            {entry.actor_roles.length > 0 && <span className="text-gray-400"> · {entry.actor_roles.join(', ')}</span>}
          </p>
          <ul className="space-y-0.5">
            {executedActions.map((a, i) => (
              <li key={i} className={`text-[11px] ${
                a.startsWith('실행') ? 'text-green-700' : a.startsWith('거부') ? 'text-red-600' : 'text-gray-600'}`}>
                {a}
              </li>
            ))}
          </ul>
        </>
      ) : (
        <p className="text-[11px] text-gray-600">
          <span className="text-gray-400 mr-1">권고 상태</span>
          {STATUS_LABEL[recommendation?.status ?? ''] ?? recommendation?.status ?? '-'}
        </p>
      )}
    </div>
  )
}
