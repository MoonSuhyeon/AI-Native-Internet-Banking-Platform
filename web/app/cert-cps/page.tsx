'use client'
import { KB_MINT, KB_PRIMARY, KB_PRIMARY_BG, KB_PRIMARY_BORDER, KB_PRIMARY_SURFACE, KB_TEXT_BODY } from '@/lib/theme'

import { useState } from 'react'

const TOC = [
  { num: '1', label: '소 개' },
  { num: '2', label: '전자서명인증업무 관련 정보의 공고' },
  { num: '3', label: '신원확인' },
  { num: '4', label: '인증서 관리' },
  { num: '5', label: '시설 및 운영관리' },
  { num: '6', label: '기술적 보호조치' },
  { num: '7', label: '인증서 형식' },
  { num: '8', label: '감사 및 평가' },
  { num: '9', label: '전자서명인증업무 보증 등 기타사항' },
  { num: '부록 A', label: '프로파일' },
  { num: '부록 B', label: '개정이력' },
]

const SECTIONS = [
  {
    num: '1',
    title: '소개',
    content: (
      <div className="space-y-6">
        <section>
          <h3 className="text-[17px] font-bold mb-3 text-kb-text">1.1 개요</h3>
          <h4 className="text-[16px] font-bold mb-2 text-kb-text">1.1.1 배경 및 목적</h4>
          <p className="text-[14px] text-kb-text-body leading-relaxed mb-4">
            AXful인증서 인증업무준칙(CPS: Certification Practice Statement)은 전자서명법 및 동법 시행령, 동법 시행규칙에 의하여 주식회사 AXful Bank(이하 &ldquo;AXful Bank&rdquo;)이 개인을 대상으로 하는 AXful인증서 및 사업자(개인사업자 및 법인사업자)를 대상으로 하는 AXful인증서(기업)(이하 &ldquo;인증서 등&rdquo;)의 발급, 관리 및 인증시스템을 운영함에 있어서 필요한 사항을 정하고, AXful Bank과 가입자 등 인증 관련 당사자의 책임과 의무사항을 규정함을 목적으로 합니다.
          </p>
          <h4 className="text-[16px] font-bold mb-2 text-kb-text">1.1.2 전자서명인증체계</h4>
          <p className="text-[14px] text-kb-text-body leading-relaxed mb-3">
            AXful Bank은 전자서명인증체계를 안전하고 신뢰성 있게 운영하기 위한 정책을 수립하고 시행하는 기관으로서 최상위인증기관(ROOT CA), 인증기관(CA)으로 전자서명인증체계를 구성하여 관리합니다.
          </p>
          <div className="space-y-2 text-[14px] text-kb-text-body leading-relaxed pl-4">
            <p className="font-semibold text-kb-text">AXful Bank 최상위 인증기관(AXful ROOT CA)</p>
            <ul className="list-disc list-inside space-y-1 pl-2 text-kb-text-muted">
              <li>안전한 전자서명 인증관리체계 구축 및 운영</li>
              <li>전자서명 인증기술의 개발 및 보급</li>
              <li>인증기관(CA) 검사 및 안전한 운영지원</li>
              <li>인증기관 전자서명생성정보에 대한 인증 등 인증업무 수행</li>
              <li>오프라인으로 관리 운영</li>
            </ul>
            <p className="font-semibold text-kb-text mt-2">AXful Bank 인증기관(AXful CA)</p>
            <ul className="list-disc list-inside space-y-1 pl-2 text-kb-text-muted">
              <li>개인 및 사업자의 신원 확인</li>
              <li>개인 및 사업자 인증서 발급, 재발급, 갱신, 폐지 업무</li>
              <li>인증서 유효성 확인 업무</li>
              <li>기타 인증기관으로서 수행해야 할 업무</li>
            </ul>
          </div>
        </section>

        <section>
          <h3 className="text-[17px] font-bold mb-2 text-kb-text">1.2 문서의 명칭</h3>
          <p className="text-[14px] text-kb-text-body leading-relaxed">
            본 문서의 명칭은 「AXful인증서 인증업무준칙」(이하 &ldquo;인증업무준칙&rdquo;이라 한다)으로 전자서명법, 동법 시행령, 동법 시행규칙을 준수합니다.
          </p>
        </section>

        <section>
          <h3 className="text-[17px] font-bold mb-2 text-kb-text">1.4 인증서 종류</h3>
          <h4 className="text-[16px] font-bold mb-3 text-kb-text">1.4.1 인증서 이용범위 및 용도</h4>
          <div className="overflow-x-auto rounded-xl" style={{ border: `1px solid ${KB_PRIMARY_BORDER}` }}>
            <table className="w-full border-collapse text-[13px]">
              <thead>
                <tr style={{ backgroundColor: KB_PRIMARY_BG }}>
                  <th className="px-4 py-2.5 text-left font-semibold" style={{ borderBottom: `2px solid ${KB_PRIMARY_BORDER}`, color: KB_PRIMARY }}>구분</th>
                  <th className="px-4 py-2.5 text-left font-semibold" style={{ borderBottom: `2px solid ${KB_PRIMARY_BORDER}`, color: KB_PRIMARY }}>용도</th>
                  <th className="px-4 py-2.5 text-left font-semibold" style={{ borderBottom: `2px solid ${KB_PRIMARY_BORDER}`, color: KB_PRIMARY }}>유효기간</th>
                </tr>
              </thead>
              <tbody className="text-kb-text-body">
                {[
                  ['AXful인증서', '전자서명인증이 필요한 모든 전자거래 업무에 이용', '발급일로부터 2년'],
                  ['AXful인증서(대면용)', '대면 업무용 서비스(마이데이터통합인증)를 위한 전자서명 업무', '발급 후 3시간'],
                  ['AXful인증서(기업)', '전자서명인증이 필요한 모든 전자거래 업무에 이용', '발급일로부터 3년'],
                ].map(([name, desc, period], i) => (
                  <tr key={name} style={{ backgroundColor: i % 2 === 1 ? KB_PRIMARY_SURFACE : 'white', borderTop: `1px solid ${KB_PRIMARY_BORDER}` }}>
                    <td className="px-4 py-2.5">{name}</td>
                    <td className="px-4 py-2.5">{desc}</td>
                    <td className="px-4 py-2.5 whitespace-nowrap">{period}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </section>

        <section>
          <h3 className="text-[17px] font-bold mb-2 text-kb-text">1.5 준칙의 관리</h3>
          <div className="space-y-1 text-[14px] text-kb-text-body">
            <p><span className="font-semibold text-kb-text">부서:</span> 인증사업부(P)</p>
            <p><span className="font-semibold text-kb-text">이메일:</span> admin@axful.com</p>
            <p><span className="font-semibold text-kb-text">주소:</span> 서울특별시 중구 태평로1길 1(AXful동) AXful Bank</p>
          </div>
        </section>
      </div>
    ),
  },
  {
    num: '2', title: '전자서명인증업무 관련 정보의 공고',
    content: (
      <div className="space-y-4 text-[14px] text-kb-text-body leading-relaxed">
        <section>
          <h3 className="text-[16px] font-bold mb-2 text-kb-text">2.1 저장소</h3>
          <p>AXful Bank는 인증업무준칙 및 관련 정책 문서를 공식 웹사이트(<span className="font-medium text-kb-text">https://www.axful.com/cert-cps</span>)를 통해 공고합니다. 저장소는 연중무휴 24시간 접근 가능하도록 운영됩니다.</p>
        </section>
        <section>
          <h3 className="text-[16px] font-bold mb-2 text-kb-text">2.2 공고 정보</h3>
          <ul className="list-disc list-inside space-y-1 pl-2 text-kb-text-muted">
            <li>인증업무준칙(CPS) 최신 버전 및 개정 이력</li>
            <li>인증서 폐기 목록(CRL) 및 OCSP 서비스 정보</li>
            <li>AXful Bank 인증기관(CA) 인증서</li>
            <li>인증서 정책(CP) 식별자(OID)</li>
          </ul>
        </section>
        <section>
          <h3 className="text-[16px] font-bold mb-2 text-kb-text">2.3 공고 주기</h3>
          <p>인증업무준칙은 중요 변경사항 발생 시 즉시 개정·공고하며, 변경이 없는 경우에도 매년 1회 이상 검토합니다.</p>
        </section>
      </div>
    ),
  },
  {
    num: '3', title: '신원확인',
    content: (
      <div className="space-y-4 text-[14px] text-kb-text-body leading-relaxed">
        <section>
          <h3 className="text-[16px] font-bold mb-2 text-kb-text">3.1 초기 등록 시 신원확인</h3>
          <p className="mb-2">인증서 발급을 신청하는 가입자의 신원확인은 다음 방법 중 하나로 수행합니다.</p>
          <ul className="list-disc list-inside space-y-1 pl-2 text-kb-text-muted">
            <li>공인된 신분증(주민등록증, 운전면허증, 여권) 직접 대면 확인</li>
            <li>AXful Bank 인터넷뱅킹 로그인 후 본인 인증</li>
            <li>휴대폰 본인인증 서비스를 통한 실명 확인</li>
          </ul>
        </section>
        <section>
          <h3 className="text-[16px] font-bold mb-2 text-kb-text">3.2 재발급 시 신원확인</h3>
          <p>기존 유효 인증서 보유자의 재발급 요청 시, 기존 인증서를 활용한 전자서명으로 본인 확인을 갈음할 수 있습니다.</p>
        </section>
        <section>
          <h3 className="text-[16px] font-bold mb-2 text-kb-text">3.3 폐기 요청 시 신원확인</h3>
          <p>인증서 폐기는 가입자 본인, AXful Bank 고객센터(전화·방문), 또는 AXful 뱅킹 앱에서 신청 가능하며, 각 채널별 본인확인 절차를 준수합니다.</p>
        </section>
      </div>
    ),
  },
  {
    num: '4', title: '인증서 관리',
    content: (
      <div className="space-y-4 text-[14px] text-kb-text-body leading-relaxed">
        <section>
          <h3 className="text-[16px] font-bold mb-2 text-kb-text">4.1 인증서 발급</h3>
          <p>신원확인 완료 후 영업일 기준 즉시 발급을 원칙으로 합니다. 발급된 인증서는 가입자에게 안전한 채널을 통해 전달됩니다.</p>
        </section>
        <section>
          <h3 className="text-[16px] font-bold mb-2 text-kb-text">4.2 인증서 갱신</h3>
          <p>유효기간 만료 30일 전부터 갱신 신청이 가능합니다. 갱신 시 신원확인 절차를 재수행하며, 기존 인증서의 유효기간 내 갱신한 경우 잔여 기간은 이월되지 않습니다.</p>
        </section>
        <section>
          <h3 className="text-[16px] font-bold mb-2 text-kb-text">4.3 인증서 폐기</h3>
          <p className="mb-1">다음의 경우 즉시 인증서를 폐기합니다.</p>
          <ul className="list-disc list-inside space-y-1 pl-2 text-kb-text-muted">
            <li>가입자의 폐기 요청</li>
            <li>개인키 분실 또는 유출이 의심되는 경우</li>
            <li>가입자 정보 변경으로 인증서 내용이 부정확해진 경우</li>
            <li>법원 명령 또는 관계 법령에 의한 폐기 요청</li>
          </ul>
        </section>
        <section>
          <h3 className="text-[16px] font-bold mb-2 text-kb-text">4.4 인증서 유효기간</h3>
          <div className="overflow-x-auto" style={{ border: `1px solid ${KB_PRIMARY_BORDER}`, borderRadius: 8 }}>
            <table className="w-full border-collapse text-[13px]">
              <thead>
                <tr style={{ backgroundColor: KB_PRIMARY_BG }}>
                  {['인증서 종류', '유효기간'].map(h => (
                    <th key={h} className="px-4 py-2 text-left font-semibold" style={{ borderBottom: `2px solid ${KB_PRIMARY_BORDER}`, color: KB_PRIMARY }}>{h}</th>
                  ))}
                </tr>
              </thead>
              <tbody>
                {[['AXful인증서', '2년'], ['AXful인증서(기업)', '3년'], ['금융인증서', '3년']].map(([name, period], i) => (
                  <tr key={name} style={{ backgroundColor: i % 2 === 1 ? KB_PRIMARY_SURFACE : 'white', borderTop: `1px solid ${KB_PRIMARY_BORDER}` }}>
                    <td className="px-4 py-2">{name}</td>
                    <td className="px-4 py-2">{period}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </section>
      </div>
    ),
  },
  {
    num: '5', title: '시설 및 운영관리',
    content: (
      <div className="space-y-4 text-[14px] text-kb-text-body leading-relaxed">
        <section>
          <h3 className="text-[16px] font-bold mb-2 text-kb-text">5.1 물리적 보안</h3>
          <p>AXful Bank 인증시스템은 금융감독원 전자금융감독규정을 준수하는 데이터센터에 위치합니다. 출입 통제, 이중 잠금장치, CCTV 24시간 모니터링을 운영합니다.</p>
        </section>
        <section>
          <h3 className="text-[16px] font-bold mb-2 text-kb-text">5.2 운영 인력</h3>
          <p>인증시스템 운영은 보안 전문 교육을 이수한 인가된 인원만이 수행할 수 있으며, 2인 이상의 인원이 동시에 참여하는 이중 통제 원칙을 적용합니다.</p>
        </section>
        <section>
          <h3 className="text-[16px] font-bold mb-2 text-kb-text">5.3 백업 및 복구</h3>
          <p>인증 데이터는 일 1회 이상 오프사이트 백업을 수행하며, 재해복구(DR) 시스템을 통해 서비스 연속성을 보장합니다. 목표 복구 시간(RTO)은 4시간 이내입니다.</p>
        </section>
      </div>
    ),
  },
  {
    num: '6', title: '기술적 보호조치',
    content: (
      <div className="space-y-4 text-[14px] text-kb-text-body leading-relaxed">
        <section>
          <h3 className="text-[16px] font-bold mb-2 text-kb-text">6.1 키 생성 및 보관</h3>
          <p>인증기관(CA) 개인키는 FIPS 140-2 Level 3 이상의 하드웨어 보안 모듈(HSM)에서 생성 및 보관됩니다. 키 생성 의식은 감사인 입회 하에 수행됩니다.</p>
        </section>
        <section>
          <h3 className="text-[16px] font-bold mb-2 text-kb-text">6.2 암호 알고리즘</h3>
          <ul className="list-disc list-inside space-y-1 pl-2 text-kb-text-muted">
            <li>서명 알고리즘: ECDSA P-256 / RSA-2048 이상</li>
            <li>해시 알고리즘: SHA-256 이상</li>
            <li>대칭키 암호: AES-256</li>
          </ul>
        </section>
        <section>
          <h3 className="text-[16px] font-bold mb-2 text-kb-text">6.3 가입자 키 보호</h3>
          <p>가입자 개인키는 가입자 기기 내 안전한 저장소(스마트폰 보안 영역 또는 PC 암호화 저장소)에 보관되며, AXful Bank는 가입자 개인키에 접근하지 않습니다.</p>
        </section>
      </div>
    ),
  },
  {
    num: '7', title: '인증서 형식',
    content: (
      <div className="space-y-4 text-[14px] text-kb-text-body leading-relaxed">
        <section>
          <h3 className="text-[16px] font-bold mb-2 text-kb-text">7.1 인증서 프로파일</h3>
          <p className="mb-2">AXful Bank 발급 인증서는 ITU-T X.509 v3 표준을 준수합니다. 주요 확장 필드는 다음과 같습니다.</p>
          <ul className="list-disc list-inside space-y-1 pl-2 text-kb-text-muted">
            <li>Subject Alternative Name (SAN)</li>
            <li>Key Usage / Extended Key Usage</li>
            <li>CRL Distribution Points</li>
            <li>Authority Information Access (OCSP)</li>
            <li>Certificate Policies (OID: 1.2.410.200xxx)</li>
          </ul>
        </section>
        <section>
          <h3 className="text-[16px] font-bold mb-2 text-kb-text">7.2 CRL 프로파일</h3>
          <p>인증서 폐기 목록(CRL)은 X.509 v2 형식으로 발급되며, 최소 24시간마다 갱신 발행됩니다.</p>
        </section>
        <section>
          <h3 className="text-[16px] font-bold mb-2 text-kb-text">7.3 OCSP 프로파일</h3>
          <p>실시간 인증서 상태 확인을 위한 OCSP 서비스는 RFC 6960을 준수하며, 응답 서명에 전용 OCSP 서명 인증서를 사용합니다.</p>
        </section>
      </div>
    ),
  },
  {
    num: '8', title: '감사 및 평가',
    content: (
      <div className="space-y-4 text-[14px] text-kb-text-body leading-relaxed">
        <section>
          <h3 className="text-[16px] font-bold mb-2 text-kb-text">8.1 감사 주기</h3>
          <p>AXful Bank는 인증업무에 대한 내부 감사를 연 1회 이상 수행하고, 전자서명법 제24조에 따른 외부 평가기관에 의한 적합성 평가를 정기적으로 받습니다.</p>
        </section>
        <section>
          <h3 className="text-[16px] font-bold mb-2 text-kb-text">8.2 감사 기록 보관</h3>
          <p>인증업무 관련 로그 및 감사 기록은 최소 5년간 안전하게 보관하며, 접근 권한을 엄격히 제한합니다.</p>
        </section>
        <section>
          <h3 className="text-[16px] font-bold mb-2 text-kb-text">8.3 보안 사고 대응</h3>
          <p>보안 사고 발생 시 즉각적인 대응 절차를 실행하고, 금융감독원 및 관계 기관에 규정된 기한 내 보고합니다. 사고 원인 분석 및 재발 방지 대책을 수립·이행합니다.</p>
        </section>
      </div>
    ),
  },
  {
    num: '9', title: '전자서명인증업무 보증 등 기타사항',
    content: (
      <div className="space-y-4 text-[14px] text-kb-text-body leading-relaxed">
        <section>
          <h3 className="text-[16px] font-bold mb-2 text-kb-text">9.1 요금 및 환급</h3>
          <p>AXful인증서 발급 수수료는 무료입니다. 단, 기업 인증서의 경우 별도 수수료 정책이 적용될 수 있으며, 요금표는 공식 홈페이지에 공고합니다.</p>
        </section>
        <section>
          <h3 className="text-[16px] font-bold mb-2 text-kb-text">9.2 책임의 한계</h3>
          <p>AXful Bank는 가입자의 귀책 사유로 발생한 손해, 불가항력에 의한 손해, 인증서의 허용 한도를 초과한 거래로 인한 손해에 대해서는 책임을 지지 않습니다.</p>
        </section>
        <section>
          <h3 className="text-[16px] font-bold mb-2 text-kb-text">9.3 준거법 및 분쟁 해결</h3>
          <p>본 준칙은 대한민국 법률에 따라 해석되며, 분쟁 발생 시 서울중앙지방법원을 제1심 관할 법원으로 합니다.</p>
        </section>
        <section>
          <h3 className="text-[16px] font-bold mb-2 text-kb-text">9.4 준칙의 개정</h3>
          <p>본 준칙의 중요 변경사항은 변경 30일 전 공고를 원칙으로 하며, 법령 변경 등 긴급한 경우 즉시 개정·시행할 수 있습니다.</p>
        </section>
      </div>
    ),
  },
  {
    num: '부록 A', title: '프로파일',
    content: (
      <div className="space-y-4 text-[14px] text-kb-text-body leading-relaxed">
        <section>
          <h3 className="text-[16px] font-bold mb-3 text-kb-text">A.1 AXful인증서 인증서 프로파일</h3>
          <div className="overflow-x-auto" style={{ border: `1px solid ${KB_PRIMARY_BORDER}`, borderRadius: 8 }}>
            <table className="w-full border-collapse text-[13px]">
              <thead>
                <tr style={{ backgroundColor: KB_PRIMARY_BG }}>
                  {['필드', '값'].map(h => (
                    <th key={h} className="px-4 py-2 text-left font-semibold" style={{ borderBottom: `2px solid ${KB_PRIMARY_BORDER}`, color: KB_PRIMARY }}>{h}</th>
                  ))}
                </tr>
              </thead>
              <tbody>
                {[
                  ['버전', 'v3 (X.509)'],
                  ['직렬번호', '고유 일련번호 (20바이트)'],
                  ['서명 알고리즘', 'ecdsa-with-SHA256'],
                  ['유효기간', '발급일로부터 2년'],
                  ['주체 공개키', 'EC P-256'],
                  ['키 용도', 'digitalSignature, nonRepudiation'],
                ].map(([field, val], i) => (
                  <tr key={field} style={{ backgroundColor: i % 2 === 1 ? KB_PRIMARY_SURFACE : 'white', borderTop: `1px solid ${KB_PRIMARY_BORDER}` }}>
                    <td className="px-4 py-2 font-medium text-kb-text">{field}</td>
                    <td className="px-4 py-2 text-kb-text-muted">{val}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </section>
      </div>
    ),
  },
  {
    num: '부록 B', title: '개정이력',
    content: (
      <div className="space-y-4 text-[14px] text-kb-text-body leading-relaxed">
        <div className="overflow-x-auto" style={{ border: `1px solid ${KB_PRIMARY_BORDER}`, borderRadius: 8 }}>
          <table id="cps-revisions" className="w-full border-collapse text-[13px]">
            <thead>
              <tr style={{ backgroundColor: KB_PRIMARY_BG }}>
                {['버전', '개정일', '주요 내용'].map(h => (
                  <th key={h} className="px-4 py-2 text-left font-semibold" style={{ borderBottom: `2px solid ${KB_PRIMARY_BORDER}`, color: KB_PRIMARY }}>{h}</th>
                ))}
              </tr>
            </thead>
            <tbody>
              {[
                ['Ver.1.0.14', '2026.02.24', '암호 알고리즘 업데이트 (ECDSA P-256 추가), 유효기간 조정'],
                ['Ver.1.0.13', '2025.08.01', '금융인증서 관련 조항 신설, 9.2 책임 한계 조항 명확화'],
                ['Ver.1.0.12', '2025.01.15', 'AXful인증서(기업) 유효기간 3년으로 변경'],
                ['Ver.1.0.11', '2024.06.30', '전자서명법 시행령 개정에 따른 신원확인 조항 수정'],
                ['Ver.1.0.10', '2024.01.02', '최초 제정 및 시행'],
              ].map(([ver, date, desc], i) => (
                <tr key={ver} style={{ backgroundColor: i % 2 === 1 ? KB_PRIMARY_SURFACE : 'white', borderTop: `1px solid ${KB_PRIMARY_BORDER}` }}>
                  <td className="px-4 py-2 font-medium text-kb-text whitespace-nowrap">{ver}</td>
                  <td className="px-4 py-2 text-kb-text-muted whitespace-nowrap">{date}</td>
                  <td className="px-4 py-2 text-kb-text-muted">{desc}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </div>
    ),
  },
]

export default function CertCpsPage() {
  const [openSections, setOpenSections] = useState<Set<string>>(new Set(['1']))

  function toggle(num: string) {
    setOpenSections((prev) => {
      const next = new Set(prev)
      if (next.has(num)) { next.delete(num) } else { next.add(num) }
      return next
    })
  }

  return (
    <div>

      {/* 제목 + 버전 */}
      <div className="flex items-center justify-between mb-6">
        <h1 className="text-[26px] font-bold text-kb-text">AXful인증서 인증업무준칙(CPS)</h1>
        {/* ▼ 는 고를 것이 있다는 표시인데 목록이 없었다. 개정 이력 표가 이 페이지
            안에 있으므로 그리로 보낸다 — 버전을 확인하려는 사람이 찾는 것이 그 표다. */}
        <a href="#cps-revisions"
          className="flex items-center gap-2 border rounded-lg px-4 py-2 text-[13px] font-medium transition-colors hover:bg-kb-primary-bg"
          style={{ borderColor: KB_MINT, color: KB_PRIMARY }}>
          인증업무준칙 (Ver.1.0.14)
          <span className="text-[10px]">▾ 개정 이력</span>
        </a>
      </div>

      {/* 목차 */}
      <div className="rounded-xl p-5 mb-8 space-y-1.5" style={{ backgroundColor: KB_PRIMARY_SURFACE, border: `1px solid ${KB_PRIMARY_BORDER}` }}>
        <p className="text-[13px] font-bold mb-2" style={{ color: KB_PRIMARY }}>목차</p>
        {TOC.map((item) => (
          <p key={item.num} className="text-[13px] text-kb-text-muted">
            <span className="font-medium text-kb-text">{item.num}.</span> {item.label}
          </p>
        ))}
      </div>

      <hr className="mb-6" style={{ borderColor: KB_PRIMARY_BORDER }} />

      {/* 섹션 목록 */}
      <div className="space-y-2">
        {SECTIONS.map((section) => {
          const isOpen = openSections.has(section.num)
          return (
            <div key={section.num} className="rounded-xl overflow-hidden" style={{ border: `1px solid ${KB_PRIMARY_BORDER}` }}>
              <button
                onClick={() => toggle(section.num)}
                className="w-full flex items-center justify-between px-5 py-4 text-left transition-colors"
                style={{ backgroundColor: isOpen ? KB_PRIMARY_BG : 'white' }}
                onMouseEnter={e => { if (!isOpen) (e.currentTarget as HTMLElement).style.backgroundColor = KB_PRIMARY_SURFACE }}
                onMouseLeave={e => { if (!isOpen) (e.currentTarget as HTMLElement).style.backgroundColor = 'white' }}
              >
                <span className="text-[14px] font-bold" style={{ color: isOpen ? KB_PRIMARY : KB_TEXT_BODY }}>
                  {section.num}. {section.title}
                </span>
                <span className="text-[11px] text-kb-text-muted">{isOpen ? '▲' : '▼'}</span>
              </button>

              {isOpen && (
                <div className="px-6 py-5 bg-white" style={{ borderTop: `1px solid ${KB_PRIMARY_BORDER}` }}>
                  {section.content ?? (
                    <p className="text-[13px] text-kb-text-muted italic">해당 섹션의 내용은 준비 중입니다.</p>
                  )}
                </div>
              )}
            </div>
          )
        })}
      </div>

      {/* 부칙 */}
      <div className="mt-6 rounded-xl px-5 py-4 space-y-1" style={{ border: `1px solid ${KB_PRIMARY_BORDER}`, backgroundColor: KB_PRIMARY_SURFACE }}>
        <p className="text-[14px] font-bold text-kb-text">부칙</p>
        <p className="text-[13px] font-medium" style={{ color: KB_PRIMARY }}>이 준칙은 2026년 02월 24일부터 시행합니다.</p>
      </div>
    </div>
  )
}
