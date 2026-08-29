'use client'
import { KB_BORDER, KB_DANGER, KB_PRIMARY, KB_PRIMARY_BG, KB_PRIMARY_BORDER, KB_PRIMARY_SURFACE, KB_TEXT_MUTED } from '@/lib/theme'

import Link from 'next/link'
import { useEffect, useState } from 'react'
import { useRouter } from 'next/navigation'
import { api } from '@/lib/api'
import { readAccessToken } from '@/lib/token'
import { loginUrlFor } from '@/lib/return-url'

interface GradeInfo {
  previousGradeCode: string | null
  newGradeCode: string
  changeReasonCode: string
  effectiveStartDate: string
}
interface StatusInfo {
  previousStatusCode: string | null
  newStatusCode: string
  changeReasonCode: string
  effectiveStartAt: string
}
interface MyPageData {
  customerId: number
  loginId: string
  name: string
  email: string | null
  phone: string | null
  zipCode: string | null
  address: string | null
  addressDetail: string | null
  birthDate: string
  genderCode: string
  customerGradeCode: string
  customerStatusCode: string
  creditRatingCode: string | null
  joinedAt: string
  lastLoginAt: string | null
  latestGrade: GradeInfo | null
  latestStatus: StatusInfo | null
}
interface HistoryItem {
  previousCode: string | null
  newCode: string
  changeReasonCode: string
  effectiveAt: string
}

function maskId(loginId: string) {
  if (loginId.length <= 4) return loginId + '****'
  return loginId.slice(0, 4) + '****'
}
function maskPhone(phone: string | null) {
  if (!phone) return '-'
  const d = phone.replace(/\D/g, '')
  if (d.length === 11) return `${d.slice(0, 3)}) ${d.slice(3, 5)}** - ${d.slice(7, 9)}**`
  return phone
}
function maskEmail(email: string | null) {
  if (!email) return '-'
  const [local, domain] = email.split('@')
  if (!domain) return email
  return local.slice(0, 4) + '****@' + domain
}
function gradeLabel(code: string) {
  const m: Record<string, string> = { VIP: 'VIP', GOLD: '골드', SILVER: '실버', FAMILY: '패밀리', NORMAL: '일반', PB: 'PB' }
  return m[code] ?? code
}
function reasonLabel(code: string) {
  const m: Record<string, string> = {
    JOIN: '신규 가입', TRANSACTION: '거래 실적', ADMIN: '관리자 처리', SYSTEM: '자동 산정',
    INACTIVITY: '장기 미거래', CUST_REQ: '고객 요청', REACTIVATE: '재활성화', REGULATORY: '법적 사유',
  }
  return m[code] ?? code
}
function fmtDate(str: string) {
  const d = new Date(str)
  return `${d.getFullYear()}.${String(d.getMonth()+1).padStart(2,'0')}.${String(d.getDate()).padStart(2,'0')}`
}
/** YYYYMMDD → YYYY.MM.DD. birthDate 가 null/형식미달이면 '-' (페이지 크래시 방지) */
function fmtBirth(v: string | null | undefined) {
  if (!v || v.length < 8) return '-'
  return `${v.slice(0,4)}.${v.slice(4,6)}.${v.slice(6,8)}`
}

function Toggle({ checked, onChange }: { checked: boolean; onChange: (v: boolean) => void }) {
  return (
    <button onClick={() => onChange(!checked)}
      className="relative w-10 h-5 rounded-full transition-colors flex-shrink-0"
      style={{ backgroundColor: checked ? KB_PRIMARY : KB_BORDER }}>
      <span className={`absolute top-0.5 w-4 h-4 bg-white rounded-full shadow transition-transform ${checked ? 'translate-x-5' : 'translate-x-0.5'}`} />
    </button>
  )
}

function HistoryRow({ item }: { item: HistoryItem }) {
  return (
    <div className="flex items-center text-[12px] py-2 gap-3" style={{ borderBottom: `1px solid ${KB_PRIMARY_BG}` }}>
      <span className="text-kb-text-muted w-28 flex-shrink-0">{fmtDate(item.effectiveAt)}</span>
      <span className="text-kb-text-muted flex-shrink-0">{item.previousCode ? gradeLabel(item.previousCode) : '–'} → </span>
      <span className="font-semibold text-kb-text">{gradeLabel(item.newCode)}</span>
      <span className="ml-auto text-kb-text-muted">{reasonLabel(item.changeReasonCode)}</span>
    </div>
  )
}

