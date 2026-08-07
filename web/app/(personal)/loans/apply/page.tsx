'use client'

import { useState, useEffect } from 'react'
import { useRouter } from 'next/navigation'
import Link from 'next/link'
import { loanProductApi, loanApplicationApi, creditScorePreviewApi, bpsToRate as bpsToRateUtil } from '@/lib/loan-api'

const PURPOSES = ['생활비', '교육비', '의료비', '주택구입', '전세자금', '사업자금', '부채상환', '기타']
const PURPOSE_CD: Record<string, string> = {
  '생활비': 'LIVING', '교육비': 'EDUCATION', '의료비': 'MEDICAL',
  '주택구입': 'HOUSING', '전세자금': 'CHARTER', '사업자금': 'BUSINESS',
  '부채상환': 'DEBT_REPAY', '기타': 'ETC',
}
const EMPLOYMENT_TYPES = ['직장인(정규직)', '직장인(계약직)', '자영업자', '공무원/교직원', '전문직', '무직/기타']
const EMPLOYMENT_CD: Record<string, string> = {
  '직장인(정규직)': 'EMPLOYED_FULL', '직장인(계약직)': 'EMPLOYED_CONTRACT',
  '자영업자': 'SELF_EMPLOYED', '공무원/교직원': 'PUBLIC', '전문직': 'PROFESSIONAL', '무직/기타': 'ETC',
}
const PERIODS = [6, 12, 24, 36, 48, 60]
const STEPS = ['대출상품 선택', '신청정보 입력', '결과 확인']

interface Product {
  prodId: number
  prodName: string
  loanTypeCd: string
  minRateBps: number
  maxRateBps: number
  maxAmount: number
  minPeriodMo: number
  maxPeriodMo: number
}

function bpsToRate(bps: number) { return (bps / 100).toFixed(2) }
function formatMax(amt: number) {
  if (amt >= 100_000_000) return `${amt / 100_000_000}억원`
  if (amt >= 10_000) return `${(amt / 10_000).toLocaleString('ko-KR')}만원`
  return `${amt.toLocaleString('ko-KR')}원`
}

