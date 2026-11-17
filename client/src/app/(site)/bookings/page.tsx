"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { useState } from "react";
import { useApi } from "@/lib/api";
import { BOOKING_BADGE, BOOKING_LABEL, dateTimeText, won } from "@/lib/format";
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

  if (error?.status === 401) {
    router.push("/login");
    return null;
  }

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
            {data.content.map((b) => (
              <li key={b.bookingId}>
                <Link
                  href={b.status === "PENDING" ? `/bookings/${b.bookingId}/pay` : `/bookings/${b.bookingId}`}
                  className="card flex gap-4 p-4 transition-colors hover:border-[rgba(25,27,33,0.35)]"
                >
                  <div className="w-16 shrink-0">
                    <Poster title={b.performanceTitle} url={b.posterUrl} className="!rounded" />
                  </div>
                  <div className="min-w-0 flex-1">
                    <div className="flex items-center gap-2">
                      <span className={BOOKING_BADGE[b.status]}>{BOOKING_LABEL[b.status]}</span>
                      <span className="text-xs text-muted">{b.bookingNo}</span>
                    </div>
                    <p className="mt-1 truncate font-medium">{b.performanceTitle}</p>
                    <p className="text-sm text-muted">
                      {dateTimeText(b.scheduleStartAt)} · {b.seatCount}매
                    </p>
                  </div>
                  <p className="self-center text-sm font-medium">{won(b.totalAmount)}</p>
                </Link>
              </li>
            ))}
          </ul>
          <Pagination page={data.page} totalPages={data.totalPages} onChange={setPage} />
        </>
      )}
    </>
  );
}
