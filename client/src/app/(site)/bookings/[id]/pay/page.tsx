"use client";

import Link from "next/link";
import { useParams, useRouter } from "next/navigation";
import { ANONYMOUS, loadTossPayments, type TossPaymentsWidgets } from "@tosspayments/tosspayments-sdk";
import { useEffect, useRef, useState } from "react";
import { ApiError, api, useApi, useMockSeen } from "@/lib/api";
import { dateTimeText, won } from "@/lib/format";
import type { BookingDetail, PaymentConfirmed, PaymentReady, PerformanceDetail } from "@/lib/types";
import { Ticket } from "@/components/ticket";
import { ErrorBox, Loading } from "@/components/ui";

// 결제창을 여는 클라이언트 키다. 브라우저에 내려가도 되는 키라 NEXT_PUBLIC_ 을 쓴다. (시크릿 키는 Spring 만 읽는다)
const TOSS_CLIENT_KEY = process.env.NEXT_PUBLIC_TOSS_CLIENT_KEY;

function isTossError(error: unknown): error is { code: string; message: string } {
  return (
    typeof error === "object" &&
    error !== null &&
    typeof (error as { code?: unknown }).code === "string" &&
    typeof (error as { message?: unknown }).message === "string"
  );
}

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

  // Spring 이 아니라 목 데이터가 답한 상태에서는 토스 승인이 의미가 없어서 결제창을 열지 않는다.
  const mockSeen = useMockSeen();
  const useToss = Boolean(TOSS_CLIENT_KEY) && !mockSeen;
  const widgetsRef = useRef<TossPaymentsWidgets | null>(null);
  const [widgetState, setWidgetState] = useState<"loading" | "ready" | "error">("loading");

  const pending = booking.data?.status === "PENDING";
  const totalAmount = booking.data?.totalAmount;

  useEffect(() => {
    if (!useToss || !pending || totalAmount === undefined || !TOSS_CLIENT_KEY) return;
    let cancelled = false;
    const destroyers: (() => Promise<void>)[] = [];

    (async () => {
      try {
        const toss = await loadTossPayments(TOSS_CLIENT_KEY);
        // 회원 번호를 토스에 보내지 않으려고 비회원 키를 쓴다. 결제 소유자는 서버가 주문 번호로 확인한다.
        const widgets = toss.widgets({ customerKey: ANONYMOUS });
        await widgets.setAmount({ currency: "KRW", value: totalAmount });
        const [methods, agreement] = await Promise.all([
          widgets.renderPaymentMethods({ selector: "#toss-methods" }),
          widgets.renderAgreement({ selector: "#toss-agreement" }),
        ]);
        destroyers.push(methods.destroy, agreement.destroy);
        if (cancelled) {
          destroyers.forEach((destroy) => void destroy());
          return;
        }
        widgetsRef.current = widgets;
        setWidgetState("ready");
      } catch {
        if (!cancelled) setWidgetState("error");
      }
    })();

    return () => {
      cancelled = true;
      widgetsRef.current = null;
      destroyers.forEach((destroy) => void destroy());
    };
  }, [useToss, pending, totalAmount]);

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
      const widgets = widgetsRef.current;
      if (widgets) {
        // 금액은 서버가 계산한 값이 기준이다. 화면에 렌더한 금액과 다르면 서버 값으로 다시 맞춘다.
        if (ready.amount !== b.totalAmount) await widgets.setAmount({ currency: "KRW", value: ready.amount });
        // 성공하면 successUrl 로 이동하고 거기서 승인한다. 승인 API 는 서버가 토스를 부른다.
        // 이동하면 이 줄 아래는 실행되지 않고, 구매자가 창을 닫으면 아래 catch 로 온다.
        await widgets.requestPayment({
          orderId: ready.orderId,
          orderName: ready.orderName,
          successUrl: `${window.location.origin}/bookings/${b.bookingId}/pay/success`,
          failUrl: `${window.location.origin}/bookings/${b.bookingId}/pay/fail`,
        });
        return;
      }
      // 키가 없거나 목 데이터일 때만 쓰는 임시 승인이다. 토스를 거치지 않는다.
      await api<PaymentConfirmed>("/api/v1/payments/confirm", {
        method: "POST",
        body: { paymentKey: `test_${Date.now()}`, orderId: ready.orderId, amount: ready.amount },
      });
      router.push(`/bookings/${b.bookingId}`);
    } catch (error) {
      if (error instanceof ApiError) setMessage(error.message);
      else if (isTossError(error)) {
        // 구매자가 결제창을 닫은 경우도 여기로 온다. 실패가 아니라 취소라서 안내만 한다.
        setMessage(error.code.includes("CANCEL") ? "결제를 취소했습니다" : error.message);
      } else setMessage("결제에 실패했습니다");
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

          {useToss && (
            <section className="card overflow-hidden p-2">
              <div id="toss-methods" />
              <div id="toss-agreement" />
              {widgetState === "loading" && <p className="p-4 text-center text-sm text-muted">결제 수단을 불러오는 중</p>}
              {widgetState === "error" && (
                <p className="p-4 text-center text-sm text-accent-strong">결제창을 불러오지 못했습니다. 새로고침해 주세요</p>
              )}
            </section>
          )}

          {message && <p role="alert" className="text-sm text-accent-strong">{message}</p>}
          <button
            type="button"
            className="btn btn-primary btn-lg w-full"
            disabled={busy || (useToss && widgetState !== "ready")}
            onClick={pay}
          >
            {busy ? "결제 처리 중" : `${won(b.totalAmount)} 결제하기`}
          </button>
          {!useToss && (
            <p className="text-center text-xs text-muted">
              {TOSS_CLIENT_KEY ? "연결된 서버가 없어 목 데이터로 결제를 승인합니다" : "토스 클라이언트 키가 없어 테스트 결제로 승인합니다"}
            </p>
          )}
        </>
      )}
    </div>
  );
}
