'use client'
import { KB_PRIMARY_DARK } from '@/lib/theme'

import Link from 'next/link'
import { useState } from 'react'

const CERT_BIZ_TABS = [
  { label: 'AXful인증서(기업)', href: '/cert-biz/kb-cert-issue' },
  { label: 'AXful인증서', href: '/cert-biz' },
  { label: '공동인증서', href: '/cert-biz' },
  { label: '금융인증서', href: '/cert-biz' },
  { label: '전자세금용인증서', href: '/cert-biz' },
  { label: '인증서 발급안내', href: '/cert-biz' },
  { label: '인증센터 FAQ', href: '/cert-biz' },
]

const SIDEBAR_GROUPS = [
  {
    label: 'AXful인증서(기업)',
    expanded: true,
    items: [
      { label: 'AXful인증서(기업)이란?', href: '#' },
      { label: '인증서 발급/재발급', href: '/cert-biz/kb-cert-issue', active: true },
      { label: '인증서 관리', href: '#' },
      { label: '영수증/세금계산서', href: '#' },
    ],
  },
  { label: 'AXful인증서', href: '#', items: [] },
  { label: '공동인증서', href: '#', items: [] },
  { label: '금융인증서', href: '#', items: [] },
  { label: '전자세금용 인증서', href: '#', items: [] },
  { label: '인증서 발급안내', href: '#', items: [] },
  { label: '인증센터 FAQ', href: '#', items: [] },
]

