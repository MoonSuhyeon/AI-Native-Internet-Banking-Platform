'use client'
import { KB_MINT,KB_PRIMARY,KB_PRIMARY_BG } from '@/lib/theme'

import Link from 'next/link'
import { useState, useEffect, useCallback } from 'react'
import { api } from '@/lib/api'

// ── 타입 ──────────────────────────────────────────────────────

interface CertSummary {
  serialNumber: string
  certType: string
  certTypeName: string
  issuerName: string
  issuedDate: string
  expiryDate: string
  status: string
  statusName: string
}

interface CertDetail extends CertSummary {
  subjectDn: string
  issuerDn: string
  purposeCode: string
  hasPinSet: boolean
}

type ModalType = 'view' | 'revoke' | 'pin' | 'import' | 'export' | 'copy' | null

// ── 유틸 ─────────────────────────────────────────────────────

function fmtDate(d: string) {
  if (!d || d.length !== 8) return d
  return `${d.slice(0, 4)}.${d.slice(4, 6)}.${d.slice(6, 8)}`
}

function getCustomerId(): string {
  if (typeof window === 'undefined') return ''
  return localStorage.getItem('customerId') ?? ''
}

function custHeaders() {
  return { 'X-Customer-Id': getCustomerId() }
}

function statusColor(status: string) {
  if (status === 'ACTIVE')   return 'text-kb-primary bg-[#E8F5F0]'
  if (status === 'REVOKED')  return 'text-red-600 bg-red-50'
  if (status === 'EXPIRED')  return 'text-gray-500 bg-gray-100'
  return 'text-yellow-600 bg-yellow-50'
}

// ── 공통 모달 래퍼 ────────────────────────────────────────────

function Modal({ title, onClose, children }: {
  title: string; onClose: () => void; children: React.ReactNode
}) {
  return (
    <div className="fixed inset-0 bg-black/50 flex items-center justify-center z-50 p-4">
      <div className="bg-white w-full max-w-lg rounded-xl shadow-2xl overflow-hidden">
        <div className="flex items-center justify-between px-6 py-4 border-b border-kb-border"
          style={{ backgroundColor: KB_PRIMARY }}>
          <span className="text-[15px] font-bold text-white">{title}</span>
          <button onClick={onClose} className="text-white/70 hover:text-white text-xl leading-none">✕</button>
        </div>
        <div className="px-6 py-5">{children}</div>
      </div>
    </div>
  )
}

function PwInput({ value, onChange, placeholder }: {
  value: string; onChange: (v: string) => void; placeholder?: string
}) {
  const [show, setShow] = useState(false)
  return (
    <div className="relative">
      <input type={show ? 'text' : 'password'} value={value} onChange={e => onChange(e.target.value)}
        placeholder={placeholder}
        className="w-full border border-kb-border px-3 py-2 pr-9 text-[13px] outline-none focus:border-kb-primary rounded" />
      <button type="button" onClick={() => setShow(v => !v)}
        className="absolute right-2.5 top-1/2 -translate-y-1/2 text-kb-text-muted hover:text-kb-text" tabIndex={-1}>
        {show
          ? <svg viewBox="0 0 24 24" fill="none" className="w-4 h-4" stroke="currentColor" strokeWidth="2" strokeLinecap="round"><path d="M17.94 17.94A10.07 10.07 0 0112 20c-7 0-11-8-11-8a18.45 18.45 0 015.06-5.94"/><path d="M9.9 4.24A9.12 9.12 0 0112 4c7 0 11 8 11 8a18.5 18.5 0 01-2.16 3.19"/><line x1="1" y1="1" x2="23" y2="23"/></svg>
          : <svg viewBox="0 0 24 24" fill="none" className="w-4 h-4" stroke="currentColor" strokeWidth="2" strokeLinecap="round"><path d="M1 12s4-8 11-8 11 8 11 8-4 8-11 8-11-8-11-8z"/><circle cx="12" cy="12" r="3"/></svg>
        }
      </button>
    </div>
  )
}

// ── 페이지 ────────────────────────────────────────────────────

