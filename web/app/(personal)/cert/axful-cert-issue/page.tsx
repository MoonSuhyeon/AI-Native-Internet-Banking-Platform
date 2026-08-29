'use client'
import { KB_MINT,KB_PRIMARY,KB_PRIMARY_BG } from '@/lib/theme'

import Link from 'next/link'
import { useState, useEffect, useRef } from 'react'
import { CERT_TERMS, CertTermsModal } from '@/components/cert/CertTermsModal'
import { recordConsents } from '@/lib/consent-api'
import { api } from '@/lib/api'

// ── 공통 UI ───────────────────────────────────────────────────

function NoticeBox({ items }: { items: string[] }) {
  return (
    <div className="bg-kb-primary-bg border border-kb-border rounded-xl px-5 py-4 space-y-2">
      {items.map((n, i) => (
        <div key={i} className="flex items-start gap-2 text-[13px] text-kb-text-body">
          <span className="flex-shrink-0 mt-0.5">·</span>
          {n}
        </div>
      ))}
    </div>
  )
}

function TableRow({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <tr className="border-b border-kb-border last:border-b-0">
      <td className="px-5 py-3.5 font-semibold text-[13px] text-kb-text w-44 whitespace-nowrap align-middle"
        style={{ backgroundColor: KB_PRIMARY_BG }}>
        {label}
      </td>
      <td className="border-l border-kb-border px-5 py-3 align-middle">{children}</td>
    </tr>
  )
}

// ── QR 컴포넌트 ───────────────────────────────────────────────

type QrStatus = 'idle' | 'generating' | 'pending' | 'scanned' | 'approved' | 'expired' | 'error'

function seededRand(seed: number) {
  let s = seed
  return () => { s = (s * 1664525 + 1013904223) & 0xffffffff; return (s >>> 0) / 0xffffffff }
}

function QRCode({ seed }: { seed: number }) {
  const SIZE = 25, CELL = 5
  const rand = seededRand(seed)
  const grid: boolean[][] = Array.from({ length: SIZE }, () => Array(SIZE).fill(false))
  const setFinder = (row: number, col: number) => {
    for (let r = 0; r < 7; r++)
      for (let c = 0; c < 7; c++)
        grid[row + r][col + c] = r === 0 || r === 6 || c === 0 || c === 6 || (r >= 2 && r <= 4 && c >= 2 && c <= 4)
  }
  setFinder(0, 0); setFinder(0, SIZE - 7); setFinder(SIZE - 7, 0)
  for (let i = 8; i < SIZE - 8; i++) { grid[6][i] = i % 2 === 0; grid[i][6] = i % 2 === 0 }
  const reserved = new Set<string>()
  for (let r = 0; r < 9; r++) for (let c = 0; c < 9; c++) reserved.add(`${r},${c}`)
  for (let r = 0; r < 9; r++) for (let c = SIZE - 8; c < SIZE; c++) reserved.add(`${r},${c}`)
  for (let r = SIZE - 8; r < SIZE; r++) for (let c = 0; c < 9; c++) reserved.add(`${r},${c}`)
  for (let i = 6; i < SIZE - 6; i++) { reserved.add(`6,${i}`); reserved.add(`${i},6`) }
  for (let r = 0; r < SIZE; r++)
    for (let c = 0; c < SIZE; c++)
      if (!reserved.has(`${r},${c}`)) grid[r][c] = rand() > 0.5
  const total = SIZE * CELL
  return (
    <svg width={total} height={total} viewBox={`0 0 ${total} ${total}`}>
      <rect width={total} height={total} fill="white" />
      {grid.flatMap((row, r) => row.map((filled, c) =>
        filled ? <rect key={`${r}-${c}`} x={c * CELL} y={r * CELL} width={CELL} height={CELL} fill="#1a1a1a" /> : null
      ))}
    </svg>
  )
}

function QRPlaceholder() {
  return (
    <svg width="125" height="125" viewBox="0 0 100 100" fill="none" opacity="0.25">
      <rect x="5" y="5" width="35" height="35" rx="2" stroke="#333" strokeWidth="3" fill="none"/>
      <rect x="13" y="13" width="19" height="19" fill="#333"/>
      <rect x="60" y="5" width="35" height="35" rx="2" stroke="#333" strokeWidth="3" fill="none"/>
      <rect x="68" y="13" width="19" height="19" fill="#333"/>
      <rect x="5" y="60" width="35" height="35" rx="2" stroke="#333" strokeWidth="3" fill="none"/>
      <rect x="13" y="68" width="19" height="19" fill="#333"/>
      <rect x="60" y="60" width="10" height="10" fill="#333"/>
      <rect x="75" y="60" width="10" height="10" fill="#333"/>
      <rect x="60" y="75" width="10" height="10" fill="#333"/>
      <rect x="75" y="75" width="10" height="10" fill="#333"/>
      <rect x="85" y="85" width="10" height="10" fill="#333"/>
    </svg>
  )
}

