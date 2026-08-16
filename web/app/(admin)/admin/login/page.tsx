'use client'

import { useEffect, useState } from 'react'
import { useRouter } from 'next/navigation'
import { AdminUser } from '@/lib/admin-mock-data'
import { branchLabel } from '@/lib/admin-auth'
import type { DemoAccount } from '@/lib/admin-demo-accounts'
import { agentLogin, type AgentLoginResponse } from '@/lib/consultation-api'

// 데모 모드: 로컬/개발 빌드에선 기본 노출, 운영 빌드에선 NEXT_PUBLIC_DEMO_MODE=true 일 때만.
// (운영에 직원 명단을 인증 전 화면에 깔지 않기 위함)
const DEMO_MODE =
  process.env.NEXT_PUBLIC_DEMO_MODE === 'true' || process.env.NODE_ENV !== 'production'
const DEMO_PASSWORD = 'Employee1234!'

/**
 * 로그인 후 화면용 AdminUser 구성. 역할은 admin_roles(BankRole[])로 따로 저장하므로
 * 여기선 표시용 신원(이름·지점)만 담는다. 이름은 데모 계정이면 큐레이션, 아니면 loginId.
 */
function buildAdminUser(loginId: string, branch?: string, name?: string): AdminUser {
  return { loginId, name: name ?? loginId, branchCode: branch ?? '-', branchName: branchLabel(branch) }
}

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
      // 게이트웨이를 거친다. 예전에는 /api/consultation/... 프록시를 직접 불렀는데,
      // 그 프록시가 Content-Type 외의 헤더를 버려서 신원이 서비스에 닿지 않았다.
      let agent: AgentLoginResponse
      try {
        agent = await agentLogin(id, password)
      } catch {
        setError('아이디 또는 비밀번호가 올바르지 않습니다.')
        setLoading(false)
        return
      }

      // 관리자/슈퍼바이저만 이 페이지로 접근 허용
      if (agent.role !== 'ADMIN' && agent.role !== 'SUPERVISOR') {
        setError('관리자 또는 슈퍼바이저 계정만 로그인할 수 있습니다.')
        setLoading(false)
        return
      }

      const roleMap: Record<string, string[]> = {
        ADMIN: ['ROLE_ADMIN'],
        SUPERVISOR: ['ROLE_ADMIN'],
      }
      const roles = roleMap[agent.role] ?? []
      const token = 'mock.' + btoa(unescape(encodeURIComponent(JSON.stringify({ name: agent.name, roles })))) + '.' + Date.now()
      localStorage.setItem('accessToken', token)
      localStorage.setItem('access_token', token)
      localStorage.setItem('admin_roles', JSON.stringify(roles))
      localStorage.setItem('admin_user', JSON.stringify(buildAdminUser(id, undefined, agent.name)))
      router.push('/admin/consultation/history')
    } catch {
      setError('로그인에 실패했습니다. 아이디·비밀번호를 확인하세요.')
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
