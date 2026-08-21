'use client'

import { useEffect, useState } from 'react'
import { usePathname, useRouter } from 'next/navigation'
import { getAdminRoles, isEmployee } from '@/lib/admin-auth'
import { readAccessToken } from '@/lib/token'

const PUBLIC_ADMIN_PATHS = ['/admin/login', '/admin/consultation']

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
      router.replace('/admin/login')
      return
    }

    // 2차: 서버사이드 토큰 유효성 검증
    const token = readAccessToken()
    if (!token) {
      router.replace('/admin/login')
      return
    }
    fetch('/api/v1/auth/verify', {
      method: 'GET',
      headers: { Authorization: `Bearer ${token}` },
    })
      .then((res) => {
        if (!res.ok) {
          localStorage.removeItem('accessToken')
          localStorage.removeItem('access_token')
          localStorage.removeItem('admin_roles')
          localStorage.removeItem('admin_user')
          router.replace('/admin/login')
        } else {
          setAuthorized(true)
        }
      })
      .catch(() => setAuthorized(true)) // 검증 서버 미응답 시 UX 유지 (클라이언트 검증으로 fallback)
  }, [pathname, isPublic, router])

  if (!authorized) return null
  return <>{children}</>
}
