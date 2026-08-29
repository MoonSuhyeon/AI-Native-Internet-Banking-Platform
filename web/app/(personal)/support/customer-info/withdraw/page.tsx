'use client'
import { KB_PRIMARY } from '@/lib/theme'
import { recordConsents } from '@/lib/consent-api'

import Link from 'next/link'
import { useState } from 'react'
import { useRouter } from 'next/navigation'
import { api } from '@/lib/api'

const STEPS = ['탈퇴안내', '본인확인', '탈퇴완료']

const WITHDRAW_NOTICES = [
  '회원 탈퇴 시 고객님의 모든 인터넷뱅킹 서비스 이용이 즉시 중단됩니다.',
  '탈퇴 처리 후에는 동일한 아이디(ID)로 재가입이 불가능합니다.',
  '보유 계좌 및 금융거래 내역은 관련 법령에 따라 일정 기간 보관됩니다.',
  '진행 중인 대출 또는 미결제 상품이 있는 경우 탈퇴가 제한될 수 있습니다.',
  '개인정보는 전자금융거래법 및 개인정보보호법에 따라 거래 종료일로부터 5년간 보관됩니다.',
  '인증서(AXful 금융인증서, 공동인증서)는 별도로 폐기 처리됩니다.',
]

export default function WithdrawPage() {
  const router = useRouter()
  const [step, setStep] = useState(0)

  // Step 0 — 탈퇴 안내 동의
  const [agreed, setAgreed] = useState(false)

  // Step 1 — 본인확인 (비밀번호)
  const [password, setPassword] = useState('')
  const [showPw, setShowPw]     = useState(false)
  const [confirmText, setConfirmText] = useState('')
  const [withdrawError, setWithdrawError] = useState('')
  const [loading, setLoading] = useState(false)

  async function handleStep0() {
    if (!agreed) { alert('탈퇴 안내 사항에 동의해주세요.'); return }
    // 되돌릴 수 없는 처리라 "몰랐다" 가 뒤에 쟁점이 된다. 안내를 확인했다는 사실을
    // 남긴다 — 동의라기보다 확인 기록이지만, 남겨야 할 자리는 같다.
    try {
      await recordConsents({
        bizDivCd: 'CLOSE',
        items: [{ termsNo: 'CLOSE-001', agreed: true }],
      })
    } catch {
      alert('안내 확인 기록에 실패했습니다. 잠시 후 다시 시도해 주세요.')
      return
    }
    setStep(1)
  }

  async function handleWithdraw() {
    if (!password) { alert('비밀번호를 입력해주세요.'); return }
    if (confirmText !== '회원탈퇴') {
      alert('"회원탈퇴"를 정확히 입력해주세요.'); return
    }

    setLoading(true)
    setWithdrawError('')
    try {
      await api.post('/api/v1/customers/me/withdraw', { currentPassword: password })
      // 세션 토큰 제거
      localStorage.removeItem('accessToken')
      localStorage.removeItem('access_token')
      localStorage.removeItem('user')
      setStep(2)
    } catch (err: unknown) {
      const axiosErr = err as { response?: { data?: { message?: string } } }
      setWithdrawError(axiosErr.response?.data?.message || '회원 탈퇴에 실패했습니다. 잠시 후 다시 시도해주세요.')
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
          <span>고객센터</span>
          <span>›</span>
          <span>고객정보관리</span>
          <span>›</span>
          <span className="font-semibold text-kb-text">회원탈퇴</span>
        </div>

        <main className="w-full">
          <div className="flex items-center justify-between mb-5">
            <h1 className="text-[22px] font-bold text-kb-text">회원탈퇴</h1>
            <div className="flex items-center gap-1">
              {STEPS.map((s, i) => (
                <div key={s} className="flex items-center gap-1">
                  <div
                    className={`flex items-center gap-1.5 px-3 py-1 rounded-full text-[12px] font-bold
                      ${step === i ? 'text-white' : step > i ? 'bg-kb-mint text-white' : 'border border-kb-border text-kb-text-muted'}`}
                    style={step === i ? { backgroundColor: KB_PRIMARY } : {}}
                  >
                    <span>{i + 1}.</span><span>{s}</span>
                  </div>
                  {i < STEPS.length - 1 && <span className="text-kb-border text-[10px]">›</span>}
                </div>
              ))}
            </div>
          </div>

          {/* ── STEP 0: 탈퇴 안내 ── */}
          {step === 0 && (
            <div>
              {/* 경고 배너 */}
              <div className="flex items-start gap-3 border border-red-200 bg-red-50 px-5 py-4 mb-5 rounded">
                <svg viewBox="0 0 24 24" fill="none" className="w-5 h-5 flex-shrink-0 mt-0.5" stroke="#DC2626" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
                  <path d="M10.29 3.86L1.82 18a2 2 0 001.71 3h16.94a2 2 0 001.71-3L13.71 3.86a2 2 0 00-3.42 0z"/>
                  <line x1="12" y1="9" x2="12" y2="13"/><line x1="12" y1="17" x2="12.01" y2="17"/>
                </svg>
                <p className="text-[13px] text-red-700 font-semibold">
                  회원탈퇴 처리 후에는 되돌릴 수 없습니다. 아래 안내 사항을 반드시 확인하세요.
                </p>
              </div>

              {/* 탈퇴 안내 목록 */}
              <div className="border border-kb-border mb-5">
                <div className="px-5 py-3 bg-kb-primary-bg border-b border-kb-border">
                  <p className="text-[14px] font-bold text-kb-text">탈퇴 전 확인사항</p>
                </div>
                <ul className="px-5 py-4 space-y-3">
                  {WITHDRAW_NOTICES.map((notice, i) => (
                    <li key={i} className="flex items-start gap-2 text-[13px] text-kb-text-body">
                      <span className="flex-shrink-0 font-bold" style={{ color: KB_PRIMARY }}>·</span>
                      {notice}
                    </li>
                  ))}
                </ul>
              </div>

              {/* 동의 체크박스 */}
              <div className="border border-kb-border px-5 py-4 mb-6 flex items-center gap-3">
                <button
                  onClick={() => setAgreed(!agreed)}
                  className={`w-5 h-5 rounded border-2 flex items-center justify-center flex-shrink-0 transition-colors
                    ${agreed ? 'border-kb-primary' : 'border-kb-border'}`}
                  style={agreed ? { backgroundColor: KB_PRIMARY } : {}}
                >
                  {agreed && (
                    <svg viewBox="0 0 12 10" fill="none" className="w-3 h-2.5">
                      <polyline points="1,5 4.5,8.5 11,1" stroke="white" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"/>
                    </svg>
                  )}
                </button>
                <span className="text-[13px] text-kb-text">
                  위 안내 사항을 모두 확인하였으며, 회원탈퇴에 동의합니다.
                </span>
              </div>

              <div className="flex justify-center gap-3">
                <Link href="/"
                  className="border border-kb-border px-14 py-3 text-[14px] text-kb-text-body hover:bg-kb-primary-bg">
                  취소
                </Link>
                <button onClick={handleStep0}
                  className="px-14 py-3 text-[14px] font-bold text-white rounded-lg hover:opacity-85 transition-opacity" style={{ backgroundColor: KB_PRIMARY }}>
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
                <p>· 탈퇴 처리 후에는 즉시 로그아웃되며 인터넷뱅킹 서비스 이용이 중단됩니다.</p>
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
                        <button
                          type="button"
                          onClick={() => setShowPw(v => !v)}
                          className="absolute right-2.5 text-kb-text-muted hover:text-kb-text"
                          tabIndex={-1}
                        >
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
                      탈퇴 확인
                    </td>
                    <td className="border border-kb-border px-4 py-3">
                      <div className="space-y-1">
                        <input
                          type="text"
                          value={confirmText}
                          onChange={e => setConfirmText(e.target.value)}
                          placeholder='확인을 위해 "회원탈퇴"를 입력해주세요'
                          className="border border-kb-border px-3 py-1.5 w-64 outline-none text-[13px]"
                        />
                        {confirmText && confirmText !== '회원탈퇴' && (
                          <p className="text-[12px] text-red-500">&ldquo;회원탈퇴&rdquo;를 정확히 입력해주세요.</p>
                        )}
                        {confirmText === '회원탈퇴' && (
                          <p className="text-[12px] font-semibold" style={{ color: KB_PRIMARY }}>✓ 확인되었습니다.</p>
                        )}
                      </div>
                    </td>
                  </tr>
                </tbody>
              </table>

              {withdrawError && (
                <p className="text-center text-[13px] text-red-500 mb-4">{withdrawError}</p>
              )}

              <div className="flex justify-center gap-3">
                <button onClick={() => { setStep(0); setPassword(''); setConfirmText(''); setWithdrawError('') }}
                  className="border border-kb-border px-14 py-3 text-[14px] text-kb-text-body hover:bg-kb-primary-bg">
                  취소
                </button>
                <button
                  onClick={handleWithdraw}
                  disabled={loading}
                  className="px-14 py-3 text-[14px] font-bold text-white rounded-lg hover:opacity-85 transition-opacity disabled:opacity-50" style={{ backgroundColor: KB_PRIMARY }}>
                  {loading ? '처리 중...' : '회원탈퇴'}
                </button>
              </div>
            </div>
          )}

          {/* ── STEP 2: 탈퇴완료 ── */}
          {step === 2 && (
            <div>
              <div className="border border-kb-border bg-kb-primary-bg px-6 py-8 mb-6 flex items-center gap-6">
                <div className="w-16 h-16 rounded-full bg-kb-border flex items-center justify-center flex-shrink-0">
                  <svg viewBox="0 0 24 24" fill="none" className="w-8 h-8" stroke="white" strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round">
                    <polyline points="20,6 9,17 4,12"/>
                  </svg>
                </div>
                <div>
                  <p className="text-[16px] font-bold text-kb-text mb-1">회원탈퇴가 완료되었습니다.</p>
                  <p className="text-[13px] text-kb-text-muted">
                    그동안 AXful Bank를 이용해 주셔서 감사합니다.<br />
                    개인정보는 관련 법령에 따라 보관 후 안전하게 파기됩니다.
                  </p>
                </div>
              </div>

              <table className="w-full text-[13px] border-collapse mb-6">
                <tbody>
                  {[
                    { label: '처리상태', value: '탈퇴 완료' },
                    { label: '처리일시', value: new Date().toLocaleString('ko-KR', { year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' }) },
                    { label: '개인정보 보관기간', value: '거래 종료일로부터 5년' },
                  ].map(row => (
                    <tr key={row.label} className="border-b border-kb-border last:border-b-0">
                      <td className="bg-kb-primary-bg border border-kb-border px-4 py-3 font-semibold text-kb-text w-40 whitespace-nowrap">{row.label}</td>
                      <td className="border border-kb-border px-4 py-3">{row.value}</td>
                    </tr>
                  ))}
                </tbody>
              </table>

              <div className="border border-kb-border bg-kb-primary-bg px-5 py-4 mb-6 text-[12px] text-kb-text-muted space-y-1">
                <p>· 보유하신 계좌 및 상품 관련 문의는 AXful Bank 영업점 또는 고객센터(1588-0000)로 연락주세요.</p>
                <p>· 재가입을 원하시면 온라인 고객 신규가입 또는 가까운 영업점을 방문하세요.</p>
              </div>

              <div className="flex justify-center gap-3">
                <button
                  onClick={() => router.push('/')}
                  className="px-10 py-3 text-[14px] font-bold text-white rounded-lg hover:opacity-85 transition-opacity" style={{ backgroundColor: KB_PRIMARY }}>
                  메인으로
                </button>
                <Link href="/support/customer-info/online-join"
                  className="border border-kb-border px-10 py-3 text-[14px] font-semibold text-kb-text-body hover:bg-kb-primary-bg">
                  재가입하기
                </Link>
              </div>
            </div>
          )}
        </main>
      </div>
    </div>
  )
}
