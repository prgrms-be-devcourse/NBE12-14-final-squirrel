import { useCallback, useEffect, useState, useSyncExternalStore } from "react";
import { clearAccount } from "@/lib/auth";
import type { ApiEnvelope } from "@/lib/types";

// 브라우저는 Spring 이 아니라 Next 서버(BFF)의 /api/* 만 부른다.

export class ApiError extends Error {
  constructor(
    public status: number,
    public code: string,
    message: string,
  ) {
    super(message);
  }
}

type Query = Record<string, string | number | null | undefined>;

type Options = {
  method?: "GET" | "POST" | "PUT" | "PATCH" | "DELETE";
  body?: unknown;
  query?: Query;
  headers?: Record<string, string>;
};

// BFF 가 목 데이터로 답했는지. 화면 위쪽에 안내를 띄우는 데 쓴다.
let mockSeen = false;
const mockListeners = new Set<() => void>();

function markMock() {
  if (mockSeen) return;
  mockSeen = true;
  mockListeners.forEach((listener) => listener());
}

export function useMockSeen(): boolean {
  return useSyncExternalStore(
    (listener) => {
      mockListeners.add(listener);
      return () => mockListeners.delete(listener);
    },
    () => mockSeen,
    () => false,
  );
}

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
  return (await apiWithMeta<T>(path, options)).data;
}

// 응답이 목 데이터인지 함께 돌려준다. 결제처럼 "진짜 서버가 답했는가"가 중요한 곳에서 쓴다.
export async function apiWithMeta<T>(path: string, options: Options = {}): Promise<{ data: T; mock: boolean }> {
  const { method = "GET", body, query, headers } = options;
  const response = await fetch(`${path}${toQueryString(query)}`, {
    method,
    headers: {
      ...(body !== undefined ? { "Content-Type": "application/json" } : {}),
      ...headers,
    },
    body: body !== undefined ? JSON.stringify(body) : undefined,
    cache: "no-store",
  });

  const mock = Boolean(response.headers.get("x-mock"));
  if (mock) markMock();

  let envelope: ApiEnvelope<T> | null = null;
  try {
    envelope = (await response.json()) as ApiEnvelope<T>;
  } catch {
    envelope = null;
  }

  if (!response.ok || !envelope?.success) {
    if (response.status === 401) clearAccount();
    throw new ApiError(
      response.status,
      envelope?.code ?? "COMMON999",
      envelope?.message || "요청을 처리하지 못했습니다.",
    );
  }
  return { data: envelope.data, mock };
}

type Loaded<T> = { key: string; data?: T; error?: ApiError };

// 조회용 훅. 경로나 쿼리가 바뀌면 다시 읽고, path 가 null 이면 읽지 않는다.
export function useApi<T>(path: string | null, query?: Query, headers?: Record<string, string>) {
  const key = path === null ? "" : `${path}${toQueryString(query)}`;
  const [state, setState] = useState<Loaded<T>>({ key: "" });
  const [version, setVersion] = useState(0);
  const headerKey = headers ? JSON.stringify(headers) : "";

  useEffect(() => {
    if (path === null) return;
    let ignore = false;
    api<T>(path, { query, headers: headerKey ? JSON.parse(headerKey) : undefined })
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
  }, [key, headerKey, version]);

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

export const IMAGE_MAX_BYTES = 10 * 1024 * 1024;
const IMAGE_TYPES = ["image/jpeg", "image/png", "image/webp"];

// 사진을 서버로 보내면 서버가 S3 에 올리고 공개 주소를 돌려준다. (api-spec IMG-01)
// 요청 전체가 11MB 를 넘으면 서버가 JSON 없이 연결을 끊으므로 올리기 전에 크기를 확인한다.
export async function uploadImage(file: File, purpose: ImagePurpose): Promise<string> {
  if (!IMAGE_TYPES.includes(file.type)) {
    throw new ApiError(400, "CLIENT", "jpg, png, webp 형식의 사진만 올릴 수 있습니다");
  }
  if (file.size === 0 || file.size > IMAGE_MAX_BYTES) {
    throw new ApiError(400, "CLIENT", "사진은 10MB 이하여야 합니다");
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
  if (response.headers.get("x-mock")) markMock();

  let envelope: ApiEnvelope<{ imageUrl: string }> | null = null;
  try {
    envelope = (await response.json()) as ApiEnvelope<{ imageUrl: string }>;
  } catch {
    envelope = null;
  }
  if (!response.ok || !envelope?.success) {
    if (response.status === 401) clearAccount();
    throw new ApiError(response.status, envelope?.code ?? "COMMON999", envelope?.message || "사진을 올리지 못했습니다");
  }
  return envelope.data.imageUrl;
}
