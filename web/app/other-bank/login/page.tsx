'use client'

import { useState } from 'react'
import Link from 'next/link'
import { useRouter } from 'next/navigation'
import { DAON_NAVY, KB_BLUE } from '@/lib/theme'

type DaonLoginTab = '공동금융인증서' | 'daon인증서' | '간편로그인'

export default function DaonLoginPage() {
  const router = useRouter()
  const [tab, setTab] = useState<DaonLoginTab>('공동금융인증서')
  const [userId, setUserId] = useState('')
  const [password, setPassword] = useState('')

  function handleLogin() {
    // 데모: 인증 절차 생략하고 바로 계좌조회로 이동
    router.push('/other-bank/accounts')
  }

  return (
    <>
      {/* 페이지 타이틀 바 */}
      <div className="bg-white border-b border-kb-border">
        <div className="max-w-kb-container mx-auto px-6 py-4">
          <h1 className="text-2xl font-bold text-kb-text">로그인</h1>
        </div>
      </div>

      {/* 본문 */}
      <div className="py-6" style={{ backgroundColor: '#EEF2F8' }}>
        <div className="w-full max-w-[900px] mx-auto">

          {/* 로그인 카드 */}
          <div className="bg-white shadow-sm">

            {/* 탭 */}
            <div className="flex border-b border-kb-border">
              <DaonTabButton active={tab === '공동금융인증서'} onClick={() => setTab('공동금융인증서')}>
                공동·금융인증서
              </DaonTabButton>
              <DaonTabButton active={tab === 'daon인증서'} onClick={() => setTab('daon인증서')}>
                <DaonShieldIcon />
                다온인증서
              </DaonTabButton>
              <DaonTabButton active={tab === '간편로그인'} onClick={() => setTab('간편로그인')}>
                간편로그인
              </DaonTabButton>
            </div>

            {/* 탭 컨텐츠 */}
            <div className="min-h-[280px] flex flex-col justify-center">
              {tab === '공동금융인증서' && (
                <div className="flex divide-x divide-kb-border py-8">
                  {/* 좌: 공동인증서 */}
                  <div className="flex-1 flex flex-col items-center gap-5 px-10">
                    <p className="text-base text-kb-text-body font-medium">공동인증서(구 공인인증서)</p>
                    <button
                      onClick={handleLogin}
                      className="w-full py-3.5 text-base font-bold text-white transition-all hover:brightness-110"
                      style={{ backgroundColor: DAON_NAVY }}
                    >
                      공동인증서(구 공인인증서) 로그인
                    </button>
                    <div className="flex items-center gap-3 text-sm text-kb-blue">
                      <Link href="#" className="hover:underline">공동인증서(구 공인인증서) 발급</Link>
                      <span className="text-kb-border">|</span>
                      <Link href="#" className="hover:underline">인증서 관리</Link>
                    </div>
                  </div>

                  {/* 우: 금융인증서 */}
                  <div className="flex-1 flex flex-col items-center gap-5 px-10">
                    <p className="text-base text-kb-text-body font-medium">금융인증서(브라우저인증서)</p>
                    <button
                      onClick={handleLogin}
                      className="w-full py-3.5 text-base font-bold text-white transition-all hover:brightness-110"
                      style={{ backgroundColor: DAON_NAVY }}
                    >
                      금융인증서(브라우저인증서) 로그인
                    </button>
                    <div className="flex items-center gap-3 text-sm text-kb-blue">
                      <Link href="#" className="hover:underline">금융인증서 발급</Link>
                      <span className="text-kb-border">|</span>
                      <Link href="#" className="hover:underline">인증서 관리</Link>
                    </div>
                  </div>
                </div>
              )}

              {tab === 'daon인증서' && (
                <div className="flex flex-col items-center gap-5 py-10 px-10">
                  <p className="text-base text-kb-text-body font-medium flex items-center gap-1.5">
                    <DaonShieldIcon size={18} />
                    다온인증서로 간편하게 로그인하세요
                  </p>
                  <button
                    onClick={handleLogin}
                    className="w-full max-w-[360px] py-3.5 text-base font-bold text-white transition-all hover:brightness-110"
                    style={{ backgroundColor: DAON_NAVY }}
                  >
                    다온인증서 로그인
                  </button>
                  <Link href="#" className="text-sm text-kb-blue hover:underline">다온인증서 발급</Link>
                </div>
              )}

              {tab === '간편로그인' && (
                <form
                  onSubmit={(e) => { e.preventDefault(); handleLogin() }}
                  className="flex flex-col items-center gap-3 py-10 px-10"
                >
                  <p className="text-base text-kb-text-body font-medium self-center mb-1">아이디 로그인</p>
                  <input
                    type="text"
                    value={userId}
                    onChange={(e) => setUserId(e.target.value)}
                    placeholder="아이디"
                    className="w-full max-w-[360px] border border-kb-border px-4 py-3 text-base focus:outline-none focus:border-kb-text"
                  />
                  <input
                    type="password"
                    value={password}
                    onChange={(e) => setPassword(e.target.value)}
                    placeholder="비밀번호"
                    className="w-full max-w-[360px] border border-kb-border px-4 py-3 text-base focus:outline-none focus:border-kb-text"
                  />
                  <button
                    type="submit"
                    className="w-full max-w-[360px] py-3.5 text-base font-bold text-white transition-all hover:brightness-110"
                    style={{ backgroundColor: DAON_NAVY }}
                  >
                    로그인
                  </button>
                  <div className="flex items-center gap-3 text-sm text-kb-blue">
                    <Link href="#" className="hover:underline">아이디 찾기</Link>
                    <span className="text-kb-border">|</span>
                    <Link href="#" className="hover:underline">비밀번호 찾기</Link>
                    <span className="text-kb-border">|</span>
                    <Link href="#" className="hover:underline">회원가입</Link>
                  </div>
                </form>
              )}
            </div>

            {/* 하단 바로가기 */}
            <div className="border-t border-kb-border">
              <div className="flex justify-center">
                {[
                  { icon: '🔒', label: '인증센터' },
                  { icon: '⚙️', label: '로그인 설정' },
                ].map((item, i) => (
                  <button
                    key={item.label}
                    className={`flex flex-col items-center gap-1.5 py-4 px-16 text-sm text-kb-text-body
                                hover:bg-daon-surface-light transition-colors w-full
                                ${i > 0 ? 'border-l border-kb-border' : ''}`}
                  >
                    <span className="text-lg">{item.icon}</span>
                    <span>{item.label}</span>
                  </button>
                ))}
              </div>
            </div>
          </div>

          {/* 카드 하단 안내 */}
          <div className="mt-4 px-1 space-y-1">
            <p className="text-sm" style={{ color: KB_BLUE }}>
              • 인터넷뱅킹 종료 시, 안전한 금융거래를 위하여 반드시 [로그아웃]버튼을 눌러 종료하시기 바랍니다.
            </p>
            <p className="text-sm" style={{ color: KB_BLUE }}>
              • 로그인설정 버튼을 통해 자주 쓰는 로그인 방식을 설정해두면 더 간편하게 로그인 하실 수 있습니다.
            </p>
          </div>
        </div>
      </div>
    </>
  )
}

/* ── 탭 버튼 ── */
function DaonTabButton({
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
          ? 'bg-white text-kb-text border-b-2 border-kb-text'
          : 'bg-daon-surface-light text-kb-text-muted hover:bg-daon-surface'
        }`}
    >
      {children}
    </button>
  )
}

/* ── 다온 방패 아이콘 ── */
function DaonShieldIcon({ size = 16 }: { size?: number }) {
  return (
    <svg width={size} height={size} viewBox="0 0 24 24" fill="none">
      <path d="M12 2L4 6v6c0 5.5 3.5 10.7 8 12 4.5-1.3 8-6.5 8-12V6L12 2z" fill="#5A73A8" stroke="#1B3A6B" strokeWidth="1.5" />
      <text x="12" y="16" textAnchor="middle" fontSize="9" fontWeight="bold" fill="#fff">D</text>
    </svg>
  )
}
