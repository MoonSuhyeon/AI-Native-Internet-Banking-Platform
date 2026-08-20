'use client'
import { KB_BLUE, KB_PRIMARY_DARK } from '@/lib/theme'

import { useState, useEffect } from 'react'
import Link from 'next/link'

type BizLoginTab = '공동금융인증서' | 'kb인증서'

export default function BizLoginPage() {
  const [tab, setTab] = useState<BizLoginTab>('공동금융인증서')
  const [showLoginSetting, setShowLoginSetting] = useState(false)

  return (
    <>
      {/* 페이지 타이틀 바 */}
      <div className="bg-white border-b border-kb-border">
        <div className="max-w-kb-container mx-auto px-6 py-4">
          <h1 className="text-2xl font-bold text-kb-text">로그인</h1>
        </div>
      </div>

      {/* 본문 */}
      <div className="py-6" style={{ backgroundColor: '#F0F8F4' }}>
        <div className="w-full max-w-[900px] mx-auto">

          {/* 로그인 카드 */}
          <div className="bg-white shadow-sm">

            {/* 탭 */}
            <div className="flex border-b border-kb-border">
              <BizTabButton active={tab === '공동금융인증서'} onClick={() => setTab('공동금융인증서')}>
                공동·금융인증서
              </BizTabButton>
              <BizTabButton active={tab === 'kb인증서'} onClick={() => setTab('kb인증서')}>
                <KBShieldIcon />
                AXful인증서
              </BizTabButton>
            </div>

            {/* 탭 컨텐츠 */}
            <div className="min-h-[280px] flex flex-col justify-center">
              {tab === '공동금융인증서' && <CommonFinCertTab />}
              {tab === 'kb인증서' && <KBCertTab />}
            </div>

            {/* 하단 바로가기 — 기업은 인증센터 + 로그인설정만 */}
            <div className="border-t border-kb-border">
              <div className="flex justify-center">
                {[
                  { icon: '🔒', label: '인증센터', href: '/biz/cert/joint-cert-issue' as string | null },
                  { icon: '⚙️', label: '로그인 설정', href: null },
                ].map((item, i) => (
                  item.href === null ? (
                    <button
                      key={item.label}
                      onClick={() => setShowLoginSetting(true)}
                      className={`flex flex-col items-center gap-1.5 py-4 px-16 text-sm text-kb-text-body
                                  hover:bg-kb-beige-light transition-colors w-full
                                  ${i > 0 ? 'border-l border-kb-border' : ''}`}
                    >
                      <span className="text-lg">{item.icon}</span>
                      <span>{item.label}</span>
                    </button>
                  ) : (
                    <Link
                      key={item.label}
                      href={item.href}
                      className={`flex flex-col items-center gap-1.5 py-4 px-16 text-sm text-kb-text-body
                                  hover:bg-kb-beige-light transition-colors w-full
                                  ${i > 0 ? 'border-l border-kb-border' : ''}`}
                    >
                      <span className="text-lg">{item.icon}</span>
                      <span>{item.label}</span>
                    </Link>
                  )
                ))}
              </div>
            </div>
          </div>

          {showLoginSetting && <BizLoginSettingModal onClose={() => setShowLoginSetting(false)} />}

          {/* 카드 하단 안내 */}
          <div className="mt-4 px-1 space-y-1">
            <p className="text-sm" style={{ color: KB_BLUE }}>
              • 인터넷뱅킹 종료 시, 안전한 금융거래를 위하여 반드시 [로그아웃]버튼을 눌러 종료하시기 바랍니다.
            </p>
            <p className="text-sm" style={{ color: KB_BLUE }}>
              • 로그인설정 버튼을 통해 자주 쓰는 로그인 방식을 설정해두면 더 간편하게 로그인 하실 수 있습니다.
            </p>
          </div>
        </div>
      </div>
    </>
  )
}

