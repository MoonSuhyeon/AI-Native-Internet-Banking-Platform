'use client'

import { useEffect, useState } from 'react'
import { usePathname, useRouter } from 'next/navigation'
import { readAccessToken } from '@/lib/token'
import { loginUrlFor } from '@/lib/return-url'

/**
 * 로그인 없이 열어 두는 경로 prefix.
 *
 * <p>{@code /products} 와 {@code /support} 는 상품 안내·약관·FAQ·가입 전 절차라
 * 로그인 전에 보여야 한다. 다만 <b>그 아래에 본인 데이터를 다루는 화면이 섞여
 * 있다</b> — 예금해지, 가입·해지 내역, 회원탈퇴, 인터넷뱅킹 해지가 그렇다.
 * 접두사 하나로 열면 그것까지 함께 열린다.
 */
const PUBLIC_PREFIXES = [
  '/login',
  '/logout',
  '/cert',
  '/cert-cps',
  '/cert-biz',
  '/products',
  '/support',
  '/security-install',
]

/**
 * 공개 접두사 <b>안에</b> 있지만 로그인이 필요한 경로. 위 목록보다 우선한다.
 *
 * <p><b>왜 이 목록이 필요한가.</b> 예전에는 {@code /products} · {@code /support} 를
 * 통째로 열어 두어, 로그인하지 않아도 예금해지·회원탈퇴·인터넷뱅킹 해지 화면과
 * 가입·해지 내역 조회가 그대로 열렸다. 조회 API 는 게이트웨이가 막으니 값은 비어
 * 있었지만, 화면이 열린다는 것 자체가 은행에서는 문제다 — 무엇을 할 수 있는지,
 * 어떤 항목을 다루는지가 그대로 노출된다.
 *
 * <p><b>기준.</b> 본인의 계좌·계약·계정 상태를 보여주거나 바꾸면 여기 넣는다.
 * 상품 설명·약관·FAQ·가입 전 절차는 공개로 둔다.
 *
 * <p>계정 찾기({@code /support/customer-info/id-password})는 <b>일부러 공개</b>다.
 * 로그인하지 못하는 사람이 쓰는 화면이라 로그인을 요구하면 쓸 수 없다. 본인확인을
 * 통과해야 다음 단계로 넘어간다.
 */
const PRIVATE_PREFIXES = [
  // 본인 예금 계약 — 해지 실행과 가입·해지 내역
  '/products/deposit/inquiry',
  '/products/deposit/join',
  // 본인 대출 — 현황·관리·사전심사·심사자료 제출
  '/products/loan/my',
  '/products/loan/status',
  '/products/loan/manage',
  '/products/loan/prescreening',
  '/products/loan/credit-eval',
  // 본인 계정 상태를 바꾸는 것
  '/support/customer-info/withdraw',
  '/support/customer-info/internet-banking-cancel',
  // 본인 이름으로 남는 상담·예약
  '/support/consultation',
]

export default function AuthGuard({ children }: { children: React.ReactNode }) {
  const pathname = usePathname()
  const router = useRouter()

  // 더 좁은 쪽이 이긴다. 공개 접두사 안에 있어도 비공개 목록에 걸리면 보호한다.
  const isPrivate = PRIVATE_PREFIXES.some((p) => pathname.startsWith(p))
  const isPublic = !isPrivate
    && (pathname === '/' || PUBLIC_PREFIXES.some((p) => pathname.startsWith(p)))
  const [authorized, setAuthorized] = useState(isPublic)

  useEffect(() => {
    if (isPublic) {
      setAuthorized(true)
      return
    }
    // accessToken(ID 로그인) 또는 access_token(인증서 로그인) 둘 다 허용
    const token = readAccessToken()
    if (!token) {
      // 어디로 가려던 참이었는지 함께 보낸다. 예전에는 그냥 '/login' 이라
      // 로그인에 성공해도 누른 적 없는 홈이 떴다 — 사용자에게는 버튼이
      // 안 먹는 것으로 보인다. lib/return-url.ts 주석 참고.
      router.replace(loginUrlFor(pathname, window.location.search))
    } else {
      // 두 키를 통일한다. readAccessToken 이 이미 검증한 값이라 그대로 쓴다 —
      // 여기서 localStorage 를 다시 읽으면 검증을 건너뛴 값이 들어올 수 있다.
      localStorage.setItem('accessToken', token)
      setAuthorized(true)
    }
  }, [pathname, isPublic, router])

  if (!authorized) return null
  return <>{children}</>
}
