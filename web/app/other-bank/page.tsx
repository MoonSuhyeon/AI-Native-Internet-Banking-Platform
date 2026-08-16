import Link from 'next/link'
import DaonHeroCarousel from '@/components/other-bank/DaonHeroCarousel'
import DaonProductCarousel from '@/components/other-bank/DaonProductCarousel'

const QUICK_MENUS = [
  {
    label: '전체계좌조회', href: '/other-bank/accounts',
    svg: (
      <svg viewBox="0 0 56 56" fill="none" className="w-14 h-14" strokeLinecap="round" strokeLinejoin="round">
        <rect x="8" y="4" width="32" height="26" rx="2" fill="white" stroke="currentColor" strokeWidth="2.2"/>
        <rect x="12" y="8" width="24" height="18" rx="1" fill="white" stroke="currentColor" strokeWidth="1.3"/>
        <line x1="16" y1="13" x2="32" y2="13" stroke="currentColor" strokeWidth="1.5"/>
        <line x1="16" y1="18" x2="26" y2="18" stroke="currentColor" strokeWidth="1.5"/>
        <rect x="4" y="32" width="40" height="10" rx="1" fill="white" stroke="currentColor" strokeWidth="2"/>
        <rect x="16" y="35" width="16" height="4" rx="1" fill="none" stroke="currentColor" strokeWidth="1.3"/>
        <circle cx="37" cy="37" r="10" fill="#5a73a8" stroke="currentColor" strokeWidth="2.2"/>
        <line x1="44.1" y1="44.1" x2="51" y2="51" stroke="currentColor" strokeWidth="2.8" strokeLinecap="round"/>
      </svg>
    ),
  },
  {
    label: '거래내역조회', href: '/other-bank/accounts',
    svg: (
      <svg viewBox="0 0 56 56" fill="none" className="w-14 h-14" strokeLinecap="round" strokeLinejoin="round">
        <rect x="4" y="4" width="28" height="36" rx="2" fill="white" stroke="currentColor" strokeWidth="2.2"/>
        <line x1="10" y1="13" x2="26" y2="13" stroke="currentColor" strokeWidth="2"/>
        <line x1="10" y1="20" x2="26" y2="20" stroke="currentColor" strokeWidth="2"/>
        <line x1="10" y1="27" x2="20" y2="27" stroke="currentColor" strokeWidth="2"/>
        <circle cx="37" cy="37" r="10" fill="#5a73a8" stroke="currentColor" strokeWidth="2.2"/>
        <line x1="44.1" y1="44.1" x2="51" y2="51" stroke="currentColor" strokeWidth="2.8" strokeLinecap="round"/>
      </svg>
    ),
  },
  {
    label: '계좌이체', href: '#',
    svg: (
      <svg viewBox="0 0 56 56" fill="none" className="w-14 h-14" strokeLinecap="round" strokeLinejoin="round">
        <circle cx="22" cy="28" r="18" fill="white" stroke="currentColor" strokeWidth="2.2"/>
        <circle cx="22" cy="28" r="13" fill="#5a73a8" stroke="none"/>
        <text x="22" y="33" textAnchor="middle" fontSize="13" fontWeight="bold" stroke="none" fill="currentColor">W</text>
        <line x1="42" y1="28" x2="54" y2="28" stroke="currentColor" strokeWidth="2.2"/>
        <polyline points="50,23 54,28 50,33" stroke="currentColor" strokeWidth="2.2" fill="none"/>
      </svg>
    ),
  },
  {
    label: '결재내역', href: '#',
    svg: (
      <svg viewBox="0 0 56 56" fill="none" className="w-14 h-14" strokeLinecap="round" strokeLinejoin="round">
        <rect x="4" y="4" width="28" height="36" rx="2" fill="white" stroke="currentColor" strokeWidth="2.2"/>
        <line x1="10" y1="13" x2="26" y2="13" stroke="currentColor" strokeWidth="2"/>
        <line x1="10" y1="20" x2="26" y2="20" stroke="currentColor" strokeWidth="2"/>
        <line x1="10" y1="27" x2="20" y2="27" stroke="currentColor" strokeWidth="2"/>
        <path d="M27 30 L43 14 L49 20 L33 36 L25 38 Z" fill="#5a73a8" stroke="currentColor" strokeWidth="1.5"/>
      </svg>
    ),
  },
  {
    label: '증명서/확인증발급', href: '#',
    svg: (
      <svg viewBox="0 0 56 56" fill="none" className="w-14 h-14" strokeLinecap="round" strokeLinejoin="round">
        <rect x="4" y="4" width="28" height="36" rx="2" fill="white" stroke="currentColor" strokeWidth="2.2"/>
        <line x1="10" y1="13" x2="26" y2="13" stroke="currentColor" strokeWidth="2"/>
        <line x1="10" y1="20" x2="26" y2="20" stroke="currentColor" strokeWidth="2"/>
        <line x1="10" y1="27" x2="20" y2="27" stroke="currentColor" strokeWidth="2"/>
        <circle cx="37" cy="37" r="10" fill="#5a73a8" stroke="currentColor" strokeWidth="2.2"/>
        <polyline points="32,37 36,41 43,31" stroke="white" strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round"/>
      </svg>
    ),
  },
]

