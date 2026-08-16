'use client'
import { KB_PRIMARY } from '@/lib/theme'

import Link from 'next/link'
import { useState, useEffect } from 'react'
import { useParams, useRouter } from 'next/navigation'
import DepositSidebar from '@/components/products/DepositSidebar'
import AutoBreadcrumb from '@/components/layout/AutoBreadcrumb'
import { createDepositContract, getCurrentDepositCustomerId, fetchDepositAccountViewModels, fetchDepositProduct, DepositViewAccount } from '@/lib/deposit-api'
import { formatNumber } from '@/lib/mock-data'
import MouseNumKeypad from '@/components/ui/MouseNumKeypad'

const SESSION_EXTENSION_MS = 10 * 60 * 1000

function extendLocalSessionAfterAuthenticatedAction() {
  const token = localStorage.getItem('accessToken') || localStorage.getItem('access_token')
  if (!token) return
  localStorage.setItem('sessionExpiry', String(Date.now() + SESSION_EXTENSION_MS))
}

const PRODUCT_NAMES: Record<string, string> = {
  // 예금
  'axful-regular': 'AXful 정기예금',
  'axful-super': 'AXful 수퍼정기예금(개인)',
  'regular': '일반정기예금',
  'axful-youth': 'AXful 청년도약계좌',
  // 자유적금
  'axful-free': 'AXful 내맘대로적금',
  'axful-dollar': 'AXful 달러자적금',
  'axful-green': 'AXful 맑은하늘적금',
  'axful-star-savings': 'AXful 특★한 적금',
  // 정기적금
  'axful-soldier': 'AXful 장병내일준비적금',
  'axful-work': 'AXful 직장인우대적금',
  'axful-dream': 'AXful 꿈적금',
  'axful-together': 'AXful 함께적금',
  // 입출금자유
  'axful-free-account': 'AXful 자유입출금통장',
  'axful-youth-account': 'AXful 청년우대통장',
  'axful-sok': 'AXful 쏙머니통장',
  'monimo-daily': '모니모 AXful 매일이자 통장',
  // 주택청약
  'housing-savings': '주택청약종합저축',
  'youth-housing': '청년 주택드림 청약통장',
}

// 적금 상품 ID (전체)
const SAVINGS_IDS = new Set([
  'axful-free', 'axful-dollar', 'axful-green', 'axful-soldier', 'axful-star-savings',
  'axful-work', 'axful-dream', 'axful-together',
])
// 자유적금 ID (납입 자유)
const FREE_SAVINGS_IDS = new Set([
  'axful-free', 'axful-dollar', 'axful-green', 'axful-star-savings',
])
// 정기적금 ID (월 고정 납입)
const REGULAR_SAVINGS_IDS = new Set([
  'axful-soldier', 'axful-work', 'axful-dream', 'axful-together',
])

const HOUSING_IDS = new Set([
  'housing-savings', 'youth-housing',
])

const CHECKING_IDS = new Set([
  'axful-free-account', 'axful-youth-account', 'axful-sok', 'monimo-daily',
  'axful-living', 'axful-gs', 'axful-moim', 'axful-star-account', 'axful-wallet', 'election',
])

// 적금별 가입기간 범위
const SAVINGS_PERIOD_RANGE: Record<string, { min: number; max: number; label: string }> = {
  'axful-free':         { min: 6,  max: 36, label: '6~36개월, 월단위' },
  'axful-dollar':       { min: 6,  max: 6,  label: '6개월 고정' },
  'axful-green':        { min: 6,  max: 36, label: '6~36개월, 월단위' },
  'axful-soldier':      { min: 24, max: 24, label: '24개월 고정' },
  'axful-work':         { min: 12, max: 36, label: '12~36개월, 월단위' },
  'axful-dream':        { min: 12, max: 36, label: '12~36개월, 월단위' },
  'axful-together':     { min: 6,  max: 24, label: '6~24개월, 월단위' },
  'axful-star-savings': { min: 1,  max: 12, label: '1~12개월, 월단위' },
}

const PRODUCT_RATES: Record<string, string> = {
  'axful-regular':       '연 2.4%',
  'axful-super':         '연 2.2%',
  'regular':             '연 2.25%',
  'axful-youth':         '연 3.5%',
  'axful-free':          '연 2.95%',
  'axful-dollar':        '연 1.0%',
  'axful-green':         '연 2.85%',
  'axful-star-savings':  '연 2.0%',
  'axful-soldier':       '연 5.0% + 우대 최대 5.5%',
  'axful-work':          '연 3.2% + 우대 최대 1.3%',
  'axful-dream':         '연 3.0% + 우대 최대 1.2%',
  'axful-together':      '연 2.8% + 우대 최대 1.2%',
  'axful-youth-account': '연 2.0%',
  'axful-sok':           '연 1.5%',
  'monimo-daily':        '연 2.5% (일 복리)',
  'housing-savings':     '연 3.1%',
  'youth-housing':       '연 3.1% + 우대 최대 1.4%',
}

const TERMS_BY_TYPE: Record<string, string[]> = {
  deposit: [
    '예금거래기본약관',
    '거치식예금약관',
    'AXful Star 정기예금 특약',
    'AXful Star 정기예금 상품설명서',
  ],
  savings: [
    '예금거래기본약관',
    '적립식예금약관',
    'AXful 적금 특약',
    'AXful 적금 상품설명서',
  ],
  checking: [
    '예금거래기본약관',
    '보통예금약관',
    'AXful 입출금통장 상품설명서',
  ],
  housing: [
    '예금거래기본약관',
    '주택청약종합저축약관',
    '주택청약종합저축 상품설명서',
  ],
}

const MATURITY_OPTIONS = [
  '자동재예치(원금+이자)',
  '자동재예치(원금)',
  '자동해지',
]

