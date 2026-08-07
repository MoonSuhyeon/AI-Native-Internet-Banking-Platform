'use client'

import { useState, useEffect, useRef } from 'react'
import type { Loan } from '@/lib/generated'
import Link from 'next/link'
import { useParams, useRouter } from 'next/navigation'
import { loanApplicationApi } from '@/lib/loan-api'

const DOC_TYPES = [
  { code: 'EMPLOYMENT_CERT', label: '재직증명서', desc: '발급일로부터 3개월 이내' },
  { code: 'INCOME_CERT', label: '소득확인증명서', desc: '국세청 발급 가능' },
  { code: 'HEALTH_INSURANCE', label: '건강보험료납부확인서', desc: '최근 3개월 이내' },
  { code: 'ID_CARD', label: '신분증 사본', desc: '주민등록증 또는 운전면허증' },
]

// 손으로 쓰지 않는다. 예전에는 fileName·uploadedAt 을 적어 뒀는데 백엔드에는 그 이름이
// 없다(docName·submittedAt 이 정본) — 제출 완료 표시가 undefined 였다.
type UploadedDoc = Loan['LoanDocumentResponse']

export default function LoanDocumentsPage() {
  const params = useParams()
  const router = useRouter()
  const applId = parseInt(params.applId as string, 10)

  const [docs, setDocs] = useState<UploadedDoc[]>([])
  const [uploading, setUploading] = useState<string | null>(null)
  const [error, setError] = useState('')
  const [loading, setLoading] = useState(true)
  const fileInputRef = useRef<HTMLInputElement>(null)
  const [activeDocType, setActiveDocType] = useState<string | null>(null)

  useEffect(() => {
    loanApplicationApi.getDocuments(applId)
      .then(({ data: res }) => setDocs(res.data?.items ?? []))
      .catch(() => {})
      .finally(() => setLoading(false))
  }, [applId])

  function handleFileClick(docTypeCd: string) {
    setActiveDocType(docTypeCd)
    fileInputRef.current?.click()
  }

  async function handleFileChange(e: React.ChangeEvent<HTMLInputElement>) {
    const file = e.target.files?.[0]
    if (!file || !activeDocType) return
    e.target.value = ''

    setUploading(activeDocType)
    setError('')
    const formData = new FormData()
    formData.append('file', file)
    formData.append('docTypeCd', activeDocType)

    try {
      const { data: res } = await loanApplicationApi.uploadDocument(applId, formData)
      const uploaded = res.data
      setDocs(prev => {
        const filtered = prev.filter(d => d.docTypeCd !== activeDocType)
        return [...filtered, uploaded]
      })
    } catch (err: any) {
      setError(err.response?.data?.message ?? '업로드 중 오류가 발생했습니다.')
    } finally {
      setUploading(null)
      setActiveDocType(null)
    }
  }

  const uploadedTypes = new Set(docs.map(d => d.docTypeCd))

  return (
    <div className="max-w-kb-container mx-auto px-6 py-4 pb-16">
      <div className="flex justify-end mb-4 text-[12px] text-kb-text-muted gap-1">
        <span>개인뱅킹</span><span>&gt;</span>
        <span>대출</span><span>&gt;</span>
        <Link href={`/loans/apply/result?applId=${applId}`} className="hover:underline">신청 결과</Link>
        <span>&gt;</span>
        <span className="font-semibold text-kb-text">서류 제출</span>
      </div>

      <h1 className="text-[22px] font-bold text-kb-text mb-2 pb-2 border-b-2 border-kb-primary">서류 제출</h1>
      <p className="text-[13px] text-kb-text-muted mb-6">신청번호: {applId} | 서류를 제출하면 심사에 반영됩니다.</p>

      <input
        ref={fileInputRef}
        type="file"
        accept=".pdf,.jpg,.jpeg,.png"
        className="hidden"
        onChange={handleFileChange}
      />

      {error && <p className="text-[13px] text-kb-red mb-4">{error}</p>}

      <div className="space-y-3 mb-8">
        {DOC_TYPES.map(dt => {
          const uploaded = docs.find(d => d.docTypeCd === dt.code)
          const isUploading = uploading === dt.code
          const verifyResult = uploaded?.verifyResultCd
          const isHold = verifyResult === 'HOLD' || verifyResult === 'FRAUD'
          const needsResubmit = verifyResult === 'NEEDS_RESUBMIT'
          return (
            <div key={dt.code} className={`border p-5 flex items-center justify-between ${
              isHold ? 'border-orange-300 bg-orange-50' :
              needsResubmit ? 'border-orange-400 bg-yellow-50' :
              'border-kb-primary-border'
            }`}>
              <div>
                <p className="text-[14px] font-bold text-kb-text mb-0.5">{dt.label}</p>
                <p className="text-[12px] text-kb-text-muted">{dt.desc}</p>
                {uploaded && (
                  isHold ? (
                    <p className="text-[12px] text-orange-600 mt-1">
                      검토 중입니다. 추가 안내를 기다려주세요.
                    </p>
                  ) : needsResubmit ? (
                    <p className="text-[12px] text-orange-700 font-semibold mt-1">
                      ⚠ 재제출이 필요합니다 — 화질이 낮거나 내용이 누락되었습니다.
                    </p>
                  ) : (
                    <p className="text-[12px] text-green-600 mt-1">
                      ✓ {uploaded.docName} 제출 완료
                    </p>
                  )
                )}
              </div>
              <button
                onClick={() => handleFileClick(dt.code)}
                disabled={isUploading || isHold}
                className={`px-5 py-2 text-[13px] font-medium transition-colors whitespace-nowrap ${
                  isHold
                    ? 'border border-gray-300 text-gray-400 cursor-not-allowed opacity-50'
                    : needsResubmit
                    ? 'bg-orange-500 text-white font-bold hover:brightness-95'
                    : uploadedTypes.has(dt.code)
                    ? 'border border-kb-primary-border text-kb-text-muted hover:bg-kb-primary-bg'
                    : 'bg-kb-primary text-kb-text font-bold hover:brightness-95'
                }`}
              >
                {isUploading ? '업로드 중...' :
                 isHold ? '재업로드 불가' :
                 needsResubmit ? '재업로드' :
                 uploadedTypes.has(dt.code) ? '재업로드' : '파일 선택'}
              </button>
            </div>
          )
        })}
      </div>

      {loading && <p className="text-[13px] text-kb-text-muted text-center">기존 서류 확인 중...</p>}

      <div className="border border-[#b3cce8] bg-[#f0f6ff] p-4 mb-6 space-y-1">
        <p className="text-[13px] text-kb-text-body">· PDF, JPG, PNG 파일만 업로드 가능합니다.</p>
        <p className="text-[13px] text-kb-text-body">· 파일 1개당 최대 10MB입니다.</p>
        <p className="text-[13px] text-kb-text-body">· 제출된 서류는 취소할 수 없으며, 재업로드 시 기존 파일이 대체됩니다.</p>
      </div>

      <div className="flex justify-center gap-3">
        <button
          onClick={() => router.push(`/loans/apply/result?applId=${applId}`)}
          className="px-14 py-3 bg-kb-primary text-[14px] font-bold text-kb-text hover:brightness-95 transition-all"
        >
          제출 완료
        </button>
      </div>
    </div>
  )
}
