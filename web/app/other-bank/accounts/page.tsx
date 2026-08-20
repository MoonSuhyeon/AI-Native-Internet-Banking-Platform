'use client'

import Link from 'next/link'
import { useCallback, useState, useEffect } from 'react'
import { fetchInboundPayments, InboundPayment } from '@/lib/other-bank-api'
import { DAON_NAVY, DAON_NAVY_MID } from '@/lib/theme'

/* ── 다온은행 계좌 정보 (mock) ── */
const DAON_ACCOUNT = {
  number: '880-21-0457-118',
  name: '다온 주거래 입출금통장',
  holder: '김민준',
  createdAt: '2025.11.03',
  balance: 8_120_000,
  availableBalance: 8_120_000,
}

const DATE_PRESETS = ['1개월', '3개월', '6개월', '1년', '직접입력']
const TX_TYPE_OPTS = ['전체', '입금', '출금']

function formatNumber(n: number) {
  return n.toLocaleString('ko-KR')
}

function formatDatetime(iso: string | null): string {
  if (!iso) return '-'
  // "2026-06-08T14:22:00" → "2026.06.08 14:22"
  return iso.replace('T', ' ').slice(0, 16).replace(/-/g, '.')
}

function senderLabel(tx: InboundPayment): string {
  const display = tx.receiverPassbookSenderDisplay
  if (display && display !== 'null') return display
  return tx.senderAccountNoSnap || '타행 계좌'
}

function bannerSenderLabel(tx: InboundPayment): string {
  const acct = tx.senderAccountNoSnap
  return acct ? `타행 ${acct}` : '타행 계좌'
}

