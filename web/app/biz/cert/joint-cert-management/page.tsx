'use client'

import Link from 'next/link'


const MANAGEMENT_CARDS = [
  {
    title: '인증서 가져오기',
    desc: '암호화된 인증서 파일을 새로운 PC에서 복원하여 이용하실 수 있습니다.',
    actionLabel: '인증서 가져오기',
    icon: (
      <svg viewBox="0 0 80 40" fill="none" className="w-20 h-10">
        <rect x="2" y="4" width="28" height="32" rx="2" fill="#f5f5f5" stroke="#ccc" strokeWidth="1.5" />
        <rect x="8" y="10" width="16" height="2" rx="1" fill="#ccc" />
        <rect x="8" y="15" width="16" height="2" rx="1" fill="#ccc" />
        <path d="M38 20h4" stroke="#FFCC00" strokeWidth="2" strokeLinecap="round" />
        <path d="M36 18l4 2-4 2" fill="#FFCC00" />
        <circle cx="66" cy="20" r="12" fill="#FFCC00" opacity="0.2" stroke="#FFCC00" strokeWidth="1.5" />
        <path d="M62 20l4 4 6-8" stroke="#544C40" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" />
      </svg>
    ),
  },
  {
    title: '인증서 내보내기',
    desc: '인증서를 표준암호화 형태의 파일로 저장하여 다른 저장 장치로 옮길 수 있습니다.',
    actionLabel: '인증서 내보내기',
    icon: (
      <svg viewBox="0 0 80 40" fill="none" className="w-20 h-10">
        <circle cx="14" cy="20" r="12" fill="#FFCC00" opacity="0.2" stroke="#FFCC00" strokeWidth="1.5" />
        <path d="M10 20l4 4 6-8" stroke="#544C40" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" />
        <path d="M38 20h4" stroke="#FFCC00" strokeWidth="2" strokeLinecap="round" />
        <path d="M44 18l4 2-4 2" fill="#FFCC00" />
        <rect x="50" y="4" width="28" height="32" rx="2" fill="#f5f5f5" stroke="#ccc" strokeWidth="1.5" />
        <rect x="56" y="10" width="16" height="2" rx="1" fill="#ccc" />
        <rect x="56" y="15" width="16" height="2" rx="1" fill="#ccc" />
      </svg>
    ),
  },
  {
    title: '인증서 복사',
    desc: '인증서를 PC 또는 이동식 저장 장치에 복사하여 여러 장소에서 동시에 이용할 수 있습니다.',
    actionLabel: '인증서 복사',
    icon: (
      <svg viewBox="0 0 80 40" fill="none" className="w-20 h-10">
        <circle cx="14" cy="20" r="12" fill="#FFCC00" opacity="0.2" stroke="#FFCC00" strokeWidth="1.5" />
        <path d="M10 20l4 4 6-8" stroke="#544C40" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" />
        <path d="M34 20h12" stroke="#FFCC00" strokeWidth="2" strokeLinecap="round" />
        <path d="M40 17l6 3-6 3" fill="#FFCC00" />
        <rect x="54" y="8" width="12" height="22" rx="3" fill="#f5f5f5" stroke="#ccc" strokeWidth="1.5" />
        <rect x="57" y="12" width="6" height="2" rx="1" fill="#FFCC00" />
        <rect x="57" y="16" width="6" height="2" rx="1" fill="#ccc" />
        <rect x="58" y="28" width="4" height="4" rx="1" fill="#ccc" />
      </svg>
    ),
  },
  {
    title: '인증서 삭제',
    desc: '사용하지 않거나 폐기된 인증서를 저장소에서 삭제할 수 있습니다.',
    actionLabel: '인증서 삭제',
    icon: (
      <svg viewBox="0 0 80 40" fill="none" className="w-20 h-10">
        <circle cx="40" cy="20" r="16" fill="#FFCC00" opacity="0.15" stroke="#FFCC00" strokeWidth="1.5" />
        <path d="M34 14l12 12M46 14L34 26" stroke="#c0392b" strokeWidth="2" strokeLinecap="round" />
        <circle cx="40" cy="20" r="16" stroke="#FFCC00" strokeWidth="1.5" fill="none" />
      </svg>
    ),
  },
  {
    title: '인증서 보기/검증',
    desc: '인증서의 유효기간 등 상세 정보를 확인하실 수 있습니다.',
    actionLabel: '인증서 보기/검증안내',
    icon: (
      <svg viewBox="0 0 80 40" fill="none" className="w-20 h-10">
        <circle cx="32" cy="20" r="14" fill="#FFCC00" opacity="0.15" stroke="#FFCC00" strokeWidth="1.5" />
        <path d="M28 20l4 4 6-8" stroke="#544C40" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" />
        <circle cx="57" cy="28" r="8" fill="#f5f5f5" stroke="#ccc" strokeWidth="1.5" />
        <path d="M53 28l3 3 5-5" stroke="#FFCC00" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round" />
        <path d="M62 33l5 5" stroke="#ccc" strokeWidth="2" strokeLinecap="round" />
      </svg>
    ),
  },
  {
    title: '인증서 암호변경',
    desc: '더욱 안전한 인증서 사용을 위해 암호를 새롭게 변경할 수 있습니다.',
    actionLabel: '인증서 암호변경',
    icon: (
      <svg viewBox="0 0 80 40" fill="none" className="w-20 h-10">
        <circle cx="40" cy="20" r="14" fill="#FFCC00" opacity="0.15" stroke="#FFCC00" strokeWidth="1.5" />
        <text x="40" y="25" textAnchor="middle" fontSize="14" fontWeight="bold" fill="#544C40">**</text>
        <path d="M54 14l6-6M56 8l4 4" stroke="#FFCC00" strokeWidth="2" strokeLinecap="round" />
      </svg>
    ),
  },
]

