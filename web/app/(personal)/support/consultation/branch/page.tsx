'use client'
import { KB_MINT, KB_PRIMARY, KB_PRIMARY_BORDER } from '@/lib/theme'

/* eslint-disable @typescript-eslint/no-unused-vars -- 예약 폼 미사용 state, 추후 기능 연결 예정 (빌드 차단 방지) */

import Link from 'next/link'
import { useCallback, useEffect, useState } from 'react'

import BranchSearchModal from '@/components/support/BranchSearchModal'
import {
  cancelBranchReservation,
  fetchMyBranchReservations,
  reserveBranchConsultation,
  type Branch,
  type BranchReservation,
} from '@/lib/banking-api'


const CONTENT_TYPES = [
  '선택',
  '예금/적금',
  '대출',
  '펀드/신탁',
  '카드',
  '보험',
  '외환',
  '기타',
]

/** 화면 문구 ↔ 저장 코드. 문구만 바꿔도 집계가 끊기지 않게 코드를 따로 둔다. */
const TOPIC_CODES: Record<string, string> = {
  '예금/적금': 'DEPOSIT',
  '대출': 'LOAN',
  '펀드/신탁': 'FUND',
  '카드': 'CARD',
  '보험': 'INSURANCE',
  '외환': 'FX',
  '기타': 'ETC',
}

const TOPIC_LABELS: Record<string, string> = Object.fromEntries(
  Object.entries(TOPIC_CODES).map(([label, code]) => [code, label]))

const TIMES = [
  '선택', '09:00', '09:30', '10:00', '10:30', '11:00', '11:30',
  '14:00', '14:30', '15:00', '15:30',
]

