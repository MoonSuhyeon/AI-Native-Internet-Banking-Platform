'use client'
import { KB_PRIMARY } from '@/lib/theme'

import Link from 'next/link'
import { useState, Suspense } from 'react'
import { useSearchParams, useParams } from 'next/navigation'
import LoanSidebar from '@/components/inquiry/LoanSidebar'
import AutoBreadcrumb from '@/components/layout/AutoBreadcrumb'
import { loanApplicationApi, loanContractApi } from '@/lib/loan-api'

// ─── 사후 서류 제출 ─────────────────────────────────────────

function DocsUpload({ applId }: { applId: number | null }) {
  const [files, setFiles] = useState<Record<string, File>>({})
  const [submitting, setSubmitting] = useState(false)
  const [done, setDone] = useState(false)
  const [error, setError] = useState('')

  const docs = [
    { code: 'EMPLOYMENT_CERT', name: '재직증명서', required: true },
    { code: 'INCOME_CERT', name: '소득확인서류', required: true },
    { code: 'HEALTH_INSURANCE', name: '건강보험료 납부확인서', required: false },
  ]

  async function handleSubmit() {
    if (!applId) { setError('신청번호를 확인할 수 없습니다.'); return }
    setSubmitting(true)
    setError('')
    try {
      for (const [code, file] of Object.entries(files)) {
        const fd = new FormData()
        fd.append('file', file)
        fd.append('docTypeCd', code)
        await loanApplicationApi.uploadDocument(applId, fd)
      }
      setDone(true)
    } catch (err: any) {
      setError(err.response?.data?.message ?? '제출 중 오류가 발생했습니다.')
    } finally {
      setSubmitting(false)
    }
  }

  if (done) return (
    <div className="py-12 text-center">
      <p className="text-[16px] font-bold text-green-600 mb-2">서류 제출 완료</p>
      <Link href="/products/loan/status" className="text-[13px] text-kb-primary hover:underline">진행현황으로 돌아가기</Link>
    </div>
  )

  return (
    <div>
      <p className="text-[13px] text-kb-text-muted mb-4">대출 실행 후 요구된 사후 서류를 제출합니다.</p>
      {!applId && <p className="text-[13px] text-kb-red mb-4">신청번호가 없습니다. 진행현황 페이지에서 다시 접근해주세요.</p>}
      <table className="w-full text-[13px] border-t-2 border-kb-primary mb-5">
        <thead>
          <tr className="bg-[#F5F5F5]">
            <th className="px-4 py-3 text-left font-medium border-b border-kb-primary-border">서류명</th>
            <th className="px-4 py-3 text-center font-medium border-b border-kb-primary-border">필수여부</th>
            <th className="px-4 py-3 text-center font-medium border-b border-kb-primary-border">파일선택</th>
          </tr>
        </thead>
        <tbody className="divide-y divide-kb-border">
          {docs.map(d => (
            <tr key={d.code} className="hover:bg-kb-primary-bg">
              <td className="px-4 py-3">{d.name}</td>
              <td className="px-4 py-3 text-center">
                <span className={`text-[11px] font-bold px-2 py-0.5 ${d.required ? 'bg-red-500 text-white' : 'bg-kb-border text-kb-text-muted'}`}>
                  {d.required ? '필수' : '선택'}
                </span>
              </td>
              <td className="px-4 py-3 text-center">
                <input type="file" accept=".pdf,.jpg,.jpeg,.png"
                  onChange={e => { const f = e.target.files?.[0]; if (f) setFiles(prev => ({ ...prev, [d.code]: f })) }}
                  className="text-[11px]" />
              </td>
            </tr>
          ))}
        </tbody>
      </table>
      {error && <p className="text-[13px] text-kb-red mb-3">{error}</p>}
      <div className="flex justify-center">
        <button onClick={handleSubmit} disabled={submitting || !applId}
          className={`px-12 py-2.5 text-[14px] font-bold text-white ${submitting || !applId ? 'opacity-50' : ''}`}
          style={{ backgroundColor: KB_PRIMARY }}>
          {submitting ? '제출 중...' : '제출'}
        </button>
      </div>
    </div>
  )
}

// ─── 전자서명 ────────────────────────────────────────────────

