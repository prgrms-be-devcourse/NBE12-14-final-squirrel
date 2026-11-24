import { useSyncExternalStore } from "react";
import type { AccountType } from "@/lib/types";

// 로그인 상태의 진짜 기준은 Spring 의 SESSION 쿠키(HttpOnly)다.
// 여기에는 헤더 메뉴를 그리려고 계정 종류만 적어 둔다. (비밀 값은 담지 않는다)

export type Account = { accountType: AccountType; role: string | null };

const STORAGE_KEY = "tickting.account";
const listeners = new Set<() => void>();

let cachedRaw: string | null | undefined;
let cachedValue: Account | null = null;

function read(): Account | null {
  let raw: string | null = null;
  try {
    raw = window.localStorage.getItem(STORAGE_KEY);
  } catch {
    raw = null;
  }
  if (raw === cachedRaw) return cachedValue;
  cachedRaw = raw;
  try {
    cachedValue = raw ? (JSON.parse(raw) as Account) : null;
  } catch {
    cachedValue = null;
  }
  return cachedValue;
}

// 로그인·로그아웃이 일어난 횟수. 화면이 이 값이 바뀌면 처음부터 다시 그려져서(AccountScope)
// 이전 계정의 입력값과 불러온 데이터가 다음 계정 화면에 남지 않는다.
let epoch = 0;

function notify() {
  listeners.forEach((listener) => listener());
}

export function saveAccount(account: Account) {
  try {
    window.localStorage.setItem(STORAGE_KEY, JSON.stringify(account));
  } catch {
    // 저장소를 못 쓰는 환경이면 메뉴만 갱신되지 않는다.
  }
  epoch += 1;
  notify();
}

// expired: 세션이 풀려서(401) 표시만 지울 때 true. 사용자가 로그아웃한 것과 달리 화면을 새로 그리지 않는다.
// 어차피 로그인 화면으로 이동하는 중이라 다시 그리면 같은 요청만 한 번 더 나가고, 다음 로그인에서 화면이 새로 그려진다.
export function clearAccount({ expired = false }: { expired?: boolean } = {}) {
  // 로그인한 적 없는 상태에서 401 이 오면 여기를 부른다. 그때마다 화면을 다시 그리면 401 → 다시 그림이 끝없이 이어지므로,
  // 저장된 계정이 실제로 있었을 때만 초기화 대상으로 센다.
  const hadAccount = read() !== null && !expired;
  try {
    window.localStorage.removeItem(STORAGE_KEY);
  } catch {
    // 위와 같다.
  }
  if (hadAccount) epoch += 1;
  notify();
}

// 서버에서는 undefined(아직 모름), 브라우저에서는 Account 또는 null.
export function useAccount(): Account | null | undefined {
  return useSyncExternalStore(
    (listener) => {
      listeners.add(listener);
      window.addEventListener("storage", listener);
      return () => {
        listeners.delete(listener);
        window.removeEventListener("storage", listener);
      };
    },
    read,
    () => undefined,
  );
}

// 로그인·로그아웃이 일어날 때마다 바뀌는 값. 다른 탭에서 로그인·로그아웃해도 바뀐다.
export function useAccountEpoch(): number {
  return useSyncExternalStore(
    (listener) => {
      listeners.add(listener);
      const onStorage = (event: StorageEvent) => {
        if (event.key !== null && event.key !== STORAGE_KEY) return;
        epoch += 1;
        listener();
      };
      window.addEventListener("storage", onStorage);
      return () => {
        listeners.delete(listener);
        window.removeEventListener("storage", onStorage);
      };
    },
    () => epoch,
    () => 0,
  );
}
