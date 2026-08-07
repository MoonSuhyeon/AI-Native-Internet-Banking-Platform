'use client'

import { useState } from 'react'
import AdminSidebar from '@/components/admin/AdminSidebar'
import { collateralApi, adminLoanApi } from '@/lib/loan-api'

const COL_TYPES: Record<string, string> = {
  REAL_ESTATE: '부동산', VEHICLE: '차량', FINANCIAL_ASSET: '금융자산',
  EQUIPMENT: '설비', OTHER: '기타',
}

export default function AdminCollateralPage() {
  const [applId, setApplId]   = useState('')
  const [cols, setCols]       = useState<any[]>([])
  const [loading, setLoading] = useState(false)
  const [busy, setBusy]       = useState(false)
  const [msg, setMsg]         = useState('')
  const [err, setErr]         = useState('')

  // edit modal
  const [editing, setEditing] = useState<any>(null)
  const [editVal, setEditVal] = useState('')

  // release modal
  const [releasing, setReleasing] = useState<any>(null)
  const [releaseReason, setReleaseReason] = useState('')
  const [releaseDate, setReleaseDate] = useState('')

  function notify(m: string) { setMsg(m); setTimeout(() => setMsg(''), 3000) }
  function fail(m: string)   { setErr(m); setTimeout(() => setErr(''), 4000) }

  async function search() {
    if (!applId) return
    setLoading(true); setErr('')
    try {
      const { data: res } = await collateralApi.list(parseInt(applId))
      setCols(res.data?.items ?? [])
    } catch { fail('담보 목록을 불러오지 못했습니다.') }
    finally { setLoading(false) }
  }

  async function reload() {
    const { data: res } = await collateralApi.list(parseInt(applId))
    // 응답은 배열이 아니라 { items, totalCount } 다.
    setCols(res.data?.items ?? [])
  }

  async function saveEdit() {
    if (!editing || !editVal) return
    setBusy(true)
    try {
      await adminLoanApi.updateCollateral(editing.colId, { declaredValue: parseInt(editVal) })
      notify('담보 수정이 완료되었습니다.')
      setEditing(null)
      await reload()
    } catch (e: any) { fail(e?.response?.data?.message ?? '수정 실패') }
    finally { setBusy(false) }
  }

  async function doRelease() {
    if (!releasing) return
    setBusy(true)
    try {
      await adminLoanApi.releaseCollateral(releasing.colId, {
        releaseReasonCd: releaseReason || 'LOAN_REPAID',
        releaseDate: releaseDate.replace(/-/g, ''),
      })
      notify('담보가 해제되었습니다.')
      setReleasing(null)
      await reload()
    } catch (e: any) { fail(e?.response?.data?.message ?? '해제 실패') }
    finally { setBusy(false) }
  }

  return (
    <div className="flex min-h-screen bg-gray-50">
      <AdminSidebar />
      <main className="flex-1 overflow-auto">
        <div className="bg-white border-b border-gray-200 px-6 py-3 text-xs text-gray-500">
          대출 &gt; <span className="text-gray-800 font-medium">담보 관리</span>
        </div>
        <div className="px-6 py-5 max-w-4xl">
          <h1 className="text-lg font-bold text-gray-800 mb-5">담보 관리</h1>

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
                className="px-5 py-1.5 bg-[#1B3A6B] text-white text-[13px] rounded hover:opacity-90 disabled:opacity-50">
                {loading ? '조회 중...' : '조회'}
              </button>
            </div>
          </div>

          {cols.length > 0 && (
            <div className="bg-white border border-gray-200 rounded-lg overflow-hidden">
              <table className="w-full text-[13px]">
                <thead>
                  <tr className="bg-gray-50 border-b border-gray-200">
                    {['colId', '유형', '명칭', '신고가액', '감정가액', 'LTV', '상태', ''].map(h => (
                      <th key={h} className="px-4 py-3 text-left font-semibold text-gray-600">{h}</th>
                    ))}
                  </tr>
                </thead>
                <tbody className="divide-y divide-gray-100">
                  {cols.map((c: any) => (
                    <tr key={c.colId} className="hover:bg-gray-50">
                      <td className="px-4 py-3 text-gray-400 text-xs">{c.colId}</td>
                      <td className="px-4 py-3 text-gray-600">{COL_TYPES[c.colTypeCd] ?? c.colTypeCd}</td>
                      <td className="px-4 py-3 font-medium text-gray-800">{c.colName}</td>
                      <td className="px-4 py-3">{c.declaredValue != null ? `${(c.declaredValue / 10000).toLocaleString('ko-KR')}만원` : '-'}</td>
                      <td className="px-4 py-3">{c.appraisedValue != null ? `${(c.appraisedValue / 10000).toLocaleString('ko-KR')}만원` : '-'}</td>
                      <td className="px-4 py-3">{c.ltvRatio != null ? `${(c.ltvRatio * 100).toFixed(1)}%` : '-'}</td>
                      <td className="px-4 py-3">
                        <span className={`text-[11px] px-2 py-0.5 rounded border ${c.colStatusCd === 'ACTIVE' ? 'bg-green-100 text-green-700 border-green-300' : 'bg-gray-100 text-gray-500 border-gray-300'}`}>
                          {c.colStatusCd}
                        </span>
                      </td>
                      <td className="px-4 py-3">
                        <div className="flex gap-2">
                          <button onClick={() => { setEditing(c); setEditVal(String(c.declaredValue ?? '')) }}
                            className="px-2 py-1 text-[11px] border border-gray-300 rounded hover:bg-gray-50">
                            수정
                          </button>
                          {c.colStatusCd === 'ACTIVE' && (
                            <button onClick={() => { setReleasing(c); setReleaseReason(''); setReleaseDate(new Date().toISOString().slice(0, 10)) }}
                              className="px-2 py-1 text-[11px] border border-red-300 text-red-600 rounded hover:bg-red-50">
                              해제
                            </button>
                          )}
                        </div>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </div>

        {/* 수정 모달 */}
        {editing && (
          <Modal title={`담보 수정 — ${editing.colName}`} onClose={() => setEditing(null)}>
            <label className="block text-[13px] text-gray-600 mb-1">신고가액(원)</label>
            <input type="number" value={editVal} onChange={e => setEditVal(e.target.value)}
              className="w-full border border-gray-300 rounded px-3 py-2 text-[13px] mb-4 focus:outline-none" />
            <div className="flex justify-end gap-2">
              <button onClick={() => setEditing(null)} className="px-4 py-2 text-[13px] border border-gray-300 rounded hover:bg-gray-50">취소</button>
              <button onClick={saveEdit} disabled={busy || !editVal}
                className="px-4 py-2 text-[13px] bg-[#1B3A6B] text-white rounded hover:opacity-90 disabled:opacity-50">저장</button>
            </div>
          </Modal>
        )}

        {/* 해제 모달 */}
        {releasing && (
          <Modal title={`담보 해제 — ${releasing.colName}`} onClose={() => setReleasing(null)}>
            <label className="block text-[13px] text-gray-600 mb-1">해제 사유</label>
            <select value={releaseReason} onChange={e => setReleaseReason(e.target.value)}
              className="w-full border border-gray-300 rounded px-3 py-2 text-[13px] mb-3 focus:outline-none">
              <option value="LOAN_REPAID">대출 상환</option>
              <option value="COLLATERAL_SWAP">담보 교체</option>
              <option value="ADMIN_OVERRIDE">관리자 처리</option>
              <option value="OTHER">기타</option>
            </select>
            <label className="block text-[13px] text-gray-600 mb-1">해제 일자</label>
            <input type="date" value={releaseDate} onChange={e => setReleaseDate(e.target.value)}
              className="w-full border border-gray-300 rounded px-3 py-2 text-[13px] mb-4 focus:outline-none" />
            <div className="flex justify-end gap-2">
              <button onClick={() => setReleasing(null)} className="px-4 py-2 text-[13px] border border-gray-300 rounded hover:bg-gray-50">취소</button>
              <button onClick={doRelease} disabled={busy}
                className="px-4 py-2 text-[13px] bg-red-600 text-white rounded hover:opacity-90 disabled:opacity-50">해제</button>
            </div>
          </Modal>
        )}
      </main>
    </div>
  )
}

function Modal({ title, children, onClose }: { title: string; children: React.ReactNode; onClose: () => void }) {
  return (
    <div className="fixed inset-0 bg-black/40 flex items-center justify-center z-50" onClick={onClose}>
      <div className="bg-white rounded-xl shadow-xl w-full max-w-md p-6" onClick={e => e.stopPropagation()}>
        <h3 className="text-[14px] font-bold text-gray-800 mb-4">{title}</h3>
        {children}
      </div>
    </div>
  )
}
