'use client'
import { KB_MINT, KB_PRIMARY, KB_PRIMARY_BG, KB_PRIMARY_BORDER, KB_PRIMARY_DARK, KB_PRIMARY_SURFACE } from '@/lib/theme'

import { useState, useEffect, useRef } from 'react'
import Link from 'next/link'
import { api } from '@/lib/api'

type LoginTab = 'kb인증서' | '공동금융인증서' | '아이디'

export default function LoginPage() {
  const [tab, setTab] = useState<LoginTab>('kb인증서')
  const [showLoginSetting, setShowLoginSetting] = useState(false)

  // 저장된 선호 로그인 방식이 있으면 진입 시 해당 탭을 기본 선택
  useEffect(() => {
    const pref = localStorage.getItem('preferredLoginMethod')
    if (pref && METHOD_TAB_MAP[pref]) setTab(METHOD_TAB_MAP[pref])
  }, [])

  return (
    <>
      {/* 페이지 타이틀 바 */}
      <div className="bg-white border-b border-kb-border">
        <div className="max-w-kb-container mx-auto px-6 py-4">
          <h1 className="text-2xl font-bold text-kb-text">로그인</h1>
        </div>
      </div>

      {/* 본문 */}
      <div className="py-6" style={{ backgroundColor: KB_PRIMARY_BG }}>
        <div className="w-full max-w-[1100px] mx-auto">

          {/* 로그인 카드 */}
          <div className="bg-white shadow-sm">

            {/* 탭 */}
            <div className="flex border-b border-kb-border">
              <TabButton active={tab === 'kb인증서'} onClick={() => setTab('kb인증서')}>
                <KBShieldIcon />
                AXful인증서
              </TabButton>
              <TabButton active={tab === '공동금융인증서'} onClick={() => setTab('공동금융인증서')}>
                공동·금융인증서
              </TabButton>
              <TabButton active={tab === '아이디'} onClick={() => setTab('아이디')}>
                아이디 로그인
              </TabButton>
            </div>

            {/* 탭 컨텐츠 */}
            <div className="min-h-[280px] flex flex-col justify-center">
              {tab === 'kb인증서' && <KBCertTab />}
              {tab === '공동금융인증서' && <CommonCertTab />}
              {tab === '아이디' && <IdLoginTab />}
            </div>

            {/* 하단 바로가기 */}
            <div className="border-t border-kb-border">
              <div className="grid grid-cols-4">
                {[
                  { icon: <KBShieldIcon size={20} />, label: 'AXful인증서 발급', href: '/cert-cps' as string | null },
                  { icon: <svg viewBox="0 0 24 24" fill="none" className="w-5 h-5" stroke="currentColor" strokeWidth="1.8" style={{ color: KB_PRIMARY }}><rect x="5" y="11" width="14" height="10" rx="2"/><path d="M8 11V7a4 4 0 018 0v4"/></svg>, label: '인증센터', href: '/cert' as string | null },
                  { icon: <svg viewBox="0 0 24 24" fill="none" className="w-5 h-5" stroke="currentColor" strokeWidth="1.8" style={{ color: KB_PRIMARY }}><circle cx="12" cy="12" r="3"/><path d="M19.4 15a1.65 1.65 0 00.33 1.82l.06.06a2 2 0 010 2.83 2 2 0 01-2.83 0l-.06-.06a1.65 1.65 0 00-1.82-.33 1.65 1.65 0 00-1 1.51V21a2 2 0 01-4 0v-.09A1.65 1.65 0 009 19.4a1.65 1.65 0 00-1.82.33l-.06.06a2 2 0 01-2.83-2.83l.06-.06A1.65 1.65 0 004.68 15a1.65 1.65 0 00-1.51-1H3a2 2 0 010-4h.09A1.65 1.65 0 004.6 9a1.65 1.65 0 00-.33-1.82l-.06-.06a2 2 0 012.83-2.83l.06.06A1.65 1.65 0 009 4.68a1.65 1.65 0 001-1.51V3a2 2 0 014 0v.09a1.65 1.65 0 001 1.51 1.65 1.65 0 001.82-.33l.06-.06a2 2 0 012.83 2.83l-.06.06A1.65 1.65 0 0019.4 9a1.65 1.65 0 001.51 1H21a2 2 0 010 4h-.09a1.65 1.65 0 00-1.51 1z"/></svg>, label: '로그인 설정', href: null },
                  { icon: <svg viewBox="0 0 24 24" fill="none" className="w-5 h-5" stroke="currentColor" strokeWidth="1.8" style={{ color: KB_PRIMARY }}><circle cx="12" cy="12" r="10"/><line x1="12" y1="16" x2="12" y2="12"/><line x1="12" y1="8" x2="12.01" y2="8"/></svg>, label: '인증서 이용 안내', href: '/banking/first-visit' as string | null },
                ].map((item, i) => (
                  item.href === null ? (
                    <button
                      key={item.label}
                      onClick={() => setShowLoginSetting(true)}
                      className={`flex flex-col items-center gap-1.5 py-4 text-sm text-kb-text-body
                                  hover:bg-kb-primary-bg transition-colors w-full
                                  ${i > 0 ? 'border-l border-kb-border' : ''}`}
                    >
                      <div className="flex items-center justify-center w-6 h-6">{item.icon}</div>
                      <span>{item.label}</span>
                    </button>
                  ) : (
                    <Link
                      key={item.label}
                      href={item.href}
                      className={`flex flex-col items-center gap-1.5 py-4 text-sm text-kb-text-body
                                  hover:bg-kb-primary-bg transition-colors
                                  ${i > 0 ? 'border-l border-kb-border' : ''}`}
                    >
                      <div className="flex items-center justify-center w-6 h-6">{item.icon}</div>
                      <span>{item.label}</span>
                    </Link>
                  )
                ))}
              </div>
            </div>
          </div>

          {showLoginSetting && <LoginSettingModal onApply={setTab} onClose={() => setShowLoginSetting(false)} />}

          {/* 카드 하단 안내 */}
          <div className="mt-4 px-1 space-y-1">
            <p className="text-sm" style={{ color: KB_PRIMARY }}>
              • 인터넷뱅킹 종료 시, 안전한 금융거래를 위하여 반드시 [로그아웃]버튼을 눌러 종료하시기 바랍니다.
            </p>
            <p className="text-sm" style={{ color: KB_PRIMARY }}>
              • 로그인설정 버튼을 통해 자주 쓰는 로그인 방식을 설정해두면 더 간편하게 로그인 하실 수 있습니다.
            </p>
          </div>
        </div>
      </div>
    </>
  )
}

/* ── 탭 버튼 ── */
function TabButton({
  active,
  onClick,
  children,
}: {
  active: boolean
  onClick: () => void
  children: React.ReactNode
}) {
  return (
    <button
      onClick={onClick}
      className={`flex-1 py-4 text-base font-medium transition-colors duration-kb
                  flex items-center justify-center gap-1.5
        ${active
          ? 'bg-white border-b-2'
          : 'bg-kb-primary-bg text-kb-text-muted hover:bg-kb-primary-border'
        }`}
      style={active ? { color: KB_PRIMARY, borderColor: KB_PRIMARY } : {}}
    >
      {children}
    </button>
  )
}

/* ── AXful인증서 탭 (QR) ── */
type QrStatus = 'idle' | 'generating' | 'pending' | 'scanned' | 'approved' | 'expired' | 'error'

