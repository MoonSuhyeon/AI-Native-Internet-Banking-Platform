'use client'

import Link from 'next/link'
import { useState } from 'react'
import { useRouter } from 'next/navigation'

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

const REQUIRED_TERMS = [
  '계좌통합관리서비스 이용약관',
  '개인(신용)정보수집·이용동의서(계좌통합관리)',
  '[고유식별정보 처리 동의]',
  '[통신사 이용약관 동의]',
  '[휴대폰본인확인 개인정보수집이용동의]',
  '[휴대폰본인확인서비스 이용약관 동의]',
  '오픈뱅킹 서비스 이용약관 및 설명서',
  '개인(신용)정보 수집·이용·제공 동의서(오픈뱅킹용)',
]

const REQUIRED_TERMS_EXTRA = [
  '[오픈뱅킹]금융정보조회 및 자동계좌이체 약관',
  '금융거래정보제공 동의서(오픈뱅킹용)',
  '개인(신용)정보 수집·이용·제공 동의서 (오픈뱅킹 접속 단말기 정보 수집용)',
]

const MARKETING_TERMS = [
  '개인(신용)정보 수집·이용 동의서(오픈뱅킹 활용 상품서비스 안내 등)',
]

const EMAIL_DOMAINS = ['naver.com', 'gmail.com', 'daum.net', 'kakao.com', 'nate.com', '직접입력']

