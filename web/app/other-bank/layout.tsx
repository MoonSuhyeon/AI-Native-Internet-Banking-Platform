import DaonHeader from '@/components/other-bank/DaonHeader'
import DaonMyMenu from '@/components/other-bank/DaonMyMenu'
import Link from 'next/link'

const FOOTER_LINKS_TOP = [
  '보호금융상품등록부', '전자민원접수', '전자금융거래기본약관',
  '개인정보 처리방침', '신용정보활용체제', '위치기반서비스 이용약관', '경영공시',
]
const FOOTER_LINKS_BOTTOM = [
  '이용상담', '보안프로그램', '사고신고', '그룹 내 고객정보 제공안내',
  '스튜어드십 코드', '다온인증서 제휴문의', '다온 뱅킹 Ads',
]
const FOOTER_DROPDOWNS = [
  '다온금융그룹네트워크', '대표전화 1599-0000', '챗봇/채팅/이메일상담(24시간)', '비교조회서비스',
]

export default function OtherBankLayout({ children }: { children: React.ReactNode }) {
  return (
    <div className="min-h-screen bg-white">
      <DaonHeader />

      <div className="min-h-[calc(100vh-300px)]">
        {children}
      </div>

      {/* 푸터 */}
      <footer className="border-t border-kb-border bg-daon-surface-light">
        <div className="max-w-kb-container mx-auto px-6 py-5">
          <div className="flex flex-wrap gap-x-4 gap-y-1 mb-2">
            {FOOTER_LINKS_TOP.map((link) => (
              <Link key={link} href="#" className={`text-sm hover:underline ${link === '개인정보 처리방침' ? 'text-kb-blue font-semibold' : 'text-kb-text-muted'}`}>
                {link}
              </Link>
            ))}
          </div>
          <div className="flex flex-wrap gap-x-4 gap-y-1 mb-4">
            {FOOTER_LINKS_BOTTOM.map((link) => (
              <Link key={link} href="#" className="text-sm text-kb-text-muted hover:underline">{link}</Link>
            ))}
          </div>
          <p className="text-sm text-kb-text-muted">
            사업자 등록번호 : 000-00-00000 &nbsp;&nbsp; 서울특별시 중구 다온로 1(다온동) &nbsp;&nbsp; 대표 : 홍대표
          </p>
        </div>
        <div>
          <div className="max-w-kb-container mx-auto px-6 py-3 flex items-center justify-between">
            <div className="flex items-center gap-3">
              {FOOTER_DROPDOWNS.map((label) => (
                <button key={label}
                  className="flex items-center gap-1.5 border border-kb-border px-4 py-2
                             text-sm text-kb-text-body bg-white hover:bg-daon-surface transition-colors">
                  {label} <span className="text-xs text-kb-text-muted">▾</span>
                </button>
              ))}
            </div>
            <div className="flex items-center gap-2">
              {['f', '📷', '▶', 'B'].map((label) => (
                <Link key={label} href="#"
                  className="w-10 h-10 rounded-full border border-kb-border flex items-center justify-center text-base font-bold text-kb-text-muted hover:bg-daon-surface hover:text-kb-text transition-colors">
                  {label}
                </Link>
              ))}
            </div>
          </div>
          <div className="max-w-kb-container mx-auto px-6 pb-4">
            <p className="text-sm text-kb-text-muted">Copyright DAON bank. All Rights Reserved.</p>
          </div>
        </div>
      </footer>

      <DaonMyMenu />
    </div>
  )
}
