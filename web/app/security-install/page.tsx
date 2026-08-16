'use client'
import { KB_MINT, KB_PRIMARY, KB_PRIMARY_BG, KB_PRIMARY_BORDER } from '@/lib/theme'

import Link from 'next/link'
import { useRouter } from 'next/navigation'
import { useState, useEffect } from 'react'

function IconCert() {
  return (
    <svg viewBox="0 0 24 24" fill="none" className="w-5 h-5" stroke="currentColor" strokeWidth="1.8" style={{ color: KB_PRIMARY }}>
      <path d="M9 12l2 2 4-4"/>
      <path d="M12 2a7 7 0 017 7c0 4-2.5 6-4 7.5V20a1 1 0 01-1 1h-4a1 1 0 01-1-1v-3.5C7.5 15 5 13 5 9a7 7 0 017-7z"/>
    </svg>
  )
}

function IconShield() {
  return (
    <svg viewBox="0 0 24 24" fill="none" className="w-5 h-5" stroke="currentColor" strokeWidth="1.8" style={{ color: KB_PRIMARY }}>
      <path d="M12 2l7 3v6c0 4.5-3 8-7 9-4-1-7-4.5-7-9V5l7-3z"/>
      <path d="M9 12l2 2 4-4"/>
    </svg>
  )
}


function IconDownload() {
  return (
    <svg viewBox="0 0 24 24" fill="none" className="w-4 h-4" stroke="currentColor" strokeWidth="2.2">
      <path d="M12 3v12M7 11l5 5 5-5"/>
      <path d="M5 20h14"/>
    </svg>
  )
}

function IconCheck() {
  return (
    <svg viewBox="0 0 24 24" fill="none" className="w-4 h-4" stroke="currentColor" strokeWidth="2.5">
      <path d="M5 13l4 4L19 7"/>
    </svg>
  )
}

