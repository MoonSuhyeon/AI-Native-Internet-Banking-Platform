'use client'

import { useState, useCallback } from 'react'
import AdminSidebar from '@/components/admin/AdminSidebar'
import { executeStaffFeature } from '@/lib/consultation-api'
import StaffFeatureTable, { StaffColumn } from '@/components/admin/StaffFeatureTable'

const won = (v: unknown) => (v == null || v === '' ? '-' : `${Number(v).toLocaleString('ko-KR')}원`)
const pct = (v: unknown) => (v == null || v === '' ? '-' : `${v}%`)
const dt  = (v: unknown) => (v ? String(v).slice(0, 16).replace('T', ' ') : '-')

const ACCOUNT_COLUMNS: StaffColumn[] = [
  { key: 'account_number', label: '계좌번호' },
  { key: 'account_type',   label: '종류' },
  { key: 'account_alias',  label: '별칭' },
  { key: 'balance',        label: '잔액', align: 'right', format: won },
  { key: 'currency',       label: '통화' },
  { key: 'account_status', label: '상태' },
  { key: 'opened_at',      label: '개설일', format: dt },
]

type Tab = { id: string; code: string; label: string; columns: StaffColumn[]; emptyMsg: string }

const TABS: Tab[] = [
  { id: 'customer', code: 'STAFF_CUSTOMER', label: '고객 정보', emptyMsg: '조회된 고객 정보가 없습니다.', columns: ACCOUNT_COLUMNS },
  {
    id: 'contract', code: 'STAFF_CONTRACT', label: '고객 계약', emptyMsg: '조회된 계약이 없습니다.',
    columns: [
      { key: 'contract_no',            label: '계약번호' },
      { key: 'product_name',           label: '상품명' },
      { key: 'join_amount',            label: '가입금액', align: 'right', format: won },
      { key: 'contract_interest_rate', label: '약정금리', align: 'right', format: pct },
      { key: 'started_at',             label: '시작일', format: dt },
      { key: 'maturity_at',            label: '만기일', format: dt },
      { key: 'contract_status',        label: '상태' },
    ],
  },
  { id: 'account', code: 'STAFF_ACCOUNT', label: '고객 계좌', emptyMsg: '조회된 계좌가 없습니다.', columns: ACCOUNT_COLUMNS },
  {
    id: 'transfer', code: 'STAFF_TRANSFER_FLOW', label: '이체 흐름', emptyMsg: '조회된 이체 내역이 없습니다.',
    columns: [
      { key: 'transaction_number', label: '거래번호' },
      { key: 'account_number',     label: '계좌번호' },
      { key: 'transaction_type',   label: '거래유형' },
      { key: 'transaction_status', label: '상태' },
      { key: 'amount',             label: '금액', align: 'right', format: won },
      { key: 'transaction_at',     label: '거래일시', format: dt },
    ],
  },
  {
    id: 'consultation', code: 'STAFF_CONSULTATION_HISTORY', label: '상담 이력', emptyMsg: '조회된 상담 이력이 없습니다.',
    columns: [
      { key: 'consultation_id', label: '상담ID' },
      { key: 'content_summary', label: '상담요약' },
      { key: 'status_code_id',  label: '상태' },
      { key: 'answer_summary',  label: '답변요약' },
      { key: 'consulted_at',    label: '상담일시', format: dt },
      { key: 'completed_at',    label: '완료일시', format: dt },
    ],
  },
]

type TabState = { rows: Record<string, unknown>[]; notice: string | null }

