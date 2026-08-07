'use client'

import { useState, useEffect, useCallback } from 'react'
import { useParams } from 'next/navigation'
import Link from 'next/link'
import { collateralApi } from '@/lib/loan-api'

const COL_TYPES = [
  { code: 'REAL_ESTATE', label: '부동산' },
  { code: 'APARTMENT',   label: '아파트' },
  { code: 'LAND',        label: '토지' },
  { code: 'VEHICLE',     label: '차량' },
  { code: 'DEPOSIT',     label: '예금/적금' },
]

const OWNERSHIP_TYPES = [
  { code: 'SOLE',  label: '단독 소유' },
  { code: 'JOINT', label: '공동 소유' },
]

const COL_STATUS_LABEL: Record<string, string> = {
  REGISTERED: '등록',
  EVALUATED:  '평가완료',
  APPROVED:   '승인',
  RELEASED:   '해제',
  REJECTED:   '거절',
}

const LTV_STATUS_LABEL: Record<string, { text: string; cls: string }> = {
  PASS: { text: '통과', cls: 'text-green-700' },
  FAIL: { text: '미통과', cls: 'text-red-600' },
}

function formatWon(n: number) {
  if (n >= 100_000_000) return `${(n / 100_000_000).toFixed(1)}억원`
  if (n >= 10_000)      return `${(n / 10_000).toLocaleString('ko-KR')}만원`
  return `${n.toLocaleString('ko-KR')}원`
}

function bps(v: number) { return (v / 100).toFixed(2) }

type ColItem = {
  colId: number
  colTypeCd: string
  colStatusCd: string
  colNo: string
  colName: string
  colAddress: string
  declaredValue: number
  seniorLienYn: boolean
  seniorLienAmount: number
}

type LtvResult = {
  ltvId: number
  appliedColValue: number
  seniorLienAmount: number
  requestedAmount: number
  ltvRatioBps: number
  ltvLimitBps: number
  maxLoanAmount: number
  ltvStatusCd: string
}

