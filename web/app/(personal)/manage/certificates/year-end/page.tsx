'use client'

import { useEffect, useState } from 'react'

import Link from 'next/link'

import { fetchInterestIncomeStatement, type InterestIncomeStatement } from '@/lib/deposit-api'
import { KB_BORDER, KB_PRIMARY, KB_PRIMARY_BG, KB_PRIMARY_BORDER, KB_TEXT_MUTED } from '@/lib/theme'

function won(n: number) {
  return n.toLocaleString('ko-KR') + '원'
}

/** 귀속연도 후보. 올해와 지난 4년. */
const YEARS = Array.from({ length: 5 }, (_, i) => new Date().getFullYear() - i)

/**
 * 연말정산증명서 — 이자소득 원천징수영수증.
 *
 * <p>이 화면이 없어서 홈 검색과 사이드바에서 연말정산증명서 항목을 뺀 채로 두었다.
 * 데이터(deposit_interest_history)는 이미 있었고 보여 줄 곳이 없었다.
 *
 * <p>계좌별 내역을 합계 아래에 함께 둔다. 합계만 주면 숫자가 이상할 때 어디서 왔는지
 * 확인할 길이 없고, 고객은 이 숫자를 그대로 연말정산에 옮겨 적는다.
 */
export default function YearEndCertificatePage() {
  const [taxYear, setTaxYear] = useState(YEARS[0])
  const [data, setData] = useState<InterestIncomeStatement | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')

  useEffect(() => {
    let alive = true
    setLoading(true)
    fetchInterestIncomeStatement(taxYear)
      .then(d => { if (alive) { setData(d); setError('') } })
      .catch((e: unknown) => {
        if (!alive) return
        const status = (e as { response?: { status?: number } })?.response?.status
        setError(status === 403
          ? '로그인 후 이용할 수 있습니다.'
          : '증명서를 불러오지 못했습니다.')
      })
      .finally(() => { if (alive) setLoading(false) })
    return () => { alive = false }
  }, [taxYear])

  return (
    <div className="max-w-kb-container mx-auto px-6 py-8">
      <div className="flex items-center gap-1 text-[12px] text-kb-text-muted mb-6">
        <Link href="/" className="hover:underline">홈</Link>
        <span>›</span>
        <span>뱅킹관리</span>
        <span>›</span>
        <span className="font-semibold text-kb-text">연말정산증명서</span>
      </div>

      <h1 className="text-[22px] font-bold text-kb-text mb-2">연말정산증명서</h1>
      <p className="text-[13px] text-kb-text-muted mb-6">
        이자소득 원천징수영수증입니다. 해당 연도에 지급받은 이자와 원천징수된 세액을 보여드립니다.
      </p>

      <div className="flex items-center gap-2 mb-5">
        <span className="text-[13px] font-semibold text-kb-text">귀속연도</span>
        <select value={taxYear} onChange={e => setTaxYear(Number(e.target.value))}
          className="border rounded-lg px-3 py-1.5 text-[13px] outline-none bg-white"
          style={{ borderColor: KB_PRIMARY_BORDER }}>
          {YEARS.map(y => <option key={y} value={y}>{y}년</option>)}
        </select>
      </div>

      {error && <p className="text-[13px] text-kb-red mb-4">{error}</p>}
      {loading && <p className="text-[13px]" style={{ color: KB_TEXT_MUTED }}>불러오는 중…</p>}

      {data && !loading && (
        <>
          <div className="rounded-xl p-6 mb-6" style={{ border: `1px solid ${KB_PRIMARY_BORDER}` }}>
            <dl className="space-y-2 text-[14px]">
              <div className="flex justify-between">
                <dt style={{ color: KB_TEXT_MUTED }}>이자소득 (세전)</dt>
                <dd className="font-bold text-kb-text">{won(data.totalInterestBeforeTax)}</dd>
              </div>
              <div className="flex justify-between">
                <dt style={{ color: KB_TEXT_MUTED }}>소득세</dt>
                <dd className="text-kb-text">{won(data.totalIncomeTax)}</dd>
              </div>
              <div className="flex justify-between">
                <dt style={{ color: KB_TEXT_MUTED }}>지방소득세</dt>
                <dd className="text-kb-text">{won(data.totalLocalIncomeTax)}</dd>
              </div>
              <div className="flex justify-between pt-2 border-t" style={{ borderColor: KB_BORDER }}>
                <dt className="font-semibold text-kb-text">원천징수 세액 합계</dt>
                <dd className="font-bold" style={{ color: KB_PRIMARY }}>{won(data.totalTaxAmount)}</dd>
              </div>
              <div className="flex justify-between">
                <dt className="font-semibold text-kb-text">실수령 이자</dt>
                <dd className="font-bold" style={{ color: KB_PRIMARY }}>{won(data.totalInterestAfterTax)}</dd>
              </div>
            </dl>
          </div>

          <h2 className="text-[16px] font-bold text-kb-text mb-3">계좌별 내역</h2>
          {data.accounts.length === 0 ? (
            <div className="rounded-xl py-12 text-center text-[14px]"
              style={{ border: `1px solid ${KB_PRIMARY_BORDER}`, color: KB_TEXT_MUTED }}>
              {taxYear}년에 지급받은 이자가 없습니다.
            </div>
          ) : (
            <div className="overflow-x-auto rounded-xl" style={{ border: `1px solid ${KB_PRIMARY_BORDER}` }}>
              <table className="w-full text-[13px]">
                <thead>
                  <tr style={{ backgroundColor: KB_PRIMARY_BG }}>
                    <th className="px-4 py-3 text-left font-semibold text-kb-text">계좌</th>
                    <th className="px-4 py-3 text-right font-semibold text-kb-text">세전 이자</th>
                    <th className="px-4 py-3 text-right font-semibold text-kb-text">소득세</th>
                    <th className="px-4 py-3 text-right font-semibold text-kb-text">지방소득세</th>
                    <th className="px-4 py-3 text-right font-semibold text-kb-text">실수령</th>
                  </tr>
                </thead>
                <tbody>
                  {data.accounts.map(a => (
                    <tr key={a.accountId} className="border-t" style={{ borderColor: KB_BORDER }}>
                      <td className="px-4 py-3">
                        <p className="font-medium text-kb-text">{a.accountAlias ?? '-'}</p>
                        <p className="text-[12px]" style={{ color: KB_TEXT_MUTED }}>
                          {a.accountNumber} · {a.paymentCount}회 지급
                        </p>
                      </td>
                      <td className="px-4 py-3 text-right text-kb-text">{won(a.interestBeforeTax)}</td>
                      <td className="px-4 py-3 text-right text-kb-text">{won(a.incomeTax)}</td>
                      <td className="px-4 py-3 text-right text-kb-text">{won(a.localIncomeTax)}</td>
                      <td className="px-4 py-3 text-right font-semibold" style={{ color: KB_PRIMARY }}>
                        {won(a.interestAfterTax)}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}

          <p className="text-[12px] mt-4" style={{ color: KB_TEXT_MUTED }}>
            이자를 지급받지 않은 계좌는 표시되지 않습니다. 비과세·세금우대 계좌의 세액은 해당 상품의 조건에 따릅니다.
          </p>
        </>
      )}
    </div>
  )
}
