"use client";

import { ArrowLeft } from "lucide-react";
import Link from "next/link";
import { useParams, useRouter } from "next/navigation";
import { useState } from "react";
import { ApiError, api, useApi } from "@/lib/api";
import { BOOKING_BADGE, BOOKING_LABEL, dateTimeText, won } from "@/lib/format";
import type { BookingDetail, CancelResult } from "@/lib/types";
import { Ticket } from "@/components/ticket";
import { ErrorBox, Loading } from "@/components/ui";

export default function BookingDetailPage() {
  const { id } = useParams<{ id: string }>();
  const router = useRouter();
  const { data: b, error, loading, reload } = useApi<BookingDetail>(`/api/v1/bookings/${id}`);

  const [selected, setSelected] = useState<number[]>([]);
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState<string | null>(null);

  if (loading) return <Loading />;
  if (error) {
    if (error.status === 401) router.push("/login");
    return <ErrorBox error={error} onRetry={reload} />;
  }
  if (!b) return null;

  const activeIds = b.seats.filter((s) => s.active).map((s) => s.bookingSeatId);

  async function cancel(bookingSeatIds?: number[]) {
    if (!b) return;
    const target = bookingSeatIds ?? activeIds;
    const fee = Math.round(
      b.seats.filter((s) => target.includes(s.bookingSeatId)).reduce((sum, s) => sum + s.price, 0) *
        (b.currentFeeRate / 100),
    );
    if (!window.confirm(`${target.length}매를 취소합니다. 취소 수수료는 약 ${won(fee)}입니다. 진행할까요?`)) {
      return;
    }
    setBusy(true);
    setMessage(null);
    try {
      const result = await api<CancelResult>(`/api/v1/bookings/${b.bookingId}/cancel`, {
        method: "POST",
        body: bookingSeatIds ? { bookingSeatIds } : {},
      });
      setSelected([]);
      setMessage(`취소했습니다. 환불 ${won(result.refund.amount)} (수수료 ${won(result.cancelFeeTotal)})`);
      reload();
    } catch (e) {
      setMessage(e instanceof ApiError ? e.message : "취소에 실패했습니다");
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="mx-auto max-w-2xl space-y-6">
      <Link
        href="/bookings"
        className="inline-flex items-center gap-2 text-sm text-secondary transition-colors duration-150 hover:text-foreground"
      >
        <ArrowLeft size={16} strokeWidth={1.5} />내 예매
      </Link>

      {b.status === "PENDING" && (
        <Link href={`/bookings/${b.bookingId}/pay`} className="btn btn-primary btn-lg w-full">
          결제하러 가기
        </Link>
      )}

      <Ticket
        head={
          <>
            <div className="flex items-center gap-2">
              <span className={BOOKING_BADGE[b.status]}>{BOOKING_LABEL[b.status]}</span>
              <span className="text-xs text-muted">{b.bookingNo}</span>
            </div>
            <h1 className="mt-4 text-2xl leading-snug font-semibold">{b.performance.title}</h1>
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
            {b.refundedAmount > 0 && (
              <div className="flex justify-between text-ok">
                <dt>환불 금액</dt>
                <dd>{won(b.refundedAmount)}</dd>
              </div>
            )}
          </dl>
        }
      >
        <ul className="divide-y divide-line text-sm">
          {b.seats.map((s) => (
            <li key={s.bookingSeatId} className="flex items-center justify-between gap-4 py-2">
              <label className="flex items-center gap-3">
                {b.cancelable && s.active && (
                  <input
                    type="checkbox"
                    checked={selected.includes(s.bookingSeatId)}
                    onChange={(e) =>
                      setSelected(
                        e.target.checked
                          ? [...selected, s.bookingSeatId]
                          : selected.filter((x) => x !== s.bookingSeatId),
                      )
                    }
                  />
                )}
                <span className={s.active ? "" : "text-muted line-through"}>
                  {s.zoneName} {s.rowLabel} {s.seatNumber}번
                  <span className="ml-2 text-muted">{s.gradeName}</span>
                </span>
              </label>
              <span className="text-right">
                {won(s.price)}
                {!s.active && (
                  <span className="block text-xs text-muted">취소 수수료 {won(s.cancelFee ?? 0)}</span>
                )}
              </span>
            </li>
          ))}
        </ul>
      </Ticket>

      <section className="card space-y-2 p-6 text-sm">
        <h2 className="mb-2 font-semibold">수령·결제 정보</h2>
        <p>
          <span className="text-muted">수령 </span>
          {b.receiveType === "DELIVERY" ? "배송" : "현장 수령"}
        </p>
        {b.delivery && (
          <p className="text-muted">
            {b.delivery.recipientName} · {b.delivery.recipientPhone} · {b.delivery.recipientAddress}
          </p>
        )}
        {b.payment && b.payment.status === "DONE" && (
          <p>
            <span className="text-muted">결제 </span>
            {b.payment.method} {won(b.payment.amount)} · {dateTimeText(b.payment.approvedAt)}
          </p>
        )}
        <p>
          <span className="text-muted">주문자 </span>
          {b.orderer.name} · {b.orderer.email}
        </p>
      </section>

      {(b.status === "PAID" || b.status === "PARTIAL_CANCELED") && (
        <section className="card space-y-4 p-6">
          <h2 className="font-semibold">예매 취소</h2>
          {b.cancelable ? (
            <>
              <p className="text-sm text-muted">
                지금 취소하면 티켓 금액의 {b.currentFeeRate}%가 수수료로 차감됩니다. 예매 수수료는 환불됩니다.
              </p>
              <div className="flex flex-wrap gap-2">
                <button
                  type="button"
                  className="btn"
                  disabled={busy || selected.length === 0}
                  onClick={() => cancel(selected)}
                >
                  선택한 좌석 취소 ({selected.length})
                </button>
                <button type="button" className="btn" disabled={busy} onClick={() => cancel()}>
                  전체 취소
                </button>
              </div>
            </>
          ) : (
            <p className="text-sm text-muted">지금은 취소할 수 없습니다. (공연 당일이거나 이미 모두 취소됨)</p>
          )}
        </section>
      )}

      {message && <p className="text-sm text-accent-strong">{message}</p>}
    </div>
  );
}