export default function CertManagementPage() {
  const [certs, setCerts]             = useState<CertSummary[]>([])
  const [loading, setLoading]         = useState(true)
  const [selected, setSelected]       = useState<string>('')
  const [modal, setModal]             = useState<ModalType>(null)

  // 인증서 보기/검증
  const [detail, setDetail]           = useState<CertDetail | null>(null)
  const [detailLoading, setDetailLoading] = useState(false)

  // 삭제
  const [revokeLoading, setRevokeLoading] = useState(false)
  const [revokeError, setRevokeError]     = useState('')
  const [revokeDone, setRevokeDone]       = useState(false)

  // 암호변경
  const [currentPin, setCurrentPin]   = useState('')
  const [newPin, setNewPin]           = useState('')
  const [newPinConfirm, setNewPinConfirm] = useState('')
  const [pinLoading, setPinLoading]   = useState(false)
  const [pinError, setPinError]       = useState('')
  const [pinDone, setPinDone]         = useState(false)

  // 모의 처리 상태
  const [mockDone, setMockDone]       = useState(false)

  const loadCerts = useCallback(async () => {
    setLoading(true)
    try {
      const { data } = await api.get('/api/v1/cert/manage', { headers: custHeaders() })
      const list: CertSummary[] = data.data ?? []
      setCerts(list)
      if (list.length > 0) setSelected(list[0].serialNumber)
    } catch {
      setCerts([])
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => { loadCerts() }, [loadCerts])

  function openModal(type: ModalType) {
    setModal(type)
    setDetail(null); setDetailLoading(false)
    setRevokeError(''); setRevokeDone(false); setRevokeLoading(false)
    setCurrentPin(''); setNewPin(''); setNewPinConfirm('')
    setPinError(''); setPinDone(false); setPinLoading(false)
    setMockDone(false)

    if (type === 'view' && selected) {
      setDetailLoading(true)
      api.get(`/api/v1/cert/manage/${selected}`, { headers: custHeaders() })
        .then(r => setDetail(r.data.data))
        .catch(() => setDetail(null))
        .finally(() => setDetailLoading(false))
    }
  }

  async function handleRevoke() {
    if (!selected) return
    setRevokeLoading(true); setRevokeError('')
    try {
      await api.delete(`/api/v1/cert/manage/${selected}`, { headers: custHeaders() })
      setRevokeDone(true)
      await loadCerts()
    } catch (err: unknown) {
      const e = err as { response?: { data?: { message?: string } } }
      setRevokeError(e.response?.data?.message ?? '인증서 폐기 중 오류가 발생했습니다.')
    } finally {
      setRevokeLoading(false)
    }
  }

  async function handlePinChange() {
    const isFinCert = certs.find(c => c.serialNumber === selected)?.certType === 'CERT_FIN'
    if (isFinCert ? !/^\d{6}$/.test(newPin) : newPin.length < 8) {
      setPinError(isFinCert ? '금융인증서 PIN은 숫자 6자리로 입력해 주세요.' : '새 인증서 암호는 8자리 이상 입력해 주세요.'); return
    }
    if (newPin !== newPinConfirm)    { setPinError('새 인증서 암호가 일치하지 않습니다.'); return }
    if (!currentPin)                 { setPinError('현재 인증서 암호를 입력해 주세요.'); return }
    setPinLoading(true); setPinError('')
    try {
      await api.put('/api/v1/cert/manage/pin', {
        certSerialNumber: selected, currentPin, newPin,
      }, { headers: custHeaders() })
      setPinDone(true)
    } catch (err: unknown) {
      const e = err as { response?: { data?: { message?: string } } }
      setPinError(e.response?.data?.message ?? '암호 변경 중 오류가 발생했습니다.')
    } finally {
      setPinLoading(false)
    }
  }

  const selectedCert = certs.find(c => c.serialNumber === selected)
  const isFinCert = selectedCert?.certType === 'CERT_FIN'   // 금융인증서는 6자리 숫자 PIN, 그 외(공동 등)는 8+ 영숫자특

  // ── 인증서 선택 바 ─────────────────────────────────────────

  function CertSelector() {
    if (loading) return <p className="text-[13px] text-kb-text-muted">인증서 목록을 불러오는 중...</p>
    if (certs.length === 0) return (
      <div className="bg-[#FFF9E6] border border-[#E8D88A] rounded-xl px-5 py-4 text-[13px] text-[#7A6200]">
        등록된 인증서가 없습니다.&nbsp;
        <Link href="/cert" className="font-bold underline">인증서 발급하기 →</Link>
      </div>
    )
    return (
      <div className="border border-kb-border rounded-xl overflow-hidden">
        <div className="px-5 py-3 bg-kb-primary-bg border-b border-kb-border">
          <span className="text-[13px] font-bold text-kb-text">등록된 인증서 ({certs.length}개)</span>
        </div>
        {certs.map(cert => (
          <button key={cert.serialNumber}
            onClick={() => setSelected(cert.serialNumber)}
            className={`w-full flex items-center justify-between px-5 py-3.5 text-left border-b border-kb-border last:border-b-0 transition-colors
              ${selected === cert.serialNumber ? 'bg-kb-primary-bg' : 'hover:bg-kb-beige-light'}`}>
            <div className="flex items-center gap-3">
              <div className={`w-2 h-2 rounded-full flex-shrink-0 ${selected === cert.serialNumber ? 'bg-kb-primary' : 'bg-gray-300'}`} />
              <div>
                <span className="text-[13px] font-semibold text-kb-text">{cert.certTypeName}</span>
                <span className="text-[12px] text-kb-text-muted ml-2">{cert.serialNumber}</span>
              </div>
            </div>
            <div className="flex items-center gap-3 flex-shrink-0">
              <span className="text-[11px] text-kb-text-muted">
                {fmtDate(cert.issuedDate)} ~ {fmtDate(cert.expiryDate)}
              </span>
              <span className={`text-[11px] font-semibold px-2 py-0.5 rounded-full ${statusColor(cert.status)}`}>
                {cert.statusName}
              </span>
            </div>
          </button>
        ))}
      </div>
    )
  }

  // ── 카드 정의 ─────────────────────────────────────────────

  const CARDS = [
    {
      id: 'view' as ModalType,
      title: '인증서 보기/검증',
      desc: '인증서의 유효기간 등 상세 정보를 확인하실 수 있습니다.',
      actionLabel: '인증서 보기/검증',
      icon: (
        <svg viewBox="0 0 80 40" fill="none" className="w-20 h-10">
          <circle cx="32" cy="20" r="14" fill="#C09B3A" opacity="0.15" stroke="#C09B3A" strokeWidth="1.5"/>
          <path d="M28 20l4 4 6-8" stroke="#544C40" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round"/>
          <circle cx="57" cy="28" r="8" fill="#f5f5f5" stroke="#ccc" strokeWidth="1.5"/>
          <path d="M53 28l3 3 5-5" stroke="#C09B3A" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round"/>
          <path d="M62 33l5 5" stroke="#ccc" strokeWidth="2" strokeLinecap="round"/>
        </svg>
      ),
    },
    {
      id: 'revoke' as ModalType,
      title: '인증서 삭제',
      desc: '사용하지 않거나 불필요한 인증서를 폐기할 수 있습니다.',
      actionLabel: '인증서 삭제',
      icon: (
        <svg viewBox="0 0 80 40" fill="none" className="w-20 h-10">
          <circle cx="40" cy="20" r="16" fill="#C09B3A" opacity="0.15" stroke="#C09B3A" strokeWidth="1.5"/>
          <path d="M34 14l12 12M46 14L34 26" stroke="#c0392b" strokeWidth="2" strokeLinecap="round"/>
        </svg>
      ),
    },
    {
      id: 'pin' as ModalType,
      title: '인증서 암호변경',
      desc: '더욱 안전한 인증서 사용을 위해 암호를 새롭게 변경할 수 있습니다.',
      actionLabel: '인증서 암호변경',
      icon: (
        <svg viewBox="0 0 80 40" fill="none" className="w-20 h-10">
          <circle cx="40" cy="20" r="14" fill="#C09B3A" opacity="0.15" stroke="#C09B3A" strokeWidth="1.5"/>
          <text x="40" y="25" textAnchor="middle" fontSize="14" fontWeight="bold" fill="#544C40">**</text>
          <path d="M54 14l6-6M56 8l4 4" stroke="#C09B3A" strokeWidth="2" strokeLinecap="round"/>
        </svg>
      ),
    },
    {
      id: 'import' as ModalType,
      title: '인증서 가져오기',
      desc: '암호화된 인증서 파일을 복원하여 이용하실 수 있습니다.',
      actionLabel: '인증서 가져오기',
      icon: (
        <svg viewBox="0 0 80 40" fill="none" className="w-20 h-10">
          <rect x="2" y="4" width="28" height="32" rx="2" fill="#f5f5f5" stroke="#ccc" strokeWidth="1.5"/>
          <rect x="8" y="10" width="16" height="2" rx="1" fill="#ccc"/>
          <rect x="8" y="15" width="16" height="2" rx="1" fill="#ccc"/>
          <path d="M38 20h4" stroke="#C09B3A" strokeWidth="2" strokeLinecap="round"/>
          <path d="M36 18l4 2-4 2" fill="#C09B3A"/>
          <circle cx="66" cy="20" r="12" fill="#C09B3A" opacity="0.2" stroke="#C09B3A" strokeWidth="1.5"/>
          <path d="M62 20l4 4 6-8" stroke="#544C40" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round"/>
        </svg>
      ),
    },
    {
      id: 'export' as ModalType,
      title: '인증서 내보내기',
      desc: '인증서를 암호화 파일로 저장하여 다른 장치로 이동할 수 있습니다.',
      actionLabel: '인증서 내보내기',
      icon: (
        <svg viewBox="0 0 80 40" fill="none" className="w-20 h-10">
          <circle cx="14" cy="20" r="12" fill="#C09B3A" opacity="0.2" stroke="#C09B3A" strokeWidth="1.5"/>
          <path d="M10 20l4 4 6-8" stroke="#544C40" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round"/>
          <path d="M38 20h4" stroke="#C09B3A" strokeWidth="2" strokeLinecap="round"/>
          <path d="M44 18l4 2-4 2" fill="#C09B3A"/>
          <rect x="50" y="4" width="28" height="32" rx="2" fill="#f5f5f5" stroke="#ccc" strokeWidth="1.5"/>
          <rect x="56" y="10" width="16" height="2" rx="1" fill="#ccc"/>
          <rect x="56" y="15" width="16" height="2" rx="1" fill="#ccc"/>
        </svg>
      ),
    },
    {
      id: 'copy' as ModalType,
      title: '인증서 복사',
      desc: '인증서를 다른 저장 장치에 복사하여 여러 곳에서 이용할 수 있습니다.',
      actionLabel: '인증서 복사',
      icon: (
        <svg viewBox="0 0 80 40" fill="none" className="w-20 h-10">
          <circle cx="14" cy="20" r="12" fill="#C09B3A" opacity="0.2" stroke="#C09B3A" strokeWidth="1.5"/>
          <path d="M10 20l4 4 6-8" stroke="#544C40" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round"/>
          <path d="M34 20h12" stroke="#C09B3A" strokeWidth="2" strokeLinecap="round"/>
          <path d="M40 17l6 3-6 3" fill="#C09B3A"/>
          <rect x="54" y="8" width="12" height="22" rx="3" fill="#f5f5f5" stroke="#ccc" strokeWidth="1.5"/>
        </svg>
      ),
    },
  ]

  return (
    <div className="max-w-kb-container mx-auto px-6 py-8">

      {/* 브레드크럼 */}
      <div className="flex items-center gap-1 text-[12px] text-kb-text-muted mb-6">
        <Link href="/" className="hover:underline">홈</Link>
        <span>›</span>
        <Link href="/cert" className="hover:underline">인증센터(개인)</Link>
        <span>›</span>
        <span className="font-semibold text-kb-text">인증서 관리</span>
      </div>

      <h1 className="text-[24px] font-bold text-kb-text mb-8">인증서 관리</h1>

      <div className="space-y-8">

        {/* 인증서 선택 */}
        <CertSelector />

        {/* 안내 */}
        <div className="bg-kb-primary-bg border border-kb-border rounded-xl px-5 py-4 space-y-1.5">
          <div className="flex items-start gap-2 text-[13px] text-kb-text-body">
            <span className="flex-shrink-0 mt-0.5">·</span>
            인증서 관리 메뉴에서 인증서 보기, 검증, 암호변경, 폐기 등을 할 수 있습니다.
          </div>
          <div className="flex items-start gap-2 text-[13px] text-kb-text-body">
            <span className="flex-shrink-0 mt-0.5">·</span>
            작업을 수행할 인증서를 위 목록에서 먼저 선택해 주세요.
          </div>
        </div>

        {/* 6개 카드 */}
        <div className="grid grid-cols-2 gap-4">
          {CARDS.map((card) => {
            const disabled = certs.length === 0 || !selected
            return (
              <div key={card.id} className="border border-kb-border rounded-xl p-6 space-y-3">
                <div className="flex justify-center py-2">{card.icon}</div>
                <h3 className="text-[14px] font-bold text-kb-text">{card.title}</h3>
                <p className="text-[12px] text-kb-text-muted leading-relaxed">{card.desc}</p>
                <div className="flex gap-2 pt-1">
                  <Link href="/banking/first-visit"
                    className="flex-1 py-2 border border-kb-border text-[12px] text-kb-text hover:bg-kb-primary-bg transition-colors rounded text-center">
                    이용 안내
                  </Link>
                  <button
                    onClick={() => !disabled && openModal(card.id)}
                    disabled={disabled}
                    className="flex-1 py-2 text-[12px] font-bold text-white hover:opacity-85 disabled:opacity-40 transition-opacity rounded"
                    style={{ backgroundColor: KB_PRIMARY }}
                  >
                    {card.actionLabel}
                  </button>
                </div>
              </div>
            )
          })}
        </div>
      </div>

      {/* ── 모달: 보기/검증 ── */}
      {modal === 'view' && (
        <Modal title="인증서 보기/검증" onClose={() => setModal(null)}>
          {detailLoading ? (
            <div className="flex justify-center py-8">
              <div className="w-8 h-8 rounded-full border-[3px] border-t-transparent animate-spin" style={{ borderColor: KB_MINT, borderTopColor: 'transparent' }} />
            </div>
          ) : detail ? (
            <div className="space-y-4">
              <div className="border border-kb-border rounded-xl overflow-hidden">
                <table className="w-full text-[13px]">
                  <tbody>
                    {[
                      ['인증서 유형', detail.certTypeName],
                      ['일련번호', detail.serialNumber],
                      ['발급기관', detail.issuerName],
                      ['발급일', fmtDate(detail.issuedDate)],
                      ['만료일', fmtDate(detail.expiryDate)],
                      ['상태', detail.statusName],
                      ['용도', detail.purposeCode],
                      ['주체', detail.subjectDn],
                      ['발급자 DN', detail.issuerDn],
                    ].map(([label, value]) => (
                      <tr key={label} className="border-b border-kb-border last:border-b-0">
                        <td className="px-4 py-2.5 font-semibold text-[12px] w-28 whitespace-nowrap" style={{ backgroundColor: KB_PRIMARY_BG }}>{label}</td>
                        <td className="border-l border-kb-border px-4 py-2.5 text-[12px] text-kb-text-body break-all">{value}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
              <div className={`flex items-center gap-2 px-4 py-2.5 rounded-lg text-[12px] font-semibold ${detail.status === 'ACTIVE' ? 'bg-[#E8F5F0] text-kb-primary' : 'bg-red-50 text-red-600'}`}>
                <svg viewBox="0 0 16 16" fill="none" className="w-4 h-4" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
                  {detail.status === 'ACTIVE'
                    ? <><circle cx="8" cy="8" r="7"/><path d="M5 8l2 2 4-4"/></>
                    : <><circle cx="8" cy="8" r="7"/><line x1="8" y1="5" x2="8" y2="9"/><circle cx="8" cy="11.5" r="0.5" fill="currentColor"/></>
                  }
                </svg>
                {detail.status === 'ACTIVE' ? '인증서가 유효합니다.' : `인증서 상태: ${detail.statusName}`}
              </div>
              <div className="flex justify-end">
                <button onClick={() => setModal(null)}
                  className="px-8 py-2 text-[13px] font-bold text-white rounded-lg hover:opacity-85"
                  style={{ backgroundColor: KB_PRIMARY }}>
                  확인
                </button>
              </div>
            </div>
          ) : (
            <p className="text-[13px] text-red-500 py-4">인증서 정보를 불러올 수 없습니다.</p>
          )}
        </Modal>
      )}

      {/* ── 모달: 삭제(폐기) ── */}
      {modal === 'revoke' && (
        <Modal title="인증서 삭제" onClose={() => setModal(null)}>
          {revokeDone ? (
            <div className="space-y-4">
              <div className="flex items-center gap-3 bg-kb-primary-bg border border-kb-border rounded-xl px-5 py-4">
                <div className="w-10 h-10 rounded-full flex items-center justify-center flex-shrink-0" style={{ backgroundColor: KB_PRIMARY }}>
                  <svg viewBox="0 0 24 24" fill="none" className="w-5 h-5" stroke="white" strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round">
                    <polyline points="20,6 9,17 4,12"/>
                  </svg>
                </div>
                <div>
                  <p className="text-[14px] font-bold" style={{ color: KB_PRIMARY }}>인증서가 폐기되었습니다.</p>
                  <p className="text-[12px] text-kb-text-muted">해당 인증서는 더 이상 사용할 수 없습니다.</p>
                </div>
              </div>
              <div className="flex justify-end">
                <button onClick={() => setModal(null)}
                  className="px-8 py-2 text-[13px] font-bold text-white rounded-lg hover:opacity-85"
                  style={{ backgroundColor: KB_PRIMARY }}>
                  확인
                </button>
              </div>
            </div>
          ) : (
            <div className="space-y-5">
              <div className="bg-[#FFF4F4] border border-red-200 rounded-xl px-5 py-4 space-y-1.5">
                <p className="text-[13px] font-bold text-red-600">⚠ 인증서 폐기 주의사항</p>
                <p className="text-[12px] text-kb-text-body">폐기된 인증서는 복구할 수 없습니다. 신중하게 진행해 주세요.</p>
                <p className="text-[12px] text-kb-text-body">폐기 후 해당 인증서로 로그인 및 금융거래가 불가합니다.</p>
              </div>
              {selectedCert && (
                <div className="border border-kb-border rounded-xl overflow-hidden">
                  <table className="w-full text-[13px]">
                    <tbody>
                      <tr className="border-b border-kb-border">
                        <td className="px-4 py-2.5 font-semibold text-[12px] w-24 whitespace-nowrap" style={{ backgroundColor: KB_PRIMARY_BG }}>인증서 유형</td>
                        <td className="border-l border-kb-border px-4 py-2.5 text-[12px]">{selectedCert.certTypeName}</td>
                      </tr>
                      <tr>
                        <td className="px-4 py-2.5 font-semibold text-[12px]" style={{ backgroundColor: KB_PRIMARY_BG }}>일련번호</td>
                        <td className="border-l border-kb-border px-4 py-2.5 text-[12px] break-all">{selectedCert.serialNumber}</td>
                      </tr>
                    </tbody>
                  </table>
                </div>
              )}
              {revokeError && <p className="text-[12px] text-red-500">{revokeError}</p>}
              <div className="flex justify-end gap-3">
                <button onClick={() => setModal(null)}
                  className="px-8 py-2 border border-kb-border text-[13px] text-kb-text-body hover:bg-kb-primary-bg transition-colors">
                  취소
                </button>
                <button onClick={handleRevoke} disabled={revokeLoading}
                  className="px-8 py-2 text-[13px] font-bold text-white rounded-lg bg-red-500 hover:bg-red-600 disabled:opacity-50 transition-colors">
                  {revokeLoading ? '처리 중...' : '폐기 확인'}
                </button>
              </div>
            </div>
          )}
        </Modal>
      )}

      {/* ── 모달: 암호변경 ── */}
      {modal === 'pin' && (
        <Modal title="인증서 암호변경" onClose={() => setModal(null)}>
          {pinDone ? (
            <div className="space-y-4">
              <div className="flex items-center gap-3 bg-kb-primary-bg border border-kb-border rounded-xl px-5 py-4">
                <div className="w-10 h-10 rounded-full flex items-center justify-center flex-shrink-0" style={{ backgroundColor: KB_PRIMARY }}>
                  <svg viewBox="0 0 24 24" fill="none" className="w-5 h-5" stroke="white" strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round">
                    <polyline points="20,6 9,17 4,12"/>
                  </svg>
                </div>
                <div>
                  <p className="text-[14px] font-bold" style={{ color: KB_PRIMARY }}>인증서 암호가 변경되었습니다.</p>
                  <p className="text-[12px] text-kb-text-muted">다음 로그인부터 새 암호를 사용하세요.</p>
                </div>
              </div>
              <div className="flex justify-end">
                <button onClick={() => setModal(null)}
                  className="px-8 py-2 text-[13px] font-bold text-white rounded-lg hover:opacity-85"
                  style={{ backgroundColor: KB_PRIMARY }}>
                  확인
                </button>
              </div>
            </div>
          ) : (
            <div className="space-y-4">
              {selectedCert && (
                <p className="text-[12px] text-kb-text-muted">
                  대상: <span className="font-semibold text-kb-text">{selectedCert.certTypeName}</span>
                  <span className="ml-2 text-kb-text-muted">{selectedCert.serialNumber}</span>
                </p>
              )}
              <div className="space-y-3">
                <div className="space-y-1">
                  <label className="text-[13px] font-semibold text-kb-text">{isFinCert ? '현재 PIN' : '현재 인증서 암호'}</label>
                  <PwInput value={currentPin} onChange={isFinCert ? (v) => setCurrentPin(v.replace(/\D/g, '').slice(0, 6)) : setCurrentPin} placeholder={isFinCert ? '현재 PIN 입력' : '현재 암호 입력'} />
                </div>
                <div className="space-y-1">
                  <label className="text-[13px] font-semibold text-kb-text">{isFinCert ? '새 PIN' : '새 인증서 암호'}</label>
                  <PwInput value={newPin} onChange={isFinCert ? (v) => setNewPin(v.replace(/\D/g, '').slice(0, 6)) : setNewPin} placeholder={isFinCert ? '숫자 6자리' : '영문/숫자/특수문자 조합(8자 이상)'} />
                </div>
                <div className="space-y-1">
                  <label className="text-[13px] font-semibold text-kb-text">{isFinCert ? '새 PIN 확인' : '새 인증서 암호 확인'}</label>
                  <PwInput value={newPinConfirm} onChange={isFinCert ? (v) => setNewPinConfirm(v.replace(/\D/g, '').slice(0, 6)) : setNewPinConfirm} placeholder={isFinCert ? '새 PIN 재입력' : '새 암호 재입력'} />
                  {newPinConfirm && newPin !== newPinConfirm && (
                    <p className="text-[11px] text-red-500">암호가 일치하지 않습니다.</p>
                  )}
                  {newPinConfirm && newPin === newPinConfirm && (isFinCert ? newPin.length === 6 : newPin.length >= 8) && (
                    <p className="text-[11px] font-semibold" style={{ color: KB_PRIMARY }}>✓ 일치합니다.</p>
                  )}
                </div>
              </div>
              {pinError && <p className="text-[12px] text-red-500">{pinError}</p>}
              <div className="flex justify-end gap-3 pt-1">
                <button onClick={() => setModal(null)}
                  className="px-8 py-2 border border-kb-border text-[13px] text-kb-text-body hover:bg-kb-primary-bg transition-colors">
                  취소
                </button>
                <button onClick={handlePinChange} disabled={pinLoading}
                  className="px-8 py-2 text-[13px] font-bold text-white rounded-lg hover:opacity-85 disabled:opacity-50"
                  style={{ backgroundColor: KB_PRIMARY }}>
                  {pinLoading ? '변경 중...' : '암호 변경'}
                </button>
              </div>
            </div>
          )}
        </Modal>
      )}

      {/* ── 모달: 가져오기 (모의) ── */}
      {modal === 'import' && (
        <Modal title="인증서 가져오기" onClose={() => setModal(null)}>
          {mockDone ? (
            <div className="space-y-4">
              <div className="flex items-center gap-3 bg-kb-primary-bg border border-kb-border rounded-xl px-5 py-4">
                <div className="w-10 h-10 rounded-full flex items-center justify-center flex-shrink-0" style={{ backgroundColor: KB_PRIMARY }}>
                  <svg viewBox="0 0 24 24" fill="none" className="w-5 h-5" stroke="white" strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round">
                    <polyline points="20,6 9,17 4,12"/>
                  </svg>
                </div>
                <div>
                  <p className="text-[14px] font-bold" style={{ color: KB_PRIMARY }}>인증서를 가져왔습니다.</p>
                  <p className="text-[12px] text-kb-text-muted">인증서가 정상적으로 복원되었습니다.</p>
                </div>
              </div>
              <div className="flex justify-end">
                <button onClick={() => setModal(null)}
                  className="px-8 py-2 text-[13px] font-bold text-white rounded-lg hover:opacity-85"
                  style={{ backgroundColor: KB_PRIMARY }}>확인</button>
              </div>
            </div>
          ) : (
            <div className="space-y-5">
              <div className="bg-kb-primary-bg border border-kb-border rounded-xl px-5 py-4 space-y-1.5 text-[12px] text-kb-text-body">
                <p>· 암호화된 인증서 파일(.pfx)을 선택하여 가져올 수 있습니다.</p>
                <p>· 인증서 파일과 파일 암호가 필요합니다.</p>
              </div>
              <div className="space-y-3">
                <div className="space-y-1">
                  <label className="text-[13px] font-semibold text-kb-text">인증서 파일 선택</label>
                  <input type="file" accept=".pfx,.p12,.cer"
                    className="w-full border border-kb-border px-3 py-2 text-[12px] rounded file:mr-3 file:py-1 file:px-3 file:rounded file:border-0 file:text-[12px] file:font-semibold file:text-white"
                    style={{ '--tw-file-selector-button-bg': KB_PRIMARY } as React.CSSProperties} />
                </div>
                <div className="space-y-1">
                  <label className="text-[13px] font-semibold text-kb-text">파일 암호</label>
                  <PwInput value={currentPin} onChange={setCurrentPin} placeholder="파일 암호 입력" />
                </div>
              </div>
              <div className="flex justify-end gap-3">
                <button onClick={() => setModal(null)}
                  className="px-8 py-2 border border-kb-border text-[13px] text-kb-text-body hover:bg-kb-primary-bg transition-colors">취소</button>
                <button onClick={() => setMockDone(true)}
                  className="px-8 py-2 text-[13px] font-bold text-white rounded-lg hover:opacity-85"
                  style={{ backgroundColor: KB_PRIMARY }}>가져오기</button>
              </div>
            </div>
          )}
        </Modal>
      )}

      {/* ── 모달: 내보내기 (모의) ── */}
      {modal === 'export' && (
        <Modal title="인증서 내보내기" onClose={() => setModal(null)}>
          {mockDone ? (
            <div className="space-y-4">
              <div className="flex items-center gap-3 bg-kb-primary-bg border border-kb-border rounded-xl px-5 py-4">
                <div className="w-10 h-10 rounded-full flex items-center justify-center flex-shrink-0" style={{ backgroundColor: KB_PRIMARY }}>
                  <svg viewBox="0 0 24 24" fill="none" className="w-5 h-5" stroke="white" strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round">
                    <polyline points="20,6 9,17 4,12"/>
                  </svg>
                </div>
                <div>
                  <p className="text-[14px] font-bold" style={{ color: KB_PRIMARY }}>인증서를 내보냈습니다.</p>
                  <p className="text-[12px] text-kb-text-muted">파일명: cert_export_{new Date().toISOString().slice(0, 10)}.pfx</p>
                </div>
              </div>
              <div className="flex justify-end">
                <button onClick={() => setModal(null)}
                  className="px-8 py-2 text-[13px] font-bold text-white rounded-lg hover:opacity-85"
                  style={{ backgroundColor: KB_PRIMARY }}>확인</button>
              </div>
            </div>
          ) : (
            <div className="space-y-5">
              <div className="bg-kb-primary-bg border border-kb-border rounded-xl px-5 py-4 space-y-1.5 text-[12px] text-kb-text-body">
                <p>· 인증서를 암호화된 파일(.pfx)로 저장합니다.</p>
                <p>· 저장할 파일 암호를 설정해 주세요.</p>
              </div>
              {selectedCert && (
                <p className="text-[12px] text-kb-text-muted">
                  대상: <span className="font-semibold text-kb-text">{selectedCert.certTypeName}</span>
                  <span className="ml-2">{selectedCert.serialNumber}</span>
                </p>
              )}
              <div className="space-y-1">
                <label className="text-[13px] font-semibold text-kb-text">파일 암호 설정</label>
                <PwInput value={currentPin} onChange={setCurrentPin} placeholder="파일 암호 입력 (8자 이상)" />
              </div>
              <div className="flex justify-end gap-3">
                <button onClick={() => setModal(null)}
                  className="px-8 py-2 border border-kb-border text-[13px] text-kb-text-body hover:bg-kb-primary-bg transition-colors">취소</button>
                <button onClick={() => setMockDone(true)}
                  className="px-8 py-2 text-[13px] font-bold text-white rounded-lg hover:opacity-85"
                  style={{ backgroundColor: KB_PRIMARY }}>내보내기</button>
              </div>
            </div>
          )}
        </Modal>
      )}

      {/* ── 모달: 복사 (모의) ── */}
      {modal === 'copy' && (
        <Modal title="인증서 복사" onClose={() => setModal(null)}>
          {mockDone ? (
            <div className="space-y-4">
              <div className="flex items-center gap-3 bg-kb-primary-bg border border-kb-border rounded-xl px-5 py-4">
                <div className="w-10 h-10 rounded-full flex items-center justify-center flex-shrink-0" style={{ backgroundColor: KB_PRIMARY }}>
                  <svg viewBox="0 0 24 24" fill="none" className="w-5 h-5" stroke="white" strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round">
                    <polyline points="20,6 9,17 4,12"/>
                  </svg>
                </div>
                <div>
                  <p className="text-[14px] font-bold" style={{ color: KB_PRIMARY }}>인증서가 복사되었습니다.</p>
                  <p className="text-[12px] text-kb-text-muted">선택한 저장 장치에 인증서가 복사되었습니다.</p>
                </div>
              </div>
              <div className="flex justify-end">
                <button onClick={() => setModal(null)}
                  className="px-8 py-2 text-[13px] font-bold text-white rounded-lg hover:opacity-85"
                  style={{ backgroundColor: KB_PRIMARY }}>확인</button>
              </div>
            </div>
          ) : (
            <div className="space-y-5">
              <div className="bg-kb-primary-bg border border-kb-border rounded-xl px-5 py-4 space-y-1.5 text-[12px] text-kb-text-body">
                <p>· 인증서를 PC 또는 이동식 저장 장치에 복사합니다.</p>
                <p>· 복사된 인증서는 동일한 인증서 암호로 사용됩니다.</p>
              </div>
              {selectedCert && (
                <p className="text-[12px] text-kb-text-muted">
                  대상: <span className="font-semibold text-kb-text">{selectedCert.certTypeName}</span>
                  <span className="ml-2">{selectedCert.serialNumber}</span>
                </p>
              )}
              <div className="space-y-1">
                <label className="text-[13px] font-semibold text-kb-text">저장 위치</label>
                <select className="w-full border border-kb-border px-3 py-2 text-[13px] outline-none focus:border-kb-primary rounded bg-white">
                  <option>내 컴퓨터 (C:\Users\공인인증서)</option>
                  <option>이동식 디스크 (D:\)</option>
                  <option>이동식 디스크 (E:\)</option>
                </select>
              </div>
              <div className="flex justify-end gap-3">
                <button onClick={() => setModal(null)}
                  className="px-8 py-2 border border-kb-border text-[13px] text-kb-text-body hover:bg-kb-primary-bg transition-colors">취소</button>
                <button onClick={() => setMockDone(true)}
                  className="px-8 py-2 text-[13px] font-bold text-white rounded-lg hover:opacity-85"
                  style={{ backgroundColor: KB_PRIMARY }}>복사</button>
              </div>
            </div>
          )}
        </Modal>
      )}

    </div>
  )
}