export default function CollateralPage() {
  const { applId } = useParams<{ applId: string }>()

  const [collaterals, setCollaterals] = useState<ColItem[]>([])
  const [ltvMap, setLtvMap] = useState<Record<number, LtvResult>>({})

  const [colType, setColType]       = useState('REAL_ESTATE')
  const [colName, setColName]       = useState('')
  const [colAddress, setColAddress] = useState('')
  const [declaredValue, setDeclaredValue] = useState('')
  const [ownershipType, setOwnershipType] = useState('SOLE')
  const [seniorLienYn, setSeniorLienYn]   = useState(false)
  const [seniorLienAmt, setSeniorLienAmt] = useState('')

  const [submitting, setSubmitting] = useState(false)
  const [error, setError]           = useState('')
  const [ltvLoading, setLtvLoading] = useState<number | null>(null)

  const loadCollaterals = useCallback(async () => {
    try {
      const { data: res } = await collateralApi.list(Number(applId))
      setCollaterals(res.data?.items ?? [])
    } catch {}
  }, [applId])

  useEffect(() => { loadCollaterals() }, [loadCollaterals])

  async function handleRegister() {
    if (!colType) return
    setSubmitting(true)
    setError('')
    try {
      const declared = declaredValue ? parseInt(declaredValue.replace(/,/g, '')) : undefined
      const { data: res } = await collateralApi.create(Number(applId), {
        colTypeCd:      colType,
        colName:        colName || undefined,
        colAddress:     colAddress || undefined,
        declaredValue:  declared,
        currencyCd:     'KRW',
        ownershipTypeCd: ownershipType,
        seniorLienYn,
        seniorLienAmount: seniorLienYn && seniorLienAmt
          ? parseInt(seniorLienAmt.replace(/,/g, ''))
          : 0,
      })
      const col = res.data as ColItem
      setCollaterals(prev => [...prev, col])

      // 등록 직후 감정평가(stub) → LTV 자동 계산
      await runEvalAndLtv(col.colId, declared ?? 0)

      // 폼 초기화
      setColName(''); setColAddress(''); setDeclaredValue('')
      setSeniorLienYn(false); setSeniorLienAmt('')
    } catch (err: any) {
      setError(err.response?.data?.message ?? '담보 등록 중 오류가 발생했습니다.')
    } finally {
      setSubmitting(false)
    }
  }

  async function runEvalAndLtv(colId: number, appraisedValue: number) {
    setLtvLoading(colId)
    try {
      await collateralApi.evaluate(colId, {
        evalMethodCd:  'APPRAISAL',
        evalAgencyCd:  'KAB',
        appraisedValue: appraisedValue || 0,
      })
      const { data: ltvRes } = await collateralApi.calculateLtv(colId)
      setLtvMap(prev => ({ ...prev, [colId]: ltvRes.data }))
      await loadCollaterals()
    } catch (err: any) {
      setError(err.response?.data?.message ?? 'LTV 계산 중 오류가 발생했습니다.')
    } finally {
      setLtvLoading(null)
    }
  }

  async function handleCalcLtv(colId: number) {
    setLtvLoading(colId)
    setError('')
    try {
      const { data: ltvRes } = await collateralApi.calculateLtv(colId)
      setLtvMap(prev => ({ ...prev, [colId]: ltvRes.data }))
    } catch (err: any) {
      setError(err.response?.data?.message ?? 'LTV 계산 중 오류가 발생했습니다.')
    } finally {
      setLtvLoading(null)
    }
  }

  const canSubmit = !!colType && !submitting

  return (
    <div className="max-w-kb-container mx-auto px-6 py-4 pb-16">
      <div className="flex justify-end mb-4 text-[12px] text-kb-text-muted gap-1">
        <span>개인뱅킹</span><span>&gt;</span>
        <span>대출</span><span>&gt;</span>
        <Link href="/loans/apply" className="hover:underline">대출 신청</Link>
        <span>&gt;</span>
        <span className="font-semibold text-kb-text">담보 등록</span>
      </div>

      <h1 className="text-[22px] font-bold text-kb-text mb-6 pb-2 border-b-2 border-kb-primary">담보 등록 및 LTV 계산</h1>

      <div className="border border-kb-primary-border bg-kb-primary-bg p-4 mb-6 text-[13px] text-kb-text-body leading-relaxed">
        <p>· 담보 등록 후 감정평가 및 LTV(담보인정비율) 계산이 자동으로 진행됩니다.</p>
        <p>· LTV = 신청금액 ÷ 감정가 × 100. 기본 한도 70%(7,000bps).</p>
      </div>

      {/* 등록된 담보 목록 */}
      {collaterals.length > 0 && (
        <section className="mb-8">
          <h2 className="text-lg font-bold text-kb-text mb-5 pb-2 border-b border-kb-primary-border">등록된 담보물</h2>
          <div className="space-y-4">
            {collaterals.map(col => {
              const ltv = ltvMap[col.colId]
              const loading = ltvLoading === col.colId
              return (
                <div key={col.colId} className="border border-kb-primary-border rounded-xl overflow-hidden">
                  <div className="bg-kb-primary-bg px-5 py-3 flex justify-between items-center border-b border-kb-primary-border">
                    <span className="text-[13px] font-bold text-kb-text">{col.colNo} · {COL_TYPES.find(t => t.code === col.colTypeCd)?.label ?? col.colTypeCd}</span>
                    <span className={`text-[11px] px-2 py-0.5 rounded border ${
                      col.colStatusCd === 'EVALUATED' ? 'border-green-400 text-green-700 bg-green-50'
                      : 'border-kb-primary-border text-kb-text-muted'}`}>
                      {COL_STATUS_LABEL[col.colStatusCd] ?? col.colStatusCd}
                    </span>
                  </div>
                  <div className="px-5 py-4 text-[13px] grid grid-cols-2 gap-x-8 gap-y-2 text-kb-text-body">
                    {col.colName    && <div><span className="font-medium text-kb-text">담보명</span>: {col.colName}</div>}
                    {col.colAddress && <div><span className="font-medium text-kb-text">주소</span>: {col.colAddress}</div>}
                    {col.declaredValue > 0 && (
                      <div><span className="font-medium text-kb-text">신고가액</span>: {formatWon(col.declaredValue)}</div>
                    )}
                    <div>
                      <span className="font-medium text-kb-text">선순위 근저당</span>: {col.seniorLienYn
                        ? `있음 (${formatWon(col.seniorLienAmount)})`
                        : '없음'}
                    </div>
                  </div>

                  {/* LTV 결과 */}
                  {loading ? (
                    <div className="px-5 py-3 border-t border-kb-primary-border text-[13px] text-kb-text-muted">LTV 계산 중...</div>
                  ) : ltv ? (
                    <div className="px-5 py-4 border-t border-kb-primary-border bg-kb-primary-bg">
                      <p className="text-[12px] font-bold text-kb-text mb-3">LTV 계산 결과</p>
                      <div className="grid grid-cols-3 gap-4 text-[12px]">
                        <div className="border border-kb-primary-border bg-white rounded-lg p-3 text-center">
                          <p className="text-kb-text-muted mb-1">LTV 비율</p>
                          <p className="font-bold text-[16px] text-kb-text">{bps(ltv.ltvRatioBps)}%</p>
                        </div>
                        <div className="border border-kb-primary-border bg-white rounded-lg p-3 text-center">
                          <p className="text-kb-text-muted mb-1">한도 내 최대 대출</p>
                          <p className="font-bold text-[14px] text-kb-text">{formatWon(ltv.maxLoanAmount)}</p>
                        </div>
                        <div className={`border rounded-lg p-3 text-center ${
                          ltv.ltvStatusCd === 'PASS' ? 'border-green-300 bg-green-50' : 'border-red-300 bg-red-50'}`}>
                          <p className="text-kb-text-muted text-[12px] mb-1">심사 결과</p>
                          <p className={`font-bold text-[16px] ${LTV_STATUS_LABEL[ltv.ltvStatusCd]?.cls}`}>
                            {LTV_STATUS_LABEL[ltv.ltvStatusCd]?.text ?? ltv.ltvStatusCd}
                          </p>
                        </div>
                      </div>
                      <p className="text-[11px] text-kb-text-muted mt-3">
                        감정가 {formatWon(ltv.appliedColValue)} · 선순위 {formatWon(ltv.seniorLienAmount)} · 신청금액 {formatWon(ltv.requestedAmount)} · 한도 {bps(ltv.ltvLimitBps)}%
                      </p>
                    </div>
                  ) : col.colStatusCd === 'EVALUATED' ? (
                    <div className="px-5 py-3 border-t border-kb-primary-border flex items-center gap-3">
                      <button onClick={() => handleCalcLtv(col.colId)}
                        className="px-5 py-1.5 border border-kb-text text-[12px] text-kb-text hover:bg-kb-primary-bg transition-colors">
                        LTV 계산
                      </button>
                    </div>
                  ) : null}
                </div>
              )
            })}
          </div>
        </section>
      )}

      {/* 담보 등록 폼 */}
      <section className="mb-6">
        <h2 className="text-lg font-bold text-kb-text mb-5 pb-2 border-b border-kb-primary-border">담보물 등록</h2>
        <div className="border border-kb-primary-border divide-y divide-kb-border overflow-hidden">

          <div className="flex items-center px-5 py-4 gap-4">
            <label className="w-36 text-[13px] font-medium text-kb-text flex-shrink-0">
              담보 종류 <span className="text-kb-red">*</span>
            </label>
            <div className="flex gap-2 flex-wrap">
              {COL_TYPES.map(t => (
                <button key={t.code} onClick={() => setColType(t.code)}
                  className={`px-4 py-1.5 border text-[12px] rounded-lg transition-colors ${
                    colType === t.code
                      ? 'bg-kb-primary border-kb-text font-bold text-kb-text'
                      : 'border-kb-primary-border text-kb-text-muted hover:bg-kb-primary-bg'}`}>
                  {t.label}
                </button>
              ))}
            </div>
          </div>

          <div className="flex items-center px-5 py-4 gap-4">
            <label className="w-36 text-[13px] font-medium text-kb-text flex-shrink-0">담보명</label>
            <input value={colName} onChange={e => setColName(e.target.value)}
              placeholder="예: 서울시 강남구 아파트"
              className="flex-1 border border-kb-primary-border px-3 py-2 text-[13px] focus:outline-none max-w-sm" />
          </div>

          <div className="flex items-center px-5 py-4 gap-4">
            <label className="w-36 text-[13px] font-medium text-kb-text flex-shrink-0">주소</label>
            <input value={colAddress} onChange={e => setColAddress(e.target.value)}
              placeholder="예: 서울특별시 강남구 테헤란로 123"
              className="flex-1 border border-kb-primary-border px-3 py-2 text-[13px] focus:outline-none max-w-lg" />
          </div>

          <div className="flex items-center px-5 py-4 gap-4">
            <label className="w-36 text-[13px] font-medium text-kb-text flex-shrink-0">신고가액</label>
            <div className="flex items-center gap-2">
              <input type="text" value={declaredValue ? parseInt(declaredValue.replace(/,/g, '')).toLocaleString('ko-KR') : ''}
                onChange={e => setDeclaredValue(e.target.value.replace(/[^\d]/g, ''))}
                placeholder="0" className="border border-kb-primary-border px-3 py-2 text-[13px] w-40 focus:outline-none text-right" />
              <span className="text-[13px] text-kb-text">원</span>
              <span className="text-[12px] text-kb-text-muted">(감정평가액으로 사용됩니다)</span>
            </div>
          </div>

          <div className="flex items-center px-5 py-4 gap-4">
            <label className="w-36 text-[13px] font-medium text-kb-text flex-shrink-0">소유 형태</label>
            <div className="flex gap-2">
              {OWNERSHIP_TYPES.map(t => (
                <button key={t.code} onClick={() => setOwnershipType(t.code)}
                  className={`px-4 py-1.5 border text-[12px] rounded-lg transition-colors ${
                    ownershipType === t.code
                      ? 'bg-kb-primary border-kb-text font-bold text-kb-text'
                      : 'border-kb-primary-border text-kb-text-muted hover:bg-kb-primary-bg'}`}>
                  {t.label}
                </button>
              ))}
            </div>
          </div>

          <div className="flex items-start px-5 py-4 gap-4">
            <label className="w-36 text-[13px] font-medium text-kb-text flex-shrink-0 pt-2">선순위 근저당</label>
            <div className="space-y-2">
              <div className="flex gap-2">
                {([false, true] as const).map(v => (
                  <button key={String(v)} onClick={() => setSeniorLienYn(v)}
                    className={`px-4 py-1.5 border text-[12px] rounded-lg transition-colors ${
                      seniorLienYn === v
                        ? 'bg-kb-primary border-kb-text font-bold text-kb-text'
                        : 'border-kb-primary-border text-kb-text-muted hover:bg-kb-primary-bg'}`}>
                    {v ? '있음' : '없음'}
                  </button>
                ))}
              </div>
              {seniorLienYn && (
                <div className="flex items-center gap-2">
                  <input type="text"
                    value={seniorLienAmt ? parseInt(seniorLienAmt.replace(/,/g, '')).toLocaleString('ko-KR') : ''}
                    onChange={e => setSeniorLienAmt(e.target.value.replace(/[^\d]/g, ''))}
                    placeholder="선순위 금액"
                    className="border border-kb-primary-border px-3 py-2 text-[13px] w-40 focus:outline-none text-right" />
                  <span className="text-[13px] text-kb-text">원</span>
                </div>
              )}
            </div>
          </div>
        </div>
      </section>

      {error && <p className="text-center text-[13px] text-kb-red mb-4">{error}</p>}

      <div className="flex justify-center gap-3">
        <Link href={`/loans/apply/result?applId=${applId}`}
          className="px-10 py-3 border border-kb-primary-border text-[14px] text-kb-text hover:bg-kb-primary-bg transition-colors">
          신청 결과로
        </Link>
        <button onClick={handleRegister} disabled={!canSubmit}
          className={`px-14 py-3 text-[14px] font-bold transition-all ${
            canSubmit
              ? 'bg-kb-primary text-white hover:opacity-85'
              : 'bg-gray-200 text-gray-400 cursor-not-allowed'}`}>
          {submitting ? '등록 중...' : '담보 등록'}
        </button>
      </div>
    </div>
  )
}
