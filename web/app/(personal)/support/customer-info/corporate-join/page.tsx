'use client'
import { KB_PRIMARY as GREEN, KB_MINT } from '@/lib/theme'
import { recordConsents } from '@/lib/consent-api'

import { useState } from 'react'
import Link from 'next/link'
import MobileAuthField from '@/components/MobileAuthField'
import { TAX_TYPES, registerCorporate, authErrorMessage } from '@/lib/customer-auth-api'

const STEPS = ['약관동의', '법인정보', '대표자확인', '가입완료']

export default function CorporateJoinPage() {
  const [step, setStep] = useState(0)

  // 약관
  const [agreed, setAgreed] = useState(false)

  // 법인정보
  const [corpName, setCorpName] = useState('')
  const [corpEnglishName, setCorpEnglishName] = useState('')
  const [corpRegNo, setCorpRegNo] = useState('') // 6-7
  const [bizRegNo, setBizRegNo] = useState('') // 3-2-5
  const [tradeName, setTradeName] = useState('')
  const [openingDate, setOpeningDate] = useState('') // YYYYMMDD
  const [ntsIndustryCode, setNtsIndustryCode] = useState('')
  const [ksicCode, setKsicCode] = useState('')
  const [bizItemCode, setBizItemCode] = useState('')
  const [taxTypeCode, setTaxTypeCode] = useState<string>(TAX_TYPES[0].code)

  // 대표자 계정 + 본인인증
  const [loginId, setLoginId] = useState('')
  const [password, setPassword] = useState('')
  const [passwordConfirm, setPasswordConfirm] = useState('')
  const [email, setEmail] = useState('')
  const [verifiedPhone, setVerifiedPhone] = useState('')

  const [submitError, setSubmitError] = useState('')
  const [loading, setLoading] = useState(false)
  const [result, setResult] = useState<{ customerId: number; loginId: string } | null>(null)

  async function handleStep0() {
    if (!agreed) {
      alert('필수 약관에 동의해주세요.')
      return
    }
    // 동의를 남기고 나서 다음 단계로 간다.
    try {
      await recordConsents({
        bizDivCd: 'CORP_JOIN',
        items: [{ termsNo: 'CORP-001', agreed: true }],
      })
    } catch {
      alert('약관 동의 기록에 실패했습니다. 잠시 후 다시 시도해 주세요.')
      return
    }
    setStep(1)
  }

  function handleStep1() {
    if (!corpName) return alert('법인명을 입력해주세요.')
    if (!/^[A-Za-z0-9 .,&()'\-]+$/.test(corpEnglishName)) return alert('영문 법인명을 영문으로 입력해주세요.')
    if (!/^\d{6}-\d{7}$/.test(corpRegNo)) return alert('법인등록번호를 형식(123456-1234567)에 맞게 입력해주세요.')
    if (!/^\d{3}-\d{2}-\d{5}$/.test(bizRegNo)) return alert('사업자등록번호를 형식(123-45-67890)에 맞게 입력해주세요.')
    if (!tradeName) return alert('상호명을 입력해주세요.')
    if (!/^\d{8}$/.test(openingDate) || isNaN(Date.parse(`${openingDate.slice(0, 4)}-${openingDate.slice(4, 6)}-${openingDate.slice(6, 8)}`)))
      return alert('개업일자를 정확히 입력해주세요. (YYYYMMDD)')
    if (!/^\d{6}$/.test(ntsIndustryCode)) return alert('국세청 업종코드 6자리를 입력해주세요.')
    if (!/^\d{5}$/.test(ksicCode)) return alert('표준산업분류(KSIC) 5자리 숫자를 입력해주세요.')
    if (!bizItemCode) return alert('업태/종목을 입력해주세요.')
    setStep(2)
  }

  async function handleSubmit() {
    if (!loginId || loginId.length < 4) return alert('아이디는 4자리 이상 입력해주세요.')
    if (password.length < 8) return alert('비밀번호는 8자리 이상 입력해주세요.')
    if (password !== passwordConfirm) return alert('비밀번호가 일치하지 않습니다.')
    if (!email) return alert('이메일을 입력해주세요.')
    if (!verifiedPhone) return alert('대표자 휴대폰 본인인증을 완료해주세요.')

    setSubmitError('')
    setLoading(true)
    try {
      const res = await registerCorporate({
        corpName,
        corpEnglishName,
        corpRegNo,
        bizRegNo,
        tradeName,
        openingDate,
        ntsIndustryCode,
        ksicCode,
        bizItemCode,
        taxTypeCode,
        loginId,
        password,
        email,
        phone: verifiedPhone,
      })
      setResult(res)
      setStep(3)
    } catch (err) {
      setSubmitError(authErrorMessage(err, '법인 회원가입에 실패했습니다.'))
    } finally {
      setLoading(false)
    }
  }

  return (
    <div className="min-h-screen bg-white">
      <div className="max-w-kb-container mx-auto px-6 py-8">
        {/* 브레드크럼 */}
        <div className="flex items-center gap-1 text-[12px] text-kb-text-muted mb-6">
          <Link href="/" className="hover:underline">홈</Link>
          <span>›</span>
          <span>고객센터</span>
          <span>›</span>
          <span>고객정보관리</span>
          <span>›</span>
          <span className="font-semibold text-kb-text">법인 신규가입</span>
        </div>

        <main className="w-full">
          <div className="flex items-center justify-between mb-5">
            <h1 className="text-[22px] font-bold text-kb-text">법인(기업) 신규가입</h1>
            <div className="flex items-center gap-1">
              {STEPS.map((s, i) => (
                <div key={s} className="flex items-center gap-1">
                  <div
                    className={`flex items-center gap-1.5 px-3 py-1 rounded-full text-[12px] font-bold
                      ${step === i ? 'text-white' : step > i ? 'text-white' : 'border border-kb-border text-kb-text-muted'}`}
                    style={step === i ? { backgroundColor: GREEN } : step > i ? { backgroundColor: KB_MINT } : {}}
                  >
                    <span>{i + 1}.</span>
                    <span>{s}</span>
                  </div>
                  {i < STEPS.length - 1 && <span className="text-kb-border text-[10px]">›</span>}
                </div>
              ))}
            </div>
          </div>

          {/* STEP 0: 약관동의 */}
          {step === 0 && (
            <div>
              <div className="border border-kb-border bg-kb-primary-bg px-5 py-4 mb-4 text-[13px] text-kb-text-body space-y-1.5">
                <p>· 법인·개인사업자 고객은 본 화면에서 인터넷뱅킹 온라인 신규가입이 가능합니다.</p>
                <p>· 가입에는 사업자등록증·법인등기부등본상의 정보와 대표자 명의 휴대폰 본인인증이 필요합니다.</p>
                <p>· 등록된 정보는 사업자 진위확인 및 컴플라이언스 심사에 이용됩니다.</p>
              </div>
              <label className="flex items-center gap-3 border border-kb-border px-5 py-4 cursor-pointer">
                <input type="checkbox" checked={agreed} onChange={(e) => setAgreed(e.target.checked)} className="w-4 h-4" />
                <span className="text-[14px] font-semibold text-kb-text">
                  <span className="mr-1" style={{ color: GREEN }}>[필수]</span>
                  법인 인터넷뱅킹 이용약관 및 개인정보 수집·이용에 동의합니다.
                </span>
              </label>
              <div className="flex justify-center gap-3 mt-6">
                <button onClick={handleStep0} className="px-14 py-3 text-[14px] font-bold text-white rounded-lg hover:opacity-85" style={{ backgroundColor: GREEN }}>
                  확인
                </button>
                <Link href="/support/customer-info/online-join" className="border border-kb-border px-14 py-3 text-[14px] text-kb-text-body hover:bg-kb-primary-bg">
                  취소
                </Link>
              </div>
            </div>
          )}

          {/* STEP 1: 법인정보 */}
          {step === 1 && (
            <div className="max-w-2xl">
              <FormRow label="법인명">
                <input value={corpName} onChange={(e) => setCorpName(e.target.value)} placeholder="(주)에이엑스풀뱅크" className="input w-full" />
              </FormRow>
              <FormRow label="영문 법인명">
                <input value={corpEnglishName} onChange={(e) => setCorpEnglishName(e.target.value)} placeholder="AXful Bank Co., Ltd." className="input w-full" />
              </FormRow>
              <FormRow label="법인등록번호">
                <input value={corpRegNo} onChange={(e) => setCorpRegNo(e.target.value)} placeholder="123456-1234567" className="input w-full" maxLength={14} />
              </FormRow>
              <FormRow label="사업자등록번호">
                <input value={bizRegNo} onChange={(e) => setBizRegNo(e.target.value)} placeholder="123-45-67890" className="input w-full" maxLength={12} />
              </FormRow>
              <FormRow label="상호명">
                <input value={tradeName} onChange={(e) => setTradeName(e.target.value)} placeholder="에이엑스풀뱅크" className="input w-full" />
              </FormRow>
              <FormRow label="개업일자">
                <input value={openingDate} onChange={(e) => setOpeningDate(e.target.value.replace(/\D/g, '').slice(0, 8))} placeholder="YYYYMMDD" className="input w-full" maxLength={8} />
              </FormRow>
              <FormRow label="국세청 업종코드">
                <input value={ntsIndustryCode} onChange={(e) => setNtsIndustryCode(e.target.value)} placeholder="940909" className="input w-full" />
              </FormRow>
              <FormRow label="표준산업분류(KSIC)">
                <input value={ksicCode} onChange={(e) => setKsicCode(e.target.value)} placeholder="64191" className="input w-full" />
              </FormRow>
              <FormRow label="업태/종목">
                <input value={bizItemCode} onChange={(e) => setBizItemCode(e.target.value)} placeholder="금융업" className="input w-full" />
              </FormRow>
              <FormRow label="과세유형">
                <select value={taxTypeCode} onChange={(e) => setTaxTypeCode(e.target.value)} className="input w-full">
                  {TAX_TYPES.map((t) => (
                    <option key={t.code} value={t.code}>{t.label}</option>
                  ))}
                </select>
              </FormRow>
              <div className="flex justify-center gap-3 mt-6">
                <button onClick={() => setStep(0)} className="border border-kb-border px-10 py-3 text-[14px] text-kb-text-body hover:bg-kb-primary-bg">이전</button>
                <button onClick={handleStep1} className="px-10 py-3 text-[14px] font-bold text-white rounded-lg hover:opacity-85" style={{ backgroundColor: GREEN }}>다음</button>
              </div>
            </div>
          )}

          {/* STEP 2: 대표자 계정 + 본인인증 */}
          {step === 2 && (
            <div className="max-w-2xl">
              <h2 className="text-[15px] font-bold text-kb-text mb-3">대표자 계정 정보</h2>
              <FormRow label="아이디">
                <input value={loginId} onChange={(e) => setLoginId(e.target.value)} placeholder="영문·숫자 4자리 이상" className="input w-full" autoComplete="username" />
              </FormRow>
              <FormRow label="비밀번호">
                <input type="password" value={password} onChange={(e) => setPassword(e.target.value)} placeholder="8자리 이상" className="input w-full" autoComplete="new-password" />
              </FormRow>
              <FormRow label="비밀번호 확인">
                <input type="password" value={passwordConfirm} onChange={(e) => setPasswordConfirm(e.target.value)} placeholder="비밀번호 재입력" className="input w-full" autoComplete="new-password" />
              </FormRow>
              <FormRow label="이메일">
                <input type="email" value={email} onChange={(e) => setEmail(e.target.value)} placeholder="corp@example.com" className="input w-full" autoComplete="email" />
              </FormRow>

              <h2 className="text-[15px] font-bold text-kb-text mt-6 mb-3">대표자 휴대폰 본인인증</h2>
              <div className="border border-kb-border px-5 py-5">
                <MobileAuthField purpose="IDENTITY_VERIFY" onVerified={setVerifiedPhone} />
              </div>

              {submitError && <p className="text-[13px] text-red-500 mt-4">{submitError}</p>}

              <div className="flex justify-center gap-3 mt-6">
                <button onClick={() => setStep(1)} className="border border-kb-border px-10 py-3 text-[14px] text-kb-text-body hover:bg-kb-primary-bg">이전</button>
                <button onClick={handleSubmit} disabled={loading} className="px-10 py-3 text-[14px] font-bold text-white rounded-lg hover:opacity-85 disabled:opacity-50" style={{ backgroundColor: GREEN }}>
                  {loading ? '처리 중...' : '가입하기'}
                </button>
              </div>
            </div>
          )}

          {/* STEP 3: 완료 */}
          {step === 3 && result && (
            <div className="max-w-xl mx-auto text-center py-12">
              <div className="w-16 h-16 rounded-full flex items-center justify-center mx-auto mb-5" style={{ backgroundColor: KB_MINT }}>
                <svg viewBox="0 0 24 20" fill="none" className="w-8 h-7"><polyline points="2,11 9,18 22,2" stroke="white" strokeWidth="3" strokeLinecap="round" strokeLinejoin="round" /></svg>
              </div>
              <h2 className="text-[20px] font-bold text-kb-text mb-2">법인 회원가입이 완료되었습니다.</h2>
              <p className="text-[14px] text-kb-text-body mb-1">아이디 <b>{result.loginId}</b> 로 로그인하실 수 있습니다.</p>
              <p className="text-[13px] text-kb-text-muted mb-8">고객번호: {result.customerId}</p>
              <div className="flex justify-center gap-3">
                <Link href="/login" className="px-10 py-3 text-[14px] font-bold text-white rounded-lg hover:opacity-85" style={{ backgroundColor: GREEN }}>로그인하기</Link>
                <Link href="/" className="border border-kb-border px-10 py-3 text-[14px] text-kb-text-body hover:bg-kb-primary-bg">홈으로</Link>
              </div>
            </div>
          )}
        </main>
      </div>
    </div>
  )
}

function FormRow({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <div className="flex items-center gap-3 mb-2.5">
      <label className="w-36 text-[13px] text-kb-text-body text-right flex-shrink-0">{label}</label>
      <div className="flex-1">{children}</div>
    </div>
  )
}
