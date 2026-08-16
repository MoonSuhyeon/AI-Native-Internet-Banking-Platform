'use client'

import Link from 'next/link'
import { useState } from 'react'
import { KB_GOLD } from '@/lib/theme'

type Tab = '공동인증서' | '인터넷뱅킹' | '인증서로그인'

const FAQ_DATA: Record<Tab, { id: number; title: string; content: string }[]> = {
  '공동인증서': [
    { id: 26, title: '개인용 공동인증서는 어떻게 발급받나요?', content: '인증센터(개인) > 공동인증서 > 공동인증서 발급/재발급 메뉴에서 발급받으실 수 있습니다. 아이디, 주민등록번호, 로그인 비밀번호 입력 후 인증서 암호를 설정하시면 발급이 완료됩니다.' },
    { id: 25, title: '기업용 인증서 발급할 때 어떻게 되나요?', content: '기업 인터넷뱅킹 가입 후 인증센터(기업) 메뉴에서 발급 가능합니다. 사업자등록번호와 대표자 정보가 필요합니다.' },
    { id: 24, title: '인증서 관련 거래(발급/재발급/갱신/수수료 등) 페이지 오류 또는 스크립트 오류가 발생합니다.', content: '브라우저 캐시를 삭제하고 다시 시도해 주세요. 문제가 지속되면 고객센터로 문의해 주시기 바랍니다.' },
    { id: 23, title: '인터넷뱅킹 고객 중 개인사업자/임의단체는 타행/타기관으로 대상이 아닙니다.라고 나오면?', content: '개인사업자의 경우 개인 인터넷뱅킹 채널에서 거래가 가능합니다. 기업 채널은 법인 고객 전용입니다.' },
    { id: 22, title: '인증서 발급이력을 확인하고 싶습니다.', content: '인증센터(개인) > 인증서 관리 메뉴에서 발급이력을 확인하실 수 있습니다.' },
    { id: 21, title: '인증서 발급을 하는데, 보안카드는 어떻게 입력하는건가요?', content: '현재 AXful Bank는 보안카드 없이 인증서 발급이 가능합니다. 아이디와 비밀번호로 본인인증 후 발급 가능합니다.' },
    { id: 20, title: 'AXful Bank에서 발급 가능한 공동인증서(구 공인인증서) 종류와 이용범위는 어떻게 되나요?', content: '개인용 공동인증서, 금융인증서, AXful인증서 3종류가 있으며 인터넷뱅킹 로그인, 전자금융거래 등에 활용 가능합니다.' },
    { id: 19, title: 'PC를 포맷하거나 PC가 바뀌어서 인증서가 없어졌습니다.', content: '인증서 재발급이 필요합니다. 인증센터(개인) > 공동인증서 발급/재발급 메뉴를 이용해 주세요.' },
    { id: 18, title: '인증서암호가 올바르지 않다고 합니다.', content: '인증서 암호는 발급 시 설정한 암호와 동일해야 합니다. 5회 이상 틀리면 인증서가 잠기므로 주의해 주세요.' },
    { id: 17, title: '개인 송/포제한을 인증서의 종류와 이용범위는 무엇인가요?', content: '공동인증서는 은행, 보험, 증권 등 전 금융기관에서 사용 가능하며 전자정부 서비스에도 활용됩니다.' },
  ],
  '인터넷뱅킹': [
    { id: 20, title: '해외에서 전자금융 거래를 위해 전자금융인서를 작성하고 합니다. 작성방법이 궁금합니다.', content: '해외 IP에서 접속 시 추가 인증이 필요할 수 있습니다. 고객센터로 사전 문의 후 이용해 주세요.' },
    { id: 19, title: '이불PC(본인)지정서비스. 인서 발급(타기관 인서 등록 포함) 사유로, 자택 직장 전화 모두 없는 경우 전화인증은 어떻게 하나요?', content: '영업점 방문을 통한 직접 신청이 필요합니다. 가까운 AXful Bank 영업점을 방문해 주세요.' },
    { id: 18, title: '「해외출국확인」에서 출국확인 프로를 할 수 없습니다. 이유는 무엇요?', content: '해외출국확인 서비스는 평일 업무시간 내에만 이용 가능합니다.' },
    { id: 17, title: 'ARS인증 버튼을 선택했으나 전화가 오지 않는 경우', content: '등록된 전화번호를 확인해 주시고, 수신 차단 설정이 되어 있지 않은지 확인해 주세요.' },
    { id: 16, title: '이불PC지정서비스를 신청 완료하였으나 \'이불PC 미지정\'으로 조회됩니다.', content: '신청 후 반영까지 최대 1영업일이 소요될 수 있습니다. 당일 신청의 경우 다음 날 확인해 주세요.' },
    { id: 15, title: '추가 본인 인증은 어떤 경우에 하는 건가요?', content: '고액 이체, 신규 기기 접속, 해외 IP 접속 등 이상거래 탐지 시 추가 인증이 요청됩니다.' },
    { id: 14, title: '추가 본인 시 전화번호가 잘못 등록되어 있는 경우 어떻게 해야 하나요?', content: 'My AXful > 기본정보 수정 또는 영업점 방문을 통해 전화번호를 변경하신 후 이용해 주세요.' },
    { id: 13, title: '보안매체별 이체한도가 어떻게 되나요?', content: '공동인증서: 1일 5억/1회 1억, 금융인증서: 1일 5억/1회 1억, AXful인증서: 1일 1억/1회 1천만원 (기본 한도 기준)' },
    { id: 12, title: '인터넷뱅킹으로 계좌이체를 할 때 본인 전자확인증을 다시 출력할 수 있나요?', content: '이체결과 조회 메뉴에서 최근 90일 이내 거래 내역을 확인하고 출력할 수 있습니다.' },
    { id: 11, title: '[개인뱅킹] 인터넷뱅킹 이체한도를 변경하려면 어떻게 해야 하나요?', content: '뱅킹관리 > 계좌관리 > 이체한도 조회/변경 메뉴에서 변경 가능합니다. 증액 시 본인인증이 필요합니다.' },
  ],
  '인증서로그인': [
    { id: 3, title: '인증서암호가 올바르지 않다고 합니다.(로그인 시 오신 경우)', content: '발급 시 설정한 인증서 암호를 정확히 입력했는지 확인해 주세요. 연속 5회 오류 시 인증서가 잠깁니다. 잠긴 경우 인증서를 재발급받아야 합니다.' },
    { id: 2, title: 'PC를 포맷하거나 PC가 바뀌어서 인증서가 없어졌습니다.', content: '하드디스크에 저장된 인증서는 PC 포맷 시 삭제됩니다. 인증센터에서 재발급 후 이용해 주세요. 금융인증서/AXful인증서는 클라우드 저장으로 PC와 무관합니다.' },
    { id: 1, title: '공동인증서(구 공인인증서)가 무엇인가요?', content: '공동인증서는 인터넷 금융거래 시 본인임을 증명하는 전자 신분증입니다. 발급 시 설정한 암호로 사용하며, 유효기간은 3년입니다.' },
  ],
}