/* ── 탭 버튼 ── */
function BizTabButton({
  active,
  onClick,
  children,
}: {
  active: boolean
  onClick: () => void
  children: React.ReactNode
}) {
  return (
    <button
      onClick={onClick}
      className={`flex-1 py-4 text-base font-medium transition-colors duration-kb
                  flex items-center justify-center gap-1.5
        ${active
          ? 'bg-white text-kb-text border-b-2 border-kb-text'
          : 'bg-kb-beige-light text-kb-text-muted hover:bg-kb-beige'
        }`}
    >
      {children}
    </button>
  )
}

/* ── 공동·금융인증서 탭 ── */
function CommonFinCertTab() {
  const [showFinCert, setShowFinCert] = useState(false)

  return (
    <>
      <div className="flex divide-x divide-kb-border py-8">
        {/* 좌: 공동인증서 */}
        <div className="flex-1 flex flex-col items-center gap-5 px-10">
          <p className="text-base text-kb-text-body font-medium">공동인증서(구 공인인증서)</p>
          <button
            className="btn-primary w-full py-3.5 text-base font-bold"
            onClick={() => {
              const ok = window.confirm(
                '인증프로그램을 설치하셔야만 이용이 가능한 서비스입니다.\n[확인]을 선택하시면 설치페이지로 연결됩니다.'
              )
              if (ok) window.location.href = '/security-install'
            }}
          >
            공동인증서(구 공인인증서) 로그인
          </button>
          <div className="flex items-center gap-3 text-sm text-kb-blue">
            <Link href="/biz/cert/joint-cert-issue" className="hover:underline">공동인증서(구 공인인증서) 발급</Link>
            <span className="text-kb-border">|</span>
            <Link href="/biz/cert/joint-cert-management" className="hover:underline">인증서 관리</Link>
          </div>
        </div>

        {/* 우: 금융인증서(브라우저인증서) */}
        <div className="flex-1 flex flex-col items-center gap-5 px-10">
          <p className="text-base text-kb-text-body font-medium">금융인증서(브라우저인증서)</p>
          <button
            onClick={() => setShowFinCert(true)}
            className="btn-primary w-full py-3.5 text-base font-bold"
          >
            금융인증서(브라우저인증서) 로그인
          </button>
          <div className="flex items-center gap-3 text-sm text-kb-blue">
            <Link href="/biz/cert/fin-cert-issue" className="hover:underline">금융인증서 발급</Link>
            <span className="text-kb-border">|</span>
            <Link href="/biz/cert/fin-cert-management" className="hover:underline">인증서 관리</Link>
          </div>
        </div>
      </div>

      {showFinCert && <BizFinCertFlow onClose={() => setShowFinCert(false)} />}
    </>
  )
}