export default function MyKBPage() {
  const router = useRouter()
  const [data, setData] = useState<MyPageData | null>(null)
  const [error, setError] = useState('')
  const [gradeHistory, setGradeHistory] = useState<HistoryItem[]>([])
  const [statusHistory, setStatusHistory] = useState<HistoryItem[]>([])

  const [notification, setNotification] = useState({ smsReceiveYn: false, emailReceiveYn: false, postalReceiveYn: false })
  const [notifSaving, setNotifSaving] = useState(false)
  const [notifMsg, setNotifMsg] = useState('')
  const [pwForm, setPwForm] = useState({ current: '', next: '', confirm: '' })
  const [pwSaving, setPwSaving] = useState(false)
  const [pwMsg, setPwMsg] = useState('')
  const [pwError, setPwError] = useState('')

  useEffect(() => {
    const token = readAccessToken()
    if (!token) { router.replace(loginUrlFor('/mypage')); return }

    api.get('/api/v1/customers/me')
      .then(res => setData(res.data.data))
      .catch(() => { setError('정보를 불러오지 못했습니다.'); localStorage.removeItem('accessToken') })

    api.get('/api/v1/customers/me/settings')
      .then(res => {
        const d = res.data.data
        setNotification({ smsReceiveYn: d.smsReceiveYn, emailReceiveYn: d.emailReceiveYn, postalReceiveYn: d.postalReceiveYn })
      }).catch(() => {})

    api.get('/api/v1/customers/me/grade-history')
      .then(res => {
        const items = (res.data.data as Array<{
          previousCustomerGradeCode: string | null
          customerGradeCode: string
          customerGradeChangeReasonCode: string
          customerGradeEffectiveStartDate: string
        }>).slice(0, 5).map(h => ({
          previousCode: h.previousCustomerGradeCode,
          newCode: h.customerGradeCode,
          changeReasonCode: h.customerGradeChangeReasonCode,
          effectiveAt: h.customerGradeEffectiveStartDate,
        })).filter(h => h.previousCode !== null)
        setGradeHistory(items)
      }).catch(() => {})

    api.get('/api/v1/customers/me/status-history')
      .then(res => {
        const items = (res.data.data as Array<{
          previousCustomerStatusCode: string | null
          customerStatusCode: string
          customerStatusChangeReasonCode: string
          customerStatusEffectiveStartAt: string
        }>).slice(0, 5).map(h => ({
          previousCode: h.previousCustomerStatusCode,
          newCode: h.customerStatusCode,
          changeReasonCode: h.customerStatusChangeReasonCode,
          effectiveAt: h.customerStatusEffectiveStartAt,
        })).filter(h => h.previousCode !== null)
        setStatusHistory(items)
      }).catch(() => {})
  }, [router])

  async function handleNotifSave() {
    setNotifSaving(true); setNotifMsg('')
    try {
      await api.put('/api/v1/customers/me/notification', notification)
      setNotifMsg('알림설정이 저장되었습니다.')
    } catch { setNotifMsg('저장에 실패했습니다.') }
    finally { setNotifSaving(false); setTimeout(() => setNotifMsg(''), 3000) }
  }

  async function handlePasswordChange() {
    setPwError(''); setPwMsg('')
    if (!pwForm.current || !pwForm.next || !pwForm.confirm) { setPwError('모든 항목을 입력해주세요.'); return }
    if (pwForm.next !== pwForm.confirm) { setPwError('새 비밀번호가 일치하지 않습니다.'); return }
    if (pwForm.next.length < 8) { setPwError('새 비밀번호는 8자 이상이어야 합니다.'); return }
    setPwSaving(true)
    try {
      await api.put('/api/v1/customers/me/password', { currentPassword: pwForm.current, newPassword: pwForm.next })
      setPwMsg('비밀번호가 변경되었습니다.')
      setPwForm({ current: '', next: '', confirm: '' })
    } catch (e: unknown) {
      const msg = (e as { response?: { data?: { message?: string } } })?.response?.data?.message
      setPwError(msg ?? '현재 비밀번호를 확인해주세요.')
    } finally { setPwSaving(false) }
  }

  if (error) return (
    <div className="max-w-kb-container mx-auto px-6 py-20 text-center">
      <p className="text-kb-danger mb-4">{error}</p>
      <Link href="/login" style={{ color: KB_PRIMARY }} className="hover:underline">로그인 페이지로 이동</Link>
    </div>
  )
  if (!data) return <div className="max-w-kb-container mx-auto px-6 py-20 text-center text-kb-text-muted text-[14px]">불러오는 중...</div>

  const lastLoginDate = data.lastLoginAt ? new Date(data.lastLoginAt) : new Date(data.joinedAt)
  const lastLoginStr = `${lastLoginDate.getFullYear()}.${String(lastLoginDate.getMonth()+1).padStart(2,'0')}.${String(lastLoginDate.getDate()).padStart(2,'0')} ${String(lastLoginDate.getHours()).padStart(2,'0')}:${String(lastLoginDate.getMinutes()).padStart(2,'0')}`

  const lc = "border border-kb-border bg-kb-beige-light px-4 py-3 font-semibold text-kb-text"
  const vc = "border border-kb-border px-4 py-3 text-kb-text-body"
  const ic = "border rounded-lg px-3 py-2 text-[13px] outline-none focus:ring-1 w-full"

  return (
    <div className="max-w-kb-container mx-auto px-6 py-10 pb-16">
      <div className="flex justify-end mb-4 text-[12px] text-kb-text-muted gap-1">
        <span>개인뱅킹</span><span>&gt;</span>
        <span className="font-semibold text-kb-text">My AXful</span>
      </div>

      <h1 className="text-2xl font-bold text-kb-text mb-1">My AXful</h1>
      <p className="text-[12px] text-kb-text-muted mb-6">최종 접속시간 : {lastLoginStr}</p>

      {/* 인터넷뱅킹정보 */}
      <section className="mb-6">
        <h2 className="text-lg font-bold text-kb-text mb-2">인터넷뱅킹정보</h2>
        <table className="w-full border-collapse text-[13px]">
          <tbody>
            <tr>
              <td className={`${lc} w-[160px]`}>사용자 ID</td>
              <td className={`${vc} w-[300px]`}>{maskId(data.loginId)}</td>
              <td className={`${lc} w-[120px]`}>보안매체</td>
              <td className={vc}>금융인증서</td>
            </tr>
            <tr>
              <td className={lc}>이체한도</td>
              <td className={vc}>
                <div className="flex items-center gap-3">
                  <div className="text-[12px]"><p>(1일) 1,000,000원</p><p>(1회) 1,000,000원</p></div>
                  <Link href="/banking/transfer-limit" className="border border-kb-border px-3 py-1 text-[12px] text-kb-text-body hover:bg-kb-beige-light">이체한도 변경</Link>
                </div>
              </td>
              <td className={lc}>고객등급</td>
              <td className={vc}>
                <span>{gradeLabel(data.customerGradeCode)}</span>
                {data.creditRatingCode && <span className="ml-2 text-[11px] text-kb-text-muted">(신용: {data.creditRatingCode})</span>}
                {data.latestGrade && (
                  <p className="text-[11px] text-kb-text-muted mt-0.5">
                    최근 변경: {fmtDate(data.latestGrade.effectiveStartDate)} ({reasonLabel(data.latestGrade.changeReasonCode)})
                  </p>
                )}
              </td>
            </tr>
          </tbody>
        </table>
      </section>

      {/* 기본정보 */}
      <section className="mb-6">
        <div className="flex justify-between items-center mb-2">
          <h2 className="text-lg font-bold text-kb-text">기본정보</h2>
          <Link href="/settings" className="border border-kb-border px-4 py-1 text-[12px] text-kb-text-body hover:bg-kb-beige-light">정보수정</Link>
        </div>
        <table className="w-full border-collapse text-[13px]">
          <tbody>
            <tr>
              <td className={`${lc} w-[160px]`}>성명</td>
              <td className={`${vc} w-[300px]`}>{data.name}</td>
              <td className={`${lc} w-[120px]`}>생년월일</td>
              <td className={vc}>{fmtBirth(data.birthDate)}</td>
            </tr>
            <tr>
              <td className={lc}>휴대폰번호</td>
              <td className={vc}>{maskPhone(data.phone)}</td>
              <td className={lc}>이메일</td>
              <td className={vc}>{maskEmail(data.email)}</td>
            </tr>
            <tr>
              <td className={lc}>자택주소</td>
              <td className={vc} colSpan={3}>{data.address ? `(${data.zipCode ?? ''}) ${data.address} ${data.addressDetail ?? ''}`.trim() : '-'}</td>
            </tr>
          </tbody>
        </table>
      </section>

      {/* 등급 변경 이력 */}
      {gradeHistory.length > 0 && (
        <section className="mb-6">
          <h2 className="text-lg font-bold text-kb-text mb-2">등급 변경 이력</h2>
          <div className="rounded-xl overflow-hidden" style={{ border: `1px solid ${KB_PRIMARY_BORDER}` }}>
            <div className="px-5 py-3 text-[12px] font-semibold" style={{ backgroundColor: KB_PRIMARY_BG, borderBottom: `1px solid ${KB_PRIMARY_BORDER}`, color: KB_PRIMARY }}>
              최근 5건
            </div>
            <div className="px-5 py-2">
              {gradeHistory.map((h, i) => <HistoryRow key={i} item={h} />)}
            </div>
          </div>
        </section>
      )}

      {/* 상태 변경 이력 */}
      {statusHistory.length > 0 && (
        <section className="mb-6">
          <h2 className="text-lg font-bold text-kb-text mb-2">상태 변경 이력</h2>
          <div className="rounded-xl overflow-hidden" style={{ border: `1px solid ${KB_PRIMARY_BORDER}` }}>
            <div className="px-5 py-3 text-[12px] font-semibold" style={{ backgroundColor: KB_PRIMARY_BG, borderBottom: `1px solid ${KB_PRIMARY_BORDER}`, color: KB_PRIMARY }}>
              최근 5건
            </div>
            <div className="px-5 py-2">
              {statusHistory.map((h, i) => <HistoryRow key={i} item={h} />)}
            </div>
          </div>
        </section>
      )}

      {/* 알림설정 */}
      <section className="mb-6">
        <h2 className="text-lg font-bold text-kb-text mb-2">알림설정</h2>
        <div className="rounded-xl overflow-hidden" style={{ border: `1px solid ${KB_PRIMARY_BORDER}` }}>
          <div className="px-5 py-3 text-[13px] font-semibold text-kb-text" style={{ backgroundColor: KB_PRIMARY_BG, borderBottom: `1px solid ${KB_PRIMARY_BORDER}` }}>수신 동의</div>
          {[
            { key: 'smsReceiveYn' as const, label: 'SMS 수신', desc: '이체·입금 등 주요 알림을 문자로 받습니다.' },
            { key: 'emailReceiveYn' as const, label: '이메일 수신', desc: '이벤트·혜택·공지를 이메일로 받습니다.' },
            { key: 'postalReceiveYn' as const, label: '우편 수신', desc: '금융 안내를 우편으로 받습니다.' },
          ].map(({ key, label, desc }, i, arr) => (
            <div key={key} className="flex items-center justify-between px-5 py-4" style={{ borderBottom: i < arr.length - 1 ? `1px solid ${KB_PRIMARY_BORDER}` : 'none' }}>
              <div>
                <p className="text-[13px] font-semibold text-kb-text">{label}</p>
                <p className="text-[12px] text-kb-text-muted mt-0.5">{desc}</p>
              </div>
              <Toggle checked={notification[key]} onChange={v => setNotification(prev => ({ ...prev, [key]: v }))} />
            </div>
          ))}
          <div className="px-5 py-3 flex items-center justify-between" style={{ borderTop: `1px solid ${KB_PRIMARY_BORDER}`, backgroundColor: KB_PRIMARY_SURFACE }}>
            {notifMsg ? <p className="text-[12px]" style={{ color: notifMsg.includes('실패') ? KB_DANGER : KB_PRIMARY }}>{notifMsg}</p> : <span />}
            <button onClick={handleNotifSave} disabled={notifSaving}
              className="px-8 py-2 text-[13px] font-bold text-white rounded-xl hover:opacity-85 disabled:opacity-50" style={{ backgroundColor: KB_PRIMARY }}>
              {notifSaving ? '저장 중...' : '저장'}
            </button>
          </div>
        </div>
      </section>

      {/* 비밀번호 변경 */}
      <section className="mb-8">
        <h2 className="text-lg font-bold text-kb-text mb-2">비밀번호 변경</h2>
        <div className="rounded-xl overflow-hidden" style={{ border: `1px solid ${KB_PRIMARY_BORDER}` }}>
          <div className="px-5 py-3 text-[13px] font-semibold text-kb-text" style={{ backgroundColor: KB_PRIMARY_BG, borderBottom: `1px solid ${KB_PRIMARY_BORDER}` }}>인터넷뱅킹 비밀번호</div>
          <div className="px-5 py-5 space-y-4">
            <div className="rounded-xl px-4 py-3 text-[12px] text-kb-text-muted space-y-1" style={{ backgroundColor: KB_PRIMARY_SURFACE, border: `1px solid ${KB_PRIMARY_BORDER}` }}>
              <p>· 비밀번호는 8자 이상, 영문·숫자·특수문자를 조합하여 설정해주세요.</p>
              <p>· 직전 비밀번호와 동일한 비밀번호는 사용할 수 없습니다.</p>
            </div>
            <div className="max-w-sm space-y-3">
              {[
                { key: 'current' as const, label: '현재 비밀번호', placeholder: '현재 비밀번호 입력' },
                { key: 'next' as const, label: '새 비밀번호', placeholder: '새 비밀번호 입력 (8자 이상)' },
                { key: 'confirm' as const, label: '새 비밀번호 확인', placeholder: '새 비밀번호 재입력' },
              ].map(({ key, label, placeholder }) => (
                <div key={key} className="flex items-center gap-4">
                  <label className="w-32 text-[13px] font-medium text-kb-text flex-shrink-0">{label}</label>
                  <input type="password" value={pwForm[key]} onChange={e => setPwForm(prev => ({ ...prev, [key]: e.target.value }))}
                    placeholder={placeholder} className={ic} style={{ borderColor: KB_BORDER }} />
                </div>
              ))}
            </div>
            {pwError && <p className="text-[12px] font-medium" style={{ color: KB_DANGER }}>{pwError}</p>}
            {pwMsg && <p className="text-[12px] font-medium" style={{ color: KB_PRIMARY }}>{pwMsg}</p>}
            <div className="flex gap-2 pt-1">
              <button onClick={handlePasswordChange} disabled={pwSaving}
                className="px-10 py-2.5 text-[14px] font-bold text-white rounded-xl hover:opacity-85 disabled:opacity-50" style={{ backgroundColor: KB_PRIMARY }}>
                {pwSaving ? '변경 중...' : '비밀번호 변경'}
              </button>
              <button onClick={() => { setPwForm({ current: '', next: '', confirm: '' }); setPwError(''); setPwMsg('') }}
                className="border rounded-xl px-8 py-2.5 text-[14px] font-medium hover:bg-kb-primary-bg" style={{ borderColor: KB_BORDER, color: KB_TEXT_MUTED }}>
                취소
              </button>
            </div>
          </div>
        </div>
      </section>

      {/* 하단 카드 */}
      <div className="grid grid-cols-2 gap-6">
        <Link href="/mypage/consents"
          className="rounded-xl p-6 flex items-center justify-between hover:bg-kb-primary-bg transition-colors group" style={{ border: `1px solid ${KB_PRIMARY_BORDER}` }}>
          <div>
            <p className="text-[15px] font-bold text-kb-text group-hover:text-kb-primary">약관 동의 이력 &gt;</p>
            <p className="text-[13px] text-kb-text-muted mt-1">동의한 약관과 시점을 확인하고, 선택 항목은 철회하세요.</p>
          </div>
        </Link>
        <Link href="/banking/transfer-limit"
          className="rounded-xl p-6 flex items-center justify-between hover:bg-kb-primary-bg transition-colors group" style={{ border: `1px solid ${KB_PRIMARY_BORDER}` }}>
          <div>
            <p className="text-[15px] font-bold text-kb-text group-hover:text-kb-primary">이체한도 조회/변경 &gt;</p>
            <p className="text-[13px] text-kb-text-muted mt-1">1일·1회 이체한도를 확인하고 변경하세요.</p>
          </div>
        </Link>
        <Link href="/support/customer-info/withdraw"
          className="rounded-xl p-6 flex items-center justify-between hover:bg-red-50 transition-colors group" style={{ border: `1px solid ${KB_PRIMARY_BORDER}` }}>
          <div>
            <p className="text-[15px] font-bold text-kb-text group-hover:text-kb-danger">회원탈퇴 &gt;</p>
            <p className="text-[13px] text-kb-text-muted mt-1">인터넷뱅킹 이용을 중단하고 탈퇴합니다.</p>
          </div>
        </Link>
      </div>
    </div>
  )
}