export default function OtherBankTermsPage() {
  const router = useRouter()
  const [requiredOpen, setRequiredOpen] = useState(true)
  const [requiredExtraOpen, setRequiredExtraOpen] = useState(true)
  const [marketingOpen, setMarketingOpen] = useState(true)
  const [emailId, setEmailId] = useState('')
  const [emailDomain, setEmailDomain] = useState('naver.com')

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
          {/* 타이틀 + 스텝 */}
          <div className="flex items-center justify-between mb-4">
            <h1 className="text-[20px] font-bold text-kb-text">다른금융 조회</h1>
            <div className="flex items-center gap-1">
              <span className="px-4 py-1.5 bg-kb-yellow text-[13px] font-bold text-kb-text rounded-full whitespace-nowrap">
                1. 서비스 이용 동의
              </span>
              {[2, 3, 4].map(n => (
                <span key={n}
                  className="w-8 h-8 rounded-full border border-kb-border flex items-center justify-center text-[13px] text-kb-text-muted">
                  {n}
                </span>
              ))}
            </div>
          </div>

          {/* 안내 문구 */}
          <div className="border border-kb-border bg-kb-beige-light px-4 py-3 mb-5 text-[13px] text-kb-text-body">
            · 다른금융 계좌등록을 위하여 약관에 동의하고 서비스를 신청합니다.
          </div>

          {/* 조회/출금 동의 섹션 */}
          <div className="mb-6">
            <h2 className="text-[14px] font-bold text-kb-blue mb-3">조회/출금 동의</h2>

            {/* 필수 이용약관 */}
            <div className="border border-kb-border mb-2">
              <button
                onClick={() => setRequiredOpen(v => !v)}
                className="w-full flex items-center justify-between px-4 py-3 bg-kb-beige-light hover:bg-kb-beige"
              >
                <div className="flex items-center gap-2">
                  <span className="text-green-600 font-bold text-[15px]">✓</span>
                  <span className="text-[13px] font-semibold text-kb-text">필수 이용약관 및 동의서</span>
                </div>
                <span className="text-[11px] text-kb-text-muted">{requiredOpen ? '∧' : '∨'}</span>
              </button>

              {requiredOpen && (
                <ul className="divide-y divide-kb-border">
                  {REQUIRED_TERMS.map(term => (
                    <li key={term}
                      className="flex items-center justify-between px-5 py-3 hover:bg-kb-beige-light cursor-pointer">
                      <span className="text-[13px] text-kb-text-body">{term}</span>
                      <span className="text-kb-text-muted text-[13px]">›</span>
                    </li>
                  ))}

                  {/* 동의 항목 접기 */}
                  <li>
                    <button
                      onClick={() => setRequiredExtraOpen(v => !v)}
                      className="w-full flex items-center gap-1 px-5 py-2 text-[12px] text-kb-text-muted hover:bg-kb-beige-light"
                    >
                      동의 항목 {requiredExtraOpen ? '∧' : '∨'}
                    </button>
                    {requiredExtraOpen && (
                      <ul className="divide-y divide-kb-border">
                        {REQUIRED_TERMS_EXTRA.map(term => (
                          <li key={term}
                            className="flex items-center justify-between px-5 py-3 hover:bg-kb-beige-light cursor-pointer bg-gray-50">
                            <span className="text-[13px] text-kb-text-body">{term}</span>
                            <span className="text-kb-text-muted text-[13px]">›</span>
                          </li>
                        ))}
                      </ul>
                    )}
                  </li>
                </ul>
              )}
            </div>

            {/* 마케팅 선택 동의 */}
            <div className="border border-kb-border mb-2">
              <button
                onClick={() => setMarketingOpen(v => !v)}
                className="w-full flex items-center justify-between px-4 py-3 bg-kb-beige-light hover:bg-kb-beige"
              >
                <div className="flex items-center gap-2">
                  <span className="text-green-600 font-bold text-[15px]">✓</span>
                  <span className="text-[13px] font-semibold text-kb-text">마케팅 및 광고성 정보 선택 동의</span>
                </div>
                <span className="text-[11px] text-kb-text-muted">{marketingOpen ? '∧' : '∨'}</span>
              </button>
              {marketingOpen && (
                <ul className="divide-y divide-kb-border">
                  {MARKETING_TERMS.map(term => (
                    <li key={term}
                      className="flex items-center justify-between px-5 py-3 hover:bg-kb-beige-light cursor-pointer">
                      <span className="text-[13px] text-kb-text-body">{term}</span>
                      <span className="text-kb-text-muted text-[13px]">›</span>
                    </li>
                  ))}
                </ul>
              )}
            </div>

            {/* 안내 */}
            <div className="mt-3 text-[12px] text-kb-text-muted space-y-0.5">
              <p>ⓘ 위 [선택] 항목의 서비스는 상품서비스 가입 이전에 처리 또는 동시 처리되며, 아래 경로를 통해 변경이 가능합니다.</p>
              <p className="pl-3">※ 경로: 전체서비스 &gt; 고객센터 &gt; 고객정보 관리</p>
            </div>
          </div>

          {/* 이메일정보 */}
          <div className="border border-kb-border mb-4">
            <div className="flex items-center justify-between px-4 py-2 bg-kb-beige-light border-b border-kb-border">
              <span className="text-[13px] font-semibold text-kb-text">이메일정보</span>
              <span className="text-[12px] text-red-500">* 필수입력항목</span>
            </div>
            <div className="px-4 py-4">
              <div className="flex items-center gap-2">
                <span className="text-[13px] text-red-500 font-semibold">*</span>
                <span className="text-[13px] text-kb-text w-16">이메일</span>
                <input
                  type="text"
                  value={emailId}
                  onChange={e => setEmailId(e.target.value)}
                  className="border border-kb-border px-3 py-1.5 text-[13px] w-40"
                  placeholder=""
                />
                <span className="text-[13px] text-kb-text">@</span>
                <select
                  value={emailDomain}
                  onChange={e => setEmailDomain(e.target.value)}
                  className="border border-kb-border px-2 py-1.5 text-[13px] w-36"
                >
                  {EMAIL_DOMAINS.map(d => (
                    <option key={d} value={d}>{d}</option>
                  ))}
                </select>
              </div>
              <p className="text-[12px] text-kb-text-muted mt-2 pl-[80px]">
                ⓘ 금융정보 제공내용 통지를 위한 이메일을 입력하세요.
              </p>
            </div>
          </div>

          {/* 권유직원 */}
          <div className="border border-kb-border mb-6">
            <div className="flex items-center px-4 py-2 bg-kb-beige-light border-b border-kb-border">
              <span className="text-[13px] font-semibold text-kb-text">권유직원</span>
            </div>
            <div className="px-4 py-4 flex items-center gap-3">
              <span className="text-[13px] text-red-500 font-semibold">*</span>
              <span className="text-[13px] text-kb-text w-16">공유직원</span>
              <select className="border border-kb-border px-2 py-1.5 text-[13px] w-40">
                <option>공유직원없음</option>
              </select>
            </div>
          </div>

          {/* 확인/취소 버튼 */}
          <div className="flex justify-center gap-3">
            <button
              onClick={() => router.back()}
              className="px-14 py-3 border border-kb-border text-[14px] text-kb-text-body hover:bg-kb-beige-light"
            >
              취소
            </button>
            {/* 동의 다음 단계인 '계좌 불러오기' 는 금융결제원 오픈뱅킹 연계로
                동작한다. 그 연계가 없어 여기서 멈춘다(docs/OPEN_ITEMS.md).
                눌러도 아무 일이 없던 버튼을 그대로 두면 등록됐다고 믿는다. */}
            <button disabled
              title="계좌 불러오기는 금융결제원 오픈뱅킹 연계가 필요합니다. 준비 중입니다."
              className="px-14 py-3 bg-kb-yellow text-[14px] font-bold text-kb-text opacity-40 cursor-not-allowed">
              확인
            </button>
          </div>
        </main>
      </div>
    </div>
  )
}
