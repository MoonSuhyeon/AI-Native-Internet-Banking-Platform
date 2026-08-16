'use client'
import { KB_DANGER, KB_PRIMARY, KB_PRIMARY_BG, KB_PRIMARY_BORDER, KB_PRIMARY_SURFACE } from '@/lib/theme'

import { useEffect, useState } from 'react'
import PinManageModal from '@/components/PinManageModal'
import { api } from '@/lib/api'

/* ─── 공통 타입 ─── */
interface SettingsData {
  name: string; email: string | null; phone: string | null
  zipCode: string | null; address: string | null; addressDetail: string | null
  smsReceiveYn: boolean; emailReceiveYn: boolean; postalReceiveYn: boolean
}
interface PersonInfo {
  birthDate: string; genderCode: string; maritalStatusCode: string | null
  occupationCode: string | null; occupationName: string | null; workplaceName: string | null
  annualIncomeAmount: number | null; incomeProofCode: string | null
  isPep: boolean; pepTypeCode: string | null
}
interface AuthMethod {
  authMethodId: number; authMethodTypeCode: string; authMethodAliasName: string | null
  authMethodStatusCode: string; primary: boolean
  authMethodRegisteredDate: string; authMethodExpiryDate: string | null; lastUsedAt: string | null
}
interface ForeignerInfo {
  passportNo: string | null; passportCountryCode: string | null; passportExpiryDate: string | null
  stayQualificationCode: string | null; stayExpiryDate: string | null
  recentEntryDate: string | null; stayAddress: string | null
}
interface TaxResidency {
  taxResidencyId: number; residentTypeCode: string; taxCountryCode: string | null
  foreignTin: string | null; withholdingRateBps: number | null; taxResidencyConfirmDate: string
}

type Tab = '프로필 수정' | '비밀번호 변경' | '알림 설정' | '화면 설정' | '개인정보' | '인증수단' | '외국인정보' | '납세거주'
const TABS: Tab[] = ['프로필 수정', '비밀번호 변경', '알림 설정', '화면 설정', '개인정보', '인증수단', '외국인정보', '납세거주']

const inputCls   = "border rounded-lg px-3 py-2 text-[13px] outline-none focus:ring-1 transition-all"
const inputStyle = { borderColor: '#D1D5DB' }

const ROW_LABEL = "w-[180px] px-5 py-3 text-[13px] font-semibold flex-shrink-0 flex items-center"
const rowLabelStyle = { backgroundColor: KB_PRIMARY_BG, color: KB_PRIMARY, alignSelf: 'stretch' as const, display: 'flex' as const, alignItems: 'center' as const }

function Toggle({ checked, onChange }: { checked: boolean; onChange: () => void }) {
  return (
    <button onClick={onChange} className="relative w-10 h-5 rounded-full transition-colors flex-shrink-0"
      style={{ backgroundColor: checked ? KB_PRIMARY : '#D1D5DB' }}>
      <span className={`absolute top-0.5 w-4 h-4 bg-white rounded-full shadow transition-transform ${checked ? 'translate-x-5' : 'translate-x-0.5'}`} />
    </button>
  )
}
function SaveBar({ msg, isError, onSave, saving, label = '저장' }: { msg: string; isError: boolean; onSave: () => void; saving: boolean; label?: string }) {
  return (
    <div className="flex items-center gap-3 mt-6">
      <button onClick={onSave} disabled={saving}
        className="px-12 py-2.5 text-[14px] font-bold text-white rounded-xl hover:opacity-85 disabled:opacity-50" style={{ backgroundColor: KB_PRIMARY }}>
        {saving ? '저장 중...' : label}
      </button>
      {msg && <span className="text-[13px] font-medium" style={{ color: isError ? KB_DANGER : KB_PRIMARY }}>{msg}</span>}
    </div>
  )
}
function Card({ children }: { children: React.ReactNode }) {
  return <div className="rounded-xl overflow-hidden" style={{ border: `1px solid ${KB_PRIMARY_BORDER}` }}>{children}</div>
}
function SectionTitle({ children }: { children: React.ReactNode }) {
  return (
    <div className="px-5 py-3 text-[13px] font-semibold" style={{ backgroundColor: KB_PRIMARY_BG, borderBottom: `1px solid ${KB_PRIMARY_BORDER}`, color: KB_PRIMARY }}>
      {children}
    </div>
  )
}
function FieldRow({ label, children, last }: { label: string; children: React.ReactNode; last?: boolean }) {
  return (
    <div className="flex items-center" style={{ borderBottom: last ? 'none' : `1px solid ${KB_PRIMARY_BORDER}` }}>
      <div className={ROW_LABEL} style={rowLabelStyle}>{label}</div>
      <div className="flex-1 px-5 py-2.5">{children}</div>
    </div>
  )
}

