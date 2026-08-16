import { KB_MINT, KB_PRIMARY, KB_PRIMARY_BG } from '@/lib/theme'
import Link from 'next/link'
import { notFound } from 'next/navigation'

const EVENTS: Record<string, {
  title: string
  period: string
  body: React.ReactNode
}> = {
  'ai-portfolio-launch': {
    title: 'AI 포트폴리오 추천 서비스 론칭 기념 특별 금리 이벤트',
    period: '2026.05.01 ~ 2026.07.31',
    body: (
      <div className="space-y-6 text-[15px] text-kb-text-body leading-relaxed">
        <p>AXful Bank의 새로운 <strong>AI 포트폴리오 추천 서비스</strong> 출시를 기념하여 특별 금리 우대 이벤트를 진행합니다.</p>

        <div className="rounded-2xl p-6 space-y-4" style={{ backgroundColor: KB_PRIMARY_BG, border: `1px solid ${KB_MINT}30` }}>
          <h3 className="text-[17px] font-bold text-kb-text">이벤트 혜택</h3>
          <div className="space-y-3">
            <div className="flex items-start gap-3">
              <span className="w-6 h-6 rounded-full flex items-center justify-center text-white text-[12px] font-bold flex-shrink-0 mt-0.5"
                style={{ backgroundColor: KB_PRIMARY }}>1</span>
              <div>
                <p className="font-semibold text-kb-text">AI 추천 정기예금 가입 시 금리 우대</p>
                <p className="text-[14px] text-kb-text-muted mt-0.5">AI 포트폴리오 서비스를 통해 추천받은 정기예금 가입 시 <strong style={{ color: KB_PRIMARY }}>연 0.3%p 추가 금리</strong> 제공</p>
              </div>
            </div>
            <div className="flex items-start gap-3">
              <span className="w-6 h-6 rounded-full flex items-center justify-center text-white text-[12px] font-bold flex-shrink-0 mt-0.5"
                style={{ backgroundColor: KB_PRIMARY }}>2</span>
              <div>
                <p className="font-semibold text-kb-text">AI 포트폴리오 분석 리포트 무료 제공</p>
                <p className="text-[14px] text-kb-text-muted mt-0.5">이벤트 기간 중 가입 고객 전원에게 <strong style={{ color: KB_PRIMARY }}>월 1회 프리미엄 AI 분석 리포트</strong> 무료 발송</p>
              </div>
            </div>
            <div className="flex items-start gap-3">
              <span className="w-6 h-6 rounded-full flex items-center justify-center text-white text-[12px] font-bold flex-shrink-0 mt-0.5"
                style={{ backgroundColor: KB_PRIMARY }}>3</span>
              <div>
                <p className="font-semibold text-kb-text">추천인 이벤트</p>
                <p className="text-[14px] text-kb-text-muted mt-0.5">친구 추천 가입 시 추천인·피추천인 모두 <strong style={{ color: KB_PRIMARY }}>AX 포인트 5,000점</strong> 적립</p>
              </div>
            </div>
          </div>
        </div>

        <div>
          <h3 className="text-[16px] font-bold text-kb-text mb-3">■ 참여 방법</h3>
          <ol className="space-y-2 pl-4">
            <li className="flex gap-2"><span className="font-bold text-kb-primary">1.</span> AXful Bank 인터넷뱅킹 로그인</li>
            <li className="flex gap-2"><span className="font-bold text-kb-primary">2.</span> 금융상품 → AI 포트폴리오 추천 서비스 접속</li>
            <li className="flex gap-2"><span className="font-bold text-kb-primary">3.</span> AI 추천 결과 확인 후 원하는 상품 가입</li>
          </ol>
        </div>

        <div>
          <h3 className="text-[16px] font-bold text-kb-text mb-3">■ 유의사항</h3>
          <ul className="space-y-1.5 pl-4 text-[14px] text-kb-text-muted">
            <li className="flex gap-2"><span>·</span>본 이벤트는 AXful Bank 인터넷뱅킹 회원에 한하여 적용됩니다.</li>
            <li className="flex gap-2"><span>·</span>우대금리는 AI 포트폴리오 서비스 최초 이용 고객에 한해 1회 적용됩니다.</li>
            <li className="flex gap-2"><span>·</span>이벤트 내용은 당행 사정에 따라 변경될 수 있습니다.</li>
            <li className="flex gap-2"><span>·</span>문의: 고객센터 1588-0000</li>
          </ul>
        </div>
      </div>
    ),
  },
  'ax-banking-renewal': {
    title: 'AX 뱅킹 앱 리뉴얼 기념 AI 챌린지 이벤트',
    period: '2026.04.20 ~ 2026.06.30',
    body: (
      <div className="space-y-6 text-[15px] text-kb-text-body leading-relaxed">
        <p>AXful Bank 앱이 완전히 새로워졌습니다! AX 뱅킹 앱 리뉴얼을 기념하여 <strong>AI 챌린지 이벤트</strong>를 진행합니다.</p>

        <div className="rounded-2xl p-6 space-y-4" style={{ backgroundColor: KB_PRIMARY_BG, border: `1px solid ${KB_MINT}30` }}>
          <h3 className="text-[17px] font-bold text-kb-text">AI 챌린지 미션</h3>
          <div className="space-y-3">
            {[
              { mission: 'AI 금융비서 첫 대화', reward: 'AX 포인트 1,000점', desc: 'AX Assistant와 첫 번째 대화를 시작하세요' },
              { mission: 'AI 예금 추천 받기', reward: 'AX 포인트 2,000점', desc: 'AI 포트폴리오 서비스에서 예금 추천을 받아보세요' },
              { mission: 'AI 지출 분석 확인', reward: 'AX 포인트 1,500점', desc: '이번 달 AI 지출 분석 리포트를 확인하세요' },
              { mission: 'AI 대출 한도 조회', reward: 'AX 포인트 1,000점', desc: 'AI 기반 대출 한도 조회 서비스를 이용해보세요' },
            ].map((item, i) => (
              <div key={i} className="flex items-start gap-3 bg-white rounded-xl p-4" style={{ border: `1px solid ${KB_MINT}20` }}>
                <span className="w-7 h-7 rounded-full flex items-center justify-center text-white text-[12px] font-bold flex-shrink-0"
                  style={{ backgroundColor: KB_MINT }}>
                  {i + 1}
                </span>
                <div className="flex-1">
                  <div className="flex items-center justify-between mb-1">
                    <p className="font-semibold text-kb-text">{item.mission}</p>
                    <span className="text-[13px] font-bold" style={{ color: KB_PRIMARY }}>{item.reward}</span>
                  </div>
                  <p className="text-[13px] text-kb-text-muted">{item.desc}</p>
                </div>
              </div>
            ))}
          </div>
        </div>

        <div>
          <h3 className="text-[16px] font-bold text-kb-text mb-3">■ 전체 미션 달성 보너스</h3>
          <p>4가지 미션 모두 완료 시 <strong style={{ color: KB_PRIMARY }}>AXful 스타벅스 아메리카노 e-쿠폰</strong> 추가 증정 (선착순 5,000명)</p>
        </div>

        <div>
          <h3 className="text-[16px] font-bold text-kb-text mb-3">■ 유의사항</h3>
          <ul className="space-y-1.5 pl-4 text-[14px] text-kb-text-muted">
            <li className="flex gap-2"><span>·</span>AX 포인트는 적립 후 30일 이내 사용 가능합니다.</li>
            <li className="flex gap-2"><span>·</span>미션별 1인 1회 참여 가능합니다.</li>
            <li className="flex gap-2"><span>·</span>e-쿠폰은 이벤트 종료 후 2주 이내 앱 내 알림으로 발송됩니다.</li>
            <li className="flex gap-2"><span>·</span>문의: 고객센터 1588-0000</li>
          </ul>
        </div>
      </div>
    ),
  },
}

export default function EventPage({ params }: { params: { id: string } }) {
  const event = EVENTS[params.id]
  if (!event) notFound()

  return (
    <main className="bg-white min-h-screen">
      <div className="max-w-[860px] mx-auto px-8 py-12">

        {/* 브레드크럼 */}
        <div className="flex items-center gap-2 text-[13px] text-kb-text-muted mb-8">
          <Link href="/" className="hover:text-kb-text transition-colors">홈</Link>
          <span>›</span>
          <span>새소식/이벤트</span>
          <span>›</span>
          <span className="text-kb-text">이벤트</span>
        </div>

        {/* 헤더 */}
        <div className="mb-8 pb-6" style={{ borderBottom: `2px solid ${KB_MINT}` }}>
          <span className="inline-block px-3 py-1 text-[12px] font-bold rounded-full mb-3"
            style={{ backgroundColor: `${KB_MINT}20`, color: KB_PRIMARY }}>
            이벤트
          </span>
          <h1 className="text-[24px] font-bold text-kb-text leading-snug mb-3">{event.title}</h1>
          <p className="text-[14px] text-kb-text-muted">이벤트 기간: {event.period}</p>
        </div>

        {/* 본문 */}
        <div className="mb-12">
          {event.body}
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
