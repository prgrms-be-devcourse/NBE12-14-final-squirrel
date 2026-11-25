// Spring 서버 호출 한곳 모음. 외부 호출이라 실패(연결 불가)를 null 로 돌려준다.

export function springBaseUrl(): string | null {
  return process.env.SPRING_API_URL || null;
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