/* ── 기업 금융인증서 2단계 모달 플로우 ── */
function BizFinCertFlow({ onClose }: { onClose: () => void }) {
  const [step, setStep] = useState<'confirm' | 'yeskey'>('confirm')
  const [certTab, setCertTab] = useState<'사업자' | '개인' | '브라우저'>('사업자')

  useEffect(() => {
    const t = setTimeout(() => setStep('yeskey'), 900)
    return () => clearTimeout(t)
  }, [])
  const [bizNo, setBizNo] = useState('')
  const [phone, setPhone] = useState('')
  const [autoLogin, setAutoLogin] = useState(false)

  return (
    <div className="fixed inset-0 z-[100] flex items-center justify-center bg-black/50">

      {/* ── STEP 1: 금융인증서(사업자) 팝업창 확인 ── */}
      <div className="flex shadow-2xl" style={{ width: 760 }}>

        {/* 좌: 메인 패널 */}
        <div className="bg-white flex-1 flex" style={{ minHeight: 380 }}>
          {/* 좌측 탭 사이드바 */}
          <div className="w-[110px] border-r border-gray-200 bg-gray-50 py-1 flex-shrink-0">
            {[
              { key: '사업자' as const, label: '금융인증서\n(사업자)', icon: '☁️' },
              { key: '개인' as const, label: '금융인증서', icon: '☁️' },
              { key: '브라우저' as const, label: '브라우저인증서', icon: '🌐' },
            ].map((item) => (
              <button
                key={item.key}
                onClick={() => setCertTab(item.key)}
                className={`w-full px-2 py-3 flex flex-col items-center gap-1 text-[11px] text-center cursor-pointer transition-colors
                  ${certTab === item.key
                    ? 'bg-white border-l-2 border-blue-500 font-semibold text-kb-text'
                    : 'text-kb-text-muted hover:bg-white'
                  } ${item.key === '브라우저' ? 'opacity-50' : ''}`}
              >
                <span className="text-xl">{item.icon}</span>
                <span className="whitespace-pre-line leading-tight">{item.label}</span>
              </button>
            ))}
          </div>

          {/* 메인 컨텐츠 */}
          <div className="flex-1 flex flex-col">
            {/* 윈도우 크롬 */}
            <div className="flex items-center justify-between px-3 py-1.5 bg-gray-100 border-b border-gray-200">
              <span className="text-gray-500 text-sm px-1" aria-hidden="true">−</span>{/* 모의 창의 최소화 표시. 이 모달에 최소화는 뜻이 없어 누를 수 없게 둔다 */}
              <span className="text-[13px] font-medium text-gray-700">금융인증서(사업자)</span>
              <button onClick={onClose} className="text-gray-500 hover:text-gray-700 text-sm px-1">✕</button>
            </div>

            <div className="flex-1 flex flex-col items-center justify-center gap-4 px-8 py-8">
              {/* 체크 아이콘 */}
              <div className="w-12 h-12 rounded border-2 border-blue-400 flex items-center justify-center">
                <span className="text-blue-500 text-2xl font-bold">✓</span>
              </div>
              <p className="text-[16px] font-medium text-kb-text">팝업창을 확인해주세요.</p>
              <p className="text-[13px] text-center leading-relaxed text-gray-500">
                <span className="text-blue-600 font-medium">클라우드에 저장하는</span>{' '}
                <span className="text-kb-text font-medium">새로운 인증서</span>
                <br />
                금융인증서의 팝업창이 열리지 않았다면{' '}
                <span className="text-kb-text font-medium">아래 버튼을</span>
                <br />
                눌러 인증을 진행해주세요.
              </p>
              <button
                onClick={() => setStep('yeskey')}
                className="mt-2 px-10 py-2 bg-blue-600 text-white text-[14px] font-bold hover:bg-blue-700"
              >
                확인
              </button>
            </div>
          </div>
        </div>

        {/* 우: 이용안내 패널 */}
        <div className="w-[200px] bg-white border-l border-gray-200 flex flex-col" style={{ minHeight: 380 }}>
          <div className="flex items-center justify-between px-4 py-3 border-b border-gray-200">
            <span className="text-[12px] font-bold text-gray-700">금융인증서 이용안내</span>
            <span className="text-xl">☁️</span>
          </div>
          <div className="px-4 py-4 space-y-4 text-[11px] text-gray-600 leading-relaxed flex-1 overflow-y-auto">
            <div>
              <p className="font-bold text-gray-700 mb-1">인증서가 없는 경우</p>
              <ul className="space-y-1 list-none pl-0">
                <li>• [인증센터] &gt; 금융인증서 &gt; 금융인증서 발급/재발급 에서 인증서를 발급받을 수 있어요.</li>
                <li className="mt-1">• 스마트폰에서 금융인증서를 발급하신도 금융결제원 클라우드에 연결하여 인증서를 불러올 수 있어요.</li>
              </ul>
            </div>
            <div>
              <p className="font-bold text-gray-700 mb-1">타행/타기관에서 발급한 인증서인 경우</p>
              <ul className="pl-0">
                <li>• 타행/타기관인증서 등록 후 로그인 하실 수 있어요.</li>
              </ul>
            </div>
            <div>
              <p className="font-bold text-gray-700 mb-1">금융결제원 클라우드로 연결하기</p>
              <ul className="pl-0">
                <li>• 성명/생년월일/휴대폰번호를 입력 후 금융결제원 클라우드 나온 숫자 2자리를 휴대폰 문자로 전송하여 연결됩니다.</li>
              </ul>
            </div>
          </div>
          <div className="px-4 py-3 border-t border-gray-200">
            <Link href="#" className="text-[11px] text-blue-600 hover:underline">다시 보지않기</Link>
          </div>
        </div>
      </div>

      {/* ── STEP 2: YESKEY 사업자 인증 오버레이 ── */}
      {step === 'yeskey' && (
        <div className="absolute inset-0 flex items-center justify-center bg-black/30 z-10">
          <div className="bg-white shadow-2xl" style={{ width: 500 }}>
            {/* 헤더 */}
            <div className="flex items-center justify-between px-4 py-2.5 bg-gray-100 border-b border-gray-300">
              <span className="text-[13px] font-medium text-gray-700">금융인증서비스</span>
              <button onClick={onClose} className="text-gray-500 hover:text-gray-700">✕</button>
            </div>

            <div className="flex" style={{ minHeight: 380 }}>
              {/* 좌: YESKEY 브랜드 */}
              <div className="w-[140px] border-r border-gray-200 flex flex-col items-center justify-center gap-3 bg-gray-50 py-8 flex-shrink-0">
                <p className="font-extrabold text-[15px] tracking-tight" style={{ color: '#1a5fb4' }}>YESKEY</p>
                <div className="w-10 h-4 bg-gray-300 rounded-sm flex items-center justify-center">
                  <span className="text-[8px] text-gray-600 font-bold">금융인증서</span>
                </div>
                <div className="w-14 h-14 rounded-full border-2 border-orange-400 flex items-center justify-center mt-1 bg-white">
                  <span className="text-2xl">☁️</span>
                </div>
              </div>

              {/* 우: 사업자 인증 폼 */}
              <div className="flex-1 flex flex-col px-6 py-6 gap-4">
                <p className="text-[17px] font-bold text-kb-text leading-snug">
                  사업자용 금융인증서를<br />이용합니다
                </p>

                <div className="space-y-2">
                  <div className="flex items-center border border-gray-300 bg-gray-50">
                    <span className="text-[12px] text-gray-500 px-3 py-2 border-r border-gray-300 flex-shrink-0 w-[80px]">사업자번호</span>
                    <input
                      type="text"
                      value={bizNo}
                      onChange={(e) => setBizNo(e.target.value)}
                      placeholder="123 - 45 - 67890"
                      className="flex-1 px-3 py-2 text-[13px] outline-none bg-white placeholder-gray-400"
                    />
                  </div>
                  <div className="flex items-center border border-gray-300 bg-gray-50">
                    <span className="text-[12px] text-gray-500 px-3 py-2 border-r border-gray-300 flex-shrink-0 w-[80px]">휴대폰번호</span>
                    <input
                      type="text"
                      value={phone}
                      onChange={(e) => setPhone(e.target.value)}
                      placeholder="010 - 1234 - 5678"
                      className="flex-1 px-3 py-2 text-[13px] outline-none bg-white placeholder-gray-400"
                    />
                  </div>
                </div>

                <label className="flex items-center gap-2 text-[12px] text-gray-600 cursor-pointer">
                  <input
                    type="checkbox"
                    checked={autoLogin}
                    onChange={(e) => setAutoLogin(e.target.checked)}
                    className="w-3.5 h-3.5"
                  />
                  자동로그인
                </label>

                {/* 휴대폰 인증 API(/api/v1/mobile-auth/send)는 있지만 기업 로그인
                    흐름에 연결돼 있지 않다(docs/OPEN_ITEMS.md). 인증번호를 받을 곳도
                    화면에 없어, 지금 연결하면 절반만 도는 흐름이 된다. */}
                <button
                  disabled
                  title="휴대폰 문자인증은 준비 중입니다. 인증서 로그인을 이용해 주세요."
                  className="w-full py-2.5 text-[14px] font-bold bg-gray-200 text-gray-400 cursor-not-allowed"
                >
                  휴대폰 문자인증
                </button>

                <div className="flex items-center justify-center gap-3 text-[12px] text-blue-600">
                  <Link href="#" className="hover:underline">ARS 인증</Link>
                  <span className="text-gray-300">|</span>
                  <Link href="#" className="hover:underline">등록된 OTP로 인증</Link>
                </div>

                <div className="text-center">
                  <Link href="#" className="text-[12px] text-gray-500 hover:underline">
                    사업자용 금융인증서비스를 처음 이용하시나요?
                  </Link>
                </div>

                <div className="border-t border-gray-200 pt-3 flex items-center justify-center gap-6">
                  <Link href="#" className="flex items-center gap-1.5 text-[12px] text-gray-600 hover:underline">
                    <span className="text-blue-500">📋</span> 이용안내
                  </Link>
                  <Link href="#" className="flex items-center gap-1.5 text-[12px] text-gray-600 hover:underline">
                    <span className="text-blue-400">☁️</span> 금융결제원 고객센터
                  </Link>
                </div>
              </div>
            </div>
          </div>
        </div>
      )}
    </div>
  )
}

