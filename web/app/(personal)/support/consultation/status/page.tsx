'use client'

import { Suspense, useCallback, useEffect, useState } from 'react'
import Link from 'next/link'
import { useSearchParams } from 'next/navigation'
import { getConsultStatus, ConsultStatus } from '@/lib/risk-consult'

/**
 * 이상거래 상담 처리 현황 조회.
 *
 * **왜 필요한가.** 위험 거래로 이체가 막혀 "상담 연결"을 누른 고객은 그 순간
 * "접수됐다, 검토 후 연락드린다"는 안내만 받는다(`/transfer/result`). 그 뒤 담당
 * 직원이 조사·승인을 마쳐도 고객에게 결과를 다시 보여주는 화면이 없었다 — 접수번호를
 * 쥐고 있어도 확인할 곳이 없었다. 이 화면이 그 빈 자리다.
 *
 * **왜 접수번호 입력을 받는가.** 고객별 상담 이력을 한 번에 모아 보여주는 API가
 * 아직 없다(최소 구현). 접수 직후에는 `?case_id=` 로 바로 열리고, 나중에 다시
 * 찾아올 때는 접수번호를 알고 있어야 한다 — 접수 시 화면에 그 번호를 보여준 이유다.
 */
export default function ConsultStatusPage() {
  return (
    <Suspense>
      <ConsultStatusView />
    </Suspense>
  )
}

const STATUS_LABEL: Record<ConsultStatus['status'], string> = {
  PENDING: '접수 완료 · 확인 대기',
  UNDER_REVIEW: '조사 완료 · 담당자 확인 중',
  RESOLVED: '처리 완료',
}

const STATUS_CLS: Record<ConsultStatus['status'], string> = {
  PENDING: 'bg-gray-100 text-gray-600 border-gray-300',
  UNDER_REVIEW: 'bg-amber-100 text-amber-700 border-amber-300',
  RESOLVED: 'bg-green-100 text-green-700 border-green-300',
}

function ConsultStatusView() {
  const searchParams = useSearchParams()
  const linkedCaseId = searchParams.get('case_id') ?? ''

  const [caseId, setCaseId] = useState(linkedCaseId)
  const [result, setResult] = useState<ConsultStatus | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [loading, setLoading] = useState(false)

  const lookup = useCallback(async (id: string) => {
    if (!id.trim()) return
    setLoading(true); setError(null); setResult(null)
    const r = await getConsultStatus(id.trim())
    if (r.ok) setResult(r.data)
    else setError(r.message)
    setLoading(false)
  }, [])

  // 접수 직후 링크(?case_id=)로 들어온 경우 바로 조회한다.
  useEffect(() => { if (linkedCaseId) lookup(linkedCaseId) }, [linkedCaseId, lookup])

  return (
    <div className="min-h-screen" style={{ backgroundColor: '#F8FDFB' }}>
      <div className="max-w-kb-container mx-auto px-6 py-8">
        <div className="flex items-center gap-1 text-[12px] text-kb-text-muted mb-6">
          <Link href="/" className="hover:underline">홈</Link>
          <span>›</span>
          <span>고객센터</span>
          <span>›</span>
          <span>고객상담</span>
          <span>›</span>
          <span className="font-semibold text-kb-text">이상거래 상담 처리 현황</span>
        </div>

        <main className="w-full max-w-xl">
          <h1 className="text-[22px] font-bold text-kb-text mb-2">이상거래 상담 처리 현황</h1>
          <p className="text-[13px] text-kb-text-muted mb-6">
            이체가 막혀 상담을 요청하셨을 때 받은 접수번호로 처리 현황을 확인하세요.
          </p>

          <div className="flex gap-2 mb-6">
            <input
              type="text"
              value={caseId}
              onChange={e => setCaseId(e.target.value)}
              onKeyDown={e => { if (e.key === 'Enter') lookup(caseId) }}
              placeholder="접수번호 (예: consult-9001-1234567890)"
              className="flex-1 border border-kb-border rounded px-3 py-2 text-[13px] outline-none focus:border-kb-primary"
            />
            <button
              onClick={() => lookup(caseId)}
              disabled={loading || !caseId.trim()}
              className="px-5 py-2 text-[13px] font-semibold text-white rounded bg-kb-primary hover:opacity-90 disabled:opacity-50"
            >
              {loading ? '조회 중...' : '조회'}
            </button>
          </div>

          {error && (
            <div className="px-4 py-3 bg-red-50 border border-red-300 text-red-700 text-[13px] rounded mb-4">
              {error}
            </div>
          )}

          {result && (
            <div className="border border-kb-border rounded-xl p-5 bg-white">
              <div className="flex items-center gap-2 mb-3">
                <span className={`text-[12px] px-2 py-0.5 rounded border font-semibold ${STATUS_CLS[result.status]}`}>
                  {STATUS_LABEL[result.status]}
                </span>
                <span className="ml-auto text-[11px] font-mono text-kb-text-muted">{result.case_id}</span>
              </div>
              <p className="text-[14px] text-kb-text mb-3">{result.message}</p>
              <dl className="grid grid-cols-2 gap-y-1.5 text-[12px] border-t border-kb-border pt-3">
                <dt className="text-kb-text-muted">금액</dt>
                <dd className="text-right font-semibold text-kb-text">{result.amount.toLocaleString('ko-KR')}원</dd>
                <dt className="text-kb-text-muted">받는분</dt>
                <dd className="text-right text-kb-text">{result.payee || '-'}</dd>
                {result.submitted_at && (
                  <>
                    <dt className="text-kb-text-muted">접수 시각</dt>
                    <dd className="text-right text-kb-text">{new Date(result.submitted_at).toLocaleString('ko-KR')}</dd>
                  </>
                )}
              </dl>
              {result.status !== 'RESOLVED' && (
                <p className="text-[11px] text-kb-text-muted mt-3">
                  담당자 확인이 끝나면 이 화면에서 처리 결과를 다시 확인하실 수 있습니다.
                </p>
              )}
            </div>
          )}

          <p className="text-[11px] text-kb-text-muted mt-6">
            문의사항은 고객센터(1588-9999)로 연락해 주세요.
          </p>
        </main>
      </div>
    </div>
  )
}