// ─────────────────────────────────────────────────────────────

const NOTICES = [
  'AXful Bank 전용 인증서로, 앱 하나로 발급·인증합니다.',
  '클라우드 기반으로 저장되어 PC·모바일에서 동시에 이용 가능합니다.',
  '유효기간은 3년이며 발급 수수료는 무료입니다.',
  'AXful Bank 앱에서 QR코드를 스캔하면 30초 안에 발급이 완료됩니다.',
]

export default function AxfulCertIssuePage() {
  const [step, setStep]                   = useState<1 | 2>(1)
  const [termModalIndex, setTermModalIndex] = useState<number | null>(null)
  const [checked, setChecked]             = useState<boolean[]>(CERT_TERMS.map(() => false))
  const allChecked                        = checked.every(Boolean)
  const [userId, setUserId]               = useState('')
  const [step1Error, setStep1Error]       = useState('')

  const [qrStatus, setQrStatus]           = useState<QrStatus>('idle')
  const [confirmCode, setConfirmCode]     = useState('')
  const [timeLeft, setTimeLeft]           = useState(0)
  const [qrSeed, setQrSeed]               = useState(0)
  const [qrError, setQrError]             = useState('')
  const [issuedCert, setIssuedCert]       = useState<{ serialNumber: string; issuedDate: string; expiryDate: string } | null>(null)
  const tokenHashRef                      = useRef<string>('')

  useEffect(() => {
    if (qrStatus !== 'pending' && qrStatus !== 'scanned') return
    if (timeLeft <= 0) { setQrStatus('expired'); return }
    const t = setTimeout(() => setTimeLeft(s => s - 1), 1000)
    return () => clearTimeout(t)
  }, [qrStatus, timeLeft])

  useEffect(() => {
    if (qrStatus !== 'pending' && qrStatus !== 'scanned') return
    const poll = setInterval(async () => {
      try {
        const { data } = await api.get(`/api/v1/auth/qr-cert/status?token=${tokenHashRef.current}`)
        const s: string = data.data.status
        if (s === 'SCANNED')  setQrStatus('scanned')
        if (s === 'EXPIRED')  { setQrStatus('expired'); clearInterval(poll) }
        if (s === 'APPROVED') {
          clearInterval(poll)
          setQrStatus('approved')
          const { serialNumber, issuedDate, expiryDate } = data.data
          setIssuedCert({ serialNumber, issuedDate, expiryDate })
          localStorage.setItem('issuedAxfulCert', JSON.stringify({
            serialNumber, certType: 'CERT_AXFUL', user: userId,
            expiry: `${expiryDate.slice(0,4)}.${expiryDate.slice(4,6)}.${expiryDate.slice(6,8)}`,
          }))
        }
      } catch {}
    }, 2000)
    return () => clearInterval(poll)
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [qrStatus])

  useEffect(() => {
    if (step === 2) handleGenerate()
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [step])

  async function handleGenerate() {
    setQrStatus('generating'); setQrError(''); setIssuedCert(null)
    try {
      const { data } = await api.post('/api/v1/auth/qr-cert/generate', {})
      tokenHashRef.current = data.data.tokenHash
      setConfirmCode(data.data.confirmCode)
      setQrSeed(Math.random() * 1e9)
      setTimeLeft(179)
      setQrStatus('pending')
    } catch {
      setQrError('QR 코드 생성에 실패했습니다. 잠시 후 다시 시도해 주세요.')
      setQrStatus('error')
    }
  }

  async function goToStep2() {
    if (!allChecked)        { setStep1Error('필수 약관에 모두 동의해 주세요.'); return }
    if (!userId.trim())     { setStep1Error('사용자 ID를 입력해 주세요.'); return }

    // 동의를 남기고 나서 다음 단계로 간다.
    //
    // 기록에 실패했는데 발급이 진행되면 "동의 없이 발급된 건" 이 남는다. 화면에서는
    // 분명히 동의를 받았는데 그것을 증명할 방법이 없는 상태라, 규제 관점에서는
    // 동의를 받지 않은 것과 같다. 그래서 여기서 멈춘다.
    try {
      await recordConsents({
        bizDivCd: 'CERT',
        items: CERT_TERMS.map((t, i) => ({ termsNo: t.termsNo, agreed: checked[i] })),
      })
    } catch {
      setStep1Error('약관 동의 기록에 실패했습니다. 잠시 후 다시 시도해 주세요.')
      return
    }

    setStep1Error(''); setStep(2)
  }

  const mm = String(Math.floor(timeLeft / 60)).padStart(2, '0')
  const ss = String(timeLeft % 60).padStart(2, '0')

  return (
    <div className="max-w-kb-container mx-auto px-6 py-8">

      {/* 브레드크럼 */}
      <div className="flex items-center gap-1 text-[12px] text-kb-text-muted mb-6">
        <Link href="/" className="hover:underline">홈</Link>
        <span>›</span>
        <Link href="/cert" className="hover:underline">인증센터(개인)</Link>
        <span>›</span>
        <span>AXful인증서</span>
        <span>›</span>
        <span className="font-semibold text-kb-text">인증서 발급/재발급</span>
      </div>

      <h1 className="text-[24px] font-bold text-kb-text mb-8">AXful인증서 발급/재발급</h1>

      {/* 단계 표시 */}
      <div className="flex items-center gap-0 mb-8">
        {['약관동의 및 사용자 확인', 'AXful앱 인증'].map((label, i) => (
          <div key={i} className="flex items-center">
            <div className={`flex items-center justify-center rounded-full text-[11px] font-bold border-2 transition-all
              ${step === i + 1
                ? 'w-auto px-3 h-7 text-white'
                : i + 1 < step
                  ? 'w-7 h-7 text-white'
                  : 'w-7 h-7 border-gray-300 text-gray-400'
              }`}
              style={step >= i + 1 ? { backgroundColor: KB_PRIMARY, borderColor: KB_PRIMARY } : {}}>
              {step === i + 1 ? label : i + 1}
            </div>
            {i < 1 && <div className="w-8 h-px bg-gray-300 mx-1" />}
          </div>
        ))}
      </div>

      {/* ── STEP 1 ── */}
      {step === 1 && (
        <div className="space-y-6">
          <NoticeBox items={NOTICES} />

          {/* 약관 */}
          <section className="space-y-3">
            <h2 className="text-[15px] font-bold text-kb-text">약관 동의</h2>
            <div className="border border-kb-border rounded-xl overflow-hidden">
              {/* 전체약관 */}
              <div className="flex items-center justify-between px-5 py-4 border-b border-kb-border bg-kb-primary-bg">
                <button className="flex items-center gap-3 flex-1 text-left"
                  onClick={() => setChecked(CERT_TERMS.map(() => !allChecked))}>
                  <div className={`w-5 h-5 rounded border-2 flex items-center justify-center flex-shrink-0 ${allChecked ? 'border-kb-primary' : 'border-kb-border'}`}
                    style={allChecked ? { backgroundColor: KB_PRIMARY } : {}}>
                    {allChecked && <svg viewBox="0 0 12 10" fill="none" className="w-3 h-2.5"><polyline points="1,5 4.5,8.5 11,1" stroke="white" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"/></svg>}
                  </div>
                  <span className="text-[14px] font-bold text-kb-text">전체약관보기</span>
                </button>
                <button onClick={() => setTermModalIndex(0)}
                  className="border border-kb-border px-3 py-1 text-[13px] font-semibold text-kb-text-body hover:bg-white flex-shrink-0">
                  약관보기 ›
                </button>
              </div>
              {/* 개별 약관 */}
              {CERT_TERMS.map((term, i) => (
                <div key={i} className="flex items-center justify-between px-5 py-4 border-b border-kb-border last:border-b-0">
                  <div className="flex items-center gap-3 min-w-0">
                    <button onClick={() => { const n = [...checked]; n[i] = !n[i]; setChecked(n) }}
                      className={`w-5 h-5 rounded border-2 flex items-center justify-center flex-shrink-0 transition-colors ${checked[i] ? 'border-kb-primary' : 'border-kb-border'}`}
                      style={checked[i] ? { backgroundColor: KB_PRIMARY } : {}}>
                      {checked[i] && <svg viewBox="0 0 12 10" fill="none" className="w-3 h-2.5"><polyline points="1,5 4.5,8.5 11,1" stroke="white" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"/></svg>}
                    </button>
                    <span className="text-[13px] text-kb-text-muted whitespace-pre-line">
                      <span className="font-semibold mr-1" style={{ color: KB_PRIMARY }}>[필수]</span>
                      {term.label}
                    </span>
                  </div>
                  <button onClick={() => setTermModalIndex(i)}
                    className="border border-kb-border px-3 py-1 text-[13px] font-semibold text-kb-text-body hover:bg-kb-primary-bg flex-shrink-0 ml-3">
                    약관보기 ›
                  </button>
                </div>
              ))}
            </div>
          </section>

          {/* 사용자 확인 */}
          <section className="space-y-3">
            <h2 className="text-[15px] font-bold text-kb-text">사용자 확인</h2>
            <div className="border border-kb-border rounded-xl overflow-hidden">
              <table className="w-full text-[13px]">
                <tbody>
                  <TableRow label="사용자 ID">
                    <div className="flex items-center gap-4">
                      <input type="text" value={userId} onChange={e => setUserId(e.target.value)}
                        placeholder="아이디 입력"
                        className="border border-kb-border px-3 py-1.5 w-64 outline-none text-[13px] focus:border-kb-primary" />
                      <Link href="/support/customer-info/id-password"
                        className="text-[13px] text-kb-primary hover:underline whitespace-nowrap">
                        ID를 모르시는 경우↗
                      </Link>
                    </div>
                  </TableRow>
                </tbody>
              </table>
            </div>
            {step1Error && <p className="text-[13px] text-red-500">{step1Error}</p>}
          </section>

          <div className="flex justify-center gap-3 pt-2">
            <Link href="/cert"
              className="border border-kb-border px-12 py-2.5 text-[14px] text-kb-text-body hover:bg-kb-primary-bg transition-colors">
              취소
            </Link>
            <button onClick={goToStep2}
              className="px-12 py-2.5 text-[14px] font-bold text-white rounded-lg hover:opacity-85 transition-opacity"
              style={{ backgroundColor: KB_PRIMARY }}>
              다음
            </button>
          </div>
        </div>
      )}

      {/* ── STEP 2 ── */}
      {step === 2 && (
        <div className="space-y-6">

          {/* QR 카드 */}
          <div className="border border-kb-border rounded-xl overflow-hidden">
            <div className="px-6 py-4 font-semibold text-[14px] text-white" style={{ backgroundColor: KB_PRIMARY }}>
              AXful Bank 앱으로 QR코드를 스캔하세요
            </div>
            <div className="p-8 flex flex-col items-center gap-6">

              <p className="text-[13px] text-kb-text-muted">스마트폰으로 아래 QR코드를 스캔하면 인증서가 발급됩니다.</p>

              {/* QR 이미지 */}
              <div className="relative w-[145px] h-[145px] border-2 border-kb-border flex items-center justify-center bg-white overflow-hidden rounded-lg">
                {(qrStatus === 'idle' || qrStatus === 'generating') && <QRPlaceholder />}
                {(qrStatus === 'pending' || qrStatus === 'scanned' || qrStatus === 'approved') && <QRCode seed={qrSeed} />}
                {qrStatus === 'generating' && (
                  <div className="absolute inset-0 bg-white/80 flex items-center justify-center">
                    <div className="w-8 h-8 rounded-full border-[3px] border-t-transparent animate-spin" style={{ borderColor: KB_MINT, borderTopColor: 'transparent' }} />
                  </div>
                )}
                {qrStatus === 'scanned' && (
                  <div className="absolute inset-0 bg-white/75 flex flex-col items-center justify-center gap-1">
                    <div className="w-8 h-8 rounded-full border-[3px] border-t-transparent animate-spin" style={{ borderColor: KB_MINT, borderTopColor: 'transparent' }} />
                    <span className="text-[11px] font-bold" style={{ color: KB_PRIMARY }}>앱 확인 중</span>
                  </div>
                )}
                {qrStatus === 'approved' && (
                  <div className="absolute inset-0 bg-white/90 flex flex-col items-center justify-center gap-1">
                    <svg width="40" height="40" viewBox="0 0 24 24" fill="none" stroke="#5BC9A8" strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round">
                      <circle cx="12" cy="12" r="10"/><path d="M8 12l3 3 5-5"/>
                    </svg>
                    <span className="text-[11px] font-bold" style={{ color: KB_PRIMARY }}>발급 완료</span>
                  </div>
                )}
                {qrStatus === 'expired' && (
                  <div className="absolute inset-0 bg-white/90 flex flex-col items-center justify-center gap-1">
                    <svg width="36" height="36" viewBox="0 0 24 24" fill="none" stroke="#9CA3AF" strokeWidth="2" strokeLinecap="round">
                      <circle cx="12" cy="12" r="10"/><line x1="12" y1="8" x2="12" y2="13"/><circle cx="12" cy="16" r="0.5" fill="#9CA3AF"/>
                    </svg>
                    <span className="text-[11px] text-gray-400">만료됨</span>
                  </div>
                )}
              </div>

              {/* 확인코드 + 타이머 */}
              {(qrStatus === 'pending' || qrStatus === 'scanned') && (
                <div className="text-center space-y-2">
                  <p className="text-[12px] text-kb-text-muted">앱에서 아래 확인코드를 입력해 주세요</p>
                  <div className="flex items-center justify-center gap-4">
                    <span className="text-[24px] font-bold tracking-[0.35em] text-kb-text px-5 py-2 border border-kb-border rounded-lg bg-kb-primary-bg">
                      {confirmCode}
                    </span>
                    <span className={`text-[16px] font-bold tabular-nums ${timeLeft <= 30 ? 'text-red-500' : 'text-kb-primary'}`}>
                      {mm}:{ss}
                    </span>
                  </div>
                </div>
              )}

              {qrStatus === 'error' && <p className="text-[13px] text-red-500">{qrError}</p>}

              {/* 앱 시뮬레이터 링크 */}
              {(qrStatus === 'pending' || qrStatus === 'scanned') && tokenHashRef.current && (
                <div className="text-center border-t border-kb-border pt-4 w-full">
                  <p className="text-[12px] text-kb-text-muted mb-1.5">앱이 없으신가요?</p>
                  <Link
                    href={`/cert/axful-cert-approve?token=${tokenHashRef.current}`}
                    target="_blank"
                    className="text-[13px] font-semibold hover:underline"
                    style={{ color: KB_PRIMARY }}
                  >
                    AXful Bank 앱 시뮬레이터 열기 →
                  </Link>
                </div>
              )}
            </div>
          </div>

          {/* 발급 완료 */}
          {qrStatus === 'approved' && issuedCert && (
            <div className="border border-kb-border bg-kb-primary-bg px-6 py-8 rounded-xl flex items-center gap-6">
              <div className="w-16 h-16 rounded-full flex items-center justify-center flex-shrink-0"
                style={{ backgroundColor: KB_PRIMARY }}>
                <svg viewBox="0 0 24 24" fill="none" className="w-8 h-8" stroke="white" strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round">
                  <polyline points="20,6 9,17 4,12"/>
                </svg>
              </div>
              <div className="space-y-1.5">
                <p className="text-[16px] font-bold" style={{ color: KB_PRIMARY }}>AXful인증서 발급이 완료되었습니다.</p>
                <p className="text-[13px] text-kb-text-muted">일련번호: <span className="font-medium text-kb-text">{issuedCert.serialNumber}</span></p>
                <p className="text-[13px] text-kb-text-muted">
                  유효기간: {issuedCert.expiryDate.slice(0,4)}.{issuedCert.expiryDate.slice(4,6)}.{issuedCert.expiryDate.slice(6,8)} 까지 (3년)
                </p>
                <p className="text-[12px] text-kb-text-muted">로그인 화면의 &lsquo;AXful인증서&rsquo; 탭에서 사용할 수 있습니다.</p>
              </div>
            </div>
          )}

          {/* 버튼 */}
          <div className="flex justify-center gap-3">
            {qrStatus === 'approved' ? (
              <>
                <Link href="/login"
                  className="px-12 py-2.5 text-[14px] font-bold text-white rounded-lg hover:opacity-85 transition-opacity"
                  style={{ backgroundColor: KB_PRIMARY }}>
                  로그인 화면으로
                </Link>
                <Link href="/cert"
                  className="border border-kb-border px-12 py-2.5 text-[14px] text-kb-text-body hover:bg-kb-primary-bg transition-colors">
                  인증서 안내로
                </Link>
              </>
            ) : qrStatus === 'expired' || qrStatus === 'error' ? (
              <button onClick={handleGenerate}
                className="px-12 py-2.5 text-[14px] font-bold text-white rounded-lg hover:opacity-85 transition-opacity"
                style={{ backgroundColor: KB_PRIMARY }}>
                QR코드 재생성
              </button>
            ) : (
              <button onClick={() => setStep(1)}
                className="border border-kb-border px-12 py-2.5 text-[14px] text-kb-text-body hover:bg-kb-primary-bg transition-colors">
                이전
              </button>
            )}
          </div>

        </div>
      )}

      {termModalIndex !== null && (
        <CertTermsModal
          termIndex={termModalIndex}
          onClose={() => setTermModalIndex(null)}
          onAgreeOne={(index) => {
            if (index === termModalIndex) {
              const n = [...checked]; n[index] = true; setChecked(n)
            } else {
              setTermModalIndex(index)
            }
          }}
          onAgreeAll={() => { setChecked(CERT_TERMS.map(() => true)); setTermModalIndex(null) }}
        />
      )}
    </div>
  )
}
