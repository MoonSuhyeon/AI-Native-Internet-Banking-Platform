/* eslint-disable @typescript-eslint/no-explicit-any */
'use client'
import { KB_MINT, KB_PRIMARY, KB_PRIMARY_BG, KB_PRIMARY_BORDER } from '@/lib/theme'

import Link from 'next/link'
import { useState } from 'react'

const FAQ_ITEMS = [
  {
    category: '설치',
    q: '보안프로그램 설치 후에도 계속 설치 팝업이 뜹니다.',
    a: '브라우저 캐시를 삭제 후 재시도해 주세요. Chrome의 경우 설정 → 개인정보 및 보안 → 인터넷 사용 기록 삭제에서 캐시를 삭제할 수 있습니다. 그래도 해결되지 않으면 기존 보안프로그램을 완전히 삭제 후 재설치해 주세요.',
  },
  {
    category: '설치',
    q: 'Windows 보안 경고가 떠서 설치가 안 됩니다.',
    a: '설치 파일 실행 시 Windows SmartScreen 경고가 뜨는 경우 [추가 정보]를 클릭한 후 [실행] 버튼을 클릭하면 설치를 진행할 수 있습니다. AXful Bank 공식 사이트에서 다운로드한 파일은 안전합니다.',
  },
  {
    category: '설치',
    q: '설치 중 오류가 발생합니다.',
    a: '기존에 설치된 동일 프로그램을 먼저 제거한 후 재설치해 주세요. 제어판 → 프로그램 추가/제거에서 해당 프로그램을 삭제할 수 있습니다. 삭제 후 PC를 재시작하고 다시 설치해 보세요.',
  },
  {
    category: '설치',
    q: '설치 파일이 다운로드되지 않습니다.',
    a: '브라우저의 팝업 차단 설정을 확인해 주세요. Chrome의 경우 주소창 오른쪽의 팝업 차단 아이콘을 클릭하여 허용으로 변경하거나, 설정 → 개인정보 및 보안 → 사이트 설정 → 팝업 및 리디렉션에서 axful.com을 허용 목록에 추가해 주세요.',
  },
  {
    category: '이용',
    q: '보안프로그램을 설치해야만 인터넷뱅킹을 이용할 수 있나요?',
    a: '공동인증프로그램(WizIn-Delfino G3)은 공동인증서 로그인 시 필수입니다. 통합 보안프로그램(AhnLab Safe Transaction)은 권장 사항이며, OTP 이용 시 자율 선택이 가능합니다. AXful 인증서를 이용하는 경우 통합 보안프로그램 없이도 이용 가능합니다.',
  },
  {
    category: '이용',
    q: '보안프로그램이 PC 속도를 느리게 합니다.',
    a: '통합 보안프로그램은 인터넷뱅킹 이용 시에만 활성화되며 평상시에는 최소한의 리소스만 사용합니다. 만약 지속적으로 PC 성능에 영향을 준다면 프로그램을 최신 버전으로 업데이트하거나 재설치해 보세요.',
  },
  {
    category: '이용',
    q: '맥(Mac) 또는 리눅스에서도 사용할 수 있나요?',
    a: '현재 보안프로그램은 Windows 환경만 지원합니다. macOS 사용자의 경우 Safari 또는 Chrome 브라우저를 통해 일부 서비스를 이용하실 수 있으나, 일부 기능에 제한이 있을 수 있습니다. 자세한 내용은 고객센터(1588-0000)로 문의해 주세요.',
  },
  {
    category: '오류',
    q: '로그인 시 인증서 오류가 발생합니다.',
    a: '공동인증프로그램이 정상적으로 설치되어 있는지 확인해 주세요. 설치가 되어 있다면 인증서의 유효기간을 확인하고, 만료된 경우 인증센터에서 재발급을 받아야 합니다. 그래도 해결되지 않으면 프로그램 재설치 후 브라우저를 재시작해 주세요.',
  },
  {
    category: '오류',
    q: '키보드 보안 프로그램 충돌이 발생합니다.',
    a: '다른 보안 소프트웨어나 키보드 매크로 프로그램과 충돌할 수 있습니다. 다른 보안 프로그램을 일시적으로 비활성화하거나 충돌하는 프로그램을 종료한 후 이용해 주세요. 지속적인 문제 발생 시 고객센터로 문의해 주세요.',
  },
  {
    category: '업데이트',
    q: '보안프로그램 업데이트는 어떻게 하나요?',
    a: '인터넷뱅킹 접속 시 최신 버전이 감지되면 자동으로 업데이트 안내 팝업이 표시됩니다. 팝업에서 [업데이트] 버튼을 클릭하면 최신 버전으로 자동 업데이트됩니다. 수동으로 업데이트하려면 기존 프로그램을 삭제 후 보안프로그램 설치 페이지에서 새로 설치해 주세요.',
  },
]

const CATEGORIES = ['전체', '설치', '이용', '오류', '업데이트']

function ChevronIcon({ open }: { open: boolean }) {
  return (
    <svg viewBox="0 0 20 20" fill="none" className={`w-5 h-5 transition-transform duration-200 flex-shrink-0 ${open ? 'rotate-180' : ''}`}
      stroke="currentColor" strokeWidth="2">
      <path d="M5 8l5 5 5-5"/>
    </svg>
  )
}

