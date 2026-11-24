"use client";

import { useRouter } from "next/navigation";
import { useEffect } from "react";
import { isPaidMessage } from "@/lib/popup";

// 예매 창에서 결제를 마쳤다는 알림을 받으면 본 창을 내 예매로 옮긴다.
// 같은 사이트가 보낸 메시지만 받는다.
export function PopupBridge() {
  const router = useRouter();
  useEffect(() => {
    function onMessage(event: MessageEvent) {
      if (event.origin !== window.location.origin || !isPaidMessage(event.data)) return;
      window.focus();
      router.push("/bookings");
    }
    window.addEventListener("message", onMessage);
    return () => window.removeEventListener("message", onMessage);
  }, [router]);
  return null;
}
