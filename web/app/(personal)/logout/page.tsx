'use client'
import { KB_MINT, KB_PRIMARY, KB_PRIMARY_BG, KB_PRIMARY_BORDER } from '@/lib/theme'

import Link from 'next/link'
import { useEffect } from 'react'
import { useSearchParams } from 'next/navigation'
import { Suspense } from 'react'

function LogoutContent() {
  const searchParams = useSearchParams()
  const isManual = searchParams.get('reason') === 'manual'

  useEffect(() => {
    localStorage.removeItem('access_token')
    localStorage.removeItem('accessToken')
    localStorage.removeItem('refreshToken')
    localStorage.removeItem('sessionExpiry')
    localStorage.removeItem('user')
    localStorage.removeItem('customerId')
    sessionStorage.clear()
  }, [])

  return (
    <div className="min-h-[calc(100vh-260px)] py-12" style={{ backgroundColor: KB_PRIMARY_BG }}>
      <div className="w-full max-w-[540px] mx-auto bg-white rounded-2xl shadow-sm overflow-hidden" style={{ border: `1px solid ${KB_MINT}20` }}>

        {/* 본문 */}
        <div className="flex flex-col items-center px-12 py-14 gap-5">
          {/* 아이콘 */}
          <div className="w-16 h-16 rounded-2xl flex items-center justify-center" style={{ backgroundColor: KB_PRIMARY_BG }}>
            {isManual ? (
              <svg viewBox="0 0 24 24" fill="none" className="w-8 h-8" stroke="currentColor" strokeWidth="1.8" style={{ color: KB_PRIMARY }}>
                <path d="M9 21H5a2 2 0 01-2-2V5a2 2 0 012-2h4"/>
                <polyline points="16 17 21 12 16 7"/>
                <line x1="21" y1="12" x2="9" y2="12"/>
              </svg>
            ) : (
              <svg viewBox="0 0 24 24" fill="none" className="w-8 h-8" stroke="currentColor" strokeWidth="1.8" style={{ color: KB_PRIMARY }}>
                <circle cx="12" cy="12" r="10"/>
                <line x1="12" y1="8" x2="12" y2="12"/>
                <line x1="12" y1="16" x2="12.01" y2="16"/>
              </svg>
            )}
          </div>

          <h2 className="text-[22px] font-bold text-kb-text">
            {isManual ? '정상적으로 로그아웃되었습니다.' : '자동으로 로그아웃되었습니다.'}
          </h2>

          <p className="text-[14px] text-kb-text-body text-center leading-relaxed">
            {isManual ? (
              <>
                AXful Bank axful.com을 방문해주셔서 감사합니다.<br />
                즐거운 하루 보내시기 바랍니다.<br />
                <span style={{ color: KB_PRIMARY }}>확인을 누르시면 메인화면으로 이동합니다.</span>
              </>
            ) : (
              <>
                고객님의 안전한 금융거래를 위해 로그인 후 10분 동안<br />
                서비스 이용이 없어 자동 로그아웃 되었습니다.<br />
                이용해 주셔서 감사합니다.<br />
                <span style={{ color: KB_PRIMARY }}>확인을 누르시면 메인화면으로 이동합니다.</span>
              </>
            )}
          </p>

          <Link href="/"
            className="w-full py-3.5 text-center text-[15px] font-bold text-white rounded-xl hover:opacity-85 transition-opacity mt-2"
            style={{ backgroundColor: KB_PRIMARY }}>
            확인
          </Link>
        </div>

        {/* AXful 인증서 배너 */}
        <div className="border-t mx-6 mb-6" style={{ borderColor: KB_PRIMARY_BORDER }} />
        <div className="mx-6 mb-6 flex items-center gap-4 rounded-xl p-5" style={{ backgroundColor: KB_PRIMARY_BG, border: `1px solid ${KB_MINT}20` }}>
          <div className="w-10 h-10 rounded-xl flex items-center justify-center flex-shrink-0 text-white text-[13px] font-bold"
            style={{ backgroundColor: KB_PRIMARY }}>
            AX
          </div>
          <div>
            <p className="text-[13px] text-kb-text-body">
              간편하고 안전하게 사용가능한 AXful Bank인증서를<br />
              통해 다양한 금융서비스를 이용해보세요!
            </p>
            <Link href="/cert" className="text-[13px] hover:underline" style={{ color: KB_PRIMARY }}>
              AXful Bank인증서 알아보기 ›
            </Link>
          </div>
        </div>
      </div>
    </div>
  )
}

export default function LogoutPage() {
  return (
    <Suspense>
      <LogoutContent />
    </Suspense>
  )
}
