import { usePathname, useRouter } from "next/navigation";
import { useCallback } from "react";

// 예매(좌석 선택·결제)는 PC 에서 별도 창으로 연다. 창은 같은 사이트의 /popup 아래 주소를 쓰고,
// 로그인 쿠키가 그대로 공유돼서 서버(Spring)가 바꿀 것은 없다.
export const POPUP_PREFIX = "/popup";
const MESSAGE_PAID = "tickting:booking-paid";
const WINDOW_WIDTH = 1040;
const WINDOW_HEIGHT = 820;

type PaidMessage = { type: typeof MESSAGE_PAID; bookingId: number };

export function isPaidMessage(data: unknown): data is PaidMessage {
  return typeof data === "object" && data !== null && (data as { type?: unknown }).type === MESSAGE_PAID;
}

// 지금 화면이 예매 창 안인지, 그리고 그 안에서 쓸 주소와 이동 함수를 돌려준다.
export function useBookingWindow() {
  const pathname = usePathname();
  const router = useRouter();
  const inPopup = pathname.startsWith(POPUP_PREFIX);
  const prefix = inPopup ? POPUP_PREFIX : "";

  // 예매 창 안에서는 사이트 화면(로그인 등)으로 가지 않고, 본 창을 옮긴 뒤 이 창을 닫는다.
  const goTo = useCallback(
    (href: string) => {
      if (inPopup && window.opener && !window.opener.closed) {
        window.opener.location.assign(href);
        window.close();
        return;
      }
      router.push(href);
    },
    [inPopup, router],
  );

  const close = useCallback(() => {
    if (inPopup) window.close();
  }, [inPopup]);

  return { inPopup, prefix, goTo, close, toLogin: () => goTo("/login") };
}

// PC 에서만 별도 창을 쓴다. 모바일 브라우저는 새 창이 탭처럼 열려서 같은 창에서 이동한다.
function wantsWindow(): boolean {
  return window.matchMedia("(min-width: 900px) and (pointer: fine)").matches;
}

// 예매하기 버튼의 클릭 처리 안에서 불러야 팝업 차단에 걸리지 않는다.
// 창을 열었으면 true. 열지 못했거나(차단, 모바일) 쓰지 않으면 false 라서 호출한 쪽이 같은 창에서 이동한다.
export function openBookingWindow(performanceId: number, scheduleId: number): boolean {
  if (!wantsWindow()) return false;
  const left = Math.max(0, Math.round(window.screenX + (window.outerWidth - WINDOW_WIDTH) / 2));
  const top = Math.max(0, Math.round(window.screenY + (window.outerHeight - WINDOW_HEIGHT) / 2));
  // 창 이름을 회차마다 고정해서 같은 회차를 다시 눌러도 창이 늘지 않고 열려 있는 창이 앞으로 온다.
  const opened = window.open(
    `${POPUP_PREFIX}/performances/${performanceId}/schedules/${scheduleId}`,
    `tickting-booking-${scheduleId}`,
    `popup=yes,width=${WINDOW_WIDTH},height=${WINDOW_HEIGHT},left=${left},top=${top},resizable=yes,scrollbars=yes`,
  );
  if (!opened) return false;
  opened.focus();
  return true;
}

// 결제를 마치면 예매 창이 본 창에 알려서 내 예매로 옮기게 한다.
export function notifyPaid(bookingId: number): void {
  if (window.opener && !window.opener.closed) {
    window.opener.postMessage({ type: MESSAGE_PAID, bookingId } satisfies PaidMessage, window.location.origin);
  }
}
