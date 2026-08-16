'use client'

import { useState } from 'react'
import AdminSidebar from '@/components/admin/AdminSidebar'
import { loanApplicationApi, adminLoanApi } from '@/lib/loan-api'

const DOC_TYPES: Record<string, string> = {
  INCOME_PROOF: '소득증빙', EMPLOYMENT_CERT: '재직증명', ID_COPY: '신분증사본',
  ASSET_PROOF: '자산증빙', CREDIT_REPORT: '신용보고서', OTHER: '기타',
}

export default function AdminDocumentsPage() {
  const [applId, setApplId]   = useState('')
  const [docs, setDocs]       = useState<any[]>([])
  const [loading, setLoading] = useState(false)
  const [busy, setBusy]       = useState(false)
  const [msg, setMsg]         = useState('')
  const [err, setErr]         = useState('')
  const [confirm, setConfirm] = useState<any>(null)

  function notify(m: string) { setMsg(m); setTimeout(() => setMsg(''), 3000) }
  function fail(m: string)   { setErr(m); setTimeout(() => setErr(''), 4000) }

  async function search() {
    if (!applId) return
    setLoading(true); setErr('')
    try {
      const { data: res } = await loanApplicationApi.getDocuments(parseInt(applId))
      setDocs(res.data?.items ?? [])
    } catch { fail('서류 목록을 불러오지 못했습니다.') }
    finally { setLoading(false) }
  }

  async function deleteDoc(docId: number) {
    setBusy(true)
    try {
      await adminLoanApi.deleteDocument(docId)
      notify('서류가 삭제되었습니다.')
      setConfirm(null)
      setDocs(prev => prev.filter(d => d.docId !== docId))
    } catch (e: any) { fail(e?.response?.data?.message ?? '삭제 실패') }
    finally { setBusy(false) }
  }

  return (
    <div className="flex min-h-screen bg-gray-50">
      <AdminSidebar />
      <main className="flex-1 overflow-auto">
        <div className="bg-white border-b border-gray-200 px-6 py-3 text-xs text-gray-500">
          대출 &gt; <span className="text-gray-800 font-medium">서류 관리</span>
        </div>
        <div className="px-6 py-5 max-w-4xl">
          <h1 className="text-lg font-bold text-gray-800 mb-5">서류 관리</h1>

          {msg && <div className="mb-4 px-4 py-2 bg-green-50 border border-green-300 text-green-700 text-sm rounded">{msg}</div>}
          {err && <div className="mb-4 px-4 py-2 bg-red-50 border border-red-300 text-red-700 text-sm rounded">{err}</div>}

          <div className="bg-white border border-gray-200 rounded-lg p-5 mb-5">
            <div className="flex gap-3 items-end">
              <label className="text-[12px] text-gray-600">
                신청 ID
                <input type="number" value={applId} onChange={e => setApplId(e.target.value)}
                  placeholder="applId 입력"
                  className="ml-2 border border-gray-300 rounded px-3 py-1.5 text-[13px] w-36 focus:outline-none" />
              </label>
              <button onClick={search} disabled={loading || !applId}
                className="px-5 py-1.5 bg-kb-admin text-white text-[13px] rounded hover:opacity-90 disabled:opacity-50">
                {loading ? '조회 중...' : '조회'}
              </button>
            </div>
          </div>

          {docs.length > 0 ? (
            <div className="bg-white border border-gray-200 rounded-lg overflow-hidden">
              <table className="w-full text-[13px]">
                <thead>
                  <tr className="bg-gray-50 border-b border-gray-200">
                    {['docId', '문서 유형', '파일명', '상태', '검증결과', '검증일시', '업로드일시', ''].map(h => (
                      <th key={h} className="px-4 py-3 text-left font-semibold text-gray-600">{h}</th>
                    ))}
                  </tr>
                </thead>
                <tbody className="divide-y divide-gray-100">
                  {docs.map((d: any) => (
                    <tr key={d.docId} className={`${
                      d.verifyResultCd === 'HOLD' || d.verifyResultCd === 'FRAUD'
                        ? 'bg-red-50 hover:bg-red-100'
                        : 'hover:bg-gray-50'
                    }`}>
                      <td className="px-4 py-3 text-gray-400 text-xs">{d.docId}</td>
                      <td className="px-4 py-3 text-gray-600">{DOC_TYPES[d.docTypeCd] ?? d.docTypeCd}</td>
                      <td className="px-4 py-3 text-gray-800 font-mono text-xs">{d.fileName ?? d.fileUrl ?? '-'}</td>
                      <td className="px-4 py-3">
                        <span className={`text-[11px] px-2 py-0.5 rounded border ${
                          d.docStatusCd === 'VERIFIED' ? 'bg-green-100 text-green-700 border-green-300' :
                          d.docStatusCd === 'REJECTED' ? 'bg-red-100 text-red-700 border-red-300' :
                          'bg-yellow-100 text-yellow-700 border-yellow-300'}`}>
                          {d.docStatusCd}
                        </span>
                      </td>
                      <td className="px-4 py-3">
                        {d.verifyResultCd ? (
                          <span className={`text-[11px] px-2 py-0.5 rounded border ${
                            d.verifyResultCd === 'AUTO_PASS'      ? 'bg-green-100 text-green-700 border-green-300' :
                            d.verifyResultCd === 'NEEDS_RESUBMIT' ? 'bg-orange-100 text-orange-700 border-orange-300' :
                            d.verifyResultCd === 'HOLD'           ? 'bg-red-100 text-red-700 border-red-300' :
                            d.verifyResultCd === 'FRAUD'          ? 'bg-red-200 text-red-900 border-red-400 font-bold' :
                            'bg-gray-100 text-gray-600 border-gray-300'}`}>
                            {d.verifyResultCd}
                          </span>
                        ) : <span className="text-gray-300 text-xs">-</span>}
                      </td>
                      <td className="px-4 py-3 text-gray-400 text-xs">
                        {d.verifiedAt?.slice(0, 16).replace('T', ' ') ?? '-'}
                      </td>
                      <td className="px-4 py-3 text-gray-400 text-xs">
                        {d.uploadedAt?.slice(0, 16).replace('T', ' ') ?? d.createdAt?.slice(0, 16).replace('T', ' ') ?? '-'}
                      </td>
                      <td className="px-4 py-3">
                        <div className="flex gap-2 items-center">
                          {(d.verifyResultCd === 'HOLD' || d.verifyResultCd === 'FRAUD') && (
                            <span className="text-[10px] text-red-700 font-semibold">사기 의심</span>
                          )}
                          <a href={adminLoanApi.downloadDocumentUrl(d.docId)} target="_blank" rel="noreferrer"
                            className="px-2 py-1 text-[11px] border border-gray-300 rounded hover:bg-gray-50 text-gray-600">
                            다운로드
                          </a>
                          <button onClick={() => setConfirm(d)}
                            className="px-2 py-1 text-[11px] border border-red-300 text-red-600 rounded hover:bg-red-50">
                            삭제
                          </button>
                        </div>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          ) : applId && !loading ? (
            <p className="py-10 text-center text-sm text-gray-400">서류가 없습니다.</p>
          ) : null}
        </div>

        {/* 삭제 확인 모달 */}
        {confirm && (
          <div className="fixed inset-0 bg-black/40 flex items-center justify-center z-50" onClick={() => setConfirm(null)}>
            <div className="bg-white rounded-xl shadow-xl w-full max-w-sm p-6" onClick={e => e.stopPropagation()}>
              <h3 className="text-[14px] font-bold text-gray-800 mb-2">서류 삭제 확인</h3>
              <p className="text-[13px] text-gray-600 mb-5">
                <strong>{DOC_TYPES[confirm.docTypeCd] ?? confirm.docTypeCd}</strong> 서류를 삭제합니다. 복구할 수 없습니다.
              </p>
              <div className="flex justify-end gap-2">
                <button onClick={() => setConfirm(null)} className="px-4 py-2 text-[13px] border border-gray-300 rounded hover:bg-gray-50">취소</button>
                <button onClick={() => deleteDoc(confirm.docId)} disabled={busy}
                  className="px-4 py-2 text-[13px] bg-red-600 text-white rounded hover:opacity-90 disabled:opacity-50">삭제</button>
              </div>
            </div>
          </div>
        )}
      </main>
    </div>
  )
}