function ESignForm({ applId }: { applId: number | null }) {
  const [step, setStep] = useState<'sign' | 'account' | 'done'>('sign')
  const [journey, setJourney] = useState<any>(null)
  const [cntrNo, setCntrNo] = useState('')
  const [accountNo, setAccountNo] = useState('')
  const [submitting, setSubmitting] = useState(false)
  const [error, setError] = useState('')
  const [certType, setCertType] = useState('금융인증서')

  async function handleSign() {
    if (!applId) { setError('신청번호를 확인할 수 없습니다.'); return }
    setSubmitting(true); setError('')
    try {
      await loanApplicationApi.submitConsent(applId, {
        consentTypeCd: 'ESIGN',
        consentScopeCd: 'LOAN_CONTRACT',
        consentTargetCd: 'BORROWER',
        consentMethodCd: 'ELECTRONIC',
      })
      const { data: res } = await loanApplicationApi.journey(applId)
      setJourney(res.data)
      setStep('account')
    } catch (err: any) {
      setError(err.response?.data?.message ?? '전자서명 처리 중 오류가 발생했습니다.')
    } finally {
      setSubmitting(false)
    }
  }

  async function handleExecute() {
    if (!journey || !accountNo) return
    setSubmitting(true); setError('')
    const appl = journey.application
    const rateBps = journey.creditEvaluation?.rateBps ?? journey.prescreening?.rateBps ?? 500
    try {
      const { data: contractRes } = await loanContractApi.create(appl.applId, {
        contractedAmount: appl.requestedAmount,
        contractedPeriodMo: appl.requestedPeriodMo,
        baseRateBps: rateBps,
        spreadBps: 0,
        rateTypeCd: 'FIXED',
        repaymentMethodCd: appl.repaymentMethodCd ?? 'INSTALLMENT',
      })
      const newCntrId = contractRes.data?.cntrId
      setCntrNo(contractRes.data?.cntrNo ?? '')
      await loanContractApi.execute(newCntrId, {
        executedAmount: appl.requestedAmount,
        disbursementAccountNo: accountNo,
      })
      setStep('done')
    } catch (err: any) {
      setError(err.response?.data?.message ?? '대출 실행 처리 중 오류가 발생했습니다.')
    } finally {
      setSubmitting(false)
    }
  }

  if (step === 'done') return (
    <div className="py-12 text-center">
      <div className="w-16 h-16 rounded-full bg-kb-primary flex items-center justify-center mx-auto mb-3">
        <svg viewBox="0 0 40 40" fill="none" className="w-9 h-9">
          <path d="M8 20l8 8 16-16" stroke="#333" strokeWidth="3" strokeLinecap="round" strokeLinejoin="round" />
        </svg>
      </div>
      <p className="text-[16px] font-bold text-kb-text mb-1">대출 실행 완료</p>
      {cntrNo && <p className="text-[13px] text-kb-text-muted mb-1">계약번호: {cntrNo}</p>}
      <p className="text-[13px] text-kb-text-muted mb-4">지정 계좌로 대출금이 지급됩니다.</p>
      <Link href="/products/loan/manage/payment" className="text-[13px] text-kb-primary hover:underline mr-4">상환 스케줄 확인</Link>
      <Link href="/products/loan/status" className="text-[13px] text-kb-primary hover:underline">진행현황으로 돌아가기</Link>
    </div>
  )

  if (step === 'account') {
    const appl = journey?.application
    return (
      <div className="max-w-lg">
        <div className="bg-[#FFF9E6] border border-kb-gold p-4 mb-5 text-[13px] text-kb-text-body">
          <p className="font-bold mb-1">전자서명 완료 — 대출 실행 정보를 입력해 주세요.</p>
          <p>지정 계좌로 대출금이 즉시 지급됩니다.</p>
        </div>
        <div className="border border-kb-primary-border rounded-xl overflow-hidden divide-y divide-kb-border">
          {appl && (
            <>
              <div className="flex items-center px-5 py-3 gap-6">
                <span className="w-32 text-[13px] font-medium text-kb-text flex-shrink-0">실행 금액</span>
                <span className="text-[13px] font-bold">{appl.requestedAmount.toLocaleString('ko-KR')}원</span>
              </div>
              <div className="flex items-center px-5 py-3 gap-6">
                <span className="w-32 text-[13px] font-medium text-kb-text flex-shrink-0">대출 기간</span>
                <span className="text-[13px]">{appl.requestedPeriodMo}개월</span>
              </div>
            </>
          )}
          <div className="flex items-center px-5 py-3 gap-6">
            <span className="w-32 text-[13px] font-medium text-kb-text flex-shrink-0">입금 계좌번호 <span className="text-kb-red">*</span></span>
            <input type="text" value={accountNo} onChange={e => setAccountNo(e.target.value)}
              placeholder="계좌번호 입력 (예: 123-456-789012)"
              className="flex-1 border border-kb-primary-border rounded-lg px-3 py-2 text-[13px] focus:outline-none focus:border-kb-text" />
          </div>
        </div>
        {error && <p className="text-[13px] text-kb-red mt-3">{error}</p>}
        <div className="flex justify-center mt-5 gap-3">
          <button onClick={() => { setStep('sign'); setError('') }}
            className="px-8 py-2.5 text-[13px] border border-kb-primary-border rounded-xl text-kb-text hover:bg-kb-primary-bg">
            이전
          </button>
          <button onClick={handleExecute} disabled={submitting || !accountNo}
            className={`px-12 py-2.5 text-[14px] font-bold text-white disabled:opacity-50`}
            style={{ backgroundColor: KB_PRIMARY }}>
            {submitting ? '처리 중...' : '대출 실행'}
          </button>
        </div>
      </div>
    )
  }

  return (
    <div className="max-w-lg">
      {!applId && <p className="text-[13px] text-kb-red mb-4">신청번호가 없습니다. 진행현황 페이지에서 다시 접근해주세요.</p>}
      <div className="bg-[#FFF9E6] border border-kb-gold p-4 mb-5 text-[13px] text-kb-text-body">
        <p className="font-bold mb-1">전자서명 안내</p>
        <p>약정 체결을 위한 전자서명 후 지정 계좌로 대출금이 지급됩니다.</p>
      </div>
      <div className="border border-kb-primary-border rounded-xl p-5 space-y-4">
        <div className="flex items-center gap-4">
          <label className="w-28 text-[13px] font-medium text-kb-text flex-shrink-0">신청번호</label>
          <span className="text-[13px] text-kb-text font-bold">{applId ?? '-'}</span>
        </div>
        <div className="flex items-center gap-4">
          <label className="w-28 text-[13px] font-medium text-kb-text flex-shrink-0">인증 수단</label>
          <select value={certType} onChange={e => setCertType(e.target.value)}
            className="flex-1 border border-kb-primary-border rounded-lg px-3 py-2 text-[13px] focus:outline-none">
            <option>금융인증서</option>
            <option>공동인증서 (구 공인인증서)</option>
          </select>
        </div>
      </div>
      {error && <p className="text-[13px] text-kb-red mt-3">{error}</p>}
      <div className="flex justify-center mt-5">
        <button onClick={handleSign} disabled={submitting || !applId}
          className={`px-12 py-2.5 text-[14px] font-bold text-white ${submitting || !applId ? 'opacity-50' : ''}`}
          style={{ backgroundColor: KB_PRIMARY }}>
          {submitting ? '처리 중...' : '전자서명 진행'}
        </button>
      </div>
    </div>
  )
}

