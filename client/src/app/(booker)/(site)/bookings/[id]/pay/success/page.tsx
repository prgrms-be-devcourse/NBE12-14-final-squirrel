"use client";

import Link from "next/link";
import { useRouter, useSearchParams } from "next/navigation";
import { useEffect, useRef, useState } from "react";
import { useRouteParams } from "@/lib/route-params";
import { ApiError, api } from "@/lib/api";
import { clearBooking, loadBooking } from "@/lib/booking-cache";
import { dateTimeText, won } from "@/lib/format";
import type { BookingDetail, PaymentConfirmed } from "@/lib/types";
import { SALE_PAUSED_CODE, SalePausedModal } from "@/components/sale-paused-modal";
import { Loading } from "@/components/ui";

type Failure = { code: string; message: string };

// 결제 결과를 모르는(502) 경우와 이미 처리된(409) 경우는 다시 결제하면 안 된다.
const NO_RETRY_CODES = new Set(["PAYMENT006", "PAYMENT003"]);

export default function PaySuccessPage() {
  const { id } = useRouteParams<{ id: string }>("/bookings/[id]/pay/success");
  const router = useRouter();
  const params = useSearchParams();
  const [failure, setFailure] = useState<Failure | null>(null);
  const [done, setDone] = useState<PaymentConfirmed | null>(null);
  // 판매 중지로 승인이 거절되면 보낼 공연 번호
  const [pausedPerformanceId, setPausedPerformanceId] = useState<number | null>(null);
  // 개발 모드(StrictMode)에서 효과가 두 번 돌아도 승인은 한 번만 보낸다. 새로고침으로 다시 오면 서버가 409 로 막는다.
  const started = useRef(false);

  const paymentKey = params.get("paymentKey");
  const orderId = params.get("orderId");
  const amount = Number(params.get("amount"));

  const invalid = !paymentKey || !orderId || !Number.isInteger(amount) || amount <= 0;

  useEffect(() => {
    if (started.current || invalid) return;
    started.current = true;
    // 승인은 서버가 토스를 불러서 한다. 금액은 서버가 저장한 값과 비교하므로 여기서는 받은 값을 그대로 넘긴다.
    api<PaymentConfirmed>("/api/v1/payments/confirm", {
      method: "POST",
      body: { paymentKey, orderId, amount },
    })
      .then((confirmed) => {
        // 결제가 끝났으니 결제 화면에 쓰던 예매 정보는 지운다. 예매 상세 API 가 없어도 이 화면이 결과를 보여 준다.
        clearBooking(id);
        setDone(confirmed);
      })
      .catch((error: unknown) => {
        if (error instanceof ApiError && error.status === 401) {
          router.push("/login");
          return;
        }
        if (error instanceof ApiError && error.code === SALE_PAUSED_CODE) {
          const cached = loadBooking(id);
          if (cached) setPausedPerformanceId(cached.performanceId);
          else
            // 다른 탭에서 결제한 경우다. 읽지 못하면 아래 실패 화면이 그대로 알린다.
            api<BookingDetail>(`/api/v1/bookings/${id}`)
              .then((booking) => setPausedPerformanceId(booking.performance.performanceId))
              .catch(() => {});
        }
        setFailure(
          error instanceof ApiError
            ? { code: error.code, message: error.message }
            : { code: "CLIENT", message: "결제를 확인하지 못했습니다" },
        );
      });
  }, [id, invalid, paymentKey, orderId, amount, router]);

  const shown = failure ?? (invalid ? { code: "CLIENT", message: "결제 정보가 올바르지 않습니다" } : null);

  if (done) {
    return (
      <div className="card mx-auto max-w-lg space-y-4 p-6 text-center">
        <h1 className="text-xl font-semibold">결제가 완료되었습니다</h1>
        <dl className="mx-auto grid max-w-xs grid-cols-[88px_1fr] gap-x-4 gap-y-2 text-left text-sm">
          <dt className="text-muted">예매번호</dt>
          <dd>{done.bookingNo}</dd>
          <dt className="text-muted">결제 금액</dt>
          <dd>{won(done.amount)}</dd>
          <dt className="text-muted">결제 수단</dt>
          <dd>{done.method}</dd>
          <dt className="text-muted">승인 시각</dt>
          <dd>{dateTimeText(done.approvedAt)}</dd>
        </dl>
        <div className="flex justify-center gap-2">
          <Link href="/" className="btn">
            홈으로
          </Link>
          <Link href="/bookings" className="btn btn-primary">
            내 예매 보기
          </Link>
        </div>
      </div>
    );
  }

  if (!shown) {
    return (
      <div className="mx-auto max-w-lg py-16 text-center">
        <Loading />
        <p className="mt-2 text-sm text-secondary">결제를 확인하고 있습니다. 화면을 닫지 마세요</p>
      </div>
    );
  }

  return (
    <div className="card mx-auto max-w-lg space-y-4 p-6 text-center">
      <h1 className="text-xl font-semibold">결제를 완료하지 못했습니다</h1>
      <p role="alert" className="text-sm text-secondary">
        {shown.message}
      </p>
      {pausedPerformanceId !== null && (
        <SalePausedModal href={`/performances/${pausedPerformanceId}`}>
          결제되지 않았습니다. 좌석 점유 시간 안에 재개되면 내 예매에서 다시 결제할 수 있습니다
        </SalePausedModal>
      )}
      <div className="flex justify-center gap-2">
        <Link href="/bookings" className="btn">
          내 예매 보기
        </Link>
        {!NO_RETRY_CODES.has(shown.code) && (
          <Link href={`/bookings/${id}/pay`} className="btn btn-primary">
            다시 결제하기
          </Link>
        )}
      </div>
    </div>
  );
}
