"use client";

import { usePathname } from "next/navigation";
import { POPUP_PREFIX } from "@/lib/popup";

// 예매 창(/popup)에는 사이트 머리글·바닥글을 그리지 않는다.
export function HideInPopup({ children }: { children: React.ReactNode }) {
  const pathname = usePathname();
  return pathname.startsWith(POPUP_PREFIX) ? null : <>{children}</>;
}
