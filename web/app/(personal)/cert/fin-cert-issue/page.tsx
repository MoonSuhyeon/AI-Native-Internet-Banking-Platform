'use client'
import { KB_PRIMARY,KB_PRIMARY_BG } from '@/lib/theme'

import Link from 'next/link'
import { useState } from 'react'
import { api } from '@/lib/api'
import { CERT_TERMS, CertTermsModal } from '@/components/cert/CertTermsModal'
import { recordConsents } from '@/lib/consent-api'

// ── 공통 UI ───────────────────────────────────────────────────

function NoticeBox({ items }: { items: string[] }) {
  return (
    <div className="bg-kb-primary-bg border border-kb-border rounded-xl px-5 py-4 space-y-2">
      {items.map((n, i) => (
        <div key={i} className="flex items-start gap-2 text-[13px] text-kb-text-body">
          <span className="flex-shrink-0 mt-0.5">·</span>
          {n}
        </div>
      ))}
    </div>
  )
}

function TableRow({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <tr className="border-b border-kb-border last:border-b-0">
      <td className="px-5 py-3.5 font-semibold text-[13px] text-kb-text w-44 whitespace-nowrap align-middle"
        style={{ backgroundColor: KB_PRIMARY_BG }}>
        {label}
      </td>
      <td className="border-l border-kb-border px-5 py-3 align-middle">{children}</td>
    </tr>
  )
}

function PwInput({ value, onChange, placeholder }: {
  value: string; onChange: (v: string) => void; placeholder?: string
}) {
  const [show, setShow] = useState(false)
  return (
    <div className="relative inline-flex items-center">
      <input
        type={show ? 'text' : 'password'}
        value={value}
        onChange={e => onChange(e.target.value)}
        placeholder={placeholder}
        className="border border-kb-border px-3 py-1.5 pr-9 w-64 outline-none text-[13px] focus:border-kb-primary transition-colors"
      />
      <button type="button" onClick={() => setShow(v => !v)}
        className="absolute right-2.5 text-kb-text-muted hover:text-kb-text" tabIndex={-1}>
        {show
          ? <svg viewBox="0 0 24 24" fill="none" className="w-4 h-4" stroke="currentColor" strokeWidth="2" strokeLinecap="round"><path d="M17.94 17.94A10.07 10.07 0 0112 20c-7 0-11-8-11-8a18.45 18.45 0 015.06-5.94"/><path d="M9.9 4.24A9.12 9.12 0 0112 4c7 0 11 8 11 8a18.5 18.5 0 01-2.16 3.19"/><line x1="1" y1="1" x2="23" y2="23"/></svg>
          : <svg viewBox="0 0 24 24" fill="none" className="w-4 h-4" stroke="currentColor" strokeWidth="2" strokeLinecap="round"><path d="M1 12s4-8 11-8 11 8 11 8-4 8-11 8-11-8-11-8z"/><circle cx="12" cy="12" r="3"/></svg>
        }
      </button>
    </div>
  )
}

// ─────────────────────────────────────────────────────────────

const NOTICES = [
  '금융인증서는 금융결제원의 클라우드에 저장되어 PC·스마트폰 등 기기에 관계없이 이용 가능합니다.',
  '유효기간은 3년이며 만료 전 갱신을 통해 계속 사용 가능합니다.',
  '발급 수수료는 무료입니다.',
  '금융인증서 PIN은 숫자 6자리로 설정해 주세요.',
]

