"use client";

import { useState } from "react";
import { ApiError, api, useApi } from "@/lib/api";
import { dateTimeText, won } from "@/lib/format";
import type { Page, RefundItem, RefundStatus } from "@/lib/types";
import { ConsoleTitle, Table } from "@/components/console";
import { Empty, ErrorBox, Loading, Pagination } from "@/components/ui";

const REASON_LABEL: Record<RefundItem["reasonType"], string> = {
  USER_CANCEL: "예매자 취소",
  PERFORMANCE_CANCEL: "공연 취소",
  POSTPONE_CANCEL: "연기 취소",
  SYSTEM: "시스템",
  ADMIN: "관리자",
};

const STATUS_FILTERS: { value: RefundStatus; label: string }[] = [
  { value: "FAILED", label: "실패" },
  { value: "REQUESTED", label: "요청됨" },
  { value: "DONE", label: "완료" },
];

export default function RefundsPage() {
  const [status, setStatus] = useState<RefundStatus>("FAILED");
  const [exhaustedOnly, setExhaustedOnly] = useState(true);
  const [page, setPage] = useState(0);
  const { data, error, loading, reload } = useApi<Page<RefundItem>>("/api/v1/admin/refunds", {
    status,
    exhaustedOnly: String(exhaustedOnly),
    page,
  });
  const [busyId, setBusyId] = useState<number | null>(null);
  const [message, setMessage] = useState<{ text: string; error: boolean } | null>(null);

  async function retry(refund: RefundItem) {
    if (!window.confirm(`${refund.bookingNo} 환불 ${won(refund.amount)}을 다시 처리합니다. 같은 요청 번호로 보내서 이중 환불은 일어나지 않습니다`)) {
      return;
    }
    setBusyId(refund.refundId);
    setMessage(null);
    try {
      await api(`/api/v1/admin/refunds/${refund.refundId}/retry`, { method: "POST" });
      setMessage({ text: `${refund.bookingNo} 환불을 처리했습니다`, error: false });
      reload();
    } catch (e) {
      setMessage({ text: e instanceof ApiError ? e.message : "처리하지 못했습니다", error: true });
    } finally {
      setBusyId(null);
    }
  }

  return (
    <>
      <ConsoleTitle title="환불 처리" sub="자동 재시도가 끝나도 실패한 환불을 직접 다시 처리합니다" />

      <div className="mb-5 flex flex-wrap items-center gap-2">
        {STATUS_FILTERS.map((f) => (
          <button
            key={f.value}
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
        {status === "FAILED" && (
          <label className="ml-auto flex items-center gap-2 text-sm text-secondary">
            <input
              type="checkbox"
              className="size-5"
              checked={exhaustedOnly}
              onChange={(e) => {
                setExhaustedOnly(e.target.checked);
                setPage(0);
              }}
            />
            재시도 3회 이상만
          </label>
        )}
      </div>

      {message && (
        <p role="status" className={`mb-5 rounded-xl px-4 py-3 text-sm ${message.error ? "bg-accent-soft text-accent-strong" : "bg-[var(--ok-soft)] text-ok"}`}>
          {message.text}
        </p>
      )}

      {loading && <Loading />}
      {error && <ErrorBox error={error} onRetry={reload} />}
      {data && data.content.length === 0 && <Empty>해당하는 환불이 없습니다</Empty>}
      {data && data.content.length > 0 && (
        <>
          <Table head={["예매번호", "금액", "사유", "재시도", "실패 원인", "요청 시각", ""]}>
            {data.content.map((r) => (
              <tr key={r.refundId}>
                <td className="px-5 py-4 font-medium">{r.bookingNo}</td>
                <td className="px-5 py-4">{won(r.amount)}</td>
                <td className="px-5 py-4">
                  {REASON_LABEL[r.reasonType]}
                  {r.reasonDetail && <span className="block text-xs text-muted">{r.reasonDetail}</span>}
                </td>
                <td className="px-5 py-4">{r.retryCount}회</td>
                <td className="px-5 py-4 text-secondary">{r.failReason ?? "-"}</td>
                <td className="px-5 py-4 text-muted">{dateTimeText(r.requestedAt)}</td>
                <td className="px-5 py-4 text-right">
                  {r.status === "FAILED" && (
                    <button type="button" className="btn" disabled={busyId === r.refundId} onClick={() => retry(r)}>
                      다시 처리
                    </button>
                  )}
                </td>
              </tr>
            ))}
          </Table>
          <Pagination page={data.page} totalPages={data.totalPages} onChange={setPage} />
        </>
      )}
    </>
  );
}