/* ── AXful인증서 탭 (기업 + QR 2열) ── */
function KBCertTab() {
  const [showKBBizModal, setShowKBBizModal] = useState(false)
  const [showTooltip, setShowTooltip] = useState(false)
  const [generated, setGenerated] = useState(false)
  const [confirmCode, setConfirmCode] = useState('')
  const [timeLeft, setTimeLeft] = useState(0)
  const [qrSeed, setQrSeed] = useState(0)

  useEffect(() => {
    if (!generated || timeLeft <= 0) {
      if (generated && timeLeft <= 0) setGenerated(false)
      return
    }
    const t = setTimeout(() => setTimeLeft((s) => s - 1), 1000)
    return () => clearTimeout(t)
  }, [generated, timeLeft])

  function handleGenerate() {
    setConfirmCode(String(Math.floor(1000 + Math.random() * 9000)))
    setQrSeed(Math.random())
    setTimeLeft(299)
    setGenerated(true)
  }

  const mm = String(Math.floor(timeLeft / 60)).padStart(2, '0')
  const ss = String(timeLeft % 60).padStart(2, '0')

  return (
    <div className="flex divide-x divide-kb-border py-8">

      {/* 좌: AXful인증서(기업) */}
      <div className="flex-1 flex flex-col items-center gap-5 px-10">
        <p className="text-body text-kb-text-body font-medium flex items-center gap-1.5">
          <KBShieldIcon size={18} />
          AXful인증서(기업)
        </p>
        <button
          onClick={() => setShowKBBizModal(true)}
          className="btn-primary w-full py-3.5 text-body font-bold"
        >
          AXful인증서(기업) 로그인
        </button>
        <div className="text-caption text-kb-blue">
          <Link href="/cert-biz/kb-cert-issue" className="hover:underline">AXful인증서(기업) 발급</Link>
        </div>
      </div>

      {showKBBizModal && <KBBizCertModal onClose={() => setShowKBBizModal(false)} />}

      {/* 우: QR코드 로그인 */}
      <div className="flex-1 flex flex-col items-center gap-4 px-10">
        <p className="text-h2 font-bold text-kb-text">QR코드로 로그인</p>

        <div className="w-[140px] h-[140px] border border-kb-border flex items-center justify-center bg-white">
          {generated ? <QRCodeSVG seed={qrSeed} /> : <QRPlaceholder />}
        </div>

        {generated ? (
          <div className="text-center space-y-1">
            <p className="text-body text-kb-text">
              확인 코드 <span className="font-bold">{confirmCode}</span>
            </p>
            <p className="text-body font-medium" style={{ color: KB_BLUE }}>
              남은 시간 {mm}분 {ss}초
            </p>
            <p className="text-caption text-kb-text-muted leading-relaxed pt-1">
              QR코드를 스캔하여 열린 화면에 확인코드를 입력 후<br />간편비밀번호를 입력해주세요.
            </p>
          </div>
        ) : (
          <button onClick={handleGenerate} className="btn-primary w-full py-3.5 text-body font-bold">
            QR코드 생성하기
          </button>
        )}

        <div className="relative">
          <span
            className="text-caption text-kb-text-muted hover:underline flex items-center gap-1 cursor-pointer"
            onMouseEnter={() => setShowTooltip(true)}
            onMouseLeave={() => setShowTooltip(false)}
          >
            QR코드 로그인 방법이 궁금해요
            <span className="w-4 h-4 rounded-full border border-kb-text-muted text-[10px] flex items-center justify-center">?</span>
          </span>

          {showTooltip && (
            <div className="absolute bottom-full left-1/2 -translate-x-1/2 mb-2 w-[280px] bg-white border border-kb-border shadow-md p-4 z-50 text-left">
              <p className="text-[12px] font-bold text-kb-text mb-2">QR코드 로그인 방법</p>
              <ol className="space-y-1.5 text-[11px] text-kb-text-body leading-relaxed">
                <li>① [QR코드 생성하기] 버튼을 선택해 주세요.</li>
                <li>② 스마트기기 카메라 앱으로 QR코드를 촬영해 주세요.</li>
                <li>③ PC에 보이는 확인코드를 AXful 뱅킹 앱 내에 입력해주세요.</li>
                <li>④ 확인코드 입력 후 AXful 뱅킹 앱 내에 간편비밀번호를 입력해 주세요.</li>
              </ol>
            </div>
          )}
        </div>
      </div>
    </div>
  )
}

