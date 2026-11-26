import { useCallback, useEffect, useState } from "react";
import { clearAccount } from "@/lib/auth";
import { ROUTE_PLACEHOLDER } from "@/lib/route-params";
import type { ApiEnvelope } from "@/lib/types";

// 브라우저는 Spring 이 아니라 Next 서버(BFF)의 /api/* 만 부른다.

export class ApiError extends Error {
  constructor(
    public status: number,
    public code: string,
    message: string,
    // 서버가 준 응답 본문 전체. 화면에는 message 만 보여 주고, 원인을 찾을 때 콘솔에서 본다.
    public payload?: unknown,
  ) {
    super(message);
  }
}

// 오류를 화면에 보여 줄 문장으로 만든다. 서버 메시지 뒤에 코드를 붙여서 같은 메시지의 다른 오류를 구분한다.
export function errorText(error: unknown, fallback: string): string {
  return error instanceof ApiError && error.code !== "COMMON999" ? `${error.message} (${error.code})` : fallback;
}

// 콘솔에 남길 때 가릴 값의 이름. 비밀번호·토큰·키는 요청 본문에도 응답에도 그대로 남기지 않는다.
const SENSITIVE_KEY = /pass(word)?|token|secret|authorization|cookie|paymentKey/i;

function masked(value: unknown, depth = 0): unknown {
  if (value === null || typeof value !== "object" || depth > 6) return value;
  if (Array.isArray(value)) return value.map((item) => masked(item, depth + 1));
  return Object.fromEntries(
    Object.entries(value).map(([key, item]) => [key, SENSITIVE_KEY.test(key) ? "***" : masked(item, depth + 1)]),
  );
}

// 모든 API 호출의 요청 본문과 응답을 콘솔에 남긴다. 개발자 도구의 네트워크 탭을 열지 않아도 원인을 볼 수 있게 하려는 것이다.
function trace(method: string, path: string, status: number, request: unknown, response: unknown) {
  const line = `[API] ${method} ${path} → ${status}`;
  const detail = { 요청: request === undefined ? "(본문 없음)" : masked(request), 응답: masked(response) };
  if (status >= 200 && status < 300) console.info(line, detail);
  else console.warn(line, detail);
}

type Query = Record<string, string | number | null | undefined>;

type Options = {
  method?: "GET" | "POST" | "PUT" | "PATCH" | "DELETE";
  body?: unknown;
  query?: Query;
  headers?: Record<string, string>;
  // 창이 닫히거나 새로고침되는 중에 보내는 요청에 켠다. 안 켜면 브라우저가 요청을 취소할 수 있다.
  keepalive?: boolean;
};

function toQueryString(query?: Query): string {
  if (!query) return "";
  const params = new URLSearchParams();
  Object.entries(query).forEach(([key, value]) => {
    if (value !== undefined && value !== null && value !== "") params.set(key, String(value));
  });
  const text = params.toString();
  return text ? `?${text}` : "";
}

export async function api<T>(path: string, options: Options = {}): Promise<T> {
  const { method = "GET", body, query, headers, keepalive } = options;
  // Next 가 경로 값 자리에 채워 둔 자리표시(%%drp:id:해시%%)가 그대로 들어온 경우다. 이 주소로 보내면 연결이 끊긴다
  // (ERR_HTTP2_PROTOCOL_ERROR). 기다리지 않고 오류로 돌려서 화면이 "불러오는 중"에서 멈추지 않게 한다.
  // 경로 값은 useRouteParams 로 읽어서 진짜 값을 쓰므로 정상이라면 여기에 오지 않는다.
  if (path.includes(ROUTE_PLACEHOLDER)) {
    console.error(`[API] 경로에 자리표시 값이 들어 있어 요청하지 않았습니다: ${method} ${path}`);
    throw new ApiError(0, "CLIENT", "주소를 읽지 못했습니다. 새로고침해 주세요");
  }
  const response = await fetch(`${path}${toQueryString(query)}`, {
    method,
    headers: {
      ...(body !== undefined ? { "Content-Type": "application/json" } : {}),
      ...headers,
    },
    body: body !== undefined ? JSON.stringify(body) : undefined,
    cache: "no-store",
    keepalive,
  });

  let envelope: ApiEnvelope<T> | null = null;
  try {
    envelope = (await response.json()) as ApiEnvelope<T>;
  } catch {
    envelope = null;
  }

  trace(method, `${path}${toQueryString(query)}`, response.status, body, envelope ?? "(JSON 이 아닌 응답)");

  if (!response.ok || !envelope?.success) {
    if (response.status === 401) clearAccount({ expired: true });
    throw new ApiError(
      response.status,
      envelope?.code ?? "COMMON999",
      envelope?.message || "요청을 처리하지 못했습니다.",
      envelope,
    );
  }
  return envelope.data;
}

