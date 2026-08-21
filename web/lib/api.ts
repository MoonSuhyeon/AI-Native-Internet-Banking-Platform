import axios, { AxiosError, InternalAxiosRequestConfig } from "axios";
import { readAccessToken } from "@/lib/token";

export const api = axios.create({
  // 게이트웨이. 예전 기본값 8080 은 로컬에서 payment-service-a 가 물고 있어,
  // 이 클라이언트를 쓰는 화면(계좌·거래·대출상품 조회)이 게이트웨이를 건너뛰고
  // 엉뚱한 서비스를 불렀다 — 브라우저 콘솔에 404 로 찍히던 것이 이것이다.
  // 같은 변수를 lib/advisory-api.ts·ai-api.ts 는 이미 8088 로 쓰고 있었다.
  baseURL: process.env.NEXT_PUBLIC_API_URL || "http://localhost:8088",
  headers: { "Content-Type": "application/json" },
});

// 요청 인터셉터 — 토큰 자동 첨부
api.interceptors.request.use((config) => {
  if (typeof window !== "undefined") {
    // JWT 가 아닌 값은 여기서 걸러진다(readAccessToken 이 지우고 null 을 준다).
    // 가짜 토큰을 실어 보내면 게이트웨이가 401 을 주고, 화면은 로그인 상태로
    // 남아 원인을 찾기 어려워진다.
    const token = readAccessToken();
    if (token) config.headers.Authorization = `Bearer ${token}`;
    const customerId = localStorage.getItem("customerId");
    if (customerId && !config.headers["X-Customer-Id"]) {
      config.headers["X-Customer-Id"] = customerId === "CUST001" ? "1" : customerId;
    }
  }
  return config;
});

// 응답 인터셉터 — 401이면 refresh 토큰으로 액세스 토큰을 자동 갱신하고 원요청을 1회 재시도한다.
// 액세스 토큰 수명(10분) < UI 세션(30분) 불일치로 생기던 "좀비 로그인"(화면은 로그인,
// API는 전부 401) 구간을 없앤다. 갱신/재시도까지 실패할 때만 로그인 페이지로 보낸다.
type RetriableConfig = InternalAxiosRequestConfig & { _retry?: boolean };

// 자동 갱신 대상 아님. cert/issue 는 본문의 id/password 로 재인증하므로 401=세션만료가 아니라
// "입력한 자격 오류" → 갱신·리다이렉트 없이 페이지 catch 가 인라인 에러로 처리해야 한다(#54).
const AUTH_PATHS = ["/auth/login", "/auth/cert-login", "/auth/cert/issue", "/auth/refresh"];
const SILENT_PATHS = ["/customers/me"]; // 실패해도 로그인으로 안 보냄(배경 보조 호출)

function clearSession() {
  localStorage.removeItem("accessToken");
  localStorage.removeItem("access_token");
  localStorage.removeItem("refreshToken");
  localStorage.removeItem("sessionExpiry");
}

// 동시에 발생한 401들이 refresh 를 한 번만 호출하도록 단일 비행(single-flight)으로 공유한다.
let refreshPromise: Promise<string | null> | null = null;

async function refreshAccessToken(): Promise<string | null> {
  const refreshToken = localStorage.getItem("refreshToken");
  if (!refreshToken) return null;
  try {
    // 인터셉터 재귀를 피하려고 기본 axios 로 직접 호출한다.
    const { data } = await axios.post(
      `${api.defaults.baseURL}/api/v1/auth/refresh`,
      { refreshToken },
      { headers: { "Content-Type": "application/json" } }
    );
    const newToken: string | undefined = data?.data?.accessToken;
    if (!newToken) return null;
    localStorage.setItem("accessToken", newToken);
    localStorage.setItem("access_token", newToken);
    // 백엔드는 refresh 시 refresh 토큰도 회전한다(LoginService.refresh: 옛 키 삭제 →
    // reissueTokens 가 새 refresh 발급·저장). 새 값을 저장하지 않으면 다음 갱신에서
    // stale 토큰으로 호출해 TOKEN_INVALID(401) → 로그아웃되므로 반드시 함께 갱신한다.
    const newRefreshToken: string | undefined = data?.data?.refreshToken;
    if (newRefreshToken) localStorage.setItem("refreshToken", newRefreshToken);
    return newToken;
  } catch {
    return null;
  }
}

api.interceptors.response.use(
  (res) => res,
  async (err: AxiosError) => {
    const config = (err.config ?? {}) as RetriableConfig;
    const url = config.url ?? "";
    const isSilent = SILENT_PATHS.some((p) => url.includes(p));

    if (err.response?.status !== 401 || typeof window === "undefined") {
      return Promise.reject(err);
    }

    // 인증 엔드포인트 자체의 401은 갱신 대상이 아니다. refresh 가 401이면 세션 종료.
    if (AUTH_PATHS.some((p) => url.includes(p))) {
      if (url.includes("/auth/refresh")) {
        clearSession();
        window.location.href = "/login";
      }
      return Promise.reject(err);
    }

    // 이미 한 번 재시도한 요청이면 더 갱신하지 않는다(무한 루프 방지).
    if (config._retry) {
      clearSession();
      if (!isSilent) window.location.href = "/login";
      return Promise.reject(err);
    }
    config._retry = true;

    if (!refreshPromise) {
      refreshPromise = refreshAccessToken().finally(() => {
        refreshPromise = null;
      });
    }
    const newToken = await refreshPromise;

    if (!newToken) {
      clearSession();
      if (!isSilent) window.location.href = "/login";
      return Promise.reject(err);
    }

    // 새 액세스 토큰으로 원요청을 1회 재시도(요청 인터셉터가 새 토큰을 자동 첨부).
    return api(config);
  }
);

// ── 자금이동 승인(step-up) ───────────────────────────────────────────────────
//
// 이체는 로그인과 다른 등급의 행위다. 로그인 세션만으로 이체가 되면 세션 하나가 곧
// 자금 유출이라, 이체 직전에 인증서 PIN 을 다시 확인하고 그 결과를 토큰으로 받는다.
// 토큰은 이 거래(출금계좌·수취계좌·금액)에 묶여 있어 다른 이체에는 쓸 수 없다.
// 설계: docs/plan/transfer-step-up-auth.md

export type TransferApprovalInput = {
  /** 계좌 번호로 묶는다 — 내부이체·타행이체가 같은 승인 체계를 쓰기 위해서다. */
  fromAccountNo: string;
  toAccountNo: string;
  amount: number;
  certSerialNumber: string;
  pin: string;
};

/** 고객의 인증서 목록. 이체 승인에 쓸 인증서를 고르기 위해 부른다. */
export async function fetchMyCertificates(customerId: string) {
  const { data } = await api.get("/api/v1/cert/manage", {
    headers: { "X-Customer-Id": customerId },
  });
  return (data?.data ?? []) as Array<{ serialNumber: string; status: string; certTypeName?: string }>;
}

/**
 * 인증서 PIN 을 확인하고 이 이체에 한정된 승인 토큰을 받는다.
 * PIN 이 틀리면 예외가 난다 — 호출하는 쪽에서 사용자에게 알려야 한다.
 */
export async function issueTransferApproval(
  customerId: string,
  input: TransferApprovalInput
): Promise<string> {
  const { data } = await api.post(
    "/api/v1/customers/me/transaction-approvals",
    input,
    { headers: { "X-Customer-Id": customerId } }
  );
  return data?.data?.approvalToken as string;
}
