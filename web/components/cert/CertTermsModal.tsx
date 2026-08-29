'use client'
import { KB_PRIMARY } from '@/lib/theme'

import { createPortal } from 'react-dom'

/**
 * 인증서 발급에서 받는 약관.
 *
 * `termsNo` 는 서버 약관 템플릿(customer-service V43 시드)을 가리킨다. 이 값으로
 * 동의가 기록되므로 바꾸면 그 항목의 동의가 끊긴다 — 등록되지 않은 약관은 서버가
 * 거절하고, 그러면 그 화면의 동의가 통째로 남지 않는다.
 */
export const CERT_TERMS = [
  { termsNo: 'CERT-001', label: '전자금융거래기본약관' },
  { termsNo: 'CERT-002', label: '전자금융서비스이용약관' },
  { termsNo: 'CERT-003', label: '전자금융서비스설명서' },
  { termsNo: 'CERT-004', label: '개인(신용)정보 수집·이용 동의서\n(개인 공동/금융인증서 발급용)' },
  { termsNo: 'CERT-005', label: '고유식별정보 수집·이용 동의서\n(개인 공동/금융인증서 발급용)' },
]

function TermContent({ index }: { index: number }) {
  if (index === 0) return (
    <div className="text-[13px] leading-relaxed text-kb-text-body space-y-4">
      <h2 className="text-[17px] font-bold text-center mb-6">전자금융거래기본약관</h2>
      <div>
        <p className="font-bold mb-1">제1조(목적)</p>
        <p>이 약관은 AXful Bank(이하 &ldquo;은행&rdquo;이라 합니다.)과 이용자 사이의 전자금융거래에 관한 기본적인 사항을 정함으로써, 거래의 신속하고 효율적인 처리를 도모하고 거래당사자 상호간의 이해관계를 합리적으로 조정하는 것을 목적으로 합니다.</p>
      </div>
      <div>
        <p className="font-bold mb-1">제2조(용어의 정의)</p>
        <p className="mb-2">① 이 약관에서 사용하는 용어의 의미는 다음 각 호와 같습니다.</p>
        <ol className="list-decimal ml-5 space-y-2">
          <li>&ldquo;전자금융거래&rdquo;라 함은 은행이 전자적 장치를 통하여 제공하는 금융상품 및 서비스를 이용자가 전자적 장치를 통하여 비대면·자동화된 방식으로 직접 이용하는 거래를 말합니다.</li>
          <li>&ldquo;이용자&rdquo;라 함은 전자금융거래를 위하여 은행과 체결한 계약에 따라 전자금융거래를 이용하는 고객을 말합니다.</li>
          <li>&ldquo;접근매체&rdquo;라 함은 전자금융거래에 있어서 거래지시를 하거나 이용자 및 거래내용의 진정성을 확보하기 위하여 사용되는 수단 또는 정보를 말합니다.</li>
        </ol>
      </div>
      <div>
        <p className="font-bold mb-1">제3조(약관의 명시 및 변경)</p>
        <p className="mb-1">① 은행은 이 약관을 영업점 및 AXful Bank 인터넷뱅킹 홈페이지에 게시합니다.</p>
        <p>② 은행은 이 약관을 변경하는 경우 변경일로부터 1개월 전에 영업점 및 홈페이지에 게시합니다.</p>
      </div>
    </div>
  )

  if (index === 1) return (
    <div className="text-[13px] leading-relaxed text-kb-text-body space-y-4">
      <h2 className="text-[17px] font-bold text-center mb-6">전자금융서비스 이용약관</h2>
      <div>
        <p className="font-bold mb-1">제 1 조(목적)</p>
        <p>이 약관은 AXful Bank(이하 &ldquo;은행&rdquo;이라 한다)과 전자금융서비스(인터넷뱅킹, 모바일뱅킹, 폰뱅킹, 이하 서비스라 한다)를 이용하는 고객(이하 &ldquo;이용자&rdquo;라 한다)사이의 서비스 이용에 관한 제반 사항을 정함을 목적으로 한다.</p>
      </div>
      <div>
        <p className="font-bold mb-1">제 2 조(용어의 정의)</p>
        <p className="mb-2">① 이 약관에서 사용하는 용어의 의미는 다음 각 호와 같다.</p>
        <ol className="list-decimal ml-5 space-y-2">
          <li>&ldquo;인터넷뱅킹&rdquo;이라 함은 인터넷이 가능한 이용매체를 이용하여 계좌조회, 이체, 신규, 해지, 대출, 외화송금 등의 은행업무를 언제 어디서나 편리하게 이용할 수 있는 서비스를 말한다.</li>
          <li>&ldquo;모바일뱅킹&rdquo;이라 함은 휴대기기(스마트폰, 태블릿PC 등 모바일기기 포함)를 통하여 제공되는 계좌조회, 이체, 신규, 해지, 대출, 외화송금 등의 은행업무를 이용할 수 있는 서비스를 말한다.</li>
          <li>&ldquo;간편비밀번호&rdquo; 또는 &ldquo;PIN&rdquo;이란 전자금융서비스 이용시 이용자의 본인확인수단으로서 이용자가 직접 지정한 6~8자리의 숫자가 조합된 개인인증번호를 말한다.</li>
        </ol>
      </div>
      <div>
        <p className="font-bold mb-1">제 3 조(약관의 적용)</p>
        <p>① 서비스의 이용에 관하여 이 약관에 명시되지 아니한 사항은 전자금융거래법 및 관계법령, 전자금융거래기본약관, 예금거래기본약관(가계용/기업용), 외환거래 약관 등 관련 약관의 규정에 따른다.</p>
      </div>
    </div>
  )

  if (index === 2) return (
    <div className="text-[13px] leading-relaxed text-kb-text-body space-y-4">
      <h2 className="text-[17px] font-bold text-center mb-6">전자금융서비스설명서</h2>
      <p className="text-[12px] text-kb-text-muted mb-4">
        KB 국민은행 준법감시인 심의필 제 2026-2089 호 (심의일: 2026.05.14)&nbsp;&nbsp;[유효기간: 2026.05.14 ~ 2028.05.13]
      </p>
      <div>
        <p className="font-bold mb-2">1 서비스 개요 및 특징</p>
        <ul className="space-y-1.5 ml-3">
          <li>• 인터넷뱅킹은 PC 등 인터넷이 가능한 이용매체를 이용하여 계좌조회, 이체, 신규, 해지, 대출, 외화송금 등의 은행업무를 이용할 수 있는 서비스입니다.</li>
          <li>• 모바일뱅킹은 휴대기기(스마트폰, 태블릿PC 등 모바일기기)를 통해 제공되는 계좌조회, 이체, 신규, 해지, 대출, 외화송금 등의 은행업무를 이용할 수 있는 서비스입니다.</li>
          <li>• 바이오인증은 지문, 음성, 정맥 등 이용자의 생체정보를 본인의 전자적장치(스마트폰 등)에 미리 저장하여 은행이 확인하는 본인인증방법입니다.</li>
          <li>• 알림서비스는 고객이 지정한 휴대기기(스마트폰, 태블릿PC 등 모바일기기)를 통하여 고객의 입출금거래내역, 금융정보, 마케팅, 보안정보 등을 Push 알림을 통해 무료로 제공하는 서비스입니다.</li>
        </ul>
      </div>
      <div>
        <p className="font-bold mb-2">2 인증서 이용 안내</p>
        <ul className="space-y-1.5 ml-3">
          <li>• 인터넷뱅킹 이용을 위해서는 공동인증서(구 공인인증서), 금융인증서, AXful인증서 중 하나가 필요합니다.</li>
          <li>• 인증서 유효기간 만료 전 갱신하시기 바랍니다.</li>
          <li>• 인증서 비밀번호는 타인에게 알려주시면 안 됩니다.</li>
        </ul>
      </div>
    </div>
  )

  if (index === 3) return (
    <div className="text-[13px] leading-relaxed text-kb-text-body">
      <h2 className="text-[15px] font-bold text-center mb-1">[필수] 개인(신용)정보 수집·이용 동의서</h2>
      <h2 className="text-[15px] font-bold text-center mb-4">(개인 공동/금융인증서 발급용)</h2>
      <p className="font-bold mb-2">AXful Bank 귀중</p>
      <p className="mb-4 text-[12px]">* 귀 행과의 공동/금융인증서 발급 관련하여 귀 행이 본인의 개인(신용)정보를 수집·이용하고자 하는 경우에는 「신용정보의 이용 및 보호에 관한 법률」,「개인정보보호법」, 동 관계 법령에 따라 본인의 동의가 필요합니다.</p>

      <table className="w-full border-collapse text-[12px] mb-5">
        <tbody>
          <tr>
            <td className="border border-kb-border px-3 py-3 font-bold text-center w-28 align-top bg-kb-primary-bg">수집·이용 목적</td>
            <td className="border border-kb-border px-4 py-3">– 금융결제원 인증서 발급 업무를 위한 온라인 신원확인</td>
          </tr>
          <tr>
            <td className="border border-kb-border px-3 py-3 font-bold text-center align-top bg-kb-primary-bg">보유 및 이용기간</td>
            <td className="border border-kb-border px-4 py-3">– 신원확인 완료 시까지 보유·이용됩니다.</td>
          </tr>
          <tr>
            <td className="border border-kb-border px-3 py-3 font-bold text-center align-top bg-kb-primary-bg">거부권리및불이익</td>
            <td className="border border-kb-border px-4 py-3">귀하는 동의를 거부하실 수 있습니다. 다만, 위 개인(신용)정보 수집·이용에 관한 동의는 공동/금융인증서 발급을 위한 필수적 사항이므로, 위 사항에 동의하셔야만 공동/금융인증서 발급 업무가 가능합니다.</td>
          </tr>
        </tbody>
      </table>

      <p className="font-bold mb-2">수집·이용 항목</p>
      <table className="w-full border-collapse text-[12px]">
        <tbody>
          <tr>
            <td className="border border-kb-border px-3 py-3 font-bold align-top bg-kb-primary-bg w-[130px]">
              개인(신용)정보<br />
              <span className="font-normal text-[11px]">└ 일반개인정보</span><br />
              <span className="font-normal text-[11px]">└ 신용거래정보</span>
            </td>
            <td className="border border-kb-border px-4 py-3">
              전자금융거래ID, 전화번호(휴대폰번호, 자택번호, 직장번호), 계좌비밀번호<br />
              계좌번호<br />
              위 개인(신용)정보 수집·이용에 동의하십니까?&nbsp;&nbsp;
              <label className="inline-flex items-center gap-1 cursor-pointer">
                <input type="radio" name="cert_privacy_agree" readOnly /> 동의하지 않음
              </label>
              &nbsp;&nbsp;
              <label className="inline-flex items-center gap-1 cursor-pointer">
                <input type="radio" name="cert_privacy_agree" defaultChecked readOnly /> 동의함
              </label>
            </td>
          </tr>
        </tbody>
      </table>
    </div>
  )

  return (
    <div className="text-[13px] leading-relaxed text-kb-text-body">
      <h2 className="text-[15px] font-bold text-center mb-1">[필수] 고유식별정보 수집·이용 동의서</h2>
      <h2 className="text-[15px] font-bold text-center mb-4">(개인 공동/금융인증서 발급용)</h2>
      <p className="font-bold mb-2">AXful Bank 귀중</p>
      <p className="mb-4 text-[12px]">* 귀 행과의 공동/금융인증서 발급 관련하여 귀 행이 본인의 고유식별정보를 수집·이용하고자 하는 경우에는 「개인정보보호법」 제24조에 따라 본인의 동의가 필요합니다.</p>

      <table className="w-full border-collapse text-[12px] mb-5">
        <tbody>
          <tr>
            <td className="border border-kb-border px-3 py-3 font-bold text-center w-28 align-top bg-kb-primary-bg">수집·이용 목적</td>
            <td className="border border-kb-border px-4 py-3">– 공동/금융인증서 발급 업무를 위한 신원 확인</td>
          </tr>
          <tr>
            <td className="border border-kb-border px-3 py-3 font-bold text-center align-top bg-kb-primary-bg">보유 및 이용기간</td>
            <td className="border border-kb-border px-4 py-3">– 신원확인 완료 시까지 보유·이용됩니다.</td>
          </tr>
          <tr>
            <td className="border border-kb-border px-3 py-3 font-bold text-center align-top bg-kb-primary-bg">거부권리및불이익</td>
            <td className="border border-kb-border px-4 py-3">귀하는 동의를 거부하실 수 있습니다. 다만, 위 고유식별정보 수집·이용에 관한 동의는 공동/금융인증서 발급을 위한 필수적 사항이므로, 위 사항에 동의하셔야만 공동/금융인증서 발급 업무가 가능합니다.</td>
          </tr>
        </tbody>
      </table>

      <p className="font-bold mb-2">수집·이용 항목</p>
      <table className="w-full border-collapse text-[12px]">
        <tbody>
          <tr>
            <td className="border border-kb-border px-3 py-3 font-bold align-top bg-kb-primary-bg w-28">고유식별정보</td>
            <td className="border border-kb-border px-4 py-3">
              – 주민등록번호, 여권번호, 외국인등록번호<br />
              위 고유식별정보 수집·이용에 동의하십니까?&nbsp;&nbsp;
              <label className="inline-flex items-center gap-1 cursor-pointer">
                <input type="radio" name="cert_uid_agree" readOnly /> 동의하지 않음
              </label>
              &nbsp;&nbsp;
              <label className="inline-flex items-center gap-1 cursor-pointer">
                <input type="radio" name="cert_uid_agree" defaultChecked readOnly /> 동의함
              </label>
            </td>
          </tr>
        </tbody>
      </table>
    </div>
  )
}

