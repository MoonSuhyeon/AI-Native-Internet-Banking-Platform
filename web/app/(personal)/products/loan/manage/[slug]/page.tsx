'use client'
import { KB_PRIMARY } from '@/lib/theme'

import { useState, useEffect } from 'react'
import { useParams } from 'next/navigation'
import LoanSidebar from '@/components/inquiry/LoanSidebar'
import AutoBreadcrumb from '@/components/layout/AutoBreadcrumb'
import {
  loanContractApi, repaymentApi, rateApi, closureApi, loanMiscApi, guaranteeInsuranceApi,
  getCustomerId, bpsToRate, formatAmount,
  type LoanContract, type RepaymentSchedule, type Notification,
} from '@/lib/loan-api'

// ─── 계약 선택 훅 ─────────────────────────────────────────────

function useContracts() {
  const [contracts, setContracts] = useState<LoanContract[]>([])
  const [selectedId, setSelectedId] = useState<number | null>(null)

  useEffect(() => {
    const cid = getCustomerId()
    if (!cid) return
    loanContractApi.list({ customerId: cid, size: 20 })
      .then(({ data: res }) => {
        const items: LoanContract[] = res.data?.items ?? []
        setContracts(items)
        if (items.length > 0) setSelectedId(items[0].cntrId)
      })
      .catch(() => {})
  }, [])

  return { contracts, selectedId, setSelectedId }
}

// ─── 공용 UI ──────────────────────────────────────────────────

function ContractSelect({ contracts, selectedId, onChange }: {
  contracts: LoanContract[]; selectedId: number | null; onChange: (id: number) => void
}) {
  return (
    <div className="flex items-center px-5 py-3 gap-6">
      <span className="w-32 text-[13px] font-medium text-kb-text flex-shrink-0">대출계좌번호</span>
      {contracts.length === 0 ? (
        <span className="text-[13px] text-kb-text-muted">해당계좌가 없습니다.</span>
      ) : (
        <select value={selectedId ?? ''} onChange={e => onChange(parseInt(e.target.value, 10))}
          className="border border-kb-primary-border rounded-lg px-3 py-1.5 text-[13px] focus:outline-none min-w-[220px]">
          {contracts.map(c => (
            <option key={c.cntrId} value={c.cntrId}>
              {c.cntrNo} ({formatAmount(c.contractedAmount)})
            </option>
          ))}
        </select>
      )}
    </div>
  )
}

function StepIndicator() {
  return (
    <div className="flex items-center gap-1 mb-5">
      <span className="px-4 py-1.5 text-[13px] font-bold bg-[#3D3D3D] text-white rounded-lg">1. 계좌선택</span>
      {[2, 3, 4, 5].map(n => (
        <span key={n} className="px-4 py-1.5 text-[13px] text-kb-text-body border border-kb-primary-border rounded-lg">{n}</span>
      ))}
    </div>
  )
}

// ─── 적용금리조회 ─────────────────────────────────────────────

