"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { useEffect, useState } from "react";
import { useApi } from "@/lib/api";
import { BOOKING_BADGE, BOOKING_LABEL, scheduleText, won } from "@/lib/format";
import type { BookingStatus, BookingSummary, Page } from "@/lib/types";
import { Empty, ErrorBox, Loading, PageTitle, Pagination, Poster } from "@/components/ui";

const FILTERS: { value: BookingStatus | ""; label: string }[] = [
  { value: "", label: "전체" },
  { value: "PENDING", label: "결제 대기" },
  { value: "PAID", label: "예매 완료" },
  { value: "CANCELED", label: "취소" },
];

export default function BookingsPage() {
  const router = useRouter();
  const [status, setStatus] = useState<BookingStatus | "">("");
  const [page, setPage] = useState(0);
  const { data, error, loading, reload } = useApi<Page<BookingSummary>>("/api/v1/bookings", {
    status,
    page,
    size: 10,
  });

  // 화면을 그리는 도중에 이동하면 React 가 경고한다. 로그인이 풀렸으면 그린 뒤에 이동한다.
  const unauthorized = error?.status === 401;
  useEffect(() => {
    if (unauthorized) router.push("/login");
  }, [unauthorized, router]);
  if (unauthorized) return null;

  return (
    <>
      <PageTitle title="내 예매" />
      <div className="mb-5 flex flex-wrap gap-2">
        {FILTERS.map((f) => (
          <button
            key={f.value || "ALL"}
            type="button"
            className={`btn ${status === f.value ? "btn-dark" : ""}`}
            onClick={() => {
              setStatus(f.value);
              setPage(0);
            }}
          >
            {f.label}
          </button>
        ))}
      </div>

      {loading && <Loading />}
      {error && <ErrorBox error={error} onRetry={reload} />}
      {data && data.content.length === 0 && <Empty>예매 내역이 없습니다</Empty>}
      {data && data.content.length > 0 && (
        <>
          <ul className="space-y-3">
            {data.content.map((b) => {
              // 전체 취소된 예매에는 붙이지 않는다. 환불을 기다리거나 결제할 수 없다는 것을 알려야 하는 예매만 표시한다.
              const scheduleCanceled = b.scheduleStatus === "CANCELED" && b.status !== "CANCELED";
              return (
                <li key={b.bookingId}>
                  <Link
                    // 취소된 회차의 결제 대기 예매는 결제할 수 없어 안내가 있는 상세로 보낸다.
                    href={
                      b.status === "PENDING" && !scheduleCanceled
                        ? `/bookings/${b.bookingId}/pay`
                        : `/bookings/${b.bookingId}`
                    }
                    className="card flex gap-4 p-4 transition-colors hover:border-[rgba(25,27,33,0.35)]"
                  >
                    <div className="w-16 shrink-0">
                      <Poster title={b.performanceTitle} url={b.posterUrl} className="!rounded" />
                    </div>
                    <div className="min-w-0 flex-1">
                      <div className="flex items-center gap-2">
                        <span className={BOOKING_BADGE[b.status]}>{BOOKING_LABEL[b.status]}</span>
                        {scheduleCanceled && <span className="badge badge-accent">회차 취소</span>}
                        <span className="text-xs text-muted">{b.bookingNo}</span>
                      </div>
                      <p className="mt-1 truncate font-medium">{b.performanceTitle}</p>
                      <p className="text-sm text-muted">
                        {scheduleText(b.scheduleStartAt, b.scheduleStatus)} · {b.seatCount}매
                      </p>
                    </div>
                    <p className="self-center text-sm font-medium">{won(b.totalAmount)}</p>
                  </Link>
                </li>
              );
            })}
          </ul>
          <Pagination page={data.page} totalPages={data.totalPages} onChange={setPage} />
        </>
      )}
    </>
  );
}
