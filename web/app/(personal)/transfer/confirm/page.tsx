'use client'
import { KB_MINT,KB_PRIMARY,KB_PRIMARY_BG,KB_PRIMARY_SURFACE } from '@/lib/theme'

import { useEffect, useState } from 'react'
import { useRouter } from 'next/navigation'
import Link from 'next/link'
import { formatNumber } from '@/lib/mock-data'
import TransferSidebar from '@/components/inquiry/TransferSidebar'
import { executeDepositTransfer, getCurrentDepositCustomerId } from '@/lib/deposit-api'
import { createInstantTransfer, newIdempotencyKey, PAYMENT_BANK_CODE_MAP } from '@/lib/payment-api'
import { fetchMyCertificates, issueTransferApproval } from '@/lib/api'

type PendingTransfer = {
  fromAccountId?: number
  toAccountId?: number
  transferType?: 'INTERNAL' | 'EXTERNAL'
  fromNumber: string
  fromName: string
  toBank: string
  toBankCode: string
  toAccount: string
  amount: number
  receiverName: string
  fee: number
}

const PIN_PAD = [
  [1, 2, 3],
  [4, 5, 6],
  [7, 8, 9],
  ['↺', 0, '✕'],
]

/** 인증서가 없어 승인을 받을 수 없을 때. 이체를 진행하지 않는다. */
class NoCertificateError extends Error {}

/**
 * 이체 승인 토큰을 받는다.
 *
 * 인증서가 없으면 예외를 던져 이체를 막는다. 예전에는 토큰 없이 그냥 진행했는데,
 * 그러면 인증 화면을 통과한 사용자와 통과하지 않은 사용자가 같은 결과를 받는다 —
 * 화면상 인증한 것처럼 보이는 상태가 다시 생긴다.
 */
async function issueApproval(
  customerId: string,
  data: PendingTransfer,
  serialNumber: string,
  pin: string
): Promise<string> {
  if (!serialNumber) throw new NoCertificateError()

  return issueTransferApproval(customerId, {
    fromAccountNo: data.fromNumber,
    toAccountNo: data.toAccount,
    amount: data.amount,
    certSerialNumber: serialNumber,
    pin,
  })
}

