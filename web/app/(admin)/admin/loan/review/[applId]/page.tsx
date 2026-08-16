'use client'

import { useState, useEffect, useCallback } from 'react'
import { useParams } from 'next/navigation'
import Link from 'next/link'
import AdminSidebar from '@/components/admin/AdminSidebar'
import { adminReviewApi, loanApplicationApi } from '@/lib/loan-api'
import { viewAdvisoryReport, ackAdvisoryReport, AckResponseCd } from '@/lib/advisory-api'
import { getAdminLoanRole } from '@/lib/admin-loan-auth'

const REV_STATUS: Record<string, { text: string; cls: string }> = {
  PENDING_APPROVAL:  { text: '확정 대기',   cls: 'bg-yellow-100 text-yellow-700 border-yellow-300' },
  PENDING_APPROVER:  { text: '승인자 대기', cls: 'bg-blue-100   text-blue-700   border-blue-300' },
  COMPLETED:         { text: '완료',         cls: 'bg-green-100  text-green-700  border-green-300' },
  EXPIRED:           { text: '만료',         cls: 'bg-gray-100   text-gray-500   border-gray-300' },
  BIAS_REVIEWING:    { text: '편향검토중',   cls: 'bg-red-100    text-red-700    border-red-300' },
}

const REJECT_REASONS = ['CREDIT_SCORE', 'HIGH_DSR', 'INCOME_VERIFICATION', 'COLLATERAL_INSUFFICIENT', 'POLICY_REJECT', 'OTHER']