/* ── 프로필 수정 ── */
function ProfileTab({ data }: { data: SettingsData }) {
  const [phone, setPhone] = useState(data.phone ?? '')
  const [email, setEmail] = useState(data.email ?? '')
  const [zipCode, setZipCode] = useState(data.zipCode ?? '')
  const [address, setAddress] = useState(data.address ?? '')
  const [addressDetail, setAddressDetail] = useState(data.addressDetail ?? '')
  const [saving, setSaving] = useState(false)
  const [msg, setMsg] = useState(''); const [isError, setIsError] = useState(false)

  async function handleSave() {
    setSaving(true); setMsg(''); setIsError(false)
    try { await api.put('/api/v1/customers/me/profile', { email, phone, zipCode, address, addressDetail }); setMsg('저장되었습니다.') }
    catch { setMsg('저장에 실패했습니다.'); setIsError(true) }
    finally { setSaving(false); setTimeout(() => setMsg(''), 3000) }
  }

  const rows = [
    { label: '휴대폰 번호', value: phone, setter: setPhone, type: 'tel' },
    { label: '이메일', value: email, setter: setEmail, type: 'email' },
    { label: '우편번호', value: zipCode, setter: setZipCode, type: 'text' },
    { label: '자택 주소', value: address, setter: setAddress, type: 'text' },
    { label: '상세 주소', value: addressDetail, setter: setAddressDetail, type: 'text' },
  ]
  return (
    <div>
      <Card>
        <div className="flex" style={{ borderBottom: `1px solid ${KB_PRIMARY_BORDER}`, backgroundColor: KB_PRIMARY_BG }}>
          <div className="w-[180px] px-5 py-3 text-[13px] font-semibold flex-shrink-0" style={{ color: KB_PRIMARY }}>이름</div>
          <div className="flex-1 px-5 py-3 text-[13px] text-kb-text">{data.name}</div>
        </div>
        {rows.map((row, i) => (
          <FieldRow key={row.label} label={row.label} last={i === rows.length - 1}>
            <input type={row.type} value={row.value} onChange={e => row.setter(e.target.value)}
              className={inputCls + ' w-full max-w-sm'} style={inputStyle} />
          </FieldRow>
        ))}
      </Card>
      <SaveBar msg={msg} isError={isError} onSave={handleSave} saving={saving} />
    </div>
  )
}

/* ── 비밀번호 변경 ── */
function PasswordTab() {
  const [current, setCurrent] = useState(''); const [next, setNext] = useState(''); const [confirm, setConfirm] = useState('')
  const [saving, setSaving] = useState(false); const [msg, setMsg] = useState(''); const [isError, setIsError] = useState(false)

  async function handleChange() {
    setMsg(''); setIsError(false)
    if (!current || !next || !confirm) { setMsg('모든 항목을 입력해 주세요.'); setIsError(true); return }
    if (next.length < 8) { setMsg('비밀번호는 8자 이상이어야 합니다.'); setIsError(true); return }
    if (next !== confirm) { setMsg('새 비밀번호가 일치하지 않습니다.'); setIsError(true); return }
    setSaving(true)
    try {
      await api.put('/api/v1/customers/me/password', { currentPassword: current, newPassword: next })
      setMsg('비밀번호가 변경되었습니다.'); setCurrent(''); setNext(''); setConfirm('')
    } catch (e: unknown) {
      const m = (e as { response?: { data?: { message?: string } } })?.response?.data?.message
      setMsg(m ?? '현재 비밀번호를 확인해주세요.'); setIsError(true)
    } finally { setSaving(false) }
  }

  return (
    <div>
      <div className="rounded-xl px-5 py-4 mb-5 text-[12px] space-y-1.5" style={{ backgroundColor: KB_PRIMARY_SURFACE, border: `1px solid ${KB_PRIMARY_BORDER}` }}>
        <p className="text-kb-text-muted">· 영문·숫자·특수문자를 포함하여 8~20자로 설정해 주세요.</p>
        <p className="text-kb-text-muted">· 90일마다 비밀번호 변경을 권장합니다.</p>
      </div>
      <Card>
        {[
          { label: '현재 비밀번호', value: current, setter: setCurrent },
          { label: '새 비밀번호', value: next, setter: setNext },
          { label: '새 비밀번호 확인', value: confirm, setter: setConfirm },
        ].map((row, i, arr) => (
          <FieldRow key={row.label} label={row.label} last={i === arr.length - 1}>
            <input type="password" value={row.value} onChange={e => row.setter(e.target.value)}
              placeholder="입력하세요" className={inputCls + ' w-full max-w-sm'} style={inputStyle} />
          </FieldRow>
        ))}
      </Card>
      <SaveBar msg={msg} isError={isError} onSave={handleChange} saving={saving} label="비밀번호 변경" />
    </div>
  )
}