export default function BizJointCertManagementPage() {
  return (
    <div className="space-y-8">
      {/* 브레드크럼 */}
      <div className="flex items-center gap-2 text-caption text-kb-text-muted">
        <span>인증센터(기업)</span>
        <span>&gt;</span>
        <span>공동인증서</span>
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
          · 인증서 관리 메뉴에서 인증서 보기, 검증, 저장, 암호변경, 내보내기, 가져오기 등을 할 수 있습니다.
        </p>
        <p className="text-caption text-kb-text-body leading-relaxed">
          · 공동인증서는 하드 디스크보다 이동식 저장 장치에 저장하여 사용하는 것이 더 안전하고, 어느 PC에서도 편리하게 이용할 수 있습니다.
        </p>
      </div>

      {/* 카드 그리드 2×3 */}
      <div className="grid grid-cols-2 gap-4">
        {MANAGEMENT_CARDS.map((card) => (
          <div key={card.title} className="border border-kb-border p-6 space-y-3">
            <div className="flex justify-center py-2">
              {card.icon}
            </div>
            <h3 className="text-body font-bold text-kb-text">{card.title}</h3>
            <p className="text-caption text-kb-text-muted leading-relaxed">{card.desc}</p>
            <div className="flex gap-2 pt-1">
              <Link href="/banking/first-visit"
                className="flex-1 py-2 border border-kb-border text-caption text-kb-text hover:bg-kb-beige-light transition-colors text-center">
                이용 안내
              </Link>
              {/* 가져오기·내보내기는 인증서 관리 화면에서 한다. 여기서는 안내만 하고
                  실제 조작은 한 곳에 모아 둔다 — 두 곳에 두면 한쪽만 고치게 된다. */}
              <Link href="/cert/cert-management"
                className="flex-1 py-2 bg-kb-yellow text-caption font-bold text-kb-text hover:brightness-95 transition-all text-center">
                {card.actionLabel}
              </Link>
            </div>
          </div>
        ))}
      </div>
    </div>
  )
}
