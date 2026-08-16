'use client'

import { useEffect, useState } from 'react'
import Link from 'next/link'
import AdminSidebar from '@/components/admin/AdminSidebar'
import { AdminUser } from '@/lib/admin-mock-data'
import { disposeQuarantine, fetchQuarantineReports,
         QuarantineDisposition, QuarantineReportDto } from '@/lib/audit-api'

type FilterType = 'ALL' | 'BIAS_DETECTION' | 'COMPLIANCE_VERIFICATION'
type FilterSev  = 'ALL' | 'WARN' | 'CRITICAL'

export default function QuarantinePage() {
  const [adminUser,   setAdminUser]   = useState<AdminUser | null>(null)
  const [reports,     setReports]     = useState<QuarantineReportDto[]>([])
  const [loading,     setLoading]     = useState(true)
  const [filterType,  setFilterType]  = useState<FilterType>('ALL')
  const [filterSev,   setFilterSev]   = useState<FilterSev>('ALL')
  const [selected,    setSelected]    = useState<QuarantineReportDto | null>(null)
  const [note,        setNote]        = useState('')
  const [disposing,   setDisposing]   = useState<QuarantineDisposition | null>(null)
  const [disposeErr,  setDisposeErr]  = useState('')

  // 격리를 푸는 것은 감사 대상이라 사유가 필수다. 서버도 빈 사유를 400 으로 막는다 —
  // 화면에서 미리 막지 않으면 눌러 보고 나서야 알게 된다.
  async function handleDispose(disposition: QuarantineDisposition) {
    if (!selected || !note.trim()) { setDisposeErr('처분 사유를 적어주세요.'); return }
    setDisposing(disposition)
    setDisposeErr('')
    try {
      await disposeQuarantine(selected.advrId, disposition, note.trim())
      // 정상 판정은 격리가 끝나 목록에서 빠지고, 재심사·조사 의뢰는 남는다.
      const next = await fetchQuarantineReports()
      setReports(next)
      setSelected(next.find(r => r.advrId === selected.advrId) ?? null)
      setNote('')
    } catch (e: unknown) {
      const err = e as { response?: { status?: number; data?: { message?: string } } }
      setDisposeErr(err.response?.status === 409
        ? '이미 처분된 리포트입니다.'
        : err.response?.data?.message ?? '처분에 실패했습니다.')
    } finally {
      setDisposing(null)
    }
  }

  useEffect(() => {
    try {
      const s = localStorage.getItem('admin_user')
      if (s) setAdminUser(JSON.parse(s))
    } catch {}
  }, [])

  useEffect(() => {
    if (!adminUser) return
    fetchQuarantineReports()
      .then(setReports)
      .catch(() => {})
      .finally(() => setLoading(false))
  }, [adminUser])

  if (!adminUser) return null

  const filtered = reports.filter((r) => {
    if (filterType !== 'ALL' && r.advisoryTypeCd !== filterType) return false
    if (filterSev  !== 'ALL' && r.severityCd      !== filterSev)  return false
    return true
  }).sort((a, b) => b.quarantinedAt.localeCompare(a.quarantinedAt))

  return (
    <div className="flex min-h-screen bg-kb-beige-light">
      <AdminSidebar />

      <main className="flex-1 overflow-auto">
        {/* 상단 바 */}
        <div className="bg-white border-b border-kb-border px-8 py-4 flex items-center gap-3">
          <Link href="/admin/audit" className="text-sm text-gray-400 hover:text-gray-600">AI 감사</Link>
          <span className="text-gray-300">/</span>
          <h1 className="text-lg font-bold text-gray-800">격리 리포트 관리</h1>
          <span className="ml-auto text-xs text-gray-400">QUARANTINE 상태 리포트 — 책임자 재심사 대기</span>
        </div>

        <div className="px-8 py-6 space-y-4">

          {/* 안내 배너 */}
          <div className="bg-red-50 border border-red-200 rounded px-4 py-3 flex items-start gap-3">
            <span className="text-red-400 mt-0.5">⚠</span>
            <div>
              <p className="text-sm font-semibold text-red-700">격리 리포트란?</p>
              <p className="text-sm text-red-600 mt-0.5">
                AI 감사 에이전트가 <strong>편향 의심(BIAS_SUSPECTED)</strong> 또는 <strong>위반 의심(VIOLATION_SUSPECTED)</strong> 결론을 내린 심사 건입니다.
                책임자(팀장 이상) 재심사 후 해소 또는 조사 의뢰 처리가 필요합니다.
              </p>
            </div>
          </div>

          {/* 필터 */}
          <div className="bg-white border border-kb-border rounded-lg px-5 py-3.5 flex items-center gap-6">
            <div className="flex items-center gap-2">
              <span className="text-xs font-medium text-gray-500">유형</span>
              {(['ALL', 'BIAS_DETECTION', 'COMPLIANCE_VERIFICATION'] as FilterType[]).map((v) => (
                <button
                  key={v}
                  onClick={() => setFilterType(v)}
                  className={`text-xs px-3 py-1 rounded-full border transition-colors ${
                    filterType === v
                      ? 'bg-gray-700 text-white border-gray-700'
                      : 'border-gray-200 text-gray-500 hover:border-gray-400'
                  }`}
                >
                  {v === 'ALL' ? '전체' : v === 'BIAS_DETECTION' ? '편향 탐지' : '규정 준수'}
                </button>
              ))}
            </div>
            <div className="w-px h-4 bg-gray-200" />
            <div className="flex items-center gap-2">
              <span className="text-xs font-medium text-gray-500">심각도</span>
              {(['ALL', 'WARN', 'CRITICAL'] as FilterSev[]).map((v) => (
                <button
                  key={v}
                  onClick={() => setFilterSev(v)}
                  className={`text-xs px-3 py-1 rounded-full border transition-colors ${
                    filterSev === v
                      ? 'bg-gray-700 text-white border-gray-700'
                      : 'border-gray-200 text-gray-500 hover:border-gray-400'
                  }`}
                >
                  {v === 'ALL' ? '전체' : v}
                </button>
              ))}
            </div>
            <span className="ml-auto text-xs text-gray-400">
              {loading ? '조회 중...' : `${filtered.length}건`}
            </span>
          </div>

          {/* 테이블 + 상세 패널 */}
          <div className="flex gap-4 items-start">
            {/* 리포트 목록 */}
            <div className={`bg-white border border-kb-border rounded-lg shadow-sm overflow-hidden ${selected ? 'flex-1' : 'w-full'}`}>
              <table className="w-full text-sm">
                <thead>
                  <tr className="bg-kb-beige-light text-xs text-kb-text-muted uppercase">
                    <th className="px-4 py-2.5 text-left font-medium">심사 ID</th>
                    <th className="px-4 py-2.5 text-left font-medium">대상 심사관</th>
                    <th className="px-4 py-2.5 text-left font-medium">리포트 제목</th>
                    <th className="px-4 py-2.5 text-center font-medium">유형</th>
                    <th className="px-4 py-2.5 text-center font-medium">심각도</th>
                    <th className="px-4 py-2.5 text-left font-medium">격리 시각</th>
                    <th className="px-4 py-2.5 text-center font-medium">상세</th>
                  </tr>
                </thead>
                <tbody className="divide-y divide-gray-100">
                  {loading ? (
                    <tr>
                      <td colSpan={7} className="px-4 py-8 text-center text-sm text-gray-400">
                        데이터 조회 중...
                      </td>
                    </tr>
                  ) : filtered.length === 0 ? (
                    <tr>
                      <td colSpan={7} className="px-4 py-8 text-center text-sm text-gray-400">
                        격리된 리포트가 없습니다.
                      </td>
                    </tr>
                  ) : filtered.map((r) => (
                    <tr
                      key={r.advrId}
                      className={`transition-colors cursor-pointer ${
                        selected?.advrId === r.advrId
                          ? 'bg-red-50'
                          : 'hover:bg-kb-beige-light'
                      }`}
                      onClick={() => setSelected(selected?.advrId === r.advrId ? null : r)}
                    >
                      <td className="px-4 py-3 text-gray-500 font-mono text-xs">rev-{r.revId}</td>
                      <td className="px-4 py-3 font-mono text-xs text-gray-700">#{r.targetReviewerId}</td>
                      <td className="px-4 py-3 text-gray-600 max-w-xs">
                        <span className="line-clamp-1">{r.advrTitle}</span>
                      </td>
                      <td className="px-4 py-3 text-center">
                        <AdvisoryTypeBadge type={r.advisoryTypeCd} />
                      </td>
                      <td className="px-4 py-3 text-center">
                        <SeverityBadge sev={r.severityCd} />
                      </td>
                      <td className="px-4 py-3 text-gray-400 text-xs">
                        {new Date(r.quarantinedAt).toLocaleString('ko-KR')}
                      </td>
                      <td className="px-4 py-3 text-center">
                        <span className="text-xs text-blue-500 hover:underline">
                          {selected?.advrId === r.advrId ? '닫기' : '보기'}
                        </span>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>

            {/* 상세 패널 */}
            {selected && (
              <div className="w-80 flex-shrink-0 bg-white border border-kb-border rounded-lg shadow-sm p-5 space-y-4">
                <div className="flex items-start justify-between">
                  <h3 className="text-sm font-bold text-gray-800">리포트 상세</h3>
                  <button onClick={() => setSelected(null)} className="text-gray-400 hover:text-gray-600 text-xs">✕ 닫기</button>
                </div>

                <div className="space-y-3 text-sm">
                  <DetailRow label="심사 ID"    value={`rev-${selected.revId}`} mono />
                  <DetailRow label="리포트 ID"  value={`advr-${selected.advrId}`} mono />
                  <DetailRow label="대상 심사관" value={`#${selected.targetReviewerId}`} mono />
                  <DetailRow label="심각도">
                    <SeverityBadge sev={selected.severityCd} />
                  </DetailRow>
                  <DetailRow label="격리 시각" value={new Date(selected.quarantinedAt).toLocaleString('ko-KR')} />
                  <DetailRow label="생성 시각" value={new Date(selected.generatedAt).toLocaleString('ko-KR')} />
                </div>

                <div className="border-t border-gray-100 pt-4">
                  <p className="text-xs font-medium text-gray-500 mb-2">리포트 제목</p>
                  <p className="text-xs text-gray-700 leading-relaxed">{selected.advrTitle}</p>
                </div>

                <div className="border-t border-gray-100 pt-4 space-y-2">
                  <p className="text-xs font-medium text-gray-500 mb-2">처리 액션</p>

                  {/* 사유가 먼저다. 격리를 푼 근거가 남지 않으면 통제가 아니라
                      그냥 상태 변경이다. */}
                  <textarea
                    value={note}
                    onChange={e => setNote(e.target.value)}
                    rows={2}
                    placeholder="처분 사유 (필수)"
                    className="w-full text-xs px-2 py-1.5 rounded border border-kb-border outline-none focus:border-kb-mint resize-none"
                  />
                  {disposeErr && <p className="text-[11px] text-kb-danger">{disposeErr}</p>}

                  <button
                    onClick={() => handleDispose('REVIEW_REASSIGNED')}
                    disabled={disposing !== null || !note.trim()}
                    className="w-full text-xs py-2 px-3 rounded border border-blue-200 text-blue-600 hover:bg-blue-50 transition-colors disabled:opacity-40">
                    {disposing === 'REVIEW_REASSIGNED' ? '처리 중…' : '재심사 배정'}
                  </button>
                  <button
                    onClick={() => handleDispose('AUDIT_REFERRED')}
                    disabled={disposing !== null || !note.trim()}
                    className="w-full text-xs py-2 px-3 rounded border border-orange-200 text-orange-600 hover:bg-orange-50 transition-colors disabled:opacity-40">
                    {disposing === 'AUDIT_REFERRED' ? '처리 중…' : '감사부 조사 의뢰'}
                  </button>
                  <button
                    onClick={() => handleDispose('RELEASED')}
                    disabled={disposing !== null || !note.trim()}
                    className="w-full text-xs py-2 px-3 rounded border border-green-200 text-green-600 hover:bg-green-50 transition-colors disabled:opacity-40">
                    {disposing === 'RELEASED' ? '처리 중…' : '정상 판정 (격리 해제)'}
                  </button>
                </div>
              </div>
            )}
          </div>

        </div>
      </main>
    </div>
  )
}

// ── 하위 컴포넌트 ───────────────────────────────────────────────

function AdvisoryTypeBadge({ type }: { type: string }) {
  if (type === 'BIAS_DETECTION')
    return <span className="text-xs px-2 py-0.5 rounded bg-purple-100 text-purple-700 font-medium">편향 탐지</span>
  if (type === 'COMPLIANCE_VERIFICATION')
    return <span className="text-xs px-2 py-0.5 rounded bg-blue-100 text-blue-700 font-medium">규정 준수</span>
  return <span className="text-xs px-2 py-0.5 rounded bg-gray-100 text-gray-600 font-medium">{type}</span>
}

function SeverityBadge({ sev }: { sev: 'WARN' | 'CRITICAL' }) {
  if (sev === 'CRITICAL')
    return <span className="text-xs px-2 py-0.5 rounded-full bg-red-100 text-red-700 font-bold">CRITICAL</span>
  return <span className="text-xs px-2 py-0.5 rounded-full bg-yellow-100 text-yellow-700 font-medium">WARN</span>
}

function DetailRow({
  label, value, mono, children,
}: {
  label: string; value?: string; mono?: boolean; children?: React.ReactNode
}) {
  return (
    <div className="flex items-center justify-between gap-2">
      <span className="text-xs text-gray-400 flex-shrink-0">{label}</span>
      {children ?? (
        <span className={`text-xs text-gray-700 text-right ${mono ? 'font-mono' : ''}`}>{value}</span>
      )}
    </div>
  )
}