// ─── 페이지 라우팅 ────────────────────────────────────────────

type SlugMeta = { title: string; breadcrumb: string }

const PAGE_META: Record<string, SlugMeta> = {
  docs:       { title: '사후서류제출',          breadcrumb: '사후서류제출' },
  sign:       { title: '전자서명',               breadcrumb: '전자서명' },
}

function PageContent({ slug, applId }: { slug: string; applId: number | null }) {
  switch (slug) {
    case 'docs':
      return <DocsUpload applId={applId} />
    case 'sign':
      return <ESignForm applId={applId} />
    default:
      return <p className="text-[15px] text-kb-text-muted py-20 text-center">페이지를 찾을 수 없습니다.</p>
  }
}

function StatusSlugContent({ slug }: { slug: string }) {
  const searchParams = useSearchParams()
  const applIdRaw = searchParams.get('applId')
  const applId = applIdRaw ? parseInt(applIdRaw, 10) : null
  const meta = PAGE_META[slug]

  return (
    <main className="pb-16">
      <div className="max-w-kb-container mx-auto px-6 pt-6">
        <AutoBreadcrumb leaf={meta?.breadcrumb ?? slug} />
        <div className="flex gap-8">
          <LoanSidebar />
          <div className="flex-1 min-w-0">
            <h1 className="text-[26px] font-bold text-kb-text mb-6">{meta?.title ?? slug}</h1>
            <div className="border-t-2 border-kb-primary pt-6">
              <PageContent slug={slug} applId={applId} />
            </div>
          </div>
        </div>
      </div>
    </main>
  )
}

export default function StatusSlugPage() {
  const { slug } = useParams<{ slug: string }>()
  return (
    <Suspense fallback={<div className="max-w-kb-container mx-auto px-6 py-16 text-center text-kb-text-muted">로딩 중...</div>}>
      <StatusSlugContent slug={slug} />
    </Suspense>
  )
}