type Loaded<T> = { key: string; data?: T; error?: ApiError };

// 조회용 훅. 경로나 쿼리가 바뀌면 다시 읽고, path 가 null 이면 읽지 않는다.
export function useApi<T>(path: string | null, query?: Query) {
  const key = path === null ? "" : `${path}${toQueryString(query)}`;
  const [state, setState] = useState<Loaded<T>>({ key: "" });
  const [version, setVersion] = useState(0);

  useEffect(() => {
    if (path === null) return;
    let ignore = false;
    api<T>(path, { query })
      .then((data) => {
        if (!ignore) setState({ key, data });
      })
      .catch((error: unknown) => {
        if (!ignore) {
          setState({
            key,
            error: error instanceof ApiError ? error : new ApiError(0, "COMMON999", "알 수 없는 오류"),
          });
        }
      });
    return () => {
      ignore = true;
    };
    // query 는 key 에 이미 반영돼 있다.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [key, version]);

  const reload = useCallback(() => setVersion((v) => v + 1), []);
  const loading = path !== null && (state.key !== key || (state.data === undefined && !state.error));

  return {
    data: state.key === key ? state.data : undefined,
    error: state.key === key ? state.error : undefined,
    loading,
    reload,
  };
}

export type ImagePurpose = "POSTER" | "DETAIL" | "PROFILE";

// 서버는 10MB 까지 받지만, 브라우저는 Next 서버(BFF)를 거쳐 올리고 Vercel 함수는 요청 본문이 4.5MB 를 넘으면 거절한다.
// 그래서 화면에서는 4MB 로 막는다.
export const IMAGE_MAX_BYTES = 4 * 1024 * 1024;
const IMAGE_TYPES = ["image/jpeg", "image/png", "image/webp"];

// 사진을 서버로 보내면 서버가 S3 에 올리고 공개 주소를 돌려준다. (api-spec IMG-01)
// 요청 전체가 11MB 를 넘으면 서버가 JSON 없이 연결을 끊으므로 올리기 전에 크기를 확인한다.
export async function uploadImage(file: File, purpose: ImagePurpose): Promise<string> {
  if (!IMAGE_TYPES.includes(file.type)) {
    throw new ApiError(400, "CLIENT", "jpg, png, webp 형식의 사진만 올릴 수 있습니다");
  }
  if (file.size === 0 || file.size > IMAGE_MAX_BYTES) {
    throw new ApiError(400, "CLIENT", "사진은 4MB 이하여야 합니다");
  }
  const form = new FormData();
  form.set("purpose", purpose);
  form.set("file", file);

  let response: Response;
  try {
    // Content-Type 은 브라우저가 경계값과 함께 붙이므로 직접 쓰지 않는다.
    response = await fetch("/api/v1/images", { method: "POST", body: form, cache: "no-store" });
  } catch {
    throw new ApiError(0, "CLIENT", "사진을 올리지 못했습니다. 잠시 뒤 다시 시도해 주세요");
  }

  let envelope: ApiEnvelope<{ imageUrl: string }> | null = null;
  try {
    envelope = (await response.json()) as ApiEnvelope<{ imageUrl: string }>;
  } catch {
    envelope = null;
  }
  trace("POST", "/api/v1/images", response.status, { purpose, file: `${file.name} (${file.size}바이트, ${file.type})` }, envelope ?? "(JSON 이 아닌 응답)");
  if (!response.ok || !envelope?.success) {
    if (response.status === 401) clearAccount({ expired: true });
    throw new ApiError(response.status, envelope?.code ?? "COMMON999", envelope?.message || "사진을 올리지 못했습니다", envelope);
  }
  return envelope.data.imageUrl;
}
