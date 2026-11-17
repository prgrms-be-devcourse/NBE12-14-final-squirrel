"use client";

import { useParams } from "next/navigation";
import { useState } from "react";
import { useApi } from "@/lib/api";
import { BOOKING_BADGE, BOOKING_LABEL, dateTimeText, won } from "@/lib/format";
import type { BookingStatus, Page, ScheduleBooking } from "@/lib/types";
import { ConsoleTitle, Table } from "@/components/console";
import { Empty, ErrorBox, Loading, Pagination } from "@/components/ui";

const FILTERS: { value: BookingStatus | ""; label: string }[] = [
  { value: "", label: "전체" },
  { value: "PAID", label: "예매 완료" },
  { value: "PARTIAL_CANCELED", label: "부분 취소" },
  { value: "CANCELED", label: "취소" },
];

export default function ScheduleBookingsPage() {
  const { id } = useParams<{ id: string }>();
  const [status, setStatus] = useState<BookingStatus | "">("");
  const [page, setPage] = useState(0);
  const { data, error, loading, reload } = useApi<Page<ScheduleBooking>>(`/api/v1/seller/schedules/${id}/bookings`, {
    status,
    page,
  });

  return (
    <>
      <ConsoleTitle title="회차 예매 내역" sub={`회차 번호 ${id}`} back={{ href: "/seller/performances", label: "공연 목록" }} />

      <div className="mb-5 flex flex-wrap gap-2">
        {FILTERS.map((f) => (
          <button
            key={f.value || "ALL"}
            type="button"
            className="chip"
            aria-pressed={status === f.value}
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
          <Table head={["예매번호", "상태", "주문자", "연락처", "좌석", "금액", "수령", "예매 시각"]}>
            {data.content.map((b) => (
              <tr key={b.bookingId}>
                <td className="px-5 py-3 font-medium">{b.bookingNo}</td>
                <td className="px-5 py-3">
                  <span className={BOOKING_BADGE[b.status]}>{BOOKING_LABEL[b.status]}</span>
                </td>
                <td className="px-5 py-3">{b.ordererName}</td>
                <td className="px-5 py-3 text-muted">{b.ordererPhone}</td>
                <td className="px-5 py-3">{b.seatCount}매</td>
                <td className="px-5 py-3">{won(b.totalAmount)}</td>
                <td className="px-5 py-3">{b.receiveType === "DELIVERY" ? "배송" : "현장"}</td>
                <td className="px-5 py-3 text-muted">{dateTimeText(b.createdAt)}</td>
              </tr>
            ))}
          </Table>
          <Pagination page={data.page} totalPages={data.totalPages} onChange={setPage} />
        </>
      )}
    </>
  );
}
