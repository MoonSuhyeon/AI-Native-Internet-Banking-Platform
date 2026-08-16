/* eslint-disable @typescript-eslint/no-explicit-any */
'use client'
import { KB_MINT, KB_PRIMARY, KB_PRIMARY_BG } from '@/lib/theme'

import Link from 'next/link'
import { useState, useEffect } from 'react'
import HeroWithQuickMenu from '@/components/home/HeroWithQuickMenu'
import ProductShowcase from '@/components/home/ProductShowcase'
import { formatNumber } from '@/lib/mock-data'
import { fetchDepositAccountViewModels, getCurrentDepositCustomerId, fetchTransactions, type DepositViewAccount, type DepositTransaction } from '@/lib/deposit-api'
import { NEWS_ITEMS } from '@/lib/news-data'

interface StoredUser { name: string; email: string }

type RecentTransfer = { datetime: string; name: string; amount: number; bank: string; account: string }



// ── 공통 섹션들 ──

function RateBanner() {
  return (
    <section className="py-3.5" style={{ backgroundColor: KB_PRIMARY }}>
      <div className="max-w-kb-container mx-auto px-8 flex items-center justify-center gap-10">
        <span className="text-[14px] font-bold tracking-widest flex-shrink-0" style={{ color: 'rgba(255,255,255,0.5)' }}>TODAY</span>
        <span className="w-px h-4 flex-shrink-0" style={{ backgroundColor: 'rgba(255,255,255,0.2)' }} />
        <div className="flex items-center gap-10">
          {[
            { label: 'AXful Star 정기예금', rate: '연 3.80%' },
            { label: '직장인든든 신용대출', rate: '연 4.20%~' },
            { label: 'AXful 외화예금 (USD)', rate: '연 2.10%' },
          ].map((item, i, arr) => (
            <div key={item.label} className="flex items-center gap-2.5">
              <span className="text-[15px]" style={{ color: 'rgba(255,255,255,0.7)' }}>{item.label}</span>
              <span className="text-[17px] font-bold" style={{ color: KB_MINT }}>{item.rate}</span>
              {i < arr.length - 1 && (
                <span className="ml-10 w-px h-4 flex-shrink-0" style={{ backgroundColor: 'rgba(255,255,255,0.2)' }} />
              )}
            </div>
          ))}
        </div>
      </div>
    </section>
  )
}




function NewsSection() {
  return (
    <section className="py-10 bg-white">
      <div className="max-w-kb-container mx-auto px-8">
        <div className="grid gap-10" style={{ gridTemplateColumns: '1.8fr 1fr' }}>

          {/* 새소식 / 이벤트 */}
          <div>
            <div className="flex items-center justify-between mb-5">
              <h2 className="text-[22px] font-bold text-kb-text">새소식 / 이벤트</h2>
              <Link href="/support/news" className="text-[14px] text-kb-text-muted hover:text-kb-text transition-colors">전체보기 ›</Link>
            </div>
            <ul className="space-y-0">
              {NEWS_ITEMS.map((item, idx) => (
                <li key={idx}>
                  <Link href={item.href} className="flex items-center gap-4 py-3.5 border-b border-gray-100 hover:bg-gray-50 -mx-2 px-2 rounded-lg transition-colors">
                    <span className="flex-shrink-0 text-[13px] font-bold px-2.5 py-1 rounded-full border"
                      style={{
                        borderColor: item.type === '새소식' ? KB_PRIMARY : KB_MINT,
                        color: item.type === '새소식' ? KB_PRIMARY : KB_MINT,
                      }}>
                      {item.type}
                    </span>
                    <span className="text-[15px] text-kb-text-body flex-1 truncate">{item.title}</span>
                    <span className="text-[13px] text-kb-text-muted flex-shrink-0">{item.date}</span>
                  </Link>
                </li>
              ))}
            </ul>
          </div>

          {/* 고객센터 */}
          <div className="rounded-2xl p-6" style={{ backgroundColor: KB_PRIMARY_BG, border: `1px solid ${KB_MINT}20` }}>
            <h2 className="text-[22px] font-bold text-kb-text mb-4">고객센터</h2>
            <p className="text-[30px] font-bold mb-1" style={{ color: KB_PRIMARY }}>1588-0000</p>
            <div className="space-y-1 text-[14px] text-kb-text-muted mb-4">
              <p>해외 +82-2-0000-0000</p>
              <p>외국인상담 1599-0044</p>
              <p className="text-[13px]">평일 09:00 ~ 18:00 (은행휴무일 제외)</p>
            </div>
            <div className="border-t pt-3 flex items-center gap-0 flex-wrap" style={{ borderColor: `${KB_MINT}30` }}>
              {[
                { label: '인터넷뱅킹 이용안내', href: '/support/internet-banking-guide' },
                { label: '보안프로그램 설치', href: '/security-install' },
                { label: '이용수수료 안내', href: '/support/fee-guide' },
              ].map((link, i) => (
                <span key={link.href} className="flex items-center">
                  {i > 0 && <span className="text-[11px] text-kb-text-muted mx-2">|</span>}
                  <Link href={link.href}
                    className="text-[12px] text-kb-text-muted hover:text-kb-primary transition-colors">
                    {link.label}
                  </Link>
                </span>
              ))}
            </div>
          </div>
        </div>
      </div>
    </section>
  )
}