function KBCertTab() {
  const [status, setStatus] = useState<QrStatus>('idle')
  const [confirmCode, setConfirmCode] = useState('')
  const [timeLeft, setTimeLeft] = useState(0)
  const [qrSeed, setQrSeed] = useState(0)
  const [errorMsg, setErrorMsg] = useState('')
  const [approveId, setApproveId] = useState('')
  const [approvePw, setApprovePw] = useState('')
  const [approveError, setApproveError] = useState('')
  const [approveLoading, setApproveLoading] = useState(false)

  // 카운트다운
  useEffect(() => {
    if (status !== 'pending' && status !== 'scanned') return
    if (timeLeft <= 0) { setStatus('expired'); return }
    const t = setTimeout(() => setTimeLeft((s) => s - 1), 1000)
    return () => clearTimeout(t)
  }, [status, timeLeft])

  const tokenHashRef = useRef<string>('')

  // QR 생성 (실제 백엔드)
  async function handleGenerate() {
    setStatus('generating')
    setErrorMsg('')
    try {
      const { data } = await api.post('/api/v1/auth/qr/generate', {})
      tokenHashRef.current = data.data.tokenHash
      setConfirmCode(data.data.confirmCode)
      setQrSeed(Math.random())
      setTimeLeft(179)
      setStatus('pending')
    } catch {
      setErrorMsg('QR 생성에 실패했습니다. 잠시 후 다시 시도해 주세요.')
      setStatus('error')
    }
  }

  // 폴링: 2초마다 status 조회
  useEffect(() => {
    if (status !== 'pending' && status !== 'scanned') return
    let pollErrors = 0
    const poll = setInterval(async () => {
      try {
        const { data } = await api.get(`/api/v1/auth/qr/status?token=${tokenHashRef.current}`)
        pollErrors = 0
        const s: string = data.data.status
        if (s === 'SCANNED')  setStatus('scanned')
        if (s === 'EXPIRED')  { setStatus('expired'); clearInterval(poll) }
        if (s === 'APPROVED') {
          clearInterval(poll)
          if (!data.data.accessToken || data.data.customerId == null) {
            setStatus('error')
            setErrorMsg('인증 토큰 발급에 실패했습니다. 다시 시도해 주세요.')
            return
          }
          setStatus('approved')
          localStorage.removeItem('sessionExpiry')
          localStorage.setItem('accessToken',  data.data.accessToken)
          localStorage.setItem('access_token', data.data.accessToken)
          localStorage.setItem('customerId',   String(data.data.customerId))
          if (data.data.refreshToken) localStorage.setItem('refreshToken', data.data.refreshToken)
          localStorage.setItem('user', JSON.stringify({ name: '고객', email: '', customer_id: data.data.customerId }))
          localStorage.setItem('sessionExpiry', String(Date.now() + 10 * 60 * 1000))
          try {
            const me = await api.get('/api/v1/customers/me')
            localStorage.setItem('user', JSON.stringify({ name: me.data.data.name, email: me.data.data.email ?? '', customer_id: me.data.data.customerId }))
          } catch {}
          window.location.href = '/'
        }
      } catch {
        if (++pollErrors >= 3) {
          clearInterval(poll)
          setStatus('error')
          setErrorMsg('서버와 통신 중 오류가 발생했습니다. 다시 시도해 주세요.')
        }
      }
    }, 2000)
    return () => clearInterval(poll)
  }, [status])

  async function handleApprove() {
    if (!approveId || !approvePw) { setApproveError('아이디와 비밀번호를 입력해주세요.'); return }
    setApproveError('')
    setApproveLoading(true)
    try {
      await api.post('/api/v1/auth/qr/approve', {
        tokenHash: tokenHashRef.current,
        loginId: approveId,
        password: approvePw,
      })
      setStatus('scanned')
    } catch (err: unknown) {
      const e = err as { response?: { data?: { message?: string } } }
      setApproveError(e.response?.data?.message ?? '승인에 실패했습니다.')
    } finally {
      setApproveLoading(false)
    }
  }

  function handleReset() {
    setStatus('idle')
    setTimeLeft(0)
    setErrorMsg('')
    setApproveError('')
    setApproveId('')
    setApprovePw('')
    tokenHashRef.current = ''
  }

  const mm = String(Math.floor(timeLeft / 60)).padStart(2, '0')
  const ss = String(timeLeft % 60).padStart(2, '0')

  return (
    <div className="flex flex-col items-center gap-4 px-12 pt-8 pb-6">
      <p className="text-xl font-bold text-kb-text">QR코드로 로그인</p>

      {/* QR 이미지 영역 */}
      <div className="w-[140px] h-[140px] border border-kb-border flex items-center justify-center bg-white relative overflow-hidden">
        {status === 'idle' && <QRPlaceholder />}
        {(status === 'pending' || status === 'scanned' || status === 'approved') && (
          <QRCode seed={qrSeed} />
        )}
        {status === 'scanned' && (
          <div className="absolute inset-0 bg-white/75 flex flex-col items-center justify-center gap-1">
            <div className="w-8 h-8 rounded-full border-[3px] border-t-transparent animate-spin" style={{ borderColor: KB_MINT, borderTopColor: 'transparent' }} />
            <span className="text-[11px] font-bold" style={{ color: KB_PRIMARY }}>앱 확인 중</span>
          </div>
        )}
        {status === 'approved' && (
          <div className="absolute inset-0 bg-white/90 flex flex-col items-center justify-center gap-1">
            <svg width="36" height="36" viewBox="0 0 24 24" fill="none" stroke="#5BC9A8" strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round">
              <circle cx="12" cy="12" r="10"/><path d="M8 12l3 3 5-5"/>
            </svg>
            <span className="text-[11px] font-bold" style={{ color: KB_PRIMARY }}>승인 완료</span>
          </div>
        )}
        {status === 'expired' && (
          <div className="absolute inset-0 bg-white/90 flex flex-col items-center justify-center gap-1">
            <svg width="32" height="32" viewBox="0 0 24 24" fill="none" stroke="#9CA3AF" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
              <circle cx="12" cy="12" r="10"/><line x1="12" y1="8" x2="12" y2="12"/><circle cx="12" cy="16" r="0.5" fill="#9CA3AF"/>
            </svg>
            <span className="text-[11px] text-kb-text-muted">만료됨</span>
          </div>
        )}
      </div>

      {/* 상태별 안내 */}
      {(status === 'idle' || status === 'error') && (
        <div className="w-full space-y-2 text-center">
          {status === 'error' && (
            <p className="text-sm text-red-500">{errorMsg}</p>
          )}
          <button
            onClick={handleGenerate}
            className="w-full py-3.5 text-base font-bold text-white rounded-lg hover:opacity-85 transition-opacity"
            style={{ backgroundColor: KB_PRIMARY }}
          >
            QR코드 생성하기
          </button>
        </div>
      )}

      {status === 'generating' && (
        <p className="text-sm text-kb-text-muted">QR코드를 생성 중입니다...</p>
      )}

      {status === 'pending' && (
        <div className="w-full space-y-3">
          <div className="text-center space-y-1">
            <p className="text-base text-kb-text">확인 코드 <span className="font-bold">{confirmCode}</span></p>
            <p className="text-base font-medium" style={{ color: KB_PRIMARY }}>남은 시간 {mm}분 {ss}초</p>
            <p className="text-sm text-kb-text-muted leading-relaxed">
              QR코드를 스캔하여 확인코드를 입력 후 승인해주세요.
            </p>
          </div>

          {/* 테스트용 모바일 승인 */}
          <div className="border rounded-xl p-4 space-y-2" style={{ borderColor: KB_PRIMARY_BORDER, backgroundColor: KB_PRIMARY_SURFACE }}>
            <p className="text-[12px] font-bold text-center" style={{ color: KB_PRIMARY }}>
              테스트용 모바일 승인
            </p>
            <input
              type="text"
              placeholder="아이디"
              value={approveId}
              onChange={(e) => setApproveId(e.target.value)}
              className="w-full border rounded-lg px-3 py-2 text-[13px] outline-none"
              style={{ borderColor: KB_MINT }}
            />
            <input
              type="password"
              placeholder="비밀번호"
              value={approvePw}
              onChange={(e) => setApprovePw(e.target.value)}
              onKeyDown={(e) => e.key === 'Enter' && handleApprove()}
              className="w-full border rounded-lg px-3 py-2 text-[13px] outline-none"
              style={{ borderColor: KB_MINT }}
            />
            {approveError && <p className="text-[12px] text-red-500">{approveError}</p>}
            <button
              onClick={handleApprove}
              disabled={approveLoading}
              className="w-full py-2 text-[13px] font-bold text-white rounded-lg hover:opacity-85 disabled:opacity-60 transition-opacity"
              style={{ backgroundColor: KB_PRIMARY }}
            >
              {approveLoading ? '승인 중...' : '앱에서 승인하기'}
            </button>
            <p className="text-[11px] text-center text-kb-text-muted">
              실제 서비스에서는 AXful 앱에서 승인합니다
            </p>
          </div>
        </div>
      )}

      {status === 'scanned' && (
        <div className="text-center space-y-1">
          <p className="text-base font-bold" style={{ color: KB_PRIMARY }}>앱에서 인증 중입니다...</p>
          <p className="text-sm text-kb-text-muted">잠시 후 자동으로 로그인됩니다.</p>
          <p className="text-sm" style={{ color: KB_PRIMARY }}>남은 시간 {mm}분 {ss}초</p>
        </div>
      )}

      {status === 'approved' && (
        <div className="text-center space-y-1">
          <p className="text-base font-bold" style={{ color: KB_PRIMARY }}>인증 완료! 로그인 중...</p>
        </div>
      )}

      {status === 'expired' && (
        <div className="text-center space-y-2">
          <p className="text-sm text-kb-text-muted">QR코드가 만료되었습니다.</p>
          <button
            onClick={handleReset}
            className="w-full py-3 text-base font-bold text-white rounded-lg hover:opacity-85 transition-opacity"
            style={{ backgroundColor: KB_PRIMARY }}
          >
            다시 생성하기
          </button>
        </div>
      )}
    </div>
  )
}

