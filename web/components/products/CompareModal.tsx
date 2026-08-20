'use client'

import Link from 'next/link'

import { KB_BORDER, KB_PRIMARY, KB_PRIMARY_SURFACE, KB_TEXT_MUTED } from '@/lib/theme'

export type CompareProduct = {
  id: string
  name: string
  channel: string
  desc?: string
  period?: string
  rate?: string
  canApply?: boolean
}

const ROWS: { label: string; get: (p: CompareProduct) => string }[] = [
  { label: '가입채널', get: p => p.channel },
  { label: '가입기간', get: p => p.period ?? '-' },
  { label: '금리', get: p => p.rate ?? '-' },
  { label: '상품설명', get: p => p.desc ?? '-' },
]

/**
 * 상품 비교.
 *
 * <p>목록의 "비교하기" 버튼에 핸들러가 없었다. 챗봇에는 비교 기능이 있었지만,
 * 상품을 눈앞에 두고 고르는 화면에서는 쓸 수 없었다.
 *
 * <p>금리 행을 굵게 둔다 — 상품을 비교하는 대부분의 이유가 그것이라, 다른 행과 같은
 * 무게로 늘어놓으면 표를 훑어 찾아야 한다.
 *
 * <p>여기 보이는 값은 목록에 표시된 것과 같은 값이다. 비교 화면이 따로 계산하면
 * 목록과 다른 숫자가 나올 수 있고, 그러면 어느 쪽이 맞는지 고객이 알 방법이 없다.
 */
export default function CompareModal({
  products,
  onRemove,
  onClose,
}: {
  products: CompareProduct[]
  onRemove: (id: string) => void
  onClose: () => void
}) {
  return (
    <div className="fixed inset-0 z-[200] flex items-center justify-center bg-black/40 p-4" onClick={onClose}>
      <div className="bg-white rounded-xl shadow-2xl w-full max-w-[720px] max-h-[86vh] flex flex-col"
        onClick={e => e.stopPropagation()}>

        <div className="flex items-center justify-between px-5 py-3 rounded-t-xl"
          style={{ backgroundColor: KB_PRIMARY }}>
          <span className="text-[15px] font-bold text-white">상품 비교 {products.length}건</span>
          <button onClick={onClose} aria-label="닫기" className="text-white text-[18px] leading-none">×</button>
        </div>

        <div className="overflow-auto px-5 py-4">
          <table className="w-full text-[13px] border-collapse">
            <thead>
              <tr>
                <th className="w-[100px]" />
                {products.map(p => (
                  <th key={p.id} className="border-b px-3 py-2 text-left align-top"
                    style={{ borderColor: KB_BORDER }}>
                    <div className="flex items-start justify-between gap-2">
                      <span className="font-bold text-kb-text">{p.name}</span>
                      <button onClick={() => onRemove(p.id)} aria-label={`${p.name} 비교에서 빼기`}
                        className="text-[14px] leading-none" style={{ color: KB_TEXT_MUTED }}>×</button>
                    </div>
                  </th>
                ))}
              </tr>
            </thead>
            <tbody>
              {ROWS.map(row => (
                <tr key={row.label}>
                  <th className="border-b px-3 py-2.5 text-left font-semibold whitespace-nowrap"
                    style={{ borderColor: KB_BORDER, backgroundColor: KB_PRIMARY_SURFACE }}>
                    {row.label}
                  </th>
                  {products.map(p => (
                    <td key={p.id} className="border-b px-3 py-2.5 align-top"
                      style={{
                        borderColor: KB_BORDER,
                        color: row.label === '금리' ? KB_PRIMARY : undefined,
                        fontWeight: row.label === '금리' ? 700 : undefined,
                      }}>
                      {row.get(p)}
                    </td>
                  ))}
                </tr>
              ))}
              <tr>
                <th className="px-3 py-3" />
                {products.map(p => (
                  <td key={p.id} className="px-3 py-3 align-top">
                    {p.canApply ? (
                      <Link href={`/products/deposit/join/${p.id}`}
                        className="inline-block rounded-xl px-4 py-1.5 text-[13px] font-bold text-white hover:opacity-85 transition-opacity"
                        style={{ backgroundColor: KB_PRIMARY }}>
                        가입하기
                      </Link>
                    ) : (
                      <span className="text-[12px]" style={{ color: KB_TEXT_MUTED }}>
                        영업점에서 가입
                      </span>
                    )}
                  </td>
                ))}
              </tr>
            </tbody>
          </table>

          <p className="text-[11px] mt-3" style={{ color: KB_TEXT_MUTED }}>
            표시된 금리는 상품별 최저~최고 구간이며, 실제 적용금리는 가입기간·우대조건에 따라 달라집니다.
          </p>
        </div>
      </div>
    </div>
  )
}
