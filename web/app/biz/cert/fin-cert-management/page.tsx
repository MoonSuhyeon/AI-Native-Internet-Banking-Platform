'use client'

import Link from 'next/link'

const MANAGEMENT_ITEMS = [
  {
    title: '인증서 비밀번호 변경',
    path: '금융인증서 관리 > 변경을 원하는 인증서 선택 > 비밀번호 변경 선택',
  },
  {
    title: '자동연결관리',
    path: '금융인증서 관리 > 자동연결관리 선택',
  },
  {
    title: '클라우드 계정삭제',
    path: '금융인증서 관리 > 탈퇴하기 선택',
  },
  {
    title: '공유설정',
    path: '금융인증서 관리 > 인증서 관리 > 인증서 공유 관리 > 공유여부 설정',
  },
]

export default function BizFinCertManagementPage() {
  return (
    <div className="space-y-8">
      {/* 브레드크럼 */}
      <div className="flex items-center gap-2 text-caption text-kb-text-muted">
        <span>인증센터(기업)</span>
        <span>&gt;</span>
        <span>금융인증서</span>
        <span>&gt;</span>
        <span className="text-kb-text font-medium">인증서 관리</span>
      </div>

      {/* 페이지 제목 */}
      <h2 className="text-[22px] font-bold text-kb-text border-b-2 border-kb-text pb-3">
        인증서 관리
      </h2>

      {/* 안내 박스 */}
      <div className="border border-kb-border bg-kb-beige-light px-6 py-4 space-y-1.5">
        <p className="text-caption text-kb-text-body leading-relaxed">
          · 금융인증서 공통을 누르시면 금융인증서비스 화면으로 이동합니다.
        </p>
        <p className="text-caption text-kb-text-body leading-relaxed">
          · 금융인증서비스 화면에서 인증서 비밀번호 변경, 인증서 삭제, 자동연결관리, 클라우드 계정삭제를 하실 수 있습니다.
        </p>
      </div>

      {/* 관리 항목 */}
      <div className="space-y-4">
        {MANAGEMENT_ITEMS.map((item) => (
          <div key={item.title} className="flex items-start gap-2">
            <span className="text-caption text-kb-text mt-0.5 flex-shrink-0">•</span>
            <p className="text-caption">
              <span className="font-bold text-kb-text">{item.title}</span>
              <span className="text-kb-text-body"> : {item.path}</span>
            </p>
          </div>
        ))}
      </div>

      {/* 버튼 */}
      <div className="flex justify-center pt-4">
        {/* 안내문이 "금융인증서비스 화면으로 이동합니다" 라고 말하는데 버튼이
            아무 데도 가지 않았다. 인증서 목록·비밀번호 변경·폐기는 그 화면에 있다. */}
        <Link href="/cert/cert-management"
          className="px-14 py-3 bg-kb-yellow text-body font-bold text-kb-text hover:brightness-95 transition-all">
          금융인증서 관리
        </Link>
      </div>
    </div>
  )
}