/* ── 알림 설정 ── */
type NotifKey = 'smsReceiveYn' | 'emailReceiveYn' | 'postalReceiveYn'
const NOTIF_ITEMS: { key: NotifKey; label: string; desc: string }[] = [
  { key: 'smsReceiveYn', label: 'SMS 수신', desc: '이체·로그인 등 주요 거래 알림을 SMS로 받습니다.' },
  { key: 'emailReceiveYn', label: '이메일 수신', desc: '마케팅 및 이벤트 정보를 이메일로 받습니다.' },
  { key: 'postalReceiveYn', label: '우편 수신', desc: '금리 변동 등 금융 안내를 우편으로 받습니다.' },
]
function NotificationTab({ data }: { data: SettingsData }) {
  const [notifs, setNotifs] = useState<Record<NotifKey, boolean>>({
    smsReceiveYn: data.smsReceiveYn, emailReceiveYn: data.emailReceiveYn, postalReceiveYn: data.postalReceiveYn,
  })
  const [saving, setSaving] = useState(false); const [msg, setMsg] = useState(''); const [isError, setIsError] = useState(false)

  async function handleSave() {
    setSaving(true); setMsg(''); setIsError(false)
    try { await api.put('/api/v1/customers/me/notification', notifs); setMsg('알림설정이 저장되었습니다.') }
    catch { setMsg('저장에 실패했습니다.'); setIsError(true) }
    finally { setSaving(false); setTimeout(() => setMsg(''), 3000) }
  }
  return (
    <div>
      <Card>
        {NOTIF_ITEMS.map((item, i, arr) => (
          <div key={item.key} className="flex items-center justify-between px-5 py-4"
            style={{ borderBottom: i < arr.length - 1 ? `1px solid ${KB_PRIMARY_BORDER}` : 'none' }}>
            <div>
              <p className="text-[14px] font-semibold text-kb-text">{item.label}</p>
              <p className="text-[12px] text-kb-text-muted mt-0.5">{item.desc}</p>
            </div>
            <Toggle checked={notifs[item.key]} onChange={() => setNotifs(p => ({ ...p, [item.key]: !p[item.key] }))} />
          </div>
        ))}
      </Card>
      <SaveBar msg={msg} isError={isError} onSave={handleSave} saving={saving} />
    </div>
  )
}

/* ── 화면 설정 ── */
type FontSize = '소' | '중' | '대'
type ColorTheme = '기본' | '고대비'
function ScreenTab() {
  const [fontSize, setFontSize] = useState<FontSize>('중')
  const [colorTheme, setColorTheme] = useState<ColorTheme>('기본')
  const [mainPageLayout, setMainPageLayout] = useState('기본형')
  const [quickMenuVisible, setQuickMenuVisible] = useState(true)
  const [msg, setMsg] = useState('')

  useEffect(() => {
    try {
      const s = JSON.parse(localStorage.getItem('screenSettings') || '{}')
      if (s.fontSize) setFontSize(s.fontSize)
      if (s.colorTheme) setColorTheme(s.colorTheme)
      if (s.mainPageLayout) setMainPageLayout(s.mainPageLayout)
      if (s.quickMenuVisible !== undefined) setQuickMenuVisible(s.quickMenuVisible)
    } catch {}
  }, [])

  function handleSave() {
    localStorage.setItem('screenSettings', JSON.stringify({ fontSize, colorTheme, mainPageLayout, quickMenuVisible }))
    setMsg('저장되었습니다.'); setTimeout(() => setMsg(''), 2000)
  }
  const btn = (active: boolean, onClick: () => void, children: React.ReactNode) => (
    <button onClick={onClick} className="border rounded-lg px-7 py-2 text-[13px] font-medium transition-colors"
      style={active ? { backgroundColor: KB_PRIMARY, borderColor: KB_PRIMARY, color: 'white', fontWeight: 700 } : { borderColor: KB_PRIMARY_BORDER, color: '#6B7280' }}>
      {children}
    </button>
  )
  return (
    <div className="space-y-6">
      <div>
        <h3 className="text-[14px] font-bold text-kb-text mb-3">글자 크기</h3>
        <div className="flex gap-2">
          {(['소', '중', '대'] as FontSize[]).map(s => btn(fontSize === s, () => setFontSize(s),
            <span style={{ fontSize: s === '소' ? '12px' : s === '중' ? '14px' : '16px' }}>가 ({s})</span>
          ))}
        </div>
      </div>
      <div>
        <h3 className="text-[14px] font-bold text-kb-text mb-3">색상 테마</h3>
        <div className="flex gap-2">
          {(['기본', '고대비'] as ColorTheme[]).map(t => btn(colorTheme === t, () => setColorTheme(t), t))}
        </div>
      </div>
      <div>
        <h3 className="text-[14px] font-bold text-kb-text mb-3">메인 화면 레이아웃</h3>
        <div className="flex gap-2">
          {['기본형', '간편형'].map(l => btn(mainPageLayout === l, () => setMainPageLayout(l), l))}
        </div>
      </div>
      <div className="flex items-center justify-between rounded-xl px-5 py-4" style={{ border: `1px solid ${KB_PRIMARY_BORDER}` }}>
        <div>
          <p className="text-[14px] font-semibold text-kb-text">퀵메뉴 표시</p>
          <p className="text-[12px] text-kb-text-muted mt-0.5">메인 화면 상단 퀵메뉴 바를 표시합니다.</p>
        </div>
        <Toggle checked={quickMenuVisible} onChange={() => setQuickMenuVisible(v => !v)} />
      </div>
      <div className="flex items-center gap-3">
        <button onClick={handleSave} className="px-12 py-2.5 text-[14px] font-bold text-white rounded-xl hover:opacity-85" style={{ backgroundColor: KB_PRIMARY }}>저장</button>
        {msg && <span className="text-[13px] font-medium" style={{ color: KB_PRIMARY }}>{msg}</span>}
      </div>
    </div>
  )
}