export default function FaqPage() {
  const [activeTab, setActiveTab] = useState<Tab>('공동인증서')
  const [search, setSearch] = useState('')
  const [expandedId, setExpandedId] = useState<number | null>(null)

  const items = FAQ_DATA[activeTab].filter(item =>
    !search || item.title.includes(search)
  )

  return (
    <div className="max-w-kb-container mx-auto px-6 py-8">
      {/* 브레드크럼 */}
      <div className="flex items-center gap-1 text-[12px] text-kb-text-muted mb-6">
        <Link href="/" className="hover:underline">개인뱅킹</Link>
        <span>›</span>
        <Link href="/banking/first-visit" className="hover:underline">뱅킹관리</Link>
        <span>›</span>
        <span>이용안내</span>
        <span>›</span>
        <span className="font-semibold text-kb-text">인터넷뱅킹 FAQ</span>
      </div>

      <h1 className="text-[24px] font-bold text-kb-text mb-8">인터넷뱅킹 FAQ</h1>

      {/* 탭 */}
      <div className="flex border-b-2 border-kb-primary mb-6">
        {(['공동인증서', '인터넷뱅킹', '인증서로그인'] as Tab[]).map(tab => (
          <button
            key={tab}
            onClick={() => { setActiveTab(tab); setExpandedId(null); setSearch('') }}
            className={`px-8 py-3 text-[14px] font-semibold transition-colors
              ${activeTab === tab
                ? 'bg-kb-primary text-white'
                : 'bg-kb-beige-light text-kb-text-body hover:bg-kb-primary-border border border-kb-border border-b-0'
              }`}
          >
            {tab}
          </button>
        ))}
      </div>

      {/* 안내 */}
      <div className="text-[13px] text-kb-text-body space-y-1 mb-5">
        <p>· 궁금하신 문제를 가장 빨리 해결할 드리기 위해 자주찾는 질문을 정리하고 있습니다.</p>
        <p>· [FAQ] {activeTab}과 관련하여 궁금한 점을 직접 검색하시거나 질문목록에서 찾아하시기 바랍니다.</p>
        <p>· FAQ 검색 후 찾으시는 내용이 없는 경우에는 상담원에게 직접 질문하실 수 있습니다.</p>
      </div>

      {/* 검색 */}
      <div className="flex items-center gap-2 mb-6">
        <span className="text-[13px] text-kb-text-body whitespace-nowrap">자주찾는 질문</span>
        <input
          type="text"
          value={search}
          onChange={e => setSearch(e.target.value)}
          className="flex-1 max-w-sm border border-kb-border px-3 py-2 text-[13px] outline-none focus:border-kb-primary"
        />
        <button
          onClick={() => {}}
          className="px-4 py-2 text-[13px] font-bold text-white"
          style={{ backgroundColor: KB_GOLD }}
        >
          검색
        </button>
      </div>

      {/* FAQ 목록 */}
      <div className="border-t-2 border-kb-primary">
        <div className="grid grid-cols-[60px_1fr] border-b border-kb-border bg-kb-beige-light">
          <div className="px-4 py-3 text-[13px] font-bold text-center text-kb-text">번호</div>
          <div className="px-4 py-3 text-[13px] font-bold text-center text-kb-text">제목</div>
        </div>

        {items.length === 0 ? (
          <div className="py-12 text-center text-[13px] text-kb-text-muted">
            검색 결과가 없습니다.
          </div>
        ) : (
          items.map(item => (
            <div key={item.id} className="border-b border-kb-border">
              <button
                onClick={() => setExpandedId(expandedId === item.id ? null : item.id)}
                className="w-full grid grid-cols-[60px_1fr] hover:bg-kb-beige-light/50 transition-colors text-left"
              >
                <div className="px-4 py-3.5 text-[13px] text-center text-kb-text-muted">{item.id}</div>
                <div className="px-4 py-3.5 text-[13px] text-kb-text flex items-center justify-between gap-2">
                  <span>{item.title}</span>
                  <span className="text-kb-text-muted text-[11px] flex-shrink-0">
                    {expandedId === item.id ? '▲' : '▼'}
                  </span>
                </div>
              </button>
              {expandedId === item.id && (
                <div className="px-6 py-4 bg-kb-primary-surface border-t border-kb-border text-[13px] text-kb-text-body leading-relaxed">
                  {item.content}
                </div>
              )}
            </div>
          ))
        )}
      </div>

      {/* 전체건수 */}
      <div className="flex justify-end mt-3 text-[12px] text-kb-text-muted">
        전체건수 : {items.length}건
      </div>

      {/* 이메일 상담 */}
      <div className="mt-8 border border-kb-border p-5">
        <p className="text-[13px] font-bold text-kb-text mb-1">이메일상담</p>
        <p className="text-[12px] text-kb-text-muted mb-3">
          원하시는 답변이 없을 경우나 더 자세한 사항 문의를 원하시면 상담원에게 문의해 주시기 바랍니다.
        </p>
        <button className="border border-kb-border px-4 py-1.5 text-[12px] text-kb-text-body hover:bg-kb-beige-light">
          상담하기
        </button>
      </div>
    </div>
  )
}
