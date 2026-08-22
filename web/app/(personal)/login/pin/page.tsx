'use client'
import { KB_PRIMARY } from '@/lib/theme'
import { postLoginTarget } from '@/lib/return-url'

import { useEffect, useState } from 'react'
import Link from 'next/link'
import { pinLogin, persistLogin, loadPinDeviceId, authErrorMessage } from '@/lib/customer-auth-api'

const GREEN = KB_PRIMARY

export default function PinLoginPage() {
  const [deviceId, setDeviceId] = useState<number | null>(null)
  const [loginId, setLoginId] = useState('')
  const [pin, setPin] = useState('')
  const [error, setError] = useState('')
  const [loading, setLoading] = useState(false)

  useEffect(() => {
    setDeviceId(loadPinDeviceId())
  }, [])

  async function handleLogin() {
    if (deviceId == null) return
    if (!loginId) return setError('아이디를 입력해주세요.')
    if (!/^\d{6}$/.test(pin)) return setError('PIN 6자리를 입력해주세요.')

    setError('')
    setLoading(true)
    localStorage.removeItem('accessToken')
    localStorage.removeItem('access_token')
    try {
      const res = await pinLogin({ loginId, deviceId, pin })
      await persistLogin(res)
      window.location.href = postLoginTarget()
    } catch (err) {
      setError(authErrorMessage(err, 'PIN 로그인에 실패했습니다.'))
    } finally {
      setLoading(false)
    }
  }

  return (
    <div className="min-h-screen bg-white">
      <div className="max-w-kb-container mx-auto px-6 py-12">
        <main className="max-w-md mx-auto">
          <h1 className="text-[22px] font-bold text-kb-text mb-2 text-center">간편비밀번호 로그인</h1>
          <p className="text-[13px] text-kb-text-muted mb-8 text-center">이 기기에 등록한 6자리 PIN으로 로그인합니다.</p>

          {deviceId == null ? (
            <div className="border border-kb-border bg-kb-primary-bg px-5 py-6 text-center">
              <p className="text-[14px] text-kb-text-body mb-4">이 기기에 등록된 간편비밀번호가 없습니다.</p>
              <p className="text-[13px] text-kb-text-muted mb-5">일반 로그인 후 설정 › 간편비밀번호(PIN)에서 등록할 수 있습니다.</p>
              <Link href="/login" className="inline-block px-8 py-3 text-[14px] font-bold text-white rounded-lg hover:opacity-85" style={{ backgroundColor: GREEN }}>
                일반 로그인하기
              </Link>
            </div>
          ) : (
            <div className="border border-kb-border px-6 py-7 space-y-3">
              <div className="flex items-center gap-3">
                <label className="w-20 text-[13px] text-kb-text-body text-right flex-shrink-0">아이디</label>
                <input
                  value={loginId}
                  onChange={(e) => setLoginId(e.target.value)}
                  placeholder="아이디"
                  className="input flex-1"
                  autoComplete="username"
                  onKeyDown={(e) => e.key === 'Enter' && handleLogin()}
                />
              </div>
              <div className="flex items-center gap-3">
                <label className="w-20 text-[13px] text-kb-text-body text-right flex-shrink-0">PIN</label>
                <input
                  type="password"
                  inputMode="numeric"
                  value={pin}
                  onChange={(e) => setPin(e.target.value.replace(/\D/g, '').slice(0, 6))}
                  placeholder="숫자 6자리"
                  className="input flex-1"
                  maxLength={6}
                  onKeyDown={(e) => e.key === 'Enter' && handleLogin()}
                />
              </div>

              {error && <p className="text-[13px] text-red-500">{error}</p>}

              <button onClick={handleLogin} disabled={loading} className="w-full py-3 text-[14px] font-bold text-white rounded-lg hover:opacity-85 disabled:opacity-50 mt-1" style={{ backgroundColor: GREEN }}>
                {loading ? '로그인 중...' : 'PIN 로그인'}
              </button>

              <div className="text-center pt-2">
                <Link href="/login" className="text-[13px] text-kb-text-muted hover:underline">일반 로그인으로 돌아가기</Link>
              </div>
            </div>
          )}
        </main>
      </div>
    </div>
  )
}
