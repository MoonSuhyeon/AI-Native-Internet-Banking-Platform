'use client'

import { useEffect, useState } from 'react'
import { usePathname, useRouter } from 'next/navigation'
import { readAccessToken } from '@/lib/token'
import { loginUrlFor } from '@/lib/return-url'

// 로그인 없이 접근 가능한 경로 prefix
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

export default function AuthGuard({ children }: { children: React.ReactNode }) {
  const pathname = usePathname()
  const router = useRouter()

  const isPublic = pathname === '/' || PUBLIC_PREFIXES.some((p) => pathname.startsWith(p))
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
