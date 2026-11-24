import { useMemo, useSyncExternalStore } from "react";
import type { BookingCreated } from "@/lib/types";

// 예매 상세 API(BKG-05)가 서버에 생기기 전에도 결제 화면이 예매 정보를 읽도록,
// 예매를 만든 직후의 응답을 이 탭의 sessionStorage 에 잠깐 둔다.
// 서버의 값을 대신하는 게 아니라 같은 응답을 다시 쓰는 것이라, 금액은 승인 때 서버가 다시 검증한다.
// 탭을 닫으면 사라지고, 결제를 마치면 지운다.

export type CachedBooking = BookingCreated & {
  performanceId: number;
  scheduleId: number;
  performanceTitle: string;
  scheduleStartAt: string;
  // 목 데이터로 만든 예매인지. 목 예매는 토스 결제창을 열지 않는다.
  mock: boolean;
};

const key = (bookingId: number | string) => `tickting.booking.${bookingId}`;

export function saveBooking(booking: CachedBooking): void {
  try {
    window.sessionStorage.setItem(key(booking.bookingId), JSON.stringify(booking));
  } catch {
    // 저장소를 못 써도 결제 화면이 서버 조회로 대신한다.
  }
}

export function loadBooking(bookingId: number | string): CachedBooking | null {
  try {
    const raw = window.sessionStorage.getItem(key(bookingId));
    return raw ? (JSON.parse(raw) as CachedBooking) : null;
  } catch {
    return null;
  }
}

export function clearBooking(bookingId: number | string): void {
  try {
    window.sessionStorage.removeItem(key(bookingId));
  } catch {
    // 지우지 못해도 다음 결제 때 서버가 상태를 확인한다.
  }
}

const subscribe = () => () => {};

// 서버 렌더와 하이드레이션 때는 undefined(아직 모름), 브라우저에서는 저장된 값이나 null(없음)을 돌려준다.
// sessionStorage 를 렌더 중에 바로 읽으면 하이드레이션이 어긋나서 useSyncExternalStore 로 읽는다.
export function useCachedBooking(bookingId: string): { state: "unknown" | "none" | "found"; booking: CachedBooking | null } {
  const raw = useSyncExternalStore(
    subscribe,
    () => {
      try {
        return window.sessionStorage.getItem(key(bookingId)) ?? "";
      } catch {
        return "";
      }
    },
    () => undefined,
  );
  return useMemo(() => {
    if (raw === undefined) return { state: "unknown" as const, booking: null };
    if (raw === "") return { state: "none" as const, booking: null };
    try {
      return { state: "found" as const, booking: JSON.parse(raw) as CachedBooking };
    } catch {
      return { state: "none" as const, booking: null };
    }
  }, [raw]);
}
