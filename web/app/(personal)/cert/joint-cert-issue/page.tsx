'use client'
import { KB_PRIMARY } from '@/lib/theme'

import Link from 'next/link'
import { useState } from 'react'
import { api } from '@/lib/api'
import { CERT_TERMS, CertTermsModal } from '@/components/cert/CertTermsModal'
import { recordConsents } from '@/lib/consent-api'

const STEPS = ['약관동의 및 사용자 본인확인', '발급 완료']

export default function JointCertIssuePage() {
  const [termModalIndex, setTermModalIndex] = useState<number | null>(null)
  const [checked, setChecked] = useState<boolean[]>(CERT_TERMS.map(() => false))
  const allChecked = checked.every(Boolean)
  const [userId, setUserId] = useState('')
  const [rrn1, setRrn1] = useState('')
  const [rrn2, setRrn2] = useState('')
  const [password, setPassword] = useState('')
  const [certPin, setCertPin] = useState('')
  const [certPinConfirm, setCertPinConfirm] = useState('')
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState('')
  const [issuedCert, setIssuedCert] = useState<{ serialNumber: string; expiryDate: string } | null>(null)

  function toggleAll() {
    setChecked(CERT_TERMS.map(() => !allChecked))
  }

  function toggleOne(i: number) {
    setChecked(prev => prev.map((v, idx) => idx === i ? !v : v))
  }

  function agreeOne(i: number) {
    setChecked(prev => prev.map((v, idx) => idx === i ? true : v))
  }

  function agreeAll() {
    setChecked(CERT_TERMS.map(() => true))
    setTermModalIndex(null)
  }

  async function handleIssue() {
    if (!checked.every(Boolean)) { setError('필수 약관에 모두 동의해 주세요.'); return }
    if (!userId) { setError('아이디를 입력해 주세요.'); return }
    if (rrn1.length !== 6 || rrn2.length !== 7) { setError('주민등록번호를 올바르게 입력해 주세요.'); return }
    if (!password) { setError('비밀번호를 입력해 주세요.'); return }
    if (certPin.length < 8) { setError('인증서 암호는 8자 이상 입력해 주세요.'); return }
    if (certPin !== certPinConfirm) { setError('인증서 암호가 일치하지 않습니다.'); return }
    setError(''); setLoading(true)
    try {
      // 발급 전에 동의를 남긴다. 기록에 실패했는데 발급이 되면 "동의 없이 발급된 건"
      // 이 남고, 화면에서는 분명히 받았는데 증명할 방법이 없는 상태가 된다.
      await recordConsents({
        bizDivCd: 'CERT',
        items: CERT_TERMS.map((t, i) => ({ termsNo: t.termsNo, agreed: checked[i] })),
      })
      const { data: res } = await api.post('/api/v1/auth/cert/issue', {
        loginId: userId,
        password,
        certType: 'CERT_COMMON',
        certPin,
      })
      const cert = res.data
      // 발급된 인증서를 localStorage에 저장해 로그인 모달에서 사용
      localStorage.setItem('issuedJointCert', JSON.stringify({
        serialNumber: cert.serialNumber,
        certType: cert.certType,
        user: userId,
        expiry: `${cert.expiryDate.slice(0,4)}.${cert.expiryDate.slice(4,6)}.${cert.expiryDate.slice(6,8)}`,
        issuer: cert.issuerName,
      }))
      setIssuedCert(cert)
    } catch (err: unknown) {
      const e = err as { response?: { data?: { message?: string } } }
      setError(e.response?.data?.message ?? '인증서 발급 중 오류가 발생했습니다.')
    } finally {
      setLoading(false)
    }
  }

  return (
    <div className="max-w-kb-container mx-auto px-6 py-8">
      <main className="space-y-6">

        {/* 브레드크럼 */}
        <div className="flex items-center gap-1 text-[12px] text-kb-text-muted">
          <Link href="/cert" className="hover:text-kb-text">인증센터(개인)</Link>
          <span>&gt;</span>
          <span>공동인증서(구 공인인증서)</span>
          <span>&gt;</span>
          <span>공동인증서 발급/재발급</span>
          <span>&gt;</span>
          <span className="text-kb-text font-medium">개인용 인증서 발급</span>
        </div>

        {/* 페이지 제목 */}
        <h2 className="text-[22px] font-bold text-kb-text border-b-2 border-kb-primary pb-3">
          개인용 인증서 발급
        </h2>

        {/* 단계 표시 */}
        <div className="flex items-center gap-0">
          {STEPS.map((label, i) => (
            <div key={i} className="flex items-center">
              <div className={`flex items-center justify-center rounded-full text-[11px] font-bold border-2 px-3 h-7
                ${i === 0 && !issuedCert ? 'bg-kb-primary border-kb-primary text-white'
                : i === 1 && issuedCert ? 'bg-kb-primary border-kb-primary text-white'
                : 'border-gray-300 text-gray-400'}`}
              >
                {label}
              </div>
              {i < STEPS.length - 1 && <div className="w-6 h-px bg-gray-300 mx-1" />}
            </div>
          ))}
        </div>

        {/* 약관동의 섹션 */}
        <section>
          <h3 className="text-[15px] font-bold text-kb-text mb-3">약관동의 및 사용자 본인 확인</h3>

          <div className="border border-kb-border">
            {/* 전체약관보기 */}
            <div className="flex items-center justify-between px-5 py-4 border-b border-kb-border bg-kb-primary-bg">
              <button className="flex items-center gap-3 flex-1 text-left" onClick={toggleAll}>
                <div className={`w-5 h-5 rounded border-2 flex items-center justify-center flex-shrink-0 ${allChecked ? 'border-kb-primary' : 'border-kb-border'}`}
                  style={allChecked ? { backgroundColor: KB_PRIMARY } : {}}>
                  {allChecked && <svg viewBox="0 0 12 10" fill="none" className="w-3 h-2.5"><polyline points="1,5 4.5,8.5 11,1" stroke="white" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"/></svg>}
                </div>
                <span className="text-[14px] font-bold text-kb-text">전체약관보기</span>
              </button>
              <button onClick={() => setTermModalIndex(0)}
                className="border border-kb-border px-3 py-1 text-[13px] font-semibold text-kb-text-body hover:bg-kb-primary-bg flex-shrink-0">
                약관보기 ›
              </button>
            </div>

            {/* 개별 약관 */}
            {CERT_TERMS.map((term, i) => (
              <div key={i} className="flex items-center justify-between px-5 py-4 border-b border-kb-border last:border-b-0">
                <div className="flex items-center gap-3 min-w-0">
                  <button onClick={() => toggleOne(i)}
                    className={`w-5 h-5 rounded border-2 flex items-center justify-center flex-shrink-0 transition-colors ${checked[i] ? 'border-kb-primary' : 'border-kb-border'}`}
                    style={checked[i] ? { backgroundColor: KB_PRIMARY } : {}}>
                    {checked[i] && <svg viewBox="0 0 12 10" fill="none" className="w-3 h-2.5"><polyline points="1,5 4.5,8.5 11,1" stroke="white" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"/></svg>}
                  </button>
                  <span className="text-[13px] text-kb-text-muted whitespace-pre-line">
                    <span className="font-semibold mr-1" style={{ color: KB_PRIMARY }}>[필수]</span>
                    {term.label}
                  </span>
                </div>
                <button onClick={() => setTermModalIndex(i)}
                  className="border border-kb-border px-3 py-1 text-[13px] font-semibold text-kb-text-body hover:bg-kb-primary-bg flex-shrink-0 ml-3">
                  약관보기 ›
                </button>
              </div>
            ))}
          </div>
        </section>

        {/* 약관 모달 */}
        {termModalIndex !== null && (
          <CertTermsModal
            termIndex={termModalIndex}
            onClose={() => setTermModalIndex(null)}
            onAgreeOne={(i) => { agreeOne(i); setTermModalIndex(i) }}
            onAgreeAll={agreeAll}
          />
        )}

        {/* 사용자 본인확인 섹션 */}
        <section className="border border-kb-border p-6 space-y-4">
          <h3 className="text-[15px] font-bold text-kb-text border-b border-kb-border pb-3">사용자 본인확인</h3>

          {/* 사용자 ID */}
          <div className="flex items-center gap-3">
            <label className="w-24 text-[13px] text-kb-text-body text-right flex-shrink-0">사용자 ID</label>
            <input
              type="text"
              value={userId}
              onChange={(e) => setUserId(e.target.value)}
              className="border border-kb-border px-2 py-1.5 text-[13px] w-40 outline-none focus:border-blue-400"
            />
            <Link href="/support/customer-info/id-password" className="text-[12px] text-kb-primary hover:underline">
              ID를 모르시는 경우↗
            </Link>
          </div>

          {/* 주민등록번호 */}
          <div className="flex items-center gap-3">
            <label className="w-24 text-[13px] text-kb-text-body text-right flex-shrink-0">주민등록번호</label>
            <div className="flex items-center gap-1">
              <input
                type="text"
                maxLength={6}
                value={rrn1}
                onChange={(e) => setRrn1(e.target.value.replace(/\D/g, ''))}
                className="border border-kb-border px-2 py-1.5 text-[13px] w-24 outline-none focus:border-blue-400"
              />
              <span className="text-gray-500">-</span>
              <input
                type="password"
                maxLength={7}
                value={rrn2}
                onChange={(e) => setRrn2(e.target.value.replace(/\D/g, ''))}
                className="border border-kb-border px-2 py-1.5 text-[13px] w-24 outline-none focus:border-blue-400"
              />
            </div>
          </div>
          <p className="text-[11px] text-kb-text-muted pl-[108px]">
            ① 숫자만 입력하실 수 있으며, 붙여넣기는 지원되지 않습니다.
          </p>

          <p className="text-[12px] text-kb-text-body pt-2">
            위의 전자금융거래기본약관과 전자금융서비스이용약관의 내용에 동의하고 인터넷뱅킹 서비스를 이용하시겠습니까?
          </p>

          {/* 비밀번호 */}
          <div className="flex items-center gap-3">
            <label className="w-24 text-[13px] text-kb-text-body text-right flex-shrink-0">비밀번호</label>
            <input
              type="password"
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              placeholder="로그인 비밀번호"
              className="border border-kb-border px-2 py-1.5 text-[13px] w-40 outline-none focus:border-kb-primary"
            />
          </div>

          {/* 인증서 암호 */}
          <div className="flex items-center gap-3">
            <label className="w-24 text-[13px] text-kb-text-body text-right flex-shrink-0">인증서 암호</label>
            <input
              type="password"
              value={certPin}
              onChange={(e) => setCertPin(e.target.value)}
              placeholder="8자 이상"
              className="border border-kb-border px-2 py-1.5 text-[13px] w-40 outline-none focus:border-kb-primary"
            />
          </div>
          <div className="flex items-center gap-3">
            <label className="w-24 text-[13px] text-kb-text-body text-right flex-shrink-0">암호 확인</label>
            <input
              type="password"
              value={certPinConfirm}
              onChange={(e) => setCertPinConfirm(e.target.value)}
              placeholder="인증서 암호 재입력"
              className="border border-kb-border px-2 py-1.5 text-[13px] w-40 outline-none focus:border-kb-primary"
            />
          </div>
          <p className="text-[11px] text-kb-text-muted pl-[108px]">
            인증서 암호는 로그인 비밀번호와 다르게 설정하시고 주기적으로 변경하시기 바랍니다.
          </p>

          {error && <p className="text-[12px] text-red-500 pl-[108px]">{error}</p>}

          {/* 발급 완료 화면 */}
          {issuedCert && (
            <div className="border-2 border-kb-primary bg-kb-primary-bg p-5 space-y-3">
              <div className="flex items-center gap-2">
                <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="#0D5C47" strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round">
                  <circle cx="12" cy="12" r="10"/><path d="M8 12l3 3 5-5"/>
                </svg>
                <p className="text-[14px] font-bold text-kb-primary">공동인증서 발급이 완료되었습니다</p>
              </div>
              <div className="bg-white border border-[#C8E8DF] p-4 space-y-1.5 text-[12px]">
                <div className="flex gap-3">
                  <span className="w-20 text-kb-text-muted flex-shrink-0">일련번호</span>
                  <span className="text-kb-text font-medium break-all">{issuedCert.serialNumber}</span>
                </div>
                <div className="flex gap-3">
                  <span className="w-20 text-kb-text-muted flex-shrink-0">인증서 유형</span>
                  <span className="text-kb-text">공동인증서(구 공인인증서)</span>
                </div>
                <div className="flex gap-3">
                  <span className="w-20 text-kb-text-muted flex-shrink-0">유효기간</span>
                  <span className="text-kb-text">
                    {issuedCert.expiryDate.slice(0,4)}.{issuedCert.expiryDate.slice(4,6)}.{issuedCert.expiryDate.slice(6,8)} 까지 (3년)
                  </span>
                </div>
              </div>
              <p className="text-[11px] text-kb-text-muted">
                발급된 인증서는 로그인 화면의 &lsquo;공동·금융인증서&rsquo; 탭에서 사용할 수 있습니다.
              </p>
            </div>
          )}

          {/* 버튼 섹션 */}
          <div className="flex gap-2 pt-2">
            {issuedCert ? (
              <>
                <Link
                  href="/login"
                  className="px-8 py-2.5 text-[13px] font-bold text-white hover:opacity-90"
                  style={{ backgroundColor: KB_PRIMARY }}
                >
                  로그인 화면으로
                </Link>
                <Link
                  href="/cert/joint-cert-management"
                  className="px-8 py-2.5 border border-kb-border text-[13px] text-kb-text-body hover:bg-kb-beige-light flex items-center"
                >
                  인증서 관리
                </Link>
              </>
            ) : (
              <>
                <button
                  onClick={handleIssue}
                  disabled={loading}
                  className="px-8 py-2.5 text-[13px] font-bold text-white hover:opacity-90 disabled:opacity-50"
                  style={{ backgroundColor: KB_PRIMARY }}
                >
                  {loading ? '발급 중...' : '약관 동의/본인확인'}
                </button>
                <Link href="/cert" className="px-8 py-2.5 border border-kb-border text-[13px] text-kb-text-body hover:bg-kb-beige-light flex items-center">
                  취소
                </Link>
              </>
            )}
          </div>
        </section>


    </main>
    </div>
  )
}