/* ── 개인정보 ── */
const OCCUPATION_CODES = [
  { code: '', label: '선택하세요' }, { code: 'EMPLOYEE', label: '직장인' }, { code: 'SELF_EMPLOYED', label: '자영업' },
  { code: 'FREELANCER', label: '프리랜서' }, { code: 'HOUSEWIFE', label: '주부' }, { code: 'STUDENT', label: '학생' }, { code: 'UNEMPLOYED', label: '무직' },
]
const MARITAL_CODES = [
  { code: '', label: '선택하세요' }, { code: 'SINGLE', label: '미혼' }, { code: 'MARRIED', label: '기혼' }, { code: 'DIVORCED', label: '이혼' },
]
function PersonInfoTab() {
  const [info, setInfo] = useState<PersonInfo | null>(null)
  const [form, setForm] = useState({ occupationCode: '', occupationName: '', workplaceName: '', annualIncomeAmount: '', incomeProofCode: '', maritalStatusCode: '' })
  const [saving, setSaving] = useState(false); const [msg, setMsg] = useState(''); const [isError, setIsError] = useState(false)

  useEffect(() => {
    api.get('/api/v1/customers/me/person-info').then(res => {
      const d: PersonInfo = res.data.data
      setInfo(d)
      setForm({
        occupationCode: d.occupationCode ?? '', occupationName: d.occupationName ?? '',
        workplaceName: d.workplaceName ?? '',
        annualIncomeAmount: d.annualIncomeAmount ? String(d.annualIncomeAmount) : '',
        incomeProofCode: d.incomeProofCode ?? '', maritalStatusCode: d.maritalStatusCode ?? '',
      })
    }).catch(() => {})
  }, [])

  async function handleSave() {
    setSaving(true); setMsg(''); setIsError(false)
    try {
      await api.put('/api/v1/customers/me/person-info', {
        occupationCode: form.occupationCode || null, occupationName: form.occupationName || null,
        workplaceName: form.workplaceName || null,
        annualIncomeAmount: form.annualIncomeAmount ? Number(form.annualIncomeAmount) : null,
        incomeProofCode: form.incomeProofCode || null, maritalStatusCode: form.maritalStatusCode || null,
      })
      setMsg('저장되었습니다.')
    } catch { setMsg('저장에 실패했습니다.'); setIsError(true) }
    finally { setSaving(false); setTimeout(() => setMsg(''), 3000) }
  }

  if (!info) return <p className="text-[13px] text-kb-text-muted">불러오는 중...</p>

  return (
    <div>
      {/* 변경 불가 정보 */}
      <p className="text-[12px] text-kb-text-muted mb-3">생년월일·성별은 등록된 정보이며 수정되지 않습니다.</p>
      <Card>
        <SectionTitle>기본 인적사항</SectionTitle>
        <FieldRow label="생년월일">
          <span className="text-[13px] text-kb-text-muted">
            {info.birthDate ? `${info.birthDate.slice(0,4)}.${info.birthDate.slice(4,6)}.${info.birthDate.slice(6,8)}` : '-'}
          </span>
        </FieldRow>
        <FieldRow label="성별" last>
          <span className="text-[13px] text-kb-text-muted">
            {info.genderCode === 'M' ? '남성' : info.genderCode === 'F' ? '여성' : '-'}
          </span>
        </FieldRow>
      </Card>

      {/* 수정 가능 정보 */}
      <div className="mt-4">
        <Card>
          <SectionTitle>직업·소득 정보 (수정 가능)</SectionTitle>
          <FieldRow label="결혼 여부">
            <select value={form.maritalStatusCode} onChange={e => setForm(p => ({ ...p, maritalStatusCode: e.target.value }))}
              className={inputCls + ' w-full max-w-xs'} style={inputStyle}>
              {MARITAL_CODES.map(c => <option key={c.code} value={c.code}>{c.label}</option>)}
            </select>
          </FieldRow>
          <FieldRow label="직업 구분">
            <select value={form.occupationCode} onChange={e => setForm(p => ({ ...p, occupationCode: e.target.value }))}
              className={inputCls + ' w-full max-w-xs'} style={inputStyle}>
              {OCCUPATION_CODES.map(c => <option key={c.code} value={c.code}>{c.label}</option>)}
            </select>
          </FieldRow>
          <FieldRow label="직업명">
            <input value={form.occupationName} onChange={e => setForm(p => ({ ...p, occupationName: e.target.value }))}
              placeholder="직업명 입력" className={inputCls + ' w-full max-w-sm'} style={inputStyle} />
          </FieldRow>
          <FieldRow label="직장명">
            <input value={form.workplaceName} onChange={e => setForm(p => ({ ...p, workplaceName: e.target.value }))}
              placeholder="직장명 입력" className={inputCls + ' w-full max-w-sm'} style={inputStyle} />
          </FieldRow>
          <FieldRow label="연간 소득 (원)" last>
            <input type="number" value={form.annualIncomeAmount} onChange={e => setForm(p => ({ ...p, annualIncomeAmount: e.target.value }))}
              placeholder="예: 40000000" className={inputCls + ' w-full max-w-sm'} style={inputStyle} />
          </FieldRow>
        </Card>
      </div>
      <SaveBar msg={msg} isError={isError} onSave={handleSave} saving={saving} />
    </div>
  )
}

