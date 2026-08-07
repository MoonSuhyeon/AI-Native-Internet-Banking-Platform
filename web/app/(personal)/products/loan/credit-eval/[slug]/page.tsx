'use client'
import { KB_PRIMARY } from '@/lib/theme'

import { useState, useEffect, useCallback } from 'react'
import { useSearchParams, useParams } from 'next/navigation'
import LoanSidebar from '@/components/inquiry/LoanSidebar'
import AutoBreadcrumb from '@/components/layout/AutoBreadcrumb'
import { loanApplicationApi } from '@/lib/loan-api'

const DOC_STATUS_LABEL: Record<string, string> = {
  PENDING: '검토대기', VERIFIED: '검토완료', REJECTED: '반려',
}
const DOC_STATUS_COLOR: Record<string, string> = {
  PENDING: 'bg-yellow-100 text-yellow-700',
  VERIFIED: 'bg-green-100 text-green-700',
  REJECTED: 'bg-red-100 text-red-700',
}

/* ─── 업체현황 및 사업계획서 ─── */
function BizPlanForm({ applId }: { applId: number | null }) {
  const [mode, setMode] = useState<'view' | 'write'>('view')
  const [docs, setDocs] = useState<any[]>([])
  const [loading, setLoading] = useState(false)
  const [content, setContent] = useState('')
  const [file, setFile] = useState<File | null>(null)
  const [submitting, setSubmitting] = useState(false)
  const [msg, setMsg] = useState('')
  const [err, setErr] = useState('')

  const fetchDocs = useCallback(async () => {
    if (!applId) return
    setLoading(true)
    try {
      const { data: res } = await loanApplicationApi.getDocuments(applId)
      // 응답은 배열이 아니라 { items, totalCount } 다.
      const all = res.data?.items ?? []
      setDocs(all.filter(d => d.docTypeCd === 'BIZ_PLAN' || d.docTypeCd === 'OTHER'))
    } catch { /* ignore */ }
    finally { setLoading(false) }
  }, [applId])

  useEffect(() => { if (mode === 'view') fetchDocs() }, [mode, fetchDocs])

  async function handleSubmit() {
    if (!applId) { setErr('신청 ID가 없습니다. URL에 ?applId=... 를 추가하세요.'); return }
    if (!content && !file) { setErr('내용 또는 파일을 입력하세요.'); return }
    setSubmitting(true); setErr('')
    try {
      const fd = new FormData()
      fd.append('docTypeCd', 'BIZ_PLAN')
      if (file) fd.append('file', file)
      if (content) fd.append('description', content)
      await loanApplicationApi.uploadDocument(applId, fd)
      setMsg('제출이 완료되었습니다.'); setContent(''); setFile(null)
      setTimeout(() => { setMsg(''); setMode('view') }, 1500)
    } catch (e: any) {
      setErr(e?.response?.data?.message ?? '제출 중 오류가 발생했습니다.')
    } finally { setSubmitting(false) }
  }

  return (
    <div>
      <div className="flex gap-3 mb-6">
        <button onClick={() => setMode('view')}
          className={`px-6 py-2 text-[13px] font-bold border transition-colors
            ${mode === 'view' ? 'bg-kb-text text-white border-kb-text' : 'border-kb-primary-border text-kb-text-muted hover:bg-kb-primary-bg'}`}>
          조회
        </button>
        <button onClick={() => setMode('write')}
          className={`px-6 py-2 text-[13px] font-bold border transition-colors
            ${mode === 'write' ? 'bg-kb-text text-white border-kb-text' : 'border-kb-primary-border text-kb-text-muted hover:bg-kb-primary-bg'}`}>
          작성
        </button>
      </div>

      {mode === 'view' ? (
        <>
          {!applId && (
            <p className="text-[13px] text-kb-text-muted py-8 text-center">
              URL에 신청번호(applId)를 지정하면 실제 서류를 조회할 수 있습니다.
            </p>
          )}
          {applId && loading && <p className="py-8 text-center text-[13px] text-kb-text-muted">불러오는 중...</p>}
          {applId && !loading && (
            <table className="w-full text-[13px] border-t-2 border-kb-primary">
              <thead>
                <tr className="bg-[#F5F5F5]">
                  <th className="px-4 py-3 text-center font-medium border-b border-kb-primary-border">제출일</th>
                  <th className="px-4 py-3 text-center font-medium border-b border-kb-primary-border">서류 유형</th>
                  <th className="px-4 py-3 text-center font-medium border-b border-kb-primary-border">처리 상태</th>
                  <th className="px-4 py-3 text-center font-medium border-b border-kb-primary-border">검토 부서</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-kb-border">
                {docs.length === 0 ? (
                  <tr><td colSpan={4} className="py-8 text-center text-kb-text-muted">제출된 서류가 없습니다.</td></tr>
                ) : docs.map((d: any) => (
                  <tr key={d.docId} className="hover:bg-kb-primary-bg">
                    <td className="px-4 py-3 text-center">
                      {(d.uploadedAt ?? d.createdAt)?.slice(0, 10).replace(/-/g, '.') ?? '-'}
                    </td>
                    <td className="px-4 py-3 text-center">{d.fileName ?? '업체현황/사업계획서'}</td>
                    <td className="px-4 py-3 text-center">
                      <span className={`text-[11px] font-bold px-2 py-0.5 ${DOC_STATUS_COLOR[d.docStatusCd] ?? 'bg-gray-100 text-gray-700'}`}>
                        {DOC_STATUS_LABEL[d.docStatusCd] ?? d.docStatusCd}
                      </span>
                    </td>
                    <td className="px-4 py-3 text-center text-kb-text-muted">여신심사팀</td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </>
      ) : (
        <div className="max-w-lg border border-kb-primary-border rounded-xl p-5 space-y-4">
          {msg && <p className="text-[13px] text-green-600 font-medium">{msg}</p>}
          {err && <p className="text-[13px] text-red-500">{err}</p>}
          <div className="flex items-start gap-4">
            <label className="w-28 text-[13px] font-medium text-kb-text flex-shrink-0 pt-2">업체현황 내용</label>
            <textarea rows={5} value={content} onChange={e => setContent(e.target.value)}
              placeholder="업체 현황을 입력하세요"
              className="flex-1 border border-kb-primary-border rounded-lg px-3 py-2 text-[13px] focus:outline-none focus:border-kb-text resize-none" />
          </div>
          <div className="flex items-center gap-4">
            <label className="w-28 text-[13px] font-medium text-kb-text flex-shrink-0">파일 첨부</label>
            <input type="file" accept=".pdf,.doc,.docx"
              onChange={e => setFile(e.target.files?.[0] ?? null)}
              className="flex-1 text-[13px]" />
          </div>
          <div className="flex justify-center pt-2">
            <button onClick={handleSubmit} disabled={submitting}
              className="px-12 py-2.5 text-[14px] font-bold text-white disabled:opacity-50" style={{ backgroundColor: KB_PRIMARY }}>
              {submitting ? '제출 중...' : '제출'}
            </button>
          </div>
        </div>
      )}
    </div>
  )
}

/* ─── FATI 제출 ─── */
const FATI_TYPES = [
  { label: '재무제표 (손익계산서)', value: 'FATI_INCOME' },
  { label: '재무제표 (대차대조표)', value: 'FATI_BALANCE' },
  { label: '부가가치세 신고서',     value: 'FATI_VAT' },
  { label: '종합소득세 신고서',     value: 'FATI_INCOME_TAX' },
]
const YEARS = ['2025년', '2024년', '2023년']

function FatiSubmitForm({ applId }: { applId: number | null }) {
  const [fatiType, setFatiType] = useState(FATI_TYPES[0].value)
  const [year, setYear] = useState(YEARS[0])
  const [file, setFile] = useState<File | null>(null)
  const [submitting, setSubmitting] = useState(false)
  const [msg, setMsg] = useState('')
  const [err, setErr] = useState('')

  async function handleSubmit() {
    if (!applId) { setErr('신청 ID가 없습니다. URL에 ?applId=... 를 추가하세요.'); return }
    if (!file) { setErr('파일을 첨부하세요.'); return }
    setSubmitting(true); setErr('')
    try {
      const fd = new FormData()
      fd.append('docTypeCd', fatiType)
      fd.append('file', file)
      fd.append('description', year)
      await loanApplicationApi.uploadDocument(applId, fd)
      setMsg('제출이 완료되었습니다.'); setFile(null)
      setTimeout(() => setMsg(''), 3000)
    } catch (e: any) {
      setErr(e?.response?.data?.message ?? '제출 중 오류가 발생했습니다.')
    } finally { setSubmitting(false) }
  }

  return (
    <div className="max-w-lg">
      <div className="bg-[#FFF9E6] border border-[#C09B3A] p-4 mb-5 text-[13px] text-kb-text-body">
        <p className="font-bold mb-1">FATI (Financial and Tax Information) 제출 안내</p>
        <p>여신심사를 위해 재무제표, 세무자료 등을 제출합니다. 파일은 PDF 또는 Excel 형식만 업로드 가능합니다.</p>
      </div>
      {msg && <p className="mb-3 text-[13px] text-green-600 font-medium">{msg}</p>}
      {err && <p className="mb-3 text-[13px] text-red-500">{err}</p>}
      <div className="border border-kb-primary-border rounded-xl p-5 space-y-4">
        <div className="flex items-center gap-4">
          <label className="w-28 text-[13px] font-medium text-kb-text flex-shrink-0">자료 유형</label>
          <select value={fatiType} onChange={e => setFatiType(e.target.value)}
            className="flex-1 border border-kb-primary-border rounded-lg px-3 py-2 text-[13px] focus:outline-none">
            {FATI_TYPES.map(t => <option key={t.value} value={t.value}>{t.label}</option>)}
          </select>
        </div>
        <div className="flex items-center gap-4">
          <label className="w-28 text-[13px] font-medium text-kb-text flex-shrink-0">기준연도</label>
          <select value={year} onChange={e => setYear(e.target.value)}
            className="flex-1 border border-kb-primary-border rounded-lg px-3 py-2 text-[13px] focus:outline-none">
            {YEARS.map(y => <option key={y}>{y}</option>)}
          </select>
        </div>
        <div className="flex items-center gap-4">
          <label className="w-28 text-[13px] font-medium text-kb-text flex-shrink-0">파일 첨부</label>
          <input type="file" accept=".pdf,.xlsx,.xls"
            onChange={e => setFile(e.target.files?.[0] ?? null)}
            className="flex-1 text-[13px]" />
        </div>
      </div>
      <div className="flex justify-center mt-5">
        <button onClick={handleSubmit} disabled={submitting}
          className="px-12 py-2.5 text-[14px] font-bold text-white disabled:opacity-50" style={{ backgroundColor: KB_PRIMARY }}>
          {submitting ? '제출 중...' : '제출'}
        </button>
      </div>
    </div>
  )
}

/* ─── FATI 제출내역 ─── */
const FATI_TYPE_LABEL: Record<string, string> = {
  FATI_INCOME: '재무제표 (손익계산서)', FATI_BALANCE: '재무제표 (대차대조표)',
  FATI_VAT: '부가가치세 신고서', FATI_INCOME_TAX: '종합소득세 신고서',
}

function FatiHistoryTable({ applId }: { applId: number | null }) {
  const [docs, setDocs] = useState<any[]>([])
  const [loading, setLoading] = useState(false)

  useEffect(() => {
    if (!applId) return
    setLoading(true)
    loanApplicationApi.getDocuments(applId)
      .then(({ data: res }) => {
        // 응답은 배열이 아니라 { items, totalCount } 다.
      const all = res.data?.items ?? []
        setDocs(all.filter(d => d.docTypeCd?.startsWith('FATI')))
      })
      .catch(() => { /* ignore */ })
      .finally(() => setLoading(false))
  }, [applId])

  if (!applId) return (
    <p className="text-[13px] text-kb-text-muted py-8 text-center">
      URL에 신청번호(applId)를 지정하면 제출내역을 조회할 수 있습니다.
    </p>
  )

  if (loading) return <p className="py-8 text-center text-[13px] text-kb-text-muted">불러오는 중...</p>

  return (
    <table className="w-full text-[13px] border-t-2 border-kb-primary">
      <thead>
        <tr className="bg-[#F5F5F5]">
          <th className="px-4 py-3 text-center font-medium border-b border-kb-primary-border">제출일</th>
          <th className="px-4 py-3 text-left font-medium border-b border-kb-primary-border">자료 유형</th>
          <th className="px-4 py-3 text-center font-medium border-b border-kb-primary-border">기준연도</th>
          <th className="px-4 py-3 text-center font-medium border-b border-kb-primary-border">처리 상태</th>
        </tr>
      </thead>
      <tbody className="divide-y divide-kb-border">
        {docs.length === 0 ? (
          <tr><td colSpan={4} className="py-8 text-center text-kb-text-muted">제출된 내역이 없습니다.</td></tr>
        ) : docs.map((d: any) => (
          <tr key={d.docId} className="hover:bg-kb-primary-bg">
            <td className="px-4 py-3 text-center">
              {(d.uploadedAt ?? d.createdAt)?.slice(0, 10).replace(/-/g, '.') ?? '-'}
            </td>
            <td className="px-4 py-3">{FATI_TYPE_LABEL[d.docTypeCd] ?? d.docTypeCd}</td>
            <td className="px-4 py-3 text-center">{d.description ?? '-'}</td>
            <td className="px-4 py-3 text-center">
              <span className={`text-[11px] font-bold px-2 py-0.5 ${DOC_STATUS_COLOR[d.docStatusCd] ?? 'bg-gray-100 text-gray-700'}`}>
                {DOC_STATUS_LABEL[d.docStatusCd] ?? d.docStatusCd}
              </span>
            </td>
          </tr>
        ))}
      </tbody>
    </table>
  )
}

/* ─── Page ─── */
export default function CreditEvalPage() {
  const { slug } = useParams<{ slug: string }>()
  const searchParams = useSearchParams()
  const applIdRaw = searchParams.get('applId')
  const applId = applIdRaw ? parseInt(applIdRaw) : null

  type Meta = { title: string; breadcrumb: string; content: React.ReactNode }
  const PAGE_MAP: Record<string, Meta> = {
    'biz-plan': {
      title: '「업체현황 및 사업계획서」조회 및 작성',
      breadcrumb: '업체현황 및 사업계획서',
      content: <BizPlanForm applId={applId} />,
    },
    'fati-submit': {
      title: '「FATI (재무 및 세무자료)」제출',
      breadcrumb: 'FATI 제출',
      content: <FatiSubmitForm applId={applId} />,
    },
    'fati-history': {
      title: '「FATI (재무 및 세무자료)」제출내역조회',
      breadcrumb: 'FATI 제출내역조회',
      content: <FatiHistoryTable applId={applId} />,
    },
  }

  const meta = PAGE_MAP[slug]

  if (!meta) {
    return (
      <main className="pb-16">
        <div className="max-w-kb-container mx-auto px-6 pt-6">
          <div className="flex gap-8">
            <LoanSidebar />
            <div className="flex-1 flex items-center justify-center py-20">
              <p className="text-[15px] text-kb-text-muted">페이지를 찾을 수 없습니다.</p>
            </div>
          </div>
        </div>
      </main>
    )
  }

  return (
    <main className="pb-16">
      <div className="max-w-kb-container mx-auto px-6 pt-6">
        <AutoBreadcrumb leaf={meta.breadcrumb} />
        <div className="flex gap-8">
          <LoanSidebar />
          <div className="flex-1 min-w-0">
            <h1 className="text-[26px] font-bold text-kb-text mb-2">{meta.title}</h1>
            {applId && (
              <p className="text-[12px] text-kb-text-muted mb-6">신청번호: {applId}</p>
            )}
            {!applId && (
              <p className="text-[12px] text-yellow-600 mb-6">
                ※ 신청번호를 조회하려면 URL에 ?applId=신청ID 를 입력하세요.
              </p>
            )}
            <div className="border-t-2 border-kb-primary pt-6">
              {meta.content}
            </div>
          </div>
        </div>
      </div>
    </main>
  )
}
