import Header from '@/components/layout/Header'
import Link from 'next/link'
import FooterConsultButton from '@/components/layout/FooterConsultButton'

const FOOTER_LINKS_TOP = [
  '보호금융상품등록부', '전자민원접수', '전자금융거래기본약관',
  '개인정보 처리방침', '신용정보활용체제', '위치기반서비스 이용약관', '경영공시',
]
const FOOTER_LINKS_BOTTOM = [
  '이용상담', '보안프로그램', '사고신고', '그룹 내 고객정보 제공안내',
  '스튜어드십 코드', 'AXful인증서 제류문의', 'AXful 뱅킹 Ads',
]
/** 펼칠 내용이 없는 것들. ▾ 를 붙이면 열린다고 약속하는 셈이라 표시로만 둔다. */
const FOOTER_INFO = ['AXful금융그룹네트워크', '비교조회서비스']

export default function CertCpsLayout({ children }: { children: React.ReactNode }) {
  return (
    <div className="min-h-screen bg-white">
      <Header />
      <main className="max-w-kb-container mx-auto px-8 py-10">
        {children}
      </main>
      <footer className="border-t border-kb-border bg-kb-beige-light">
        <div className="max-w-kb-container mx-auto px-6 py-5">
          <div className="flex flex-wrap gap-x-1 gap-y-1 mb-1">
            {FOOTER_LINKS_TOP.map((link, i) => (
              <span key={link} className="flex items-center gap-3">
                {i > 0 && <span className="text-kb-border">|</span>}
                <Link href="#" className={`text-sm hover:underline text-kb-text ${['개인정보 처리방침', '전자민원접수', '전자금융거래기본약관', '신용정보활용체제', '위치기반서비스 이용약관', '경영공시'].includes(link) ? 'font-semibold' : ''}`}>
                  {link}
                </Link>
              </span>
            ))}
          </div>
          <div className="flex flex-wrap gap-x-1 gap-y-1 mb-3">
            {FOOTER_LINKS_BOTTOM.map((link, i) => (
              <span key={link} className="flex items-center gap-3">
                {i > 0 && <span className="text-kb-border">|</span>}
                <Link href="#" className="text-sm text-kb-text hover:underline">{link}</Link>
              </span>
            ))}
          </div>
          <p className="text-sm text-kb-text mt-3 mb-0">
            사업자 등록번호 : 000-00-00000 &nbsp;|&nbsp; 서울특별시 중구 태평로1길 1(AXful동) &nbsp;|&nbsp; 대표 : 홍대표
          </p>
        </div>
        <div className="bg-kb-beige-light">
          <div className="max-w-kb-container mx-auto px-6 pt-4 pb-5">
            <div className="flex items-center gap-3">
              {/* 전화는 걸면 되고, 상담은 모달이 이미 있다. 나머지 둘은 들어갈
                  화면이 없어 ▾ 를 떼고 표시로 둔다. */}
              <a href="tel:15880000"
                className="flex items-center gap-1.5 border border-kb-border px-4 py-2
                           text-sm text-kb-text-body bg-white hover:bg-kb-beige transition-colors">
                대표전화 1588-0000
              </a>
              <FooterConsultButton />
              {FOOTER_INFO.map((label) => (
                <span key={label}
                  className="flex items-center gap-1.5 border border-kb-border px-4 py-2
                             text-sm text-kb-text-muted bg-white">
                  {label}
                </span>
              ))}
            </div>
          </div>
          <div className="max-w-kb-container mx-auto px-6 pb-10">
            <p className="text-sm text-kb-text-muted">Copyright AXful Bank. All Rights Reserved.</p>
          </div>
        </div>
      </footer>
    </div>
  )
}