export default function TransferConfirmPage() {
  const router = useRouter()
  const [data, setData] = useState<PendingTransfer | null>(null)
  const [showCertModal, setShowCertModal] = useState(false)
  const [certStep, setCertStep] = useState<'info' | 'pin'>('info')
  const [pin, setPin] = useState<number[]>([])
  const [isSubmitting, setIsSubmitting] = useState(false)
  const [certError, setCertError] = useState<string | null>(null)
  const [certs, setCerts] = useState<Array<{ serialNumber: string; status: string; certTypeName?: string }>>([])
  const [selectedCert, setSelectedCert] = useState<string>('')

  useEffect(() => {
    const raw = sessionStorage.getItem('pendingTransfer')
    if (!raw) { router.push('/transfer/account'); return }
    setData(JSON.parse(raw))
  }, [router])

  // 인증서 목록은 승인 단계에서 필요하다. 여러 장이면 고를 수 있어야 하고,
  // 한 장도 없으면 이체 전에 알려줘야 한다 — 6자리를 다 누른 뒤 실패시키면 나쁘다.
  useEffect(() => {
    let alive = true
    fetchMyCertificates(getCurrentDepositCustomerId())
      .then(list => {
        if (!alive) return
        const usable = list.filter(c => c.status === 'ACTIVE')
        setCerts(usable)
        setSelectedCert(usable[0]?.serialNumber ?? '')
      })
      .catch(() => { if (alive) { setCerts([]); setSelectedCert('') } })
    return () => { alive = false }
  }, [])

  function handlePinKey(key: number | string) {
    if (key === '↺') { setPin([]); return }
    if (key === '✕') { setPin(p => p.slice(0, -1)); return }
    if (typeof key === 'number' && pin.length < 6) {
      const next = [...pin, key]
      setPin(next)
      if (next.length === 6) {
        if (isSubmitting) return
        const pinValue = next.join('')
        setTimeout(async () => {
          if (!data) return
          setIsSubmitting(true)
          setCertError(null)
          setShowCertModal(false)
          try {
            if (!data.fromAccountId) throw new Error('출금계좌 정보가 없습니다.')
            if (data.transferType === 'EXTERNAL') {
              const bankCode = PAYMENT_BANK_CODE_MAP[data.toBankCode]
              if (!bankCode) throw new Error(`지원하지 않는 은행 코드: ${data.toBankCode}`)
              // 예전에는 newAuthToken() 이 만든 값을 인증 토큰이라고 보냈다 — 브라우저가
              // 지어낸 문자열이라 서버가 검증할 수도 없었다. 이제 인증서 PIN 으로 받은
              // 승인 토큰을 보내고, 결제계가 인증보안계에 대조한다.
              const approvalToken = await issueApproval(
                getCurrentDepositCustomerId(), data, selectedCert, pinValue)
              const idempotencyKey = newIdempotencyKey()
              const result = await createInstantTransfer(
                {
                  senderAccountId: data.fromNumber,
                  receiverBankCode: bankCode,
                  receiverAccountNo: data.toAccount,
                  receiverHolderName: data.receiverName,
                  transferAmount: data.amount,
                  channel: 'MOBILE',
                  senderMemo: '인터넷 이체',
                },
                {
                  userId: getCurrentDepositCustomerId(),
                  authTokenId: approvalToken,
                  idempotencyKey,
                  channel: 'MOBILE',
                  requestId: idempotencyKey,
                }
              )
              sessionStorage.setItem('paymentResult', JSON.stringify({
                status: result.status,
                txNo: result.transactionNo,
                piId: result.paymentInstructionId,
                failureCategory: result.failureCategory,
              }))
            } else {
              // 입력한 PIN 으로 이 이체에 한정된 승인 토큰을 받는다.
              // 지금까지 PIN 은 화면에서만 받고 아무 데도 쓰이지 않았다 — 6자리를 채우면
              // 그대로 이체가 됐다. 이제 실제로 인증서 PIN 을 확인한다.
              const customerId = getCurrentDepositCustomerId()
              const approvalToken = await issueApproval(customerId, data, selectedCert, pinValue)

              const result = await executeDepositTransfer(customerId, {
                fromAccountId: data.fromAccountId,
                toAccountId: data.toAccountId,
                toAccountNo: data.toAccount,
                amount: data.amount,
                transferType: data.transferType ?? (data.toAccountId ? 'INTERNAL' : 'EXTERNAL'),
                counterpartyBankCode: data.toBankCode,
                counterpartyBankName: data.toBank,
                counterpartyName: data.receiverName,
                transactionMemo: '인터넷 이체',
                approvalToken,
              })
              sessionStorage.setItem('paymentResult', JSON.stringify({
                status: 'COMPLETED',
                txNo: String(result.transactionId ?? Date.now()),
              }))
            }
          } catch (e: unknown) {
            const err = e as {
              response?: { status?: number; data?: { error?: string; message?: string; code?: string } }
              message?: string
            }
            // 인증서 PIN 실패는 이체 실패와 다르다. 결과 페이지로 넘기지 않고
            // 같은 화면에서 다시 입력받는다 — 사용자는 무엇이 틀렸는지 알아야 한다.
            if (e instanceof NoCertificateError) {
              sessionStorage.setItem('paymentResult', JSON.stringify({
                status: 'ERROR',
                message: '이체에는 인증서가 필요합니다. 인증센터에서 발급 후 다시 시도해 주세요.',
              }))
              setIsSubmitting(false)
              router.push('/transfer/result')
              return
            }
            const code = err.response?.data?.code ?? ''
            if (code.startsWith('CUST_03')) {
              setCertError(err.response?.data?.message ?? '인증서 비밀번호가 올바르지 않습니다.')
              setPin([])
              setShowCertModal(true)
              setIsSubmitting(false)
              return
            }
            sessionStorage.setItem('paymentResult', JSON.stringify({
              status: 'ERROR',
              message: err.response?.data?.message ?? err.response?.data?.error ?? err.message ?? '네트워크 오류가 발생했습니다.',
            }))
          } finally {
            setIsSubmitting(false)
          }
          router.push('/transfer/result')
        }, 400)
      }
    }
  }

  if (!data) return null

  return (
    <div className="max-w-kb-container mx-auto px-6">
      <div className="flex">
        <TransferSidebar />

        <main className="flex-1 pl-8 pt-6 pb-12">
          <h1 className="text-[22px] font-bold text-kb-text mb-5">계좌이체</h1>

          {/* STEP 표시 */}
          <div className="flex items-center gap-3 mb-6">
            <span className="text-[13px] text-kb-text-muted">STEP 1. 이체정보 입력</span>
            <span className="text-kb-text-muted">›</span>
            <span className="text-[14px] font-bold pb-0.5 border-b-2" style={{ color: KB_PRIMARY, borderColor: KB_PRIMARY }}>
              STEP 2. 이체정보 확인
            </span>
          </div>

          {/* 확인 헤더 */}
          <div className="rounded-xl p-6 mb-5 text-center" style={{ backgroundColor: KB_PRIMARY_BG, border: '1px solid #E2F5EF' }}>
            <p className="text-[16px] font-bold" style={{ color: KB_PRIMARY }}>
              {data.receiverName}님께 {formatNumber(data.amount)}원 이체하시겠습니까?
            </p>
          </div>

          {/* 안내 메시지 */}
          <div className="mb-5 text-[12px] space-y-1 rounded-xl px-5 py-4" style={{ backgroundColor: KB_PRIMARY_SURFACE, border: '1px solid #E2F5EF' }}>
            <p className="text-kb-text-muted">· 입금은행, 입금계좌번호, 이체금액 및 받는분을 다시 한번 확인하세요.</p>
            <p className="text-kb-text-muted">· 메시지·문자로 송금을 요구받은 경우에는 반드시 사실관계 확인 후 이체하시기 바랍니다.</p>
            <p className="font-medium" style={{ color: KB_PRIMARY }}>
              · 확인 후 5분 이내 결과화면이 나오지 않으면 [이체결과 조회]에서 이체 실행 여부를 확인하시기 바랍니다.
            </p>
          </div>

          {/* 이체정보 테이블 */}
          <div className="mb-6">
            <div className="flex justify-between items-center mb-3">
              <p className="text-[15px] font-bold text-kb-text">이체정보</p>
              <button
                onClick={() => router.push('/transfer/account')}
                className="border rounded-lg px-4 py-1.5 text-[12px] font-medium transition-colors hover:bg-kb-primary-bg"
                style={{ borderColor: KB_MINT, color: KB_PRIMARY }}>
                수정
              </button>
            </div>
            <div className="rounded-xl overflow-hidden" style={{ border: '1px solid #E2F5EF' }}>
              <table className="w-full border-collapse text-[13px]">
                <thead>
                  <tr style={{ backgroundColor: KB_PRIMARY_BG }}>
                    {['출금계좌번호', '입금계좌번호', '이체금액', '수수료', '받는분', '상태'].map(h => (
                      <th key={h} className="px-4 py-3 text-center font-semibold text-[12px]"
                        style={{ borderBottom: '2px solid #E2F5EF', color: KB_PRIMARY }}>
                        {h}
                      </th>
                    ))}
                  </tr>
                </thead>
                <tbody>
                  <tr>
                    <td className="px-4 py-4 text-center text-kb-text">{data.fromNumber}</td>
                    <td className="px-4 py-4 text-center">
                      <p className="font-medium text-kb-text">{data.toBank}</p>
                      <p className="text-kb-text-muted text-[12px]">{data.toAccount}</p>
                    </td>
                    <td className="px-4 py-4 text-right font-bold text-[15px]" style={{ color: KB_PRIMARY }}>
                      {formatNumber(data.amount)}원
                    </td>
                    <td className="px-4 py-4 text-center text-kb-text-muted">
                      {data.fee === 0 ? '면제' : `${formatNumber(data.fee)}원`}
                    </td>
                    <td className="px-4 py-4 text-center text-kb-text">{data.receiverName}</td>
                    <td className="px-4 py-4 text-center font-semibold" style={{ color: KB_MINT }}>정상</td>
                  </tr>
                </tbody>
              </table>
            </div>
          </div>

          {/* 확인/취소 버튼 */}
          <div className="flex justify-center gap-3">
            <button
              onClick={() => { setShowCertModal(true); setCertStep('info'); setPin([]) }}
              className="px-16 py-3 text-[15px] font-bold text-white rounded-xl hover:opacity-85 transition-opacity"
              style={{ backgroundColor: KB_PRIMARY }}>
              확인
            </button>
            <button
              onClick={() => router.push('/transfer/account')}
              className="border rounded-xl px-16 py-3 text-[15px] font-medium transition-colors hover:bg-kb-primary-bg"
              style={{ borderColor: '#D1D5DB', color: '#6B7280' }}>
              취소
            </button>
          </div>
        </main>
      </div>

      {/* 금융인증서 모달 */}
      {showCertModal && data && (
        <div className="fixed inset-0 bg-black/50 z-[300] flex items-center justify-center">
          <div className="bg-white rounded-2xl shadow-2xl overflow-hidden flex" style={{ width: 620, minHeight: 400 }}>

            {/* 왼쪽 - 브랜드 */}
            <div className="w-[180px] flex-shrink-0 flex flex-col items-center justify-center gap-4 p-6"
              style={{ backgroundColor: KB_PRIMARY_BG, borderRight: '1px solid #E2F5EF' }}>
              <div className="text-center">
                <div className="w-12 h-12 rounded-xl flex items-center justify-center mx-auto mb-3 text-white text-[13px] font-extrabold"
                  style={{ backgroundColor: KB_PRIMARY }}>
                  AX
                </div>
                <p className="text-[13px] font-bold" style={{ color: KB_PRIMARY }}>AXful 금융인증서</p>
              </div>
            </div>

            {/* 오른쪽 - 내용 */}
            <div className="flex-1 flex flex-col">
              <div className="flex items-center justify-between px-5 py-3" style={{ borderBottom: '1px solid #E2F5EF' }}>
                <span className="text-[13px] font-bold text-kb-text">금융인증서비스</span>
                <button onClick={() => setShowCertModal(false)} className="text-kb-text-muted hover:text-kb-text text-lg">✕</button>
              </div>

              <div className="flex-1 p-6">
                {certStep === 'info' ? (
                  <div>
                    <p className="text-[14px] font-bold text-kb-text mb-4">전자서명 원문</p>
                    <div className="rounded-xl p-4 text-[13px] space-y-1.5 mb-6" style={{ backgroundColor: KB_PRIMARY_SURFACE, border: '1px solid #E2F5EF' }}>
                      <div className="flex gap-3">
                        <span className="text-kb-text-muted w-24">이체금액</span>
                        <span className="font-semibold" style={{ color: KB_PRIMARY }}>{formatNumber(data.amount)}원</span>
                      </div>
                      <div className="flex gap-3">
                        <span className="text-kb-text-muted w-24">출금계좌</span>
                        <span className="text-kb-text">{data.fromNumber}</span>
                      </div>
                      <div className="flex gap-3">
                        <span className="text-kb-text-muted w-24">입금기관</span>
                        <span className="text-kb-text">{data.toBank}</span>
                      </div>
                      <div className="flex gap-3">
                        <span className="text-kb-text-muted w-24">입금계좌</span>
                        <span className="text-kb-text">{data.toAccount}</span>
                      </div>
                      <div className="flex gap-3">
                        <span className="text-kb-text-muted w-24">받는분</span>
                        <span className="text-kb-text">{data.receiverName}</span>
                      </div>
                    </div>
                    {/* 인증서 선택 — 여러 장이면 고를 수 있어야 한다. 예전에는 첫 장이 말없이 쓰였다. */}
                    {certs.length > 1 && (
                      <div className="mb-5">
                        <p className="text-[13px] font-bold text-kb-text mb-2">인증서 선택</p>
                        <select
                          value={selectedCert}
                          onChange={e => setSelectedCert(e.target.value)}
                          className="w-full border rounded-lg px-3 py-2 text-[13px]"
                          style={{ borderColor: '#D1D5DB' }}>
                          {certs.map(c => (
                            <option key={c.serialNumber} value={c.serialNumber}>
                              {(c.certTypeName ?? '인증서') + ' — ' + c.serialNumber}
                            </option>
                          ))}
                        </select>
                      </div>
                    )}

                    {/* 인증서가 없으면 6자리를 다 누른 뒤가 아니라 여기서 알린다.
                        막기만 하면 사용자는 어디로 가야 할지 모른다 — 발급 화면으로 바로 보낸다.
                        이체 정보는 sessionStorage 에 남아 있어 발급 후 돌아오면 이어서 할 수 있다. */}
                    {certs.length === 0 && (
                      <div className="mb-5 rounded-lg px-4 py-3 bg-red-50 border border-red-200">
                        <p className="text-[13px] text-red-700 font-bold mb-1">이체에는 인증서가 필요합니다</p>
                        <p className="text-[12px] text-red-600 mb-3">
                          금융인증서를 발급받으면 이체·조회를 안전하게 이용할 수 있습니다.
                          발급 후 이 화면으로 돌아오면 입력하신 이체 정보가 그대로 남아 있습니다.
                        </p>
                        <div className="flex gap-2">
                          <Link href="/cert/fin-cert-issue"
                            className="px-4 py-2 text-[12px] font-bold text-white rounded-lg hover:opacity-85 transition-opacity"
                            style={{ backgroundColor: KB_PRIMARY }}>
                            금융인증서 발급하기
                          </Link>
                          <Link href="/cert"
                            className="px-4 py-2 text-[12px] font-bold rounded-lg border hover:bg-kb-beige transition-colors"
                            style={{ borderColor: KB_PRIMARY, color: KB_PRIMARY }}>
                            다른 인증서 보기
                          </Link>
                        </div>
                      </div>
                    )}

                    <div className="flex justify-center">
                      <button onClick={() => { setCertStep('pin'); setPin([]); setCertError(null) }}
                        disabled={certs.length === 0}
                        className="px-16 py-2.5 text-[14px] font-bold text-white rounded-xl hover:opacity-85 transition-opacity disabled:opacity-40 disabled:cursor-not-allowed"
                        style={{ backgroundColor: KB_PRIMARY }}>
                        확인
                      </button>
                    </div>
                  </div>
                ) : (
                  <div className="flex flex-col items-center">
                    <p className="text-[13px] mb-1" style={{ color: KB_PRIMARY }}>AXful 금융인증서</p>
                    <p className="text-[16px] font-bold text-kb-text mb-2">비밀번호를 입력해주세요</p>
                    {certError && (
                      <p className="text-[13px] text-red-600 mb-2 text-center">{certError}</p>
                    )}
                    <div className="flex gap-2 mb-5 mt-3">
                      {Array.from({length:6}).map((_,i) => (
                        <div key={i}
                          className="w-9 h-9 rounded-lg border-2 flex items-center justify-center transition-colors"
                          style={i < pin.length
                            ? { backgroundColor: KB_PRIMARY, borderColor: KB_PRIMARY }
                            : { borderColor: '#D1D5DB', backgroundColor: 'white' }}>
                          {i < pin.length && <span className="text-white text-sm font-bold">●</span>}
                        </div>
                      ))}
                    </div>
                    <div className="grid grid-cols-3 gap-0.5 w-full">
                      {PIN_PAD.map((row, ri) =>
                        row.map((key, ci) => (
                          <button key={`${ri}-${ci}`} onClick={() => handlePinKey(key)}
                            className="h-12 text-[18px] font-medium transition-colors hover:bg-kb-primary-bg rounded-lg"
                            style={{ color: typeof key === 'string' ? '#9CA3AF' : '#374151' }}>
                            {key}
                          </button>
                        ))
                      )}
                    </div>
                  </div>
                )}
              </div>
            </div>
          </div>
        </div>
      )}
    </div>
  )
}
