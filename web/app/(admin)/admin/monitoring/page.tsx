'use client'

import { useCallback, useEffect, useMemo, useState } from 'react'
import AdminSidebar from '@/components/admin/AdminSidebar'
import { useAdminRoles } from '@/components/admin/RoleGate'
import { hasAnyRole, BankRole } from '@/lib/admin-auth'

// Grafana 주소. iframe 은 서버가 아니라 브라우저가 직접 가져오므로
// 컨테이너 이름(grafana:3000)이 아니라 브라우저가 닿는 주소여야 한다.
const GRAFANA_URL = process.env.NEXT_PUBLIC_GRAFANA_URL || 'http://localhost:3000'

type Dashboard = {
  uid: string
  title: string
  tags: string[]
}

// 시간 범위. Grafana URL 파라미터로 그대로 넘긴다.
const RANGES = [
  { label: '1시간', from: 'now-1h' },
  { label: '6시간', from: 'now-6h' },
  { label: '24시간', from: 'now-24h' },
  { label: '7일', from: 'now-7d' },
]

// AI 에이전트 대시보드를 앞에 모은다. 태그는 대시보드 JSON 이 들고 있다.
function isAgentDashboard(d: Dashboard) {
  return d.tags?.some((t) => t === 'ai' || t === 'agent')
}

