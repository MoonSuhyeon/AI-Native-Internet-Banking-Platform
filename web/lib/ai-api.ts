import axios from 'axios'
import { getAdminGatewayHeaders } from '@/lib/admin-loan-auth'

// advisory RAG 관리 API — loan-service(8083)에 advisory-service 소스셋이 흡수되어 동작.
// 폐기된 ai-service(8086) 경로를 advisory RAG 경로(/api/internal/advisory/*)로 교체.
const aiApi = axios.create({
  baseURL: process.env.NEXT_PUBLIC_AI_API_URL || 'http://localhost:8083',
  headers: { 'Content-Type': 'application/json' },
})

aiApi.interceptors.request.use((config) => {
  if (typeof window === 'undefined') return config
  const token = localStorage.getItem('accessToken')
  if (token) {
    config.headers.Authorization = `Bearer ${token}`
  } else {
    // 어드민 목업 세션 → 게이트웨이 헤더 폴백
    Object.assign(config.headers, getAdminGatewayHeaders())
  }
  return config
})

// ── RAG 정책문서 목록 ────────────────────────────────────────────────────────

export type RagDocument = {
  docId: string        // String으로 통일 (페이지 state 타입 호환)
  docCd: string
  docTitle: string
  docCategoryCd: string
  docVersion: string
  activeYn: boolean
  createdAt: string
  // 프론트 호환 필드 (페이지에서 d.title, d.status 참조)
  title: string
  status: string
}

function mapDoc(raw: Record<string, unknown>): RagDocument {
  return {
    ...(raw as Omit<RagDocument, 'docId' | 'title' | 'status'>),
    docId:  String(raw.docId),
    title:  String(raw.docTitle ?? raw.title ?? ''),
    status: raw.activeYn ? 'INDEXED' : 'INACTIVE',
  }
}

export async function getRagDocuments(): Promise<RagDocument[]> {
  const { data } = await aiApi.get('/api/internal/advisory/documents')
  const items: Record<string, unknown>[] = data?.data ?? data ?? []
  return items.map(mapDoc)
}

// ── RAG 정책문서 등록 ────────────────────────────────────────────────────────

export async function uploadRagDocument(payload: { title: string; content: string }) {
  // docCd: 제목에서 영숫자·한글만 추출해 50자 이내 코드 자동 생성
  const docCd = payload.title
    .replace(/[^a-zA-Z0-9가-힣]/g, '_')
    .toUpperCase()
    .slice(0, 50)
  const { data } = await aiApi.post('/api/internal/advisory/documents', {
    docCd,
    docTitle:       payload.title,
    docCategoryCd:  'POLICY',
    docVersion:     '1.0',
    content:        payload.content,
  })
  return data?.data ?? data
}

// ── 문서 활성화 (재인덱싱 대체) ──────────────────────────────────────────────

export async function reindexRagDocument(docId: string | number) {
  const { data } = await aiApi.put(`/api/internal/advisory/documents/${docId}/activate`, null, {
    params: { active: true },
  })
  return data?.data ?? data
}

// ── 인제스션 로그 (현재 백엔드 미구현 — 빈 배열 반환) ─────────────────────

// eslint-disable-next-line @typescript-eslint/no-unused-vars, @typescript-eslint/no-explicit-any
export async function getRagDocumentIngestionLogs(_docId: string | number): Promise<any> {
  return []
}

// ── 케이스 백필 (Bootstrap 대체) ─────────────────────────────────────────────

export async function bootstrapRagDocuments() {
  const { data } = await aiApi.post('/api/internal/advisory/rag/case-index/backfill', null, {
    params: { dryRun: false },
  })
  return data?.data ?? data
}

// ── 유사 사례 / 정책 인용 (AdvisoryRagController) ────────────────────────────

export async function getSimilarCases(advrId: number, topK = 5) {
  const { data } = await aiApi.get(`/api/advisory/reports/${advrId}/similar-cases`, {
    headers: { 'X-Actor-Role': 'AUDITOR' },
    params: { topK },
  })
  return data?.data ?? data
}

export async function getPolicyCitations(advrId: number) {
  const { data } = await aiApi.get(`/api/advisory/reports/${advrId}/citations`, {
    headers: { 'X-Actor-Role': 'AUDITOR' },
  })
  return data?.data ?? data
}