export default function SecurityInstallPage() {
  const router = useRouter()
  const [checking, setChecking] = useState(true)
  const [installed1, setInstalled1] = useState(false)
  const [installed2, setInstalled2] = useState(false)

  useEffect(() => {
    const t = setTimeout(() => setChecking(false), 2000)
    return () => clearTimeout(t)
  }, [])

  function InstallButton({ installed, onInstall }: { installed: boolean; onInstall: () => void }) {
    if (installed) {
      return (
        <button
          disabled
          className="col-span-2 py-3 text-[14px] font-bold rounded-lg flex items-center justify-center gap-2 cursor-default"
          style={{ backgroundColor: KB_PRIMARY_BG, color: KB_PRIMARY, border: `2px solid ${KB_MINT}` }}>
          <IconCheck />
          설치 완료
        </button>
      )
    }
    return (
      <button
        onClick={onInstall}
        className="col-span-2 py-3 text-[14px] font-bold text-white rounded-lg hover:opacity-85 transition-opacity flex items-center justify-center gap-2"
        style={{ backgroundColor: KB_PRIMARY }}>
        <IconDownload />
        설치하기
      </button>
    )
  }

  return (
    <div className="min-h-screen flex flex-col" style={{ backgroundColor: KB_PRIMARY_BG }}>

      {/* 헤더 */}
      <header className="bg-white shadow-sm">
        <div className="max-w-[1200px] mx-auto px-6 h-[60px] flex items-center justify-between">
          <Link href="/" className="flex items-center gap-3">
            <div className="w-[6px] h-[26px] rounded-full" style={{ backgroundColor: KB_MINT }} />
            <span className="text-[22px] font-bold tracking-[0.02em]" style={{ color: KB_PRIMARY }}>AXful Bank</span>
          </Link>
          <nav className="flex items-center gap-1 text-[14px]">
            {[
              { label: '개인', href: '/' },
              { label: '기업', href: '/biz/login' },
              { label: '자산관리', href: '#' },
              { label: '부동산', href: '#' },
              { label: '퇴직연금', href: '#' },
              { label: '카드', href: '#' },
            ].map((item, i) => (
              <span key={item.label} className="flex items-center">
                {i > 0 && <span className="text-kb-border mx-2">|</span>}
                <Link href={item.href} className="text-kb-text-muted hover:text-kb-text transition-colors">{item.label}</Link>
              </span>
            ))}
          </nav>
        </div>
      </header>

      {/* 본문 */}
      <main className="flex-1 max-w-[900px] mx-auto w-full px-6 py-10 space-y-6">

        {/* 히어로 */}
        <div className="flex items-center justify-between bg-white rounded-2xl px-8 py-6 shadow-sm" style={{ border: `1px solid ${KB_MINT}20` }}>
          <div className="space-y-2">
            <p className="text-[13px] font-semibold" style={{ color: KB_MINT }}>Security Program</p>
            <h1 className="text-[22px] font-bold text-kb-text">
              안전한 인터넷뱅킹을 위해<br />보안프로그램 설치를 권장합니다.
            </h1>
            <p className="text-[13px] text-kb-text-muted">
              통합 보안프로그램의 경우 OTP 이용 시 자율적으로 선택 설치가 가능합니다.
            </p>
          </div>
          <div className="flex-shrink-0 w-20 h-20 rounded-2xl flex items-center justify-center"
            style={{ backgroundColor: KB_PRIMARY_BG, border: `1px solid ${KB_MINT}20` }}>
            <svg viewBox="0 0 24 24" fill="none" className="w-9 h-9" stroke="currentColor" strokeWidth="1.5" style={{ color: KB_PRIMARY }}>
              <path d="M12 2l7 3v6c0 4.5-3 8-7 9-4-1-7-4.5-7-9V5l7-3z"/>
              <path d="M9 12l2 2 4-4"/>
            </svg>
          </div>
        </div>

        {/* 프로그램 카드 */}
        <div className="bg-white rounded-2xl overflow-hidden shadow-sm" style={{ border: `1px solid ${KB_MINT}20` }}>
          <div className="grid grid-cols-2 divide-x" style={{ borderColor: KB_PRIMARY_BORDER }}>

            {/* 공동인증프로그램 */}
            <div className="p-8 space-y-5">
              <div className="flex items-center gap-3">
                <div className="w-10 h-10 rounded-xl flex items-center justify-center"
                  style={{ backgroundColor: KB_PRIMARY_BG, border: `1px solid ${KB_MINT}20` }}>
                  <IconCert />
                </div>
                <h2 className="text-[17px] font-bold text-kb-text">공동인증프로그램</h2>
              </div>
              <div className="space-y-1.5">
                <p className="text-[13px] text-kb-text-muted">
                  프로그램명: <span className="font-semibold" style={{ color: KB_PRIMARY }}>WizIn-Delfino G3</span>
                </p>
                <p className="text-[13px] text-kb-text-body leading-relaxed">
                  공동인증서 로그인과 거래내역에 대한 전자서명 등 사용자 인증에 사용됩니다.
                </p>
              </div>

              {checking ? (
                <div className="flex items-center gap-2 px-4 py-3 rounded-lg text-[13px] text-kb-text-muted"
                  style={{ backgroundColor: KB_PRIMARY_BG }}>
                  <svg className="animate-spin w-4 h-4" viewBox="0 0 24 24" fill="none" style={{ color: KB_MINT }}>
                    <circle className="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" strokeWidth="4"/>
                    <path className="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8v4l3-3-3-3v4a8 8 0 00-8 8h4z"/>
                  </svg>
                  설치 여부 확인 중...
                </div>
              ) : (
                <div className="grid grid-cols-2 gap-2">
                  <InstallButton installed={installed1} onInstall={() => setInstalled1(true)} />
                  <Link href="/security-faq" className="py-2 text-[13px] rounded-lg border font-medium hover:bg-kb-primary-bg transition-colors flex items-center justify-center"
                    style={{ borderColor: KB_MINT, color: KB_PRIMARY }}>FAQ</Link>
                  <Link href="/security-guide" className="py-2 text-[13px] rounded-lg border font-medium hover:bg-kb-primary-bg transition-colors flex items-center justify-center"
                    style={{ borderColor: KB_MINT, color: KB_PRIMARY }}>이용안내</Link>
                </div>
              )}
            </div>

            {/* 통합 보안프로그램 */}
            <div className="p-8 space-y-5">
              <div className="flex items-center gap-3">
                <div className="w-10 h-10 rounded-xl flex items-center justify-center"
                  style={{ backgroundColor: KB_PRIMARY_BG, border: `1px solid ${KB_MINT}20` }}>
                  <IconShield />
                </div>
                <h2 className="text-[17px] font-bold text-kb-text">통합 보안프로그램</h2>
              </div>
              <div className="space-y-1.5">
                <p className="text-[13px] text-kb-text-muted">
                  프로그램명: <span className="font-semibold" style={{ color: KB_PRIMARY }}>AhnLab Safe Transaction</span>
                </p>
                <p className="text-[13px] text-kb-text-body leading-relaxed">
                  키보드 보안, 백신/방화벽, 피싱/파밍 방지, 단말환경 수집 등 종합적인 보안을 제공합니다.
                </p>
              </div>
              <div className="grid grid-cols-2 gap-2">
                <InstallButton installed={installed2} onInstall={() => setInstalled2(true)} />
                <button className="py-2 text-[13px] rounded-lg border font-medium hover:bg-kb-primary-bg transition-colors"
                  style={{ borderColor: KB_MINT, color: KB_PRIMARY }}>FAQ</button>
                <button className="py-2 text-[13px] rounded-lg border font-medium hover:bg-kb-primary-bg transition-colors"
                  style={{ borderColor: KB_MINT, color: KB_PRIMARY }}>이용안내</button>
              </div>
            </div>
          </div>

          <div className="border-t flex justify-center py-4" style={{ borderColor: KB_PRIMARY_BORDER }}>
            <button className="text-[14px] text-kb-text-muted flex items-center gap-1.5 hover:text-kb-text transition-colors">
              자세히보기
              <svg viewBox="0 0 20 20" fill="none" className="w-4 h-4" stroke="currentColor" strokeWidth="2">
                <path d="M5 8l5 5 5-5"/>
              </svg>
            </button>
          </div>
        </div>

        {/* 버튼 */}
        <div className="flex justify-center gap-3">
          <button
            onClick={() => window.location.reload()}
            className="px-8 py-2.5 text-[14px] font-semibold rounded-lg border-2 transition-colors hover:bg-kb-primary-bg"
            style={{ borderColor: KB_PRIMARY, color: KB_PRIMARY }}>
            새로고침
          </button>
          <button
            onClick={() => router.push('/')}
            className="px-8 py-2.5 text-[14px] font-bold text-white rounded-lg hover:opacity-85 transition-opacity"
            style={{ backgroundColor: KB_PRIMARY }}>
            홈으로 이동
          </button>
        </div>

        {/* 안내 */}
        <p className="text-[12px] text-kb-text-muted text-center">
          수동 설치 후에는{' '}
          <span className="underline cursor-pointer" style={{ color: KB_PRIMARY }} onClick={() => window.location.reload()}>
            새로고침
          </span>
          {' '}또는 재접속하여 확인하시기 바랍니다.
        </p>
      </main>

      {/* 푸터 */}
      <footer className="bg-white border-t" style={{ borderColor: KB_PRIMARY_BORDER }}>
        <div className="max-w-[900px] mx-auto px-6 py-5 space-y-2">
          <div className="flex flex-wrap gap-x-4 gap-y-1 text-[12px] text-kb-text-muted">
            {['보호금융상품등록부', '전자민원접수', '전자금융거래기본약관', '개인정보 처리방침', '신용정보활용체제', '위치기반서비스 이용약관', '경영공시'].map(item => (
              <Link key={item} href="#" className="hover:text-kb-text transition-colors">{item}</Link>
            ))}
          </div>
          <p className="text-[12px] text-kb-text-muted">Copyright AXful Bank. All Rights Reserved.</p>
        </div>
      </footer>
    </div>
  )
}