/* ── 공동·금융인증서 탭 ── */
function CommonCertTab() {
  const [showCertModal, setShowCertModal] = useState(false)
  const [showJointCertModal, setShowJointCertModal] = useState(false)
  const [showKBStarModal, setShowKBStarModal] = useState(false)

  return (
    <>
      <div className="flex divide-x divide-kb-border py-8">
        {/* 좌: 공동인증서 */}
        <div className="flex-1 flex flex-col items-center gap-4 px-10">
          <p className="text-sm text-kb-text-body whitespace-nowrap">공동인증서(구 공인인증서)</p>
          <button
            className="w-full py-3 text-sm whitespace-nowrap font-semibold text-white rounded-lg hover:opacity-85 transition-opacity" style={{ backgroundColor: KB_PRIMARY }}
            onClick={() => setShowJointCertModal(true)}
          >공동인증서(구 공인인증서) 로그인</button>
          <button className="w-full py-3 text-sm whitespace-nowrap font-semibold rounded-lg border-2 hover:bg-kb-primary-bg transition-colors" style={{ borderColor: KB_PRIMARY, color: KB_PRIMARY }} onClick={() => setShowKBStarModal(true)}>AXful 앱 연동 로그인</button>
          <p className="text-sm whitespace-nowrap">
            <Link href="/cert/joint-cert-issue" className="hover:underline" style={{ color: KB_PRIMARY }}>공동인증서(구 공인인증서) 발급</Link>
            <span className="mx-2 text-kb-border">|</span>
            <Link href="/cert/joint-cert-management" className="hover:underline" style={{ color: KB_PRIMARY }}>인증서 관리</Link>
          </p>
        </div>

        {/* 우: 금융인증서 */}
        <div className="flex-1 flex flex-col items-center gap-4 px-10">
          <p className="text-sm text-kb-text-body whitespace-nowrap">금융인증서(브라우저인증서)</p>
          <button
            onClick={() => setShowCertModal(true)}
            className="w-full py-3 text-sm whitespace-nowrap font-semibold text-white rounded-lg hover:opacity-85 transition-opacity" style={{ backgroundColor: KB_PRIMARY }}
          >
            금융인증서(브라우저인증서) 로그인
          </button>
          <p className="text-sm whitespace-nowrap">
            <Link href="/cert/fin-cert-issue" className="hover:underline" style={{ color: KB_PRIMARY }}>금융인증서 발급</Link>
            <span className="mx-2 text-kb-border">|</span>
            <Link href="/cert/cert-management" className="hover:underline" style={{ color: KB_PRIMARY }}>인증서 관리</Link>
          </p>
        </div>
      </div>

      {showCertModal && <FinCertModal onClose={() => setShowCertModal(false)} />}
      {showJointCertModal && <JointCertModal onClose={() => setShowJointCertModal(false)} />}
      {showKBStarModal && <KBStarModal onClose={() => setShowKBStarModal(false)} />}
    </>
  )
}

/* ── 공동인증서 모달 (전자 서명 작성) ── */
const STORAGE_TYPES = [
  { label: '하드디스크', icon: (
    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.5" className="w-5 h-5">
      <rect x="2" y="6" width="20" height="12" rx="2"/><circle cx="17" cy="12" r="1.5" fill="currentColor" stroke="none"/><line x1="5" y1="12" x2="10" y2="12"/>
    </svg>
  )},
  { label: '이동식', icon: (
    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.5" className="w-5 h-5">
      <path d="M8 3h8l3 6v9a1 1 0 01-1 1H6a1 1 0 01-1-1V9L8 3z"/><circle cx="12" cy="14" r="2"/>
    </svg>
  )},
  { label: '보안토큰', icon: (
    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.5" className="w-5 h-5">
      <rect x="5" y="11" width="14" height="10" rx="2"/><path d="M8 11V7a4 4 0 018 0v4"/>
    </svg>
  )},
  { label: '휴대폰', icon: (
    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.5" className="w-5 h-5">
      <rect x="7" y="2" width="10" height="20" rx="2"/><circle cx="12" cy="17" r="1" fill="currentColor" stroke="none"/>
    </svg>
  )},
  { label: '안전디스크', icon: (
    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.5" className="w-5 h-5">
      <circle cx="12" cy="12" r="9"/><circle cx="12" cy="12" r="3"/><circle cx="12" cy="12" r="1" fill="currentColor" stroke="none"/>
    </svg>
  )},
  { label: '간편인증', icon: (
    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.5" className="w-5 h-5">
      <path d="M9 12l2 2 4-4"/><path d="M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2z"/>
    </svg>
  )},
]

const MOCK_JOINT_CERTS = [
  {
    id: 'joint_1',
    serialNumber: 'COMMON-TEST-2024-000001',
    certType: 'CERT_COMMON',
    type: '금융결제원',
    user: '홍길동(0100-0101-****-****)',
    expiry: '2027.06.01',
    issuer: '한국전자인증',
  },
]

async function persistLoginState(auth: { customerId: number; accessToken: string; refreshToken?: string }) {
  const fallbackUser = { name: '홍길동', email: '', customer_id: auth.customerId }

  localStorage.removeItem('sessionExpiry')
  localStorage.setItem('accessToken', auth.accessToken)
  localStorage.setItem('access_token', auth.accessToken)
  localStorage.setItem('customerId', String(auth.customerId))
  localStorage.setItem('user', JSON.stringify(fallbackUser))
  localStorage.setItem('sessionExpiry', String(Date.now() + 10 * 60 * 1000))
  if (auth.refreshToken) localStorage.setItem('refreshToken', auth.refreshToken)

  try {
    const me = await api.get('/api/v1/customers/me')
    localStorage.setItem('user', JSON.stringify({ name: me.data.data.name, email: me.data.data.email ?? '', customer_id: me.data.data.customerId }))
  } catch {}
}

async function handleCertLogin(certSerialNumber: string, certType: string, pin: string) {
  const controller = new AbortController()
  const timeoutId = window.setTimeout(() => controller.abort(), 8000)
  let res: Response
  try {
    res = await fetch('/api/customer/cert-login', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ certSerialNumber, pin, certType }),
      signal: controller.signal,
    })
  } catch (err: unknown) {
    const e = err as { name?: string }
    throw new Error(e.name === 'AbortError' ? '로그인 요청 시간이 초과되었습니다.' : '인증 서버와 통신할 수 없습니다.')
  } finally {
    window.clearTimeout(timeoutId)
  }

  const text = await res.text()
  let data: { code?: string; message?: string; data?: { accessToken: string; refreshToken?: string; customerId: number } } | null = null
  try {
    data = text ? JSON.parse(text) : null
  } catch {
    throw new Error('인증 서버 응답을 확인할 수 없습니다.')
  }
  if (!res.ok || data?.code !== 'OK' || !data.data) {
    throw new Error(data?.message ?? '인증서 로그인에 실패했습니다.')
  }
  await persistLoginState(data.data)
  window.location.href = '/'
}