function RateInfo({ contracts, selectedId, setSelectedId }: {
  contracts: LoanContract[]; selectedId: number | null; setSelectedId: (id: number) => void
}) {
  const [, setAccruals] = useState<unknown[]>([])
  const [rateChanges, setRateChanges] = useState<any[]>([])
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState('')

  const QUICK = ['당일', '3일', '1주일', '1개월', '3개월']
  const [period, setPeriod] = useState<string | null>(null)

  async function handleSearch() {
    if (!selectedId) return
    setLoading(true); setError('')
    try {
      const [acc, rc] = await Promise.all([
        rateApi.getInterestAccruals(selectedId),
        rateApi.getRateChanges(selectedId),
      ])
      setAccruals(acc.data?.data?.items ?? [])
      setRateChanges(rc.data?.data?.items ?? [])
    } catch {
      setError('금리 정보를 불러오지 못했습니다.')
    } finally {
      setLoading(false)
    }
  }

  const contract = contracts.find(c => c.cntrId === selectedId)

  return (
    <div>
      <div className="border border-kb-primary-border rounded-xl bg-[#FAFAFA] px-5 py-4 mb-6 text-[13px] text-kb-text-body space-y-1.5">
        <p>· 조회기간을 선택하지 않을 경우에는 현재 적용금리가 조회됩니다.</p>
        <p>· 대출 잔액이 있는 가계대출에 한하여 조회 가능합니다.</p>
      </div>
      <div className="border border-kb-primary-border rounded-xl overflow-hidden divide-y divide-kb-border">
        <ContractSelect contracts={contracts} selectedId={selectedId} onChange={setSelectedId} />
        <div className="flex items-center px-5 py-3 gap-6">
          <span className="w-32 text-[13px] font-medium text-kb-text flex-shrink-0">조회기간</span>
          <div className="flex gap-1">
            {QUICK.map(q => (
              <button key={q} onClick={() => setPeriod(q)}
                className={`px-3 py-1 text-[12px] border transition-colors ${period === q ? 'bg-kb-primary border-kb-primary-border font-bold text-kb-text' : 'border-kb-primary-border text-kb-text-body hover:bg-kb-primary-bg'}`}>
                {q}
              </button>
            ))}
          </div>
        </div>
      </div>
      <div className="flex justify-center mt-5">
        <button onClick={handleSearch} disabled={!selectedId || loading}
          className="px-14 py-2.5 text-[14px] font-bold text-kb-text bg-kb-primary text-white hover:opacity-85 disabled:opacity-50 transition-colors">
          {loading ? '조회 중...' : '조회'}
        </button>
      </div>
      {error && <p className="text-[13px] text-kb-red mt-4 text-center">{error}</p>}
      {contract && (
        <div className="mt-6 border border-kb-primary-border rounded-xl overflow-hidden">
          <div className="bg-kb-primary-bg px-5 py-3 border-b border-kb-primary-border">
            <p className="text-[13px] font-bold text-kb-text">현재 적용금리</p>
          </div>
          <div className="px-5 py-4 space-y-2 text-[13px]">
            <div className="flex gap-4">
              <span className="text-kb-text-muted w-32">계약번호</span>
              <span className="font-medium">{contract.cntrNo}</span>
            </div>
            <div className="flex gap-4">
              <span className="text-kb-text-muted w-32">승인금리</span>
              <span className="font-bold text-kb-primary">연 {bpsToRate(contract.totalRateBps)}%</span>
            </div>
            <div className="flex gap-4">
              <span className="text-kb-text-muted w-32">승인금액</span>
              <span>{formatAmount(contract.contractedAmount)}</span>
            </div>
            <div className="flex gap-4">
              <span className="text-kb-text-muted w-32">만기일</span>
              <span>{contract.cntrEndDate ?? '-'}</span>
            </div>
          </div>
        </div>
      )}
      {rateChanges.length > 0 && (
        <div className="mt-4 border border-kb-primary-border rounded-xl overflow-hidden">
          <div className="bg-kb-primary-bg px-5 py-3 border-b border-kb-primary-border">
            <p className="text-[13px] font-bold text-kb-text">금리변동 내역</p>
          </div>
          <table className="w-full text-[13px]">
            <thead><tr className="bg-[#FAFAFA]">
              <th className="px-4 py-2 text-left font-medium border-b border-kb-primary-border">변경일</th>
              <th className="px-4 py-2 text-right font-medium border-b border-kb-primary-border">변경 전</th>
              <th className="px-4 py-2 text-right font-medium border-b border-kb-primary-border">변경 후</th>
            </tr></thead>
            <tbody className="divide-y divide-kb-border">
              {rateChanges.map((r: any, i: number) => (
                <tr key={i}>
                  <td className="px-4 py-2">{r.changedAt?.slice(0, 10) ?? '-'}</td>
                  <td className="px-4 py-2 text-right">{r.prevRateBps ? `${bpsToRate(r.prevRateBps)}%` : '-'}</td>
                  <td className="px-4 py-2 text-right font-medium">{r.newRateBps ? `${bpsToRate(r.newRateBps)}%` : '-'}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  )
}

// ─── 이자/월부금 납입 ─────────────────────────────────────────

function InterestPaymentForm({ contracts, selectedId, setSelectedId }: {
  contracts: LoanContract[]; selectedId: number | null; setSelectedId: (id: number) => void
}) {
  const [schedules, setSchedules] = useState<RepaymentSchedule[]>([])
  const [loading, setLoading] = useState(false)
  const [submitting, setSubmitting] = useState(false)
  const [done, setDone] = useState(false)
  const [error, setError] = useState('')
  // valueDate 는 yyyyMMdd 여야 한다(@Pattern(regexp="\d{8}")).
  const today = new Date().toISOString().slice(0, 10).replace(/-/g, '')

  async function handleSearch() {
    if (!selectedId) return
    setLoading(true); setError('')
    try {
      const { data: res } = await loanContractApi.getRepaymentSchedules(selectedId)
      setSchedules(res.data?.items ?? [])
    } catch {
      setError('상환 스케줄을 불러오지 못했습니다.')
    } finally {
      setLoading(false)
    }
  }

  async function handlePay(schedule: RepaymentSchedule) {
    if (!selectedId) return
    setSubmitting(true); setError('')
    try {
      // 금액·분배는 서버가 스케줄 row 기준으로 산출한다 — 회차 번호만 보낸다.
      await repaymentApi.pay(selectedId, { installmentNo: schedule.installmentNo, valueDate: today })
      setDone(true)
    } catch (err: any) {
      setError(err.response?.data?.message ?? '납입 처리 중 오류가 발생했습니다.')
    } finally {
      setSubmitting(false)
    }
  }

  if (done) return (
    <div className="py-12 text-center">
      <p className="text-[16px] font-bold text-green-600 mb-2">납입 처리 완료</p>
      <button onClick={() => setDone(false)} className="text-[13px] text-kb-primary hover:underline">다시 조회</button>
    </div>
  )

  return (
    <div>
      <StepIndicator />
      <div className="border border-kb-primary-border rounded-xl overflow-hidden divide-y divide-kb-border">
        <ContractSelect contracts={contracts} selectedId={selectedId} onChange={setSelectedId} />
      </div>
      <div className="flex justify-center mt-5">
        <button onClick={handleSearch} disabled={!selectedId || loading}
          className="px-14 py-2.5 text-[14px] font-bold text-kb-text bg-kb-primary text-white hover:opacity-85 disabled:opacity-50 transition-colors">
          {loading ? '조회 중...' : '조회'}
        </button>
      </div>
      {error && <p className="text-[13px] text-kb-red mt-4 text-center">{error}</p>}
      {schedules.length > 0 && (
        <div className="mt-6 border border-kb-primary-border rounded-xl overflow-hidden">
          <table className="w-full text-[13px]">
            <thead><tr className="bg-kb-primary-bg">
              <th className="px-4 py-3 text-center font-semibold border-b border-kb-primary-border">회차</th>
              <th className="px-4 py-3 text-center font-semibold border-b border-kb-primary-border">납입예정일</th>
              <th className="px-4 py-3 text-right font-semibold border-b border-kb-primary-border">원금</th>
              <th className="px-4 py-3 text-right font-semibold border-b border-kb-primary-border">이자</th>
              <th className="px-4 py-3 text-right font-semibold border-b border-kb-primary-border">납입액</th>
              <th className="px-4 py-3 text-center font-semibold border-b border-kb-primary-border">상태</th>
              <th className="px-4 py-3 text-center font-semibold border-b border-kb-primary-border">처리</th>
            </tr></thead>
            <tbody className="divide-y divide-kb-border">
              {schedules.map(s => (
                <tr key={s.rschId} className="hover:bg-kb-primary-bg">
                  <td className="px-4 py-3 text-center">{s.installmentNo}</td>
                  <td className="px-4 py-3 text-center">{formatDueDate(s.dueDate)}</td>
                  <td className="px-4 py-3 text-right">{s.scheduledPrincipal.toLocaleString('ko-KR')}원</td>
                  <td className="px-4 py-3 text-right">{s.scheduledInterest.toLocaleString('ko-KR')}원</td>
                  <td className="px-4 py-3 text-right font-bold">{s.scheduledTotal.toLocaleString('ko-KR')}원</td>
                  <td className="px-4 py-3 text-center">
                    <span className={`text-[11px] font-bold px-2 py-0.5 ${SCHEDULE_STATUS[s.rschStatusCd]?.cls ?? 'bg-gray-100 text-gray-600'}`}>
                      {SCHEDULE_STATUS[s.rschStatusCd]?.label ?? s.rschStatusCd}
                    </span>
                  </td>
                  <td className="px-4 py-3 text-center">
                    {PAYABLE_STATUSES.includes(s.rschStatusCd) && (
                      <button onClick={() => handlePay(s)} disabled={submitting}
                        className="px-3 py-1 text-[11px] bg-kb-primary text-kb-text font-bold hover:brightness-95 disabled:opacity-50">
                        납입
                      </button>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  )
}

// ─── 대출금 상환 ──────────────────────────────────────────────

function RepayForm({ contracts, selectedId, setSelectedId }: {
  contracts: LoanContract[]; selectedId: number | null; setSelectedId: (id: number) => void
}) {
  const [repayType, setRepayType] = useState<'partial' | 'full'>('partial')
  const [amount, setAmount] = useState('')
  const [submitting, setSubmitting] = useState(false)
  const [done, setDone] = useState(false)
  const [error, setError] = useState('')

  const AMT_BTNS = ['100만', '50만', '10만', '5만', '1만', '정정']
  function handleAmtBtn(btn: string) {
    if (btn === '정정') { setAmount(''); return }
    const map: Record<string, number> = { '100만': 1000000, '50만': 500000, '10만': 100000, '5만': 50000, '1만': 10000 }
    const cur = Number(amount.replace(/,/g, '')) || 0
    setAmount((cur + (map[btn] ?? 0)).toLocaleString())
  }

  async function handleSubmit() {
    if (!selectedId) return
    setSubmitting(true); setError('')
    const amt = parseInt(amount.replace(/,/g, ''), 10)
    try {
      if (repayType === 'partial') {
        await repaymentApi.partialPrepay(selectedId, { prepaymentAmt: amt })
      } else {
        await repaymentApi.fullPrepay(selectedId, {})
      }
      setDone(true)
    } catch (err: any) {
      setError(err.response?.data?.message ?? '상환 처리 중 오류가 발생했습니다.')
    } finally {
      setSubmitting(false)
    }
  }

  if (done) return (
    <div className="py-12 text-center">
      <p className="text-[16px] font-bold text-green-600 mb-2">상환 처리 완료</p>
      <button onClick={() => { setDone(false); setAmount('') }} className="text-[13px] text-kb-primary hover:underline">다시 처리</button>
    </div>
  )

  return (
    <div>
      <StepIndicator />
      <div className="border border-kb-primary-border rounded-xl overflow-hidden divide-y divide-kb-border">
        <div className="flex items-center px-5 py-3 gap-6">
          <span className="w-32 text-[13px] font-medium text-kb-text flex-shrink-0">상환구분</span>
          <div className="flex items-center gap-6">
            {(['partial', 'full'] as const).map(t => (
              <label key={t} className="flex items-center gap-1.5 cursor-pointer">
                <input type="radio" name="repayType" checked={repayType === t} onChange={() => setRepayType(t)} style={{ accentColor: KB_PRIMARY }} />
                <span className="text-[13px] text-kb-text-body">{t === 'partial' ? '일부상환' : '완제'}</span>
              </label>
            ))}
          </div>
        </div>
        <ContractSelect contracts={contracts} selectedId={selectedId} onChange={setSelectedId} />
        {repayType === 'partial' && (
          <div className="flex items-start px-5 py-3 gap-6">
            <span className="w-32 text-[13px] font-medium text-kb-text flex-shrink-0 pt-2">상환금액</span>
            <div className="space-y-2">
              <div className="flex items-center gap-2">
                <input type="text" value={amount} onChange={e => setAmount(e.target.value)} placeholder=""
                  className="border border-kb-primary-border rounded-lg px-3 py-1.5 text-[13px] focus:outline-none w-[200px] text-right" />
                <span className="text-[13px]">원</span>
              </div>
              <div className="flex gap-1">
                {AMT_BTNS.map(btn => (
                  <button key={btn} onMouseDown={e => e.preventDefault()} onClick={() => handleAmtBtn(btn)}
                    className="px-3 py-1 text-[12px] border border-kb-primary-border rounded-lg text-kb-text-body hover:bg-kb-primary-bg">
                    {btn}
                  </button>
                ))}
              </div>
            </div>
          </div>
        )}
      </div>
      {error && <p className="text-[13px] text-kb-red mt-4 text-center">{error}</p>}
      <div className="flex justify-center mt-5">
        <button onClick={handleSubmit} disabled={submitting || !selectedId || (repayType === 'partial' && !amount)}
          className="px-14 py-2.5 text-[14px] font-bold text-kb-text bg-kb-primary text-white hover:opacity-85 disabled:opacity-50 transition-colors">
          {submitting ? '처리 중...' : '상환'}
        </button>
      </div>
    </div>
  )
}

// ─── 금리인하요구 ─────────────────────────────────────────────

function RateCutForm({ contracts, selectedId, setSelectedId }: {
  contracts: LoanContract[]; selectedId: number | null; setSelectedId: (id: number) => void
}) {
  const [reason, setReason] = useState('')
  const [submitting, setSubmitting] = useState(false)
  const [done, setDone] = useState(false)
  const [error, setError] = useState('')

  async function handleSubmit() {
    if (!selectedId) return
    setSubmitting(true); setError('')
    try {
      const contract = contracts.find(c => c.cntrId === selectedId)
      const targetBps = contract ? Math.max(0, contract.totalRateBps - 50) : 0
      await rateApi.requestRateChange(selectedId, { requestedRateBps: targetBps, reasonCd: reason || 'CREDIT_IMPROVED' })
      setDone(true)
    } catch (err: any) {
      setError(err.response?.data?.message ?? '요청 처리 중 오류가 발생했습니다.')
    } finally {
      setSubmitting(false)
    }
  }

  if (done) return (
    <div className="py-12 text-center">
      <p className="text-[16px] font-bold text-green-600 mb-2">금리인하요구 접수 완료</p>
      <p className="text-[13px] text-kb-text-muted">영업일 기준 3일 이내 처리 결과를 통보해 드립니다.</p>
    </div>
  )

  return (
    <div className="max-w-lg">
      <div className="bg-[#F5F5F5] border border-kb-primary-border rounded-xl p-4 mb-5 text-[13px] text-kb-text-body">
        <p>신용 상태가 개선된 경우(신용점수 상승, 소득 증가 등) 금리 인하를 요구할 수 있습니다.</p>
      </div>
      <div className="border border-kb-primary-border rounded-xl overflow-hidden divide-y divide-kb-border">
        <ContractSelect contracts={contracts} selectedId={selectedId} onChange={setSelectedId} />
        <div className="flex items-center px-5 py-3 gap-6">
          <span className="w-32 text-[13px] font-medium text-kb-text flex-shrink-0">요구 사유</span>
          <select value={reason} onChange={e => setReason(e.target.value)}
            className="flex-1 border border-kb-primary-border rounded-lg px-3 py-2 text-[13px] focus:outline-none">
            <option value="">선택하세요</option>
            <option value="CREDIT_IMPROVED">신용점수 상승</option>
            <option value="INCOME_INCREASED">소득 증가</option>
            <option value="ASSET_INCREASED">자산 증가</option>
            <option value="JOB_CHANGED">직장 변경(상향)</option>
          </select>
        </div>
      </div>
      {error && <p className="text-[13px] text-kb-red mt-4">{error}</p>}
      <div className="flex justify-center mt-5">
        <button onClick={handleSubmit} disabled={submitting || !selectedId || !reason}
          className={`px-12 py-2.5 text-[14px] font-bold text-white disabled:opacity-50`}
          style={{ backgroundColor: KB_PRIMARY }}>
          {submitting ? '처리 중...' : '요구 신청'}
        </button>
      </div>
    </div>
  )
}

// ─── 기한연장 ─────────────────────────────────────────────────

function ExtendForm({ contracts, selectedId, setSelectedId }: {
  contracts: LoanContract[]; selectedId: number | null; setSelectedId: (id: number) => void
}) {
  const [months, setMonths] = useState('12')
  const [submitting, setSubmitting] = useState(false)
  const [done, setDone] = useState(false)
  const [error, setError] = useState('')

  const contract = contracts.find(c => c.cntrId === selectedId)

  async function handleSubmit() {
    if (!selectedId || !contract) return
    setSubmitting(true); setError('')
    const raw = contract.cntrEndDate ?? ''
    const newDt = raw.length === 8
      ? new Date(`${raw.slice(0,4)}-${raw.slice(4,6)}-${raw.slice(6,8)}`)
      : new Date(raw)
    newDt.setMonth(newDt.getMonth() + parseInt(months, 10))
    try {
      await closureApi.extendMaturity(selectedId, { newMaturityDt: newDt.toISOString().slice(0, 10) })
      setDone(true)
    } catch (err: any) {
      setError(err.response?.data?.message ?? '기한연장 처리 중 오류가 발생했습니다.')
    } finally {
      setSubmitting(false)
    }
  }

  if (done) return (
    <div className="py-12 text-center">
      <p className="text-[16px] font-bold text-green-600 mb-2">기한연장 처리 완료</p>
    </div>
  )

  return (
    <div className="max-w-lg">
      <div className="border border-kb-primary-border rounded-xl overflow-hidden divide-y divide-kb-border">
        <ContractSelect contracts={contracts} selectedId={selectedId} onChange={setSelectedId} />
        {contract && (
          <div className="flex items-center px-5 py-3 gap-6">
            <span className="w-32 text-[13px] font-medium text-kb-text flex-shrink-0">현재 만기일</span>
            <span className="text-[13px]">{contract.cntrEndDate ?? '-'}</span>
          </div>
        )}
        <div className="flex items-center px-5 py-3 gap-6">
          <span className="w-32 text-[13px] font-medium text-kb-text flex-shrink-0">연장기간</span>
          <select value={months} onChange={e => setMonths(e.target.value)}
            className="border border-kb-primary-border rounded-lg px-3 py-2 text-[13px] focus:outline-none">
            {[3, 6, 12].map(m => <option key={m} value={m}>{m}개월</option>)}
          </select>
        </div>
      </div>
      {error && <p className="text-[13px] text-kb-red mt-4">{error}</p>}
      <div className="flex justify-center mt-5">
        <button onClick={handleSubmit} disabled={submitting || !selectedId}
          className={`px-12 py-2.5 text-[14px] font-bold text-white disabled:opacity-50`}
          style={{ backgroundColor: KB_PRIMARY }}>
          {submitting ? '처리 중...' : '기한연장 신청'}
        </button>
      </div>
    </div>
  )
}

// ─── 통지서비스 ───────────────────────────────────────────────

function NotifyForm() {
  const [notifs, setNotifs] = useState<Notification[]>([])
  const [loading, setLoading] = useState(true)

  useEffect(() => {
    const cid = getCustomerId()
    if (!cid) { setLoading(false); return }
    loanMiscApi.getNotifications(cid)
      .then(({ data: res }) => setNotifs(res.data?.items ?? []))
      .catch(() => {})
      .finally(() => setLoading(false))
  }, [])

  async function toggleRead(notif: Notification) {
    try {
      await loanMiscApi.updateNotification(notif.notifId, { readYn: notif.readYn === 'Y' ? 'N' : 'Y' })
      setNotifs(prev => prev.map(n => n.notifId === notif.notifId ? { ...n, readYn: n.readYn === 'Y' ? 'N' : 'Y' } : n))
    } catch {}
  }

  if (loading) return <p className="text-[13px] text-kb-text-muted py-8 text-center">불러오는 중...</p>

  return (
    <div>
      {notifs.length === 0 ? (
        <p className="text-[13px] text-kb-text-muted py-8 text-center">수신된 통지가 없습니다.</p>
      ) : (
        <table className="w-full text-[13px] border-t-2 border-kb-primary">
          <thead><tr className="bg-kb-primary-bg">
            <th className="px-4 py-3 text-left font-semibold border-b border-kb-primary-border">제목</th>
            <th className="px-4 py-3 text-center font-semibold border-b border-kb-primary-border">수신일</th>
            <th className="px-4 py-3 text-center font-semibold border-b border-kb-primary-border">읽음</th>
          </tr></thead>
          <tbody className="divide-y divide-kb-border">
            {notifs.map(n => (
              <tr key={n.notifId} className={`hover:bg-kb-primary-bg ${n.readYn === 'N' ? 'font-medium' : ''}`}>
                <td className="px-4 py-3">{n.title}</td>
                <td className="px-4 py-3 text-center text-kb-text-muted">{n.createdAt?.slice(0, 10)}</td>
                <td className="px-4 py-3 text-center">
                  <button onClick={() => toggleRead(n)}
                    className={`px-3 py-1 text-[11px] border ${n.readYn === 'Y' ? 'border-kb-primary-border text-kb-text-muted' : 'bg-kb-primary border-kb-primary-border text-kb-text font-bold'}`}>
                    {n.readYn === 'Y' ? '읽음' : '미읽음'}
                  </button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </div>
  )
}

// ─── 납입방법 변경 ────────────────────────────────────────────

function PaymentMethodForm({ contracts, selectedId, setSelectedId }: {
  contracts: LoanContract[]; selectedId: number | null; setSelectedId: (id: number) => void
}) {
  const [accountNo, setAccountNo] = useState('')
  const [submitting, setSubmitting] = useState(false)
  const [done, setDone] = useState(false)
  const [error, setError] = useState('')

  async function handleSubmit() {
    if (!selectedId || !accountNo) return
    setSubmitting(true); setError('')
    try {
      await loanContractApi.registerRepaymentAccount(selectedId, { accountNo })
      setDone(true)
    } catch (err: any) {
      setError(err.response?.data?.message ?? '변경 처리 중 오류가 발생했습니다.')
    } finally {
      setSubmitting(false)
    }
  }

  if (done) return (
    <div className="py-12 text-center">
      <p className="text-[16px] font-bold text-green-600 mb-2">납입방법 변경 완료</p>
    </div>
  )

  return (
    <div className="max-w-lg">
      <div className="border border-kb-primary-border rounded-xl overflow-hidden divide-y divide-kb-border">
        <ContractSelect contracts={contracts} selectedId={selectedId} onChange={setSelectedId} />
        <div className="flex items-center px-5 py-3 gap-6">
          <span className="w-32 text-[13px] font-medium text-kb-text flex-shrink-0">새 납입 계좌</span>
          <input type="text" value={accountNo} onChange={e => setAccountNo(e.target.value)}
            placeholder="계좌번호 입력" className="flex-1 border border-kb-primary-border rounded-lg px-3 py-2 text-[13px] focus:outline-none" />
        </div>
      </div>
      {error && <p className="text-[13px] text-kb-red mt-4">{error}</p>}
      <div className="flex justify-center mt-5">
        <button onClick={handleSubmit} disabled={submitting || !selectedId || !accountNo}
          className={`px-12 py-2.5 text-[14px] font-bold text-white disabled:opacity-50`}
          style={{ backgroundColor: KB_PRIMARY }}>
          {submitting ? '처리 중...' : '변경'}
        </button>
      </div>
    </div>
  )
}

// ─── 연체정보 조회 ────────────────────────────────────────────

function DelinquencyView({ contracts, selectedId, setSelectedId }: {
  contracts: LoanContract[]; selectedId: number | null; setSelectedId: (id: number) => void
}) {
  const [dlq, setDlq] = useState<any>(null)
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState('')

  async function handleSearch() {
    if (!selectedId) return
    setLoading(true); setError('')
    try {
      const { data: res } = await loanMiscApi.getDelinquency(selectedId)
      setDlq(res.data)
    } catch (err: any) {
      const msg = err.response?.data?.message ?? ''
      if (err.response?.status === 404 || msg.includes('LOAN_100')) {
        setDlq(null)
        setError('현재 활성 연체 내역이 없습니다.')
      } else {
        setError('연체 정보를 불러오지 못했습니다.')
      }
    } finally {
      setLoading(false)
    }
  }

  const DLQ_STATUS: Record<string, string> = {
    ACTIVE: '연체중', RESOLVED: '해소완료', WRITTEN_OFF: '대손처리',
  }
  const DLQ_STAGE: Record<string, string> = {
    EARLY: '초기(1~30일)', MID: '중기(31~90일)', LATE: '장기(91일+)',
  }

  return (
    <div>
      <div className="border border-kb-primary-border rounded-xl bg-[#FAFAFA] px-5 py-4 mb-6 text-[13px] text-kb-text-body space-y-1">
        <p>· 연체가 발생한 경우 연체 현황을 조회할 수 있습니다.</p>
        <p>· 연체 해소를 위해 즉시 납입해 주시기 바랍니다.</p>
      </div>
      <div className="border border-kb-primary-border rounded-xl overflow-hidden divide-y divide-kb-border">
        <ContractSelect contracts={contracts} selectedId={selectedId} onChange={setSelectedId} />
      </div>
      <div className="flex justify-center mt-5">
        <button onClick={handleSearch} disabled={!selectedId || loading}
          className="px-14 py-2.5 text-[14px] font-bold text-kb-text bg-kb-primary text-white hover:opacity-85 disabled:opacity-50 transition-colors">
          {loading ? '조회 중...' : '조회'}
        </button>
      </div>
      {error && <p className="text-[13px] text-kb-red mt-4 text-center">{error}</p>}
      {dlq && (
        <div className="mt-6 border border-kb-primary-border rounded-xl overflow-hidden">
          <div className="bg-red-50 px-5 py-3 border-b border-kb-primary-border">
            <p className="text-[13px] font-bold text-red-700">연체 정보</p>
          </div>
          <div className="divide-y divide-kb-border text-[13px]">
            {[
              ['연체 상태',     DLQ_STATUS[dlq.dlqStatusCd] ?? dlq.dlqStatusCd],
              ['연체 단계',     DLQ_STAGE[dlq.dlqStageCd] ?? dlq.dlqStageCd ?? '-'],
              ['연체 시작일',   dlq.dlqStartDate ?? '-'],
              ['연체 경과일',   dlq.dlqDays != null ? `${dlq.dlqDays}일` : '-'],
              ['연체 원금',     dlq.dlqPrincipalAmt != null ? `${dlq.dlqPrincipalAmt.toLocaleString('ko-KR')}원` : '-'],
              ['연체 이자',     dlq.dlqInterestAmt != null ? `${dlq.dlqInterestAmt.toLocaleString('ko-KR')}원` : '-'],
              ['연체 합계',     dlq.dlqTotalAmt != null ? `${dlq.dlqTotalAmt.toLocaleString('ko-KR')}원` : '-'],
              ['연체 금리',     dlq.overdueRateBps != null ? `연 ${bpsToRate(dlq.overdueRateBps)}%` : '-'],
            ].map(([label, val]) => (
              <div key={label} className="flex">
                <div className="w-36 px-5 py-3 bg-kb-primary-bg font-medium text-kb-text flex-shrink-0">{label}</div>
                <div className={`px-5 py-3 ${label === '연체 합계' ? 'font-bold text-red-600' : 'text-kb-text-body'}`}>{val}</div>
              </div>
            ))}
          </div>
        </div>
      )}
    </div>
  )
}

// ─── 상환 취소(역분개) ────────────────────────────────────────

function ReversalForm({ contracts, selectedId, setSelectedId }: {
  contracts: LoanContract[]; selectedId: number | null; setSelectedId: (id: number) => void
}) {
  const [txList, setTxList] = useState<any[]>([])
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState('')
  const [reversing, setReversing] = useState<number | null>(null)
  const [remark, setRemark] = useState('')
  const [done, setDone] = useState<number | null>(null)

  async function handleSearch() {
    if (!selectedId) return
    setLoading(true); setError('')
    try {
      const { data: res } = await repaymentApi.list(selectedId)
      const items: any[] = res.data?.items ?? []
      setTxList(items.filter(t => t.rtxStatusCd === 'SUCCESS' && t.rtxTypeCd === 'SCHEDULED'))
    } catch { setError('거래 목록을 불러오지 못했습니다.') }
    finally { setLoading(false) }
  }

  async function handleReverse(rtxId: number) {
    if (!selectedId) return
    setError('')
    try {
      await repaymentApi.reverse(selectedId, rtxId, { reversalReasonCd: 'MISTAKE', reversalRemark: remark || undefined })
      setReversing(null); setRemark(''); setDone(rtxId)
      await handleSearch()
    } catch (err: any) {
      setError(err.response?.data?.message ?? '역분개 처리 중 오류가 발생했습니다.')
    }
  }

  const RTX_TYPE: Record<string, string> = { SCHEDULED: '회차상환', EARLY: '중도상환', PARTIAL: '부분상환' }

  return (
    <div>
      <div className="border border-kb-primary-border rounded-xl bg-[#FAFAFA] px-5 py-4 mb-6 text-[13px] text-kb-text-body space-y-1">
        <p>· SCHEDULED(회차) 상환 거래만 역분개 가능합니다. 중도상환 역분개는 지원하지 않습니다.</p>
        <p>· 역분개 시 해당 회차 상태가 PAID → DUE 로 되돌아갑니다.</p>
      </div>
      <div className="border border-kb-primary-border rounded-xl overflow-hidden divide-y divide-kb-border">
        <ContractSelect contracts={contracts} selectedId={selectedId} onChange={setSelectedId} />
      </div>
      <div className="flex justify-center mt-5">
        <button onClick={handleSearch} disabled={!selectedId || loading}
          className="px-14 py-2.5 text-[14px] font-bold text-kb-text bg-kb-primary text-white hover:opacity-85 disabled:opacity-50 transition-colors">
          {loading ? '조회 중...' : '조회'}
        </button>
      </div>
      {error && <p className="text-[13px] text-kb-red mt-4 text-center">{error}</p>}
      {done && <p className="text-[13px] text-green-600 mt-4 text-center">역분개 처리 완료 (rtxId: {done})</p>}
      {txList.length > 0 && (
        <div className="mt-6 border border-kb-primary-border rounded-xl overflow-hidden">
          <table className="w-full text-[13px]">
            <thead><tr className="bg-kb-primary-bg">
              <th className="px-4 py-3 text-center font-semibold border-b border-kb-primary-border">거래ID</th>
              <th className="px-4 py-3 text-center font-semibold border-b border-kb-primary-border">유형</th>
              <th className="px-4 py-3 text-right font-semibold border-b border-kb-primary-border">금액</th>
              <th className="px-4 py-3 text-center font-semibold border-b border-kb-primary-border">납입일</th>
              <th className="px-4 py-3 text-center font-semibold border-b border-kb-primary-border">처리</th>
            </tr></thead>
            <tbody className="divide-y divide-kb-border">
              {txList.map((t: any) => (
                <tr key={t.rtxId} className="hover:bg-kb-primary-bg">
                  <td className="px-4 py-3 text-center text-kb-text-muted">{t.rtxId}</td>
                  <td className="px-4 py-3 text-center">{RTX_TYPE[t.rtxTypeCd] ?? t.rtxTypeCd}</td>
                  <td className="px-4 py-3 text-right font-bold">{t.totalAmount?.toLocaleString('ko-KR')}원</td>
                  <td className="px-4 py-3 text-center text-kb-text-muted">{t.paidAt?.slice(0, 10) ?? '-'}</td>
                  <td className="px-4 py-3 text-center">
                    <button onClick={() => { setReversing(t.rtxId); setRemark('') }}
                      className="px-3 py-1 text-[11px] border border-red-400 text-red-600 hover:bg-red-50">
                      취소
                    </button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
      {txList.length === 0 && !loading && !error && (
        <p className="text-[13px] text-kb-text-muted mt-6 text-center">조회 버튼을 눌러 상환 거래를 확인하세요.</p>
      )}

      {reversing !== null && (
        <div className="fixed inset-0 bg-black/40 flex items-center justify-center z-50">
          <div className="bg-white rounded-xl shadow-lg w-[400px] p-8">
            <p className="text-[18px] font-bold text-kb-text mb-2">상환 취소(역분개)</p>
            <p className="text-[13px] text-kb-text-body mb-4">거래 rtxId: <strong>{reversing}</strong></p>
            <textarea value={remark} onChange={e => setRemark(e.target.value)}
              rows={3} placeholder="취소 사유 (선택)"
              className="w-full border border-kb-primary-border rounded-lg px-3 py-2 text-[13px] focus:outline-none mb-4 resize-none" />
            <div className="flex gap-2 justify-end">
              <button onClick={() => setReversing(null)}
                className="px-6 py-2 border border-kb-primary-border rounded-xl text-[13px] text-kb-text hover:bg-kb-primary-bg">
                닫기
              </button>
              <button onClick={() => handleReverse(reversing)}
                className="px-6 py-2 bg-red-500 text-[13px] font-bold text-white hover:bg-red-600">
                취소 확정
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  )
}

// ─── 보증보험 ─────────────────────────────────────────────────

const GINS_AGENCY = [
  { code: 'SGI', label: 'SGI서울보증' },
  { code: 'HUG', label: 'HUG주택도시보증공사' },
  { code: 'HF',  label: 'HF한국주택금융공사' },
]

function GuaranteeInsuranceForm({ contracts, selectedId, setSelectedId }: {
  contracts: LoanContract[]; selectedId: number | null; setSelectedId: (id: number) => void
}) {
  const [agency, setAgency] = useState('SGI')
  const [guaranteeAmt, setGuaranteeAmt] = useState('')
  const [ratioBps, setRatioBps] = useState('10000')
  const [premium, setPremium] = useState('')
  const [submitting, setSubmitting] = useState(false)
  const [error, setError] = useState('')
  const [issued, setIssued] = useState<any>(null)
  const [canceling, setCanceling] = useState(false)

  async function handleIssue() {
    if (!selectedId || !guaranteeAmt) return
    setSubmitting(true); setError('')
    try {
      const { data: res } = await guaranteeInsuranceApi.issue(selectedId, {
        ginsAgencyCd:     agency,
        guaranteeAmount:  parseInt(guaranteeAmt.replace(/,/g, '')),
        guaranteeRatioBps: parseInt(ratioBps),
        premiumAmount:    premium ? parseInt(premium.replace(/,/g, '')) : 0,
      })
      setIssued(res.data)
    } catch (err: any) {
      setError(err.response?.data?.message ?? '보증보험 발급 중 오류가 발생했습니다.')
    } finally { setSubmitting(false) }
  }

  async function handleCancel() {
    if (!selectedId || !issued) return
    setCanceling(true); setError('')
    try {
      const { data: res } = await guaranteeInsuranceApi.cancel(selectedId, issued.ginsId, { cancelReasonCd: 'USER_REQUEST' })
      setIssued(res.data)
    } catch (err: any) {
      setError(err.response?.data?.message ?? '보증보험 취소 중 오류가 발생했습니다.')
    } finally { setCanceling(false) }
  }

  const GINS_STATUS: Record<string, { text: string; cls: string }> = {
    ISSUED:   { text: '발급완료', cls: 'text-green-700 bg-green-50 border-green-400' },
    CANCELED: { text: '취소됨',   cls: 'text-gray-500 bg-gray-50 border-gray-300' },
    EXPIRED:  { text: '만료됨',   cls: 'text-orange-600 bg-orange-50 border-orange-400' },
  }

  return (
    <div>
      <div className="border border-kb-primary-border rounded-xl bg-kb-primary-bg px-5 py-4 mb-6 text-[13px] text-kb-text-body space-y-1">
        <p>· 외부 보증기관(SGI/HUG/HF) stub — 발급 요청 즉시 ISSUED 처리됩니다.</p>
        <p>· 계약 상태가 SIGNED 또는 ACTIVE인 경우 발급 가능하며, 활성 보증보험이 1건 초과될 수 없습니다.</p>
      </div>

      {issued ? (
        <div className="border border-kb-primary-border rounded-xl overflow-hidden">
          <div className="bg-kb-primary-bg px-5 py-3 flex justify-between items-center border-b border-kb-primary-border">
            <span className="text-[13px] font-bold text-kb-text">증권번호: {issued.ginsPolicyNo}</span>
            <span className={`text-[11px] px-2 py-0.5 rounded border ${GINS_STATUS[issued.ginsStatusCd]?.cls ?? 'border-kb-primary-border text-kb-text-muted'}`}>
              {GINS_STATUS[issued.ginsStatusCd]?.text ?? issued.ginsStatusCd}
            </span>
          </div>
          <div className="px-5 py-4 grid grid-cols-2 gap-x-8 gap-y-2 text-[13px] text-kb-text-body">
            {[
              ['보증기관', GINS_AGENCY.find(a => a.code === issued.ginsAgencyCd)?.label ?? issued.ginsAgencyCd],
              ['보증금액', `${issued.guaranteeAmount?.toLocaleString('ko-KR')}원`],
              ['보증비율', `${(issued.guaranteeRatioBps / 100).toFixed(0)}%`],
              ['보험료',   `${issued.premiumAmount?.toLocaleString('ko-KR')}원`],
              ['유효시작', issued.ginsStartDate ?? '-'],
              ['유효종료', issued.ginsEndDate ?? '-'],
              ['발급일시', issued.issuedAt?.slice(0, 16).replace('T', ' ') ?? '-'],
            ].map(([label, val]) => (
              <div key={label}><span className="font-medium text-kb-text">{label}</span>: {val}</div>
            ))}
          </div>
          {issued.ginsStatusCd === 'ISSUED' && (
            <div className="px-5 py-3 border-t border-kb-primary-border">
              <button onClick={handleCancel} disabled={canceling}
                className="px-5 py-1.5 border border-red-400 text-[12px] text-red-600 hover:bg-red-50 disabled:opacity-50">
                {canceling ? '처리 중...' : '보증보험 취소'}
              </button>
            </div>
          )}
        </div>
      ) : (
        <>
          <div className="border border-kb-primary-border rounded-xl overflow-hidden divide-y divide-kb-border mb-5">
            <ContractSelect contracts={contracts} selectedId={selectedId} onChange={setSelectedId} />

            <div className="flex items-center px-5 py-4 gap-4">
              <span className="w-36 text-[13px] font-medium text-kb-text flex-shrink-0">보증기관 <span className="text-kb-red">*</span></span>
              <div className="flex gap-2">
                {GINS_AGENCY.map(a => (
                  <button key={a.code} onClick={() => setAgency(a.code)}
                    className={`px-4 py-1.5 border text-[12px] rounded-lg transition-colors ${
                      agency === a.code ? 'bg-kb-primary border-kb-text font-bold text-kb-text' : 'border-kb-primary-border text-kb-text-muted hover:bg-kb-primary-bg'}`}>
                    {a.code}
                  </button>
                ))}
              </div>
            </div>

            <div className="flex items-center px-5 py-4 gap-4">
              <span className="w-36 text-[13px] font-medium text-kb-text flex-shrink-0">보증금액 <span className="text-kb-red">*</span></span>
              <div className="flex items-center gap-2">
                <input type="text"
                  value={guaranteeAmt ? parseInt(guaranteeAmt.replace(/,/g, '')).toLocaleString('ko-KR') : ''}
                  onChange={e => setGuaranteeAmt(e.target.value.replace(/[^\d]/g, ''))}
                  placeholder="0" className="border border-kb-primary-border rounded-lg px-3 py-2 text-[13px] w-40 focus:outline-none text-right" />
                <span className="text-[13px]">원</span>
              </div>
            </div>

            <div className="flex items-center px-5 py-4 gap-4">
              <span className="w-36 text-[13px] font-medium text-kb-text flex-shrink-0">보증비율</span>
              <select value={ratioBps} onChange={e => setRatioBps(e.target.value)}
                className="border border-kb-primary-border rounded-lg px-3 py-2 text-[13px] focus:outline-none">
                <option value="10000">100%</option>
                <option value="8000">80%</option>
                <option value="5000">50%</option>
              </select>
            </div>

            <div className="flex items-center px-5 py-4 gap-4">
              <span className="w-36 text-[13px] font-medium text-kb-text flex-shrink-0">보험료</span>
              <div className="flex items-center gap-2">
                <input type="text"
                  value={premium ? parseInt(premium.replace(/,/g, '')).toLocaleString('ko-KR') : ''}
                  onChange={e => setPremium(e.target.value.replace(/[^\d]/g, ''))}
                  placeholder="0" className="border border-kb-primary-border rounded-lg px-3 py-2 text-[13px] w-40 focus:outline-none text-right" />
                <span className="text-[13px]">원</span>
              </div>
            </div>
          </div>
          {error && <p className="text-[13px] text-kb-red mb-4">{error}</p>}
          <div className="flex justify-center">
            <button onClick={handleIssue} disabled={!selectedId || !guaranteeAmt || submitting}
              className="px-14 py-2.5 text-[14px] font-bold text-kb-text bg-kb-primary text-white hover:opacity-85 disabled:opacity-50 transition-colors">
              {submitting ? '발급 중...' : '보증보험 발급'}
            </button>
          </div>
        </>
      )}
      {error && issued && <p className="text-[13px] text-kb-red mt-4">{error}</p>}
    </div>
  )
}

// ─── UI-only 슬러그 (API pending) ─────────────────────────────

function WithdrawForm({ contracts, selectedId, setSelectedId }: {
  contracts: LoanContract[]; selectedId: number | null; setSelectedId: (id: number) => void
}) {
  const [submitting, setSubmitting] = useState(false)
  const [done, setDone] = useState(false)
  const [error, setError] = useState('')

  async function handleSubmit() {
    if (!selectedId) return
    setSubmitting(true); setError('')
    try {
      await closureApi.close(selectedId, { closureReasonCd: 'WITHDRAWAL' })
      setDone(true)
    } catch (err: any) {
      setError(err.response?.data?.message ?? '처리 중 오류가 발생했습니다.')
    } finally {
      setSubmitting(false)
    }
  }

  if (done) return (
    <div className="py-12 text-center">
      <p className="text-[16px] font-bold text-green-600 mb-2">계약 철회/완제 처리 완료</p>
    </div>
  )

  return (
    <div className="max-w-lg">
      <div className="bg-red-50 border border-red-300 p-4 mb-5 text-[13px] text-red-700">
        <p className="font-bold mb-1">주의</p>
        <p>계약 철회 시 취소가 불가합니다. 신중하게 진행해 주세요.</p>
      </div>
      <div className="border border-kb-primary-border rounded-xl overflow-hidden divide-y divide-kb-border">
        <ContractSelect contracts={contracts} selectedId={selectedId} onChange={setSelectedId} />
      </div>
      {error && <p className="text-[13px] text-kb-red mt-4">{error}</p>}
      <div className="flex justify-center mt-5">
        <button onClick={handleSubmit} disabled={submitting || !selectedId}
          className="px-12 py-2.5 text-[14px] font-bold text-white bg-red-500 hover:bg-red-600 disabled:opacity-50">
          {submitting ? '처리 중...' : '철회/완제 신청'}
        </button>
      </div>
    </div>
  )
}

// ─── 단순 조회 공통 폼 ────────────────────────────────────────

function SimpleQueryForm({ contracts, selectedId, setSelectedId, apiCall, renderResult }: {
  contracts: LoanContract[]; selectedId: number | null; setSelectedId: (id: number) => void
  apiCall: (cntrId: number) => Promise<any>
  renderResult: (data: any) => React.ReactNode
}) {
  const [result, setResult] = useState<any>(null)
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState('')

  async function handleSearch() {
    if (!selectedId) return
    setLoading(true); setError('')
    try {
      const { data: res } = await apiCall(selectedId)
      setResult(res.data)
    } catch {
      setError('조회 중 오류가 발생했습니다.')
    } finally {
      setLoading(false)
    }
  }

  return (
    <div>
      <div className="border border-kb-primary-border rounded-xl overflow-hidden divide-y divide-kb-border">
        <ContractSelect contracts={contracts} selectedId={selectedId} onChange={setSelectedId} />
      </div>
      <div className="flex justify-center mt-5">
        <button onClick={handleSearch} disabled={!selectedId || loading}
          className="px-14 py-2.5 text-[14px] font-bold text-kb-text bg-kb-primary text-white hover:opacity-85 disabled:opacity-50 transition-colors">
          {loading ? '조회 중...' : '조회'}
        </button>
      </div>
      {error && <p className="text-[13px] text-kb-red mt-4 text-center">{error}</p>}
      {result && <div className="mt-6">{renderResult(result)}</div>}
    </div>
  )
}

// ─── 메인 페이지 ──────────────────────────────────────────────

type SlugMeta = { title: string; breadcrumb: string; description?: string }

const PAGE_META: Record<string, SlugMeta> = {
  rate:           { title: '적용금리조회',                       breadcrumb: '적용금리조회' },
  payment:        { title: '이자/월부금입금',                     breadcrumb: '이자/월부금입금' },
  repay:          { title: '대출금상환',                         breadcrumb: '대출금상환' },
  withdraw:       { title: '대출계약철회 예상조회/완제',            breadcrumb: '대출계약철회', description: '대출계약 철회 처리합니다.' },
  extend:         { title: '기한연장',                           breadcrumb: '기한연장', description: '대출 만기일을 연장합니다.' },
  'rate-cut':     { title: '개인대출 금리인하요구권',              breadcrumb: '금리인하요구권' },
  closed:         { title: '해지계좌조회',                       breadcrumb: '해지계좌조회', description: '해지된 대출 계좌 내역을 조회합니다.' },
  'rate-detail':  { title: '금리산정내역서 조회',                 breadcrumb: '금리산정내역서' },
  'debt-relief':  { title: '소멸시효완성에 따른 채무면제 결과조회', breadcrumb: '채무면제 결과조회' },
  'auto-interest':{ title: '통장자동대출 이자납입내역 조회',       breadcrumb: '이자납입내역' },
  notify:         { title: '개인대출 통지서비스',                 breadcrumb: '통지서비스' },
  'payment-method':{ title: '개인대출 할부금(이자) 납입방법 변경', breadcrumb: '납입방법 변경' },
  delinquency:     { title: '연체정보조회',                       breadcrumb: '연체정보조회' },
  reversal:        { title: '상환 취소(역분개)',                   breadcrumb: '상환 취소' },
  'guarantee-insurance': { title: '보증보험 발급/조회',            breadcrumb: '보증보험' },
}

function PageContent({ slug, contracts, selectedId, setSelectedId }: {
  slug: string; contracts: LoanContract[]; selectedId: number | null; setSelectedId: (id: number) => void
}) {
  const props = { contracts, selectedId, setSelectedId }
  switch (slug) {
    case 'rate':    return <RateInfo {...props} />
    case 'payment': return <InterestPaymentForm {...props} />
    case 'repay':   return <RepayForm {...props} />
    case 'withdraw': return <WithdrawForm {...props} />
    case 'extend':  return <ExtendForm {...props} />
    case 'rate-cut': return <RateCutForm {...props} />
    case 'notify':  return <NotifyForm />
    case 'payment-method': return <PaymentMethodForm {...props} />
    case 'delinquency': return <DelinquencyView {...props} />
    case 'reversal':    return <ReversalForm {...props} />
    case 'guarantee-insurance': return <GuaranteeInsuranceForm {...props} />
    case 'closed':
      return <SimpleQueryForm {...props}
        apiCall={cntrId => closureApi.getClosure(cntrId)}
        renderResult={data => (
          <div className="border border-kb-primary-border rounded-xl p-4 text-[13px]">
            <p>해지일: {data?.closedAt?.slice(0, 10) ?? '-'}</p>
            <p>해지사유: {data?.closureReasonCd ?? '-'}</p>
          </div>
        )} />
    case 'rate-detail':
      return <SimpleQueryForm {...props}
        apiCall={cntrId => loanMiscApi.getCertificate(cntrId, 'RATE_DETAIL')}
        renderResult={data => (
          <div className="border border-kb-primary-border rounded-xl overflow-hidden">
            <div className="bg-kb-primary-bg px-5 py-3 border-b border-kb-primary-border">
              <p className="text-[13px] font-bold text-kb-text">금리산정내역서</p>
            </div>
            <div className="divide-y divide-kb-border text-[13px]">
              {data && Object.entries(data).map(([k, v]) => (
                <div key={k} className="flex">
                  <div className="w-40 px-5 py-3 bg-kb-primary-bg font-medium text-kb-text flex-shrink-0">{k}</div>
                  <div className="px-5 py-3 text-kb-text-body">{String(v)}</div>
                </div>
              ))}
            </div>
          </div>
        )} />
    case 'debt-relief':
      return <SimpleQueryForm {...props}
        apiCall={cntrId => loanMiscApi.getCreditInfoReport(cntrId)}
        renderResult={data => (
          <div className="border border-kb-primary-border rounded-xl overflow-hidden">
            <div className="bg-kb-primary-bg px-5 py-3 border-b border-kb-primary-border">
              <p className="text-[13px] font-bold text-kb-text">신용정보 결과</p>
            </div>
            <div className="divide-y divide-kb-border text-[13px]">
              {data && Object.entries(data).map(([k, v]) => (
                <div key={k} className="flex">
                  <div className="w-40 px-5 py-3 bg-kb-primary-bg font-medium text-kb-text flex-shrink-0">{k}</div>
                  <div className="px-5 py-3 text-kb-text-body">{String(v)}</div>
                </div>
              ))}
            </div>
          </div>
        )} />
    case 'auto-interest':
      return <SimpleQueryForm {...props}
        apiCall={cntrId => rateApi.getInterestAccruals(cntrId)}
        renderResult={data => {
          const items: any[] = data?.items ?? (Array.isArray(data) ? data : [])
          return (
            <div className="border border-kb-primary-border rounded-xl overflow-hidden">
              <div className="bg-kb-primary-bg px-5 py-3 border-b border-kb-primary-border">
                <p className="text-[13px] font-bold text-kb-text">이자 발생 내역 ({items.length}건)</p>
              </div>
              {items.length === 0 ? (
                <p className="px-5 py-6 text-[13px] text-kb-text-muted">내역이 없습니다.</p>
              ) : (
                <table className="w-full text-[13px]">
                  <thead><tr className="bg-[#FAFAFA]">
                    <th className="px-4 py-2 text-left font-medium border-b border-kb-primary-border">발생일</th>
                    <th className="px-4 py-2 text-right font-medium border-b border-kb-primary-border">원금 잔액</th>
                    <th className="px-4 py-2 text-right font-medium border-b border-kb-primary-border">이자</th>
                    <th className="px-4 py-2 text-right font-medium border-b border-kb-primary-border">금리</th>
                  </tr></thead>
                  <tbody className="divide-y divide-kb-border">
                    {items.map((item: any, i: number) => (
                      <tr key={i} className="hover:bg-kb-primary-bg">
                        <td className="px-4 py-2">{item.accrualDate?.slice(0, 10) ?? '-'}</td>
                        <td className="px-4 py-2 text-right">{item.principalBalance != null ? `${item.principalBalance.toLocaleString('ko-KR')}원` : '-'}</td>
                        <td className="px-4 py-2 text-right font-medium">{item.interestAmt != null ? `${item.interestAmt.toLocaleString('ko-KR')}원` : '-'}</td>
                        <td className="px-4 py-2 text-right">{item.dailyRateBps != null ? `${bpsToRate(item.dailyRateBps * 365)}%` : '-'}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              )}
            </div>
          )
        }} />
    default:
      return <p className="text-[15px] text-kb-text-muted py-20 text-center">페이지를 찾을 수 없습니다.</p>
  }
}

/**
 * 상환 회차 상태. 백엔드 RepaymentSchedule 의 rsch_status_cd 와 같은 집합이다.
 * 예전에는 paidYn 이라는 존재하지 않는 필드를 'Y'/'N' 으로 비교해 전부 "미납"으로 보였다.
 */
const SCHEDULE_STATUS: Record<string, { label: string; cls: string }> = {
  PAID:         { label: '납입완료', cls: 'bg-green-100 text-green-700' },
  DUE:          { label: '납입예정', cls: 'bg-gray-100 text-gray-600' },
  OVERDUE:      { label: '연체',     cls: 'bg-red-100 text-red-600' },
  PARTIAL_PAID: { label: '부분납입', cls: 'bg-yellow-100 text-yellow-700' },
  SUPERSEDED:   { label: '대체됨',   cls: 'bg-gray-100 text-gray-400' },
}

/** 납입 버튼을 띄울 상태. 이미 냈거나 대체된 회차에는 띄우지 않는다. */
const PAYABLE_STATUSES = ['DUE', 'OVERDUE', 'PARTIAL_PAID']

/** yyyyMMdd → yyyy-MM-dd */
function formatDueDate(yyyymmdd: string): string {
  if (!yyyymmdd || yyyymmdd.length !== 8) return yyyymmdd ?? ''
  return `${yyyymmdd.slice(0, 4)}-${yyyymmdd.slice(4, 6)}-${yyyymmdd.slice(6, 8)}`
}

export default function ManagePage() {
  const params = useParams()
  const slug = params.slug as string
  const { contracts, selectedId, setSelectedId } = useContracts()
  const meta = PAGE_META[slug]

  return (
    <main className="pb-16">
      <div className="max-w-kb-container mx-auto px-6 pt-6">
        <AutoBreadcrumb leaf={meta?.breadcrumb ?? slug} />
        <div className="flex gap-8">
          <LoanSidebar />
          <div className="flex-1 min-w-0">
            <h1 className="text-[26px] font-bold text-kb-text mb-2">{meta?.title ?? slug}</h1>
            {meta?.description && (
              <p className="text-[13px] text-kb-text-muted mb-6">{meta.description}</p>
            )}
            <div className="border-t-2 border-kb-primary pt-6">
              <PageContent slug={slug} contracts={contracts} selectedId={selectedId} setSelectedId={setSelectedId} />
            </div>
          </div>
        </div>
      </div>
    </main>
  )
}
