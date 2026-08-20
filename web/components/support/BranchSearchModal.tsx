'use client'

import { useEffect, useState } from 'react'

import { searchBranches, type Branch } from '@/lib/banking-api'
import { KB_BORDER, KB_PRIMARY, KB_TEXT_MUTED } from '@/lib/theme'

const TYPE_LABEL: Record<Branch['branchType'], string> = {
  BRANCH: '영업점',
  CORPORATE: '기업금융센터',
  PB: 'PB센터',
}

/**
 * 지점검색.
 *
 * <p>예약 화면의 "지점검색" 버튼에 핸들러가 없었다. 지점명을 손으로 쳐 넣게 돼
 * 있었는데, 존재하지 않는 지점을 적어도 예약은 되던 상태였다.
 *
 * <p>지점명과 지역을 한 칸으로 찾는다 — 고객은 "강남" 이 지점명인지 지역인지
 * 구분해 입력하지 않는다.
 */
export default function BranchSearchModal({
  onSelect,
  onClose,
}: {
  onSelect: (branch: Branch) => void
  onClose: () => void
}) {
  const [keyword, setKeyword] = useState('')
  const [items, setItems] = useState<Branch[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')

  async function run(q: string) {
    setLoading(true)
    try {
      setItems(await searchBranches(q))
      setError('')
    } catch {
      setError('영업점을 불러오지 못했습니다.')
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => {
    run('')
  }, [])

  return (
    <div className="fixed inset-0 z-[200] flex items-center justify-center bg-black/40 p-4" onClick={onClose}>
      <div className="bg-white rounded-xl shadow-2xl w-full max-w-[520px] max-h-[86vh] flex flex-col"
        onClick={e => e.stopPropagation()}>

        <div className="flex items-center justify-between px-5 py-3 rounded-t-xl"
          style={{ backgroundColor: KB_PRIMARY }}>
          <span className="text-[15px] font-bold text-white">지점검색</span>
          <button onClick={onClose} aria-label="닫기" className="text-white text-[18px] leading-none">×</button>
        </div>

        <div className="px-5 py-4 flex gap-2">
          <input value={keyword} onChange={e => setKeyword(e.target.value)}
            onKeyDown={e => e.key === 'Enter' && run(keyword)}
            placeholder="지점명 또는 지역 (예: 강남)"
            className="flex-1 border rounded-lg px-3 py-1.5 text-[13px] outline-none"
            style={{ borderColor: KB_BORDER }} />
          <button onClick={() => run(keyword)}
            className="rounded-lg px-4 py-1.5 text-[13px] font-bold text-white"
            style={{ backgroundColor: KB_PRIMARY }}>
            검색
          </button>
        </div>

        <div className="overflow-y-auto px-5 pb-4">
          {error && <p className="text-[13px] text-kb-red">{error}</p>}
          {loading ? (
            <p className="text-[13px] py-3" style={{ color: KB_TEXT_MUTED }}>불러오는 중…</p>
          ) : items.length === 0 ? (
            <p className="text-[13px] py-3" style={{ color: KB_TEXT_MUTED }}>
              찾는 영업점이 없습니다. 지역명으로도 찾아보세요.
            </p>
          ) : (
            <ul className="space-y-2">
              {items.map(b => (
                <li key={b.branchId}>
                  <button onClick={() => onSelect(b)}
                    className="w-full text-left border rounded-lg px-3 py-2.5 hover:bg-kb-primary-bg transition-colors"
                    style={{ borderColor: KB_BORDER }}>
                    <div className="flex items-baseline gap-2">
                      <span className="text-[13px] font-bold text-kb-text">{b.branchName}</span>
                      <span className="text-[11px]" style={{ color: KB_PRIMARY }}>{TYPE_LABEL[b.branchType]}</span>
                    </div>
                    <p className="text-[12px] mt-0.5" style={{ color: KB_TEXT_MUTED }}>{b.address}</p>
                    <p className="text-[12px]" style={{ color: KB_TEXT_MUTED }}>
                      {b.phone ?? '-'} · {b.openTime}~{b.closeTime}
                    </p>
                  </button>
                </li>
              ))}
            </ul>
          )}
        </div>
      </div>
    </div>
  )
}
