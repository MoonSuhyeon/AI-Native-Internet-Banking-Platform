'use client'

import { useEffect, useState } from 'react'
import { usePathname, useRouter } from 'next/navigation'
import { getAdminRoles, isEmployee } from '@/lib/admin-auth'
import { readAccessToken } from '@/lib/token'

const PUBLIC_ADMIN_PATHS = ['/admin/login', '/admin/consultation']

/**
 * 로그인 안 된 직원을 어디로 보낼 것인가.
 *
 * <p>예전에는 {@code /admin/login} 이었다. 그 화면은 상담 서비스의 상담원 로그인을
 * 부르고, 성공하면 백엔드 JWT 가 아니라 {@code mock.<base64>} 토큰을 스스로 만든다 —
 * {@code readAccessToken} 이 지우는 그 형태다. 상담 서비스를 올리든 안 올리든
 * <b>은행 직원은 그 문으로 들어올 수 없다.</b>
 *
 * <p>은행 직원의 문은 {@code /login} 하나다. 거기서 받은 진짜 JWT 의 roles 클레임이
 * 이 가드가 보는 값의 출처가 된다. returnUrl 을 달아 원래 가려던 화면으로 잇는다.
 */
function loginPathFor(pathname: string): string {
  return '/login?returnUrl=' + encodeURIComponent(pathname)
}

export default function AdminGuard({ children }: { children: React.ReactNode }) {
  const pathname = usePathname()
  const router = useRouter()
  const isPublic = PUBLIC_ADMIN_PATHS.some((p) => pathname.startsWith(p))
  const [authorized, setAuthorized] = useState(isPublic)

  useEffect(() => {
    if (isPublic) {
      setAuthorized(true)
      return
    }

    // 1차: 클라이언트 localStorage 역할 확인 (빠른 UX 리다이렉트용)
    if (!isEmployee(getAdminRoles())) {
      router.replace(loginPathFor(pathname))
      return
    }

    // 2차: 서버사이드 토큰 유효성 검증
    const token = readAccessToken()
    if (!token) {
      router.replace(loginPathFor(pathname))
      return
    }
    fetch('/api/v1/auth/verify', {
      method: 'GET',
      headers: { Authorization: `Bearer ${token}` },
    })
      .then((res) => {
        // **401·403 만 "이 토큰은 아니다" 라는 답이다.** 그 밖의 실패(404·5xx)는
        // 검증을 못 했다는 뜻이지 토큰이 틀렸다는 뜻이 아니다.
        //
        // 실제로 그 구분이 없어서 직원이 로그인 직후 튕겨 나갔다. 이 경로는 Next 의
        // 라우트 핸들러로만 있는데, 운영에서는 Caddy 가 /api/* 를 전부 게이트웨이로
        // 보내 백엔드에 없는 경로가 되어 404 가 난다. 가드는 그것을 무효 토큰으로
        // 보고 저장소를 지운 뒤 로그인으로 되돌렸다 — 화면에서는 로그인이 안 되는
        // 것과 구분되지 않는다.
        //
        // 검증이 불가능할 때 통과시켜도 뚫리지 않는다. 화면이 부르는 API 는 매번
        // 게이트웨이와 서비스가 진짜 토큰으로 다시 판정한다.
        if (res.status === 401 || res.status === 403) {
          localStorage.removeItem('accessToken')
          localStorage.removeItem('access_token')
          localStorage.removeItem('admin_roles')
          localStorage.removeItem('admin_user')
          router.replace(loginPathFor(pathname))
        } else {
          setAuthorized(true)
        }
      })
      .catch(() => setAuthorized(true)) // 검증 서버 미응답 시 UX 유지 (클라이언트 검증으로 fallback)
  }, [pathname, isPublic, router])

  if (!authorized) return null
  return <>{children}</>
}
