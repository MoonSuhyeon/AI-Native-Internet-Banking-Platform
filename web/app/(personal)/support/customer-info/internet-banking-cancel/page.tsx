'use client'
import { KB_PRIMARY } from '@/lib/theme'
import { recordConsents } from '@/lib/consent-api'

import Link from 'next/link'
import { useState } from 'react'
import { useRouter } from 'next/navigation'
import { api } from '@/lib/api'

const STEPS = ['해지안내', '본인확인', '해지완료']

const CANCEL_NOTICES = [
  '인터넷뱅킹 해지 후에는 온라인 조회·이체·신청 등 모든 인터넷뱅킹 서비스 이용이 즉시 중단됩니다.',
  '보유 중인 계좌, 예금, 대출 상품 등 금융 거래는 계속 유지됩니다.',
  '해지 후 재이용을 원하실 경우 온라인 재신청 또는 가까운 영업점 방문이 필요합니다.',
  'AXful 금융인증서 및 공동인증서는 해지 처리 시 자동으로 폐기됩니다.',
  '자동이체, 예약이체 등 등록된 이체 서비스는 해지 전에 반드시 취소하시기 바랍니다.',
  '해지 처리 후에는 해당 아이디(ID)로 인터넷뱅킹 로그인이 불가능합니다.',
]

