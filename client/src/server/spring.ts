import type { ApiEnvelope, PerformanceDetail } from "@/lib/types";

// Spring 서버 호출 한곳 모음. 외부 호출이라 실패(연결 불가)를 null 로 돌려준다.

export function springBaseUrl(): string | null {
  return process.env.SPRING_API_URL || null;
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
      // 꺼져 있을 때 화면이 오래 멈추지 않게 짧게 기다린다.
      signal: AbortSignal.timeout(8000),
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