export default function KBCertBizIssuePage() {
  const [openGroup, setOpenGroup] = useState('AXful인증서(기업)')

  return (
    <>
      {/* 다크브라운 서브탭 */}
      <nav style={{ backgroundColor: KB_PRIMARY_DARK }}>
        <div className="max-w-kb-container mx-auto px-6">
          <div className="flex">
            {CERT_BIZ_TABS.map((tab) => (
              <Link
                key={tab.label}
                href={tab.href}
                className={`px-5 py-4 text-base transition-colors whitespace-nowrap
                  ${tab.href === '/cert-biz/kb-cert-issue'
                    ? 'text-white font-bold border-b-2 border-white'
                    : 'text-white/60 hover:text-white hover:bg-black/20'
                  }`}
              >
                {tab.label}
              </Link>
            ))}
          </div>
        </div>
      </nav>

      {/* 본문 */}
      <div className="max-w-kb-container mx-auto px-6 py-6 flex gap-6">

        {/* ── 좌측 사이드바 ── */}
        <aside className="w-[200px] flex-shrink-0">
          <h2 className="text-base font-bold text-kb-text mb-5 px-2">인증센터(기업)</h2>
          <nav className="border border-kb-border">
            {SIDEBAR_GROUPS.map((group) => (
              <div key={group.label}>
                <button
                  onClick={() => setOpenGroup(openGroup === group.label ? '' : group.label)}
                  className={`w-full flex items-center justify-between px-3 py-2.5 text-sm font-medium transition-colors
                    ${openGroup === group.label ? 'bg-kb-beige-light text-kb-text' : 'text-kb-text-body hover:bg-kb-beige-light'}
                    border-t border-kb-border first:border-t-0`}
                >
                  <span>{group.label}</span>
                  {group.items.length > 0 && (
                    <span className="text-xs text-kb-text-muted">{openGroup === group.label ? '∧' : '∨'}</span>
                  )}
                </button>
                {openGroup === group.label && group.items.length > 0 && (
                  <ul>
                    {group.items.map((item) => (
                      <li key={item.label}>
                        <Link
                          href={item.href}
                          className={`block px-4 py-2 text-sm transition-colors
                            ${'active' in item && item.active
                              ? 'bg-kb-yellow font-semibold text-kb-text'
                              : 'text-kb-text-muted hover:bg-kb-beige-light'
                            }`}
                        >
                          {item.label}
                        </Link>
                      </li>
                    ))}
                  </ul>
                )}
              </div>
            ))}
          </nav>

          {/* 제휴신청 배너 */}
          <div className="mt-4 border border-kb-border bg-[#FFFBE6] p-4">
            <p className="text-[11px] text-kb-text-muted mb-0.5">1,500만명의 선택</p>
            <p className="text-sm font-bold text-kb-text leading-snug mb-2">
              AXful인증서 제휴신청
            </p>
            <Link href="#" className="text-[11px] text-kb-blue hover:underline">바로가기 &gt;</Link>
            <div className="flex justify-end mt-1">
              <div className="w-10 h-10 bg-kb-yellow rounded flex items-center justify-center">
                <span className="text-[9px] font-extrabold text-kb-text leading-tight text-center">AX</span>
              </div>
            </div>
          </div>
        </aside>

        {/* ── 메인 콘텐츠 ── */}
        <main className="flex-1 min-w-0">
          {/* 브레드크럼 */}
          <div className="flex items-center gap-1 text-sm text-kb-text-muted mb-4">
            <Link href="/cert-biz" className="hover:underline">인증센터(기업)</Link>
            <span>&gt;</span>
            <Link href="/cert-biz" className="hover:underline">AXful인증서(기업)</Link>
            <span>&gt;</span>
            <Link href="/cert-biz/kb-cert-issue" className="hover:underline">인증서 발급/재발급</Link>
            <span>&gt;</span>
            <span className="text-kb-text font-medium">AXful인증서(기업) 인증서 발급</span>
          </div>

          <h1 className="text-2xl font-bold text-kb-text mb-5">AXful인증서(기업) 인증서 발급</h1>

          {/* 안내 박스 */}
          <div className="border border-[#E6D080] bg-[#FFFBE6] px-5 py-4 mb-4 space-y-1">
            <p className="text-sm text-kb-text-body">
              • AXful인증서(기업)는 개인사업자/법인 고객이 발급할 수 있습니다.
            </p>
            <p className="text-sm text-kb-text-body">
              • 다만, 법인과 고유번호/납세번호가 있는 단체의 경우 영업점 방문이 필요합니다.
            </p>
          </div>

          <Link href="#" className="text-sm text-kb-blue hover:underline mb-6 inline-block">
            AXful인증서(기업)이란? &gt;
          </Link>

          {/* 통합용 섹션 */}
          <div className="mb-5">
            <h2 className="text-base font-bold text-kb-text">AXful인증서(기업) 통합용</h2>
            <p className="text-sm text-kb-text-muted mt-0.5">
              AXful Bank 인터넷뱅킹 Master ID와 연결되는 인증서입니다.
            </p>
          </div>

          {/* 발급 카드 2종 */}
          <div className="grid grid-cols-2 gap-6">
            {/* 카드 1: 비대면 실명 확인 */}
            <div className="border border-kb-border-dark rounded-xl p-6 flex flex-col gap-4">
              <h3 className="text-base font-bold text-kb-text">비대면 실명 확인을 통한 발급</h3>
              <div className="text-sm text-kb-text-body space-y-1 flex-1">
                <p>신분증만 있으면 발급 받을 수 있어요.</p>
                <p className="text-kb-text-muted">
                  ※ 법인·고유번호/납세번호가 있는 단체의 경우 영업점 1회 신청전로 필요
                </p>
              </div>
              {/* 발급 화면은 하나다. 카드마다 화면을 따로 만들면 한쪽만 고치게 된다. */}
              <Link href="/cert/axful-cert-issue"
                className="btn-primary w-full py-3 text-base font-bold text-center">
                인증서 발급
              </Link>
            </div>

            {/* 카드 2: 1회용 영업점 신청 번호 */}
            <div className="border border-kb-border-dark rounded-xl p-6 flex flex-col gap-4">
              <h3 className="text-base font-bold text-kb-text">1회용 영업점 신청 번호를 통한 발급</h3>
              <div className="text-sm text-kb-text-body flex-1">
                <p>
                  영업점에서 처음 1회용 신청 번호를 통해 신분증이나 법인 서류 없이
                  간편하게 인증서를 발급받을 수 있어요.
                </p>
              </div>
              <Link href="/cert/axful-cert-issue"
                className="btn-primary w-full py-3 text-base font-bold text-center">
                인증서 발급
              </Link>
            </div>
          </div>
        </main>
      </div>
    </>
  )
}
