'use client'

import Link from 'next/link'
import { useState } from 'react'
import { usePathname } from 'next/navigation'
import { KB_PRIMARY } from '@/lib/theme'

type Child = { label: string; href: string }
type SidebarItem = { label: string; expandable?: boolean; href?: string; children?: Child[] }

/**
 * 상단 드롭다운(Header.GNB_MENUS 의 '금융상품')과 같은 갈래로 둔다.
 *
 * <p>예전에는 사이드바가 '예금 상품/가입' + '예금 조회/해지' 로 나뉘어, 드롭다운의
 * '예금상품' · '결과/내역 조회' 와 항목 구성이 어긋났다. 같은 화면을 두 경로로
 * 들어오는데 분류가 다르면, 지금 어디에 있는지가 사이드바에서 읽히지 않는다.
 *
 * <p>둘은 손으로 관리되는 별개의 배열이라 한쪽만 고치기 쉽다. 드롭다운을 바꿀 때
 * 여기도 함께 본다.
 */
const NAV: SidebarItem[] = [
  {
    label: '예금상품',
    expandable: true,
    children: [
      { label: '예금가입', href: '/products/deposit/list' },
      { label: '예금해지', href: '/products/deposit/inquiry/terminate' },
    ],
  },
  {
    label: '결과/내역 조회',
    expandable: true,
    children: [
      { label: '신규결과/내역 조회', href: '/products/deposit/inquiry/new' },
      { label: '해지결과/내역 조회', href: '/products/deposit/inquiry/terminate-result' },
    ],
  },
]

export default function DepositSidebar() {
  const pathname = usePathname()

  const defaultOpen = new Set(
    NAV.filter(item => item.children?.some(c => c.href !== '#' && pathname.startsWith(c.href)))
       .map(item => item.label)
  )

  const [openSections, setOpenSections] = useState<Set<string>>(defaultOpen)

  function toggle(label: string) {
    setOpenSections(prev => {
      const next = new Set(prev)
      if (next.has(label)) { next.delete(label) } else { next.add(label) }
      return next
    })
  }

  return (
    <aside className="w-[200px] flex-shrink-0 border-r border-kb-primary-border min-h-[700px] pt-6 pr-2 bg-white">
      <h2 className="text-[13px] font-bold mb-4 px-2 pb-2 border-b border-kb-primary-border flex items-center gap-2" style={{ color: KB_PRIMARY }}>예금</h2>
      <nav>
        {NAV.map((item) => (
          <div key={item.label} className="border-b border-kb-primary-border last:border-b-0">
            {item.expandable ? (
              <>
                <button
                  onClick={() => toggle(item.label)}
                  className="w-full flex items-center justify-between px-2 py-2.5 text-[13px] font-medium text-kb-text-body hover:text-kb-text hover:bg-kb-primary-bg rounded-sm transition-colors duration-150"
                >
                  <span className="text-left leading-tight">{item.label}</span>
                  <span className="text-[10px] text-kb-text-muted ml-1 flex-shrink-0">
                    {openSections.has(item.label) ? '▴' : '▾'}
                  </span>
                </button>
                {openSections.has(item.label) && item.children && (
                  <ul className="mb-2 pl-1">
                    {item.children.map((child) => {
                      const active = child.href !== '#' && (pathname === child.href || pathname.startsWith(child.href + '/'))
                      return (
                        <li key={child.label}>
                          <Link
                            href={child.href}
                            className={`block py-1.5 text-[12px] leading-snug transition-colors duration-150 ${
                              active
                                ? 'pl-[9px] pr-3 border-l-[3px] border-kb-mint bg-kb-primary-bg font-semibold text-kb-primary'
                                : 'px-3 text-kb-text-muted hover:text-kb-text hover:bg-kb-primary-bg'
                            }`}
                          >
                            {child.label}
                          </Link>
                        </li>
                      )
                    })}
                  </ul>
                )}
              </>
            ) : (
              <Link
                href={item.href ?? '#'}
                className={`block py-2.5 text-[13px] transition-colors duration-150 ${
                  item.href && pathname.startsWith(item.href)
                    ? 'pl-[9px] pr-3 border-l-[3px] border-kb-mint bg-kb-primary-bg font-semibold text-kb-primary'
                    : 'px-2 text-kb-text-muted hover:text-kb-text hover:bg-kb-primary-bg'
                }`}
              >
                {item.label}
              </Link>
            )}
          </div>
        ))}
      </nav>
    </aside>
  )
}
