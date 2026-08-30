'use client'

import { useEffect, useState } from 'react'
import { useRouter } from 'next/navigation'
import type { DemoAccount } from '@/lib/admin-demo-accounts'
import { api } from '@/lib/api'
import { persistEmployeeSession } from '@/lib/employee-session'
import { postLoginTarget } from '@/lib/return-url'

// 데모 모드: 로컬/개발 빌드에선 기본 노출, 운영 빌드에선 NEXT_PUBLIC_DEMO_MODE=true 일 때만.
// (운영에 직원 명단을 인증 전 화면에 깔지 않기 위함)
const DEMO_MODE =
  process.env.NEXT_PUBLIC_DEMO_MODE === 'true' || process.env.NODE_ENV !== 'production'
const DEMO_PASSWORD = 'Employee1234!'

export default function AdminLoginPage() {
  const router = useRouter()
  const [loginId, setLoginId] = useState('')
  const [password, setPassword] = useState('')
  const [error, setError] = useState('')
  const [loading, setLoading] = useState(false)
  const [demoAccounts, setDemoAccounts] = useState<DemoAccount[]>([])

  // 데모 계정 목록은 DEMO_MODE 일 때만 별도 청크로 동적 로드한다(운영 번들 제외).
  useEffect(() => {
    if (!DEMO_MODE) return
    import('@/lib/admin-demo-accounts').then((m) => setDemoAccounts(m.DEMO_ACCOUNTS)).catch(() => { /* noop */ })
  }, [])

  async function handleLogin() {
    const id = loginId.trim()
    if (!id)       { setError('아이디를 입력해주세요.'); return }
    if (!password) { setError('비밀번호를 입력해주세요.'); return }

    setLoading(true)
    setError('')
    try {
      // **직원 전용 경로다.** 고객 계정으로 여기 들어오면 백엔드가 403 을 내고
      // 토큰을 아예 발급하지 않는다 — 화면에서 걸러내면 고객도 일단 토큰을 받는다.
      //
      // 예전에는 상담 서비스의 상담원 로그인을 부르고 mock.<base64> 토큰을 스스로
      // 만들었다. 게이트웨이가 그 토큰을 거절해 화면만 열리고 모든 API 가 401 이었고,
      // 은행 직원 계정으로는 아예 들어올 수도 없었다.
      const { data } = await api.post('/api/v1/auth/employee/login', { loginId: id, password })

      // 갈 곳: returnUrl 이 있으면 그쪽, 없으면 조사 화면.
      const target = postLoginTarget()
      const next = persistEmployeeSession(
        data.data.accessToken, id, target === '/' ? '/admin/fraud' : target)
      router.push(next)
    } catch (err: unknown) {
      const e = err as { response?: { data?: { message?: string } } }
      // 자격이 틀린 것과 '고객 계정이라 여기로 못 들어온다' 는 다른 일이다.
      // 뭉뚱그리면 맞는 비밀번호를 몇 번이고 다시 넣게 된다.
      setError(
        e.response?.data?.message
        ?? (err instanceof Error ? err.message : '로그인에 실패했습니다.'))
      setLoading(false)
    }
  }

  function fillDemo(account: DemoAccount) {
    setLoginId(account.loginId)
    setPassword(DEMO_PASSWORD)
    setError('')
  }

  return (
    <div className="min-h-screen flex items-center justify-center bg-gray-100">
      <div className="w-full max-w-md bg-white shadow-lg">
        {/* 헤더 */}
        <div className="px-8 py-6 border-b border-gray-200" style={{ backgroundColor: '#1a3a5c' }}>
          <p className="text-xs text-blue-300 mb-1">AXful Bank</p>
          <h1 className="text-xl font-bold text-white">관리자 시스템 로그인</h1>
          <p className="text-sm text-blue-200 mt-1">접근 권한에 따라 열람 가능한 데이터가 제한됩니다</p>
        </div>

        <div className="px-8 py-6">
          {/* 아이디 */}
          <div className="mb-4">
            <label className="block text-sm font-semibold text-gray-700 mb-1.5">아이디</label>
            <input
              type="text"
              value={loginId}
              onChange={(e) => { setLoginId(e.target.value); setError('') }}
              onKeyDown={(e) => e.key === 'Enter' && handleLogin()}
              placeholder="직원 아이디"
              autoComplete="username"
              className="w-full border border-gray-300 px-3 py-2.5 text-sm outline-none focus:border-blue-400 rounded"
            />
          </div>

          {/* 비밀번호 */}
          <div className="mb-4">
            <label className="block text-sm font-semibold text-gray-700 mb-1.5">비밀번호</label>
            <input
              type="password"
              value={password}
              onChange={(e) => { setPassword(e.target.value); setError('') }}
              onKeyDown={(e) => e.key === 'Enter' && handleLogin()}
              placeholder="비밀번호를 입력하세요"
              autoComplete="current-password"
              className="w-full border border-gray-300 px-3 py-2.5 text-sm outline-none focus:border-blue-400 rounded"
            />
          </div>

          {error && <p className="text-sm text-red-500 mb-3">{error}</p>}

          <button
            onClick={handleLogin}
            disabled={loading}
            className="w-full py-3 text-sm font-bold text-white rounded transition-colors disabled:opacity-50"
            style={{ backgroundColor: '#1a3a5c' }}
            onMouseEnter={(e) => { if (!loading) e.currentTarget.style.backgroundColor = '#122a44' }}
            onMouseLeave={(e) => { if (!loading) e.currentTarget.style.backgroundColor = '#1a3a5c' }}
          >
            {loading ? '로그인 중…' : '로그인'}
          </button>

          {/* 데모 계정 빠른 입력 — 운영 빌드에선 숨김 */}
          {DEMO_MODE && demoAccounts.length > 0 && (
            <div className="mt-6 border-t border-gray-100 pt-4">
              <p className="text-xs font-semibold text-gray-500 mb-2">데모 계정 빠른 입력</p>
              <div className="flex flex-wrap gap-1.5">
                {demoAccounts.map((account) => (
                  <button
                    key={account.loginId}
                    type="button"
                    onClick={() => fillDemo(account)}
                    title={`${account.desc} · ${account.loginId}`}
                    className="text-xs px-2 py-1 border border-gray-200 rounded text-gray-600 hover:border-blue-400 hover:bg-blue-50 transition-colors"
                  >
                    {account.name}
                  </button>
                ))}
              </div>
              <p className="text-xs text-gray-400 mt-2">클릭하면 아이디·비밀번호가 자동 입력됩니다 (데모 비밀번호: {DEMO_PASSWORD})</p>
            </div>
          )}

          <p className="text-center text-xs text-gray-400 mt-4">
            본 시스템의 모든 접근 이력은 감사 로그에 기록됩니다
          </p>
        </div>
      </div>
    </div>
  )
}
