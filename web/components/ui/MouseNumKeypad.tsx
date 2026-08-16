'use client'
import { KB_BORDER, KB_PRIMARY } from '@/lib/theme'

import { useState } from 'react'

function shufflePad(): string[] {
  const digits = ['0', '1', '2', '3', '4', '5', '6', '7', '8', '9']
  for (let i = digits.length - 1; i > 0; i--) {
    const j = Math.floor(Math.random() * (i + 1))
    ;[digits[i], digits[j]] = [digits[j], digits[i]]
  }
  return digits
}

interface MouseNumKeypadProps {
  value: string
  onChange: (v: string) => void
  maxLength?: number
  dotCount?: number   // 표시할 dot 수 (maxLength와 같으면 고정, 없으면 가변)
}

export default function MouseNumKeypad({ value, onChange, maxLength = 7, dotCount }: MouseNumKeypadProps) {
  const [pad, setPad] = useState<string[]>(shufflePad)

  function handleDigit(d: string) {
    if (value.length >= maxLength) return
    onChange(value + d)
  }

  function handleBackspace() {
    onChange(value.slice(0, -1))
  }

  function handleShuffle() {
    setPad(shufflePad())
  }

  const displayCount = dotCount ?? maxLength

  return (
    <div className="inline-block border border-kb-border bg-white rounded-lg p-3 shadow-sm select-none">
      {/* 입력 표시 */}
      <div className="flex gap-1.5 justify-center mb-3">
        {Array.from({ length: displayCount }).map((_, i) => (
          <div
            key={i}
            className="w-7 h-7 rounded border-2 flex items-center justify-center"
            style={
              i < value.length
                ? { backgroundColor: KB_PRIMARY, borderColor: KB_PRIMARY }
                : { borderColor: KB_BORDER, backgroundColor: 'white' }
            }
          >
            {i < value.length && <span className="text-white text-[10px] font-bold">●</span>}
          </div>
        ))}
      </div>

      {/* 숫자 그리드 */}
      <div className="grid grid-cols-3 gap-0.5">
        {pad.slice(0, 9).map((d) => (
          <button
            key={d}
            type="button"
            onClick={() => handleDigit(d)}
            className="h-10 w-10 text-[16px] font-medium text-kb-text hover:bg-gray-100 rounded transition-colors"
            tabIndex={-1}
          >
            {d}
          </button>
        ))}
        <button
          type="button"
          onClick={handleShuffle}
          className="h-10 w-10 flex items-center justify-center hover:bg-gray-100 rounded transition-colors text-gray-500 text-lg"
          tabIndex={-1}
          title="재배열"
        >↻</button>
        <button
          type="button"
          onClick={() => handleDigit(pad[9])}
          className="h-10 w-10 text-[16px] font-medium text-kb-text hover:bg-gray-100 rounded transition-colors"
          tabIndex={-1}
        >
          {pad[9]}
        </button>
        <button
          type="button"
          onClick={handleBackspace}
          className="h-10 w-10 flex items-center justify-center hover:bg-gray-100 rounded transition-colors"
          tabIndex={-1}
        >
          <span className="bg-gray-500 text-white text-[11px] rounded px-1.5 py-0.5">✕</span>
        </button>
      </div>
    </div>
  )
}
