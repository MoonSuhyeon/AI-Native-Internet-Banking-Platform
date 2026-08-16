'use client'

import { useState, useCallback } from 'react'
import AdminSidebar from '@/components/admin/AdminSidebar'
import {
  getRagDocuments,
  uploadRagDocument,
  reindexRagDocument,
  getRagDocumentIngestionLogs,
  bootstrapRagDocuments,
  RagDocument,
} from '@/lib/ai-api'

export default function AdminRagDocumentsPage() {
  const [docs, setDocs]           = useState<RagDocument[]>([])
  const [loading, setLoading]     = useState(false)
  const [busy, setBusy]           = useState(false)
  const [msg, setMsg]             = useState('')
  const [err, setErr]             = useState('')

  const [showUpload, setShowUpload]   = useState(false)
  const [uploadTitle, setUploadTitle] = useState('')
  const [uploadContent, setUploadContent] = useState('')

  const [logsDocId, setLogsDocId] = useState<string | null>(null)
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  const [logs, setLogs]           = useState<any[]>([])
  const [logsLoading, setLogsLoading] = useState(false)

  function notify(m: string) { setMsg(m); setTimeout(() => setMsg(''), 3000) }
  function fail(m: string)   { setErr(m); setTimeout(() => setErr(''), 4000) }

  const load = useCallback(async () => {
    setLoading(true)
    try {
      const data = await getRagDocuments()
      setDocs(Array.isArray(data) ? data : [])
    } catch { fail('문서 목록을 불러오지 못했습니다.') }
    finally { setLoading(false) }
  }, [])

  async function handleUpload() {
    if (!uploadTitle || !uploadContent) return
    setBusy(true)
    try {
      await uploadRagDocument({ title: uploadTitle, content: uploadContent })
      notify('문서가 업로드되었습니다.')
      setUploadTitle(''); setUploadContent(''); setShowUpload(false)
      await load()
    } catch (e) { fail((e as {response?: {data?: {message?: string}}})?.response?.data?.message ?? '업로드 실패') }
    finally { setBusy(false) }
  }

  async function handleReindex(docId: string) {
    setBusy(true)
    try {
      await reindexRagDocument(docId)
      notify(`문서 ${docId} 재인덱싱 요청 완료`)
      await load()
    } catch (e) { fail((e as {response?: {data?: {message?: string}}})?.response?.data?.message ?? '재인덱싱 실패') }
    finally { setBusy(false) }
  }

  async function handleBootstrap() {
    if (!confirm('시드 문서를 초기화하시겠습니까?')) return
    setBusy(true)
    try {
      await bootstrapRagDocuments()
      notify('Bootstrap 완료')
      await load()
    } catch (e) { fail((e as {response?: {data?: {message?: string}}})?.response?.data?.message ?? 'Bootstrap 실패') }
    finally { setBusy(false) }
  }

  async function loadLogs(docId: string) {
    if (logsDocId === docId) { setLogsDocId(null); setLogs([]); return }
    setLogsDocId(docId); setLogsLoading(true)
    try {
      const data = await getRagDocumentIngestionLogs(docId)
      setLogs(Array.isArray(data) ? data : data?.items ?? [])
    } catch { fail('인제스션 로그를 불러오지 못했습니다.') }
    finally { setLogsLoading(false) }
  }

  return (
    <div className="flex min-h-screen bg-gray-50">
      <AdminSidebar />
      <main className="flex-1 overflow-auto">
        <div className="bg-white border-b border-gray-200 px-6 py-3 text-xs text-gray-500">
          AI 심사지원 &gt; <span className="text-gray-800 font-medium">RAG 문서관리</span>
        </div>
        <div className="px-6 py-5 max-w-5xl">
          <div className="flex items-center justify-between mb-5">
            <h1 className="text-lg font-bold text-gray-800">RAG 지식베이스 문서관리</h1>
            <div className="flex gap-2">
              <button onClick={load} disabled={loading}
                className="px-4 py-1.5 text-[13px] border border-gray-300 rounded hover:bg-gray-50 disabled:opacity-50">
                {loading ? '조회 중...' : '목록 조회'}
              </button>
              <button onClick={() => setShowUpload(v => !v)}
                className="px-4 py-1.5 text-[13px] bg-kb-admin text-white rounded hover:opacity-90">
                {showUpload ? '업로드 닫기' : '문서 업로드'}
              </button>
              <button onClick={handleBootstrap} disabled={busy}
                className="px-4 py-1.5 text-[13px] border border-orange-300 text-orange-700 rounded hover:bg-orange-50 disabled:opacity-50">
                Bootstrap
              </button>
            </div>
          </div>

          {msg && <div className="mb-4 px-4 py-2 bg-green-50 border border-green-300 text-green-700 text-sm rounded">{msg}</div>}
          {err && <div className="mb-4 px-4 py-2 bg-red-50 border border-red-300 text-red-700 text-sm rounded">{err}</div>}

          {/* 업로드 폼 */}
          {showUpload && (
            <div className="bg-white border border-gray-200 rounded-lg p-5 mb-5">
              <h2 className="text-[13px] font-semibold text-gray-700 mb-3">새 문서 업로드</h2>
              <div className="space-y-3">
                <div>
                  <label className="block text-[12px] text-gray-600 mb-1">제목 *</label>
                  <input type="text" value={uploadTitle} onChange={e => setUploadTitle(e.target.value)}
                    placeholder="문서 제목"
                    className="w-full border border-gray-300 rounded px-3 py-2 text-[13px] focus:outline-none" />
                </div>
                <div>
                  <label className="block text-[12px] text-gray-600 mb-1">내용 *</label>
                  <textarea value={uploadContent} onChange={e => setUploadContent(e.target.value)}
                    rows={6} placeholder="문서 내용 (Markdown 가능)"
                    className="w-full border border-gray-300 rounded px-3 py-2 text-[13px] focus:outline-none resize-y" />
                </div>
                <div className="flex justify-end">
                  <button onClick={handleUpload} disabled={busy || !uploadTitle || !uploadContent}
                    className="px-6 py-2 bg-kb-admin text-white text-[13px] rounded hover:opacity-90 disabled:opacity-50">
                    {busy ? '업로드 중...' : '업로드'}
                  </button>
                </div>
              </div>
            </div>
          )}

          {/* 문서 목록 */}
          {docs.length > 0 ? (
            <div className="bg-white border border-gray-200 rounded-lg overflow-hidden">
              <table className="w-full text-[13px]">
                <thead>
                  <tr className="bg-gray-50 border-b border-gray-200">
                    {['docId', '제목', '상태', '생성일', '처리'].map(h => (
                      <th key={h} className="px-4 py-3 text-left font-semibold text-gray-600 text-xs">{h}</th>
                    ))}
                  </tr>
                </thead>
                <tbody className="divide-y divide-gray-100">
                  {docs.map((d) => (
                    <>
                      <tr key={d.docId} className="hover:bg-gray-50">
                        <td className="px-4 py-3 text-gray-400 text-xs font-mono">{d.docId}</td>
                        <td className="px-4 py-3 font-medium text-gray-800">{d.title}</td>
                        <td className="px-4 py-3">
                          <span className={`text-[11px] px-2 py-0.5 rounded border ${
                            d.status === 'INDEXED'   ? 'bg-green-100 text-green-700 border-green-300' :
                            d.status === 'PENDING'   ? 'bg-yellow-100 text-yellow-700 border-yellow-300' :
                            d.status === 'FAILED'    ? 'bg-red-100 text-red-700 border-red-300' :
                            'bg-gray-100 text-gray-500 border-gray-300'}`}>
                            {d.status}
                          </span>
                        </td>
                        <td className="px-4 py-3 text-gray-400 text-xs">
                          {d.createdAt ? new Date(d.createdAt).toLocaleString('ko-KR') : '-'}
                        </td>
                        <td className="px-4 py-3">
                          <div className="flex gap-1.5">
                            <button onClick={() => handleReindex(d.docId)} disabled={busy}
                              className="text-[11px] px-2 py-0.5 border border-blue-300 text-blue-600 rounded hover:bg-blue-50 disabled:opacity-50">
                              재인덱싱
                            </button>
                            <button onClick={() => loadLogs(d.docId)}
                              className="text-[11px] px-2 py-0.5 border border-gray-300 text-gray-600 rounded hover:bg-gray-50">
                              {logsDocId === d.docId ? '로그 닫기' : '인제스션 로그'}
                            </button>
                          </div>
                        </td>
                      </tr>
                      {logsDocId === d.docId && (
                        <tr key={`${d.docId}-logs`}>
                          <td colSpan={5} className="px-6 py-3 bg-gray-50">
                            {logsLoading ? (
                              <p className="text-[12px] text-gray-400">로그 불러오는 중...</p>
                            ) : logs.length === 0 ? (
                              <p className="text-[12px] text-gray-400">인제스션 로그 없음</p>
                            ) : (
                              <div className="space-y-1">
                                {logs.map((l, i: number) => (
                                  <div key={i} className="text-[11px] text-gray-600 flex gap-3">
                                    <span className="text-gray-400 font-mono">
                                      {l.createdAt ? new Date(l.createdAt).toLocaleString('ko-KR') : '-'}
                                    </span>
                                    <span className={l.status === 'SUCCESS' ? 'text-green-700' : l.status === 'FAILED' ? 'text-red-600' : 'text-gray-600'}>
                                      {l.status}
                                    </span>
                                    {l.message && <span>{l.message}</span>}
                                  </div>
                                ))}
                              </div>
                            )}
                          </td>
                        </tr>
                      )}
                    </>
                  ))}
                </tbody>
              </table>
            </div>
          ) : (
            !loading && (
              <div className="bg-white border border-gray-200 rounded-lg p-10 text-center text-sm text-gray-400">
                목록 조회 버튼을 눌러 문서를 확인하세요.
              </div>
            )
          )}
        </div>
      </main>
    </div>
  )
}
