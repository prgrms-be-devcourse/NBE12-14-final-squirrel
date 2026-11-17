"use client";

import Link from "next/link";
import { useParams, useRouter } from "next/navigation";
import { useEffect, useState } from "react";
import { ApiError, api, useApi } from "@/lib/api";
import { dateTimeText, won } from "@/lib/format";
import type { BookingDetail, PaymentConfirmed, PaymentReady, PerformanceDetail } from "@/lib/types";
import { Ticket } from "@/components/ticket";
import { ErrorBox, Loading } from "@/components/ui";

function useNow(intervalMs: number): number {
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => {
    const timer = window.setInterval(() => setNow(Date.now()), intervalMs);
    return () => window.clearInterval(timer);
  }, [intervalMs]);
  return now;
}

export default function PayPage() {
  const { id } = useParams<{ id: string }>();
  const router = useRouter();
  const booking = useApi<BookingDetail>(`/api/v1/bookings/${id}`);
  const performanceId = booking.data?.performance.performanceId;
  const performance = useApi<PerformanceDetail>(performanceId ? `/api/v1/performances/${performanceId}` : null);

  const now = useNow(1000);
  const [receive, setReceive] = useState<"ONSITE" | "DELIVERY">("ONSITE");
  const [recipient, setRecipient] = useState({ name: "", phone: "", address: "" });
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState<string | null>(null);

  if (booking.loading) return <Loading />;
  if (booking.error) {
    if (booking.error.status === 401) router.push("/login");
    return <ErrorBox error={booking.error} onRetry={booking.reload} />;
  }
  const b = booking.data;
  if (!b) return null;

  const allowed = performance.data?.sale.receiveType ?? "ONSITE";
  const receiveType = allowed === "BOTH" ? receive : allowed;
  const remainingMs = b.expiresAt ? new Date(b.expiresAt).getTime() - now : 0;
  const expired = b.status === "EXPIRED" || (b.status === "PENDING" && remainingMs <= 0);
  const minutes = Math.max(0, Math.floor(remainingMs / 60000));
  const seconds = Math.max(0, Math.floor((remainingMs % 60000) / 1000));

  async function pay() {
    if (!b) return;
    setBusy(true);
    setMessage(null);
    try {
      const ready = await api<PaymentReady>(`/api/v1/bookings/${b.bookingId}/payments`, {
        method: "POST",
        body:
          receiveType === "DELIVERY"
            ? {
                receiveType,
                recipientName: recipient.name,
                recipientPhone: recipient.phone,
                recipientAddress: recipient.address,
              }
            : { receiveType },
      });
      // 토스 결제창은 아직 연동 전이라, 결제 승인 API 를 테스트 값으로 바로 호출한다.
      // 실제 연동 때는 이 자리에 토스 SDK(클라이언트 키만 사용)로 결제창을 띄우고
      // 돌려받은 paymentKey 로 승인한다. 승인 호출 자체는 서버가 토스를 부른다.
      await api<PaymentConfirmed>("/api/v1/payments/confirm", {
        method: "POST",
        body: { paymentKey: `test_${Date.now()}`, orderId: ready.orderId, amount: ready.amount },
      });
      router.push(`/bookings/${b.bookingId}`);
    } catch (error) {
      setMessage(error instanceof ApiError ? error.message : "결제에 실패했습니다");
    } finally {
      setBusy(false);
    }
  }

  if (b.status !== "PENDING" && !expired) {
    return (
      <div className="card mx-auto max-w-lg p-6 text-center">
        <p className="font-medium">이미 처리된 예매입니다</p>
        <Link href={`/bookings/${b.bookingId}`} className="btn mt-4">
          예매 상세 보기
        </Link>
      </div>
    );
  }

  return (
    <div className="mx-auto max-w-2xl space-y-6">
      <h1 className="text-3xl leading-tight font-semibold">결제</h1>

      <Ticket
        head={
          <>
            <p className="text-xs text-muted">예매번호 {b.bookingNo}</p>
            <h2 className="mt-2 text-xl leading-snug font-semibold">{b.performance.title}</h2>
            <p className="mt-1 text-sm text-secondary">{dateTimeText(b.schedule.startAt)}</p>
          </>
        }
        stub={
          <dl className="space-y-2 text-sm">
            <div className="flex justify-between">
              <dt className="text-secondary">티켓 금액</dt>
              <dd>{won(b.ticketAmount)}</dd>
            </div>
            <div className="flex justify-between">
              <dt className="text-secondary">예매 수수료</dt>
              <dd>{won(b.bookingFeeAmount)}</dd>
            </div>
            <div className="flex items-baseline justify-between border-t border-line pt-3">
              <dt>결제 금액</dt>
              <dd className="text-3xl font-semibold">{won(b.totalAmount)}</dd>
            </div>
          </dl>
        }
      >
        <ul className="divide-y divide-line text-sm">
          {b.seats.map((s) => (
            <li key={s.bookingSeatId} className="flex justify-between py-2">
              <span>
                {s.zoneName} {s.rowLabel} {s.seatNumber}번
                <span className="ml-2 text-muted">{s.gradeName}</span>
              </span>
              <span>{won(s.price)}</span>
            </li>
          ))}
        </ul>
      </Ticket>

      {expired ? (
        <div className="rounded-xl border border-accent/30 bg-accent-soft p-5 text-sm text-accent-strong">
          <p>좌석 점유 시간이 지났습니다. 처음부터 다시 선택해 주세요.</p>
          <Link href={`/performances/${b.performance.performanceId}`} className="btn mt-3">
            공연으로 돌아가기
          </Link>
        </div>
      ) : (
        <>
          <p className="rounded-xl bg-raised px-4 py-3 text-center text-sm text-secondary">
            남은 결제 시간 {minutes}:{String(seconds).padStart(2, "0")}
          </p>

          <section className="card space-y-4 p-6">
            <h2 className="font-semibold">수령 방법</h2>
            {allowed === "BOTH" ? (
              <div className="flex gap-2">
                {(["ONSITE", "DELIVERY"] as const).map((type) => (
                  <button
                    key={type}
                    type="button"
                    onClick={() => setReceive(type)}
                    className={`btn ${receive === type ? "btn-dark" : ""}`}
                  >
                    {type === "ONSITE" ? "현장 수령" : "배송"}
                  </button>
                ))}
              </div>
            ) : (
              <p className="text-sm">{allowed === "ONSITE" ? "현장 수령" : "배송"}</p>
            )}
            {receiveType === "DELIVERY" && (
              <div className="grid gap-3 sm:grid-cols-2">
                <div>
                  <label className="label" htmlFor="rname">
                    받는 사람
                  </label>
                  <input
                    id="rname"
                    className="input"
                    value={recipient.name}
                    onChange={(e) => setRecipient({ ...recipient, name: e.target.value })}
                  />
                </div>
                <div>
                  <label className="label" htmlFor="rphone">
                    연락처
                  </label>
                  <input
                    id="rphone"
                    className="input"
                    inputMode="tel"
                    value={recipient.phone}
                    onChange={(e) => setRecipient({ ...recipient, phone: e.target.value })}
                  />
                </div>
                <div className="sm:col-span-2">
                  <label className="label" htmlFor="raddr">
                    주소
                  </label>
                  <input
                    id="raddr"
                    className="input"
                    value={recipient.address}
                    onChange={(e) => setRecipient({ ...recipient, address: e.target.value })}
                  />
                </div>
              </div>
            )}
          </section>

          {message && <p className="text-sm text-accent-strong">{message}</p>}
          <button type="button" className="btn btn-primary btn-lg w-full" disabled={busy} onClick={pay}>
            {busy ? "결제 처리 중" : `${won(b.totalAmount)} 결제하기`}
          </button>
          <p className="text-center text-xs text-muted">
            토스페이먼츠 결제창은 연결 전이라 테스트 결제로 승인됩니다
          </p>
        </>
      )}
    </div>
  );
}
