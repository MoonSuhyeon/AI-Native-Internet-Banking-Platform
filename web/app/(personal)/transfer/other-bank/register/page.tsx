'use client'

import Link from 'next/link'

const TRANSFER_SIDEBAR = [
  {
    label: '계좌이체',
    expandable: true,
    children: [
      { label: '계좌이체', href: '/transfer/account' },
      { label: '다른금융 계좌이체', href: '/transfer/other-bank', active: true },
      { label: '다계좌이체', href: '#' },
      { label: '잔액 모으기', href: '#' },
      { label: '잔액 모으기 예약', href: '#' },
      { label: '잔액 모으기 예약 관리', href: '#' },
      { label: '퇴직급여(개인형IRP)이체', href: '#' },
      { label: '계좌종합관리 이체', href: '#' },
    ],
  },
]
const TRANSFER_SIDEBAR_BOTTOM = [
  '이체결과 조회', '자동이체', '에스크로 이체', '자동이체 서비스',
]

export default function OtherBankRegisterPage() {
  return (
    <div className="max-w-kb-container mx-auto px-6">
      <div className="flex">
        {/* ===== 사이드바 ===== */}
        <aside className="w-[180px] flex-shrink-0 border-r border-kb-border min-h-[700px] pt-6 pr-2">
          <h2 className="text-body font-bold text-kb-text mb-3 px-1">이체</h2>
          {TRANSFER_SIDEBAR.map(section => (
            <div key={section.label}>
              <div className="flex items-center justify-between px-2 py-2 text-caption text-kb-text-body font-semibold">
                <span>{section.label}</span>
                <span className="text-[10px]">˄</span>
              </div>
              <ul className="mb-2">
                {section.children.map(child => (
                  <li key={child.label}>
                    <Link href={child.href}
                      className={`block px-3 py-1.5 text-caption ${
                        child.active
                          ? 'bg-kb-yellow font-semibold text-kb-text'
                          : 'text-kb-text-muted hover:text-kb-text hover:bg-kb-beige-light'
                      }`}>
                      {child.label}
                    </Link>
                  </li>
                ))}
              </ul>
            </div>
          ))}
          <hr className="border-kb-border my-2" />
          {TRANSFER_SIDEBAR_BOTTOM.map(item => (
            <Link key={item} href="#"
              className="block px-2 py-2 text-caption text-kb-text-muted hover:text-kb-text">
              {item}
            </Link>
          ))}
          <div className="mt-4">
            <Link href="/cert"
              className="flex items-center gap-2 border border-kb-border px-3 py-2 text-caption text-kb-text-body hover:bg-kb-beige-light">
              🔒 인증센터
            </Link>
          </div>
        </aside>

        {/* ===== 본문 ===== */}
        <main className="flex-1 pl-8 pt-4 pb-12">
          {/* 브레드크럼 */}
          <div className="flex justify-end mb-2 text-[12px] text-kb-text-muted gap-1">
            <span>개인뱅킹</span><span>&gt;</span><span>이체</span><span>&gt;</span>
            <span>다른금융 계좌이체</span><span>&gt;</span>
            <span className="font-semibold text-kb-text">다른금융 등록</span>
          </div>

          <h1 className="text-[20px] font-bold text-kb-text mb-6">다른금융 조회</h1>

          {/* 3개 등록 카드 */}
          <div className="border border-kb-border rounded-xl p-6">
            <div className="grid grid-cols-3 gap-0">
              {/* 카드 1: 은행/증권 계좌등록 */}
              <div className="flex flex-col items-center px-6 py-6 border-r border-kb-border">
                <div className="w-16 h-16 rounded-full bg-gray-100 flex items-center justify-center mb-4">
                  <svg width="36" height="36" viewBox="0 0 36 36" fill="none">
                    <rect x="4" y="10" width="28" height="18" rx="2" stroke="#888" strokeWidth="2" fill="none"/>
                    <path d="M4 15h28" stroke="#888" strokeWidth="2"/>
                    <circle cx="26" cy="22" r="3" fill="none" stroke="#888" strokeWidth="1.5"/>
                    <path d="M26 20.5v1.5l1 1" stroke="#888" strokeWidth="1.2" strokeLinecap="round"/>
                    <circle cx="26" cy="22" r="5" fill="none" stroke="#FFB800" strokeWidth="1.5"/>
                    <line x1="29.5" y1="25.5" x2="32" y2="28" stroke="#FFB800" strokeWidth="1.5" strokeLinecap="round"/>
                  </svg>
                </div>
                <h3 className="text-[15px] font-bold text-kb-text mb-2">은행/증권 계좌등록</h3>
                <p className="text-[12px] text-kb-text-muted text-center leading-relaxed mb-6">
                  등록된 계좌의 거래내역조회와<br />이체를 할 수 있어요.
                </p>
                <Link
                  href="/transfer/other-bank/register/terms"
                  className="w-full py-2.5 bg-kb-yellow text-[13px] font-bold text-kb-text text-center hover:brightness-95 transition-all block"
                >
                  계좌 한번에 불러오기
                </Link>
              </div>

              {/* 카드 2: 카드사 등록 */}
              <div className="flex flex-col items-center px-6 py-6 border-r border-kb-border">
                <div className="w-16 h-16 rounded-full bg-gray-100 flex items-center justify-center mb-4">
                  <svg width="36" height="36" viewBox="0 0 36 36" fill="none">
                    <rect x="4" y="10" width="28" height="18" rx="2" stroke="#888" strokeWidth="2" fill="none"/>
                    <path d="M4 15h28" stroke="#888" strokeWidth="2"/>
                    <rect x="7" y="19" width="8" height="4" rx="1" fill="#888"/>
                    <circle cx="27" cy="21" r="5" fill="none" stroke="#FFB800" strokeWidth="1.5"/>
                    <line x1="27" y1="18.5" x2="27" y2="23.5" stroke="#FFB800" strokeWidth="1.5" strokeLinecap="round"/>
                    <line x1="24.5" y1="21" x2="29.5" y2="21" stroke="#FFB800" strokeWidth="1.5" strokeLinecap="round"/>
                  </svg>
                </div>
                <h3 className="text-[15px] font-bold text-kb-text mb-2">카드사 등록</h3>
                <p className="text-[12px] text-kb-text-muted text-center leading-relaxed mb-6">
                  등록된 카드사의 정보를<br />확인 할 수 있어요.
                </p>
                {/* 카드사·선불기관 연동은 마이데이터 사업자 연계로 동작한다. 이
                    시스템에 그 연계가 없다(docs/OPEN_ITEMS.md). 은행/증권 계좌등록만
                    실제 화면이 있다. */}
                <button disabled
                  title="카드사 연동은 마이데이터 연계가 필요합니다. 준비 중입니다."
                  className="w-full py-2.5 bg-kb-yellow text-[13px] font-bold text-kb-text text-center opacity-40 cursor-not-allowed">
                  카드 한번에 불러오기
                </button>
              </div>

              {/* 카드 3: 선불기관(핀테크사) 등록 */}
              <div className="flex flex-col items-center px-6 py-6">
                <div className="w-16 h-16 rounded-full bg-gray-100 flex items-center justify-center mb-4">
                  <svg width="36" height="36" viewBox="0 0 36 36" fill="none">
                    <rect x="10" y="6" width="16" height="24" rx="2" stroke="#888" strokeWidth="2" fill="none"/>
                    <path d="M14 10h8M14 14h5" stroke="#888" strokeWidth="1.5" strokeLinecap="round"/>
                    <circle cx="26" cy="26" r="5" fill="none" stroke="#FFB800" strokeWidth="1.5"/>
                    <line x1="26" y1="23.5" x2="26" y2="28.5" stroke="#FFB800" strokeWidth="1.5" strokeLinecap="round"/>
                    <line x1="23.5" y1="26" x2="28.5" y2="26" stroke="#FFB800" strokeWidth="1.5" strokeLinecap="round"/>
                  </svg>
                </div>
                <h3 className="text-[15px] font-bold text-kb-text mb-2">선불기관(핀테크사) 등록</h3>
                <p className="text-[12px] text-kb-text-muted text-center leading-relaxed mb-6">
                  등록된 선불기관의 잔액과<br />거래내역을 조회 할 수 있어요.
                </p>
                <button disabled
                  title="선불기관 연동은 마이데이터 연계가 필요합니다. 준비 중입니다."
                  className="w-full py-2.5 bg-kb-yellow text-[13px] font-bold text-kb-text text-center opacity-40 cursor-not-allowed">
                  선불기관 직접 선택하기
                </button>
              </div>
            </div>

            {/* 하단 안내 및 직접 입력 링크 */}
            <div className="mt-5 pt-4 border-t border-kb-border">
              <p className="text-[12px] text-kb-text-muted mb-3">
                ※ 휴대폰 인증이 불가능하거나 본인명의가 아닌 경우
              </p>
              <div className="flex items-center gap-3 text-[13px]">
                <Link href="#" className="text-kb-blue hover:underline">계좌 직접 입력하기</Link>
                <span className="text-kb-border">|</span>
                <Link href="#" className="text-kb-blue hover:underline">카드사 직접 선택하기</Link>
              </div>
            </div>
          </div>
        </main>
      </div>
    </div>
  )
}