export default function FinCertIssuePage() {
  const [termModalIndex, setTermModalIndex] = useState<number | null>(null)
  const [checked, setChecked]   = useState<boolean[]>(CERT_TERMS.map(() => false))
  const allChecked               = checked.every(Boolean)

  const [userId, setUserId]       = useState('')
  const [rrn1, setRrn1]           = useState('')
  const [rrn2, setRrn2]           = useState('')
  const [password, setPassword]   = useState('')
  const [certPin, setCertPin]     = useState('')
  const [certPinConfirm, setCertPinConfirm] = useState('')

  const [loading, setLoading]     = useState(false)
  const [error, setError]         = useState('')
  const [issuedCert, setIssuedCert] = useState<{ serialNumber: string; expiryDate: string } | null>(null)

  function toggleOne(i: number) {
    setChecked(prev => prev.map((v, idx) => idx === i ? !v : v))
  }
  function toggleAll() {
    setChecked(CERT_TERMS.map(() => !allChecked))
  }
  function agreeOne(i: number) {
    setChecked(prev => prev.map((v, idx) => idx === i ? true : v))
  }
  function agreeAll() {
    setChecked(CERT_TERMS.map(() => true))
    setTermModalIndex(null)
  }

  async function handleIssue() {
    if (!allChecked)                    { setError('필수 약관에 모두 동의해 주세요.'); return }
    if (!userId)                        { setError('아이디를 입력해 주세요.'); return }
    if (rrn1.length !== 6 || rrn2.length !== 7) { setError('주민등록번호를 올바르게 입력해 주세요.'); return }
    if (!password)                      { setError('비밀번호를 입력해 주세요.'); return }
    if (!/^\d{6}$/.test(certPin))       { setError('금융인증서 PIN은 숫자 6자리로 입력해 주세요.'); return }
    if (certPin !== certPinConfirm)     { setError('금융인증서 PIN이 일치하지 않습니다.'); return }
    setError(''); setLoading(true)
    try {
      // 발급 전에 동의를 남긴다. 기록에 실패했는데 발급이 되면 "동의 없이 발급된 건"
      // 이 남고, 화면에서는 분명히 받았는데 증명할 방법이 없는 상태가 된다.
      await recordConsents({
        bizDivCd: 'CERT',
        items: CERT_TERMS.map((t, i) => ({ termsNo: t.termsNo, agreed: checked[i] })),
      })
      const { data: res } = await api.post('/api/v1/auth/cert/issue', {
        loginId: userId, password, certType: 'CERT_FIN', certPin,
      })
      const cert = res.data
      localStorage.setItem('issuedFinCert', JSON.stringify({
        serialNumber: cert.serialNumber, certType: cert.certType, user: userId,
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

      {/* 브레드크럼 */}
      <div className="flex items-center gap-1 text-[12px] text-kb-text-muted mb-6">
        <Link href="/" className="hover:underline">홈</Link>
        <span>›</span>
        <Link href="/cert" className="hover:underline">인증센터(개인)</Link>
        <span>›</span>
        <span>금융인증서</span>
        <span>›</span>
        <span className="font-semibold text-kb-text">인증서 발급/재발급</span>
      </div>

      <h1 className="text-[24px] font-bold text-kb-text mb-8">금융인증서 발급/재발급</h1>

      <div className="space-y-6">

        {/* 서비스 안내 */}
        <NoticeBox items={NOTICES} />

        {/* 약관 */}
        <section className="space-y-3">
          <h2 className="text-[15px] font-bold text-kb-text">약관 및 서비스설명서</h2>

          <div className="border border-kb-border rounded-xl overflow-hidden">
            {/* 전체약관 */}
            <div className="flex items-center justify-between px-5 py-4 border-b border-kb-border bg-kb-primary-bg">
              <button className="flex items-center gap-3 flex-1 text-left" onClick={toggleAll}>
                <div className={`w-5 h-5 rounded border-2 flex items-center justify-center flex-shrink-0 ${allChecked ? 'border-kb-primary' : 'border-kb-border'}`}
                  style={allChecked ? { backgroundColor: KB_PRIMARY } : {}}>
                  {allChecked && <svg viewBox="0 0 12 10" fill="none" className="w-3 h-2.5"><polyline points="1,5 4.5,8.5 11,1" stroke="white" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"/></svg>}
                </div>
                <span className="text-[14px] font-bold text-kb-text">전체약관보기</span>
              </button>
              <button onClick={() => setTermModalIndex(0)}
                className="border border-kb-border px-3 py-1 text-[13px] font-semibold text-kb-text-body hover:bg-white flex-shrink-0">
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

        {/* 사용자 확인 */}
        <section className="space-y-3">
          <h2 className="text-[15px] font-bold text-kb-text">사용자 확인</h2>
          <div className="border border-kb-border rounded-xl overflow-hidden">
            <table className="w-full text-[13px]">
              <tbody>
                <TableRow label="사용자 ID">
                  <div className="flex items-center gap-4">
                    <input type="text" value={userId} onChange={e => setUserId(e.target.value)}
                      placeholder="아이디 입력"
                      className="border border-kb-border px-3 py-1.5 w-64 outline-none text-[13px] focus:border-kb-primary" />
                    <Link href="/support/customer-info/id-password"
                      className="text-[13px] text-kb-primary hover:underline whitespace-nowrap">
                      ID를 모르시는 경우↗
                    </Link>
                  </div>
                </TableRow>
                <TableRow label="주민등록번호">
                  <div className="flex items-center gap-2">
                    <input type="text" value={rrn1}
                      onChange={e => setRrn1(e.target.value.replace(/\D/g, '').slice(0, 6))}
                      maxLength={6} placeholder="생년월일"
                      className="border border-kb-border px-3 py-1.5 w-28 text-center tracking-widest outline-none text-[13px] focus:border-kb-primary" />
                    <span className="text-kb-text-muted">-</span>
                    <input type="password" value={rrn2}
                      onChange={e => setRrn2(e.target.value.replace(/\D/g, '').slice(0, 7))}
                      maxLength={7}
                      className="border border-kb-border px-3 py-1.5 w-28 text-center tracking-widest outline-none text-[13px] focus:border-kb-primary" />
                  </div>
                </TableRow>
                <TableRow label="비밀번호">
                  <PwInput value={password} onChange={setPassword} placeholder="로그인 비밀번호" />
                </TableRow>
              </tbody>
            </table>
          </div>
        </section>

        {/* 인증서 암호 설정 */}
        <section className="space-y-3">
          <h2 className="text-[15px] font-bold text-kb-text">금융인증서 PIN 설정</h2>
          <div className="border border-kb-border rounded-xl overflow-hidden">
            <table className="w-full text-[13px]">
              <tbody>
                <TableRow label="금융인증서 PIN">
                  <div className="space-y-1">
                    <PwInput value={certPin} onChange={v => setCertPin(v.replace(/\D/g, '').slice(0, 6))} placeholder="숫자 6자리" />
                    <p className="text-[11px] text-kb-text-muted">숫자 6자리를 입력하세요.</p>
                  </div>
                </TableRow>
                <TableRow label="PIN 확인">
                  <div className="space-y-1">
                    <PwInput value={certPinConfirm} onChange={v => setCertPinConfirm(v.replace(/\D/g, '').slice(0, 6))} placeholder="PIN 재입력" />
                    {certPinConfirm && certPin !== certPinConfirm && (
                      <p className="text-[12px] text-red-500">금융인증서 PIN이 일치하지 않습니다.</p>
                    )}
                    {certPinConfirm && certPin === certPinConfirm && certPin.length === 6 && (
                      <p className="text-[12px] font-semibold" style={{ color: KB_PRIMARY }}>✓ 일치합니다.</p>
                    )}
                  </div>
                </TableRow>
              </tbody>
            </table>
          </div>
        </section>

        {error && <p className="text-[13px] text-red-500">{error}</p>}

        {/* 발급 완료 */}
        {issuedCert && (
          <div className="border border-kb-border bg-kb-primary-bg px-6 py-8 rounded-xl flex items-center gap-6">
            <div className="w-16 h-16 rounded-full flex items-center justify-center flex-shrink-0"
              style={{ backgroundColor: KB_PRIMARY }}>
              <svg viewBox="0 0 24 24" fill="none" className="w-8 h-8" stroke="white" strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round">
                <polyline points="20,6 9,17 4,12"/>
              </svg>
            </div>
            <div className="space-y-1.5">
              <p className="text-[16px] font-bold" style={{ color: KB_PRIMARY }}>금융인증서 발급이 완료되었습니다.</p>
              <p className="text-[13px] text-kb-text-muted">일련번호: <span className="font-medium text-kb-text">{issuedCert.serialNumber}</span></p>
              <p className="text-[13px] text-kb-text-muted">
                유효기간: {issuedCert.expiryDate.slice(0,4)}.{issuedCert.expiryDate.slice(4,6)}.{issuedCert.expiryDate.slice(6,8)} 까지 (3년)
              </p>
            </div>
          </div>
        )}

        {/* 버튼 */}
        <div className="flex justify-center gap-3 pt-2">
          {issuedCert ? (
            <>
              <Link href="/login"
                className="px-12 py-2.5 text-[14px] font-bold text-white rounded-lg hover:opacity-85 transition-opacity"
                style={{ backgroundColor: KB_PRIMARY }}>
                로그인 화면으로
              </Link>
              <Link href="/cert/cert-management"
                className="border border-kb-border px-12 py-2.5 text-[14px] text-kb-text-body hover:bg-kb-primary-bg transition-colors">
                인증서 관리
              </Link>
            </>
          ) : (
            <>
              <Link href="/cert"
                className="border border-kb-border px-12 py-2.5 text-[14px] text-kb-text-body hover:bg-kb-primary-bg transition-colors">
                취소
              </Link>
              <button onClick={handleIssue} disabled={loading}
                className="px-12 py-2.5 text-[14px] font-bold text-white rounded-lg hover:opacity-85 disabled:opacity-50 transition-opacity"
                style={{ backgroundColor: KB_PRIMARY }}>
                {loading ? '발급 중...' : '확인'}
              </button>
            </>
          )}
        </div>

      </div>

      {termModalIndex !== null && (
        <CertTermsModal
          termIndex={termModalIndex}
          onClose={() => setTermModalIndex(null)}
          onAgreeOne={(i) => { agreeOne(i); setTermModalIndex(i) }}
          onAgreeAll={agreeAll}
        />
      )}
    </div>
  )
}
