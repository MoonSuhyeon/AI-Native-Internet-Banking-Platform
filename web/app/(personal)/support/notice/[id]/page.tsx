import { KB_MINT, KB_PRIMARY } from '@/lib/theme'
import Link from 'next/link'
import { notFound } from 'next/navigation'

const NOTICES: Record<string, {
  title: string
  date: string
  category: string
  body: React.ReactNode
}> = {
  'ax-assistant': {
    title: 'AXful AI 금융비서 「AX Assistant」 정식 출시 안내',
    date: '2026.05.15',
    category: '새소식',
    body: (
      <div className="space-y-6 text-[15px] text-kb-text-body leading-relaxed">
        <p>안녕하세요, AXful Bank입니다.</p>
        <p>
          고객님의 금융 생활 전반을 AI가 함께하는 <strong>「AX Assistant」</strong>가 2026년 5월 15일부터 정식 서비스를 시작합니다.
          AX Assistant는 <em>AI Transformation</em>의 핵심 서비스로, 단순한 챗봇을 넘어 고객 맞춤형 금융 파트너를 지향합니다.
        </p>

        <div>
          <h3 className="text-[16px] font-bold text-kb-text mb-3">■ 주요 기능</h3>
          <ul className="space-y-2 pl-4">
            <li className="flex gap-2"><span className="text-kb-primary font-bold flex-shrink-0">·</span>맞춤 금융상품 추천 — 소비 패턴·잔액 흐름 분석을 바탕으로 최적 상품 자동 제안</li>
            <li className="flex gap-2"><span className="text-kb-primary font-bold flex-shrink-0">·</span>지출 패턴 분석 — 월별·카테고리별 소비 리포트 및 절약 팁 제공</li>
            <li className="flex gap-2"><span className="text-kb-primary font-bold flex-shrink-0">·</span>금융 목표 설정 — AI가 함께 저축 목표를 설정하고 달성을 위한 플랜 수립</li>
            <li className="flex gap-2"><span className="text-kb-primary font-bold flex-shrink-0">·</span>24시간 AI 상담 — 금융 관련 질문을 언제든지 자연어로 문의 가능</li>
          </ul>
        </div>

        <div>
          <h3 className="text-[16px] font-bold text-kb-text mb-3">■ 서비스 이용 방법</h3>
          <ul className="space-y-2 pl-4">
            <li className="flex gap-2"><span className="text-kb-primary font-bold flex-shrink-0">·</span>AXful Bank 인터넷뱅킹 로그인 후 우측 하단 챗봇 버튼 클릭</li>
            <li className="flex gap-2"><span className="text-kb-primary font-bold flex-shrink-0">·</span>AXful Bank 앱 → 하단 메뉴 「AX Assistant」 탭 선택</li>
          </ul>
        </div>

        <div className="bg-kb-primary-bg rounded-xl p-5" style={{ border: `1px solid ${KB_MINT}20` }}>
          <p className="text-[14px] text-kb-text-muted">
            ※ AX Assistant는 AI 기반 서비스로, 제공되는 금융상품 추천 및 분석은 참고용이며 투자·금융 의사결정의 최종 책임은 고객에게 있습니다.<br />
            ※ 서비스 이용 중 불편사항은 고객센터(1588-0000)로 문의해 주시기 바랍니다.
          </p>
        </div>

        <p>앞으로도 AXful Bank는 AI Transformation을 통해 고객님의 금융 생활을 더욱 스마트하게 만들어 나가겠습니다. 감사합니다.</p>
      </div>
    ),
  },
  'ai-fraud-detection': {
    title: 'AI 기반 실시간 이상거래 탐지 시스템 전면 도입 안내',
    date: '2026.05.10',
    category: '새소식',
    body: (
      <div className="space-y-6 text-[15px] text-kb-text-body leading-relaxed">
        <p>안녕하세요, AXful Bank입니다.</p>
        <p>
          고객님의 금융 자산을 더욱 안전하게 보호하기 위해 <strong>딥러닝 기반 실시간 이상거래 탐지 시스템(AX-FDS)</strong>을 2026년 5월 10일부터 전면 도입합니다.
        </p>

        <div>
          <h3 className="text-[16px] font-bold text-kb-text mb-3">■ 주요 개선 사항</h3>
          <ul className="space-y-2 pl-4">
            <li className="flex gap-2"><span className="text-kb-primary font-bold flex-shrink-0">·</span>탐지 정확도 <strong>98.7%</strong> — 기존 규칙 기반 대비 23%p 향상</li>
            <li className="flex gap-2"><span className="text-kb-primary font-bold flex-shrink-0">·</span>오탐율 <strong>60% 감소</strong> — 정상 거래 차단으로 인한 불편 최소화</li>
            <li className="flex gap-2"><span className="text-kb-primary font-bold flex-shrink-0">·</span>실시간 분석 — 거래 발생 후 <strong>0.3초 이내</strong> 이상 여부 판단</li>
            <li className="flex gap-2"><span className="text-kb-primary font-bold flex-shrink-0">·</span>신종 사기 패턴 자동 학습 — 매일 업데이트되는 AI 모델로 신종 금융사기 대응</li>
          </ul>
        </div>

        <div>
          <h3 className="text-[16px] font-bold text-kb-text mb-3">■ 고객 영향</h3>
          <ul className="space-y-2 pl-4">
            <li className="flex gap-2"><span className="text-kb-primary font-bold flex-shrink-0">·</span>정상 거래의 경우 기존과 동일하게 서비스 이용 가능</li>
            <li className="flex gap-2"><span className="text-kb-primary font-bold flex-shrink-0">·</span>이상 거래 탐지 시 등록된 연락처로 즉시 알림 발송</li>
            <li className="flex gap-2"><span className="text-kb-primary font-bold flex-shrink-0">·</span>의심 거래 발생 시 고객센터(1588-0000) 또는 앱 내 신고 기능 이용</li>
          </ul>
        </div>

        <div className="bg-kb-primary-bg rounded-xl p-5" style={{ border: `1px solid ${KB_MINT}20` }}>
          <p className="text-[14px] text-kb-text-muted">
            ※ 본 시스템 도입으로 인한 서비스 중단은 없습니다.<br />
            ※ 이상거래 탐지 관련 문의: 고객센터 1588-0000 (24시간 운영)
          </p>
        </div>

        <p>AXful Bank는 AI 기술을 통해 고객님의 자산을 더욱 안전하게 지키겠습니다. 감사합니다.</p>
      </div>
    ),
  },
}