const NEWS_ITEMS = [
  { type: '새소식', text: '신규거래 고객 수수료 우대 혜택 안내' },
  { type: '새소식', text: '「다온인증서」 개인정보 이용·제공.' },
  { type: 'FAQ', text: '[확인증 출력] 이체확인증 출력시 한장에 여러.' },
  { type: 'FAQ', text: '[보안토큰] PC를 포맷하거나 교체한 후 HS.' },
]

const BANKING_GUIDE = [
  '개인뱅킹 이용가이드',
  '다온보안센터',
  '인증센터',
]

export default function DaonHomePage() {
  return (
    <main>
      <DaonHeroCarousel />

      {/* 퀵메뉴 */}
      <section className="py-2" style={{ backgroundColor: '#f0ece8' }}>
        <div className="max-w-kb-container mx-auto px-6">
          <div className="grid grid-cols-5 gap-5">
            {QUICK_MENUS.map((menu) => (
              <Link key={menu.label} href={menu.href}
                className="flex flex-col items-center gap-1 pt-4 pb-2 group">
                <div className="w-14 h-14 flex items-center justify-center transition-transform duration-200 group-hover:scale-125">
                  {menu.svg}
                </div>
                <div className="flex flex-col items-center gap-1">
                  <span className="text-lg text-kb-text-body group-hover:font-bold transition-all duration-150">
                    {menu.label}
                  </span>
                  <div className="h-1 w-0 group-hover:w-full bg-kb-admin transition-all duration-200" />
                </div>
              </Link>
            ))}
          </div>
        </div>
      </section>

      <DaonProductCarousel />

      {/* 새소식/FAQ + 개인뱅킹안내 + 고객센터 */}
      <section className="py-16 border-t border-kb-border" style={{ backgroundColor: '#f8f8f8' }}>
        <div className="max-w-kb-container mx-auto px-6 gap-16" style={{ display: 'grid', gridTemplateColumns: '1.1fr 0.85fr 0.85fr' }}>

          {/* 새소식/FAQ */}
          <div>
            <h2 className="text-2xl font-bold text-kb-text mb-6">새소식/FAQ</h2>
            <ul>
              {NEWS_ITEMS.map((item, i) => (
                <li key={i}>
                  <Link href="#"
                    className="flex items-center gap-2 py-3 hover:bg-kb-beige-light px-1 -mx-1 transition-colors">
                    <span className={`text-xs border px-1.5 py-0.5 rounded-md flex-shrink-0 font-medium
                      ${item.type === '새소식'
                        ? 'border-kb-red text-kb-red'
                        : 'border-kb-blue text-kb-blue'}`}>
                      {item.type}
                    </span>
                    <span className="text-base text-kb-text-body truncate">{item.text}</span>
                  </Link>
                </li>
              ))}
            </ul>
          </div>

          {/* 다온개인뱅킹안내 */}
          <div>
            <h2 className="text-2xl font-bold text-kb-text mb-6">다온개인뱅킹안내</h2>
            <ul className="space-y-3 pt-3">
              {BANKING_GUIDE.map((item) => (
                <li key={item}>
                  <Link href="#" className="text-base text-kb-text-body block py-1">· {item}</Link>
                </li>
              ))}
            </ul>
            <div className="mt-4 mx-2 p-4 flex items-center gap-3 hover:opacity-90 transition-opacity cursor-pointer" style={{ backgroundColor: '#d9e4fa' }}>
              <div>
                <p className="text-sm text-kb-text whitespace-nowrap">다온개인뱅킹 좀더 쉽게 이용하기</p>
                <p className="text-lg font-semibold whitespace-nowrap" style={{ color: '#1545c3' }}>개인뱅킹 체험관</p>
              </div>
            </div>
          </div>

          {/* 고객센터 */}
          <div>
            <h2 className="text-2xl font-bold text-kb-text mb-6">고객센터</h2>
            <div>
              <p className="text-xl font-bold" style={{ color: '#1B3A6B' }}>1599·0000</p>
              <p className="text-sm text-kb-text-body mt-1 mb-2">평일 08~22시 / 토·요일 09~14시</p>
              <p className="text-base font-bold text-kb-text">개인뱅킹서비스 1599-1111</p>
              <p className="text-sm text-kb-text-muted mt-1 mb-3">평일 09~18시</p>
              <p className="text-base font-bold text-kb-text">Daon CMS 1599-2222</p>
              <p className="text-sm text-kb-text-muted mt-1 mb-3">평일 09~18시</p>
              <p className="text-base font-bold text-kb-text">B2B전자결제 1599-3333</p>
              <p className="text-sm text-kb-text-muted mt-1">평일 09~18시</p>
            </div>
            <button className="mt-5 border border-kb-border px-3 py-1 text-xs text-kb-text-body bg-white hover:bg-kb-beige transition-colors flex items-center gap-1">
              원격상담 바로가기 <span className="text-[10px]">↗</span>
            </button>
          </div>
        </div>
      </section>
    </main>
  )
}