export default function LoanApplyPage() {
  const router = useRouter()
  const [products, setProducts] = useState<Product[]>([])
  const [selectedProdId, setSelectedProdId] = useState<number | null>(null)
  const [amount, setAmount] = useState('')
  const [period, setPeriod] = useState(12)
  const [purpose, setPurpose] = useState('')
  const [employmentType, setEmploymentType] = useState('')
  const [annualIncome, setAnnualIncome] = useState('')
  const [agreed, setAgreed] = useState(false)
  const [submitting, setSubmitting] = useState(false)
  const [submitError, setSubmitError] = useState('')
  const [previewing, setPreviewing] = useState(false)
  const [previewResult, setPreviewResult] = useState<any>(null)
  const [previewError, setPreviewError] = useState('')

  useEffect(() => {
    loanProductApi.list({ prodStatusCd: 'ACTIVE', size: 20 })
      .then(({ data: res }) => {
        const items: Product[] = res.data?.items ?? []
        setProducts(items)
        // 상품 상세에서 넘어온 경우 해당 상품을 미리 선택
        const preselect = new URLSearchParams(window.location.search).get('prodId')
        if (preselect) {
          const pid = parseInt(preselect, 10)
          if (items.some(p => p.prodId === pid)) setSelectedProdId(pid)
        }
      })
      .catch(() => {})
  }, [])

  function formatAmountDisplay(raw: string) {
    const n = parseInt(raw.replace(/,/g, ''), 10)
    if (isNaN(n)) return ''
    return n.toLocaleString('ko-KR')
  }

  async function handleSubmit() {
    if (!selectedProdId || !amount || !purpose || !employmentType || !agreed) return
    setSubmitting(true)
    setSubmitError('')
    const customerId = parseInt(localStorage.getItem('customerId') ?? '1', 10)
    const requestedAmount = parseInt(amount.replace(/,/g, ''), 10)
    try {
      const { data: res } = await loanApplicationApi.create({
        customerId,
        prodId: selectedProdId,
        channelCd: 'INTERNET',
        requestedAmount,
        requestedPeriodMo: period,
        loanPurposeCd: PURPOSE_CD[purpose] ?? 'ETC',
        repaymentMethodCd: 'INSTALLMENT',
        estimatedIncomeAmt: annualIncome ? parseInt(annualIncome) * 10000 : 0,
        employmentTypeCd: EMPLOYMENT_CD[employmentType] ?? 'ETC',
      })
      const applId = res.data?.applId
      router.push(`/loans/apply/${applId}/identity-verification`)
    } catch (err: any) {
      setSubmitError(err.response?.data?.message ?? '신청 중 오류가 발생했습니다.')
    } finally {
      setSubmitting(false)
    }
  }

  async function handlePreview() {
    if (!selectedProdId || !amount) return
    const product = products.find(p => p.prodId === selectedProdId)
    if (!product) return
    const customerId = parseInt(localStorage.getItem('customerId') ?? '1', 10)
    setPreviewing(true); setPreviewResult(null); setPreviewError('')
    try {
      const { data: res } = await creditScorePreviewApi.preview({
        customerId,
        loanTypeCd: product.loanTypeCd ?? 'CREDIT',
        requestedAmount: parseInt(amount.replace(/,/g, ''), 10),
        requestedPeriodMo: period,
        loanPurposeCd: PURPOSE_CD[purpose] ?? undefined,
        employmentTypeCd: EMPLOYMENT_CD[employmentType] ?? undefined,
        estimatedIncomeAmt: annualIncome ? parseInt(annualIncome) * 10000 : 0,
        consentYn: true,
      })
      setPreviewResult(res.data)
    } catch (err: any) {
      setPreviewError(err.response?.data?.message ?? '한도조회 중 오류가 발생했습니다.')
    } finally {
      setPreviewing(false)
    }
  }

  const canPreview = !!(selectedProdId && amount && !previewing)
  const canSubmit = selectedProdId && amount && purpose && employmentType && agreed && !submitting

  return (
    <div className="max-w-kb-container mx-auto px-6 py-4 pb-16">
      <div className="flex justify-end mb-4 text-[12px] text-kb-text-muted gap-1">
        <span>개인뱅킹</span><span>&gt;</span>
        <span>대출</span><span>&gt;</span>
        <span className="font-semibold text-kb-text">대출 신청</span>
      </div>

      <h1 className="text-[22px] font-bold text-kb-text mb-6 pb-2 border-b-2 border-kb-primary">대출 신청</h1>

      <div className="flex items-center justify-center gap-0 mb-8">
        {STEPS.map((step, i) => (
          <div key={step} className="flex items-center">
            <div className="flex flex-col items-center">
              <div className={`w-8 h-8 rounded-full flex items-center justify-center text-[13px] font-bold
                ${i === 0 ? 'bg-kb-primary text-kb-text' : 'border border-kb-primary-border text-kb-text-muted bg-white'}`}>
                {i + 1}
              </div>
              <span className={`text-[11px] mt-1 font-medium ${i === 0 ? 'text-kb-text' : 'text-kb-text-muted'}`}>{step}</span>
            </div>
            {i < STEPS.length - 1 && <div className="w-16 h-px bg-kb-border mx-2 mb-4" />}
          </div>
        ))}
      </div>

      {/* 대출 상품 선택 */}
      <section className="mb-6">
        <h2 className="text-lg font-bold text-kb-text mb-5 pb-2 border-b border-kb-primary-border">
          대출 상품 선택 <span className="text-kb-red text-[11px] font-normal ml-1">* 필수</span>
        </h2>
        {products.length === 0 ? (
          <p className="text-[13px] text-kb-text-muted">상품을 불러오는 중...</p>
        ) : (
          <div className="grid grid-cols-2 gap-4">
            {products.map(p => (
              <button key={p.prodId} onClick={() => setSelectedProdId(p.prodId)}
                className={`border rounded-xl p-6 text-left transition-colors ${
                  selectedProdId === p.prodId ? 'border-kb-text bg-kb-primary/20' : 'border-kb-primary-border hover:bg-kb-primary-bg'}`}>
                <p className="text-[14px] font-bold text-kb-text mb-1">{p.prodName}</p>
                <p className="text-[12px] text-kb-primary font-medium">연 {bpsToRate(p.minRateBps)}% ~ {bpsToRate(p.maxRateBps)}%</p>
                <p className="text-[12px] text-kb-text-muted mt-1">최대 {formatMax(p.maxAmount)}</p>
              </button>
            ))}
          </div>
        )}
      </section>

      {/* 신청 정보 입력 */}
      <section className="mb-6">
        <h2 className="text-lg font-bold text-kb-text mb-5 pb-2 border-b border-kb-primary-border">신청 정보 입력</h2>
        <div className="border border-kb-primary-border divide-y divide-kb-border overflow-hidden">
          <div className="flex items-start px-5 py-4 gap-4">
            <label className="w-36 text-[13px] font-medium text-kb-text flex-shrink-0 pt-2">
              신청 금액 <span className="text-kb-red">*</span>
            </label>
            <div className="flex-1 space-y-2">
              <div className="flex items-center gap-2">
                <input type="text" value={formatAmountDisplay(amount)}
                  onChange={e => setAmount(e.target.value.replace(/,/g, ''))}
                  className="flex-1 border border-kb-primary-border px-3 py-2 text-[13px] focus:outline-none text-right" placeholder="0" />
                <span className="text-[13px] text-kb-text">원</span>
              </div>
              <div className="flex gap-1.5 flex-wrap">
                {[100, 300, 500, 1000, 3000, 5000].map(v => (
                  <button key={v} onClick={() => setAmount(String(v * 10000))}
                    className="border border-kb-primary-border px-3 py-1 text-[11px] text-kb-text-body hover:bg-kb-primary-bg">+{v}만</button>
                ))}
                <button onClick={() => setAmount('')} className="border border-kb-primary-border px-3 py-1 text-[11px] text-kb-text-muted hover:bg-kb-primary-bg">초기화</button>
              </div>
            </div>
          </div>

          <div className="flex items-center px-5 py-4 gap-4">
            <label className="w-36 text-[13px] font-medium text-kb-text flex-shrink-0">
              대출 기간 <span className="text-kb-red">*</span>
            </label>
            <div className="flex gap-1.5">
              {PERIODS.map(p => (
                <button key={p} onClick={() => setPeriod(p)}
                  className={`border px-4 py-1.5 text-[12px] rounded-lg transition-colors ${
                    period === p ? 'bg-kb-primary border-kb-text font-bold text-kb-text' : 'border-kb-primary-border text-kb-text-muted hover:bg-kb-primary-bg'}`}>
                  {p}개월
                </button>
              ))}
            </div>
          </div>

          <div className="flex items-center px-5 py-4 gap-4">
            <label className="w-36 text-[13px] font-medium text-kb-text flex-shrink-0">
              대출 목적 <span className="text-kb-red">*</span>
            </label>
            <select value={purpose} onChange={e => setPurpose(e.target.value)}
              className="border border-kb-primary-border px-3 py-2 text-[13px] focus:outline-none w-48">
              <option value="">선택하세요</option>
              {PURPOSES.map(p => <option key={p} value={p}>{p}</option>)}
            </select>
          </div>

          <div className="flex items-center px-5 py-4 gap-4">
            <label className="w-36 text-[13px] font-medium text-kb-text flex-shrink-0">
              고용 형태 <span className="text-kb-red">*</span>
            </label>
            <select value={employmentType} onChange={e => setEmploymentType(e.target.value)}
              className="border border-kb-primary-border px-3 py-2 text-[13px] focus:outline-none w-48">
              <option value="">선택하세요</option>
              {EMPLOYMENT_TYPES.map(t => <option key={t} value={t}>{t}</option>)}
            </select>
          </div>

          <div className="flex items-center px-5 py-4 gap-4">
            <label className="w-36 text-[13px] font-medium text-kb-text flex-shrink-0">연간 소득</label>
            <div className="flex items-center gap-2">
              <input type="text" value={annualIncome}
                onChange={e => setAnnualIncome(e.target.value.replace(/[^\d]/g, ''))}
                className="border border-kb-primary-border px-3 py-2 text-[13px] w-36 focus:outline-none text-right" placeholder="0" />
              <span className="text-[13px] text-kb-text">만원</span>
            </div>
          </div>
        </div>
      </section>

      {/* 한도조회 (신용점수 미리보기) */}
      <section className="mb-6">
        <h2 className="text-lg font-bold text-kb-text mb-5 pb-2 border-b border-kb-primary-border">한도조회 (선택)</h2>
        <div className="border border-kb-primary-border bg-kb-primary-bg p-4 mb-3 text-[13px] text-kb-text-body">
          <p>· 신청 전 예상 한도·금리·신용점수를 미리 확인합니다. 별도 신용조회 동의가 적용됩니다.</p>
          <p>· 조회 결과는 실제 심사 결과와 다를 수 있습니다.</p>
        </div>
        <div className="flex justify-start">
          <button onClick={handlePreview} disabled={!canPreview}
            className={`px-8 py-2 text-[13px] font-medium border transition-colors ${
              canPreview ? 'border-kb-text text-kb-text hover:bg-kb-primary-bg' : 'border-kb-primary-border text-kb-text-muted cursor-not-allowed'}`}>
            {previewing ? '조회 중...' : '한도조회'}
          </button>
        </div>
        {previewError && <p className="text-[13px] text-kb-red mt-3">{previewError}</p>}
        {previewResult && (
          <div className="mt-4 border border-kb-primary-border">
            <div className="bg-kb-primary-bg px-5 py-3 border-b border-kb-primary-border">
              <p className="text-[13px] font-bold text-kb-text">한도조회 결과</p>
            </div>
            <div className="divide-y divide-kb-border text-[13px]">
              {previewResult.creditScore != null && (
                <div className="flex">
                  <div className="w-36 px-5 py-3 bg-kb-primary-bg font-medium text-kb-text flex-shrink-0">신용점수</div>
                  <div className="px-5 py-3 font-bold text-kb-primary">{previewResult.creditScore}점</div>
                </div>
              )}
              {previewResult.estimatedLimitAmt != null && (
                <div className="flex">
                  <div className="w-36 px-5 py-3 bg-kb-primary-bg font-medium text-kb-text flex-shrink-0">예상 한도</div>
                  <div className="px-5 py-3 font-bold text-kb-text">{previewResult.estimatedLimitAmt.toLocaleString('ko-KR')}원</div>
                </div>
              )}
              {previewResult.estimatedRateBps != null && (
                <div className="flex">
                  <div className="w-36 px-5 py-3 bg-kb-primary-bg font-medium text-kb-text flex-shrink-0">예상 금리</div>
                  <div className="px-5 py-3 font-bold text-kb-text">연 {bpsToRateUtil(previewResult.estimatedRateBps)}%</div>
                </div>
              )}
              {previewResult.creditGrade && (
                <div className="flex">
                  <div className="w-36 px-5 py-3 bg-kb-primary-bg font-medium text-kb-text flex-shrink-0">신용등급</div>
                  <div className="px-5 py-3 text-kb-text-body">{previewResult.creditGrade}</div>
                </div>
              )}
            </div>
          </div>
        )}
      </section>

      {/* 약관 동의 */}
      <section className="mb-6">
        <div className="border border-kb-primary-border p-6 bg-kb-primary-bg">
          <p className="text-[13px] text-kb-text-body leading-relaxed mb-3">
            · 본 대출 신청은 심사 후 승인 결과가 달라질 수 있습니다.<br />
            · 대출 거절 시 신용등급에 영향을 줄 수 있습니다.<br />
            · 과도한 빚은 당신에게 큰 불행을 안겨줄 수 있습니다.
          </p>
          <label className="flex items-center gap-2 cursor-pointer">
            <button type="button" onClick={() => setAgreed(v => !v)}
              className={`w-4 h-4 border flex-shrink-0 flex items-center justify-center transition-colors
                ${agreed ? 'bg-kb-primary border-kb-text' : 'bg-white border-kb-primary-border'}`}>
              {agreed && <svg viewBox="0 0 12 10" fill="none" className="w-3 h-3"><path d="M1 5l3 3 7-7" stroke="#333" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" /></svg>}
            </button>
            <span className="text-[13px] text-kb-text">위 내용을 확인하였으며, <span className="font-bold">개인신용정보 조회 및 대출 신청에 동의합니다.</span></span>
          </label>
        </div>
      </section>

      {submitError && <p className="text-center text-[13px] text-kb-red mb-4">{submitError}</p>}

      <div className="flex justify-center gap-3">
        <Link href="/products/loan"
          className="px-14 py-3 border border-kb-primary-border text-[14px] text-kb-text hover:bg-kb-primary-bg transition-colors">
          취소
        </Link>
        <button onClick={handleSubmit} disabled={!canSubmit}
          className={`px-14 py-3 text-[14px] font-bold transition-all ${
            canSubmit ? 'bg-kb-primary text-white hover:opacity-85' : 'bg-gray-200 text-gray-400 cursor-not-allowed'}`}>
          {submitting ? '처리 중...' : '대출 신청하기'}
        </button>
      </div>
    </div>
  )
}