/* ── AXful인증서(기업) 로그인 모달 ── */
function KBBizCertModal({ onClose }: { onClose: () => void }) {
  const [bizNum1, setBizNum1] = useState('')
  const [bizNum2, setBizNum2] = useState('')
  const [bizNum3, setBizNum3] = useState('')
  const [phonePrefix, setPhonePrefix] = useState('010')
  const [phoneNum, setPhoneNum] = useState('')
  const [saveInfo, setSaveInfo] = useState(false)

  return (
    <div className="fixed inset-0 z-[200] flex items-center justify-center bg-black/40">
      <div className="bg-white w-[480px] shadow-xl">

        {/* 헤더 */}
        <div className="flex items-center justify-between px-6 py-4 border-b border-kb-border">
          <p className="text-body font-bold text-kb-text">AXful인증서(기업)</p>
          <button onClick={onClose} className="text-kb-text-muted hover:text-kb-text text-xl leading-none">✕</button>
        </div>

        {/* 본문 */}
        <div className="px-8 pt-7 pb-8 space-y-6">
          <p className="text-body font-medium text-kb-text text-center">인증서 정보를 입력해 주세요</p>

          {/* 폼 */}
          <div className="space-y-3">
            {/* 사업자등록번호 */}
            <div className="flex items-center gap-3">
              <label className="w-28 text-caption text-kb-text flex-shrink-0">사업자등록번호</label>
              <div className="flex items-center gap-1.5">
                <input
                  type="text"
                  value={bizNum1}
                  onChange={(e) => setBizNum1(e.target.value.replace(/\D/g, '').slice(0, 3))}
                  className="w-16 border border-kb-border px-2 py-1.5 text-caption text-center focus:outline-none focus:border-kb-taupe"
                />
                <span className="text-kb-text-muted">-</span>
                <input
                  type="text"
                  value={bizNum2}
                  onChange={(e) => setBizNum2(e.target.value.replace(/\D/g, '').slice(0, 2))}
                  className="w-12 border border-kb-border px-2 py-1.5 text-caption text-center focus:outline-none focus:border-kb-taupe"
                />
                <span className="text-kb-text-muted">-</span>
                <input
                  type="text"
                  value={bizNum3}
                  onChange={(e) => setBizNum3(e.target.value.replace(/\D/g, '').slice(0, 5))}
                  className="w-20 border border-kb-border px-2 py-1.5 text-caption text-center focus:outline-none focus:border-kb-taupe"
                />
              </div>
            </div>

            {/* 휴대폰번호 */}
            <div className="flex items-center gap-3">
              <label className="w-28 text-caption text-kb-text flex-shrink-0">휴대폰번호</label>
              <div className="flex items-center gap-1.5">
                <select
                  value={phonePrefix}
                  onChange={(e) => setPhonePrefix(e.target.value)}
                  className="border border-kb-border px-2 py-1.5 text-caption text-kb-text focus:outline-none bg-white"
                >
                  {['010', '011', '016', '017', '018', '019'].map((v) => (
                    <option key={v} value={v}>{v}</option>
                  ))}
                </select>
                <span className="text-kb-text-muted">-</span>
                <input
                  type="text"
                  value={phoneNum}
                  onChange={(e) => setPhoneNum(e.target.value.replace(/\D/g, '').slice(0, 8))}
                  className="w-40 border border-kb-border px-2 py-1.5 text-caption focus:outline-none focus:border-kb-taupe"
                />
              </div>
            </div>
          </div>

          {/* 안내 + 저장 */}
          <div className="space-y-2">
            <p className="text-[11px] text-kb-text-muted">
              ⓘ 입력하신 휴대폰번호로 인증번호 안내 SMS를 전송합니다.
            </p>
            <label className="flex items-center gap-2 cursor-pointer">
              <input
                type="checkbox"
                checked={saveInfo}
                onChange={(e) => setSaveInfo(e.target.checked)}
                className="w-3.5 h-3.5 accent-kb-taupe"
              />
              <span className="text-caption text-kb-text-body">정보 저장하기</span>
            </label>
          </div>

          {/* 버튼 */}
          <div className="flex flex-col items-center gap-3 pt-2">
            {/* 사업자등록번호로 인증하는 기업 인증서 로그인 API 가 없다 —
                개인 인증서는 loginId/password 로 발급·로그인한다(docs/OPEN_ITEMS.md). */}
            <button disabled
              title="기업 인증서 로그인은 준비 중입니다."
              className="px-14 py-2.5 bg-kb-yellow text-body font-bold text-kb-text opacity-40 cursor-not-allowed">
              확인
            </button>
            <Link href="#" className="text-caption text-kb-blue hover:underline">
              인증서 발급하기
            </Link>
          </div>
        </div>
      </div>
    </div>
  )
}