/* ── 인증수단 ── */
const METHOD_LABEL: Record<string, string> = {
  SMS: 'SMS', PASS: 'PASS 앱', CERT_FIN: '금융인증서', CERT_COMMON: '공동인증서', PIN: 'PIN', BIO_FACE: '얼굴인식', BIO_FINGER: '지문인식',
}
function AuthMethodTab() {
  const [methods, setMethods] = useState<AuthMethod[]>([])
  const [loading, setLoading] = useState(true)
  const [editAlias, setEditAlias] = useState<{ id: number; value: string } | null>(null)
  const [msg, setMsg] = useState(''); const [isError, setIsError] = useState(false)
  const [showPinModal, setShowPinModal] = useState(false)

  function reload() {
    setLoading(true)
    api.get('/api/v1/customers/me/auth-methods').then(res => setMethods(res.data.data)).catch(() => {}).finally(() => setLoading(false))
  }
  useEffect(() => { reload() }, [])

  async function saveAlias(id: number, alias: string) {
    try {
      await api.patch(`/api/v1/customers/me/auth-methods/${id}/alias?alias=${encodeURIComponent(alias)}`)
      setMsg('별칭이 저장되었습니다.'); setEditAlias(null); reload()
    } catch { setMsg('저장에 실패했습니다.'); setIsError(true) }
    finally { setTimeout(() => { setMsg(''); setIsError(false) }, 3000) }
  }
  async function setPrimary(id: number) {
    try { await api.patch(`/api/v1/customers/me/auth-methods/${id}/primary`); setMsg('주 인증수단이 변경되었습니다.'); reload() }
    catch { setMsg('변경에 실패했습니다.'); setIsError(true) }
    finally { setTimeout(() => { setMsg(''); setIsError(false) }, 3000) }
  }
  async function deactivate(id: number) {
    if (!confirm('해당 인증수단을 비활성화하시겠습니까?')) return
    try { await api.delete(`/api/v1/customers/me/auth-methods/${id}`); setMsg('비활성화되었습니다.'); reload() }
    catch (e: unknown) {
      const m = (e as { response?: { data?: { message?: string } } })?.response?.data?.message
      setMsg(m ?? '비활성화에 실패했습니다.'); setIsError(true)
    }
    finally { setTimeout(() => { setMsg(''); setIsError(false) }, 3000) }
  }

  return (
    <div>
      {/* 간편비밀번호(PIN) — 모달로 등록/해제 */}
      <button type="button" onClick={() => setShowPinModal(true)}
        className="w-full flex items-center justify-between rounded-xl px-5 py-4 mb-4 hover:bg-kb-primary-bg transition-colors text-left"
        style={{ border: `1px solid ${KB_PRIMARY_BORDER}` }}>
        <div>
          <p className="text-[14px] font-bold text-kb-text">간편비밀번호(PIN)</p>
          <p className="text-[12px] text-kb-text-muted mt-0.5">이 기기에 PIN을 등록하면 아이디·비밀번호 없이 간편하게 로그인할 수 있습니다.</p>
        </div>
        <span className="text-[13px] font-semibold flex-shrink-0 ml-4" style={{ color: KB_PRIMARY }}>등록·관리 ›</span>
      </button>
      {showPinModal && <PinManageModal onClose={() => setShowPinModal(false)} onChanged={reload} />}

      {msg && <p className="mb-3 text-[13px] font-medium" style={{ color: isError ? KB_DANGER : KB_PRIMARY }}>{msg}</p>}
      {loading ? (
        <p className="text-[13px] text-kb-text-muted">불러오는 중...</p>
      ) : methods.length === 0 ? (
        <p className="text-[13px] text-kb-text-muted">등록된 인증수단이 없습니다.</p>
      ) : (
      <div className="space-y-3">
        {methods.map(m => (
          <div key={m.authMethodId} className="rounded-xl px-5 py-4" style={{ border: `1px solid ${KB_PRIMARY_BORDER}` }}>
            <div className="flex items-start justify-between gap-4">
              <div className="flex-1">
                <div className="flex items-center gap-2 mb-1">
                  <span className="text-[14px] font-bold text-kb-text">{METHOD_LABEL[m.authMethodTypeCode] ?? m.authMethodTypeCode}</span>
                  {m.primary && (
                    <span className="text-[11px] font-bold px-2 py-0.5 rounded-full" style={{ backgroundColor: KB_PRIMARY, color: 'white' }}>주</span>
                  )}
                  <span className="text-[11px] px-2 py-0.5 rounded-full"
                    style={{ backgroundColor: m.authMethodStatusCode === 'ACTIVE' ? KB_PRIMARY_BG : '#FEE2E2', color: m.authMethodStatusCode === 'ACTIVE' ? KB_PRIMARY : KB_DANGER }}>
                    {m.authMethodStatusCode === 'ACTIVE' ? '활성' : '비활성'}
                  </span>
                </div>
                {editAlias?.id === m.authMethodId ? (
                  <div className="flex items-center gap-2 mt-1">
                    <input value={editAlias.value} onChange={e => setEditAlias(p => p ? { ...p, value: e.target.value } : null)}
                      className={inputCls + ' w-48 text-[12px]'} style={inputStyle} placeholder="별칭 입력" />
                    <button onClick={() => saveAlias(m.authMethodId, editAlias.value)}
                      className="text-[12px] font-bold px-3 py-1 rounded text-white" style={{ backgroundColor: KB_PRIMARY }}>저장</button>
                    <button onClick={() => setEditAlias(null)} className="text-[12px] text-kb-text-muted">취소</button>
                  </div>
                ) : (
                  <p className="text-[12px] text-kb-text-muted">
                    {m.authMethodAliasName ?? '별칭 없음'}
                    <button onClick={() => setEditAlias({ id: m.authMethodId, value: m.authMethodAliasName ?? '' })}
                      className="ml-2 underline hover:no-underline">수정</button>
                  </p>
                )}
                <p className="text-[11px] text-kb-text-muted mt-1">등록일: {m.authMethodRegisteredDate?.slice(0,4)}.{m.authMethodRegisteredDate?.slice(4,6)}.{m.authMethodRegisteredDate?.slice(6,8)}</p>
              </div>
              <div className="flex flex-col gap-2 flex-shrink-0">
                {!m.primary && m.authMethodStatusCode === 'ACTIVE' && (
                  <button onClick={() => setPrimary(m.authMethodId)}
                    className="border text-[12px] px-3 py-1.5 rounded-lg hover:bg-kb-primary-bg" style={{ borderColor: KB_PRIMARY, color: KB_PRIMARY }}>
                    주 인증수단 설정
                  </button>
                )}
                {m.authMethodStatusCode === 'ACTIVE' && !m.primary && (
                  <button onClick={() => deactivate(m.authMethodId)}
                    className="border text-[12px] px-3 py-1.5 rounded-lg hover:bg-red-50" style={{ borderColor: KB_DANGER, color: KB_DANGER }}>
                    비활성화
                  </button>
                )}
              </div>
            </div>
          </div>
        ))}
      </div>
      )}
    </div>
  )
}

