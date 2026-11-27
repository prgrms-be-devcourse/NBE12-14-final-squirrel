"use client";

import { useRouter } from "next/navigation";
import { useCallback } from "react";
import { Modal } from "@/components/console";

// 서버가 일시 중지된 회차의 점유·결제·대기열 입장을 거절할 때 쓰는 코드.
export const SALE_PAUSED_CODE = "BOOKING012";

// 예매 중에 판매가 중지되면 알리고 공연 상세로 보낸다. 창을 어떻게 닫든 상세로 간다.
// 뒤로 가기로 막힌 화면에 돌아오지 않게 기록을 바꿔치기한다.
export function SalePausedModal({ href, children }: { href: string; children: React.ReactNode }) {
  const router = useRouter();
  const leave = useCallback(() => router.replace(href), [router, href]);

  return (
    <Modal title="판매가 일시 중지되었습니다" onClose={leave}>
      <div className="space-y-5">
        <p className="text-sm text-secondary">{children}</p>
        <button type="button" className="btn btn-primary w-full" onClick={leave} autoFocus>
          확인
        </button>
      </div>
    </Modal>
  );
}