export default function NoticePage({ params }: { params: { id: string } }) {
  const notice = NOTICES[params.id]
  if (!notice) notFound()

  return (
    <main className="bg-white min-h-screen">
      <div className="max-w-[860px] mx-auto px-8 py-12">

        {/* 브레드크럼 */}
        <div className="flex items-center gap-2 text-[13px] text-kb-text-muted mb-8">
          <Link href="/" className="hover:text-kb-text transition-colors">홈</Link>
          <span>›</span>
          <span>새소식/이벤트</span>
          <span>›</span>
          <span className="text-kb-text">{notice.category}</span>
        </div>

        {/* 헤더 */}
        <div className="mb-8 pb-6" style={{ borderBottom: `2px solid ${KB_PRIMARY}` }}>
          <span className="inline-block px-3 py-1 text-[12px] font-bold text-white rounded-full mb-3"
            style={{ backgroundColor: KB_PRIMARY }}>
            {notice.category}
          </span>
          <h1 className="text-[24px] font-bold text-kb-text leading-snug mb-3">{notice.title}</h1>
          <p className="text-[14px] text-kb-text-muted">등록일 {notice.date} &nbsp;|&nbsp; AXful Bank</p>
        </div>

        {/* 본문 */}
        <div className="mb-12">
          {notice.body}
        </div>

        {/* 목록으로 */}
        <div className="flex justify-center">
          <Link href="/support/news"
            className="px-8 py-2.5 text-[14px] font-semibold rounded-full border transition-colors hover:bg-kb-primary-bg"
            style={{ borderColor: KB_PRIMARY, color: KB_PRIMARY }}>
            목록으로
          </Link>
        </div>
      </div>
    </main>
  )
}