export default function InternetBankingCancelPage() {
  const router = useRouter()

  const [step, setStep]         = useState(0)
  const [agreed, setAgreed]     = useState(false)

  const [password, setPassword] = useState('')
  const [showPw, setShowPw]     = useState(false)
  const [confirmText, setConfirmText] = useState('')
  const [cancelError, setCancelError] = useState('')
  const [loading, setLoading]   = useState(false)

  async function handleStep0() {
    if (!agreed) { alert('해지 안내 사항에 동의해주세요.'); return }
    // 되돌릴 수 없는 처리라 "몰랐다" 가 뒤에 쟁점이 된다. 안내를 확인했다는 사실을
    // 남긴다 — 동의라기보다 확인 기록이지만, 남겨야 할 자리는 같다.
    try {
      await recordConsents({
        bizDivCd: 'CLOSE',
        items: [{ termsNo: 'CLOSE-002', agreed: true }],
      })
    } catch {
      alert('안내 확인 기록에 실패했습니다. 잠시 후 다시 시도해 주세요.')
      return
    }
    setStep(1)
  }

  async function handleCancel() {
    if (!password) { alert('비밀번호를 입력해주세요.'); return }
    if (confirmText !== '인터넷뱅킹해지') {
      alert('"인터넷뱅킹해지"를 정확히 입력해주세요.'); return
    }
    setLoading(true)
    setCancelError('')
    try {
      await api.post('/api/v1/customers/me/internet-banking/cancel', { currentPassword: password })
      localStorage.removeItem('accessToken')
      localStorage.removeItem('access_token')
      localStorage.removeItem('refreshToken')
      localStorage.removeItem('sessionExpiry')
      localStorage.removeItem('user')
      localStorage.removeItem('customerId')
      setStep(2)
    } catch (err: unknown) {
      const e = err as { response?: { data?: { message?: string } } }
      setCancelError(e.response?.data?.message || '인터넷뱅킹 해지에 실패했습니다. 잠시 후 다시 시도해주세요.')
    } finally {
      setLoading(false)
    }
  }

  return (
    <div className="min-h-screen bg-white">
      <div className="max-w-kb-container mx-auto px-6 py-8">
        {/* 브레드크럼 */}
        <div className="flex items-center gap-1 text-[12px] text-kb-text-muted mb-6">
          <Link href="/" className="hover:underline">홈</Link>
          <span>›</span>
          <span>뱅킹관리</span>
          <span>›</span>
          <span>인터넷 뱅킹관리</span>
          <span>›</span>
          <span className="font-semibold text-kb-text">인터넷뱅킹 해지</span>
        </div>

        <main className="w-full">
          {/* 제목 + 스텝 */}
          <div className="flex items-center justify-between mb-5">
            <h1 className="text-[22px] font-bold text-kb-text">인터넷뱅킹 해지</h1>
            <div className="flex items-center gap-1">
              {STEPS.map((s, i) => (
                <div key={s} className="flex items-center gap-1">
                  <div className={`flex items-center gap-1.5 px-3 py-1 rounded-full text-[12px] font-bold
                    ${step === i ? 'text-white' : step > i ? 'bg-kb-mint text-white' : 'border border-kb-border text-kb-text-muted'}`}
                    style={step === i ? { backgroundColor: KB_PRIMARY } : {}}>
                    <span>{i + 1}.</span><span>{s}</span>
                  </div>
                  {i < STEPS.length - 1 && <span className="text-kb-border text-[10px]">›</span>}
                </div>
              ))}
            </div>
          </div>

          {/* ── STEP 0: 해지 안내 ── */}
          {step === 0 && (
            <div>
              <div className="flex items-start gap-3 border border-amber-200 bg-amber-50 px-5 py-4 mb-5 rounded">
                <svg viewBox="0 0 24 24" fill="none" className="w-5 h-5 flex-shrink-0 mt-0.5" stroke="#D97706" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
                  <path d="M10.29 3.86L1.82 18a2 2 0 001.71 3h16.94a2 2 0 001.71-3L13.71 3.86a2 2 0 00-3.42 0z"/>
                  <line x1="12" y1="9" x2="12" y2="13"/><line x1="12" y1="17" x2="12.01" y2="17"/>
                </svg>
                <p className="text-[13px] text-amber-800 font-semibold">
                  인터넷뱅킹 해지 후에는 온라인 서비스 이용이 즉시 중단됩니다. 아래 안내 사항을 확인하세요.
                </p>
              </div>

              <div className="border border-kb-border mb-5">
                <div className="px-5 py-3 bg-kb-primary-bg border-b border-kb-border">
                  <p className="text-[14px] font-bold text-kb-text">해지 전 확인사항</p>
                </div>
                <ul className="px-5 py-4 space-y-3">
                  {CANCEL_NOTICES.map((notice, i) => (
                    <li key={i} className="flex items-start gap-2 text-[13px] text-kb-text-body">
                      <span className="flex-shrink-0 font-bold" style={{ color: KB_PRIMARY }}>·</span>
                      {notice}
                    </li>
                  ))}
                </ul>
              </div>

              {/* 재이용 안내 박스 */}
              <div className="border border-kb-border bg-kb-primary-surface px-5 py-4 mb-5 text-[13px] text-kb-text-body space-y-1">
                <p className="font-semibold text-kb-text mb-1">재이용 방법 안내</p>
                <p>· <span className="font-medium">온라인</span>: 뱅킹관리 &gt; 온라인고객 신규가입에서 재신청</p>
                <p>· <span className="font-medium">영업점</span>: 가까운 AXful Bank 영업점 방문 신청</p>
              </div>

              <div className="border border-kb-border px-5 py-4 mb-6 flex items-center gap-3">
                <button
                  onClick={() => setAgreed(!agreed)}
                  className={`w-5 h-5 rounded border-2 flex items-center justify-center flex-shrink-0 transition-colors
                    ${agreed ? 'border-kb-primary' : 'border-kb-border'}`}
                  style={agreed ? { backgroundColor: KB_PRIMARY } : {}}>
                  {agreed && (
                    <svg viewBox="0 0 12 10" fill="none" className="w-3 h-2.5">
                      <polyline points="1,5 4.5,8.5 11,1" stroke="white" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"/>
                    </svg>
                  )}
                </button>
                <span className="text-[13px] text-kb-text">
                  위 안내 사항을 모두 확인하였으며, 인터넷뱅킹 해지에 동의합니다.
                </span>
              </div>

              <div className="flex justify-center gap-3">
                <Link href="/"
                  className="border border-kb-border px-14 py-3 text-[14px] text-kb-text-body hover:bg-kb-primary-bg">
                  취소
                </Link>
                <button onClick={handleStep0}
                  className="px-14 py-3 text-[14px] font-bold text-white rounded-lg hover:opacity-85 transition-opacity"
                  style={{ backgroundColor: KB_PRIMARY }}>
                  다음
                </button>
              </div>
            </div>
          )}

          {/* ── STEP 1: 본인확인 ── */}
          {step === 1 && (
            <div>
              <div className="border border-kb-border bg-kb-primary-bg px-5 py-4 mb-6 text-[13px] text-kb-text-body space-y-1.5">
                <p>· 본인 확인을 위해 현재 사용 중인 비밀번호를 입력해주세요.</p>
                <p>· 비밀번호 5회 오류 시 계정이 잠길 수 있습니다.</p>
                <p>· 해지 처리 후에는 즉시 로그아웃되며 인터넷뱅킹 서비스 이용이 중단됩니다.</p>
              </div>

              <table className="w-full text-[13px] border-collapse mb-6">
                <tbody>
                  <tr className="border-b border-kb-border">
                    <td className="bg-kb-primary-bg border border-kb-border px-4 py-3 font-semibold text-kb-text w-36 whitespace-nowrap">
                      사용자암호
                    </td>
                    <td className="border border-kb-border px-4 py-3">
                      <div className="relative inline-flex items-center">
                        <input
                          type={showPw ? 'text' : 'password'}
                          value={password}
                          onChange={e => setPassword(e.target.value)}
                          placeholder="현재 사용자암호 입력"
                          className="border border-kb-border px-3 py-1.5 pr-9 w-64 outline-none text-[13px]"
                        />
                        <button type="button" onClick={() => setShowPw(v => !v)}
                          className="absolute right-2.5 text-kb-text-muted hover:text-kb-text" tabIndex={-1}>
                          {showPw ? (
                            <svg viewBox="0 0 24 24" fill="none" className="w-4 h-4" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
                              <path d="M17.94 17.94A10.07 10.07 0 0112 20c-7 0-11-8-11-8a18.45 18.45 0 015.06-5.94"/>
                              <path d="M9.9 4.24A9.12 9.12 0 0112 4c7 0 11 8 11 8a18.5 18.5 0 01-2.16 3.19"/>
                              <line x1="1" y1="1" x2="23" y2="23"/>
                            </svg>
                          ) : (
                            <svg viewBox="0 0 24 24" fill="none" className="w-4 h-4" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
                              <path d="M1 12s4-8 11-8 11 8 11 8-4 8-11 8-11-8-11-8z"/>
                              <circle cx="12" cy="12" r="3"/>
                            </svg>
                          )}
                        </button>
                      </div>
                    </td>
                  </tr>
                  <tr>
                    <td className="bg-kb-primary-bg border border-kb-border px-4 py-3 font-semibold text-kb-text whitespace-nowrap">
                      해지 확인
                    </td>
                    <td className="border border-kb-border px-4 py-3">
                      <div className="space-y-1">
                        <input
                          type="text"
                          value={confirmText}
                          onChange={e => setConfirmText(e.target.value)}
                          placeholder='확인을 위해 "인터넷뱅킹해지"를 입력해주세요'
                          className="border border-kb-border px-3 py-1.5 w-72 outline-none text-[13px]"
                        />
                        {confirmText && confirmText !== '인터넷뱅킹해지' && (
                          <p className="text-[12px] text-red-500">&ldquo;인터넷뱅킹해지&rdquo;를 정확히 입력해주세요.</p>
                        )}
                        {confirmText === '인터넷뱅킹해지' && (
                          <p className="text-[12px] font-semibold" style={{ color: KB_PRIMARY }}>✓ 확인되었습니다.</p>
                        )}
                      </div>
                    </td>
                  </tr>
                </tbody>
              </table>

              {cancelError && (
                <p className="text-center text-[13px] text-red-500 mb-4">{cancelError}</p>
              )}

              <div className="flex justify-center gap-3">
                <button
                  onClick={() => { setStep(0); setPassword(''); setConfirmText(''); setCancelError('') }}
                  className="border border-kb-border px-14 py-3 text-[14px] text-kb-text-body hover:bg-kb-primary-bg">
                  취소
                </button>
                <button
                  onClick={handleCancel}
                  disabled={loading}
                  className="px-14 py-3 text-[14px] font-bold text-white rounded-lg hover:opacity-85 transition-opacity disabled:opacity-50"
                  style={{ backgroundColor: KB_PRIMARY }}>
                  {loading ? '처리 중...' : '인터넷뱅킹 해지'}
                </button>
              </div>
            </div>
          )}

          {/* ── STEP 2: 해지완료 ── */}
          {step === 2 && (
            <div>
              <div className="border border-kb-border bg-kb-primary-bg px-6 py-8 mb-6 flex items-center gap-6">
                <div className="w-16 h-16 rounded-full flex items-center justify-center flex-shrink-0"
                  style={{ backgroundColor: KB_PRIMARY }}>
                  <svg viewBox="0 0 24 24" fill="none" className="w-8 h-8" stroke="white" strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round">
                    <polyline points="20,6 9,17 4,12"/>
                  </svg>
                </div>
                <div>
                  <p className="text-[16px] font-bold text-kb-text mb-1">인터넷뱅킹 해지가 완료되었습니다.</p>
                  <p className="text-[13px] text-kb-text-muted">
                    그동안 AXful Bank 인터넷뱅킹을 이용해 주셔서 감사합니다.<br />
                    보유 중인 계좌 및 금융 상품은 계속 유지됩니다.
                  </p>
                </div>
              </div>

              <table className="w-full text-[13px] border-collapse mb-6">
                <tbody>
                  {[
                    { label: '처리상태',      value: '인터넷뱅킹 해지 완료' },
                    { label: '처리일시',      value: new Date().toLocaleString('ko-KR', { year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' }) },
                    { label: '재이용 방법',   value: '온라인 재신청 또는 영업점 방문' },
                  ].map(row => (
                    <tr key={row.label} className="border-b border-kb-border last:border-b-0">
                      <td className="bg-kb-primary-bg border border-kb-border px-4 py-3 font-semibold text-kb-text w-40 whitespace-nowrap">{row.label}</td>
                      <td className="border border-kb-border px-4 py-3">{row.value}</td>
                    </tr>
                  ))}
                </tbody>
              </table>

              <div className="border border-kb-border bg-kb-primary-bg px-5 py-4 mb-6 text-[12px] text-kb-text-muted space-y-1">
                <p>· 계좌 및 상품 관련 문의는 AXful Bank 영업점 또는 고객센터(1588-0000)로 연락해 주세요.</p>
                <p>· 재이용을 원하시면 온라인 신규가입 또는 가까운 영업점을 방문하세요.</p>
              </div>

              <div className="flex justify-center gap-3">
                <button
                  onClick={() => router.push('/')}
                  className="px-10 py-3 text-[14px] font-bold text-white rounded-lg hover:opacity-85 transition-opacity"
                  style={{ backgroundColor: KB_PRIMARY }}>
                  메인으로
                </button>
                <Link href="/support/customer-info/online-join"
                  className="border border-kb-border px-10 py-3 text-[14px] font-semibold text-kb-text-body hover:bg-kb-primary-bg">
                  재신청하기
                </Link>
              </div>
            </div>
          )}
        </main>
      </div>
    </div>
  )
}
