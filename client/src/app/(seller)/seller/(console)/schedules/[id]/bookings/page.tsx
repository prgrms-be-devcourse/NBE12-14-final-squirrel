"use client";


import { useState } from "react";
import { useRouteParams } from "@/lib/route-params";
import { useApi } from "@/lib/api";
import { carrierName } from "@/lib/delivery";
import { BOOKING_BADGE, BOOKING_LABEL, dateTimeText, gradeLabel, won } from "@/lib/format";
import type { BookingStatus, Page, ScheduleBooking, TicketGroup } from "@/lib/types";
import { ConsoleTitle, Table } from "@/components/console";
import { Empty, ErrorBox, Loading, Pagination } from "@/components/ui";

// 상태를 비우면 남은 표가 있는 예매(예매 완료·부분 취소)만 나온다. 나머지는 상태를 골라서 본다. (api-spec SLR-18)
const FILTERS: { value: BookingStatus | ""; label: string }[] = [
  { value: "", label: "남은 표 있음" },
  { value: "PAID", label: "예매 완료" },
  { value: "PARTIAL_CANCELED", label: "부분 취소" },
  { value: "CANCELED", label: "전체 취소" },
  { value: "PENDING", label: "결제 대기" },
  { value: "EXPIRED", label: "기한 만료" },
];

const RECEIVE_FILTERS: { value: "" | "ONSITE" | "DELIVERY"; label: string }[] = [
  { value: "", label: "수령 전체" },
  { value: "ONSITE", label: "현장 수령" },
  { value: "DELIVERY", label: "배송" },
];

const groupText = (groups: TicketGroup[]) =>
  groups.map((g) => `${gradeLabel(g.gradeName)} ${g.count}매`).join(", ");

export default function ScheduleBookingsPage() {
  const { id } = useRouteParams<{ id: string }>("/seller/schedules/[id]/bookings");
  const [status, setStatus] = useState<BookingStatus | "">("");
  const [receiveType, setReceiveType] = useState<"" | "ONSITE" | "DELIVERY">("");
  const [numberInput, setNumberInput] = useState("");
  const [bookingNo, setBookingNo] = useState("");
  const [page, setPage] = useState(0);
  const { data, error, loading, reload } = useApi<Page<ScheduleBooking>>(`/api/v1/seller/schedules/${id}/bookings`, {
    status,
    receiveType,
    bookingNo,
    page,
  });

  return (
    <>
      <ConsoleTitle title="회차 예매 내역" sub={`회차 번호 ${id}`} back={{ href: "/seller/performances", label: "공연 목록" }} />

      <form
        className="mb-5 flex flex-wrap items-center gap-2"
        onSubmit={(e) => {
          e.preventDefault();
          setPage(0);
          setBookingNo(numberInput.trim());
        }}
      >
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
        <select
          className="input h-10 w-36"
          value={receiveType}
          aria-label="수령 방법"
          onChange={(e) => {
            setReceiveType(e.target.value as typeof receiveType);
            setPage(0);
          }}
        >
          {RECEIVE_FILTERS.map((f) => (
            <option key={f.value || "ALL"} value={f.value}>
              {f.label}
            </option>
          ))}
        </select>
        {/* 예매번호는 일부만 넣으면 찾지 못하고 전체가 일치해야 한다 */}
        <input
          className="input ml-auto w-64 rounded-full"
          placeholder="예매번호 (전체 입력)"
          value={numberInput}
          onChange={(e) => setNumberInput(e.target.value)}
          aria-label="예매번호"
        />
      </form>

      {loading && <Loading />}
      {error && <ErrorBox error={error} onRetry={reload} />}
      {data && data.content.length === 0 && <Empty>예매 내역이 없습니다</Empty>}
      {data && data.content.length > 0 && (
        <>
          <Table head={["예매번호", "상태", "주문자", "표", "표 금액", "수령", "예매 시각"]}>
            {data.content.map((b) => (
              <tr key={b.bookingId}>
                <td className="px-5 py-3 font-medium">{b.bookingNo}</td>
                <td className="px-5 py-3">
                  <span className={BOOKING_BADGE[b.status]}>{BOOKING_LABEL[b.status]}</span>
                </td>
                <td className="px-5 py-3">
                  {b.ordererName}
                  <span className="block text-xs text-muted">{b.ordererPhone}</span>
                </td>
                <td className="px-5 py-3">
                  {b.tickets.length > 0 ? groupText(b.tickets) : "-"}
                  {b.canceledSeatCount > 0 && (
                    <span className="block text-xs text-muted">취소 {groupText(b.canceledTickets)}</span>
                  )}
                </td>
                <td className="px-5 py-3">{won(b.ticketAmount)}</td>
                <td className="px-5 py-3">
                  {b.receiveType === "DELIVERY" ? "배송" : "현장"}
                  {b.delivery && (
                    <span className="block text-xs text-muted">
                      {b.delivery.recipientName} · {b.delivery.recipientPhone}
                      <br />
                      {b.delivery.recipientAddress}
                      {b.delivery.carrier && (
                        <>
                          <br />
                          {carrierName(b.delivery.carrier)} {b.delivery.trackingNo}
                        </>
                      )}
                    </span>
                  )}
                </td>
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
