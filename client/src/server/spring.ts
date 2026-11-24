import type { ApiEnvelope, PerformanceDetail } from "@/lib/types";

// Spring 서버 호출 한곳 모음. 외부 호출이라 실패(연결 불가)를 null 로 돌려준다.

export function springBaseUrl(): string | null {
  return process.env.SPRING_API_URL || null;
}

// 서버가 닿지 않을 때 목 응답으로 대신할지. 개발에서만 켠다.
// 운영(Vercel)에서 Spring 이 잠깐 안 닿을 때 가짜 공연·예매가 보이거나 아무나 목으로 로그인되면 안 되기 때문이다.
// MOCK_FALLBACK=true/false 로 직접 정할 수 있고, 정하지 않으면 개발(next dev)에서만 켜진다.
export function mockEnabled(): boolean {
  const flag = process.env.MOCK_FALLBACK;
  if (flag === "true") return true;
  if (flag === "false") return false;
  return process.env.NODE_ENV !== "production";
}

// Spring 이 토스·S3 를 부르느라 오래 걸리는 요청은 BFF 가 먼저 끊으면 안 된다.
// (결제 승인은 토스 읽기 제한 10초 + 연결 2초라서 그보다 길게 기다린다. 먼저 끊으면 돈은 나갔는데 화면은 실패로 보인다)
const LONG_TIMEOUT_MS = 25_000;
const DEFAULT_TIMEOUT_MS = 10_000;

function isLongRequest(path: string): boolean {
  if (path === "/api/v1/payments/confirm" || path === "/api/v1/images") return true;
  // /api/v1/bookings/{번호}/cancel
  const parts = path.split("/");
  return parts.length === 6 && parts[3] === "bookings" && parts[5] === "cancel" && /^[0-9]+$/.test(parts[4]);
}

function timeoutFor(pathWithQuery: string): number {
  return isLongRequest(pathWithQuery.split("?")[0]) ? LONG_TIMEOUT_MS : DEFAULT_TIMEOUT_MS;
}

export async function springFetch(
  pathWithQuery: string,
  init: RequestInit = {},
): Promise<Response | null> {
  const base = springBaseUrl();
  if (!base) return null;
  try {
    return await fetch(new URL(pathWithQuery, base), {
      cache: "no-store",
      redirect: "manual",
      signal: AbortSignal.timeout(timeoutFor(pathWithQuery)),
      ...init,
    });
  } catch {
    return null;
  }
}

// 목 예매가 공연 정보(제목·등급·취소 규정)를 채울 때 쓴다. Spring 에 있으면 실제 값을 쓴다.
export async function fetchSpringPerformance(
  performanceId: number,
): Promise<PerformanceDetail | null> {
  const response = await springFetch(`/api/v1/performances/${performanceId}`);
  if (!response || !response.ok) return null;
  try {
    const body = (await response.json()) as ApiEnvelope<PerformanceDetail>;
    const data = body.data;
    // 예상한 모양이 아니면 쓰지 않는다. (목 예매가 등급·회차를 바로 읽기 때문)
    const usable =
      body.success && data && Array.isArray(data.grades) && Array.isArray(data.schedules) && !!data.sale;
    return usable ? data : null;
  } catch {
    return null;
  }
}
