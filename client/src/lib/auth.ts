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

function notify() {
  listeners.forEach((listener) => listener());
}

export function saveAccount(account: Account) {
  try {
    window.localStorage.setItem(STORAGE_KEY, JSON.stringify(account));
  } catch {
    // 저장소를 못 쓰는 환경이면 메뉴만 갱신되지 않는다.
  }
  notify();
}

export function clearAccount() {
  try {
    window.localStorage.removeItem(STORAGE_KEY);
  } catch {
    // 위와 같다.
  }
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