export default function BranchConsultationPage() {
  const [pageTab, setPageTab] = useState<'apply' | 'history'>('apply')
  const [branch, setBranch] = useState('')
  const [contentType, setContentType] = useState('선택')
  const [time, setTime] = useState('선택')
  const [reserveType, setReserveType] = useState<'auto' | 'manual'>('auto')
  const [staffSelect, setStaffSelect] = useState('선택')
  const [memo, setMemo] = useState('')

  // 예전에는 이 화면이 통째로 흉내였다 — 지점검색은 핸들러가 없었고 예약 버튼은
  // alert 만 띄우고 아무것도 저장하지 않았다. 고객은 예약됐다고 믿고 지점에 갔을 것이다.
  const [selectedBranch, setSelectedBranch] = useState<Branch | null>(null)
  const [searchOpen, setSearchOpen] = useState(false)
  const [date, setDate] = useState('')
  const [contactPhone, setContactPhone] = useState('')
  const [submitting, setSubmitting] = useState(false)
  const [reservations, setReservations] = useState<BranchReservation[]>([])

  const loadReservations = useCallback(async () => {
    // 로그인 전이면 403 이다. 예약 신청 화면 자체가 막히면 안 되므로 비워 둔다.
    try { setReservations(await fetchMyBranchReservations()) } catch { setReservations([]) }
  }, [])

  useEffect(() => { loadReservations() }, [loadReservations])

  async function handleCancel(reservationId: number) {
    try {
      await cancelBranchReservation(reservationId)
      await loadReservations()
    } catch {
      alert('취소하지 못했습니다.')
    }
  }

  async function handleSubmit() {
    if (!selectedBranch) { alert('상담 지점을 검색해 선택해주세요.'); return }
    if (contentType === '선택') { alert('상담 내용을 선택해주세요.'); return }
    if (!date || time === '선택') { alert('상담 일시를 선택해주세요.'); return }
    if (!contactPhone.trim()) { alert('연락받을 번호를 입력해주세요.'); return }

    setSubmitting(true)
    try {
      // 로컬 시각으로 만든다 — UTC 로 만들면 지점 영업시간 검사가 아홉 시간 어긋난다.
      const reservedAt = new Date(`${date}T${time}:00`).toISOString()
      await reserveBranchConsultation({
        branchId: selectedBranch.branchId,
        reservedAt,
        topicCd: TOPIC_CODES[contentType] ?? 'ETC',
        memo: memo.trim() || undefined,
        contactPhone: contactPhone.trim(),
      })
      await loadReservations()
      setPageTab('history')
      alert('상담 예약이 접수되었습니다.')
    } catch (e: unknown) {
      const msg = (e as { response?: { data?: { message?: string } } })?.response?.data?.message
      alert(msg ?? '예약하지 못했습니다. 잠시 후 다시 시도해주세요.')
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <div className="min-h-screen" style={{ backgroundColor: '#F8FDFB' }}>
      <div className="max-w-kb-container mx-auto px-6 py-8">
        {/* 브레드크럼 */}
        <div className="flex items-center gap-1 text-[12px] text-kb-text-muted mb-6">
          <Link href="/" className="hover:underline">홈</Link>
          <span>›</span>
          <span>고객센터</span>
          <span>›</span>
          <span>고객상담</span>
          <span>›</span>
          <span className="font-semibold text-kb-text">지점 상담 예약서비스</span>
        </div>

        <main className="w-full">
          <h1 className="text-[22px] font-bold text-kb-text mb-2">지점 상담 예약서비스</h1>
          <p className="text-[13px] text-kb-text-muted mb-6">원하는 지점에 방문 상담을 미리 예약하세요.</p>

          {/* 서브 탭 */}
          <div className="flex gap-0 mb-7 border-b-2 border-kb-primary-border">
            {[{ key: 'apply', label: '예약 신청' }, { key: 'history', label: '예약 현황 조회' }].map(t => (
              <button
                key={t.key}
                onClick={() => setPageTab(t.key as 'apply' | 'history')}
                className={`px-6 py-3 text-[14px] font-semibold transition-colors relative ${
                  pageTab === t.key
                    ? 'text-kb-primary font-bold'
                    : 'text-kb-text-muted hover:text-kb-text'
                }`}
              >
                {t.label}
                {pageTab === t.key && (
                  <span className="absolute bottom-[-2px] left-0 right-0 h-[2px]" style={{ backgroundColor: KB_PRIMARY }} />
                )}
              </button>
            ))}
          </div>

          {pageTab === 'apply' && (
            <>
              {/* 상담예약 절차 */}
              <div className="bg-white rounded-2xl p-6 mb-6 shadow-sm" style={{ border: `1px solid ${KB_MINT}20` }}>
                <p className="text-[14px] font-bold text-kb-text mb-5">상담예약 절차</p>
                <div className="flex items-center gap-3 flex-wrap">

                  {/* Step 1 — active */}
                  <div className="flex flex-col items-center gap-2 min-w-[100px]">
                    <div className="w-8 h-8 rounded-full flex items-center justify-center text-white text-[13px] font-bold flex-shrink-0"
                      style={{ backgroundColor: KB_PRIMARY }}>
                      1
                    </div>
                    <div className="bg-kb-primary-bg rounded-xl px-4 py-2.5 text-center" style={{ border: `1px solid ${KB_MINT}20` }}>
                      <p className="text-[12px] font-bold text-kb-primary whitespace-nowrap">상담예약 신청</p>
                    </div>
                  </div>

                  <span className="text-kb-mint text-xl font-bold">→</span>

                  {/* Step 2 */}
                  <div className="flex flex-col items-center gap-2 min-w-[100px]">
                    <div className="w-8 h-8 rounded-full flex items-center justify-center text-[13px] font-bold flex-shrink-0"
                      style={{ backgroundColor: KB_PRIMARY_BORDER, color: KB_PRIMARY }}>
                      2
                    </div>
                    <div className="rounded-xl px-4 py-2.5 text-center" style={{ border: `1px solid ${KB_MINT}40`, backgroundColor: '#FAFAFA' }}>
                      <p className="text-[12px] font-semibold text-kb-text whitespace-nowrap">상담직원 선택</p>
                    </div>
                    <div className="flex gap-2 mt-1">
                      <div className="bg-white rounded-xl px-3 py-2 text-[11px] text-center shadow-sm" style={{ border: `1px solid ${KB_MINT}20` }}>
                        <p className="font-semibold text-kb-text">직원 자동배정</p>
                        <p className="text-kb-text-muted mt-0.5">예약 즉시 확정</p>
                      </div>
                      <div className="bg-white rounded-xl px-3 py-2 text-[11px] text-center shadow-sm" style={{ border: `1px solid ${KB_MINT}20` }}>
                        <p className="font-semibold text-kb-text">직원 직접 선택</p>
                        <p className="text-kb-text-muted mt-0.5">직원 확인 후 확정</p>
                      </div>
                    </div>
                  </div>

                  <span className="text-kb-mint text-xl font-bold">→</span>

                  {/* Step 3 */}
                  <div className="flex flex-col items-center gap-2 min-w-[100px]">
                    <div className="w-8 h-8 rounded-full flex items-center justify-center text-[13px] font-bold flex-shrink-0"
                      style={{ backgroundColor: KB_PRIMARY_BORDER, color: KB_PRIMARY }}>
                      3
                    </div>
                    <div className="rounded-xl px-4 py-2.5 text-center" style={{ border: `1px solid ${KB_MINT}40`, backgroundColor: '#FAFAFA' }}>
                      <p className="text-[12px] font-semibold text-kb-text whitespace-nowrap">예약지점 방문</p>
                    </div>
                  </div>

                </div>
              </div>

              {/* 안내 사항 */}
              <div className="bg-kb-primary-bg rounded-xl px-5 py-4 mb-6" style={{ border: `1px solid ${KB_MINT}30` }}>
                <ul className="space-y-1.5 text-[12px] text-kb-text-muted">
                  {[
                    '상담 직원을 선택하면 직원 확인 후 예약이 확정돼요.',
                    '직원 선택으로 예약 시, 영업일 16시 이전 신청 건은 당일 19시까지, 영업일 16시 이후 또는 휴일 신청 건은 다음 영업일 19시까지 결과를 알려드려요.',
                    '지점 상황에 따라 예약이 취소될 수 있어요.',
                    '예약시간 15분 전부터 번호표가 발권되며, 10분이 지나면 예약이 취소됩니다.',
                    '예약 당일 지점에 방문하시면, 지점 번호표 발행기의 [예약 발권]을 눌러주세요.',
                    '예금/펀드/신탁, 개인대출, 개인사업자/법인대출, 외환(수출입거래) 상담(신규, 연장)을 받을 수 있습니다.',
                  ].map((note, i) => (
                    <li key={i} className="flex gap-1.5">
                      <span className="text-kb-mint font-bold flex-shrink-0">*</span>
                      {note}
                    </li>
                  ))}
                </ul>
              </div>

              {/* 상담 예약 정보 입력 */}
              <div className="bg-white rounded-2xl shadow-sm overflow-hidden mb-5" style={{ border: `1px solid ${KB_MINT}20` }}>
                <div className="px-6 py-4 border-b" style={{ borderColor: KB_PRIMARY_BORDER, backgroundColor: '#F8FDFB' }}>
                  <h2 className="text-[16px] font-bold text-kb-text">상담 예약 정보 입력</h2>
                  <p className="text-[12px] text-kb-danger mt-1">
                    <span className="text-kb-mint font-bold">★</span> 표시가 있는 항목은 반드시 입력해주세요.
                  </p>
                </div>

                <table className="w-full border-collapse text-[13px] border-t-2 border-kb-primary">
                  <tbody>
                    {/* 상담 지점 */}
                    <tr>
                      <td className="bg-kb-primary-bg border border-kb-primary-border px-4 py-3.5 font-semibold text-kb-text w-[130px] whitespace-nowrap align-middle">
                        상담 지점<span className="text-kb-mint font-bold ml-0.5">★</span>
                      </td>
                      <td className="border border-kb-primary-border px-4 py-3.5">
                        <div className="flex items-center gap-2">
                          {/* 손으로 치면 존재하지 않는 지점으로도 예약이 된다.
                              검색으로 고른 것만 받는다. */}
                          <input
                            type="text"
                            readOnly
                            value={branch}
                            placeholder="지점검색으로 선택"
                            onClick={() => setSearchOpen(true)}
                            className="border border-kb-primary-border rounded-lg px-3 py-1.5 text-[13px] w-48 outline-none focus:border-kb-mint transition-colors cursor-pointer bg-white"
                          />
                          <button onClick={() => setSearchOpen(true)}
                            className="border-2 rounded-lg px-4 py-1.5 text-[12px] font-semibold hover:bg-kb-primary-bg transition-colors flex items-center gap-1"
                            style={{ borderColor: KB_PRIMARY, color: KB_PRIMARY }}>
                            지점검색
                            <svg viewBox="0 0 16 16" fill="none" className="w-3 h-3" stroke="currentColor" strokeWidth="2">
                              <path d="M7 1h7v7M14 1L7 8" /><rect x="1" y="5" width="7" height="9" />
                            </svg>
                          </button>
                        </div>
                      </td>
                    </tr>

                    {/* 상담 내용 */}
                    <tr>
                      <td className="bg-kb-primary-bg border border-kb-primary-border px-4 py-3.5 font-semibold text-kb-text align-middle whitespace-nowrap">
                        상담 내용<span className="text-kb-mint font-bold ml-0.5">★</span>
                      </td>
                      <td className="border border-kb-primary-border px-4 py-3.5">
                        <div className="flex items-center gap-2 mb-2">
                          <select
                            value={contentType}
                            onChange={e => setContentType(e.target.value)}
                            className="border border-kb-primary-border rounded-lg px-3 py-1.5 text-[13px] outline-none bg-white focus:border-kb-mint transition-colors"
                          >
                            {CONTENT_TYPES.map(c => <option key={c}>{c}</option>)}
                          </select>
                        </div>
                        <p className="text-[12px] text-kb-danger flex items-start gap-1">
                          <span className="bg-kb-danger text-white rounded-full w-4 h-4 flex items-center justify-center text-[10px] flex-shrink-0 mt-0.5">ⓘ</span>
                          입출금, 통장 재발행, 통장정리, 동전교환 등 단순 업무는 예약 대상이 아닙니다.
                        </p>
                      </td>
                    </tr>

                    {/* 상담 일시 */}
                    <tr>
                      <td className="bg-kb-primary-bg border border-kb-primary-border px-4 py-3.5 font-semibold text-kb-text align-top whitespace-nowrap pt-4">
                        상담 일시<span className="text-kb-mint font-bold ml-0.5">★</span>
                      </td>
                      <td className="border border-kb-primary-border px-4 py-3.5">
                        <div className="flex items-center gap-2 mb-2 flex-wrap">
                          {/* 예전에는 '월' 만 골랐다. 날짜가 없으면 예약 시각을 만들 수 없다. */}
                          <input type="date" value={date} onChange={e => setDate(e.target.value)}
                            min={new Date().toISOString().slice(0, 10)}
                            className="border border-kb-primary-border rounded-lg px-3 py-1.5 text-[13px] outline-none bg-white focus:border-kb-mint transition-colors" />
                          <select value={time} onChange={e => setTime(e.target.value)}
                            className="border border-kb-primary-border rounded-lg px-3 py-1.5 text-[13px] outline-none bg-white focus:border-kb-mint transition-colors">
                            {TIMES.map(t => <option key={t}>{t}</option>)}
                          </select>
                        </div>
                        {/* 연락처 칸이 없었다. 화면은 "문자로 안내드리겠습니다" 라고 약속하는데
                            보낼 곳을 묻지 않았다 — 서버도 같은 이유로 빈 값을 거절한다. */}
                        <div className="flex items-center gap-2 mb-2">
                          <span className="text-[12px] text-kb-text-muted">연락처</span>
                          <input value={contactPhone} onChange={e => setContactPhone(e.target.value)}
                            placeholder="010-0000-0000"
                            className="border border-kb-primary-border rounded-lg px-3 py-1.5 text-[13px] w-44 outline-none focus:border-kb-mint transition-colors" />
                        </div>
                        <p className="text-[12px] text-kb-text-muted flex items-start gap-1">
                          <span className="bg-kb-text-muted text-white rounded-full w-4 h-4 flex items-center justify-center text-[10px] flex-shrink-0 mt-0.5">ⓘ</span>
                          {selectedBranch
                            ? `${selectedBranch.branchName} 영업시간 ${selectedBranch.openTime}~${selectedBranch.closeTime} 안에서 예약할 수 있습니다.`
                            : '상담 예약은 같은 날짜에 1회만 신청할 수 있으며, 예약 상황에 따라 일부 시간대는 신청이 어려울 수 있습니다.'}
                        </p>
                      </td>
                    </tr>

                    {/* 예약 방법 */}
                    <tr>
                      <td className="bg-kb-primary-bg border border-kb-primary-border px-4 py-3.5 font-semibold text-kb-text align-top whitespace-nowrap pt-4">
                        예약 방법<span className="text-kb-mint font-bold ml-0.5">★</span>
                      </td>
                      <td className="border border-kb-primary-border px-4 py-3.5 space-y-3">
                        {/* 자동배정 */}
                        <label className="flex items-start gap-3 cursor-pointer">
                          <input type="radio" name="reserveType" checked={reserveType === 'auto'}
                            onChange={() => setReserveType('auto')}
                            className="mt-0.5 accent-kb-mint" />
                          <div>
                            <p className="text-[13px] font-semibold text-kb-text">직원 자동배정</p>
                            <p className="text-[12px] text-kb-text-body">예약이 바로 확정돼요.</p>
                            <p className="text-[12px] text-kb-text-muted flex items-start gap-1 mt-0.5">
                              <span className="bg-kb-text-muted text-white rounded-full w-4 h-4 flex items-center justify-center text-[10px] flex-shrink-0 mt-0.5">ⓘ</span>
                              예약한 시간에 바로 상담할 수 있는 직원이 배정돼요.
                            </p>
                          </div>
                        </label>
                        {/* 직접 선택 */}
                        <label className="flex items-start gap-3 cursor-pointer">
                          <input type="radio" name="reserveType" checked={reserveType === 'manual'}
                            onChange={() => setReserveType('manual')}
                            className="mt-0.5 accent-kb-mint" />
                          <div className="flex-1">
                            <div className="flex items-center gap-4">
                              <div>
                                <p className="text-[13px] font-semibold text-kb-text">상담직원 선택</p>
                                <p className="text-[12px] text-kb-text-body">직원 확인 후 예약이 확정돼요.</p>
                              </div>
                              {reserveType === 'manual' && (
                                <select value={staffSelect} onChange={e => setStaffSelect(e.target.value)}
                                  className="border border-kb-primary-border rounded-lg px-3 py-1.5 text-[13px] outline-none bg-white focus:border-kb-mint transition-colors">
                                  <option>선택</option>
                                </select>
                              )}
                              {reserveType !== 'manual' && (
                                <select disabled
                                  className="border border-kb-primary-border rounded-lg px-3 py-1.5 text-[13px] bg-[#f5f5f5] text-kb-text-muted">
                                  <option>선택</option>
                                </select>
                              )}
                            </div>
                            <p className="text-[12px] text-kb-text-muted flex items-start gap-1 mt-0.5">
                              <span className="bg-kb-text-muted text-white rounded-full w-4 h-4 flex items-center justify-center text-[10px] flex-shrink-0 mt-0.5">ⓘ</span>
                              지점 상황에 따라 예약이 취소 될 수 있어요.
                            </p>
                          </div>
                        </label>
                      </td>
                    </tr>

                    {/* 상담 희망 내용 */}
                    <tr>
                      <td className="bg-kb-primary-bg border border-kb-primary-border px-4 py-3.5 font-semibold text-kb-text align-top whitespace-nowrap">
                        상담 희망 내용
                      </td>
                      <td className="border border-kb-primary-border px-4 py-3.5">
                        <p className="text-[12px] text-kb-text-muted mb-1.5">{memo.length}/200자</p>
                        <textarea
                          value={memo}
                          onChange={e => { if (e.target.value.length <= 200) setMemo(e.target.value) }}
                          placeholder={`상담받고 싶은 내용을 입력하시면 원활한 상담을 받으실 수 있어요.\n예) 상담 상품, 상담 문의사항, 추가 상담 필요업무 등`}
                          className="w-full h-24 border border-kb-primary-border rounded-lg px-3 py-2.5 text-[13px] resize-none outline-none leading-relaxed text-kb-text-body placeholder:text-kb-text-muted focus:border-kb-mint transition-colors"
                        />
                      </td>
                    </tr>
                  </tbody>
                </table>
              </div>

              {/* 주의사항 */}
              <div className="bg-kb-primary-bg rounded-xl px-5 py-4 mb-6" style={{ border: `1px solid ${KB_MINT}30` }}>
                <ul className="space-y-1 text-[12px] text-kb-text-muted">
                  {[
                    '상담 예약을 신청하면 카카오톡 또는 문자 메시지로 내역이 발송됩니다.',
                    '스팸 차단을 했거나 SMS 수신에 동의하지 않은 경우, 예약 안내를 받을 수 없습니다.',
                    '연락처 변경이나 휴대폰 번호 등록은 [내 정보 수정]에서 할 수 있습니다',
                  ].map((n, i) => (
                    <li key={i} className="flex gap-1.5"><span className="flex-shrink-0 text-kb-mint font-bold">*</span>{n}</li>
                  ))}
                </ul>
              </div>

              {/* 버튼 */}
              <div className="flex items-center gap-3">
                {/* 안내문이 "[내 정보 수정]에서 할 수 있습니다" 라고 가리키는 화면이
                    실제로 있다(/settings). 버튼만 연결돼 있지 않았다. */}
                <Link href="/settings"
                  className="px-6 py-2.5 text-[14px] font-semibold rounded-lg border-2 hover:bg-kb-primary-bg transition-colors"
                  style={{ borderColor: KB_PRIMARY, color: KB_PRIMARY }}>
                  내 정보 수정
                </Link>
                <button
                  onClick={handleSubmit}
                  disabled={submitting}
                  className="px-8 py-2.5 text-[14px] font-bold text-white rounded-lg hover:opacity-85 transition-opacity disabled:opacity-50"
                  style={{ backgroundColor: KB_PRIMARY }}
                >
                  {submitting ? '접수 중…' : '상담 예약'}
                </button>
              </div>
            </>
          )}

          {pageTab === 'history' && (
            reservations.length === 0 ? (
              <div className="bg-white rounded-2xl shadow-sm py-16 text-center text-[14px] text-kb-text-muted" style={{ border: `1px solid ${KB_MINT}20` }}>
                예약 현황이 없습니다.
              </div>
            ) : (
              <ul className="space-y-3">
                {reservations.map(r => (
                  <li key={r.reservationId}
                    className="bg-white rounded-2xl shadow-sm px-5 py-4 flex items-center justify-between gap-4"
                    style={{ border: `1px solid ${KB_MINT}20` }}>
                    <div className="min-w-0">
                      <p className="text-[14px] font-bold text-kb-text">{r.branchName}</p>
                      <p className="text-[13px] text-kb-text-muted mt-0.5">
                        {r.reservedAt.slice(0, 16).replace('T', ' ')} · {TOPIC_LABELS[r.topicCd] ?? r.topicCd}
                      </p>
                      {r.memo && <p className="text-[12px] text-kb-text-muted mt-0.5">{r.memo}</p>}
                    </div>
                    <div className="flex items-center gap-3 flex-shrink-0">
                      <span className="text-[12px] font-semibold"
                        style={{ color: r.statusCd === 'CANCELLED' ? '#9CA3AF' : KB_PRIMARY }}>
                        {r.statusCd === 'RESERVED' ? '예약완료'
                          : r.statusCd === 'CANCELLED' ? '취소됨'
                          : r.statusCd === 'COMPLETED' ? '상담완료' : '미방문'}
                      </span>
                      {r.statusCd === 'RESERVED' && (
                        <button onClick={() => handleCancel(r.reservationId)}
                          className="border rounded-lg px-3 py-1 text-[12px] text-kb-text-body hover:bg-kb-primary-bg transition-colors"
                          style={{ borderColor: KB_PRIMARY_BORDER }}>
                          예약 취소
                        </button>
                      )}
                    </div>
                  </li>
                ))}
              </ul>
            )
          )}
        </main>
      </div>

      {searchOpen && (
        <BranchSearchModal
          onSelect={b => { setSelectedBranch(b); setBranch(b.branchName); setSearchOpen(false) }}
          onClose={() => setSearchOpen(false)}
        />
      )}
    </div>
  )
}