/* ── QR SVG ── */
function seededRand(seed: number) {
  let s = Math.floor(seed * 1e9)
  return () => {
    s = (Math.imul(s, 1664525) + 1013904223) | 0
    return (s >>> 0) / 0xffffffff
  }
}

function QRCodeSVG({ seed }: { seed: number }) {
  const SIZE = 25
  const CELL = 5
  const rand = seededRand(seed)
  const grid: boolean[][] = Array.from({ length: SIZE }, () => Array(SIZE).fill(false))

  function setFinder(row: number, col: number) {
    for (let r = 0; r < 7; r++)
      for (let c = 0; c < 7; c++)
        grid[row + r][col + c] =
          r === 0 || r === 6 || c === 0 || c === 6 || (r >= 2 && r <= 4 && c >= 2 && c <= 4)
  }
  setFinder(0, 0)
  setFinder(0, SIZE - 7)
  setFinder(SIZE - 7, 0)

  for (let i = 8; i < SIZE - 8; i++) {
    grid[6][i] = i % 2 === 0
    grid[i][6] = i % 2 === 0
  }

  const reserved = new Set<string>()
  for (let r = 0; r < 9; r++) for (let c = 0; c < 9; c++) reserved.add(`${r},${c}`)
  for (let r = 0; r < 9; r++) for (let c = SIZE - 8; c < SIZE; c++) reserved.add(`${r},${c}`)
  for (let r = SIZE - 8; r < SIZE; r++) for (let c = 0; c < 9; c++) reserved.add(`${r},${c}`)
  for (let i = 6; i < SIZE - 6; i++) { reserved.add(`6,${i}`); reserved.add(`${i},6`) }

  for (let r = 0; r < SIZE; r++)
    for (let c = 0; c < SIZE; c++)
      if (!reserved.has(`${r},${c}`)) grid[r][c] = rand() > 0.5

  const total = SIZE * CELL
  return (
    <svg width={total} height={total} viewBox={`0 0 ${total} ${total}`}>
      <rect width={total} height={total} fill="white" />
      {grid.flatMap((row, r) =>
        row.map((filled, c) =>
          filled ? <rect key={`${r}-${c}`} x={c * CELL} y={r * CELL} width={CELL} height={CELL} fill="#1a1a1a" /> : null
        )
      )}
    </svg>
  )
}