/* ── 외국인정보 ── */
function ForeignerInfoTab() {
  const [info, setInfo] = useState<ForeignerInfo | null>(null)
  const [passportForm, setPassportForm] = useState({ passportNo: '', countryCode: '', expiryDate: '' })
  const [stayForm, setStayForm] = useState({ stayQualificationCode: '', stayExpiryDate: '' })
  const [passportMsg, setPassportMsg] = useState(''); const [passportError, setPassportError] = useState(false)
  const [stayMsg, setStayMsg] = useState(''); const [stayError, setStayError] = useState(false)
  const [saving1, setSaving1] = useState(false); const [saving2, setSaving2] = useState(false)

  useEffect(() => {
    api.get('/api/v1/customers/me/foreigner-info').then(res => {
      const d: ForeignerInfo = res.data.data
      setInfo(d)
      setPassportForm({ passportNo: d.passportNo ?? '', countryCode: d.passportCountryCode ?? '', expiryDate: d.passportExpiryDate ?? '' })
      setStayForm({ stayQualificationCode: d.stayQualificationCode ?? '', stayExpiryDate: d.stayExpiryDate ?? '' })
    }).catch(() => setInfo({} as ForeignerInfo))
  }, [])

  async function savePassport() {
    setSaving1(true); setPassportMsg(''); setPassportError(false)
    try {
      await api.put('/api/v1/customers/me/foreigner-info/passport', {
        passportNo: passportForm.passportNo, countryCode: passportForm.countryCode, expiryDate: passportForm.expiryDate,
      })
      setPassportMsg('여권 정보가 저장되었습니다.')
    } catch { setPassportMsg('저장에 실패했습니다.'); setPassportError(true) }
    finally { setSaving1(false); setTimeout(() => setPassportMsg(''), 3000) }
  }
  async function saveStay() {
    setSaving2(true); setStayMsg(''); setStayError(false)
    try {
      await api.put('/api/v1/customers/me/foreigner-info/stay', {
        stayQualificationCode: stayForm.stayQualificationCode, stayExpiryDate: stayForm.stayExpiryDate,
      })
      setStayMsg('체류 정보가 저장되었습니다.')
    } catch { setStayMsg('저장에 실패했습니다.'); setStayError(true) }
    finally { setSaving2(false); setTimeout(() => setStayMsg(''), 3000) }
  }

  if (info === null) return <p className="text-[13px] text-kb-text-muted">불러오는 중...</p>

  return (
    <div className="space-y-6">
      {/* 여권 */}
      <div>
        <Card>
          <SectionTitle>여권 정보</SectionTitle>
          <FieldRow label="여권 번호">
            <input value={passportForm.passportNo} onChange={e => setPassportForm(p => ({ ...p, passportNo: e.target.value }))}
              placeholder="M12345678" className={inputCls + ' w-full max-w-xs'} style={inputStyle} />
          </FieldRow>
          <FieldRow label="발행 국가">
            <input value={passportForm.countryCode} onChange={e => setPassportForm(p => ({ ...p, countryCode: e.target.value }))}
              placeholder="USA" maxLength={3} className={inputCls + ' w-24'} style={inputStyle} />
            <span className="text-[11px] text-kb-text-muted ml-2">ISO 3166-1 alpha-3 (예: KOR, USA)</span>
          </FieldRow>
          <FieldRow label="만료일" last>
            <input value={passportForm.expiryDate} onChange={e => setPassportForm(p => ({ ...p, expiryDate: e.target.value }))}
              placeholder="20301231" maxLength={8} className={inputCls + ' w-36'} style={inputStyle} />
            <span className="text-[11px] text-kb-text-muted ml-2">YYYYMMDD</span>
          </FieldRow>
        </Card>
        <SaveBar msg={passportMsg} isError={passportError} onSave={savePassport} saving={saving1} label="여권 정보 저장" />
      </div>

      {/* 체류 */}
      <div>
        <Card>
          <SectionTitle>체류 정보</SectionTitle>
          <FieldRow label="체류 자격">
            <input value={stayForm.stayQualificationCode} onChange={e => setStayForm(p => ({ ...p, stayQualificationCode: e.target.value }))}
              placeholder="F2, D8 등" className={inputCls + ' w-32'} style={inputStyle} />
          </FieldRow>
          <FieldRow label="체류 만료일" last>
            <input value={stayForm.stayExpiryDate} onChange={e => setStayForm(p => ({ ...p, stayExpiryDate: e.target.value }))}
              placeholder="20271231" maxLength={8} className={inputCls + ' w-36'} style={inputStyle} />
            <span className="text-[11px] text-kb-text-muted ml-2">YYYYMMDD</span>
          </FieldRow>
        </Card>
        <SaveBar msg={stayMsg} isError={stayError} onSave={saveStay} saving={saving2} label="체류 정보 저장" />
      </div>
    </div>
  )
}