export default function SecurityFaqPage() {
  const [activeCategory, setActiveCategory] = useState('전체')
  const [openIndex, setOpenIndex] = useState<number | null>(null)

  const filtered = FAQ_ITEMS.filter(item =>
    activeCategory === '전체' || item.category === activeCategory
  )

  return (
    <div className="min-h-screen flex flex-col" style={{ backgroundColor: KB_PRIMARY_BG }}>

      {/* 헤더 */}
      <header className="bg-white shadow-sm">
        <div className="max-w-[1200px] mx-auto px-6 h-[60px] flex items-center justify-between">
          <Link href="/" className="flex items-center gap-3">
            <div className="w-[6px] h-[26px] rounded-full" style={{ backgroundColor: KB_MINT }} />
            <span className="text-[22px] font-bold tracking-[0.02em]" style={{ color: KB_PRIMARY }}>AXful Bank</span>
          </Link>
          <Link href="/security-install"
            className="text-[14px] font-semibold px-4 py-1.5 rounded-lg border-2 transition-colors hover:bg-kb-primary-bg"
            style={{ borderColor: KB_PRIMARY, color: KB_PRIMARY }}>
            보안프로그램 설치
          </Link>
        </div>
      </header>

      {/* 본문 */}
      <main className="flex-1 max-w-[900px] mx-auto w-full px-6 py-10 space-y-6">

        <div>
          <p className="text-[13px] font-semibold mb-1" style={{ color: KB_MINT }}>FAQ</p>
          <h1 className="text-[24px] font-bold text-kb-text mb-2">자주 묻는 질문</h1>
          <p className="text-[14px] text-kb-text-muted">보안프로그램 설치·이용과 관련하여 자주 묻는 질문을 모았습니다.</p>
        </div>

        {/* 카테고리 탭 */}
        <div className="flex gap-2 flex-wrap">
          {CATEGORIES.map(cat => (
            <button key={cat} onClick={() => { setActiveCategory(cat); setOpenIndex(null) }}
              className="px-4 py-1.5 rounded-full text-[13px] font-semibold transition-colors"
              style={activeCategory === cat
                ? { backgroundColor: KB_PRIMARY, color: 'white' }
                : { backgroundColor: 'white', color: KB_PRIMARY, border: '1px solid #5BC9A8' }}>
              {cat}
            </button>
          ))}
        </div>

        {/* FAQ 아코디언 */}
        <div className="space-y-2">
          {filtered.map((item) => {
            const globalIndex = FAQ_ITEMS.indexOf(item)
            const isOpen = openIndex === globalIndex
            return (
              <div key={globalIndex} className="bg-white rounded-xl overflow-hidden shadow-sm"
                style={{ border: `1px solid ${isOpen ? KB_MINT : KB_PRIMARY_BORDER}` }}>
                <button
                  onClick={() => setOpenIndex(isOpen ? null : globalIndex)}
                  className="w-full flex items-center justify-between px-6 py-4 text-left hover:bg-kb-primary-surface transition-colors">
                  <div className="flex items-center gap-3">
                    <span className="text-[11px] font-bold px-2 py-0.5 rounded-full flex-shrink-0"
                      style={{ backgroundColor: KB_PRIMARY_BG, color: KB_PRIMARY, border: `1px solid ${KB_MINT}20` }}>
                      {item.category}
                    </span>
                    <span className="text-[14px] font-semibold text-kb-text">{item.q}</span>
                  </div>
                  <ChevronIcon open={isOpen} />
                </button>
                {isOpen && (
                  <div className="px-6 pb-5 pt-0">
                    <div className="border-t pt-4" style={{ borderColor: KB_PRIMARY_BORDER }}>
                      <p className="text-[14px] text-kb-text-body leading-relaxed">{item.a}</p>
                    </div>
                  </div>
                )}
              </div>
            )
          })}
        </div>

        {/* 추가 문의 */}
        <div className="bg-white rounded-2xl px-6 py-5 flex items-center justify-between shadow-sm"
          style={{ border: `1px solid ${KB_MINT}20` }}>
          <div>
            <p className="text-[15px] font-bold text-kb-text mb-0.5">해결되지 않으셨나요?</p>
            <p className="text-[13px] text-kb-text-muted">고객센터에 문의하시면 빠르게 도와드리겠습니다.</p>
          </div>
          <Link href="tel:15880000"
            className="px-5 py-2.5 text-[14px] font-bold text-white rounded-lg hover:opacity-85 transition-opacity flex-shrink-0"
            style={{ backgroundColor: KB_PRIMARY }}>
            1588-0000
          </Link>
        </div>

        {/* 하단 버튼 */}
        <div className="flex gap-3">
          <Link href="/security-install"
            className="px-6 py-2.5 text-[14px] font-bold text-white rounded-lg hover:opacity-85 transition-opacity"
            style={{ backgroundColor: KB_PRIMARY }}>
            보안프로그램 설치하기
          </Link>
          <Link href="/security-guide"
            className="px-6 py-2.5 text-[14px] font-semibold rounded-lg border-2 transition-colors hover:bg-kb-primary-bg"
            style={{ borderColor: KB_PRIMARY, color: KB_PRIMARY }}>
            이용안내 보기
          </Link>
        </div>
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