function QRPlaceholder() {
  return (
    <svg width="100" height="100" viewBox="0 0 100 100" fill="none" opacity="0.3">
      <rect x="5" y="5" width="35" height="35" rx="2" stroke="#333" strokeWidth="3" fill="none" />
      <rect x="13" y="13" width="19" height="19" fill="#333" />
      <rect x="60" y="5" width="35" height="35" rx="2" stroke="#333" strokeWidth="3" fill="none" />
      <rect x="68" y="13" width="19" height="19" fill="#333" />
      <rect x="5" y="60" width="35" height="35" rx="2" stroke="#333" strokeWidth="3" fill="none" />
      <rect x="13" y="68" width="19" height="19" fill="#333" />
      <rect x="60" y="60" width="10" height="10" fill="#333" />
      <rect x="75" y="60" width="10" height="10" fill="#333" />
      <rect x="60" y="75" width="10" height="10" fill="#333" />
      <rect x="75" y="75" width="10" height="10" fill="#333" />
      <rect x="85" y="85" width="10" height="10" fill="#333" />
    </svg>
  )
}

/* ── KB 방패 아이콘 ── */
function KBShieldIcon({ size = 16 }: { size?: number }) {
  return (
    <svg width={size} height={size} viewBox="0 0 24 24" fill="none">
      <path d="M12 2L4 6v6c0 5.5 3.5 10.7 8 12 4.5-1.3 8-6.5 8-12V6L12 2z" fill="#5BC9A8" stroke="#2D9A76" strokeWidth="1.5" />
      <text x="12" y="16" textAnchor="middle" fontSize="9" fontWeight="bold" fill="#333">AX</text>
    </svg>
  )
}

