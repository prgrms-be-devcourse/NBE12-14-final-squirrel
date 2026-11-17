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

  if (response.headers.get("x-mock")) markMock();

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
  return envelope.data;
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
