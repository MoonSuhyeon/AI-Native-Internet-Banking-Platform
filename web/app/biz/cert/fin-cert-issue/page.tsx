'use client'

import Link from 'next/link'
import { useRouter } from 'next/navigation'
import { useState } from 'react'
import MouseNumKeypad from '@/components/ui/MouseNumKeypad'

const TERMS = [
  { label: '전자금융거래기본약관' },
  { label: '전자금융서비스이용약관' },
  { label: '전자금융서비스설명서' },
  { label: '개인(신용)정보 수집·이용 동의서(기업 공동/금융인증서 발급용)' },
  { label: '고유식별정보 수집·이용 동의서(기업 공동/금융인증서 발급용)' },
]

const STEPS = [
  '약관동의 및\n사용자 본인확인',
  '인증서 발급\n방식 선택',
  '발급용\nOTP 인증',
  '비밀번호\n입력',
  '인증서 발급\n완료',
  '로그인',
]

export default function BizFinCertIssuePage() {
  const router = useRouter()
  const [stepError, setStepError] = useState('')
  const [expandedTerms, setExpandedTerms] = useState<Set<number>>(new Set())
  const [checkedTerms, setCheckedTerms] = useState<Set<number>>(new Set())
  const [allChecked, setAllChecked] = useState(false)
  const [userId, setUserId] = useState('')
  const [bizNum1, setBizNum1] = useState('')
  const [bizNum2, setBizNum2] = useState('')
  const [bizNum3, setBizNum3] = useState('')
  const [rrnFront, setRrnFront] = useState('')
  const [mouseInput, setMouseInput] = useState(false)
  const [rrnBack, setRrnBack] = useState('')

  /**
   * 약관 동의·본인확인 단계를 마치고 발급 화면으로 넘긴다.
   *
   * 예전에는 이 버튼에 핸들러가 없어 눌러도 아무 일이 없었다 — 단계 표시는
   * 여섯 칸인데 첫 칸에서 멈춰 있었고, 고객은 무엇이 잘못됐는지 알 수 없었다.
   *
   * 발급 자체는 개인·기업이 같은 API 를 쓴다(로그인 아이디로 발급한다).
   * 화면을 두 벌 만들면 한쪽만 고치는 일이 생기므로 발급 단계는 공용 화면으로
   * 보낸다.
   */
  function handleNext() {
    if (!allChecked) { setStepError('필수 약관에 모두 동의해 주세요.'); return }
    if (!userId.trim()) { setStepError('사용자 ID를 입력해 주세요.'); return }
    setStepError('')
    router.push('/cert/fin-cert-issue?corporate=1')
  }

  function toggleTerm(i: number) {
    setExpandedTerms(prev => {
      const next = new Set(prev)
      if (next.has(i)) { next.delete(i) } else { next.add(i) }
      return next
    })
  }

  function checkTerm(i: number) {
    setCheckedTerms(prev => {
      const next = new Set(prev)
      if (next.has(i)) { next.delete(i) } else { next.add(i) }
      setAllChecked(next.size === TERMS.length)
      return next
    })
  }

  function handleAllCheck() {
    if (allChecked) {
      setCheckedTerms(new Set())
      setAllChecked(false)
    } else {
      setCheckedTerms(new Set(TERMS.map((_, i) => i)))
      setAllChecked(true)
    }
  }

  return (
    <div className="space-y-8">
      {/* 브레드크럼 */}
      <div className="flex items-center gap-2 text-caption text-kb-text-muted">
        <span>인증센터(기업)</span>
        <span>&gt;</span>
        <span>금융인증서</span>
        <span>&gt;</span>
        <span className="text-kb-text font-medium">인증서 발급/재발급</span>
      </div>

      {/* 페이지 제목 */}
      <h2 className="text-[22px] font-bold text-kb-text border-b-2 border-kb-text pb-3">
        인증서 발급/재발급
      </h2>

      {/* 스텝퍼 (6단계) */}
      <div className="flex items-start justify-center py-2">
        {STEPS.map((label, i) => (
          <div key={i} className="flex items-start">
            <div className="flex flex-col items-center">
              <div className={`w-8 h-8 rounded-full flex items-center justify-center text-caption font-bold flex-shrink-0
                ${i === 0 ? 'bg-kb-yellow text-kb-text' : 'border border-kb-border text-kb-text-muted bg-white'}`}>
                {i + 1}
              </div>
              {i === 0 && (
                <span className="text-[10px] text-kb-text mt-1 text-center whitespace-pre-line leading-tight w-16 font-medium">
                  {label}
                </span>
              )}
            </div>
            {i < STEPS.length - 1 && <div className="w-6 h-px bg-kb-border mt-4 mx-0.5 flex-shrink-0" />}
          </div>
        ))}
      </div>

      {/* 안내 박스 */}
      <div className="border border-[#b3cce8] bg-[#f0f6ff] p-4 space-y-1">
        <p className="text-caption text-kb-text-body leading-relaxed">· 복수사용자의 경우 Master, S-Sub, Sub 사용자로 공동금융인증서를 발급합니다.</p>
        <p className="text-caption text-kb-text-body leading-relaxed">· Master와 S-Sub 사용자의 경우에는 영업점에서 등록된 사업 ID/사업자등록특번으로 입력 후 인증서 발급을 실행합니다.</p>
        <p className="text-caption text-kb-text-body leading-relaxed">· Sub 사용자의 경우에는 Master가 설정한 Sub ID/사용자 암호 입력 후 인증서 발급을 실행합니다.</p>
        <p className="text-caption leading-relaxed" style={{ color: '#CC0000' }}>
          · Sub 및 S-Sub는 AXful 제한중인증서만 사용 가능하며 별도 발급 수수료는 없습니다.
        </p>
      </div>

      {/* 약관동의 및 사용자 본인 확인 */}
      <section>
        <h3 className="text-body font-bold text-kb-text mb-4 pb-2 border-b border-kb-border">
          약관동의 및 사용자 본인 확인
        </h3>
        <div className="border border-kb-border divide-y divide-kb-border">
          <div className="flex items-center justify-between px-4 py-3">
            <div className="flex items-center gap-3">
              <CheckBox checked={allChecked} onChange={handleAllCheck} />
              <span className="text-caption font-medium text-kb-text">전체약관보기</span>
            </div>
            <span className="text-kb-text-muted text-sm">&gt;</span>
          </div>
          {TERMS.map((term, i) => (
            <div key={term.label}>
              <div className="flex items-center px-4 py-3 gap-3">
                <CheckBox checked={checkedTerms.has(i)} onChange={() => checkTerm(i)} />
                <span className="text-caption text-kb-blue font-medium shrink-0">[필수]</span>
                <span className="flex-1 text-caption text-kb-text">{term.label}</span>
                <button
                  onClick={() => toggleTerm(i)}
                  className="text-kb-text-muted text-sm px-2 hover:text-kb-text transition-colors"
                >
                  {expandedTerms.has(i) ? '▲' : '▼'}
                </button>
              </div>
              {expandedTerms.has(i) && (
                <div className="px-6 py-4 bg-kb-beige-light border-t border-kb-border text-caption text-kb-text-muted leading-relaxed">
                  {term.label}에 관한 약관 내용입니다.
                </div>
              )}
            </div>
          ))}
        </div>
      </section>

      {/* 본인확인 */}
      <section>
        <h3 className="text-body font-bold text-kb-text mb-4 pb-2 border-b border-kb-border">
          본인확인(인터넷뱅킹ID/사용자암호 입력)
        </h3>
        <div className="border border-kb-border divide-y divide-kb-border">

          {/* 사용자 ID */}
          <div className="flex items-start px-6 py-4 gap-4">
            <label className="w-44 text-caption font-medium text-kb-text flex-shrink-0 pt-2">사용자 ID</label>
            <div className="flex-1 space-y-1">
              <div className="flex items-center gap-3">
                <input
                  type="text"
                  value={userId}
                  onChange={(e) => setUserId(e.target.value)}
                  className="flex-1 border border-kb-border px-3 py-2 text-caption focus:outline-none focus:border-kb-taupe"
                />
                <Link href="#" className="text-caption text-kb-blue hover:underline whitespace-nowrap">
                  ID를 모르시는 경우 ⓘ
                </Link>
              </div>
              <p className="text-[11px] text-kb-text-muted">
                ⓘ 인증서를 사용할 Master 또는 Sub의 ID 입력하세요. (ID표시는 Master만 가능합니다)
              </p>
            </div>
          </div>

          {/* 사업자등록번호 */}
          <div className="flex items-center px-6 py-4 gap-4">
            <label className="w-44 text-caption font-medium text-kb-text flex-shrink-0">
              사업자등록번호<br />
              <span className="font-normal text-[11px] text-kb-text-muted">(납세번호/고유번호)</span>
            </label>
            <div className="flex items-center gap-2">
              <input
                type="text"
                value={bizNum1}
                onChange={(e) => setBizNum1(e.target.value.replace(/\D/g, '').slice(0, 3))}
                className="w-16 border border-kb-border px-3 py-2 text-caption text-center focus:outline-none"
              />
              <span className="text-kb-text-muted">-</span>
              <input
                type="text"
                value={bizNum2}
                onChange={(e) => setBizNum2(e.target.value.replace(/\D/g, '').slice(0, 2))}
                className="w-12 border border-kb-border px-3 py-2 text-caption text-center focus:outline-none"
              />
              <span className="text-kb-text-muted">-</span>
              <input
                type="text"
                value={bizNum3}
                onChange={(e) => setBizNum3(e.target.value.replace(/\D/g, '').slice(0, 5))}
                className="w-20 border border-kb-border px-3 py-2 text-caption text-center focus:outline-none"
              />
            </div>
          </div>

          {/* 주민등록번호 */}
          <div className="flex items-center px-6 py-4 gap-4">
            <label className="w-44 text-caption font-medium text-kb-text flex-shrink-0">주민등록번호</label>
            <div className="flex items-center gap-3 flex-wrap">
              <input
                type="text"
                value={rrnFront}
                onChange={(e) => setRrnFront(e.target.value.replace(/\D/g, '').slice(0, 6))}
                className="w-28 border border-kb-border px-3 py-2 text-caption text-center tracking-widest focus:outline-none"
                placeholder="생년월일"
              />
              <span className="text-kb-text-muted">-</span>
              {mouseInput ? (
                <MouseNumKeypad value={rrnBack} onChange={setRrnBack} maxLength={7} dotCount={7} />
              ) : (
                <input
                  type="password"
                  value={rrnBack}
                  onChange={e => setRrnBack(e.target.value.replace(/\D/g, '').slice(0, 7))}
                  maxLength={7}
                  className="w-28 border border-kb-border px-3 py-2 text-caption text-center tracking-widest focus:outline-none"
                />
              )}
              <label className="flex items-center gap-1.5 cursor-pointer">
                <CheckBox checked={mouseInput} onChange={() => { setMouseInput(!mouseInput); setRrnBack('') }} />
                <span className="text-caption text-kb-text-body">마우스로 입력</span>
              </label>
              <span className="text-caption text-kb-text-muted">ⓘ 개인사업자만 입력</span>
            </div>
          </div>
        </div>
        <p className="text-caption text-kb-text-muted mt-3">
          위의 전자금융거래기본약관 및 전자금융서비스이용약관의 내용에 동의하고 인터넷뱅킹 서비스를 이용하시겠습니까?
        </p>
      </section>

      {/* 버튼 */}
      <div className="flex justify-center gap-3 pt-2">
        <Link
          href="/biz/login"
          className="px-14 py-3 border border-kb-border text-body text-kb-text hover:bg-kb-beige-light transition-colors"
        >
          취소
        </Link>
        {stepError && <p className="w-full text-center text-[12px] text-kb-red mb-2">{stepError}</p>}
        <button onClick={handleNext}
          className="px-14 py-3 bg-kb-yellow text-body font-bold text-kb-text hover:brightness-95 transition-all">
          약관 동의/본인확인
        </button>
      </div>
    </div>
  )
}

function CheckBox({ checked, onChange }: { checked: boolean; onChange: () => void }) {
  return (
    <button
      type="button"
      onClick={onChange}
      className={`w-4 h-4 border flex-shrink-0 flex items-center justify-center transition-colors
        ${checked ? 'bg-kb-yellow border-kb-taupe' : 'bg-white border-kb-border'}`}
    >
      {checked && (
        <svg viewBox="0 0 12 10" fill="none" className="w-3 h-3">
          <path d="M1 5l3 3 7-7" stroke="#333" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" />
        </svg>
      )}
    </button>
  )
}
