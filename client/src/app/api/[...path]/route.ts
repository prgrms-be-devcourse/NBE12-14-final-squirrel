import type { NextRequest } from "next/server";
import { springFetch } from "@/server/spring";

// 브라우저는 Spring 을 직접 부르지 않고 이 BFF(Next 서버)만 부른다.
// 경로를 그대로 이어 붙이므로 /api/v1/... 은 Spring 의 /api/v1/... 이 된다.
// SESSION 쿠키는 그대로 전달해서 Spring 의 Redis 세션이 로그인 상태를 판단한다.
// Spring 이 답하면 그 응답을 그대로 돌려주고, 연결할 수 없으면 502 를 돌려준다. (가짜 응답으로 대신하지 않는다)

// 결제 승인처럼 오래 걸리는 요청을 Vercel 이 중간에 끊지 않게 한다. (기본값보다 길게)
export const maxDuration = 30;

// 연결 단위 헤더는 프록시가 그대로 넘기면 안 된다.
const HOP_BY_HOP = [
  "connection",
  "keep-alive",
  "transfer-encoding",
  "upgrade",
  "host",
  "content-length",
  "content-encoding",
];

// Nginx(로컬 다중 서버·운영 ALB)는 뒤의 앱이 꺼져 있으면 HTML 로 된 502/503/504 를 돌려준다.
// 연결이 안 되는 것과 같으므로 JSON 502 로 바꾼다. Spring 이 JSON 으로 주는 502(토스 통신 오류 등)는
// 업무 응답이라 그대로 전달한다.
function isGatewayFailure(response: Response): boolean {
  if (![502, 503, 504].includes(response.status)) return false;
  return !(response.headers.get("content-type") ?? "").includes("json");
}

async function proxy(request: NextRequest): Promise<Response> {
  const { pathname, search } = request.nextUrl;
  const method = request.method;
  const hasBody = method !== "GET" && method !== "HEAD";
  const body = hasBody ? await request.arrayBuffer() : undefined;

  const headers = new Headers(request.headers);
  HOP_BY_HOP.forEach((name) => headers.delete(name));

  const upstream = await springFetch(`${pathname}${search}`, { method, headers, body });

  if (upstream === null || isGatewayFailure(upstream)) {
    console.error("Spring 서버에 연결할 수 없다 (SPRING_API_URL 확인)", method, pathname);
    return Response.json(
      { success: false, code: "COMMON502", message: "서버에 연결할 수 없습니다.", data: null },
      { status: 502 },
    );
  }

  const responseHeaders = new Headers(upstream.headers);
  HOP_BY_HOP.forEach((name) => responseHeaders.delete(name));

  return new Response(upstream.body, { status: upstream.status, headers: responseHeaders });
}

export {
  proxy as GET,
  proxy as POST,
  proxy as PUT,
  proxy as PATCH,
  proxy as DELETE,
};