interface CertTermsModalProps {
  termIndex: number
  onClose: () => void
  onAgreeOne: (index: number) => void
  onAgreeAll: () => void
}

export function CertTermsModal({ termIndex, onClose, onAgreeOne, onAgreeAll }: CertTermsModalProps) {
  return createPortal(
    <div className="fixed inset-0 bg-black/50 flex items-center justify-center z-[9999]">
      <div className="bg-white w-[720px] max-h-[85vh] flex flex-col border border-kb-border shadow-2xl relative">
        {/* 헤더 */}
        <div className="flex items-center justify-between px-5 py-3 border-b border-kb-border bg-kb-primary-bg">
          <div className="flex items-center gap-2">
            <svg viewBox="0 0 20 20" fill="none" className="w-4 h-4" stroke="currentColor" strokeWidth="1.5">
              <line x1="3" y1="2" x2="3" y2="18"/><line x1="3" y1="2" x2="16" y2="2"/>
              <line x1="3" y1="10" x2="12" y2="10"/>
            </svg>
            <span className="text-[13px] font-bold text-kb-text">약관동의 및 사용자 본인 확인</span>
          </div>
          <div className="flex items-center gap-2">
            <span className="text-[11px] text-kb-text-muted">{termIndex + 1} / {CERT_TERMS.length}</span>
            <button onClick={onClose} className="text-kb-text-muted hover:text-kb-text text-[18px] leading-none ml-2">✕</button>
          </div>
        </div>

        {/* 본문 */}
        <div className="flex-1 overflow-y-auto px-10 py-6 relative">
          <div className="flex justify-end mb-3">
            <div className="border border-kb-border px-3 py-1 text-[11px] text-kb-text-muted">AXful Bank</div>
          </div>
          <TermContent index={termIndex} />
        </div>

        {/* 이전 화살표 */}
        {termIndex > 0 && (
          <button onClick={() => onAgreeOne(termIndex - 1)}
            className="absolute left-3 top-1/2 -translate-y-1/2 w-10 h-10 rounded-full bg-kb-border/80 hover:bg-kb-border flex items-center justify-center text-white text-[18px] font-bold shadow">
            ‹
          </button>
        )}
        {/* 다음 화살표 */}
        {termIndex < CERT_TERMS.length - 1 && (
          <button onClick={() => onAgreeOne(termIndex + 1)}
            className="absolute right-3 top-1/2 -translate-y-1/2 w-10 h-10 rounded-full hover:opacity-85 flex items-center justify-center text-white text-[18px] font-bold shadow" style={{ backgroundColor: KB_PRIMARY }}>
            ›
          </button>
        )}

        {/* 하단 버튼 */}
        <div className="px-5 py-4 border-t border-kb-border flex items-center justify-between">
          <button onClick={() => { onAgreeOne(termIndex); onClose() }}
            className="border border-kb-border px-6 py-2 text-[13px] text-kb-text-body hover:bg-kb-primary-bg">
            이 약관만 동의
          </button>
          <button onClick={onAgreeAll}
            className="flex items-center gap-2 px-8 py-2.5 text-[14px] font-bold text-white rounded-lg hover:opacity-85 transition-opacity" style={{ backgroundColor: KB_PRIMARY }}>
            <svg viewBox="0 0 16 16" fill="none" className="w-4 h-4" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round">
              <polyline points="3,8 6.5,11.5 13,4.5"/>
            </svg>
            전체동의
          </button>
        </div>
      </div>
    </div>,
    document.body
  )
}