export default function DaonAccountsPage() {
  const [datePreset, setDatePreset] = useState('1개월')
  const [txType, setTxType] = useState('전체')
  const [balanceVisible, setBalanceVisible] = useState(true)
  const [keyword, setKeyword] = useState('')
  const [inboundList, setInboundList] = useState<InboundPayment[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)

  const loadInbound = useCallback(() => {
    setLoading(true)
    fetchInboundPayments(DAON_ACCOUNT.number)
      .then(data => { setInboundList(data); setError(null) })
      .catch(() => setError('입금내역을 불러오지 못했습니다.'))
      .finally(() => setLoading(false))
  }, [])

  useEffect(() => { loadInbound() }, [loadInbound])

  const latestDeposit = inboundList[0] ?? null

  const filteredTx = inboundList.filter(tx => {
    // API는 COMPLETED(입금)만 반환 — 출금 필터 시 전체 빈 목록
    if (txType === '출금') return false
    const label = `타행이체 입금 ${senderLabel(tx)}`
    if (keyword && !label.includes(keyword)) return false
    return true
  })

  return (
    <div className="max-w-kb-container mx-auto px-6 py-4 pb-16">
      {/* 브레드크럼 */}
      <div className="flex justify-end mb-4 text-[12px] text-kb-text-muted gap-1">
        <span>개인뱅킹</span><span>&gt;</span>
        <span>조회</span><span>&gt;</span>
        <span className="hover:underline">계좌조회</span>
        <span>&gt;</span>
        <span className="font-semibold text-kb-text">거래내역</span>
      </div>

      {/* 타행 입금 도착 배너 */}
      {!loading && latestDeposit && (
        <div className="rounded-xl px-6 py-4 mb-5 flex items-center justify-between"
          style={{ background: `linear-gradient(135deg, ${DAON_NAVY} 0%, ${DAON_NAVY_MID} 100%)` }}>
          <div className="flex items-center gap-4">
            <div className="w-11 h-11 rounded-full bg-white/15 flex items-center justify-center text-2xl">💸</div>
            <div className="text-white">
              <p className="text-[13px] text-white/80">타행 입금이 도착했어요</p>
              <p className="text-[16px] font-bold">
                {bannerSenderLabel(latestDeposit)} → {DAON_ACCOUNT.holder}님
              </p>
            </div>
          </div>
          <div className="text-right text-white">
            <p className="text-[22px] font-extrabold">+{formatNumber(latestDeposit.transferAmount)}원</p>
            <p className="text-[12px] text-white/70">{formatDatetime(latestDeposit.completedAt)}</p>
          </div>
        </div>
      )}

      {/* 계좌 정보 헤더 */}
      <div className="border border-kb-border-dark rounded-xl p-6 mb-6 bg-daon-surface-light">
        <div className="flex items-start justify-between">
          <div>
            <p className="text-[13px] text-kb-text-muted mb-1">{DAON_ACCOUNT.name} · 예금주 {DAON_ACCOUNT.holder}</p>
            <p className="text-[18px] font-bold text-kb-text mb-1">{DAON_ACCOUNT.number}</p>
            <div className="flex gap-4 text-[12px] text-kb-text-muted">
              <span>신규일 {DAON_ACCOUNT.createdAt}</span>
            </div>
          </div>
          <div className="text-right">
            <div className="flex items-center justify-end gap-2 mb-1">
              <span className="text-[12px] text-kb-text-muted">잔액보기</span>
              <button
                onClick={() => setBalanceVisible(v => !v)}
                className="relative w-10 h-5 rounded-full transition-colors"
                style={{ backgroundColor: balanceVisible ? DAON_NAVY : '#cbd5e1' }}
              >
                <span className={`absolute top-0.5 w-4 h-4 bg-white rounded-full shadow transition-transform ${balanceVisible ? 'translate-x-5' : 'translate-x-0.5'}`} />
                <span className={`absolute text-[9px] font-bold text-white ${balanceVisible ? 'left-1.5 top-0.5' : 'right-1 top-0.5'}`}>
                  {balanceVisible ? 'ON' : 'OFF'}
                </span>
              </button>
            </div>
            <p className="text-[12px] text-kb-text-muted">출금가능잔액</p>
            <p className="text-[22px] font-bold text-kb-text">
              {balanceVisible ? formatNumber(DAON_ACCOUNT.availableBalance) : '●●●●●●●'}원
            </p>
            <p className="text-[12px] text-kb-text-muted mt-1">
              잔액 {balanceVisible ? formatNumber(DAON_ACCOUNT.balance) : '●●●●●●●'}원
            </p>
          </div>
        </div>

        {/* 계좌 액션 버튼 */}
        <div className="flex gap-2 mt-4 pt-4 border-t border-kb-border">
          {/* 다온은행은 오픈뱅킹 상대 은행을 흉내 낸 데모 화면이다. 이 화면의 몫은
              "AXful 에서 보낸 돈이 도착했는가" 를 보여 주는 것까지이고, 다온 자체의
              뱅킹 기능(이체·계좌관리·입금통보)은 이 레포에 없다. 눌러도 아무 일이
              없는 버튼을 그대로 두면 되는 줄 알고 눌러 본다. */}
          {['이체', '계좌관리', '입금통보 신청'].map(label => (
            <button key={label} disabled
              title="다온은행 데모 화면입니다. 해당 기능은 제공되지 않습니다."
              className="border border-kb-border px-5 py-1.5 text-[12px] text-kb-text-body opacity-40 cursor-not-allowed">
              {label}
            </button>
          ))}
        </div>
      </div>

      <h2 className="text-[18px] font-bold text-kb-text mb-4 pb-2 border-b-2 border-kb-text">거래내역 조회</h2>

      {/* 조회 조건 */}
      <div className="border border-kb-border-dark rounded-xl p-6 mb-4 space-y-3">
        <div className="flex items-center gap-4">
          <span className="text-[13px] font-medium text-kb-text w-16 flex-shrink-0">조회기간</span>
          <div className="flex gap-1">
            {DATE_PRESETS.map(p => (
              <button
                key={p}
                onClick={() => setDatePreset(p)}
                className="px-4 py-1.5 text-[12px] border rounded-lg transition-colors"
                style={datePreset === p
                  ? { backgroundColor: '#1B3A6B', borderColor: '#1B3A6B', color: '#fff', fontWeight: 700 }
                  : {}}
              >
                {p}
              </button>
            ))}
          </div>
        </div>

        <div className="flex items-center gap-4">
          <span className="text-[13px] font-medium text-kb-text w-16 flex-shrink-0">거래종류</span>
          <div className="flex gap-1">
            {TX_TYPE_OPTS.map(t => (
              <button
                key={t}
                onClick={() => setTxType(t)}
                className="px-4 py-1.5 text-[12px] border rounded-lg transition-colors"
                style={txType === t
                  ? { backgroundColor: '#1B3A6B', borderColor: '#1B3A6B', color: '#fff', fontWeight: 700 }
                  : {}}
              >
                {t}
              </button>
            ))}
          </div>
        </div>

        <div className="flex items-center gap-4">
          <span className="text-[13px] font-medium text-kb-text w-16 flex-shrink-0">내용검색</span>
          <div className="flex gap-2">
            <input
              type="text"
              value={keyword}
              onChange={e => setKeyword(e.target.value)}
              placeholder="거래 내용을 입력하세요"
              className="border border-kb-border px-3 py-1.5 text-[13px] w-60 focus:outline-none"
            />
            {/* 이 화면의 목적은 "AXful 에서 보낸 돈이 도착했는가" 를 확인하는 것이라
                다시 읽어 오는 버튼이 실제로 쓸모 있다. */}
            <button onClick={loadInbound} className="px-5 py-1.5 text-[12px] font-bold text-white hover:brightness-110" style={{ backgroundColor: DAON_NAVY }}>
              조회
            </button>
          </div>
        </div>
      </div>

      {/* 로딩 / 에러 */}
      {loading && (
        <p className="text-center text-[13px] text-kb-text-muted py-8">입금내역을 불러오는 중...</p>
      )}
      {error && (
        <p className="text-center text-[13px] text-kb-red py-8">{error}</p>
      )}

      {!loading && !error && (
        <>
          {/* 거래 건수 요약 */}
          <div className="flex items-center justify-between mb-2">
            <p className="text-[12px] text-kb-text-muted">
              총 <span className="font-bold text-kb-text">{filteredTx.length}</span>건
            </p>
            <div className="flex gap-3 text-[12px] text-kb-text-muted">
              <span>
                입금합계{' '}
                <span className="font-medium text-kb-blue">
                  {formatNumber(filteredTx.reduce((s, t) => s + t.transferAmount, 0))}원
                </span>
              </span>
            </div>
          </div>

          {/* 거래내역 테이블 */}
          <table className="w-full border-collapse text-[13px]">
            <thead>
              <tr className="bg-daon-surface-light border-t-2 border-kb-text">
                <th className="border border-kb-border px-4 py-2 text-left font-semibold text-kb-text">날짜/시간</th>
                <th className="border border-kb-border px-4 py-2 text-left font-semibold text-kb-text">내용</th>
                <th className="border border-kb-border px-4 py-2 text-right font-semibold text-kb-text">입금금액</th>
                <th className="border border-kb-border px-4 py-2 text-center font-semibold text-kb-text">거래점</th>
              </tr>
            </thead>
            <tbody>
              {filteredTx.length === 0 ? (
                <tr>
                  <td colSpan={4} className="border border-kb-border px-4 py-8 text-center text-kb-text-muted">
                    조회된 거래내역이 없습니다.
                  </td>
                </tr>
              ) : (
                filteredTx.map((tx, idx) => (
                  <tr key={tx.paymentInstructionId}
                    className="transition-colors"
                    style={idx === 0 ? { backgroundColor: '#eef2fb' } : {}}>
                    <td className="border border-kb-border px-4 py-2.5 text-kb-text-muted whitespace-nowrap">
                      {formatDatetime(tx.completedAt)}
                    </td>
                    <td className="border border-kb-border px-4 py-2.5 text-kb-text-body">
                      <p className={idx === 0 ? 'font-bold' : ''}>타행이체 입금</p>
                      {senderLabel(tx) && (
                        <p className="text-[11px] text-kb-text-muted">{senderLabel(tx)}</p>
                      )}
                    </td>
                    <td className="border border-kb-border px-4 py-2.5 text-right text-kb-blue font-medium">
                      {formatNumber(tx.transferAmount)}
                    </td>
                    <td className="border border-kb-border px-4 py-2.5 text-center text-kb-text-muted">
                      인터넷
                    </td>
                  </tr>
                ))
              )}
            </tbody>
          </table>
        </>
      )}

      <div className="mt-4 flex justify-center">
        <Link href="/other-bank" className="border border-kb-border px-8 py-2 text-[13px] text-kb-text-body hover:bg-daon-surface-light transition-colors">
          홈으로
        </Link>
      </div>
    </div>
  )
}
