'use client'

import { useState, useCallback } from 'react'
import AdminSidebar from '@/components/admin/AdminSidebar'
import { businessCalendarApi } from '@/lib/loan-api'

const DAY_NAMES = ['일', '월', '화', '수', '목', '금', '토']

function getDayName(dateStr: string) {
  const d = new Date(dateStr)
  return DAY_NAMES[d.getDay()] ?? ''
}

export default function AdminCalendarPage() {
  const [year, setYear] = useState(new Date().getFullYear())
  const [rows, setRows] = useState<any[]>([])
  const [loading, setLoading] = useState(false)
  const [busy, setBusy] = useState(false)
  const [msg, setMsg] = useState('')
  const [err, setErr] = useState('')

  // inline edit state
  const [editId, setEditId] = useState<number | null>(null)
  const [editBiz, setEditBiz] = useState(true)
  const [editHolidayName, setEditHolidayName] = useState('')

  // new form
  const [newDate, setNewDate] = useState('')
  const [newBiz, setNewBiz] = useState(true)
  const [newHolidayName, setNewHolidayName] = useState('')

  function notify(m: string) { setMsg(m); setTimeout(() => setMsg(''), 3000) }
  function fail(m: string) { setErr(m); setTimeout(() => setErr(''), 3000) }

  const load = useCallback(async () => {
    setLoading(true)
    try {
      const from = `${year}0101`
      const to   = `${year}1231`
      const { data: res } = await businessCalendarApi.list({ from, to })
      setRows(res.data?.items ?? [])
    } catch { fail('영업일 목록을 불러오지 못했습니다.') }
    finally { setLoading(false) }
  }, [year])

  function startEdit(row: any) {
    setEditId(row.calId)
    setEditBiz(row.businessDayYn)
    setEditHolidayName(row.holidayName ?? '')
  }

  async function saveEdit(calId: number) {
    setBusy(true)
    try {
      await businessCalendarApi.update(calId, {
        businessDayYn: editBiz,
        holidayName: editHolidayName,
      })
      notify('수정되었습니다.')
      setEditId(null)
      await load()
    } catch (e: any) { fail(e?.response?.data?.message ?? '수정 실패') }
    finally { setBusy(false) }
  }

  async function handleDelete(calId: number) {
    if (!confirm('삭제하시겠습니까?')) return
    setBusy(true)
    try {
      await businessCalendarApi.delete(calId)
      notify('삭제되었습니다.')
      setRows(prev => prev.filter(r => r.calId !== calId))
    } catch (e: any) { fail(e?.response?.data?.message ?? '삭제 실패') }
    finally { setBusy(false) }
  }

  async function handleCreate() {
    if (!newDate) return fail('날짜를 입력하세요.')
    setBusy(true)
    try {
      await businessCalendarApi.create({
        calDate: newDate.replace(/-/g, ''),
        businessDayYn: newBiz,
        holidayName: newHolidayName,
      })
      notify('날짜가 등록되었습니다.')
      setNewDate('')
      setNewBiz(true)
      setNewHolidayName('')
      await load()
    } catch (e: any) { fail(e?.response?.data?.message ?? '등록 실패') }
    finally { setBusy(false) }
  }

  return (
    <div className="flex min-h-screen bg-gray-50">
      <AdminSidebar />
      <main className="flex-1 overflow-auto">
        <div className="bg-white border-b border-gray-200 px-6 py-3 text-xs text-gray-500">
          대출 &gt; <span className="text-gray-800 font-medium">영업일 캘린더 관리</span>
        </div>
        <div className="px-6 py-5 max-w-5xl">
          <h1 className="text-lg font-bold text-gray-800 mb-5">영업일 캘린더 관리</h1>

          {msg && <div className="mb-4 px-4 py-2 bg-green-50 border border-green-300 text-green-700 text-sm rounded">{msg}</div>}
          {err && <div className="mb-4 px-4 py-2 bg-red-50 border border-red-300 text-red-700 text-sm rounded">{err}</div>}

          {/* 연도 조회 */}
          <div className="bg-white border border-gray-200 rounded-lg p-4 mb-5 flex gap-3 items-center">
            <label className="text-[12px] text-gray-600">연도</label>
            <input
              type="number"
              value={year}
              onChange={e => setYear(Number(e.target.value))}
              className="border border-gray-300 rounded px-3 py-1.5 text-[13px] w-28 focus:outline-none"
            />
            <button
              onClick={load}
              disabled={loading}
              className="px-5 py-1.5 bg-kb-admin text-white text-[13px] rounded hover:opacity-90 disabled:opacity-50"
            >
              {loading ? '조회 중...' : '조회'}
            </button>
          </div>

          {/* 테이블 */}
          {rows.length > 0 && (
            <div className="bg-white border border-gray-200 rounded-lg overflow-hidden mb-5">
              <table className="w-full text-[13px]">
                <thead>
                  <tr className="bg-gray-50 border-b border-gray-200">
                    {['calId', '날짜', '요일', '영업일여부', '공휴일명', '처리'].map(h => (
                      <th key={h} className="px-4 py-3 text-left text-xs text-gray-600 font-semibold">{h}</th>
                    ))}
                  </tr>
                </thead>
                <tbody className="divide-y divide-gray-100">
                  {rows.map((r: any) => (
                    <tr key={r.calId} className="hover:bg-gray-50 transition-colors">
                      <td className="px-4 py-3 text-gray-400 text-xs">{r.calId}</td>
                      <td className="px-4 py-3 text-gray-800 font-mono">{r.calDate}</td>
                      <td className="px-4 py-3 text-gray-600">{getDayName(r.calDate)}</td>
                      <td className="px-4 py-3">
                        {editId === r.calId ? (
                          <select
                            value={editBiz ? 'Y' : 'N'}
                            onChange={e => setEditBiz(e.target.value === 'Y')}
                            className="border border-gray-300 rounded px-2 py-1 text-[12px] focus:outline-none"
                          >
                            <option value="Y">영업일</option>
                            <option value="N">휴일</option>
                          </select>
                        ) : (
                          <span className={`text-[11px] px-2 py-0.5 rounded border ${r.businessDayYn ? 'bg-green-100 text-green-700 border-green-300' : 'bg-red-100 text-red-700 border-red-300'}`}>
                            {r.businessDayYn ? '영업일' : '휴일'}
                          </span>
                        )}
                      </td>
                      <td className="px-4 py-3 text-gray-600">
                        {editId === r.calId ? (
                          <input
                            type="text"
                            value={editHolidayName}
                            onChange={e => setEditHolidayName(e.target.value)}
                            className="border border-gray-300 rounded px-2 py-1 text-[12px] w-full focus:outline-none"
                          />
                        ) : (r.holidayName || '-')}
                      </td>
                      <td className="px-4 py-3">
                        <div className="flex gap-2">
                          {editId === r.calId ? (
                            <>
                              <button onClick={() => saveEdit(r.calId)} disabled={busy}
                                className="px-3 py-1 text-[11px] bg-kb-admin text-white rounded hover:opacity-90 disabled:opacity-50">
                                저장
                              </button>
                              <button onClick={() => setEditId(null)}
                                className="px-3 py-1 text-[11px] border border-gray-300 rounded hover:bg-gray-50">
                                취소
                              </button>
                            </>
                          ) : (
                            <>
                              <button onClick={() => startEdit(r)}
                                className="px-3 py-1 text-[11px] border border-gray-300 rounded hover:bg-gray-50">
                                수정
                              </button>
                              <button onClick={() => handleDelete(r.calId)} disabled={busy}
                                className="px-3 py-1 text-[11px] border border-red-300 text-red-600 rounded hover:bg-red-50 disabled:opacity-50">
                                삭제
                              </button>
                            </>
                          )}
                        </div>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}

          {/* 날짜 등록 폼 */}
          <div className="bg-white border border-gray-200 rounded-lg p-5">
            <h2 className="text-[13px] font-semibold text-gray-700 mb-4">날짜 등록</h2>
            <div className="flex flex-wrap gap-4 items-end">
              <label className="block">
                <span className="text-[12px] text-gray-600 mb-1 block">날짜</span>
                <input
                  type="date"
                  value={newDate}
                  onChange={e => setNewDate(e.target.value)}
                  className="border border-gray-300 rounded px-3 py-1.5 text-[13px] focus:outline-none"
                />
              </label>
              <label className="block">
                <span className="text-[12px] text-gray-600 mb-1 block">구분</span>
                <div className="flex gap-3 items-center py-1.5">
                  <label className="flex items-center gap-1 text-[13px] cursor-pointer">
                    <input type="radio" checked={newBiz} onChange={() => setNewBiz(true)} />
                    영업일
                  </label>
                  <label className="flex items-center gap-1 text-[13px] cursor-pointer">
                    <input type="radio" checked={!newBiz} onChange={() => setNewBiz(false)} />
                    휴일
                  </label>
                </div>
              </label>
              <label className="block flex-1">
                <span className="text-[12px] text-gray-600 mb-1 block">공휴일명</span>
                <input
                  type="text"
                  value={newHolidayName}
                  onChange={e => setNewHolidayName(e.target.value)}
                  placeholder="예: 추석 연휴"
                  className="w-full border border-gray-300 rounded px-3 py-1.5 text-[13px] focus:outline-none"
                />
              </label>
              <button
                onClick={handleCreate}
                disabled={busy || !newDate}
                className="px-5 py-1.5 bg-kb-admin text-white text-[13px] rounded hover:opacity-90 disabled:opacity-50"
              >
                {busy ? '등록 중...' : '등록'}
              </button>
            </div>
          </div>
        </div>
      </main>
    </div>
  )
}