/* ── 납세거주 ── */
const RESIDENT_CODES = [
  { code: 'DOMESTIC', label: '국내 거주자' }, { code: 'FOREIGN', label: '해외 거주자' }, { code: 'DUAL', label: '이중 거주자' },
]
function TaxResidencyTab() {
  const [list, setList] = useState<TaxResidency[]>([])
  const [form, setForm] = useState({ residentTypeCode: 'DOMESTIC', taxCountryCode: '', foreignTin: '', taxResidencyConfirmDate: '' })
  const [showForm, setShowForm] = useState(false)
  const [saving, setSaving] = useState(false); const [msg, setMsg] = useState(''); const [isError, setIsError] = useState(false)

  function reload() {
    api.get('/api/v1/customers/me/tax-residencies').then(res => setList(res.data.data)).catch(() => {})
  }
  useEffect(() => { reload() }, [])

  async function handleAdd() {
    if (!form.taxResidencyConfirmDate) { setMsg('확인일자를 입력해주세요.'); setIsError(true); return }
    setSaving(true); setMsg(''); setIsError(false)
    try {
      await api.post('/api/v1/customers/me/tax-residencies', {
        residentTypeCode: form.residentTypeCode,
        taxCountryCode: form.taxCountryCode || null,
        foreignTin: form.foreignTin || null,
        taxResidencyConfirmDate: form.taxResidencyConfirmDate,
      })
      setMsg('납세거주 정보가 추가되었습니다.'); setShowForm(false)
      setForm({ residentTypeCode: 'DOMESTIC', taxCountryCode: '', foreignTin: '', taxResidencyConfirmDate: '' })
      reload()
    } catch { setMsg('추가에 실패했습니다.'); setIsError(true) }
    finally { setSaving(false); setTimeout(() => { setMsg(''); setIsError(false) }, 3000) }
  }
  async function handleDelete(id: number) {
    if (!confirm('이 납세거주 정보를 삭제하시겠습니까?')) return
    try { await api.delete(`/api/v1/customers/me/tax-residencies/${id}`); setMsg('삭제되었습니다.'); reload() }
    catch { setMsg('삭제에 실패했습니다.'); setIsError(true) }
    finally { setTimeout(() => { setMsg(''); setIsError(false) }, 3000) }
  }

  const residentLabel = (code: string) => RESIDENT_CODES.find(r => r.code === code)?.label ?? code

  return (
    <div className="space-y-4">
      {msg && <p className="text-[13px] font-medium" style={{ color: isError ? KB_DANGER : KB_PRIMARY }}>{msg}</p>}

      {list.length === 0 ? (
        <div className="rounded-xl px-5 py-8 text-center text-[13px] text-kb-text-muted" style={{ border: `1px solid ${KB_PRIMARY_BORDER}` }}>
          등록된 납세거주 정보가 없습니다.
        </div>
      ) : (
        <div className="space-y-3">
          {list.map(t => (
            <div key={t.taxResidencyId} className="rounded-xl px-5 py-4 flex items-start justify-between" style={{ border: `1px solid ${KB_PRIMARY_BORDER}` }}>
              <div>
                <p className="text-[14px] font-bold text-kb-text">{residentLabel(t.residentTypeCode)}</p>
                {t.taxCountryCode && <p className="text-[12px] text-kb-text-muted mt-0.5">국가: {t.taxCountryCode}</p>}
                {t.foreignTin && <p className="text-[12px] text-kb-text-muted">TIN: {t.foreignTin}</p>}
                <p className="text-[11px] text-kb-text-muted mt-1">확인일: {t.taxResidencyConfirmDate?.slice(0,4)}.{t.taxResidencyConfirmDate?.slice(4,6)}.{t.taxResidencyConfirmDate?.slice(6,8)}</p>
              </div>
              <button onClick={() => handleDelete(t.taxResidencyId)}
                className="border text-[12px] px-3 py-1.5 rounded-lg hover:bg-red-50 flex-shrink-0" style={{ borderColor: KB_DANGER, color: KB_DANGER }}>
                삭제
              </button>
            </div>
          ))}
        </div>
      )}

      {!showForm ? (
        <button onClick={() => setShowForm(true)}
          className="w-full py-3 text-[13px] font-bold rounded-xl border-2 border-dashed hover:bg-kb-primary-bg transition-colors"
          style={{ borderColor: KB_PRIMARY, color: KB_PRIMARY }}>
          + 납세거주 정보 추가
        </button>
      ) : (
        <div className="rounded-xl overflow-hidden" style={{ border: `1px solid ${KB_PRIMARY}` }}>
          <SectionTitle>납세거주 추가</SectionTitle>
          <div className="p-5 space-y-3">
            <div className="flex items-center gap-3">
              <label className="text-[13px] font-medium w-28 flex-shrink-0">거주 유형</label>
              <select value={form.residentTypeCode} onChange={e => setForm(p => ({ ...p, residentTypeCode: e.target.value }))}
                className={inputCls + ' flex-1 max-w-xs'} style={inputStyle}>
                {RESIDENT_CODES.map(r => <option key={r.code} value={r.code}>{r.label}</option>)}
              </select>
            </div>
            <div className="flex items-center gap-3">
              <label className="text-[13px] font-medium w-28 flex-shrink-0">국가 코드</label>
              <input value={form.taxCountryCode} onChange={e => setForm(p => ({ ...p, taxCountryCode: e.target.value }))}
                placeholder="예: USA" maxLength={3} className={inputCls + ' w-24'} style={inputStyle} />
            </div>
            <div className="flex items-center gap-3">
              <label className="text-[13px] font-medium w-28 flex-shrink-0">외국 TIN</label>
              <input value={form.foreignTin} onChange={e => setForm(p => ({ ...p, foreignTin: e.target.value }))}
                placeholder="납세자번호 (선택)" className={inputCls + ' flex-1 max-w-sm'} style={inputStyle} />
            </div>
            <div className="flex items-center gap-3">
              <label className="text-[13px] font-medium w-28 flex-shrink-0">확인일자 <span className="text-kb-danger">*</span></label>
              <input value={form.taxResidencyConfirmDate} onChange={e => setForm(p => ({ ...p, taxResidencyConfirmDate: e.target.value }))}
                placeholder="20260601" maxLength={8} className={inputCls + ' w-36'} style={inputStyle} />
              <span className="text-[11px] text-kb-text-muted">YYYYMMDD</span>
            </div>
            <div className="flex gap-2 pt-2">
              <button onClick={handleAdd} disabled={saving}
                className="px-8 py-2 text-[13px] font-bold text-white rounded-xl hover:opacity-85 disabled:opacity-50" style={{ backgroundColor: KB_PRIMARY }}>
                {saving ? '추가 중...' : '추가'}
              </button>
              <button onClick={() => setShowForm(false)}
                className="border rounded-xl px-6 py-2 text-[13px] hover:bg-gray-50" style={{ borderColor: '#D1D5DB', color: '#6B7280' }}>
                취소
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  )
}

/* ── 메인 ── */
export default function SettingsPage() {
  const [activeTab, setActiveTab] = useState<Tab>('프로필 수정')
  const [settingsData, setSettingsData] = useState<SettingsData | null>(null)
  const [loadError, setLoadError] = useState('')

  useEffect(() => {
    api.get('/api/v1/customers/me/settings')
      .then(res => setSettingsData(res.data.data))
      .catch(() => setLoadError('설정 정보를 불러오지 못했습니다.'))
  }, [])

  // 설정 데이터 불필요한 탭
  const SETTINGS_FREE_TABS: Tab[] = ['개인정보', '인증수단', '외국인정보', '납세거주']

  return (
    <div className="max-w-kb-container mx-auto px-6 py-10 pb-16">
      <div className="flex justify-end mb-4 text-[12px] text-kb-text-muted gap-1">
        <span>개인뱅킹</span><span>›</span>
        <span className="font-semibold text-kb-text">설정</span>
      </div>
      <h1 className="text-[22px] font-bold text-kb-text mb-6">설정</h1>

      {/* 탭 */}
      <div className="flex flex-wrap border-b mb-6" style={{ borderColor: KB_PRIMARY_BORDER }}>
        {TABS.map(tab => (
          <button key={tab} onClick={() => setActiveTab(tab)}
            className="px-5 py-3 text-[13px] font-medium border-b-2 -mb-px transition-colors whitespace-nowrap"
            style={activeTab === tab ? { borderColor: KB_PRIMARY, color: KB_PRIMARY, fontWeight: 700 } : { borderColor: 'transparent', color: '#9CA3AF' }}>
            {tab}
          </button>
        ))}
      </div>

      {/* 컨텐츠 */}
      {loadError && <p className="text-[13px]" style={{ color: KB_DANGER }}>{loadError}</p>}

      {/* 설정 데이터 필요 없는 탭 */}
      {SETTINGS_FREE_TABS.includes(activeTab) && (
        <>
          {activeTab === '개인정보'  && <PersonInfoTab />}
          {activeTab === '인증수단'  && <AuthMethodTab />}
          {activeTab === '외국인정보' && <ForeignerInfoTab />}
          {activeTab === '납세거주'  && <TaxResidencyTab />}
        </>
      )}

      {/* 설정 데이터 필요한 탭 */}
      {!SETTINGS_FREE_TABS.includes(activeTab) && (
        <>
          {!settingsData && !loadError && <p className="text-[13px] text-kb-text-muted">불러오는 중...</p>}
          {settingsData && (
            <>
              {activeTab === '프로필 수정'  && <ProfileTab data={settingsData} />}
              {activeTab === '비밀번호 변경' && <PasswordTab />}
              {activeTab === '알림 설정'    && <NotificationTab data={settingsData} />}
              {activeTab === '화면 설정'    && <ScreenTab />}
            </>
          )}
        </>
      )}
    </div>
  )
}
