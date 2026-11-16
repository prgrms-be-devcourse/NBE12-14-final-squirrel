import type { NextRequest } from "next/server";

// 브라우저는 Spring 을 직접 부르지 않고 이 BFF(Next 서버)만 부른다.
// 경로를 그대로 이어 붙이므로 /api/v1/... 은 Spring 의 /api/v1/... 이 된다.
// SESSION 쿠키는 그대로 전달해서 Spring 의 Redis 세션이 로그인 상태를 판단한다.

const SPRING_API_URL = process.env.SPRING_API_URL;

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

async function proxy(request: NextRequest): Promise<Response> {
  if (!SPRING_API_URL) {
    // 키가 없으면 어디로 보낼지 모른다. 내부 설정 내용은 응답에 담지 않는다.
    console.error("SPRING_API_URL 이 설정되지 않았다 (client/.env.local)");
    return Response.json(
      { success: false, code: "COMMON500", message: "서버 오류가 발생했습니다." },
      { status: 500 },
    );
  }

  const target = new URL(request.nextUrl.pathname + request.nextUrl.search, SPRING_API_URL);

  const headers = new Headers(request.headers);
  HOP_BY_HOP.forEach((name) => headers.delete(name));

  const hasBody = request.method !== "GET" && request.method !== "HEAD";

  let upstream: Response;
  try {
    upstream = await fetch(target, {
      method: request.method,
      headers,
      body: hasBody ? await request.arrayBuffer() : undefined,
      redirect: "manual",
      cache: "no-store",
    });
  } catch (error) {
    console.error("Spring 서버 호출 실패", error);
    return Response.json(
      { success: false, code: "COMMON502", message: "서버에 연결할 수 없습니다." },
      { status: 502 },
    );
  }

  const responseHeaders = new Headers(upstream.headers);
  HOP_BY_HOP.forEach((name) => responseHeaders.delete(name));

  return new Response(upstream.body, {
    status: upstream.status,
    headers: responseHeaders,
  });
}

export {
  proxy as GET,
  proxy as POST,
  proxy as PUT,
  proxy as PATCH,
  proxy as DELETE,
};