/* ── 로그인 설정 모달 ── */
const BIZ_LOGIN_SETTING_OPTIONS = [
  '공동인증서(구 공인인증서)',
  '금융인증서',
  'AXful인증서',
  'AXful인증서(기업)',
  '사용안함',
]

function BizLoginSettingModal({ onClose }: { onClose: () => void }) {
  const [selected, setSelected] = useState('사용안함')

  return (
    <div className="fixed inset-0 z-[300] flex items-center justify-center bg-black/40">
      <div className="bg-white w-[400px] shadow-lg">
        <div className="flex items-center justify-between px-6 py-4" style={{ backgroundColor: KB_PRIMARY_DARK }}>
          <p className="text-body font-bold text-white">로그인 설정</p>
          <button onClick={onClose} className="text-white/80 hover:text-white text-xl leading-none">✕</button>
        </div>
        <div className="mx-6 mt-5 mb-4 border border-kb-border px-4 py-3 bg-kb-beige-light">
          <p className="text-caption text-kb-text-body">
            · 자주 쓰는 로그인 방식을 설정하고 간편하게 로그인하세요.
          </p>
        </div>
        <div className="mx-6 border border-kb-border divide-y divide-kb-border mb-6">
          {BIZ_LOGIN_SETTING_OPTIONS.map((option) => (
            <label
              key={option}
              className="flex items-center gap-3 px-5 py-3 cursor-pointer hover:bg-kb-beige-light transition-colors"
            >
              <input
                type="radio"
                name="biz-login-setting"
                value={option}
                checked={selected === option}
                onChange={() => setSelected(option)}
                className="accent-kb-taupe w-4 h-4 flex-shrink-0"
              />
              <span className="text-caption text-kb-text">{option}</span>
            </label>
          ))}
        </div>
        <div className="flex justify-center pb-6">
          <button
            onClick={onClose}
            className="px-14 py-2.5 bg-kb-yellow text-body font-bold text-kb-text hover:brightness-95 transition-all"
          >
            설정
          </button>
        </div>
      </div>
    </div>
  )
}