function JointCertModal({ onClose }: { onClose: () => void }) {
  const [storageType, setStorageType] = useState('하드디스크')
  const [certs, setCerts] = useState(MOCK_JOINT_CERTS)
  const [selectedCert, setSelectedCert] = useState<string | null>(null)
  const [password, setPassword] = useState('')
  const [error, setError] = useState('')
  const [loading, setLoading] = useState(false)
  const [subModal, setSubModal] = useState<'view' | 'find' | 'delete' | null>(null)

  // 발급 화면(joint-cert-issue)에서 저장한 인증서를 목록에 합친다.
  useEffect(() => {
    try {
      const raw = localStorage.getItem('issuedJointCert')
      if (!raw) return
      const c = JSON.parse(raw)
      if (!c?.serialNumber) return
      setCerts(prev => prev.some(x => x.serialNumber === c.serialNumber) ? prev : [
        {
          id: 'issued_joint',
          serialNumber: c.serialNumber,
          certType: c.certType ?? 'CERT_COMMON',
          type: '금융결제원',
          user: c.user ?? '본인',
          expiry: c.expiry ?? '',
          issuer: c.issuer ?? '한국전자인증',
        },
        ...prev,
      ])
    } catch {}
  }, [])

  const activeCert = certs.find(c => c.id === selectedCert)

  function handleDelete() {
    const target = certs.find(c => c.id === selectedCert)
    setCerts(prev => prev.filter(c => c.id !== selectedCert))
    // 발급 인증서를 지우면 localStorage 도 비워 모달 재진입 시 되살아나지 않게 한다.
    if (target?.id === 'issued_joint') {
      try { localStorage.removeItem('issuedJointCert') } catch {}
    }
    setSelectedCert(null)
    setSubModal(null)
  }

  async function handleConfirm() {
    if (!selectedCert) { setError('인증서를 선택해 주세요.'); return }
    if (!password)     { setError('인증서 암호를 입력해 주세요.'); return }
    setError('')
    setLoading(true)
    try {
      const cert = certs.find(c => c.id === selectedCert)
      if (!cert) { setError('인증서를 선택해 주세요.'); return }
      await handleCertLogin(cert.serialNumber, cert.certType, password)
    } catch (err: unknown) {
      const e = err as { response?: { data?: { message?: string } }; message?: string }
      setError(e.response?.data?.message ?? e.message ?? '인증서 암호가 맞지 않습니다.')
    } finally {
      setLoading(false)
    }
  }

  return (
    <div className="fixed inset-0 z-[100] flex items-center justify-center bg-black/60 backdrop-blur-sm">
      <div className="relative bg-white rounded-2xl shadow-2xl overflow-hidden" style={{ width: 500 }}>

        {/* 헤더 */}
        <div className="flex items-center justify-between px-6 py-4" style={{ backgroundColor: KB_PRIMARY }}>
          <div className="flex items-center gap-3">
            <div className="w-8 h-8 rounded-lg bg-white/20 flex items-center justify-center">
              <svg viewBox="0 0 24 24" fill="none" stroke="white" strokeWidth="1.8" className="w-4 h-4">
                <path d="M12 2L2 7l10 5 10-5-10-5z"/><path d="M2 17l10 5 10-5"/><path d="M2 12l10 5 10-5"/>
              </svg>
            </div>
            <span className="text-[15px] font-bold text-white">공동인증서 로그인</span>
          </div>
          <button onClick={onClose} className="text-white/60 hover:text-white transition-colors">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" className="w-5 h-5">
              <path d="M18 6L6 18M6 6l12 12"/>
            </svg>
          </button>
        </div>

        {/* AXful 배너 */}
        <div className="flex items-center gap-4 px-6 py-3 border-b" style={{ backgroundColor: KB_PRIMARY_BG, borderColor: KB_PRIMARY_BORDER }}>
          <div className="flex-1">
            <p className="text-[12px] font-semibold" style={{ color: KB_PRIMARY }}>금융생활을 넘어 일상생활까지 AXful인증서로</p>
            <p className="text-[11px] text-kb-text-muted mt-0.5">간편 발급 · 안전 보관 · 평생 이용</p>
          </div>
          <div className="w-9 h-9 rounded-xl flex items-center justify-center text-white text-[11px] font-extrabold shadow-sm" style={{ backgroundColor: KB_PRIMARY }}>AX</div>
        </div>

        <div className="px-6 py-5 space-y-5">

          {/* 저장 위치 */}
          <div>
            <p className="text-[12px] font-semibold text-kb-text-muted uppercase tracking-wide mb-2.5">저장 위치</p>
            <div className="grid grid-cols-6 gap-1.5">
              {STORAGE_TYPES.map((st) => (
                <button
                  key={st.label}
                  onClick={() => setStorageType(st.label)}
                  className={`flex flex-col items-center gap-1.5 py-3 px-1 rounded-xl border-2 text-[11px] transition-all
                    ${storageType === st.label
                      ? 'border-kb-primary bg-kb-primary-bg text-kb-primary font-bold shadow-sm'
                      : 'border-transparent bg-[#F8F8F8] text-kb-text-muted hover:border-kb-primary-border hover:bg-kb-primary-bg'
                    }`}
                >
                  <span className={storageType === st.label ? 'text-kb-primary' : 'text-kb-text-muted'}>{st.icon}</span>
                  <span className="leading-tight text-center">{st.label}</span>
                </button>
              ))}
            </div>
          </div>

          {/* 인증서 목록 */}
          <div>
            <p className="text-[12px] font-semibold text-kb-text-muted uppercase tracking-wide mb-2.5">인증서 선택</p>
            <div className="rounded-xl border overflow-hidden" style={{ borderColor: KB_PRIMARY_BORDER }}>
              <table className="w-full text-[12px]">
                <thead>
                  <tr style={{ backgroundColor: KB_PRIMARY_BG, borderBottom: `1px solid ${KB_PRIMARY_BORDER}` }}>
                    {['구분', '사용자', '만료일', '발급자'].map((h) => (
                      <th key={h} className="py-2.5 px-3 font-semibold text-left text-kb-text-muted">{h}</th>
                    ))}
                  </tr>
                </thead>
                <tbody>
                  {certs.map((cert) => (
                    <tr
                      key={cert.id}
                      onClick={() => setSelectedCert(cert.id)}
                      className={`cursor-pointer transition-colors ${selectedCert === cert.id ? 'bg-kb-primary-bg' : 'hover:bg-[#FAFAFA]'}`}
                    >
                      <td className="py-3 px-3 font-medium text-kb-text">{cert.type}</td>
                      <td className="py-3 px-3 text-kb-text">{cert.user}</td>
                      <td className="py-3 px-3 text-kb-text-muted">{cert.expiry}</td>
                      <td className="py-3 px-3 text-kb-text-muted">{cert.issuer}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
            <div className="flex justify-end gap-1.5 mt-2">
              <button
                onClick={() => { if (!selectedCert) { setError('인증서를 먼저 선택해 주세요.'); return } setSubModal('view') }}
                className="text-[11px] px-3 py-1.5 rounded-lg border text-kb-text-muted hover:bg-kb-primary-bg hover:text-kb-text transition-colors"
                style={{ borderColor: KB_PRIMARY_BORDER }}>
                인증서 보기
              </button>
              <button
                onClick={() => setSubModal('find')}
                className="text-[11px] px-3 py-1.5 rounded-lg border text-kb-text-muted hover:bg-kb-primary-bg hover:text-kb-text transition-colors"
                style={{ borderColor: KB_PRIMARY_BORDER }}>
                인증서 찾기
              </button>
              <button
                onClick={() => { if (!selectedCert) { setError('삭제할 인증서를 먼저 선택해 주세요.'); return } setSubModal('delete') }}
                className="text-[11px] px-3 py-1.5 rounded-lg border text-red-400 hover:bg-red-50 hover:text-red-600 transition-colors"
                style={{ borderColor: '#FCA5A5' }}>
                인증서 삭제
              </button>
            </div>

            {/* 인증서 보기 패널 */}
            {subModal === 'view' && activeCert && (
              <div className="mt-3 rounded-xl border-2 overflow-hidden" style={{ borderColor: KB_PRIMARY }}>
                <div className="flex items-center justify-between px-4 py-2.5" style={{ backgroundColor: KB_PRIMARY }}>
                  <span className="text-[12px] font-bold text-white">인증서 상세 정보</span>
                  <button onClick={() => setSubModal(null)} className="text-white/70 hover:text-white">
                    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" className="w-3.5 h-3.5"><path d="M18 6L6 18M6 6l12 12"/></svg>
                  </button>
                </div>
                <div className="bg-white divide-y divide-kb-primary-border text-[12px]">
                  {[
                    ['인증서 구분', activeCert.type],
                    ['소유자', activeCert.user],
                    ['일련번호', activeCert.serialNumber],
                    ['발급기관', activeCert.issuer],
                    ['유효기간', `${activeCert.expiry}까지`],
                    ['인증서 유형', activeCert.certType],
                  ].map(([label, value]) => (
                    <div key={label} className="flex px-4 py-2.5">
                      <span className="w-24 text-kb-text-muted flex-shrink-0">{label}</span>
                      <span className="text-kb-text font-medium break-all">{value}</span>
                    </div>
                  ))}
                </div>
              </div>
            )}

            {/* 인증서 찾기 패널 */}
            {subModal === 'find' && (
              <div className="mt-3 rounded-xl border-2 p-4" style={{ borderColor: '#E2E8F0' }}>
                <div className="flex items-center justify-between mb-3">
                  <span className="text-[12px] font-bold text-kb-text">인증서 찾기</span>
                  <button onClick={() => setSubModal(null)} className="text-kb-text-muted hover:text-kb-text">
                    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" className="w-3.5 h-3.5"><path d="M18 6L6 18M6 6l12 12"/></svg>
                  </button>
                </div>
                <p className="text-[11px] text-kb-text-muted mb-3">인증서 파일(.pfx, .p12)을 직접 선택하여 등록할 수 있습니다.</p>
                <label className="flex items-center gap-3 px-4 py-3 rounded-xl border-2 border-dashed cursor-pointer hover:bg-kb-primary-bg transition-colors" style={{ borderColor: KB_PRIMARY }}>
                  <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.5" className="w-5 h-5 flex-shrink-0" style={{ color: KB_PRIMARY }}>
                    <path d="M21 15v4a2 2 0 01-2 2H5a2 2 0 01-2-2v-4"/><polyline points="17 8 12 3 7 8"/><line x1="12" y1="3" x2="12" y2="15"/>
                  </svg>
                  <div>
                    <p className="text-[12px] font-semibold" style={{ color: KB_PRIMARY }}>파일 선택</p>
                    <p className="text-[11px] text-kb-text-muted">.pfx, .p12 형식 지원</p>
                  </div>
                  <input type="file" accept=".pfx,.p12" className="hidden" onChange={() => setSubModal(null)} />
                </label>
              </div>
            )}
          </div>

          {/* 암호 입력 */}
          <div>
            <p className="text-[12px] font-semibold text-kb-text-muted uppercase tracking-wide mb-2.5">인증서 암호</p>
            <div className="relative">
              <input
                type="password"
                value={password}
                onChange={(e) => setPassword(e.target.value)}
                onKeyDown={(e) => e.key === 'Enter' && handleConfirm()}
                placeholder="암호를 입력하세요"
                className="w-full rounded-xl border-2 px-4 py-2.5 text-[13px] outline-none transition-colors"
                style={{ borderColor: password ? KB_PRIMARY : '#E2E8F0' }}
              />
            </div>
            <p className="text-[11px] text-kb-text-muted mt-1.5">6개월마다 인증서 암호를 변경하시기 바랍니다.</p>
            {error && (
              <div className="flex items-center gap-1.5 mt-2 text-red-500">
                <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" className="w-3.5 h-3.5 flex-shrink-0">
                  <circle cx="12" cy="12" r="10"/><line x1="12" y1="8" x2="12" y2="12"/><line x1="12" y1="16" x2="12.01" y2="16"/>
                </svg>
                <p className="text-[12px]">{error}</p>
              </div>
            )}
          </div>
        </div>

        {/* 삭제 확인 오버레이 */}
        {subModal === 'delete' && activeCert && (
          <div className="absolute inset-0 z-10 flex items-center justify-center rounded-2xl bg-black/30 backdrop-blur-sm">
            <div className="bg-white rounded-2xl shadow-xl p-6 mx-6 w-full max-w-xs">
              <div className="flex items-center gap-3 mb-4">
                <div className="w-10 h-10 rounded-full bg-red-100 flex items-center justify-center flex-shrink-0">
                  <svg viewBox="0 0 24 24" fill="none" stroke="#EF4444" strokeWidth="2" className="w-5 h-5">
                    <polyline points="3 6 5 6 21 6"/><path d="M19 6l-1 14a2 2 0 01-2 2H8a2 2 0 01-2-2L5 6"/><path d="M10 11v6M14 11v6"/><path d="M9 6V4a1 1 0 011-1h4a1 1 0 011 1v2"/>
                  </svg>
                </div>
                <div>
                  <p className="text-[14px] font-bold text-kb-text">인증서 삭제</p>
                  <p className="text-[12px] text-kb-text-muted mt-0.5">삭제 후 복구할 수 없습니다.</p>
                </div>
              </div>
              <div className="bg-[#FEF2F2] rounded-xl p-3 mb-4 text-[12px]">
                <p className="font-medium text-red-700">{activeCert.user}</p>
                <p className="text-red-500 mt-0.5">{activeCert.type} · 만료 {activeCert.expiry}</p>
              </div>
              <div className="flex gap-2">
                <button onClick={() => setSubModal(null)}
                  className="flex-1 py-2.5 rounded-xl border-2 text-[13px] font-medium text-kb-text-muted hover:bg-[#F8F8F8] transition-colors"
                  style={{ borderColor: '#E2E8F0' }}>
                  취소
                </button>
                <button onClick={handleDelete}
                  className="flex-1 py-2.5 rounded-xl text-[13px] font-bold text-white bg-red-500 hover:bg-red-600 transition-colors">
                  삭제
                </button>
              </div>
            </div>
          </div>
        )}

        {/* 버튼 */}
        <div className="flex gap-2.5 px-6 pb-6">
          <button
            onClick={handleConfirm}
            disabled={loading}
            className="flex-1 py-3 text-white text-[14px] font-bold rounded-xl hover:opacity-90 disabled:opacity-50 transition-opacity"
            style={{ backgroundColor: KB_PRIMARY }}
          >
            {loading ? '확인 중...' : '확인'}
          </button>
          <button
            onClick={onClose}
            className="flex-1 py-3 rounded-xl border-2 text-[14px] font-medium text-kb-text-muted hover:bg-kb-primary-bg hover:text-kb-text transition-colors"
            style={{ borderColor: '#E2E8F0' }}
          >
            취소
          </button>
        </div>
      </div>
    </div>
  )
}

/* ── 아이디 로그인 탭 ── */
function IdLoginTab() {
  const [loginId, setLoginId] = useState('')
  const [password, setPassword] = useState('')
  const [error, setError] = useState('')
  const [loading, setLoading] = useState(false)

  async function handleLogin() {
    if (!loginId || !password) {
      setError('아이디와 비밀번호를 입력해주세요.')
      return
    }
    setError('')
    setLoading(true)
    // 만료된 토큰이 Authorization 헤더에 실려 백엔드가 거부하는 것을 방지
    localStorage.removeItem('accessToken')
    localStorage.removeItem('access_token')
    localStorage.removeItem('sessionExpiry')
    localStorage.removeItem('user')
    try {
      const { data } = await api.post('/api/v1/auth/login', { loginId, password })
      await persistLoginState(data.data)

      window.location.href = '/'
    } catch (err: unknown) {
      const axiosErr = err as { code?: string; message?: string; response?: { data?: { message?: string } } }
      if (!axiosErr.response && (axiosErr.code === 'ERR_NETWORK' || axiosErr.message === 'Network Error')) {
        setError('로그인 서버에 연결할 수 없습니다. API Gateway(8080)와 customer-service(8081)를 확인해주세요.')
      } else {
        setError(axiosErr.response?.data?.message ?? '로그인에 실패했습니다.')
      }
    } finally {
      setLoading(false)
    }
  }

  return (
    <div className="py-10 px-16">
      {/* form-table 스타일 */}
      <div className="space-y-2 mb-5">
        {/* 아이디 */}
        <div className="flex items-center gap-3">
          <label className="w-20 text-body text-kb-text-body text-right flex-shrink-0">아이디</label>
          <input
            type="text"
            placeholder="아이디"
            value={loginId}
            onChange={(e) => setLoginId(e.target.value)}
            onKeyDown={(e) => e.key === 'Enter' && handleLogin()}
            className="input flex-1"
            autoComplete="username"
          />
        </div>

        {/* 사용자암호 */}
        <div className="flex items-center gap-3">
          <label className="w-20 text-body text-kb-text-body text-right flex-shrink-0">사용자암호</label>
          <div className="flex-1 flex items-center gap-2">
            <input
              type="password"
              placeholder="사용자암호"
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              onKeyDown={(e) => e.key === 'Enter' && handleLogin()}
              className="input flex-1"
              autoComplete="current-password"
            />
          </div>
        </div>
      </div>

      {/* 에러 메시지 */}
      {error && (
        <div className="pl-[92px] mb-3">
          <p className="text-caption text-kb-red">{error}</p>
        </div>
      )}

      {/* 로그인 버튼 */}
      <div className="pl-[92px]">
        <button
          onClick={handleLogin}
          disabled={loading}
          className="w-full py-3 text-body font-bold text-white rounded-lg hover:opacity-85 transition-opacity disabled:opacity-60" style={{ backgroundColor: KB_PRIMARY }}
        >
          {loading ? '로그인 중...' : '로그인'}
        </button>

        <div className="flex items-center justify-center gap-3 mt-4 text-caption" style={{ color: KB_PRIMARY }}>
          <Link href="/support/customer-info/id-password" className="hover:underline">ID 조회 / 사용자암호 설정</Link>
          <span className="text-kb-border">|</span>
          <Link href="/login/pin" className="hover:underline">간편비밀번호 로그인</Link>
          <span className="text-kb-border">|</span>
          <Link href="/support/customer-info/online-join" className="hover:underline">개인 회원가입</Link>
          <span className="text-kb-border">|</span>
          <Link href="/support/customer-info/corporate-join" className="hover:underline">법인 회원가입</Link>
        </div>
      </div>
    </div>
  )
}

/* ── AXful 연동 로그인 모달 ── */
function KBStarModal({ onClose }: { onClose: () => void }) {
  type StarTab = 'push' | 'qr'
  const [tab, setTab] = useState<StarTab>('push')
  const [phoneMiddle, setPhoneMiddle] = useState('')
  const [phoneLast, setPhoneLast] = useState('')
  const [saveInfo, setSaveInfo] = useState(false)
  const [sent, setSent] = useState(false)

  function handleSend() {
    if (!phoneMiddle || !phoneLast) return
    setSent(true)
  }

  return (
    <div className="fixed inset-0 z-[100] flex items-center justify-center bg-black/50">
      <div className="bg-white shadow-2xl rounded-sm" style={{ width: 360 }}>

        {/* 타이틀 */}
        <div className="px-5 pt-5 pb-3">
          <p className="text-[16px] font-bold text-kb-text">AXful 연동 로그인</p>
        </div>

        {/* 탭 */}
        <div className="flex px-5 gap-0 mb-0">
          <button
            onClick={() => { setTab('push'); setSent(false) }}
            className={`px-4 py-2 text-[13px] font-medium border transition-colors
              ${tab === 'push'
                ? 'text-white border-transparent'
                : 'bg-white text-gray-500 border-gray-300 hover:bg-kb-primary-bg'}`}
          >
            PUSH(휴대폰 번호)
          </button>
          <button
            onClick={() => { setTab('qr'); setSent(false) }}
            className={`px-4 py-2 text-[13px] font-medium border-t border-b border-r transition-colors
              ${tab === 'qr'
                ? 'text-white border-transparent'
                : 'bg-white text-gray-500 border-gray-300 hover:bg-kb-primary-bg'}`}
          >
            QR코드 또는 인증번호
          </button>
        </div>

        {/* 탭 컨텐츠 */}
        <div className="px-5 py-4 min-h-[260px]">
          {tab === 'push' && !sent && (
            <div className="space-y-4">
              {/* 안내 문구 */}
              <div className="flex gap-2 text-[12px] text-gray-600 leading-relaxed">
                <span className="text-kb-primary flex-shrink-0 mt-0.5">ℹ</span>
                <div>
                  인증서가 저장된 휴대폰번호를 입력하시지요.<br />
                  <span className="text-kb-primary">모바일뱅킹앱 실행 시 지문·Face ID인증 팝업이 나오는경우</span><br />
                  해당 팝업 종료 후 연동인증을 진행해주세요.
                </div>
              </div>

              {/* 전화번호 입력 */}
              <div className="flex items-center gap-1">
                <select className="border border-gray-400 text-[13px] px-1 py-1.5 bg-white text-gray-700">
                  <option>010</option>
                  <option>011</option>
                  <option>016</option>
                  <option>017</option>
                  <option>018</option>
                  <option>019</option>
                </select>
                <span className="text-gray-400">-</span>
                <input
                  type="text"
                  maxLength={4}
                  value={phoneMiddle}
                  onChange={(e) => setPhoneMiddle(e.target.value.replace(/\D/g, ''))}
                  className="border border-gray-400 px-2 py-1.5 text-[13px] w-[80px] outline-none focus:border-kb-mint"
                />
                <span className="text-gray-400">-</span>
                <input
                  type="text"
                  maxLength={4}
                  value={phoneLast}
                  onChange={(e) => setPhoneLast(e.target.value.replace(/\D/g, ''))}
                  className="border border-gray-400 px-2 py-1.5 text-[13px] w-[80px] outline-none focus:border-kb-mint"
                />
              </div>

              {/* 내 정보 저장 */}
              <label className="flex items-center gap-2 text-[12px] text-gray-600 cursor-pointer">
                <input
                  type="checkbox"
                  checked={saveInfo}
                  onChange={(e) => setSaveInfo(e.target.checked)}
                  className="w-3.5 h-3.5"
                />
                내 정보 저장하기 (개인 컴퓨터에서만 저장하세요.)
              </label>

              {/* 전송 버튼 */}
              <button
                onClick={handleSend}
                disabled={!phoneMiddle || !phoneLast}
                className="w-full py-2.5 text-white text-[14px] font-bold rounded-lg hover:opacity-85 disabled:opacity-50 transition-opacity" style={{ backgroundColor: KB_PRIMARY }}
              >
                전송
              </button>
            </div>
          )}

          {tab === 'push' && sent && (
            <div className="flex flex-col items-center justify-center gap-3 py-8 text-center">
              <div className="w-14 h-14 rounded-full border-2 border-kb-mint flex items-center justify-center">
                <span className="text-kb-mint text-2xl">📱</span>
              </div>
              <p className="text-[14px] font-bold text-kb-text">모바일뱅킹 앱을 확인해주세요</p>
              <p className="text-[12px] text-gray-500">앱에서 인증을 완료하면 자동으로 로그인됩니다.</p>
            </div>
          )}

          {tab === 'qr' && (
            <div className="flex flex-col items-center justify-center gap-3 py-6">
              <div className="w-[120px] h-[120px] border border-gray-300 flex items-center justify-center bg-gray-50">
                <QRPlaceholder />
              </div>
              <p className="text-[12px] text-gray-500 text-center">
                AXful 앱에서 QR코드를 스캔하거나<br />인증번호를 입력하세요.
              </p>
            </div>
          )}
        </div>

        {/* AXful 뱅킹 아이콘 */}
        <div className="px-5 pb-2">
          <div className="w-10 h-10 rounded-lg flex items-center justify-center text-white text-[10px] font-extrabold" style={{ backgroundColor: KB_PRIMARY }}>
            AX
          </div>
        </div>

        {/* 하단 */}
        <div className="flex items-center justify-between px-5 py-3 border-t border-gray-200">
          <div className="text-[11px] text-gray-400">
            <span>CertGate 2.2.0 (221025)</span>
            <br />
            <button className="text-red-500 hover:underline">처음 이용하신다면</button>
          </div>
          <button
            onClick={onClose}
            className="border border-gray-400 px-5 py-1.5 text-[13px] text-gray-600 hover:bg-gray-50"
          >
            취소
          </button>
        </div>
      </div>
    </div>
  )
}

/* ── 테스트용 숫자패드 ── */
function shufflePad(): string[] {
  return ['1', '2', '3', '4', '5', '6', '7', '8', '9', '0']
}

/* ── 금융인증서 모달 ── */
function FinCertModal({ onClose }: { onClose: () => void }) {
  const [step, setStep] = useState<'confirm' | 'yeskey'>('confirm')
  const [pin, setPin] = useState('')
  const [pad, setPad] = useState<string[]>(shufflePad)
  const [attemptsLeft, setAttemptsLeft] = useState(10)
  const [errorMsg, setErrorMsg] = useState('')
  const [loading, setLoading] = useState(false)

  useEffect(() => {
    const t = setTimeout(() => setStep('yeskey'), 900)
    return () => clearTimeout(t)
  }, [])

  useEffect(() => {
    if (step !== 'yeskey') return

    function handleKeyDown(e: KeyboardEvent) {
      if (/^[0-9]$/.test(e.key)) handleDigit(e.key)
      if (e.key === 'Backspace') setPin((p) => p.slice(0, -1))
      if (e.key === 'Enter' && pin.length === 6) submitPin(pin)
    }

    window.addEventListener('keydown', handleKeyDown)
    return () => window.removeEventListener('keydown', handleKeyDown)
  })

  async function submitPin(finalPin: string) {
    if (loading) return
    if (finalPin.length !== 6) {
      setErrorMsg('비밀번호 6자리를 입력해주세요.')
      return
    }
    setLoading(true)
    setErrorMsg('')
    try {
      await handleCertLogin('FINCERT-TEST-2024-000001', 'CERT_FIN', finalPin)
    } catch (err: unknown) {
      const e = err as { response?: { data?: { message?: string } }; message?: string }
      const next = Math.max(attemptsLeft - 1, 0)
      const message = e.response?.data?.message ?? e.message ?? `비밀번호가\n맞지 않습니다\n(${next}회 남음)`
      setAttemptsLeft(next)
      setErrorMsg(message)
      setPin('')
      setPad(shufflePad())
    } finally {
      setLoading(false)
    }
  }

  function handleDigit(d: string) {
    if (pin.length >= 6 || loading) return
    const next = pin + d
    setPin(next)
    setErrorMsg('')
    if (next.length === 6) submitPin(next)
  }

  return (
    <div className="fixed inset-0 z-[100] flex items-center justify-center bg-black/50">

      {/* ── STEP 1: 팝업창 확인 ── */}
      <div className="flex shadow-2xl" style={{ width: 680 }}>
        {/* 좌: 메인 패널 */}
        <div className="bg-white flex-1 flex" style={{ minHeight: 360 }}>
          {/* 탭 사이드바 */}
          <div className="w-[110px] border-r border-gray-200 bg-gray-50 py-1 flex-shrink-0">
            {[
              { label: '금융인증서', icon: '☁️', active: true },
              { label: '브라우저인증서', icon: '🌐', active: false },
              { label: '금융인증서\n(사업자)', icon: '☁️', active: false },
            ].map((item) => (
              <div
                key={item.label}
                className={`px-2 py-3 flex flex-col items-center gap-1 text-[11px] text-center cursor-pointer transition-colors
                  ${item.active
                    ? 'bg-white border-l-2 font-semibold text-kb-text'
                    : 'text-kb-text-muted hover:bg-white opacity-60'
                  }`}
              >
                <span className="text-xl">{item.icon}</span>
                <span className="whitespace-pre-line leading-tight">{item.label}</span>
              </div>
            ))}
          </div>

          {/* 메인 컨텐츠 */}
          <div className="flex-1 flex flex-col">
            <div className="flex items-center justify-between px-3 py-1.5 bg-gray-100 border-b border-gray-200">
              <button className="text-gray-500 hover:text-gray-700 text-sm px-1">−</button>
              <span className="text-[13px] font-medium text-gray-700">금융인증서</span>
              <button onClick={onClose} className="text-gray-500 hover:text-gray-700 text-sm px-1">✕</button>
            </div>
            <div className="flex-1 flex flex-col items-center justify-center gap-4 px-8 py-8">
              <div className="w-12 h-12 rounded border-2 border-kb-mint flex items-center justify-center">
                <span className="text-kb-mint text-2xl font-bold">✓</span>
              </div>
              <p className="text-[16px] font-medium text-kb-text">팝업창을 확인해주세요.</p>
              <p className="text-[13px] text-center leading-relaxed text-gray-500">
                <span className="text-kb-primary font-medium">클라우드에 저장하는</span>{' '}
                <span className="text-kb-text font-medium">새로운 인증서</span>
                <br />
                금융인증서의 팝업창이 열리지 않았다면{' '}
                <span className="text-kb-text font-medium">아래 버튼을</span>
                <br />
                눌러 인증을 진행해주세요.
              </p>
              <button
                onClick={() => setStep('yeskey')}
                className="mt-2 px-10 py-2 text-white text-[14px] font-bold rounded-lg hover:opacity-85 transition-opacity"
                style={{ backgroundColor: KB_PRIMARY }}
              >
                확인
              </button>
            </div>
          </div>
        </div>

        {/* 우: 이용안내 패널 */}
        <div className="w-[200px] bg-white border-l border-gray-200 flex flex-col" style={{ minHeight: 360 }}>
          <div className="flex items-center justify-between px-4 py-3 border-b border-gray-200">
            <span className="text-[12px] font-bold text-gray-700">금융인증서 이용안내</span>
            <span className="text-xl">☁️</span>
          </div>
          <div className="px-4 py-4 space-y-4 text-[11px] text-gray-600 leading-relaxed flex-1 overflow-y-auto">
            <div>
              <p className="font-bold text-gray-700 mb-1">인증서가 없는 경우</p>
              <ul className="space-y-1 pl-0">
                <li>• [인증센터] &gt; 금융인증서 &gt; 금융인증서 발급/재발급 에서 인증서를 발급받을 수 있어요.</li>
                <li className="mt-1">• 스마트폰에서 금융인증서를 발급하신도 금융결제원 클라우드에 연결하여 인증서를 불러올 수 있어요.</li>
              </ul>
            </div>
            <div>
              <p className="font-bold text-gray-700 mb-1">타행/타기관에서 발급한 인증서인 경우</p>
              <ul className="pl-0">
                <li>• 타행/타기관인증서 등록 후 로그인 하실 수 있어요.</li>
              </ul>
            </div>
            <div>
              <p className="font-bold text-gray-700 mb-1">금융결제원 클라우드로 연결하기</p>
              <ul className="pl-0">
                <li>• 성명/생년월일/휴대폰번호를 입력 후 금융결제원 클라우드 나온 숫자 2자리를 휴대폰 문자로 전송하여 연결됩니다.</li>
              </ul>
            </div>
          </div>
          <div className="px-4 py-3 border-t border-gray-200">
            <button className="text-[11px] text-kb-primary hover:underline">다시 보지않기</button>
          </div>
        </div>
      </div>

      {/* ── STEP 2: YESKEY PIN 오버레이 ── */}
      {step === 'yeskey' && (
        <div className="absolute inset-0 flex items-center justify-center bg-black/30 z-10">
          <div className="bg-white shadow-2xl" style={{ width: 500 }}>
            <div className="flex items-center justify-between px-4 py-2.5 border-b border-kb-border" style={{ backgroundColor: KB_PRIMARY_BG }}>
              <span className="text-[13px] font-medium text-kb-text">금융인증서비스</span>
              <button onClick={onClose} className="text-kb-text-muted hover:text-kb-text">✕</button>
            </div>

            <div className="flex" style={{ minHeight: 400 }}>
              {/* 좌: YESKEY 브랜드 */}
              <div className="w-[140px] border-r border-gray-200 flex flex-col items-center justify-center gap-3 bg-gray-50 py-8 flex-shrink-0">
                <p className="font-extrabold text-[15px] tracking-tight" style={{ color: '#1a5fb4' }}>YESKEY</p>
                <div className="w-10 h-4 bg-gray-300 rounded-sm flex items-center justify-center">
                  <span className="text-[8px] text-gray-600 font-bold">금융인증서</span>
                </div>
                <div className="w-14 h-14 rounded-full border-2 border-orange-400 flex items-center justify-center mt-1 bg-white">
                  <div className="text-center">
                    <p className="text-[9px] font-bold text-orange-400 leading-tight">Trust</p>
                    <p className="text-[9px] font-bold text-orange-400 leading-tight">Sign</p>
                  </div>
                </div>
              </div>

              {/* 우: PIN 입력 */}
              <div className="flex-1 flex flex-col items-center py-6 px-5 gap-3">
                <p className="text-[13px] font-medium" style={{ color: KB_PRIMARY }}>
                  고객님의 금융인증서
                </p>
                <p className="text-[17px] font-bold text-kb-text">비밀번호를 입력해주세요</p>

                <div className="flex gap-2 my-1">
                  {Array.from({ length: 6 }).map((_, i) => (
                    <div
                      key={i}
                      className="w-9 h-9 rounded flex items-center justify-center border-2"
                      style={i < pin.length ? { backgroundColor: KB_PRIMARY, borderColor: KB_PRIMARY } : { borderColor: '#D1D5DB', backgroundColor: 'white' }}
                    >
                      {i < pin.length && <span className="text-white text-sm font-bold">●</span>}
                    </div>
                  ))}
                </div>

                <button className="text-[12px] hover:underline" style={{ color: KB_PRIMARY }}>
                  비밀번호를 잊으셨나요?
                </button>
                {loading && (
                  <p className="text-[12px] font-medium" style={{ color: KB_PRIMARY }}>
                    로그인 확인 중입니다...
                  </p>
                )}
                {errorMsg && (
                  <p className="w-full rounded border border-red-200 bg-red-50 px-3 py-2 text-center text-[12px] font-medium text-red-600 whitespace-pre-line">
                    {errorMsg}
                  </p>
                )}

                <div className="grid grid-cols-3 gap-0.5 w-full mt-1">
                  {pad.slice(0, 9).map((d) => (
                    <button
                      key={d}
                      onClick={() => handleDigit(d)}
                      disabled={loading}
                      className="h-12 text-[18px] font-medium text-kb-text hover:bg-gray-100 transition-colors disabled:opacity-40 select-none"
                      tabIndex={-1}
                    >
                      {d}
                    </button>
                  ))}
                  <button
                    onClick={() => { setPad(shufflePad()); setPin(''); setErrorMsg('') }}
                    disabled={loading}
                    className="h-12 flex items-center justify-center hover:bg-gray-100 transition-colors text-gray-500 text-xl"
                    tabIndex={-1}
                  >↻</button>
                  <button
                    onClick={() => handleDigit(pad[9])}
                    disabled={loading}
                    className="h-12 text-[18px] font-medium text-kb-text hover:bg-gray-100 transition-colors disabled:opacity-40 select-none"
                    tabIndex={-1}
                  >{pad[9]}</button>
                  <button
                    onClick={() => setPin((p) => p.slice(0, -1))}
                    disabled={loading}
                    className="h-12 flex items-center justify-center hover:bg-gray-100 transition-colors"
                    tabIndex={-1}
                  >
                    <span className="bg-gray-500 text-white text-xs rounded px-1.5 py-0.5">✕</span>
                  </button>
                </div>
                <button
                  onClick={() => submitPin(pin)}
                  disabled={loading || pin.length !== 6}
                  className="mt-1 w-full rounded-lg py-2.5 text-[14px] font-bold text-white disabled:cursor-not-allowed disabled:opacity-45"
                  style={{ backgroundColor: KB_PRIMARY }}
                >
                  {loading ? '확인 중...' : '로그인'}
                </button>
              </div>
            </div>
          </div>
        </div>
      )}

      {/* 오류 모달 */}
      {errorMsg && (
        <div className="fixed inset-0 z-[200] flex items-center justify-center">
          <div className="bg-white shadow-xl rounded p-8 min-w-[220px] text-center">
            <p className="text-body text-kb-text whitespace-pre-line leading-relaxed">{errorMsg}</p>
            <button
              onClick={() => setErrorMsg('')}
              className="mt-5 text-body hover:underline"
              style={{ color: KB_PRIMARY }}
            >
              확인
            </button>
          </div>
        </div>
      )}
    </div>
  )
}

/* ── 아이콘 컴포넌트 ── */
function KBShieldIcon({ size = 16 }: { size?: number }) {
  return (
    <svg width={size} height={size} viewBox="0 0 24 24" fill="none" stroke="#0D5C47" strokeWidth="1.8">
      <path d="M12 2l7 3v6c0 4.5-3 8-7 9-4-1-7-4.5-7-9V5l7-3z"/>
      <path d="M9 12l2 2 4-4"/>
    </svg>
  )
}

function seededRand(seed: number) {
  let s = Math.floor(seed * 1e9)
  return () => {
    s = (Math.imul(s, 1664525) + 1013904223) | 0
    return (s >>> 0) / 0xffffffff
  }
}

function QRCode({ seed }: { seed: number }) {
  const SIZE = 25
  const CELL = 5
  const rand = seededRand(seed)
  const grid: boolean[][] = Array.from({ length: SIZE }, () => Array(SIZE).fill(false))

  function setFinder(row: number, col: number) {
    for (let r = 0; r < 7; r++)
      for (let c = 0; c < 7; c++)
        grid[row + r][col + c] =
          r === 0 || r === 6 || c === 0 || c === 6 || (r >= 2 && r <= 4 && c >= 2 && c <= 4)
  }
  setFinder(0, 0)
  setFinder(0, SIZE - 7)
  setFinder(SIZE - 7, 0)

  for (let i = 8; i < SIZE - 8; i++) {
    grid[6][i] = i % 2 === 0
    grid[i][6] = i % 2 === 0
  }

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
      {grid.flatMap((row, r) =>
        row.map((filled, c) =>
          filled ? <rect key={`${r}-${c}`} x={c * CELL} y={r * CELL} width={CELL} height={CELL} fill="#1a1a1a" /> : null
        )
      )}
    </svg>
  )
}

function QRPlaceholder() {
  return (
    <svg width="100" height="100" viewBox="0 0 100 100" fill="none" opacity="0.3">
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

/* ── 로그인 설정 모달 ── */
const LOGIN_SETTING_OPTIONS = [
  '공동인증서(구 공인인증서)',
  '금융인증서',
  'AXful인증서',
  '아이디',
  '간편비밀번호',
  'QR코드',
  '사용안함',
]

/** 로그인 방식 → 메인 로그인 탭 매핑. '간편비밀번호'는 별도 페이지(/login/pin)로 이동, '사용안함'은 매핑 없음. */
const METHOD_TAB_MAP: Record<string, LoginTab> = {
  '공동인증서(구 공인인증서)': '공동금융인증서',
  '금융인증서': '공동금융인증서',
  'AXful인증서': 'kb인증서',
  '아이디': '아이디',
  'QR코드': 'kb인증서',
}

function LoginSettingModal({ onApply, onClose }: { onApply: (tab: LoginTab) => void; onClose: () => void }) {
  const [selected, setSelected] = useState('사용안함')

  // 저장된 선호 방식으로 초기 선택
  useEffect(() => {
    const pref = localStorage.getItem('preferredLoginMethod')
    if (pref && LOGIN_SETTING_OPTIONS.includes(pref)) setSelected(pref)
  }, [])

  function handleApply() {
    localStorage.setItem('preferredLoginMethod', selected)
    if (selected === '간편비밀번호') {
      window.location.href = '/login/pin'
      return
    }
    const target = METHOD_TAB_MAP[selected]
    if (target) onApply(target)
    onClose()
  }

  return (
    <div className="fixed inset-0 z-[300] flex items-center justify-center bg-black/40">
      <div className="bg-white w-[400px] shadow-lg">
        {/* 헤더 */}
        <div className="flex items-center justify-between px-6 py-4" style={{ backgroundColor: KB_PRIMARY_DARK }}>
          <p className="text-body font-bold text-white">로그인 설정</p>
          <button onClick={onClose} className="text-white/80 hover:text-white text-xl leading-none">✕</button>
        </div>

        {/* 안내 */}
        <div className="mx-6 mt-5 mb-4 border border-kb-border px-4 py-3 bg-kb-primary-bg">
          <p className="text-caption text-kb-text-body">
            · 자주 쓰는 로그인 방식을 설정하고 간편하게 로그인하세요.
          </p>
        </div>

        {/* 라디오 옵션 */}
        <div className="mx-6 border border-kb-border divide-y divide-kb-border mb-6">
          {LOGIN_SETTING_OPTIONS.map((option) => (
            <label
              key={option}
              className="flex items-center gap-3 px-5 py-3 cursor-pointer hover:bg-kb-primary-bg transition-colors"
            >
              <input
                type="radio"
                name="login-setting"
                value={option}
                checked={selected === option}
                onChange={() => setSelected(option)}
                className="accent-kb-taupe w-4 h-4 flex-shrink-0"
              />
              <span className="text-caption text-kb-text">{option}</span>
            </label>
          ))}
        </div>

        {/* 설정 버튼 */}
        <div className="flex justify-center pb-6">
          <button
            onClick={handleApply}
            className="px-14 py-2.5 text-white text-body font-bold rounded-lg hover:opacity-85 transition-opacity"
            style={{ backgroundColor: KB_PRIMARY }}
          >
            설정
          </button>
        </div>
      </div>
    </div>
  )
}
