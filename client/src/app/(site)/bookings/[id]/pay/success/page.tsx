"use client";

import Link from "next/link";
import { useParams, useRouter, useSearchParams } from "next/navigation";
import { useEffect, useRef, useState } from "react";
import { ApiError, api } from "@/lib/api";
import type { PaymentConfirmed } from "@/lib/types";
import { Loading } from "@/components/ui";

type Failure = { code: string; message: string };

// 결제 결과를 모르는(502) 경우와 이미 처리된(409) 경우는 다시 결제하면 안 된다.
const NO_RETRY_CODES = new Set(["PAYMENT006", "PAYMENT003"]);

export default function PaySuccessPage() {
  const { id } = useParams<{ id: string }>();
  const router = useRouter();
  const params = useSearchParams();
  const [failure, setFailure] = useState<Failure | null>(null);
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
      .then((confirmed) => router.replace(`/bookings/${confirmed.bookingId}`))
      .catch((error: unknown) => {
        if (error instanceof ApiError && error.status === 401) {
          router.replace("/login");
          return;
        }
        setFailure(
          error instanceof ApiError
            ? { code: error.code, message: error.message }
            : { code: "CLIENT", message: "결제를 확인하지 못했습니다" },
        );
      });
  }, [invalid, paymentKey, orderId, amount, router]);

  const shown = failure ?? (invalid ? { code: "CLIENT", message: "결제 정보가 올바르지 않습니다" } : null);

  if (!shown) {
    return (
      <div className="mx-auto max-w-lg py-16 text-center">
        <Loading />
        <p className="mt-2 text-sm text-secondary">결제를 확인하고 있습니다. 창을 닫지 마세요</p>
      </div>
    );
  }

  return (
    <div className="card mx-auto max-w-lg space-y-4 p-6 text-center">
      <h1 className="text-xl font-semibold">결제를 완료하지 못했습니다</h1>
      <p role="alert" className="text-sm text-secondary">
        {shown.message}
      </p>
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
