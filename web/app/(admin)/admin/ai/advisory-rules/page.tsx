'use client'

import { useState, useCallback } from 'react'
import AdminSidebar from '@/components/admin/AdminSidebar'
import {
  getAdvisoryRules,
  updateAdvisoryRule,
  AdvisoryRuleResponse,
  UpdateAdvisoryRuleBody,
} from '@/lib/advisory-api'

const SEV_CLS: Record<string, string> = {
  CRITICAL: 'bg-red-100 text-red-700 border-red-300',
  HIGH:     'bg-orange-100 text-orange-700 border-orange-300',
  MEDIUM:   'bg-yellow-100 text-yellow-700 border-yellow-200',
  LOW:      'bg-gray-100 text-gray-600 border-gray-300',
}

const CHANGE_REASON_OPTIONS = ['POLICY_UPDATE', 'THRESHOLD_TUNE', 'COMPLIANCE', 'BUG_FIX', 'OTHER']

export default function AdminAdvisoryRulesPage() {
  const [rules, setRules]     = useState<AdvisoryRuleResponse[]>([])
  const [loading, setLoading] = useState(false)
  const [busy, setBusy]       = useState(false)
  const [msg, setMsg]         = useState('')
  const [err, setErr]         = useState('')

  const [editId, setEditId]   = useState<number | null>(null)
  const [editForm, setEditForm] = useState<UpdateAdvisoryRuleBody>({})

  function notify(m: string) { setMsg(m); setTimeout(() => setMsg(''), 3000) }
  function fail(m: string)   { setErr(m); setTimeout(() => setErr(''), 4000) }

  const load = useCallback(async () => {
    setLoading(true)
    try {
      const data = await getAdvisoryRules()
      setRules(data)
    } catch { fail('규칙 목록을 불러오지 못했습니다.') }
    finally { setLoading(false) }
  }, [])

  function startEdit(r: AdvisoryRuleResponse) {
    setEditId(r.ruleId)
    setEditForm({
      activeYn:           r.activeYn,
      ruleParams:         r.ruleParams ?? '',
      ruleVersion:        r.ruleVersion ?? '',
      effectiveStartDate: r.effectiveStartDate ?? '',
      effectiveEndDate:   r.effectiveEndDate ?? '',
      ruleDesc:           r.ruleDesc ?? '',
      changeReasonCd:     'POLICY_UPDATE',
      changeRemark:       '',
    })
  }

  async function saveEdit(ruleId: number) {
    setBusy(true)
    try {
      await updateAdvisoryRule(ruleId, editForm)
      notify(`규칙 #${ruleId} 저장 완료`)
      setEditId(null)
      await load()
    } catch (e) { fail((e as {response?: {data?: {message?: string}}})?.response?.data?.message ?? '저장 실패') }
    finally { setBusy(false) }
  }

  function setF(key: keyof UpdateAdvisoryRuleBody, val: string | boolean) {
    setEditForm(prev => ({ ...prev, [key]: val }))
  }

  return (
    <div className="flex min-h-screen bg-gray-50">
      <AdminSidebar />
      <main className="flex-1 overflow-auto">
        <div className="bg-white border-b border-gray-200 px-6 py-3 text-xs text-gray-500">
          AI 심사지원 &gt; <span className="text-gray-800 font-medium">자문 규칙 관리</span>
        </div>
        <div className="px-6 py-5 max-w-6xl">
          <div className="flex items-center justify-between mb-5">
            <h1 className="text-lg font-bold text-gray-800">자문 규칙 (Advisory Rules)</h1>
            <button onClick={load} disabled={loading}
              className="px-5 py-1.5 text-[13px] bg-kb-admin text-white rounded hover:opacity-90 disabled:opacity-50">
              {loading ? '조회 중...' : '목록 조회'}
            </button>
          </div>

          {msg && <div className="mb-4 px-4 py-2 bg-green-50 border border-green-300 text-green-700 text-sm rounded">{msg}</div>}
          {err && <div className="mb-4 px-4 py-2 bg-red-50 border border-red-300 text-red-700 text-sm rounded">{err}</div>}

          {rules.length > 0 ? (
            <div className="space-y-2">
              {rules.map((r) => (
                <div key={r.ruleId} className="bg-white border border-gray-200 rounded-lg overflow-hidden">
                  <div className="flex items-center gap-3 px-4 py-3">
                    <span className="text-gray-400 text-xs font-mono w-8">#{r.ruleId}</span>
                    <span className={`text-[11px] px-2 py-0.5 rounded border font-semibold ${SEV_CLS[r.severityCd] ?? SEV_CLS.LOW}`}>
                      {r.severityCd}
                    </span>
                    <span className="text-[11px] px-1.5 py-0.5 bg-gray-100 text-gray-500 rounded border border-gray-200">
                      {r.advisoryTypeCd}
                    </span>
                    <span className="font-semibold text-gray-800 flex-1">{r.ruleName}</span>
                    <span className="text-[11px] text-gray-400 font-mono">{r.ruleCd}</span>
                    <span className={`text-[11px] px-2 py-0.5 rounded border ${
                      r.activeYn ? 'bg-green-100 text-green-700 border-green-300' : 'bg-gray-100 text-gray-500 border-gray-300'
                    }`}>
                      {r.activeYn ? '활성' : '비활성'}
                    </span>
                    {editId !== r.ruleId ? (
                      <button onClick={() => startEdit(r)}
                        className="text-[11px] px-3 py-0.5 border border-gray-300 rounded hover:bg-gray-50">
                        편집
                      </button>
                    ) : (
                      <button onClick={() => setEditId(null)}
                        className="text-[11px] px-3 py-0.5 border border-gray-300 rounded hover:bg-gray-50 text-gray-500">
                        취소
                      </button>
                    )}
                  </div>

                  {r.ruleDesc && editId !== r.ruleId && (
                    <div className="px-4 pb-3 text-[12px] text-gray-500">{r.ruleDesc}</div>
                  )}

                  {editId === r.ruleId && (
                    <div className="px-4 pb-4 border-t border-gray-100 pt-3">
                      <div className="grid grid-cols-2 gap-3 mb-3">
                        <label className="block">
                          <span className="text-[11px] text-gray-500 mb-1 block">활성 여부</span>
                          <select value={String(editForm.activeYn)} onChange={e => setF('activeYn', e.target.value === 'true')}
                            className="w-full border border-gray-300 rounded px-2 py-1.5 text-[12px]">
                            <option value="true">활성</option>
                            <option value="false">비활성</option>
                          </select>
                        </label>
                        <label className="block">
                          <span className="text-[11px] text-gray-500 mb-1 block">룰 버전</span>
                          <input type="text" value={editForm.ruleVersion ?? ''}
                            onChange={e => setF('ruleVersion', e.target.value)}
                            className="w-full border border-gray-300 rounded px-2 py-1.5 text-[12px]" />
                        </label>
                        <label className="block">
                          <span className="text-[11px] text-gray-500 mb-1 block">유효 시작일 (YYYYMMDD)</span>
                          <input type="text" value={editForm.effectiveStartDate ?? ''}
                            onChange={e => setF('effectiveStartDate', e.target.value)}
                            placeholder="예: 20260101"
                            className="w-full border border-gray-300 rounded px-2 py-1.5 text-[12px]" />
                        </label>
                        <label className="block">
                          <span className="text-[11px] text-gray-500 mb-1 block">유효 종료일 (YYYYMMDD)</span>
                          <input type="text" value={editForm.effectiveEndDate ?? ''}
                            onChange={e => setF('effectiveEndDate', e.target.value)}
                            placeholder="예: 20261231"
                            className="w-full border border-gray-300 rounded px-2 py-1.5 text-[12px]" />
                        </label>
                        <label className="block col-span-2">
                          <span className="text-[11px] text-gray-500 mb-1 block">룰 파라미터 (JSON)</span>
                          <textarea value={editForm.ruleParams ?? ''} rows={2}
                            onChange={e => setF('ruleParams', e.target.value)}
                            className="w-full border border-gray-300 rounded px-2 py-1.5 text-[12px] font-mono resize-y" />
                        </label>
                        <label className="block col-span-2">
                          <span className="text-[11px] text-gray-500 mb-1 block">룰 설명</span>
                          <textarea value={editForm.ruleDesc ?? ''} rows={2}
                            onChange={e => setF('ruleDesc', e.target.value)}
                            className="w-full border border-gray-300 rounded px-2 py-1.5 text-[12px] resize-y" />
                        </label>
                        <label className="block">
                          <span className="text-[11px] text-gray-500 mb-1 block">변경 사유 *</span>
                          <select value={editForm.changeReasonCd ?? ''} onChange={e => setF('changeReasonCd', e.target.value)}
                            className="w-full border border-gray-300 rounded px-2 py-1.5 text-[12px]">
                            {CHANGE_REASON_OPTIONS.map(c => <option key={c} value={c}>{c}</option>)}
                          </select>
                        </label>
                        <label className="block">
                          <span className="text-[11px] text-gray-500 mb-1 block">변경 비고</span>
                          <input type="text" value={editForm.changeRemark ?? ''}
                            onChange={e => setF('changeRemark', e.target.value)}
                            placeholder="(선택)"
                            className="w-full border border-gray-300 rounded px-2 py-1.5 text-[12px]" />
                        </label>
                      </div>
                      <div className="flex justify-end">
                        <button onClick={() => saveEdit(r.ruleId)} disabled={busy || !editForm.changeReasonCd}
                          className="px-5 py-1.5 bg-kb-admin text-white text-[12px] rounded hover:opacity-90 disabled:opacity-50">
                          {busy ? '저장 중...' : '저장'}
                        </button>
                      </div>
                    </div>
                  )}
                </div>
              ))}
            </div>
          ) : (
            !loading && (
              <div className="bg-white border border-gray-200 rounded-lg p-10 text-center text-sm text-gray-400">
                목록 조회 버튼을 눌러 규칙을 확인하세요.
              </div>
            )
          )}
        </div>
      </main>
    </div>
  )
}