/* ─── 아코디언 아이템 ─── */
function AccItem({ title, required, checked, onCheck, children, defaultOpen = false }: {
  title: string; required?: boolean; checked?: boolean; onCheck?: (v: boolean) => void
  children: React.ReactNode; defaultOpen?: boolean
}) {
  const [open, setOpen] = useState(defaultOpen)
  return (
    <div className="border-b border-kb-border">
      <div className="flex items-center w-full px-4 py-3 gap-3">
        {required && onCheck !== undefined && (
          <input
            type="checkbox"
            checked={!!checked}
            onChange={e => onCheck(e.target.checked)}
            className="w-4 h-4 flex-shrink-0 accent-kb-primary cursor-pointer"
          />
        )}
        <button
          onClick={() => setOpen(v => !v)}
          className="flex items-center justify-between flex-1 hover:opacity-80 transition-opacity text-left">
          <span className="flex items-center gap-2 text-[13px]">
            <svg viewBox="0 0 20 20" fill="none" className="w-4 h-4 flex-shrink-0">
              <circle cx="10" cy="10" r="9" stroke="#0D5C47" strokeWidth="1.5"/>
              <polyline points="6,10 9,13 14,7" stroke="#0D5C47" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round"/>
            </svg>
            {required && <span className="text-[11px] font-bold text-kb-primary border border-kb-primary px-1.5 py-0.5 rounded-sm">필수</span>}
            <span className="font-semibold text-kb-text">{title}</span>
          </span>
          <span className="text-kb-text-muted text-xs ml-2">{open ? '∧' : '›'}</span>
        </button>
      </div>
      {open && <div className="px-6 py-3 bg-[#FAFAFA] text-[12px] text-kb-text-body leading-relaxed">{children}</div>}
    </div>
  )
}

/* ─── 섹션 헤더 ─── */
function SectionHeader({ title }: { title: string }) {
  return (
    <div className="flex items-center gap-2 bg-[#F5F5F5] border border-kb-border px-4 py-3 mb-0">
      <svg viewBox="0 0 20 20" fill="none" className="w-4 h-4 flex-shrink-0">
        <circle cx="10" cy="10" r="9" stroke="#0D5C47" strokeWidth="1.5"/>
        <polyline points="6,10 9,13 14,7" stroke="#0D5C47" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round"/>
      </svg>
      <span className="text-[13px] font-bold text-kb-text">{title}</span>
    </div>
  )
}

/* ─── 정보입력 행 ─── */
function FormRow({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <div className="border-b border-kb-border py-4">
      <p className="text-[13px] font-semibold text-kb-text mb-2">{label}</p>
      {children}
    </div>
  )
}

