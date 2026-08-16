'use client'

import { useState, useCallback } from 'react'
import AdminSidebar from '@/components/admin/AdminSidebar'
import {
  getHumanReviewQueue,
  decideHumanReview,
  enableLegalHold,
  disableLegalHold,
  DocSubmission,
  HumanReviewDecision,
} from '@/lib/doc-agent-api'

const VERIFY_CLS: Record<string, string> = {
  HOLD:          'bg-red-100 text-red-700 border-red-300',
  PENDING:       'bg-yellow-100 text-yellow-700 border-yellow-200',
  AUTO_PASS:     'bg-green-100 text-green-700 border-green-300',
  CLEARED:       'bg-green-100 text-green-700 border-green-300',
  LOCKED:        'bg-purple-100 text-purple-700 border-purple-300',
  NEEDS_RESUBMIT:'bg-orange-100 text-orange-700 border-orange-300',
}

export default function AdminDocReviewPage() {
  const [queue, setQueue]         = useState<DocSubmission[]>([])
  const [loading, setLoading]     = useState(false)
  const [busy, setBusy]           = useState<string | null>(null)
  const [msg, setMsg]             = useState('')
  const [err, setErr]             = useState('')
  const [reviewerId, setReviewerId] = useState('admin01')
  const [decideTarget, setDecideTarget] = useState<string | null>(null)

  function notify(m: string) { setMsg(m); setTimeout(() => setMsg(''), 3000) }
  function fail(m: string)   { setErr(m); setTimeout(() => setErr(''), 4000) }

  const load = useCallback(async () => {
    setLoading(true); setErr('')
    try {
      const data = await getHumanReviewQueue()
      setQueue(data)
    } catch { fail('검토 큐를 불러오지 못했습니다.') }
    finally { setLoading(false) }
  }, [])

  async function decide(submissionId: string, decision: HumanReviewDecision) {
    setBusy(submissionId)
    try {
      await decideHumanReview(submissionId, decision, reviewerId)
      notify(`${decision === 'CLEARED' ? '승인(통과)' : '위변조 확정'} 처리 완료`)
      setDecideTarget(null)
      await load()
    } catch (e) { fail((e as {response?: {data?: {message?: string}}})?.response?.data?.message ?? '처리 실패') }
    finally { setBusy(null) }
  }

  async function toggleLegalHold(submissionId: string, currentHold: boolean) {
    setBusy(submissionId)
    try {
      if (currentHold) {
        await disableLegalHold(submissionId)
        notify('리걸홀드 해제 완료')
      } else {
        await enableLegalHold(submissionId)
        notify('리걸홀드 설정 완료')
      }
      await load()
    } catch (e) { fail((e as {response?: {data?: {message?: string}}})?.response?.data?.message ?? '리걸홀드 처리 실패') }
    finally { setBusy(null) }
  }

  return (
    <div className="flex min-h-screen bg-gray-50">
      <AdminSidebar />
      <main className="flex-1 overflow-auto">
        <div className="bg-white border-b border-gray-200 px-6 py-3 text-xs text-gray-500">
          AI 심사지원 &gt; <span className="text-gray-800 font-medium">서류 검토 큐</span>
        </div>
        <div className="px-6 py-5 max-w-6xl">
          <div className="flex items-center justify-between mb-5 flex-wrap gap-3">
            <h1 className="text-lg font-bold text-gray-800">휴먼리뷰 서류 검토 큐</h1>
            <div className="flex gap-2 items-center">
              <label className="text-[12px] text-gray-600 flex items-center gap-1.5">
                검토자 ID
                <input type="text" value={reviewerId} onChange={e => setReviewerId(e.target.value)}
                  className="border border-gray-300 rounded px-2 py-1 text-[12px] w-28 focus:outline-none" />
              </label>
              <button onClick={load} disabled={loading}
                className="px-5 py-1.5 text-[13px] bg-kb-admin text-white rounded hover:opacity-90 disabled:opacity-50">
                {loading ? '조회 중...' : '큐 조회'}
              </button>
            </div>
          </div>

          {msg && <div className="mb-4 px-4 py-2 bg-green-50 border border-green-300 text-green-700 text-sm rounded">{msg}</div>}
          {err && <div className="mb-4 px-4 py-2 bg-red-50 border border-red-300 text-red-700 text-sm rounded">{err}</div>}

          {queue.length > 0 ? (
            <div className="space-y-2">
              {queue.map((s) => (
                <div key={s.submission_id} className="bg-white border border-gray-200 rounded-lg overflow-hidden">
                  <div className="flex items-center gap-3 px-4 py-3 flex-wrap">
                    <span className="text-gray-400 font-mono text-[11px] w-56 truncate" title={s.submission_id}>
                      {s.submission_id}
                    </span>
                    <span className="text-[12px] font-medium text-gray-800">{s.application_id}</span>
                    <span className="text-[11px] px-1.5 py-0.5 bg-gray-100 border border-gray-200 rounded text-gray-500">
                      {s.doc_code}
                    </span>
                    <span className={`text-[11px] px-2 py-0.5 rounded border ${VERIFY_CLS[s.verify_status] ?? 'bg-gray-100 text-gray-500 border-gray-200'}`}>
                      {s.verify_status}
                    </span>
                    {s.forgery_score !== '-' && (
                      <span className={`text-[11px] font-bold ${Number(s.forgery_score) >= 0.7 ? 'text-red-600' : Number(s.forgery_score) >= 0.4 ? 'text-orange-500' : 'text-green-600'}`}>
                        위변조 {(Number(s.forgery_score) * 100).toFixed(0)}%
                      </span>
                    )}
                    <span className="ml-auto text-[10px] text-gray-400">
                      {new Date(s.created_at).toLocaleString('ko-KR')}
                    </span>
                    {s.legal_hold && (
                      <span className="text-[10px] px-2 py-0.5 bg-purple-100 text-purple-700 border border-purple-300 rounded font-bold">
                        LEGAL HOLD
                      </span>
                    )}
                  </div>

                  <div className="px-4 pb-3 flex gap-2 flex-wrap">
                    {decideTarget !== s.submission_id ? (
                      <button
                        onClick={() => setDecideTarget(s.submission_id)}
                        disabled={busy === s.submission_id}
                        className="text-[11px] px-3 py-1 bg-kb-admin text-white rounded hover:opacity-90 disabled:opacity-50">
                        검토 결정
                      </button>
                    ) : (
                      <>
                        <button onClick={() => decide(s.submission_id, 'CLEARED')} disabled={busy === s.submission_id}
                          className="text-[11px] px-3 py-1 bg-green-600 text-white rounded hover:opacity-90 disabled:opacity-50">
                          {busy === s.submission_id ? '처리 중...' : '✓ 승인 (통과)'}
                        </button>
                        <button onClick={() => decide(s.submission_id, 'CONFIRMED_FORGERY')} disabled={busy === s.submission_id}
                          className="text-[11px] px-3 py-1 bg-red-600 text-white rounded hover:opacity-90 disabled:opacity-50">
                          {busy === s.submission_id ? '처리 중...' : '✕ 위변조 확정'}
                        </button>
                        <button onClick={() => setDecideTarget(null)}
                          className="text-[11px] px-3 py-1 border border-gray-300 rounded hover:bg-gray-50">
                          취소
                        </button>
                      </>
                    )}
                    <button
                      onClick={() => toggleLegalHold(s.submission_id, s.legal_hold)}
                      disabled={busy === s.submission_id}
                      className={`text-[11px] px-3 py-1 border rounded hover:opacity-90 disabled:opacity-50 ${
                        s.legal_hold
                          ? 'border-purple-300 text-purple-700 hover:bg-purple-50'
                          : 'border-gray-300 text-gray-600 hover:bg-gray-50'
                      }`}>
                      {s.legal_hold ? '리걸홀드 해제' : '리걸홀드 설정'}
                    </button>
                  </div>
                </div>
              ))}
            </div>
          ) : (
            !loading && (
              <div className="bg-white border border-gray-200 rounded-lg p-10 text-center text-sm text-gray-400">
                큐 조회 버튼을 눌러 검토 대기 서류를 확인하세요.
              </div>
            )
          )}
        </div>
      </main>
    </div>
  )
}
