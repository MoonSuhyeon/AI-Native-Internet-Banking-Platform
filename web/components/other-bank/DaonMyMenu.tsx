'use client'

import { useState } from 'react'
import Link from 'next/link'
import { usePathname } from 'next/navigation'

const ZOOM_LEVELS = [0.8, 0.9, 1.0, 1.1, 1.2]
const DEFAULT_INDEX = 2

const MY_MENU = [
  { label: '계좌조회', href: '/other-bank/accounts' },
  { label: '계좌이체', href: '#' },
  { label: '외환HOME', href: '#' },
  { label: '증명서/확인증 발급', href: '#' },
]

export default function DaonMyMenu() {
  const pathname = usePathname()
  const [open, setOpen] = useState(true)
  const [zoomIndex, setZoomIndex] = useState(DEFAULT_INDEX)

  if (pathname === '/other-bank/login') return null

  const decrease = () => {
    const next = Math.max(0, zoomIndex - 1)
    setZoomIndex(next)
    document.documentElement.style.zoom = String(ZOOM_LEVELS[next])
  }

  const increase = () => {
    const next = Math.min(ZOOM_LEVELS.length - 1, zoomIndex + 1)
    setZoomIndex(next)
    document.documentElement.style.zoom = String(ZOOM_LEVELS[next])
  }

  return (
    <div className="fixed right-0 top-1/3 z-40 flex items-start">
      {/* 토글 탭 */}
      <button
        onClick={() => setOpen(!open)}
        className="mt-14 w-6 h-16 bg-daon-navy flex items-center justify-center
                   border border-kb-border border-r-0 rounded-l-lg shadow-sm hover:bg-[#14305a] transition-colors"
        style={{ writingMode: 'vertical-rl' }}
      >
        <span className="text-[10px] font-bold text-white rotate-180">
          {open ? '◀' : '▶'}
        </span>
      </button>

      {/* 패널 */}
      {open && (
        <div className="bg-white border border-kb-border shadow-xl overflow-hidden w-[320px] text-sm rounded-l-2xl">
          {/* MY MENU */}
          <div className="px-5 pt-6 pb-4">
            <div className="flex items-center justify-between mb-2">
              <span className="text-base font-bold text-kb-text">MY MENU</span>
              <Link href="#" className="text-sm text-kb-text-muted hover:underline flex items-center gap-0.5">
                더보기 <span className="text-[10px]">›</span>
              </Link>
            </div>
            <div className="grid grid-cols-2 gap-x-2 gap-y-1">
              {MY_MENU.map((item) => (
                <Link key={item.label} href={item.href}
                  className="text-sm text-kb-text-body hover:text-kb-text hover:underline py-0.5">
                  · {item.label}
                </Link>
              ))}
            </div>
          </div>

          <div className="border-t border-kb-border" />

          {/* 빠른 서비스 */}
          <div className="px-5 py-4">
            <p className="text-base font-bold text-kb-text mb-2">빠른 서비스</p>
            <div className="grid grid-cols-2 gap-x-2 gap-y-1">
              {['환율정보', '환율계산기', '지점알림', '지점방문예약'].map((item) => (
                <Link key={item} href="#"
                  className="text-sm text-kb-text-body hover:text-kb-text hover:underline py-0.5">
                  · {item}
                </Link>
              ))}
            </div>
          </div>

          {/* 글자크기 */}
          <div className="px-5 pb-5 flex justify-center">
            <div className="flex items-center border border-kb-border rounded-full overflow-hidden text-sm">
              <button onClick={decrease} className="px-3 py-1.5 text-kb-text-muted hover:bg-daon-surface transition-colors">−</button>
              <span className="px-4 py-1.5 border-x border-kb-border text-kb-text-body">글자크기</span>
              <button onClick={increase} className="px-3 py-1.5 text-kb-text-muted hover:bg-daon-surface transition-colors">+</button>
            </div>
          </div>
        </div>
      )}
    </div>
  )
}
