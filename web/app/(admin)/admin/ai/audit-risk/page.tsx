'use client'

import { useState, useCallback } from 'react'
import AdminSidebar from '@/components/admin/AdminSidebar'
import {
  getRecentAuditOpinions,
  getTopBiasRiskScores,
  getTopComplianceRiskScores,
  getQuarantineList,
} from '@/lib/advisory-api'

const CONCLUSION_CLS: Record<string, string> = {
  BIAS_SUSPECTED:       'bg-red-100 text-red-700 border-red-300',
  VIOLATION_SUSPECTED:  'bg-orange-100 text-orange-700 border-orange-300',
  CLEAN:                'bg-green-100 text-green-700 border-green-300',
  NEEDS_REVIEW:         'bg-yellow-100 text-yellow-700 border-yellow-200',
}

function fmt(iso?: string) {
  if (!iso) return '-'
  return new Date(iso).toLocaleString('ko-KR', { year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' })
}

export default function AdminAuditRiskPage() {
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  const [opinions, setOpinions]         = useState<any[]>([])
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  const [topBias, setTopBias]           = useState<any[]>([])
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  const [topCompliance, setTopCompliance] = useState<any[]>([])
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  const [quarantine, setQuarantine]     = useState<any[]>([])
  const [loading, setLoading]           = useState(false)
  const [err, setErr]                   = useState('')

  function fail(m: string) { setErr(m); setTimeout(() => setErr(''), 4000) }

  const load = useCallback(async () => {
    setLoading(true); setErr('')
    const results = await Promise.allSettled([
      getRecentAuditOpinions(20),
      getTopBiasRiskScores(10),
      getTopComplianceRiskScores(10),
      getQuarantineList(),
    ])
    if (results[0].status === 'fulfilled') setOpinions(results[0].value)
    else fail('감사 의견 조회 실패')
    if (results[1].status === 'fulfilled') setTopBias(results[1].value)
    if (results[2].status === 'fulfilled') setTopCompliance(results[2].value)
    if (results[3].status === 'fulfilled') setQuarantine(results[3].value)
    setLoading(false)
  }, [])

  return (
    <div className="flex min-h-screen bg-gray-50">
      <AdminSidebar />
      <main className="flex-1 overflow-auto">
        <div className="bg-white border-b border-gray-200 px-6 py-3 text-xs text-gray-500">
          AI 심사지원 &gt; <span className="text-gray-800 font-medium">감사·리스크 대시보드</span>
        </div>
        <div className="px-6 py-5 max-w-7xl">
          <div className="flex items-center justify-between mb-5">
            <h1 className="text-lg font-bold text-gray-800">AI 감사 · 심사관 리스크 대시보드</h1>
            <button onClick={load} disabled={loading}
              className="px-5 py-1.5 text-[13px] bg-kb-admin text-white rounded hover:opacity-90 disabled:opacity-50">
              {loading ? '조회 중...' : '전체 새로고침'}
            </button>
          </div>

          {err && <div className="mb-4 px-4 py-2 bg-red-50 border border-red-300 text-red-700 text-sm rounded">{err}</div>}

          <div className="grid grid-cols-2 gap-5 mb-5">
            {/* 편향 리스크 TOP 10 */}
            <Card title="편향 위험도 상위 심사관 (Top 10)">
              {topBias.length === 0 ? (
                <Empty />
              ) : (
                <table className="w-full text-[12px]">
                  <thead>
                    <tr className="border-b border-gray-100">
                      {['심사관 ID', '편향 점수', '규정 준수', '평가 건수', '최종 평가'].map(h => (
                        <th key={h} className="pb-2 text-left text-[11px] text-gray-500 font-semibold">{h}</th>
                      ))}
                    </tr>
                  </thead>
                  <tbody className="divide-y divide-gray-50">
                    {topBias.map((s, i: number) => (
                      <tr key={i}>
                        <td className="py-1.5 text-gray-700 font-mono">{s.reviewerId}</td>
                        <td className="py-1.5">
                          <span className={`font-bold ${s.biasScore >= 0.7 ? 'text-red-600' : s.biasScore >= 0.4 ? 'text-orange-500' : 'text-green-600'}`}>
                            {(s.biasScore * 100).toFixed(1)}
                          </span>
                        </td>
                        <td className="py-1.5 text-gray-500">{(s.complianceScore * 100).toFixed(1)}</td>
                        <td className="py-1.5 text-gray-500">{s.evaluationCount}</td>
                        <td className="py-1.5 text-gray-400 text-[11px]">{fmt(s.lastEvaluatedAt)}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              )}
            </Card>

            {/* 규정 준수 리스크 TOP 10 */}
            <Card title="규정 준수 위험도 상위 심사관 (Top 10)">
              {topCompliance.length === 0 ? (
                <Empty />
              ) : (
                <table className="w-full text-[12px]">
                  <thead>
                    <tr className="border-b border-gray-100">
                      {['심사관 ID', '규정 점수', '편향 점수', '평가 건수', '최종 평가'].map(h => (
                        <th key={h} className="pb-2 text-left text-[11px] text-gray-500 font-semibold">{h}</th>
                      ))}
                    </tr>
                  </thead>
                  <tbody className="divide-y divide-gray-50">
                    {topCompliance.map((s, i: number) => (
                      <tr key={i}>
                        <td className="py-1.5 text-gray-700 font-mono">{s.reviewerId}</td>
                        <td className="py-1.5">
                          <span className={`font-bold ${s.complianceScore >= 0.7 ? 'text-red-600' : s.complianceScore >= 0.4 ? 'text-orange-500' : 'text-green-600'}`}>
                            {(s.complianceScore * 100).toFixed(1)}
                          </span>
                        </td>
                        <td className="py-1.5 text-gray-500">{(s.biasScore * 100).toFixed(1)}</td>
                        <td className="py-1.5 text-gray-500">{s.evaluationCount}</td>
                        <td className="py-1.5 text-gray-400 text-[11px]">{fmt(s.lastEvaluatedAt)}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              )}
            </Card>
          </div>

          {/* 격리 목록 */}
          <Card title={`격리(Quarantine) 리포트 ${quarantine.length > 0 ? `(${quarantine.length}건)` : ''}`} className="mb-5">
            {quarantine.length === 0 ? (
              <Empty />
            ) : (
              <table className="w-full text-[12px]">
                <thead>
                  <tr className="border-b border-gray-100">
                    {['advrId', '심사 ID', '유형', '심각도', '제목', '대상 심사관', '격리일시'].map(h => (
                      <th key={h} className="pb-2 text-left text-[11px] text-gray-500 font-semibold">{h}</th>
                    ))}
                  </tr>
                </thead>
                <tbody className="divide-y divide-gray-50">
                  {quarantine.map((q, i: number) => (
                    <tr key={i}>
                      <td className="py-1.5 text-gray-400 font-mono text-[11px]">{q.advrId}</td>
                      <td className="py-1.5 text-gray-700">{q.revId}</td>
                      <td className="py-1.5 text-gray-500 text-[11px]">{q.advisoryTypeCd}</td>
                      <td className="py-1.5">
                        <span className={`text-[10px] px-1.5 py-0.5 rounded border ${
                          q.severityCd === 'CRITICAL' ? 'bg-red-100 text-red-700 border-red-300' :
                          q.severityCd === 'HIGH'     ? 'bg-orange-100 text-orange-700 border-orange-300' :
                          'bg-gray-100 text-gray-600 border-gray-300'}`}>
                          {q.severityCd}
                        </span>
                      </td>
                      <td className="py-1.5 text-gray-800 font-medium max-w-xs truncate">{q.advrTitle}</td>
                      <td className="py-1.5 text-gray-500 font-mono">{q.targetReviewerId ?? '-'}</td>
                      <td className="py-1.5 text-gray-400 text-[11px]">{fmt(q.quarantinedAt)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            )}
          </Card>

          {/* 최근 감사 의견 */}
          <Card title={`최근 LLM 감사 의견 ${opinions.length > 0 ? `(최근 ${opinions.length}건)` : ''}`}>
            {opinions.length === 0 ? (
              <Empty />
            ) : (
              <div className="space-y-2 max-h-80 overflow-y-auto">
                {opinions.map((o, i: number) => (
                  <div key={i} className={`text-[12px] border rounded px-3 py-2 ${CONCLUSION_CLS[o.conclusionCd] ?? 'bg-gray-50 border-gray-200'}`}>
                    <div className="flex items-center gap-2 mb-0.5">
                      <span className="text-[10px] font-semibold">{o.analysisTypeCd}</span>
                      <span className="font-bold">{o.conclusionCd}</span>
                      <span className="ml-auto text-[10px] text-gray-400">{fmt(o.generatedAt)}</span>
                      {o.confidenceScore != null && (
                        <span className="text-[10px]">신뢰도 {(o.confidenceScore * 100).toFixed(0)}%</span>
                      )}
                    </div>
                    {o.reasoningSummary && (
                      <p className="text-[11px] opacity-80 line-clamp-2">{o.reasoningSummary}</p>
                    )}
                    <div className="flex gap-3 mt-0.5 text-[10px] opacity-60">
                      <span>advrId: {o.advrId}</span>
                      <span>revId: {o.revId}</span>
                      <span>심사관: {o.reviewerId}</span>
                    </div>
                  </div>
                ))}
              </div>
            )}
          </Card>
        </div>
      </main>
    </div>
  )
}

function Card({ title, children, className = '' }: { title: string; children: React.ReactNode; className?: string }) {
  return (
    <div className={`bg-white border border-gray-200 rounded-lg p-5 ${className}`}>
      <h2 className="text-[13px] font-semibold text-gray-700 mb-4 pb-2 border-b border-gray-100">{title}</h2>
      {children}
    </div>
  )
}

function Empty() {
  return <p className="text-[12px] text-gray-400 py-4 text-center">새로고침 버튼으로 데이터를 불러오세요.</p>
}
