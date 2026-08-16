'use client'

import { useRef } from 'react'

import { KB_BORDER } from '@/lib/theme'

/**
 * YYYYMMDD 문자열 입력칸 옆의 달력 버튼.
 *
 * <p>이 자리에 달력 아이콘 버튼이 있었지만 핸들러가 없어 눌러도 아무 일이 없었다.
 * 날짜를 바꾸려면 여덟 자리를 손으로 쳐야 했다.
 *
 * <p>달력 UI 를 새로 만들지 않고 브라우저 기본 선택기를 연다. 숨긴
 * `<input type="date">` 를 두고 `showPicker()` 로 띄운 뒤, 고른 값을 YYYYMMDD 로
 * 바꿔 돌려준다 — 화면의 값 형식은 그대로 유지된다.
 */
export default function DatePickerButton({
  value,
  onChange,
  label,
}: {
  /** YYYYMMDD */
  value: string
  /** YYYYMMDD 로 돌려준다 */
  onChange: (yyyymmdd: string) => void
  label: string
}) {
  const ref = useRef<HTMLInputElement>(null)

  // 20260519 → 2026-05-19. 여덟 자리가 아니면 빈 값으로 둔다(오늘로 열린다).
  const isoValue = /^\d{8}$/.test(value)
    ? `${value.slice(0, 4)}-${value.slice(4, 6)}-${value.slice(6, 8)}`
    : ''

  function open() {
    const el = ref.current
    if (!el) return
    // showPicker 가 없는 브라우저에서는 focus 만으로도 대부분 열린다.
    if (typeof el.showPicker === 'function') el.showPicker()
    else el.focus()
  }

  return (
    <span className="relative inline-flex">
      <button
        type="button"
        onClick={open}
        aria-label={label}
        className="border rounded-lg px-2 py-1.5 text-kb-text-muted hover:bg-kb-primary-bg transition-colors"
        style={{ borderColor: KB_BORDER }}
      >
        <svg viewBox="0 0 16 16" fill="none" className="w-4 h-4" stroke="currentColor" strokeWidth="1.5">
          <rect x="1" y="2" width="14" height="13" rx="1" />
          <line x1="5" y1="1" x2="5" y2="4" />
          <line x1="11" y1="1" x2="11" y2="4" />
          <line x1="1" y1="7" x2="15" y2="7" />
        </svg>
      </button>
      <input
        ref={ref}
        type="date"
        value={isoValue}
        onChange={e => onChange(e.target.value.replaceAll('-', ''))}
        aria-hidden="true"
        tabIndex={-1}
        className="absolute inset-0 w-0 h-0 opacity-0 pointer-events-none"
      />
    </span>
  )
}
