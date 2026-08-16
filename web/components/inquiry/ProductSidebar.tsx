'use client'

import Link from 'next/link'
import { useState } from 'react'
import { usePathname } from 'next/navigation'
import { KB_PRIMARY } from '@/lib/theme'

type Child = { label: string; href: string }
type SidebarItem = { label: string; expandable?: boolean; href?: string; children?: Child[] }

const NAV: SidebarItem[] = [
  {
    label: '예금',
    expandable: true,
    children: [
      { label: '예금 상품/가입', href: '/products/deposit/list' },
      { label: '예금 리스트',   href: '/products/deposit/list' },
    ],
  },
  {
    label: '대출',
    expandable: true,
    children: [
      { label: '대출 상품',  href: '/products/loan' },
      { label: '대출 신청',  href: '/loans/apply' },
      { label: '대출현황',   href: '/products/loan/status' },
    ],
  },
]

export default function ProductSidebar() {
  const pathname = usePathname()

  const defaultOpen = new Set(
    NAV.filter(item => item.children?.some(c => c.href !== '#' && pathname.startsWith(c.href)))
       .map(item => item.label)
  )
  if (defaultOpen.size === 0) defaultOpen.add('예금')

  const [openSections, setOpenSections] = useState<Set<string>>(defaultOpen)

  function toggle(label: string) {
    setOpenSections(prev => {
      const next = new Set(prev)
      if (next.has(label)) { next.delete(label) } else { next.add(label) }
      return next
    })
  }

  return (
    <aside className="w-[200px] flex-shrink-0 border-r border-kb-border min-h-[700px] pt-6 pr-2 bg-white">
      <h2 className="text-[13px] font-bold mb-4 px-2 pb-2 border-b border-kb-border flex items-center gap-2" style={{ color: KB_PRIMARY }}>금융상품</h2>
      <nav>
        {NAV.map((item) => (
          <div key={item.label} className="border-b border-kb-border last:border-b-0">
            {item.expandable ? (
              <>
                <button
                  onClick={() => toggle(item.label)}
                  className="w-full flex items-center justify-between px-2 py-2.5 text-[13px] font-medium text-kb-text-body hover:text-kb-text hover:bg-kb-primary-bg rounded-sm transition-colors duration-150"
                >
                  <span>{item.label}</span>
                  <span className="text-[10px] text-kb-text-muted">
                    {openSections.has(item.label) ? '▴' : '▾'}
                  </span>
                </button>
                {openSections.has(item.label) && item.children && (
                  <ul className="mb-2 pl-1">
                    {item.children.map((child) => {
                      const active = child.href !== '#' && pathname === child.href
                      return (
                        <li key={child.label}>
                          <Link href={child.href}
                            className={`block py-1.5 text-[13px] transition-colors duration-150 ${
                              active
                                ? 'pl-[9px] pr-3 border-l-[3px] border-kb-mint bg-kb-primary-bg font-semibold text-kb-primary'
                                : 'px-3 text-kb-text-muted hover:text-kb-text hover:bg-kb-primary-bg'
                            }`}>
                            {child.label}
                          </Link>
                        </li>
                      )
                    })}
                  </ul>
                )}
              </>
            ) : (
              <Link href={item.href ?? '#'}
                className="block px-2 py-2.5 text-[13px] text-kb-text-muted hover:text-kb-text hover:bg-kb-primary-bg transition-colors duration-150">
                {item.label}
              </Link>
            )}
          </div>
        ))}
      </nav>
    </aside>
  )
}