export default function MonitoringPage() {
  // 사이드바에서 메뉴를 감추는 것만으로는 주소를 직접 치고 들어오는 것을 막지 못한다.
  // 전사 지표라 운영(OPS)·리스크(HQ_RISK)로 한정한다. loan/eod 와 같은 방식이다.
  const roles = useAdminRoles()
  const allowed = hasAnyRole(roles, BankRole.OPS, BankRole.HQ_RISK)

  const [dashboards, setDashboards] = useState<Dashboard[]>([])
  const [selected, setSelected] = useState<string | null>(null)
  const [range, setRange] = useState(RANGES[1].from)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)

  // 목록을 코드에 박지 않고 Grafana 에서 읽는다.
  // 박아두면 대시보드를 추가·삭제할 때마다 화면이 조용히 어긋난다.
  //
  // Grafana 를 브라우저에서 직접 부르면 CORS 로 막히므로 서버 라우트를 거친다.
  // 대시보드 본체(iframe)는 CORS 대상이 아니라 브라우저가 직접 불러도 된다.
  const load = useCallback(async () => {
    setLoading(true)
    setError(null)
    try {
      const res = await fetch('/api/monitoring/dashboards')
      if (!res.ok) {
        const body = await res.json().catch(() => null)
        throw new Error(body?.error ?? `목록 조회 실패 (${res.status})`)
      }
      const list: Dashboard[] = await res.json()
      setDashboards(list)
      setSelected((prev) => prev ?? list[0]?.uid ?? null)
    } catch (e) {
      // 대부분 Grafana 가 안 떠 있는 경우다. 빈 화면 대신 이유와 조치를 보여준다.
      setError(e instanceof Error ? e.message : '알 수 없는 오류')
    } finally {
      setLoading(false)
    }
  }, [])

  // 권한이 확인된 뒤에만 부른다. useAdminRoles 는 마운트 후에 값이 채워지므로
  // allowed 를 의존성에 둬야 판정이 끝난 시점에 한 번 더 돈다.
  useEffect(() => {
    if (allowed) load()
  }, [allowed, load])

  const groups = useMemo(() => {
    const agents = dashboards.filter(isAgentDashboard)
    const rest = dashboards.filter((d) => !isAgentDashboard(d))
    return [
      { title: 'AI 에이전트', items: agents },
      { title: '서비스 · 인프라', items: rest },
    ].filter((g) => g.items.length > 0)
  }, [dashboards])

  const current = dashboards.find((d) => d.uid === selected) ?? null

  // kiosk: Grafana 자체 메뉴·헤더를 숨겨 어드민 안에 자연스럽게 들어가게 한다.
  const embedUrl = current
    ? `${GRAFANA_URL}/d/${current.uid}?kiosk&theme=light&from=${range}&to=now&refresh=30s`
    : null

  // 역할은 마운트 후에 채워진다. 판정 전에 '권한 없음'을 그리면 정상 사용자에게도
  // 한 번 스친다. 역할이 정말 없는 경우는 AdminGuard 가 로그인으로 보낸다.
  if (roles.length === 0) return null

  if (!allowed) {
    return (
      <div className="flex min-h-screen bg-gray-50">
        <AdminSidebar />
        <main className="flex-1 p-6">
          <div className="max-w-md p-4 rounded border border-gray-300 bg-white">
            <div className="text-sm font-semibold text-gray-800 mb-1">열람 권한이 없습니다</div>
            <p className="text-xs text-gray-600">
              모니터링 대시보드는 운영·리스크 담당자만 볼 수 있습니다.
            </p>
          </div>
        </main>
      </div>
    )
  }

  return (
    <div className="flex min-h-screen bg-gray-50">
      <AdminSidebar />
      <main className="flex-1 overflow-auto">
        <div className="px-6 py-4 border-b bg-white">
          <div className="flex items-center justify-between mb-1">
            <h1 className="text-lg font-bold text-gray-800">모니터링 대시보드</h1>
            <div className="flex items-center gap-2">
              {RANGES.map((r) => (
                <button
                  key={r.from}
                  onClick={() => setRange(r.from)}
                  className={`px-2.5 py-1 text-xs rounded border transition-colors ${
                    range === r.from
                      ? 'bg-gray-800 text-white border-gray-800'
                      : 'bg-white text-gray-600 border-gray-300 hover:bg-gray-50'
                  }`}
                >
                  {r.label}
                </button>
              ))}
              <a
                href={current ? `${GRAFANA_URL}/d/${current.uid}` : GRAFANA_URL}
                target="_blank"
                rel="noreferrer"
                className="ml-1 px-2.5 py-1 text-xs rounded border border-gray-300 text-gray-600 bg-white hover:bg-gray-50"
              >
                Grafana 에서 열기 ↗
              </a>
            </div>
          </div>
          <p className="text-xs text-gray-500">
            Prometheus 가 수집한 지표를 Grafana 대시보드로 본다. 목록은 Grafana 에서 직접 읽어온다.
          </p>
        </div>

        {loading && (
          <div className="p-6 text-sm text-gray-500">대시보드 목록을 불러오는 중…</div>
        )}

        {error && (
          <div className="m-6 p-4 rounded border border-amber-300 bg-amber-50">
            <div className="text-sm font-semibold text-amber-900 mb-1">
              Grafana 에 연결하지 못했습니다
            </div>
            <p className="text-xs text-amber-800 mb-2">
              {error} — 모니터링 스택이 떠 있지 않을 수 있습니다.
            </p>
            <pre className="text-xs bg-white border border-amber-200 rounded p-2 text-gray-700 overflow-x-auto">
              docker compose up -d prometheus grafana
            </pre>
            <button
              onClick={load}
              className="mt-2 px-3 py-1 text-xs rounded bg-amber-600 text-white hover:bg-amber-700"
            >
              다시 시도
            </button>
          </div>
        )}

        {!loading && !error && (
          <div className="flex">
            <nav className="w-56 shrink-0 border-r bg-white min-h-[calc(100vh-73px)] py-3">
              {groups.map((g) => (
                <div key={g.title} className="mb-3">
                  <div className="px-4 py-1 text-[11px] font-semibold text-gray-400 uppercase tracking-wide">
                    {g.title}
                  </div>
                  {g.items.map((d) => (
                    <button
                      key={d.uid}
                      onClick={() => setSelected(d.uid)}
                      className={`w-full text-left px-4 py-2 text-xs transition-colors ${
                        selected === d.uid
                          ? 'bg-blue-50 text-blue-700 font-semibold border-r-2 border-blue-600'
                          : 'text-gray-600 hover:bg-gray-50'
                      }`}
                    >
                      {d.title}
                    </button>
                  ))}
                </div>
              ))}
            </nav>

            <div className="flex-1 p-3">
              {embedUrl ? (
                <iframe
                  key={embedUrl}
                  src={embedUrl}
                  title={current?.title ?? '대시보드'}
                  className="w-full rounded border bg-white"
                  style={{ height: 'calc(100vh - 100px)' }}
                />
              ) : (
                <div className="p-6 text-sm text-gray-500">표시할 대시보드가 없습니다.</div>
              )}
            </div>
          </div>
        )}
      </main>
    </div>
  )
}