export default function LoanReviewDetailPage() {
  const { applId } = useParams<{ applId: string }>()
  const numApplId = parseInt(applId, 10)

  // pre-review data
  const [appl, setAppl]                 = useState<any>(null)
  const [prescreening, setPrescreening] = useState<any>(null)
  const [creditEval, setCreditEval]     = useState<any>(null)
  const [dsr, setDsr]                   = useState<any>(null)

  // review data
  const [review, setReview]             = useState<any>(null)
  const [advices, setAdvices]           = useState<any[]>([])
  const [checks, setChecks]             = useState<any[]>([])
  const [advisoryReports, setAdvisory]  = useState<any[]>([])

  const [loading, setLoading] = useState(true)
  const [busy, setBusy]       = useState(false)
  const [msg, setMsg]         = useState('')
  const [err, setErr]         = useState('')

  // 어드민 목업 세션 역할 — 버튼 노출 제어용. 하이드레이션 불일치 방지 위해 마운트 후 주입.
  const [loanRole, setLoanRole] = useState('')

  // review form state
  const [revType, setRevType]               = useState('MANUAL')
  const [revDecision, setRevDecision]       = useState('APPROVED')
  const [confirmRemark, setConfirmRemark]   = useState('')
  const [ackRemark, setAckRemark]           = useState('')
  const [overrideReason, setOverrideReason] = useState('')
  const [reviseDecision, setReviseDecision] = useState('APPROVED')
  const [reviseAmount, setReviseAmount]     = useState('')
  const [reviseRate, setReviseRate]         = useState('')
  const [reviseReject, setReviseReject]     = useState('CREDIT_SCORE')
  const [revisitReason, setRevisitReason]   = useState('')
  const [checkItemCd, setCheckItemCd]       = useState('DOCUMENT_CHECK')
  const [checkResultCd, setCheckResultCd]   = useState('PASS')
  // 신용평가 입력
  const [cevalEngine, setCevalEngine]       = useState('KCB')
  const [cevalDecision, setCevalDecision]   = useState('APPROVE')
  // DSR 입력
  const [annualIncome, setAnnualIncome]     = useState('')

  const load = useCallback(async () => {
    setLoading(true)
    try {
      const [applRes, psRes, ceRes, dsrRes, revRes] = await Promise.allSettled([
        loanApplicationApi.get(numApplId),
        loanApplicationApi.getPrescreening(numApplId),
        loanApplicationApi.getCreditEvaluation(numApplId),
        loanApplicationApi.getDsr(numApplId),
        adminReviewApi.get(numApplId),
      ])
      if (applRes.status === 'fulfilled') setAppl(applRes.value.data?.data ?? null)
      if (psRes.status  === 'fulfilled') setPrescreening(psRes.value.data?.data ?? null)
      if (ceRes.status  === 'fulfilled') setCreditEval(ceRes.value.data?.data ?? null)
      if (dsrRes.status === 'fulfilled') setDsr(dsrRes.value.data?.data ?? null)
      if (revRes.status === 'fulfilled') {
        const rev = revRes.value.data?.data ?? null
        setReview(rev)
        if (rev?.revId) {
          const [adv, chk, adr] = await Promise.all([
            adminReviewApi.getAdvices(rev.revId),
            adminReviewApi.getChecks(rev.revId),
            adminReviewApi.getAdvisoryReports(rev.revId),
          ])
          setAdvices(adv.data?.data ?? [])
          setChecks(chk.data?.data ?? [])
          setAdvisory(adr.data?.data ?? [])
        }
      }
    } catch {}
    finally { setLoading(false) }
  }, [numApplId])

  useEffect(() => { load() }, [load])
  useEffect(() => { setLoanRole(getAdminLoanRole()) }, [])

  function notify(m: string) { setMsg(m); setTimeout(() => setMsg(''), 3000) }
  function fail(m: string)   { setErr(m); setTimeout(() => setErr(''), 4000) }

  async function act(fn: () => Promise<any>, successMsg: string) {
    setBusy(true); setErr(''); setMsg('')
    try { await fn(); notify(successMsg); await load() }
    catch (e: any) { fail(e?.response?.data?.message ?? '처리 실패') }
    finally { setBusy(false) }
  }

  const status = review?.revStatusCd
  const revId  = review?.revId

  // 가심사 통과 여부
  const psPass  = prescreening?.prescResultCd === 'PASS'
  const psDone  = !!prescreening
  const ceDone  = !!creditEval
  const dsrDone = !!dsr

  // 역할 기반 버튼 노출 — 서버 인가(loan-service SecurityConfig)와 매트릭스를 맞춘다.
  //   가심사·신용평가·DSR 실행 → 심사역(DEPUTY)·운영(OPS)
  //   결정 정정             → 지점장(BRANCH_MANAGER)
  const canRunReview = loanRole === 'ROLE_DEPUTY_MANAGER' || loanRole === 'ROLE_OPS'
  const canRevise    = loanRole === 'ROLE_BRANCH_MANAGER'

  return (
    <div className="flex min-h-screen bg-gray-50">
      <AdminSidebar />
      <main className="flex-1 overflow-auto">
        <div className="bg-white border-b border-gray-200 px-6 py-3 text-xs text-gray-500">
          대출 &gt; <Link href="/admin/loan/review" className="hover:underline">본심사 목록</Link> &gt;{' '}
          <span className="text-gray-800 font-medium">신청 #{applId} 심사</span>
        </div>

        <div className="px-6 py-5 max-w-5xl">
          <h1 className="text-lg font-bold text-gray-800 mb-5">신청 #{applId} · 심사 상세</h1>

          {msg && <div className="mb-4 px-4 py-2 bg-green-50 border border-green-300 text-green-700 text-sm rounded">{msg}</div>}
          {err && <div className="mb-4 px-4 py-2 bg-red-50 border border-red-300 text-red-700 text-sm rounded">{err}</div>}

          {loading ? (
            <p className="py-10 text-center text-sm text-gray-400">불러오는 중...</p>
          ) : (
            <div className="space-y-6">

              {/* ── 1단계: 가심사 ─────────────────────────────── */}
              <Section title="1단계 · 가심사 (Prescreening)">
                {psDone ? (
                  <div className="grid grid-cols-4 gap-4 text-[13px] mb-3">
                    <KV k="결과" v={
                      <span className={`font-bold ${prescreening.prescResultCd === 'PASS' ? 'text-green-600' : 'text-red-500'}`}>
                        {prescreening.prescResultCd}
                      </span>
                    } />
                    <KV k="예상 한도" v={prescreening.estimatedLimitAmt != null ? `${(prescreening.estimatedLimitAmt / 10000).toLocaleString('ko-KR')}만원` : '-'} />
                    <KV k="예상 금리" v={prescreening.estimatedRateBps != null ? `${(prescreening.estimatedRateBps / 100).toFixed(2)}%` : '-'} />
                    <KV k="거절 사유" v={prescreening.rejectReasonCd ?? '-'} />
                  </div>
                ) : (
                  <p className="text-sm text-gray-400 mb-3">가심사 미실행</p>
                )}
                {!psDone && canRunReview && appl?.applStatusCd === 'SUBMITTED' && (
                  <Btn label="가심사 실행" disabled={busy}
                    onClick={() => act(() => loanApplicationApi.runPrescreening(numApplId), '가심사가 완료되었습니다.')} />
                )}
                {!psDone && canRunReview && appl && appl.applStatusCd !== 'SUBMITTED' && (
                  <p className="text-[12px] text-gray-400">
                    신청 상태({appl.applStatusCd})가 가심사 단계가 아닙니다. 가심사는 접수(SUBMITTED) 상태에서만 실행할 수 있습니다.
                  </p>
                )}
                {!psDone && !canRunReview && (
                  <p className="text-[12px] text-gray-400">가심사 실행 권한이 없습니다 (심사역·운영).</p>
                )}
                {psDone && !psPass && (
                  <p className="text-[12px] text-red-600 mt-1">가심사 거절 — 이후 단계를 진행할 수 없습니다.</p>
                )}
              </Section>

              {/* ── 2단계: 신용평가 ───────────────────────────── */}
              <Section title="2단계 · 신용평가 (Credit Evaluation)">
                {ceDone ? (
                  <div className="grid grid-cols-4 gap-4 text-[13px] mb-3">
                    <KV k="결정" v={
                      <span className={`font-bold ${creditEval.cevalDecisionCd === 'APPROVE' ? 'text-green-600' : 'text-red-500'}`}>
                        {creditEval.cevalDecisionCd}
                      </span>
                    } />
                    <KV k="신용점수" v={creditEval.cevalScore != null ? `${creditEval.cevalScore}점` : '-'} />
                    <KV k="신용등급" v={creditEval.cevalGrade ?? '-'} />
                    <KV k="PD" v={creditEval.pdBps != null ? `${(creditEval.pdBps / 100).toFixed(2)}%` : '-'} />
                  </div>
                ) : (
                  <p className="text-sm text-gray-400 mb-3">신용평가 미실행</p>
                )}
                {!ceDone && psPass && canRunReview && (
                  <div className="flex flex-wrap gap-3 items-end">
                    <label className="text-[12px] text-gray-600">
                      CB 엔진
                      <select value={cevalEngine} onChange={e => setCevalEngine(e.target.value)}
                        className="ml-2 border border-gray-300 rounded px-2 py-1 text-[12px]">
                        <option value="KCB">KCB</option>
                        <option value="NICE">NICE</option>
                        <option value="INTERNAL">내부</option>
                      </select>
                    </label>
                    <label className="text-[12px] text-gray-600">
                      결정
                      <select value={cevalDecision} onChange={e => setCevalDecision(e.target.value)}
                        className="ml-2 border border-gray-300 rounded px-2 py-1 text-[12px]">
                        <option value="APPROVE">APPROVE</option>
                        <option value="REVIEW">REVIEW</option>
                        <option value="REJECT">REJECT</option>
                      </select>
                    </label>
                    <Btn label="신용평가 실행" disabled={busy}
                      onClick={() => act(() => loanApplicationApi.runCreditEvaluation(numApplId, { cevalEngine, cevalDecisionCd: cevalDecision }), '신용평가가 완료되었습니다.')} />
                  </div>
                )}
                {!ceDone && psPass && !canRunReview && (
                  <p className="text-[12px] text-gray-400">신용평가 실행 권한이 없습니다 (심사역·운영).</p>
                )}
                {!ceDone && !psPass && (
                  <p className="text-[12px] text-gray-400">가심사 통과 후 실행 가능</p>
                )}
              </Section>

              {/* ── 3단계: DSR ────────────────────────────────── */}
              <Section title="3단계 · DSR 산정">
                {dsrDone ? (
                  <div className="grid grid-cols-3 gap-4 text-[13px] mb-3">
                    <KV k="결과" v={
                      <span className={`font-bold ${dsr.dsrStatusCd === 'PASS' ? 'text-green-600' : 'text-red-500'}`}>
                        {dsr.dsrStatusCd}
                      </span>
                    } />
                    <KV k="DSR 비율" v={dsr.dsrRatioBps != null ? `${(dsr.dsrRatioBps / 100).toFixed(1)}%` : '-'} />
                    <KV k="한도 비율" v={dsr.dsrLimitBps != null ? `${(dsr.dsrLimitBps / 100).toFixed(1)}%` : '-'} />
                  </div>
                ) : (
                  <p className="text-sm text-gray-400 mb-3">DSR 미산정</p>
                )}
                {!dsrDone && ceDone && canRunReview && (
                  <div className="flex flex-wrap gap-3 items-end">
                    <label className="text-[12px] text-gray-600">
                      연 소득(원) *
                      <input type="number" value={annualIncome} onChange={e => setAnnualIncome(e.target.value)}
                        placeholder="예: 50000000"
                        className="ml-2 border border-gray-300 rounded px-2 py-1 text-[12px] w-36" />
                    </label>
                    <Btn label="DSR 실행" disabled={busy || !annualIncome}
                      onClick={() => act(() => loanApplicationApi.runDsr(numApplId, { annualIncomeAmt: parseInt(annualIncome) }), 'DSR 산정이 완료되었습니다.')} />
                  </div>
                )}
                {!dsrDone && ceDone && !canRunReview && (
                  <p className="text-[12px] text-gray-400">DSR 실행 권한이 없습니다 (심사역·운영).</p>
                )}
                {!dsrDone && !ceDone && (
                  <p className="text-[12px] text-gray-400">신용평가 완료 후 실행 가능</p>
                )}
              </Section>

              {/* ── 4단계: 본심사 ─────────────────────────────── */}
              <Section title="4단계 · 본심사 (Final Review)">
                {review ? (
                  <div className="grid grid-cols-4 gap-4 text-[13px] mb-4">
                    <KV k="심사 ID" v={review.revId} />
                    <KV k="심사 유형" v={review.revTypeCd === 'AUTO' ? '자동' : '수동'} />
                    <KV k="심사 상태" v={
                      <span className={`text-[11px] px-2 py-0.5 rounded border ${REV_STATUS[review.revStatusCd]?.cls ?? ''}`}>
                        {REV_STATUS[review.revStatusCd]?.text ?? review.revStatusCd}
                      </span>
                    } />
                    <KV k="권고결정" v={review.revDecisionCd ?? '-'} />
                    <KV k="승인금액" v={review.approvedAmount != null ? `${(review.approvedAmount / 10000).toLocaleString('ko-KR')}만원` : '-'} />
                    <KV k="승인금리" v={review.approvedRateBps != null ? `${(review.approvedRateBps / 100).toFixed(2)}%` : '-'} />
                    <KV k="거절사유" v={review.rejectReasonCd ?? '-'} />
                    <KV k="편향 심각도" v={review.biasSeverityCd ?? '-'} />
                    {review.revAiTrackCd && (
                      <KV k="AI 트랙" v={
                        <span className={`text-[11px] px-2 py-0.5 rounded border font-semibold ${
                          review.revAiTrackCd === 'TRACK_1' ? 'bg-green-100 text-green-700 border-green-300' :
                          review.revAiTrackCd === 'TRACK_2' ? 'bg-yellow-100 text-yellow-700 border-yellow-300' :
                          'bg-red-100 text-red-700 border-red-300'}`}>
                          {review.revAiTrackCd}
                        </span>
                      } />
                    )}
                    {review.revAiPd != null && (
                      <KV k="AI PD" v={`${(review.revAiPd * 100).toFixed(2)}%`} />
                    )}
                    {review.revAiRationale && (
                      <div className="col-span-4">
                        <p className="text-[11px] text-gray-400 mb-0.5">AI 근거</p>
                        <p className="text-[12px] text-gray-700 whitespace-pre-wrap">{review.revAiRationale}</p>
                      </div>
                    )}
                  </div>
                ) : (
                  <p className="text-sm text-gray-400 mb-3">본심사 미실행</p>
                )}

                {/* 심사 실행 */}
                {(!review || status === 'EXPIRED') && (
                  <div className="flex flex-wrap gap-3 items-end mt-2">
                    <label className="text-[12px] text-gray-600">
                      유형
                      <select value={revType} onChange={e => setRevType(e.target.value)}
                        className="ml-2 border border-gray-300 rounded px-2 py-1 text-[12px]">
                        <option value="MANUAL">수동</option>
                        <option value="AUTO">자동</option>
                      </select>
                    </label>
                    <label className="text-[12px] text-gray-600">
                      결정 *
                      <select value={revDecision} onChange={e => setRevDecision(e.target.value)}
                        className="ml-2 border border-gray-300 rounded px-2 py-1 text-[12px]">
                        <option value="APPROVED">승인</option>
                        <option value="REJECTED">거절</option>
                      </select>
                    </label>
                    <Btn label="본심사 시작" disabled={busy || !dsrDone}
                      onClick={() => act(() => adminReviewApi.run(numApplId, { revTypeCd: revType, revDecisionCd: revDecision }), '본심사가 시작되었습니다.')} />
                    <Btn label="자동 결정" disabled={busy || !dsrDone} variant="outline"
                      onClick={() => act(() => adminReviewApi.autoDecide(numApplId), '자동 결정이 완료되었습니다.')} />
                    {!dsrDone && <span className="text-[11px] text-gray-400">DSR 완료 후 본심사 가능</span>}
                  </div>
                )}

                {/* 확정 (PENDING_APPROVAL) */}
                {status === 'PENDING_APPROVAL' && (
                  <div className="flex flex-wrap gap-3 items-end mt-2 pt-3 border-t border-gray-100">
                    <label className="text-[12px] text-gray-600">
                      비고
                      <input type="text" value={confirmRemark} onChange={e => setConfirmRemark(e.target.value)}
                        placeholder="(선택)"
                        className="ml-2 border border-gray-300 rounded px-2 py-1 text-[12px] w-48" />
                    </label>
                    <Btn label="심사 확정" disabled={busy}
                      onClick={() => act(() => adminReviewApi.confirm(numApplId, { confirmRemark }), '심사가 확정되었습니다.')} />
                  </div>
                )}

                {/* 편향 인지 (BIAS_REVIEWING) */}
                {status === 'BIAS_REVIEWING' && (
                  <div className="mt-2 pt-3 border-t border-gray-100">
                    <div className="mb-3 text-[13px] text-red-700 bg-red-50 border border-red-200 rounded px-3 py-2">
                      AI 편향 감지: <strong>{review.biasSeverityCd}</strong>
                      {review.biasSeverityCd === 'BLOCKED' && ' — 오버라이드 필요'}
                    </div>
                    {review.biasSeverityCd === 'BLOCKED' ? (
                      <div className="flex flex-wrap gap-3 items-end">
                        <label className="text-[12px] text-gray-600">
                          오버라이드 사유
                          <input type="text" value={overrideReason} onChange={e => setOverrideReason(e.target.value)}
                            className="ml-2 border border-gray-300 rounded px-2 py-1 text-[12px] w-64" />
                        </label>
                        <Btn label="편향 오버라이드" disabled={busy || !overrideReason}
                          onClick={() => act(() => adminReviewApi.biasOverride(revId, { overrideReason }), '오버라이드가 완료되었습니다.')} />
                      </div>
                    ) : (
                      <div className="flex flex-wrap gap-3 items-end">
                        <label className="text-[12px] text-gray-600">
                          인지 비고
                          <input type="text" value={ackRemark} onChange={e => setAckRemark(e.target.value)}
                            placeholder="(선택)"
                            className="ml-2 border border-gray-300 rounded px-2 py-1 text-[12px] w-64" />
                        </label>
                        <Btn label="편향 인지 처리" disabled={busy}
                          onClick={() => act(() => adminReviewApi.acknowledgeBias(numApplId, { acknowledgeRemark: ackRemark }), '편향 인지가 처리되었습니다.')} />
                      </div>
                    )}
                  </div>
                )}

                {/* 승인자 승인 (PENDING_APPROVER) */}
                {status === 'PENDING_APPROVER' && (
                  <div className="mt-2 pt-3 border-t border-gray-100">
                    {advisoryReports.some(r => r.severityCd === 'CRITICAL' && r.advrStatusCd !== 'ACKED' && r.advrStatusCd !== 'RESOLVED') && (
                      <div className="mb-3 px-3 py-2 bg-red-100 border border-red-400 text-red-800 text-[12px] font-semibold rounded">
                        ⚠ CRITICAL 미해소 Advisory 리포트가 있습니다 — 승인 전 검토 필요
                      </div>
                    )}
                    <p className="text-[12px] text-gray-500 mb-2">4-eye 원칙: 로그인한 직원(심사자와 다른 사람)이 승인해야 합니다.</p>
                    <div className="flex flex-wrap gap-3 items-end">
                      <Btn label="승인" disabled={busy}
                        onClick={() => act(() => adminReviewApi.approverApprove(numApplId, { approverDecisionCd: 'APPROVED' }), '승인이 완료되었습니다.')} />
                      <Btn label="반려" disabled={busy} variant="danger"
                        onClick={() => act(() => adminReviewApi.approverApprove(numApplId, { approverDecisionCd: 'REJECTED' }), '반려 처리되었습니다.')} />
                    </div>
                  </div>
                )}

                {/* 결정 정정 (COMPLETED) — 지점장 전용 */}
                {status === 'COMPLETED' && !canRevise && (
                  <div className="mt-2 pt-3 border-t border-gray-100">
                    <p className="text-[12px] text-gray-400">결정 정정 권한이 없습니다 (지점장).</p>
                  </div>
                )}
                {status === 'COMPLETED' && canRevise && (
                  <div className="mt-2 pt-3 border-t border-gray-100">
                    <p className="text-[12px] text-gray-500 mb-2">결정 정정</p>
                    <div className="flex flex-wrap gap-3 items-end">
                      <label className="text-[12px] text-gray-600">
                        결정
                        <select value={reviseDecision} onChange={e => setReviseDecision(e.target.value)}
                          className="ml-2 border border-gray-300 rounded px-2 py-1 text-[12px]">
                          <option value="APPROVED">승인</option>
                          <option value="REJECTED">거절</option>
                        </select>
                      </label>
                      {reviseDecision === 'APPROVED' ? (
                        <>
                          <label className="text-[12px] text-gray-600">
                            승인금액(원)
                            <input type="number" value={reviseAmount} onChange={e => setReviseAmount(e.target.value)}
                              className="ml-2 border border-gray-300 rounded px-2 py-1 text-[12px] w-32" />
                          </label>
                          <label className="text-[12px] text-gray-600">
                            금리(bps)
                            <input type="number" value={reviseRate} onChange={e => setReviseRate(e.target.value)}
                              className="ml-2 border border-gray-300 rounded px-2 py-1 text-[12px] w-24" />
                          </label>
                        </>
                      ) : (
                        <label className="text-[12px] text-gray-600">
                          거절사유
                          <select value={reviseReject} onChange={e => setReviseReject(e.target.value)}
                            className="ml-2 border border-gray-300 rounded px-2 py-1 text-[12px]">
                            {REJECT_REASONS.map(r => <option key={r} value={r}>{r}</option>)}
                          </select>
                        </label>
                      )}
                      <label className="text-[12px] text-gray-600">
                        정정 사유 *
                        <input type="text" value={revisitReason} onChange={e => setRevisitReason(e.target.value)}
                          placeholder="정정 사유 코드"
                          className="ml-2 border border-gray-300 rounded px-2 py-1 text-[12px] w-40" />
                      </label>
                      <Btn label="정정 저장" disabled={busy || !revisitReason}
                        onClick={() => {
                          const body: any = { revDecisionCd: reviseDecision, revisitReasonCd: revisitReason }
                          if (reviseDecision === 'APPROVED') {
                            if (reviseAmount) body.approvedAmount = parseInt(reviseAmount)
                            if (reviseRate)   body.approvedRateBps = parseInt(reviseRate)
                          } else {
                            body.rejectReasonCd = reviseReject
                          }
                          act(() => adminReviewApi.revise(numApplId, body), '결정이 정정되었습니다.')
                        }} />
                    </div>
                  </div>
                )}
              </Section>

              {/* AI 어드바이스 */}
              {advices.length > 0 && (
                <Section title={`AI 어드바이스 (${advices.length}건)`}>
                  <div className="space-y-2">
                    {advices.map((a: any, i: number) => (
                      <div key={i} className="text-[12px] bg-blue-50 border border-blue-200 rounded px-3 py-2">
                        <span className={`inline-block px-1.5 py-0.5 rounded text-[10px] font-bold mr-2 ${
                          a.severityCd === 'HIGH' ? 'bg-red-200 text-red-800' :
                          a.severityCd === 'MEDIUM' ? 'bg-orange-200 text-orange-800' :
                          'bg-gray-200 text-gray-700'}`}>{a.severityCd}</span>
                        <span className="text-blue-900">{a.adviceContent ?? a.content ?? JSON.stringify(a)}</span>
                      </div>
                    ))}
                  </div>
                </Section>
              )}

              {/* Review Advisory 리포트 (advisory-service) */}
              {advisoryReports.length > 0 && (
                <AdvisorySection reports={advisoryReports} onRefresh={load} busy={busy} />
              )}

              {/* 체크 로그 */}
              <Section title="체크 로그">
                {checks.length > 0 && (
                  <table className="w-full text-[12px] mb-4">
                    <thead>
                      <tr className="bg-gray-50 border-b">
                        {['항목', '결과', '비고', '기록일시'].map(h => (
                          <th key={h} className="px-3 py-2 text-left font-semibold text-gray-600">{h}</th>
                        ))}
                      </tr>
                    </thead>
                    <tbody className="divide-y divide-gray-100">
                      {checks.map((c: any, i: number) => (
                        <tr key={i}>
                          <td className="px-3 py-2 text-gray-700">{c.checkItemCd}</td>
                          <td className="px-3 py-2">
                            <span className={`px-1.5 py-0.5 rounded text-[10px] font-bold ${c.checkResultCd === 'PASS' ? 'bg-green-100 text-green-700' : c.checkResultCd === 'FAIL' ? 'bg-red-100 text-red-700' : 'bg-yellow-100 text-yellow-700'}`}>
                              {c.checkResultCd}
                            </span>
                          </td>
                          <td className="px-3 py-2 text-gray-500">{c.remark ?? '-'}</td>
                          <td className="px-3 py-2 text-gray-400">{c.checkedAt?.slice(0, 16).replace('T', ' ') ?? '-'}</td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                )}
                {revId && (
                  <div className="flex gap-3 items-end">
                    <label className="text-[12px] text-gray-600">
                      체크 항목
                      <select value={checkItemCd} onChange={e => setCheckItemCd(e.target.value)}
                        className="ml-2 border border-gray-300 rounded px-2 py-1 text-[12px]">
                        <option value="DOCUMENT_CHECK">서류확인</option>
                        <option value="IDENTITY_CHECK">본인확인</option>
                        <option value="CROSS_TRANSACTION">교차거래</option>
                        <option value="ETC">기타</option>
                      </select>
                    </label>
                    <label className="text-[12px] text-gray-600">
                      결과
                      <select value={checkResultCd} onChange={e => setCheckResultCd(e.target.value)}
                        className="ml-2 border border-gray-300 rounded px-2 py-1 text-[12px]">
                        <option value="PASS">PASS</option>
                        <option value="FAIL">FAIL</option>
                        <option value="REVIEW">REVIEW</option>
                        <option value="N_A">N/A</option>
                      </select>
                    </label>
                    <Btn label="체크 추가" disabled={busy}
                      onClick={() => act(
                        () => adminReviewApi.addCheck(revId, { checkItemCd, checkResultCd }),
                        '체크가 추가되었습니다.'
                      )} />
                  </div>
                )}
              </Section>

            </div>
          )}
        </div>
      </main>
    </div>
  )
}

function Section({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <div className="bg-white border border-gray-200 rounded-lg p-5">
      <h2 className="text-[13px] font-semibold text-gray-700 mb-4 pb-2 border-b border-gray-100">{title}</h2>
      {children}
    </div>
  )
}

function KV({ k, v }: { k: string; v: React.ReactNode }) {
  return (
    <div>
      <p className="text-[11px] text-gray-400 mb-0.5">{k}</p>
      <p className="text-[13px] font-medium text-gray-800">{v}</p>
    </div>
  )
}

const ACK_CODES: { value: AckResponseCd; label: string }[] = [
  { value: 'MAINTAIN',        label: '결정 유지' },
  { value: 'OVERTURN',        label: '결정 번복' },
  { value: 'ESCALATE',        label: '상위 심사 상신' },
  { value: 'NEEDS_MORE_INFO', label: '추가 정보 필요' },
]

function AdvisorySection({ reports, onRefresh, busy }: {
  reports: any[]; onRefresh: () => Promise<void>; busy: boolean
}) {
  const [ackTarget, setAckTarget]   = useState<number | null>(null)
  const [ackCode, setAckCode]       = useState<AckResponseCd>('MAINTAIN')
  const [ackRemark, setAckRemark]   = useState('')
  const [acting, setActing]         = useState(false)
  const [msg, setMsg]               = useState('')
  const [err, setErr]               = useState('')

  function notify(m: string) { setMsg(m); setTimeout(() => setMsg(''), 3000) }
  function fail(m: string)   { setErr(m); setTimeout(() => setErr(''), 4000) }

  async function handleView(advrId: number) {
    setActing(true)
    try {
      await viewAdvisoryReport(advrId)
      notify(`리포트 #${advrId} 조회 마킹 완료`)
      await onRefresh()
    } catch { fail('조회 마킹 실패') }
    finally { setActing(false) }
  }

  async function handleAck(advrId: number) {
    setActing(true)
    try {
      await ackAdvisoryReport(advrId, { ackResponseCd: ackCode, ackRemark: ackRemark || undefined })
      notify(`리포트 #${advrId} ack 완료`)
      setAckTarget(null); setAckRemark('')
      await onRefresh()
    } catch (e: any) { fail(e?.response?.data?.message ?? 'ack 실패') }
    finally { setActing(false) }
  }

  return (
    <Section title={`Review Advisory 리포트 (${reports.length}건)`}>
      {msg && <div className="mb-3 px-3 py-1.5 bg-green-50 border border-green-300 text-green-700 text-[12px] rounded">{msg}</div>}
      {err && <div className="mb-3 px-3 py-1.5 bg-red-50 border border-red-300 text-red-700 text-[12px] rounded">{err}</div>}
      <div className="space-y-2">
        {reports.map((r: any) => (
          <div key={r.advrId ?? r.advrTitle} className={`text-[12px] border rounded px-3 py-2 ${
            r.severityCd === 'CRITICAL' ? 'bg-red-50 border-red-300' :
            r.severityCd === 'HIGH'     ? 'bg-orange-50 border-orange-300' :
            r.severityCd === 'MEDIUM'   ? 'bg-yellow-50 border-yellow-200' :
            'bg-gray-50 border-gray-200'}`}>
            <div className="flex items-center gap-2 flex-wrap">
              <span className={`text-[10px] px-1.5 py-0.5 rounded font-bold ${
                r.severityCd === 'CRITICAL' ? 'bg-red-200 text-red-800' :
                r.severityCd === 'HIGH'     ? 'bg-orange-200 text-orange-800' :
                r.severityCd === 'MEDIUM'   ? 'bg-yellow-200 text-yellow-800' :
                'bg-gray-200 text-gray-700'}`}>{r.severityCd}</span>
              <span className="font-semibold text-gray-800 flex-1">{r.advrTitle}</span>
              <span className="text-[10px] text-gray-400">{r.advrStatusCd}</span>
              {r.advrId && (
                <>
                  {r.advrStatusCd === 'OPEN' && (
                    <button onClick={() => handleView(r.advrId)} disabled={acting || busy}
                      className="text-[10px] px-2 py-0.5 border border-blue-300 text-blue-600 rounded hover:bg-blue-50 disabled:opacity-50">
                      조회 마킹
                    </button>
                  )}
                  {(r.advrStatusCd === 'VIEWED' || r.advrStatusCd === 'OPEN') && ackTarget !== r.advrId && (
                    <button onClick={() => setAckTarget(r.advrId)} disabled={acting || busy}
                      className="text-[10px] px-2 py-0.5 border border-green-300 text-green-700 rounded hover:bg-green-50 disabled:opacity-50">
                      ACK
                    </button>
                  )}
                </>
              )}
            </div>
            {r.advrSummary && <p className="mt-1 text-gray-600">{r.advrSummary}</p>}
            {ackTarget === r.advrId && (
              <div className="mt-2 pt-2 border-t border-gray-200 flex flex-wrap gap-2 items-end">
                <label className="text-[11px] text-gray-600">
                  응답
                  <select value={ackCode} onChange={e => setAckCode(e.target.value as AckResponseCd)}
                    className="ml-1.5 border border-gray-300 rounded px-2 py-0.5 text-[11px]">
                    {ACK_CODES.map(c => <option key={c.value} value={c.value}>{c.label}</option>)}
                  </select>
                </label>
                <label className="text-[11px] text-gray-600 flex-1">
                  비고
                  <input type="text" value={ackRemark} onChange={e => setAckRemark(e.target.value)}
                    placeholder="(선택)"
                    className="ml-1.5 border border-gray-300 rounded px-2 py-0.5 text-[11px] w-full max-w-xs" />
                </label>
                <button onClick={() => handleAck(r.advrId)} disabled={acting}
                  className="text-[11px] px-3 py-0.5 bg-green-600 text-white rounded hover:opacity-90 disabled:opacity-50">
                  확인
                </button>
                <button onClick={() => setAckTarget(null)}
                  className="text-[11px] px-3 py-0.5 border border-gray-300 rounded hover:bg-gray-50">
                  취소
                </button>
              </div>
            )}
          </div>
        ))}
      </div>
    </Section>
  )
}

function Btn({ label, onClick, disabled, variant = 'primary' }: {
  label: string; onClick: () => void; disabled?: boolean; variant?: 'primary' | 'outline' | 'danger'
}) {
  const cls = variant === 'danger'
    ? 'bg-red-600 text-white hover:opacity-90'
    : variant === 'outline'
    ? 'border border-gray-300 text-gray-700 hover:bg-gray-50'
    : 'bg-kb-admin text-white hover:opacity-90'
  return (
    <button onClick={onClick} disabled={disabled}
      className={`px-4 py-1.5 text-[12px] rounded transition-all disabled:opacity-50 ${cls}`}>
      {label}
    </button>
  )
}