// ── 로그인 후 대시보드 퀵액션 ──

export default function HomePage() {
  // 로그인 여부는 토큰으로 판단한다. user 프로필은 인사말 표시에만 쓰며,
  // me 조회가 일시적으로 실패해도 토큰만 있으면 로그인 홈을 보여준다.
  const [authed, setAuthed] = useState(false)
  const [user, setUser] = useState<StoredUser | null>(null)
  const [mainAccount, setMainAccount] = useState<DepositViewAccount | null>(null)
  const [recentTransfers, setRecentTransfers] = useState<RecentTransfer[]>([])

  useEffect(() => {
    setAuthed(!!localStorage.getItem('accessToken'))
    try {
      const raw = localStorage.getItem('user')
      if (raw) setUser(JSON.parse(raw))
    } catch {}
    const customerId = getCurrentDepositCustomerId()
    fetchDepositAccountViewModels(customerId)
      .then(accs => { if (accs.length > 0) setMainAccount(accs[0]) })
      .catch(() => {})
    fetchTransactions({ customerId })
      .then((txs: DepositTransaction[]) => {
        const seen = new Set<string>()
        const recent = txs
          .filter(t => t.directionType === 'OUT' && t.counterpartyAccountNo)
          .reduce<RecentTransfer[]>((acc, t) => {
            if (!seen.has(t.counterpartyAccountNo!)) {
              seen.add(t.counterpartyAccountNo!)
              acc.push({
                datetime: t.transactionAt?.slice(0, 16).replace('T', ' ') ?? '',
                name: t.counterpartyName ?? '수취인',
                amount: Number(t.amount),
                bank: t.counterpartyBankName ?? 'AXful',
                account: t.counterpartyAccountNo!,
              })
            }
            return acc
          }, [])
          .slice(0, 3)
        setRecentTransfers(recent)
      })
      .catch(() => {})
  }, [])

  /* ── 로그인 후 홈 ── */
  if (authed) {
    return (
      <main className="pb-0 bg-white">

        <section className="py-8 bg-white">
          <div className="max-w-kb-container mx-auto px-8">
            <div className="flex gap-6 items-stretch">

              {/* ===== 좌측 ===== */}
              <div className="flex-1 min-w-0">

                {/* 인사말 + 이미지 */}
                <div className="flex items-end mb-5">
                  <div className="flex-1 min-w-0">
                    <p className="text-[28px] font-bold text-kb-text mb-2">{user?.name ?? '고객'} 고객님,</p>
                    <p className="text-[14px] font-semibold" style={{ color: KB_PRIMARY }}>AXful은 항상 곁에 있습니다.</p>
                    <p className="text-[14px] font-semibold mb-4" style={{ color: KB_PRIMARY }}>24시간, 365일 늘 환영합니다.</p>
                    <div className="flex items-center gap-3">
                      <span className="border border-kb-border rounded-full px-3 py-1 text-[13px] text-kb-text-body inline-flex items-center gap-1 hover:bg-kb-beige-light cursor-pointer">
                        패밀리 <span className="text-[11px]">›</span>
                      </span>
                      <div className="flex items-center gap-2 bg-[#F5F5F5] rounded-full px-3 py-1.5">
                        <svg viewBox="0 0 20 20" fill="none" className="w-3.5 h-3.5 text-kb-text-muted" stroke="currentColor" strokeWidth="1.5">
                          <path d="M10 2a6 6 0 016 6c0 3 1 4 2 5H2c1-1 2-2 2-5a6 6 0 016-6z"/>
                          <path d="M8 17a2 2 0 004 0"/>
                        </svg>
                        <span className="text-[13px] text-kb-text-muted">알람이 없습니다.</span>
                      </div>
                    </div>
                  </div>
                  <div className="flex-shrink-0 pointer-events-none">
                    <img src="/images/personal-login-hero1.png" alt="" className="w-[420px] h-auto" />
                  </div>
                </div>

                {/* 계좌 카드 */}
                <div className="rounded-2xl p-5 relative z-10 shadow-sm" style={{ backgroundColor: KB_PRIMARY_BG, border: `1px solid ${KB_MINT}30` }}>
                  <div className="flex items-center justify-between mb-3">
                    <Link href="/inquiry/accounts" className="flex items-center gap-1 text-[13px] text-kb-text-body hover:underline">
                      <svg viewBox="0 0 16 16" fill="none" className="w-3.5 h-3.5" stroke="currentColor" strokeWidth="1.5">
                        <circle cx="8" cy="8" r="6"/><line x1="8" y1="5" x2="8" y2="11"/><line x1="5" y1="8" x2="11" y2="8"/>
                      </svg>
                      계좌바꾸기
                    </Link>
                    <Link href="/inquiry/transactions" className="flex items-center gap-1 text-[13px] text-kb-text-body hover:underline">
                      <svg viewBox="0 0 16 16" fill="none" className="w-3.5 h-3.5" stroke="currentColor" strokeWidth="1.5">
                        <line x1="2" y1="4" x2="14" y2="4"/><line x1="2" y1="8" x2="14" y2="8"/><line x1="2" y1="12" x2="10" y2="12"/>
                      </svg>
                      거래내역보기
                    </Link>
                  </div>
                  <div className="flex items-center justify-between mb-3">
                    <div>
                      <p className="text-[16px] font-bold text-kb-text">{mainAccount?.name ?? '-'}</p>
                      <p className="text-[13px] text-kb-text-muted">{mainAccount?.number ?? '-'}</p>
                    </div>
                    <p className="text-[24px] font-bold text-kb-text">
                      {formatNumber(Number(mainAccount?.balance ?? 0))}원
                    </p>
                  </div>
                  <div className="flex gap-2 pt-3 border-t border-dashed" style={{ borderColor: `${KB_MINT}40` }}>
                    <Link href="/inquiry/accounts"
                      className="flex-1 py-2.5 text-[15px] font-bold text-center rounded-lg border-2 transition-colors hover:bg-white"
                      style={{ borderColor: KB_PRIMARY, color: KB_PRIMARY }}>
                      조회
                    </Link>
                    <Link href="/transfer/account"
                      className="flex-1 py-2.5 text-[15px] font-bold text-white text-center rounded-lg transition-opacity hover:opacity-85"
                      style={{ backgroundColor: KB_PRIMARY }}>
                      이체
                    </Link>
                  </div>
                </div>

              </div>

              {/* ===== 우측: 최근이체 내역 ===== */}
              <div className="w-[420px] flex-shrink-0 rounded-2xl p-6 shadow-sm" style={{ backgroundColor: KB_PRIMARY_BG, border: `1px solid ${KB_MINT}30` }}>
                <h2 className="text-[18px] font-bold text-kb-text mb-4">최근이체 내역</h2>
                <div className="space-y-0">
                  {recentTransfers.length === 0 ? (
                    <div className="flex flex-col items-center justify-center py-10 gap-2">
                      <svg viewBox="0 0 24 24" fill="none" className="w-10 h-10 text-kb-border" stroke="currentColor" strokeWidth="1.5">
                        <path d="M3 10h18M7 15h1m4 0h1M3 6h18v12a2 2 0 01-2 2H5a2 2 0 01-2-2V6z"/>
                      </svg>
                      <p className="text-[13px] text-kb-text-muted">최근 이체 내역이 없습니다.</p>
                    </div>
                  ) : recentTransfers.map((t, i) => (
                    <div key={i} className="flex items-center justify-between py-3.5 border-b last:border-b-0" style={{ borderColor: '#F0F0F0' }}>
                      <div>
                        <p className="text-[15px] font-bold text-kb-text">{t.name}</p>
                        <p className="text-[12px] text-kb-text-muted mt-0.5">{t.bank} · {t.datetime}</p>
                        <p className="text-[12px] text-kb-text-muted">{t.account}</p>
                      </div>
                      <div className="text-right flex flex-col items-end gap-1.5">
                        <p className="text-[15px] font-bold text-kb-text">{formatNumber(t.amount)}원</p>
                        <Link
                          href={`/transfer/account?to=${encodeURIComponent(t.account)}&bank=${encodeURIComponent(t.bank)}&name=${encodeURIComponent(t.name)}`}
                          className="text-[12px] font-semibold px-2.5 py-1 rounded-full border transition-colors hover:bg-kb-primary-bg"
                          style={{ borderColor: KB_MINT, color: KB_PRIMARY }}>
                          바로이체
                        </Link>
                      </div>
                    </div>
                  ))}
                </div>
              </div>

            </div>
          </div>
        </section>

        <RateBanner />
        <ProductShowcase />

        <NewsSection />
      </main>
    )
  }

  /* ── 비로그인 홈 ── */
  return (
    <main className="pb-0 bg-white">
      <HeroWithQuickMenu />
      <RateBanner />
      <ProductShowcase />
      <NewsSection />
    </main>
  )
}