export default function DepositJoinPage() {
  const params = useParams()
  const router = useRouter()
  const id = typeof params.id === 'string' ? params.id : 'axful-regular'

  // product-{n} 슬러그는 API로 상품 정보를 조회해 타입을 결정
  const numericIdMatch = id.match(/^product-(\d+)$/)
  const [apiProductName, setApiProductName] = useState<string | null>(null)
  const [apiIsChecking, setApiIsChecking] = useState<boolean | null>(null)
  const [apiIsSavings, setApiIsSavings] = useState<boolean | null>(null)
  const [apiIsHousing, setApiIsHousing] = useState<boolean | null>(null)
  const [apiIsFreeStyleSavings, setApiIsFreeStyleSavings] = useState<boolean | null>(null)
  const [apiIsRegularSavings, setApiIsRegularSavings] = useState<boolean | null>(null)

  useEffect(() => {
    if (!numericIdMatch) return
    const productId = parseInt(numericIdMatch[1], 10)
    fetchDepositProduct(productId).then(product => {
      setApiProductName(product.productName)
      const savings = product.productType === 'SAVINGS'
      const housing = product.productType === 'SUBSCRIPTION'
      const checking = !savings && !housing && product.productName.includes('통장')
      setApiIsSavings(savings)
      setApiIsHousing(housing)
      setApiIsChecking(checking)
      setApiIsFreeStyleSavings(savings && product.savingType === 'FREE')
      setApiIsRegularSavings(savings && product.savingType === 'REGULAR')
    }).catch(() => { /* 조회 실패 시 정기예금으로 fallback */ })
  }, [id]) // eslint-disable-line react-hooks/exhaustive-deps

  const productName = apiProductName ?? (PRODUCT_NAMES[id] ?? 'AXful 정기예금')
  const isSavings = apiIsSavings ?? SAVINGS_IDS.has(id)
  const isHousing = apiIsHousing ?? HOUSING_IDS.has(id)
  const isChecking = apiIsChecking ?? CHECKING_IDS.has(id)
  const terms = isChecking ? TERMS_BY_TYPE.checking
    : isHousing ? TERMS_BY_TYPE.housing
    : isSavings ? TERMS_BY_TYPE.savings
    : TERMS_BY_TYPE.deposit
  const isFreeStyleSavings = apiIsFreeStyleSavings ?? FREE_SAVINGS_IDS.has(id)
  const isRegularSavings   = apiIsRegularSavings ?? REGULAR_SAVINGS_IDS.has(id)
  const periodRange = isSavings ? (SAVINGS_PERIOD_RANGE[id] ?? { min: 1, max: 36, label: '1~36개월, 월단위' }) : { min: 1, max: 36, label: '1~36개월, 월단위' }

  const [step, setStep] = useState<1 | 2 | 3>(1)

  /* ─── Step 1 state ─── */
  const REQUIRED_KEYS = ['illegal', 'protection', 'priority', 'burden', 'product', 'final'] as const
  type TermKey = typeof REQUIRED_KEYS[number]
  const [termChecks, setTermChecks] = useState<Record<TermKey, boolean>>({
    illegal: false, protection: false, priority: false, burden: false, product: false, final: false,
  })
  const allRequiredChecked = REQUIRED_KEYS.every(k => termChecks[k])

  function checkTerm(key: TermKey, val: boolean) {
    setTermChecks(prev => ({ ...prev, [key]: val }))
  }
  function checkAll(val: boolean) {
    setTermChecks({ illegal: val, protection: val, priority: val, burden: val, product: val, final: val })
  }

  /* ─── 군인 인증 state (장병내일준비적금 전용) ─── */
  const [militaryBranch, setMilitaryBranch] = useState('')
  const [militaryId, setMilitaryId] = useState('')
  const [enlistDate, setEnlistDate] = useState('')
  const [dischargeDate, setDischargeDate] = useState('')

  /* ─── 자동이체 state (정기적금 전용) ─── */
  const [autoTransfer, setAutoTransfer] = useState<'yes' | 'no' | null>(null)
  const [transferDay, setTransferDay] = useState('')
  const [transferAccount, setTransferAccount] = useState('')

  /* ─── Step 2 state ─── */
  const [period, setPeriod] = useState(() => {
    // 기간이 고정된 적금은 기본값 설정
    const r = isSavings ? (SAVINGS_PERIOD_RANGE[id] ?? null) : null
    return (r && r.min === r.max) ? String(r.min) : ''
  })
  const [periodPreset, setPeriodPreset] = useState<string | null>(null)
  const [amount, setAmount] = useState('')
  const [couponType, setCouponType] = useState<'coupon' | 'point' | 'none'>('none')
  const [taxExempt, setTaxExempt] = useState(false)
  const [passwordType, setPasswordType] = useState<'same' | 'new'>('same')
  const [maturity, setMaturity] = useState('자동재예치(원금+이자)')
  const [lms, setLms] = useState<'yes' | 'no' | null>(null)
  const [docMethod, setDocMethod] = useState<'email' | 'lms' | null>(null)


  /* ─── Step 3 state ─── */
  const [confirmPw, setConfirmPw] = useState('')
  const [mouseInput, setMouseInput] = useState(false)
  const [mouseConfirmPw, setMouseConfirmPw] = useState('')
  const [submitting, setSubmitting] = useState(false)

  /* ─── 출금계좌(funding) — 본인 입출금 계좌 실데이터 연동 ─── */
  const [withdrawAccounts, setWithdrawAccounts] = useState<DepositViewAccount[]>([])
  const [withdrawAccount, setWithdrawAccount] = useState('')

  useEffect(() => {
    let active = true
    fetchDepositAccountViewModels(getCurrentDepositCustomerId())
      .then(accs => {
        if (!active) return
        const transferable = accs.filter(a => a.type === '입출금')
        setWithdrawAccounts(transferable)
        if (transferable.length > 0) {
          setWithdrawAccount(prev => prev || transferable[0].id)
          setTransferAccount(prev => prev || transferable[0].id)
        }
      })
      .catch(() => { if (active) setWithdrawAccounts([]) })
    return () => { active = false }
  }, [])

  const STEP_LABELS = ['약관동의', '정보입력', '정보확인']

  function addAmount(val: number) {
    const cur = parseInt(amount.replace(/,/g, '') || '0')
    setAmount((cur + val * 10000).toLocaleString())
  }

  function handleStep1Next() {
    if (!allRequiredChecked) { alert('필수 약관에 모두 동의해 주세요.'); return }
    setStep(2)
  }

  function handleStep2Next() {
    if (id === 'axful-soldier') {
      if (!militaryBranch) { alert('군종을 선택해주세요.'); return }
      if (!militaryId.trim()) { alert('군번을 입력해주세요.'); return }
      if (!enlistDate) { alert('입대일을 입력해주세요.'); return }
      if (!dischargeDate) { alert('전역예정일을 입력해주세요.'); return }
    }
    if (!isChecking) {
      const m = parseInt(period)
      if (!m || m < periodRange.min || m > periodRange.max) {
        alert(`가입기간을 올바르게 입력해주세요. (${periodRange.label})`)
        return
      }
    }
    if (isRegularSavings) {
      if (autoTransfer === null) { alert('자동이체 여부를 선택해주세요.'); return }
      const a = parseInt(amount.replace(/,/g, ''))
      if (!a || a < 10000) { alert('납입금액은 최소 1만원 이상이어야 합니다.'); return }
      if (autoTransfer === 'yes') {
        if (!transferDay) { alert('자동이체일을 선택해주세요.'); return }
        if (!transferAccount) { alert('자동이체 출금계좌를 선택해주세요.'); return }
      }
      setStep(3)
      return
    }
    const a = parseInt(amount.replace(/,/g, ''))
    if (isSavings || isHousing) {
      if (!a || a < 10000) { alert('납입금액은 최소 1만원 이상이어야 합니다.'); return }
    } else if (!isChecking && (!a || a < 1000000)) {
      alert('가입금액은 최소 100만원 이상이어야 합니다.')
      return
    }
    setStep(3)
  }

  async function handleFinalConfirm() {
    if (submitting) return
    const pw = mouseInput ? mouseConfirmPw : confirmPw
    if (!pw) { alert('계좌 비밀번호를 입력해주세요.'); return }
    setSubmitting(true)

    try {
      const customerId = getCurrentDepositCustomerId()
      await createDepositContract(customerId, {
        slug: id,
        productName,
        amount: parseInt(amount.replace(/,/g, '')) || 0,
        periodMonth: parseInt(period) || 1,
        accountPassword: confirmPw || '0000',
        isSavings,
        isHousing,
        isChecking,
        isRegularSavings,
        autoTransferEnabled: autoTransfer === 'yes',
        autoTransferDay: transferDay ? parseInt(transferDay) : undefined,
        taxExempt,
      })
      extendLocalSessionAfterAuthenticatedAction()
      localStorage.removeItem('joinedAccounts')
      router.push('/inquiry/accounts')
      router.refresh()
    } catch (err: unknown) {
      const e = err as { response?: { data?: { message?: string; error?: string } }; message?: string }
      alert(e.response?.data?.message || e.response?.data?.error || e.message || '가입 처리 중 오류가 발생했습니다. 잠시 후 다시 시도해주세요.')
      setSubmitting(false)
    }
  }

  const months = parseInt(period) || 0
  const maturityDate = months > 0
    ? new Date(2026, 5 - 1 + months, 25).toLocaleDateString('ko-KR', { year: 'numeric', month: '2-digit', day: '2-digit' }).replace(/\. /g, '.').replace(/\.$/, '')
    : '-'

  return (
    <div className="max-w-kb-container mx-auto px-6 py-6">
      <AutoBreadcrumb
        as="/products/deposit/list"
        className="flex justify-end items-center mb-3 text-[12px] text-kb-text-muted gap-1"
        trailing={<Link href="#" className="text-kb-blue hover:underline">도움말</Link>}
      />

      <div className="flex gap-6">
        <DepositSidebar />

        <main className="flex-1 min-w-0">
          {/* 제목 + 스텝 */}
          <div className="flex items-center justify-between mb-5">
            <h1 className="text-[20px] font-bold text-kb-text">{productName}</h1>
            <div className="flex gap-1">
              {STEP_LABELS.map((s, i) => (
                <button key={s}
                  className={`px-4 py-1.5 text-[12px] rounded-lg transition-colors ${
                    i + 1 === step
                      ? 'font-bold text-white'
                      : 'text-kb-text-body border border-kb-border bg-white'
                  }`}
                  style={i + 1 === step ? { backgroundColor: KB_PRIMARY } : {}}>
                  {i + 1}. {s}
                </button>
              ))}
            </div>
          </div>

          {/* ══════════ STEP 1: 약관동의 ══════════ */}
          {step === 1 && (
            <div className="space-y-0">

              {/* 전체 동의 */}
              <div className="border border-kb-border rounded-xl bg-kb-primary-bg px-5 py-4 mb-4 flex items-center gap-3">
                <input
                  type="checkbox"
                  id="agreeAll"
                  checked={allRequiredChecked}
                  onChange={e => checkAll(e.target.checked)}
                  className="w-5 h-5 accent-kb-primary cursor-pointer flex-shrink-0"
                />
                <label htmlFor="agreeAll" className="text-[14px] font-bold text-kb-text cursor-pointer">
                  아래 약관 및 필수 항목에 전체 동의합니다.
                </label>
              </div>

              {/* 약관 및 상품설명서 */}
              <div className="border border-kb-border rounded-xl overflow-hidden mb-4">
                <SectionHeader title="약관 및 상품설명서" />
                <div>
                  <AccItem title="약관 열람">
                    {terms.map(t => (
                      <button key={t}
                        className="flex items-center justify-between w-full py-2 border-b border-kb-border last:border-0 hover:text-kb-blue transition-colors">
                        <span>{t}</span>
                        <span className="text-xs text-kb-text-muted">›</span>
                      </button>
                    ))}
                  </AccItem>
                </div>
              </div>

              {/* 확인 및 안내사항 */}
              <div className="border border-kb-border rounded-xl overflow-hidden mb-4">
                <SectionHeader title="확인 및 안내사항" />
                <AccItem title="불법·탈법 자금거래 금지 설명 확인" required
                  checked={termChecks.illegal} onCheck={v => checkTerm('illegal', v)}>
                  금융실명거래 및 비밀보장에 관한법률 제3조 제3항에 따라 누구든지 재산의 은닉, 자금세탁행위, 공중협박자금조달 행위 및 강제집행의 면탈, 그 밖의 탈법행위를 목적으로 타인의 실명으로 금융거래를 해서는 아니되며, 이를 위반시 5년 이하의 징역 또는 5천만원 이하의 벌금에 처할 수 있습니다.
                </AccItem>
                <AccItem title="예금자보호법 설명 확인" required
                  checked={termChecks.protection} onCheck={v => checkTerm('protection', v)}>
                  본인은 AX풀뱅크로부터 가입하는 금융상품의 예금자보호여부(보호 또는 비보호) 및 보호한도에 대하여 설명 받고 이해하였음을 확인합니다.
                </AccItem>
              </div>

              {/* 금융상품의 중요사항 안내 */}
              <div className="border border-kb-border rounded-xl overflow-hidden mb-4">
                <SectionHeader title="금융상품의 중요사항 안내" />
                <AccItem title="우선설명 사항 확인" required
                  checked={termChecks.priority} onCheck={v => checkTerm('priority', v)}>
                  <p className="text-kb-danger">이자율(중도해지이율, 만기후이율) 및 산출근거</p>
                </AccItem>
                <AccItem title="부담정보 및 금융소비자의 권리 사항 확인" required
                  checked={termChecks.burden} onCheck={v => checkTerm('burden', v)}>
                  <ul className="space-y-1">
                    {['중도 해지에 따른 불이익', '금리변동형 상품 안내', '자료열람요구권 행사에 관한 사항', '위법계약해지권 행사에 관한 사항',
                      '금융상품 판매 전후 안내(만기 알림 서비스)', '예금자보호법에 관한 사항(예금자보호 여부 및 그 내용)', '민원처리 및 분쟁조정 절차'].map(item => (
                      <li key={item} className="text-kb-danger flex gap-1.5 before:content-['·'] before:flex-shrink-0">{item}</li>
                    ))}
                  </ul>
                </AccItem>
                <AccItem title="예금성 상품 및 연계·제휴 서비스 확인" required
                  checked={termChecks.product} onCheck={v => checkTerm('product', v)}>
                  <ul className="space-y-1 mb-2">
                    {['예금상품의 내용(계약기간, 이자의 지급시기 및 지급제한 사유)', '계약의 해제·해지',
                      '연계·제휴 서비스의 내용, 제공받을 수 있는 요건, 제공기간 등을 사전에 알린다는 사실 및 알리는 방법'].map(item => (
                      <li key={item} className="text-kb-danger flex gap-1.5 before:content-['·'] before:flex-shrink-0">{item}</li>
                    ))}
                  </ul>
                  <p className="text-kb-text-muted">※ 금융소비자는 해당 상품 또는 서비스에 대해 설명을 받을 권리가 있습니다. 궁금한 내용은 고객센터(☎1588-9999) 또는 영업점에 문의하시기 바랍니다.</p>
                </AccItem>
              </div>

              {/* 최종 동의 */}
              <div className="border border-kb-border rounded-xl p-4 mb-6">
                <label className="flex items-start gap-3 cursor-pointer">
                  <input type="checkbox" checked={termChecks.final} onChange={e => checkTerm('final', e.target.checked)}
                    className="mt-0.5 w-4 h-4 accent-kb-primary" />
                  <div>
                    <p className="text-[13px] font-semibold text-kb-text">본인은 위 예금상품의 약관과 상품설명서에 대해 중요사항을 충분히 이해하고 본 상품에 가입함을 확인합니다. <span className="text-kb-danger">(필수)</span></p>
                    <p className="text-[12px] text-kb-danger mt-1">※ 설명내용을 제대로 이해하지 못하였음에도 이해했다는 확인을 하는 경우, 추후 권리구제가 어려울 수 있습니다.</p>
                  </div>
                </label>
              </div>

              <div className="flex justify-center gap-2">
                <Link href={`/products/deposit/${id}`}
                  className="border border-kb-border rounded-xl px-10 py-2.5 text-[13px] text-kb-text-body hover:bg-kb-beige-light">
                  이전
                </Link>
                <button onClick={handleStep1Next}
                  className={`px-10 py-2.5 text-[13px] font-bold transition-colors ${
                    allRequiredChecked
                      ? 'bg-kb-primary text-white rounded-xl hover:opacity-85'
                      : 'bg-kb-border text-kb-text-muted cursor-not-allowed'
                  }`}>
                  다음
                </button>
              </div>
            </div>
          )}

          {/* ══════════ STEP 2: 정보입력 ══════════ */}
          {step === 2 && (
            <div>
              <p className="text-[14px] font-bold text-kb-primary border-b-2 border-kb-primary inline-block pb-1 mb-4">정보입력</p>

              <div className="border border-kb-border rounded-xl px-5 py-2 mb-6 space-y-0">
                {/* 군인 인증 - 장병내일준비적금 전용 */}
                {id === 'axful-soldier' && (
                  <>
                    <div className="bg-kb-primary-bg border border-kb-primary-border px-4 py-3 mb-3 text-[12px] text-kb-text-body rounded">
                      <p className="font-semibold text-kb-chat mb-1">현역 복무 중인 장병만 가입 가능합니다.</p>
                      <p>· 군번 및 복무 정보는 병무청 데이터와 대조하여 확인됩니다.</p>
                      <p>· 허위 정보 입력 시 가입이 취소될 수 있습니다.</p>
                    </div>
                    <FormRow label="군종 선택">
                      <div className="flex gap-5 flex-wrap">
                        {['육군', '해군', '공군', '해병대', '해양경찰'].map(branch => (
                          <label key={branch} className="flex items-center gap-2 text-[13px] cursor-pointer">
                            <input type="radio" name="militaryBranch" checked={militaryBranch === branch}
                              onChange={() => setMilitaryBranch(branch)} className="accent-kb-primary" />
                            {branch}
                          </label>
                        ))}
                      </div>
                    </FormRow>
                    <FormRow label="군번">
                      <input type="text" value={militaryId} onChange={e => setMilitaryId(e.target.value)}
                        placeholder="예: 20-12345678"
                        className="border border-kb-border rounded-lg px-3 py-1.5 text-[13px] w-48 outline-none" />
                      <p className="text-[12px] text-kb-text-muted mt-1">군번은 군 식별번호입니다.</p>
                    </FormRow>
                    <FormRow label="입대일">
                      <input type="date" value={enlistDate} onChange={e => setEnlistDate(e.target.value)}
                        className="border border-kb-border rounded-lg px-3 py-1.5 text-[13px] outline-none" />
                    </FormRow>
                    <FormRow label="전역예정일">
                      <input type="date" value={dischargeDate} onChange={e => setDischargeDate(e.target.value)}
                        className="border border-kb-border rounded-lg px-3 py-1.5 text-[13px] outline-none" />
                      <p className="text-[12px] text-kb-text-muted mt-1">* 전역예정일 기준으로 만기가 설정됩니다.</p>
                    </FormRow>
                  </>
                )}

                {/* 가입기간 - 입출금 상품은 숨김 */}
                {!isChecking && (
                  <FormRow label="가입기간">
                  <div className="flex items-center gap-2 flex-wrap">
                    <p className="text-[12px] text-kb-text-muted mr-2">{periodRange.label}</p>
                    {periodRange.min === periodRange.max ? (
                      /* 기간 고정 상품 */
                      <>
                        <input type="text" value={period} readOnly
                          className="border border-kb-border rounded-lg px-3 py-1.5 text-[13px] w-20 outline-none bg-[#F5F5F5] text-center" />
                        <span className="text-[13px]">개월 (고정)</span>
                      </>
                    ) : (
                      /* 기간 선택 가능 상품 */
                      <>
                        <input type="text" value={period} onChange={e => setPeriod(e.target.value.replace(/[^0-9]/g, ''))}
                          placeholder="기간"
                          className="border border-kb-border rounded-lg px-3 py-1.5 text-[13px] w-20 outline-none" />
                        <span className="text-[13px]">개월</span>
                        {(isSavings
                          ? (periodRange.max <= 12
                              ? [1, 3, 6, 12].filter(v => v >= periodRange.min && v <= periodRange.max)
                              : [6, 12, 24, 36].filter(v => v >= periodRange.min && v <= periodRange.max))
                          : [6, 12, 24, 36]
                        ).map(m => (
                          <button key={m}
                            onClick={() => { setPeriod(String(m)); setPeriodPreset(String(m)) }}
                            className={`px-4 py-1.5 text-[12px] border rounded-lg transition-colors ${
                              periodPreset === String(m)
                                ? 'border-kb-primary text-kb-primary font-bold bg-white'
                                : 'border-kb-border text-kb-text-body hover:bg-kb-beige-light'
                            }`}>
                            {m}개월
                          </button>
                        ))}
                      </>
                    )}
                  </div>
                  </FormRow>
                )}

                {/* 자동이체 여부 - 정기적금 전용 */}
                {isRegularSavings && (
                  <FormRow label="자동이체 여부">
                    <div className="flex gap-6">
                      <label className="flex items-center gap-2 text-[13px] cursor-pointer">
                        <input type="radio" name="autoTransfer" value="yes"
                          checked={autoTransfer === 'yes'}
                          onChange={() => setAutoTransfer('yes')} className="accent-kb-primary" />
                        신청
                      </label>
                      <label className="flex items-center gap-2 text-[13px] cursor-pointer">
                        <input type="radio" name="autoTransfer" value="no"
                          checked={autoTransfer === 'no'}
                          onChange={() => setAutoTransfer('no')} className="accent-kb-primary" />
                        미신청
                      </label>
                    </div>
                  </FormRow>
                )}

                {/* 월 납입금액 - 정기적금은 항상, 그 외는 가입금액/자유납입/초기입금 */}
                <FormRow label={isFreeStyleSavings ? '최초 납입금액' : isRegularSavings ? '월 납입금액' : isChecking ? '초기 입금금액' : '가입금액'}>
                  <div className="flex items-center gap-2 flex-wrap">
                    <p className="text-[12px] text-kb-text-muted mr-2">
                      {isFreeStyleSavings ? '최소 1만원 이상, 이후 자유 납입'
                        : isRegularSavings ? '최소 1만원 이상, 원단위'
                        : isChecking ? '최소 1원 이상, 원단위'
                        : '최소 100만원 이상, 원단위'}
                    </p>
                    <input type="text" value={amount} onChange={e => setAmount(e.target.value)}
                      placeholder="0"
                      className="border border-kb-border rounded-lg px-3 py-1.5 text-[13px] w-32 outline-none text-right" />
                    <span className="text-[13px]">원</span>
                    {(isSavings ? [1, 3, 5, 10] : isChecking ? [1, 3, 5, 10, 20] : [1000, 500, 300, 100]).map(v => (
                      <button key={v} onClick={() => addAmount(v)}
                        className="border border-kb-border rounded-lg px-3 py-1.5 text-[12px] text-kb-text-body hover:bg-kb-beige-light">
                        {(isSavings || isChecking) ? `${v}만` : (v >= 1000 ? `${v / 100}천만` : `${v}만`)}
                      </button>
                    ))}
                  </div>
                </FormRow>

                {/* 자동이체일 - 신청 선택 시 */}
                {isRegularSavings && autoTransfer === 'yes' && (
                  <FormRow label="자동이체일">
                    <div className="flex items-center gap-2">
                      <span className="text-[13px]">매월</span>
                      <select value={transferDay} onChange={e => setTransferDay(e.target.value)}
                        className="border border-kb-border rounded-lg px-3 py-1.5 text-[13px] outline-none bg-white w-24">
                        <option value="">선택</option>
                        <option value="1">1일</option>
                        <option value="2">2일</option>
                        <option value="3">3일</option>
                        <option value="4">4일</option>
                        <option value="5">5일</option>
                        <option value="6">6일</option>
                        <option value="7">7일</option>
                        <option value="8">8일</option>
                        <option value="9">9일</option>
                        <option value="10">10일</option>
                        <option value="11">11일</option>
                        <option value="12">12일</option>
                        <option value="13">13일</option>
                        <option value="14">14일</option>
                        <option value="15">15일</option>
                        <option value="16">16일</option>
                        <option value="17">17일</option>
                        <option value="18">18일</option>
                        <option value="19">19일</option>
                        <option value="20">20일</option>
                        <option value="21">21일</option>
                        <option value="22">22일</option>
                        <option value="23">23일</option>
                        <option value="24">24일</option>
                        <option value="25">25일</option>
                        <option value="26">26일</option>
                        <option value="27">27일</option>
                        <option value="28">28일</option>
                        <option value="29">29일</option>
                        <option value="30">30일</option>
                        <option value="31">31일</option>
                      </select>
                      <span className="text-[13px]">이체</span>
                    </div>
                    <p className="text-[12px] text-kb-text-muted mt-1">* 29~31일은 해당 월 말일에 이체됩니다.</p>
                  </FormRow>
                )}

                {/* 자동이체 출금계좌 - 신청 선택 시 */}
                {isRegularSavings && autoTransfer === 'yes' && (
                  <FormRow label="자동이체 출금계좌">
                    <div className="flex items-center gap-2 mb-1">
                      <select className="border border-kb-border rounded-lg px-3 py-1.5 text-[13px] outline-none bg-white">
                        <option>AX풀뱅크</option>
                      </select>
                      <select value={transferAccount} onChange={e => setTransferAccount(e.target.value)}
                        className="border border-kb-border rounded-lg px-3 py-1.5 text-[13px] outline-none bg-white flex-1 max-w-[280px]">
                        {withdrawAccounts.length === 0
                          ? <option value="">출금 가능한 계좌가 없습니다</option>
                          : withdrawAccounts.map(a => <option key={a.id} value={a.id}>{a.number} ({a.name})</option>)}
                      </select>
                    </div>
                    <p className="text-[12px]">출금가능금액 <span className="text-kb-danger font-bold">{formatNumber(withdrawAccounts.find(a => a.id === transferAccount)?.availableBalance ?? 0)}</span>원</p>
                  </FormRow>
                )}

                {/* 쿠폰/포인트 */}
                <FormRow label="AXful금융쿠폰/포인트리 사용">
                  <div className="flex gap-6">
                    {([['coupon', 'AXful금융쿠폰(0)'], ['point', '포인트리'], ['none', '사용안함']] as const).map(([val, label]) => (
                      <label key={val} className="flex items-center gap-2 text-[13px] cursor-pointer">
                        <input type="radio" name="coupon" checked={couponType === val}
                          onChange={() => setCouponType(val)} className="accent-kb-primary" />
                        {label}
                      </label>
                    ))}
                  </div>
                </FormRow>

                {/* 비과세 */}
                <FormRow label="비과세종합저축으로 가입">
                  <div className="flex items-center gap-3">
                    <label className="flex items-center gap-2 text-[13px] cursor-pointer">
                      <input type="checkbox" checked={taxExempt} onChange={e => setTaxExempt(e.target.checked)}
                        className="w-4 h-4 accent-kb-primary" />
                      비과세종합저축 적용
                    </label>
                    <button className="text-[12px] text-kb-blue hover:underline">자세히보기 ›</button>
                  </div>
                </FormRow>

                {/* 출금계좌 */}
                <FormRow label="출금계좌번호">
                  <div className="flex items-center gap-2 mb-1">
                    <select className="border border-kb-border rounded-lg px-3 py-1.5 text-[13px] outline-none bg-white">
                      <option>AX풀뱅크</option>
                    </select>
                    <select value={withdrawAccount} onChange={e => setWithdrawAccount(e.target.value)}
                      className="border border-kb-border rounded-lg px-3 py-1.5 text-[13px] outline-none bg-white flex-1 max-w-[280px]">
                      {withdrawAccounts.length === 0
                        ? <option value="">출금 가능한 계좌가 없습니다</option>
                        : withdrawAccounts.map(a => <option key={a.id} value={a.id}>{a.number} ({a.name})</option>)}
                    </select>
                  </div>
                  <p className="text-[12px]">출금가능금액 <span className="text-kb-danger font-bold">{formatNumber(withdrawAccounts.find(a => a.id === withdrawAccount)?.availableBalance ?? 0)}</span>원</p>
                </FormRow>

                {/* 비밀번호 */}
                <FormRow label="비밀번호 입력">
                  <div className="flex gap-6">
                    {([['same', '출금계좌와 동일하게 설정'], ['new', '신규 설정']] as const).map(([val, label]) => (
                      <label key={val} className="flex items-center gap-2 text-[13px] cursor-pointer">
                        <input type="radio" name="pw" checked={passwordType === val}
                          onChange={() => setPasswordType(val)} className="accent-kb-primary" />
                        {label}
                      </label>
                    ))}
                  </div>
                </FormRow>

                {/* 만기 해지방법 - 입출금 상품은 숨김 */}
                {!isChecking && (
                  <FormRow label="만기 해지방법">
                    <select value={maturity} onChange={e => setMaturity(e.target.value)}
                      className="border border-kb-border rounded-lg px-3 py-1.5 text-[13px] outline-none bg-white w-60">
                      {MATURITY_OPTIONS.map(o => <option key={o}>{o}</option>)}
                    </select>
                    <p className="text-[12px] text-kb-text-muted mt-1">* 만기 해지방법은 만기일 전까지 변경할 수 있습니다.</p>
                  </FormRow>
                )}

                {/* LMS - 입출금 상품은 숨김 */}
                {!isChecking && (
                  <FormRow label="상품만기알림(LMS) 서비스 신청">
                    <div className="flex gap-6 mb-2">
                      {([['yes', '예'], ['no', '아니오']] as const).map(([val, label]) => (
                        <label key={val} className="flex items-center gap-2 text-[13px] cursor-pointer">
                          <input type="radio" name="lms" checked={lms === val}
                            onChange={() => setLms(val)} className="accent-kb-primary" />
                          {label}
                        </label>
                      ))}
                    </div>
                    <p className="text-[12px] text-kb-text-muted">LMS를 통해 예·적금상품의 만기를 사전 안내해드리는 서비스</p>
                  </FormRow>
                )}

                {/* 권유직원 */}
                <FormRow label="권유직원선택">
                  <select className="border border-kb-border rounded-lg px-3 py-1.5 text-[13px] outline-none bg-white w-48">
                    <option>권유직원없음</option>
                  </select>
                </FormRow>

                {/* 서류 수령 방법 */}
                <FormRow label="예금상품 계약서, 약관(특약), 상품설명서 제공">
                  <div className="flex gap-6 mb-2">
                    {([['email', '이메일주소로 받기'], ['lms', '문자메시지(LMS) 받기']] as const).map(([val, label]) => (
                      <label key={val} className="flex items-center gap-2 text-[13px] cursor-pointer">
                        <input type="radio" name="doc" checked={docMethod === val}
                          onChange={() => setDocMethod(val)} className="accent-kb-primary" />
                        {label}
                      </label>
                    ))}
                  </div>
                  <p className="text-[12px] text-kb-text-muted">※ 금융소비자보호법에 따라 이메일 및 문자메시지(LMS) 수신 거부 여부와 관계없이 발송됩니다.</p>
                </FormRow>
              </div>

              {/* 안내 박스 */}
              <div className="border border-kb-border rounded-xl bg-[#FAFAFA] px-4 py-3 mb-6 text-[12px] text-kb-text-body space-y-1">
                {[
                  '본 상품을 인터넷/AXful뱅킹으로 해지할 경우 휴대전화의 인증을 통해 해지가능합니다. 인증 후 본 상품에 가입합니다.',
                  '최신전화 휴대전화는 인증이 되지 않습니다.',
                  <span key="r" className="font-semibold">만기해지방법은 만기일 전까지 변경이 가능합니다.</span>,
                  <span key="r2" className="font-semibold">비과세종합저축 선택 시 만기해지방법은 자동해지만 가능합니다.</span>,
                ].map((n, i) => (
                  <p key={i} className="flex gap-1.5"><span className="flex-shrink-0">·</span><span>{n}</span></p>
                ))}
              </div>

              <div className="flex justify-center gap-2">
                <button onClick={() => setStep(1)}
                  className="border border-kb-border rounded-xl px-10 py-2.5 text-[13px] text-kb-text-body hover:bg-kb-beige-light">
                  이전
                </button>
                <button onClick={handleStep2Next}
                  className="bg-kb-primary px-10 py-2.5 text-[13px] font-bold text-white rounded-xl hover:opacity-85 transition-opacity">
                  다음
                </button>
                <button className="border border-kb-border rounded-xl px-10 py-2.5 text-[13px] text-kb-text-body hover:bg-kb-beige-light">
                  임시저장
                </button>
              </div>
            </div>
          )}

          {/* ══════════ STEP 3: 정보확인 ══════════ */}
          {step === 3 && (
            <div>
              <p className="text-[14px] font-bold text-kb-primary border-b-2 border-kb-primary inline-block pb-1 mb-4">정보 확인</p>

              <table className="w-full border-collapse text-[13px] border-t-2 border-kb-text mb-3">
                <tbody>
                  {[
                    { label: '신규일자', value: '2026.05.25' },
                    ...(id === 'axful-soldier' ? [
                      { label: '군종', value: militaryBranch },
                      { label: '군번', value: militaryId },
                      { label: '입대일', value: enlistDate },
                      { label: '전역예정일', value: dischargeDate },
                    ] : []),
                    ...(!isChecking ? [{ label: '가입기간', value: `${maturityDate} (${period}개월)` }] : []),
                    ...(isRegularSavings ? [
                      { label: '자동이체 여부', value: autoTransfer === 'yes' ? '신청' : '미신청' },
                      ...(autoTransfer === 'yes' ? [
                        { label: '자동이체일', value: `매월 ${transferDay}일` },
                        { label: '자동이체 출금계좌', value: `AX풀뱅크 ${withdrawAccounts.find(a => a.id === transferAccount)?.number ?? '-'}` },
                      ] : []),
                    ] : []),
                    { label: isFreeStyleSavings ? '납입방식' : isRegularSavings ? '월 이체금액' : isChecking ? '초기 입금금액' : '가입금액',
                      value: isFreeStyleSavings ? '자유 납입 (1회 최소 1만원)' : `${amount}원` },
                    ...(!isChecking ? [{ label: '이자지급방법', value: isSavings ? '만기일시지급식' : '만기일시지급식' }] : []),
                    { label: '적용금리', value: PRODUCT_RATES[id] ?? (isChecking ? '연 0.1%' : '연 2.4%') },
                    { label: '적용과세', value: taxExempt ? '비과세' : '일반' },
                    { label: '출금계좌', value: `AX풀뱅크 ${withdrawAccounts.find(a => a.id === withdrawAccount)?.number ?? '-'}` },
                    ...(!isChecking ? [{ label: '상품만기알림(LMS) 서비스 신청', value: lms === 'yes' ? '신청' : '미신청' }] : []),
                    { label: '연계·제류서비스', value: '해당사항 없음' },
                  ].map(row => (
                    <tr key={row.label}>
                      <td className="bg-kb-beige-light border border-kb-border px-4 py-3 font-semibold text-kb-text w-[180px] whitespace-nowrap">
                        {row.label}
                      </td>
                      <td className="border border-kb-border px-4 py-3 text-kb-text-body">{row.value}</td>
                    </tr>
                  ))}
                </tbody>
              </table>

              <div className="text-[12px] text-kb-text-muted mb-5 space-y-1">
                <p>※ 본 상품정보 교회를 이용하실 시 상품 이전을 참조하기 위한 것입니다. 자세한 내용은 <button className="text-kb-blue hover:underline">상품설명서 ›</button>를 참조하시기 바랍니다.</p>
                <p>※ 가입 후에는 &apos;계약서류&apos; 관련 약관을 통해 상품설명문서를 확인할 수 있습니다.</p>
              </div>

              {/* 예금계좌 비밀번호 설정 */}
              <div className="border border-t-2 border-kb-text pt-0">
                <p className="text-[14px] font-bold text-kb-primary border-b-2 border-kb-primary inline-block pb-1 mb-3">예금계좌 비밀번호 설정</p>
                <table className="w-full border-collapse text-[13px]">
                  <tbody>
                    <tr>
                      <td className="bg-kb-beige-light border border-kb-border px-4 py-3 font-semibold text-kb-text w-[150px] whitespace-nowrap">비밀번호 (4자리)</td>
                      <td className="border border-kb-border px-4 py-3">
                        <div className="flex flex-col gap-2">
                          {mouseInput ? (
                            <MouseNumKeypad value={mouseConfirmPw} onChange={setMouseConfirmPw} maxLength={4} dotCount={4} />
                          ) : (
                            <input
                              type="password"
                              value={confirmPw}
                              onChange={e => setConfirmPw(e.target.value)}
                              maxLength={4}
                              placeholder="4자리 입력"
                              className="border border-kb-border rounded-lg px-3 py-1.5 text-[13px] w-28 outline-none"
                            />
                          )}
                          <label className="flex items-center gap-1.5 text-[12px] cursor-pointer w-fit">
                            <input type="checkbox" checked={mouseInput} onChange={e => { setMouseInput(e.target.checked); setMouseConfirmPw(''); setConfirmPw('') }} />
                            마우스로 입력
                          </label>
                        </div>
                      </td>
                    </tr>
                  </tbody>
                </table>
              </div>

              <div className="flex justify-center gap-2 mt-6">
                <button onClick={() => setStep(2)}
                  className="border border-kb-border rounded-xl px-10 py-2.5 text-[13px] text-kb-text-body hover:bg-kb-beige-light">
                  이전
                </button>
                <button onClick={handleFinalConfirm} disabled={submitting}
                  className="bg-kb-primary px-10 py-2.5 text-[13px] font-bold text-white rounded-xl hover:opacity-85 transition-opacity disabled:opacity-50 disabled:cursor-not-allowed">
                  {submitting ? '처리 중...' : '가입완료'}
                </button>
              </div>
            </div>
          )}
        </main>
      </div>
    </div>
  )
}
