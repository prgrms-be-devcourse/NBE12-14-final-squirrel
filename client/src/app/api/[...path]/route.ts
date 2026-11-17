import type { NextRequest } from "next/server";
import { handleMock, hasMock, type MockContext } from "@/server/mock/handlers";
import { springFetch } from "@/server/spring";

// 브라우저는 Spring 을 직접 부르지 않고 이 BFF(Next 서버)만 부른다.
// 경로를 그대로 이어 붙이므로 /api/v1/... 은 Spring 의 /api/v1/... 이 된다.
// SESSION 쿠키는 그대로 전달해서 Spring 의 Redis 세션이 로그인 상태를 판단한다.
//
// 응답 우선순위
//  1. Spring 이 답하면 그 응답을 그대로 쓴다. (이미 만들어진 API 는 그대로 동작)
//  2. Spring 에 연결할 수 없거나, 아직 만들어지지 않은 경로(404 COMMON002 / 405 COMMON001)면
//     같은 경로의 목 응답(x-mock 헤더가 붙는다)으로 대신한다.
//  3. 목 응답도 없으면 Spring 의 응답(또는 502)을 그대로 돌려준다.

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

// 아직 만들어지지 않은 API 인지. Spring 의 "없는 경로" 에러 코드로만 판단한다.
// (PERFORMANCE000 같은 업무 404 는 목으로 바꾸면 안 된다)
async function isUnmappedRoute(response: Response): Promise<boolean> {
  if (response.status !== 404 && response.status !== 405) return false;
  try {
    const body = (await response.clone().json()) as { code?: string };
    return body.code === "COMMON002" || body.code === "COMMON001";
  } catch {
    return false;
  }
}

// Nginx(로컬 다중 서버·운영 ALB)는 뒤의 앱이 꺼져 있으면 HTML 로 된 502/503/504 를 돌려준다.
// 연결이 안 되는 것과 같으므로 목으로 대신한다. Spring 이 JSON 으로 주는 502(토스 통신 오류 등)는
// 업무 응답이라 그대로 전달한다.
function isGatewayFailure(response: Response): boolean {
  if (![502, 503, 504].includes(response.status)) return false;
  return !(response.headers.get("content-type") ?? "").includes("json");
}

function parseCookies(header: string | null): Map<string, string> {
  const cookies = new Map<string, string>();
  header?.split(";").forEach((part) => {
    const index = part.indexOf("=");
    if (index > 0) cookies.set(part.slice(0, index).trim(), part.slice(index + 1).trim());
  });
  return cookies;
}

async function proxy(request: NextRequest): Promise<Response> {
  const { pathname, search } = request.nextUrl;
  const method = request.method;
  const hasBody = method !== "GET" && method !== "HEAD";
  const body = hasBody ? await request.arrayBuffer() : undefined;

  const performanceHeader = Number(request.headers.get("x-performance-id"));
  const mockContext: MockContext = {
    method,
    pathname,
    search: request.nextUrl.searchParams,
    cookies: parseCookies(request.headers.get("cookie")),
    json: async () => JSON.parse(new TextDecoder().decode(body)),
    performanceId: Number.isFinite(performanceHeader) && performanceHeader > 0 ? performanceHeader : null,
  };

  const headers = new Headers(request.headers);
  HOP_BY_HOP.forEach((name) => headers.delete(name));

  const upstream = await springFetch(`${pathname}${search}`, { method, headers, body });

  if (upstream === null || isGatewayFailure(upstream)) {
    const mock = await handleMock(mockContext);
    if (mock) return mock;
    console.error("Spring 서버에 연결할 수 없다 (SPRING_API_URL 확인)", method, pathname);
    return Response.json(
      { success: false, code: "COMMON502", message: "서버에 연결할 수 없습니다.", data: null },
      { status: 502 },
    );
  }

  if (hasMock(method, pathname) && (await isUnmappedRoute(upstream))) {
    const mock = await handleMock(mockContext);
    if (mock) return mock;
  }

  const responseHeaders = new Headers(upstream.headers);
  HOP_BY_HOP.forEach((name) => responseHeaders.delete(name));

  // 목으로 로그인했던 흔적(MOCK_SESSION)이 실제 로그아웃 뒤에 남지 않게 같이 지운다.
  if (pathname === "/api/v2/auth/logout") {
    responseHeaders.append("Set-Cookie", "MOCK_SESSION=; Path=/; Max-Age=0; HttpOnly; SameSite=Lax");
  }

  return new Response(upstream.body, { status: upstream.status, headers: responseHeaders });
}

export {
  proxy as GET,
  proxy as POST,
  proxy as PUT,
  proxy as PATCH,
  proxy as DELETE,
};