export default function ConsultationCustomerPage() {
  const [customerNo, setCustomerNo] = useState('CUST001')
  const [activeTab, setActiveTab] = useState(TABS[0].id)
  const [searched, setSearched] = useState(false)
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState('')
  const [cache, setCache] = useState<Record<string, TabState>>({})

  // 직원 ID 를 인자로 받지 않는다. 신원은 게이트웨이가 JWT 에서 주입하므로
  // 화면이 보낼 것이 없다 — 예전에는 입력칸의 값을 그대로 실어 보냈다.
  const fetchTab = useCallback(async (tabId: string, cNo: string) => {
    const tab = TABS.find(t => t.id === tabId)!
    setLoading(true)
    setError('')
    try {
      const res = await executeStaffFeature(tab.code, { customer_no: cNo.trim() })
      const notice = res.status === 'OK' ? null : res.message
      setCache(prev => ({ ...prev, [tabId]: { rows: res.data ?? [], notice } }))
    } catch {
      // 401/403 도 여기로 온다 — 직원 클레임이 없는 토큰으로는 열람할 수 없다.
      setError('조회에 실패했습니다. 직원 계정으로 로그인했는지, 상담 서비스 연결을 확인해주세요.')
      setCache(prev => ({ ...prev, [tabId]: { rows: [], notice: null } }))
    } finally {
      setLoading(false)
    }
  }, [])

  function onSearch() {
    if (!customerNo.trim()) {
      setError('고객번호를 입력해주세요.')
      return
    }
    setSearched(true)
    setCache({})
    fetchTab(activeTab, customerNo)
  }

  function onTabChange(tabId: string) {
    setActiveTab(tabId)
    setError('')
    if (searched && !cache[tabId]) fetchTab(tabId, customerNo)
  }

  const tab = TABS.find(t => t.id === activeTab)!
  const state = cache[activeTab]

  return (
    <div className="flex min-h-screen bg-gray-50">
      <AdminSidebar />
      <main className="flex-1 overflow-auto">
        <div className="bg-white border-b border-gray-200 px-6 py-3 text-xs text-gray-500">
          상담 &gt; <span className="text-gray-800 font-medium">고객 조회</span>
        </div>
        <div className="px-6 py-5">
          <h1 className="text-lg font-bold text-gray-800 mb-5">
            고객 조회 <span className="text-sm font-normal text-gray-400">— 상담 직원용</span>
          </h1>

          {/* 검색 */}
          <div className="bg-white border border-gray-200 rounded-lg p-4 mb-5">
            <div className="flex flex-wrap items-end gap-3">
              <label className="text-xs font-medium text-gray-500">
                고객번호
                <input value={customerNo} onChange={e => setCustomerNo(e.target.value)}
                  onKeyDown={e => { if (e.key === 'Enter') onSearch() }}
                  className="mt-1 block h-9 w-48 rounded border border-gray-300 px-3 text-[13px] text-gray-800 focus:outline-none focus:border-kb-admin" />
              </label>
              <button onClick={onSearch} disabled={loading}
                className="h-9 px-6 bg-kb-admin text-white text-[13px] rounded hover:opacity-90 disabled:opacity-50">
                {loading ? '조회 중...' : '조회'}
              </button>
            </div>
            {error && <p className="mt-3 text-[13px] text-red-500">{error}</p>}
            <p className="mt-2 text-[11px] text-gray-400">
              ※ 열람 기록은 로그인한 직원 계정으로 남습니다.
            </p>
          </div>

          {/* 탭 */}
          <div className="flex border-b border-gray-200 mb-5">
            {TABS.map(t => (
              <button key={t.id} onClick={() => onTabChange(t.id)}
                className={`px-5 py-2.5 text-[13px] font-medium border-b-2 transition-colors ${
                  activeTab === t.id
                    ? 'border-kb-admin text-kb-admin'
                    : 'border-transparent text-gray-500 hover:text-gray-700'}`}>
                {t.label}
              </button>
            ))}
          </div>

          {/* 본문 */}
          {!searched ? (
            <p className="py-10 text-center text-sm text-gray-400">고객번호를 입력하고 조회하세요.</p>
          ) : (
            <StaffFeatureTable
              columns={tab.columns}
              rows={state?.rows ?? []}
              loading={loading}
              notice={state?.notice ?? null}
              emptyMsg={tab.emptyMsg}
            />
          )}
        </div>
      </main>
    </div>
  )
}
