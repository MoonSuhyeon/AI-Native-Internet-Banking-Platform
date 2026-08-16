import Link from 'next/link'

const TRANSFER_SIDEBAR_TOP = [
  { label: '계좌이체', href: '/transfer/account' },
  { label: '이체결과 조회', href: '#' },
  { label: '자동이체', href: '#' },
  { label: '에스크로 이체', href: '#' },
]

const AUTO_SERVICE_ITEMS = [
  // '자동이체통합관리 서비스란?' 안내 화면이 없어 뺐다

  { label: '자동이체 계좌변경', href: '/transfer/auto-service/change', active: true },
  { label: '자동납부 계좌조회/출금계좌변경', href: '#' },
  { label: '자동납부 변경신청결과 조회', href: '#' },
  { label: '자동송금 계좌조회/출금계좌변경', href: '#' },
  { label: '자동송금 변경신청결과 조회', href: '#' },
]

const STEPS = [
  { step: '01', label: '서비스\n이용안내' },
  { step: '02', label: '다른은행 자동이체\n내역조회' },
  { step: '03', label: '변경하려는\n자동이체 선택' },
  { step: '04', label: 'AXful Bank 계좌로\n변경신청' },
]

export default function AutoServiceChangePage() {
  return (
    <div className="max-w-kb-container mx-auto px-6">
      <div className="flex">
        {/* 사이드바 */}
        <aside className="w-[180px] flex-shrink-0 border-r border-kb-border min-h-[700px] pt-6 pr-2">
          <h2 className="text-base font-bold text-kb-text mb-5 px-1">이체</h2>
          <nav>
            {TRANSFER_SIDEBAR_TOP.map(item => (
              <Link key={item.label} href={item.href}
                className="block px-2 py-2 text-sm text-kb-text-muted hover:text-kb-text">
                {item.label}
              </Link>
            ))}
            <div>
              <div className="flex items-center justify-between px-2 py-2 text-sm text-kb-text-body font-semibold">
                <span>자동이체 서비스</span>
                <span className="text-[10px]">˄</span>
              </div>
              <ul className="mb-1">
                {AUTO_SERVICE_ITEMS.map(item => (
                  <li key={item.label}>
                    <Link href={item.href}
                      className={`block px-3 py-1.5 text-sm ${
                        item.active
                          ? 'bg-kb-yellow font-semibold text-kb-text'
                          : 'text-kb-text-muted hover:text-kb-text hover:bg-kb-beige-light'
                      }`}>
                      {item.label}
                    </Link>
                  </li>
                ))}
              </ul>
            </div>
          </nav>
          <div className="mt-4">
            <Link href="/cert"
              className="flex items-center gap-2 border border-kb-border px-3 py-2 text-sm text-kb-text-body hover:bg-kb-beige-light">
              🔒 인증센터
            </Link>
          </div>
        </aside>

        {/* 본문 */}
        <main className="flex-1 pl-8 pt-4 pb-12">
          <div className="flex justify-end mb-2 text-[12px] text-kb-text-muted gap-1">
            <span>개인뱅킹</span><span>&gt;</span>
            <span>이체</span><span>&gt;</span>
            <span>자동이체 서비스</span><span>&gt;</span>
            <span className="font-semibold text-kb-text">자동이체 계좌변경</span>
          </div>

          <h1 className="text-[20px] font-bold text-kb-text mb-6">자동이체 계좌변경</h1>

          {/* 안내 박스 */}
          <div className="bg-kb-beige-light border border-kb-border rounded-xl p-6 mb-8 flex items-start gap-3">
            <span className="text-[18px] text-kb-yellow mt-0.5">ℹ</span>
            <p className="text-[13px] text-kb-text-body leading-relaxed">
              조회/출금계좌변경은 다른 은행의 자동이체 출금계좌를 AXful Bank 계좌로 변경하는 거래입니다.<br />
              자동이체 출금계좌변경 서비스를 이용하시면 다른 금융기관에 등록된 자동이체를 AXful Bank 계좌로 한번에 변경하실 수 있습니다.
            </p>
          </div>

          {/* 절차 */}
          <div className="mb-10">
            <h2 className="text-lg font-bold text-kb-text mb-5">자동이체 출금계좌변경 절차</h2>
            <div className="flex items-center">
              {STEPS.map((item, i) => (
                <div key={item.step} className="flex items-center">
                  <div className="flex flex-col items-center" style={{ width: 110 }}>
                    <div className="w-20 h-20 rounded-full border-2 border-kb-yellow bg-white flex flex-col items-center justify-center mb-2 shadow-sm">
                      <span className="text-[11px] text-kb-text-muted">{item.step}</span>
                      <span className="text-[11px] font-bold text-kb-text whitespace-pre-line text-center leading-tight mt-0.5">
                        {item.label}
                      </span>
                    </div>
                  </div>
                  {i < STEPS.length - 1 && (
                    <span className="text-kb-border text-2xl mx-1 mb-2">›</span>
                  )}
                </div>
              ))}
            </div>
          </div>

          {/* 카드 2개 */}
          <div className="grid grid-cols-2 gap-6 mb-8">
            <div className="border border-kb-border-dark rounded-xl p-6">
              <h3 className="text-lg font-bold text-kb-text mb-2">자동송금이란?</h3>
              <p className="text-[13px] text-kb-text-muted mb-5 leading-relaxed">
                개인이 수취인의 계좌를 지정하여 매월 일정액을 자동 이체하는 서비스입니다.
                정기적으로 송금해야 하는 용돈, 생활비 등을 편리하게 이체할 수 있습니다.
              </p>
              <button className="w-full bg-kb-yellow py-2.5 text-[14px] font-bold text-kb-text hover:bg-kb-yellow-dark">
                자동송금 계좌변경하기
              </button>
            </div>
            <div className="border border-kb-border-dark rounded-xl p-6">
              <h3 className="text-lg font-bold text-kb-text mb-2">자동납부란?</h3>
              <p className="text-[13px] text-kb-text-muted mb-5 leading-relaxed">
                요금청구기관의 청구에 의해 자동으로 납부되는 서비스입니다.
                공과금, 보험료, 통신요금 등의 자동이체 출금계좌를 AXful Bank으로 변경할 수 있습니다.
              </p>
              <button className="w-full bg-kb-yellow py-2.5 text-[14px] font-bold text-kb-text hover:bg-kb-yellow-dark">
                자동납부 계좌변경하기
              </button>
            </div>
          </div>

          {/* 서비스 이용시간 */}
          <div className="border border-kb-border rounded-xl p-6 text-[12px] text-kb-text-muted">
            <p className="font-semibold text-kb-text mb-2">서비스 이용시간</p>
            <p>· 서비스 이용시간 : 00:00 ~ 24:00 (연중무휴)</p>
            <p className="mt-1">· 단, 은행 시스템 작업 등으로 인해 일시적으로 서비스 이용이 불가할 수 있습니다.</p>
          </div>
        </main>
      </div>
    </div>
  )
}
