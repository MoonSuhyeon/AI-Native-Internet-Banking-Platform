'use client'

import { useState } from 'react'

type RateRow = {
  period: string
  base: string
  customer: string
}

type Props = {
  productName: string
  rates: RateRow[]
  rateDate: string
  onClose: () => void
}

const DEPOSIT_SUB_TABS = [
  '전체상품', '목돈모으기상품', '목돈굴리기상품', '주택청약상품',
  '입출금이자유로운예금', '시장성예금', '판매중지상품',
]

const RATE_TYPES = ['기본이율', '우대이율', '중도해지이율', '만기후이율']

const YEARS = ['2026', '2025', '2024']
const MONTHS = ['01','02','03','04','05','06','07','08','09','10','11','12']
const DAYS = Array.from({ length: 31 }, (_, i) => String(i + 1).padStart(2, '0'))

export default function RateModal({ productName, rates, onClose }: Props) {
  const [subTab, setSubTab] = useState('전체상품')
  const [rateType, setRateType] = useState('기본이율')
  const [year, setYear] = useState('2026')
  const [month, setMonth] = useState('05')
  const [day, setDay] = useState('25')

  return (
    <div className="fixed inset-0 z-[400] flex items-center justify-center bg-black/50" onClick={onClose}>
      <div
        className="bg-white shadow-2xl flex flex-col"
        style={{ width: 740, maxWidth: '96vw', maxHeight: '90vh' }}
        onClick={e => e.stopPropagation()}
      >
        {/* 헤더 */}
        <div className="flex items-center justify-between px-5 py-3 bg-kb-mint">
          <span className="text-[16px] font-bold text-white">금리안내</span>
          <span className="text-white">
            <svg viewBox="0 0 24 24" fill="none" className="w-5 h-5" stroke="white" strokeWidth="2">
              <path d="M12 3L2 9v12h7v-7h6v7h7V9L12 3z" fill="white" stroke="none" />
              <circle cx="12" cy="3" r="1.5" fill="white" />
            </svg>
          </span>
        </div>

        <div className="overflow-y-auto flex-1">
          {/* 무엇의 금리인지 표시.
              예전에는 예금·펀드·대출·신탁·외환 다섯 탭이었는데, 이 모달은 예금 상품
              상세에서만 열리고 rates 도 그 상품 것 하나뿐이라 나머지 넷은 눌러도
              보여줄 데이터가 없었다. active: true 가 예금에 박혀 있던 것은 그 증상이다. */}
          <div className="flex items-center justify-center py-4 border-b border-kb-border">
            <span className="flex flex-col items-center gap-1 text-[13px] pb-1 text-kb-primary border-b-2 border-kb-primary font-bold">
              <span className="text-lg">🐷</span>
              예금
            </span>
          </div>

          {/* 서브 탭 */}
          <div className="flex border-b border-kb-border overflow-x-auto">
            {DEPOSIT_SUB_TABS.map(tab => (
              <button key={tab}
                onClick={() => setSubTab(tab)}
                className={`flex-shrink-0 px-4 py-2.5 text-[12px] border-r border-kb-border transition-colors ${
                  subTab === tab
                    ? 'border-b-2 border-kb-primary font-bold text-kb-text bg-white'
                    : 'text-kb-text-muted hover:text-kb-text bg-[#fafafa]'
                }`}>
                {tab}
              </button>
            ))}
          </div>

          {/* 필터 행 */}
          <div className="flex items-center gap-2 px-5 py-3 bg-[#fafafa] border-b border-kb-border flex-wrap">
            <select value={year} onChange={e => setYear(e.target.value)}
              className="border border-kb-border px-2 py-1 text-[13px] outline-none bg-white">
              {YEARS.map(y => <option key={y}>{y}</option>)}
            </select>
            <span className="text-[13px]">년</span>
            <select value={month} onChange={e => setMonth(e.target.value)}
              className="border border-kb-border px-2 py-1 text-[13px] outline-none bg-white w-16">
              {MONTHS.map(m => <option key={m}>{m}</option>)}
            </select>
            <span className="text-[13px]">월</span>
            <select value={day} onChange={e => setDay(e.target.value)}
              className="border border-kb-border px-2 py-1 text-[13px] outline-none bg-white w-16">
              {DAYS.map(d => <option key={d}>{d}</option>)}
            </select>
            <span className="text-[13px]">일</span>
            <span className="text-[13px] ml-2">상품명</span>
            <select className="border border-kb-border px-3 py-1 text-[13px] outline-none bg-white flex-1 min-w-[180px]"
              defaultValue={productName}>
              <option>{productName}</option>
            </select>
            <button className="border border-kb-border bg-white px-4 py-1 text-[13px] hover:bg-kb-beige-light">
              조회
            </button>
          </div>

          {/* 이율 유형 링크 */}
          <div className="flex gap-4 px-5 py-3 border-b border-kb-border">
            {RATE_TYPES.map(rt => (
              <button key={rt}
                onClick={() => setRateType(rt)}
                className={`text-[13px] flex items-center gap-0.5 ${
                  rateType === rt ? 'text-kb-primary font-bold' : 'text-kb-text-muted hover:text-kb-text'
                }`}>
                <span className="text-[10px]">▶</span>{rt}
              </button>
            ))}
          </div>

          {/* 테이블 */}
          <div className="px-5 pt-4 pb-2">
            <p className="text-[13px] font-bold text-kb-text mb-2">{productName}</p>
            <p className="text-[11px] text-kb-text-muted text-right mb-1">(세금공제전, 단위:연%)</p>
            <table className="w-full border-collapse text-[13px]">
              <thead>
                <tr className="bg-[#F0F8F5]">
                  <th className="border border-kb-border px-4 py-2.5 text-center font-semibold text-kb-text">기간</th>
                  <th className="border border-kb-border px-4 py-2.5 text-center font-semibold text-kb-text">기본이율</th>
                  <th className="border border-kb-border px-4 py-2.5 text-center font-semibold text-kb-text">고객적용이율</th>
                </tr>
              </thead>
              <tbody>
                {rates.map((row, i) => (
                  <tr key={i} className="hover:bg-[#f9f9f9]">
                    <td className={`border border-kb-border px-4 py-2.5 text-center ${
                      parseFloat(row.customer) >= 2.9 ? 'text-[#E07020] font-medium' : 'text-kb-text-body'
                    }`}>
                      {row.period}
                    </td>
                    <td className="border border-kb-border px-4 py-2.5 text-center text-kb-text-body">{row.base}</td>
                    <td className="border border-kb-border px-4 py-2.5 text-center text-kb-text-body">{row.customer}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>

          {/* 금리우대쿠폰 주석 */}
          <div className="px-5 py-3 text-[11px] text-kb-text-muted space-y-1">
            <p>- 금리우대쿠폰 : 이 예금의 신규 시 금리우대쿠폰을 적용한 경우 쿠폰 우대금리를 기본이율에 가산</p>
            <p>※ 금리우대쿠폰 우대금리는 신규 당시 적용한 쿠폰의 우대금리를 따르며, 세부 사항(적용방법, 유의사항 등)은 금리우대쿠폰에서 확인 가능</p>
            <p>※ 금리우대쿠폰은 신규 시에만 사용 가능하며 재예치 및 중도해지 시 미적용</p>
          </div>

          {/* 인쇄 버튼 */}
          <div className="flex justify-center py-3 border-t border-kb-border">
            <button onClick={() => window.print()}
              className="border border-kb-border px-10 py-2 text-[13px] text-kb-text-body hover:bg-kb-beige-light">
              인쇄
            </button>
          </div>
        </div>

        {/* 닫기 */}
        <div className="flex items-center justify-between px-5 py-2 border-t border-kb-border bg-[#fafafa]">
          <span className="text-[12px] text-kb-text-muted flex items-center gap-1">
            <svg viewBox="0 0 20 20" fill="none" className="w-4 h-4"><path d="M10 2L3 7v6c0 4 2.5 7 7 8 4.5-1 7-4 7-8V7L10 2z" fill="#5BC9A8"/></svg>
            AXful Bank
          </span>
          <button onClick={onClose}
            className="border border-kb-border px-6 py-1.5 text-[13px] text-kb-text-body hover:bg-kb-beige-light">
            닫기
          </button>
        </div>
      </div>
    </div>
  )
}
