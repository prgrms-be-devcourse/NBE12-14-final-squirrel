"use client";

import Link from "next/link";
import { useParams, useSearchParams } from "next/navigation";
import { useBookingWindow } from "@/lib/popup";

export default function PayFailPage() {
  const { id } = useParams<{ id: string }>();
  const params = useSearchParams();
  const { prefix, goTo } = useBookingWindow();
  // 토스가 failUrl 에 code, message 를 붙여 준다. 화면에는 메시지만 보여 주고 주문 번호 등은 노출하지 않는다.
  const message = params.get("message") || "결제가 완료되지 않았습니다";

  return (
    <div className="card mx-auto max-w-lg space-y-4 p-6 text-center">
      <h1 className="text-xl font-semibold">결제하지 못했습니다</h1>
      <p role="alert" className="text-sm text-secondary">
        {message}
      </p>
      <p className="text-xs text-muted">좌석 점유 시간이 남아 있으면 다시 결제할 수 있습니다</p>
      <div className="flex justify-center gap-2">
        <button type="button" className="btn" onClick={() => goTo("/bookings")}>
          내 예매 보기
        </button>
        <Link href={`${prefix}/bookings/${id}/pay`} className="btn btn-primary">
          다시 결제하기
        </Link>
      </div>
    </div>
  );
}
