"use client";

import { useState } from "react";
import { ApiError, api, useApi } from "@/lib/api";
import { dateTimeText, won } from "@/lib/format";
import type { Page, RefundItem, RefundRetryResult, RefundStatus } from "@/lib/types";
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
  { value: "PROCESSING", label: "처리 중" },
  { value: "DONE", label: "완료" },
];

// 토스 멱등키는 처음 요청한 날부터 15일만 유효하다. 그 뒤에는 같은 키도 새 환불로 처리돼 이중 환불이 날 수 있어서 서버가 거절한다. (ADM-06)
const RETRY_LIMIT_DAYS = 15;

// 처음 요청한 지 15일이 지난 환불은 버튼 대신 안내를 보인다.
// 현재 시각은 이 컴포넌트가 처음 그려질 때(데이터를 받은 뒤) 한 번만 읽는다. 화면 최상위에서 읽으면 사전 렌더 검증에서 이 화면이 제외된다.
function RetryAction({ refund, busy, onRetry }: { refund: RefundItem; busy: boolean; onRetry: () => void }) {
  const [now] = useState(() => Date.now());
  if (now - new Date(refund.requestedAt).getTime() > RETRY_LIMIT_DAYS * 24 * 3_600_000) {
    return (
      <span className="block max-w-44 text-xs text-muted">
        요청한 지 {RETRY_LIMIT_DAYS}일이 지났습니다. 토스에서 환불 여부를 확인해 주세요
      </span>
    );
  }
  return (
    <button type="button" className="btn" disabled={busy} onClick={onRetry}>
      다시 처리
    </button>
  );
}

export default function RefundsPage() {
  const [status, setStatus] = useState<RefundStatus>("FAILED");
  const [exhaustedOnly, setExhaustedOnly] = useState(true);
  const [page, setPage] = useState(0);
  // 서버는 exhaustedOnly 를 상태와 상관없이 재시도 횟수로 거른다. 실패 탭이 아닌데 켜 두면 횟수가 0인 완료·요청됨이 모두 사라진다.
  const { data, error, loading, reload } = useApi<Page<RefundItem>>("/api/v1/admin/refunds", {
    status,
    exhaustedOnly: String(status === "FAILED" && exhaustedOnly),
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
      const result = await api<RefundRetryResult>(`/api/v1/admin/refunds/${refund.refundId}/retry`, { method: "POST" });
      // 토스가 또 실패해도 서버는 200 으로 답한다. status 가 FAILED 면 환불은 아직 안 된 것이다.
      setMessage(
        result.status === "DONE"
          ? { text: `${refund.bookingNo} 환불을 처리했습니다`, error: false }
          : {
              text: `${refund.bookingNo} 환불이 다시 실패했습니다 (재시도 ${result.retryCount}회). ${result.failReason ?? ""}`.trim(),
              error: true,
            },
      );
      reload();
    } catch (e) {
      setMessage({ text: e instanceof ApiError ? e.message : "처리하지 못했습니다", error: true });
    } finally {
      setBusyId(null);
    }
  }

  return (
    <>
      <ConsoleTitle title="환불 처리" sub="자동 재시도가 끝나도 실패한 환불을 직접 다시 처리합니다. 오래 기다린 요청부터 보입니다" />

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
            자동 재시도를 다 쓴 것만
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
          <Table head={["예매번호", "금액", "사유", "재시도", "실패 원인", "요청 시각", "마지막 시도", ""]}>
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
                <td className="px-5 py-4 text-muted">{r.lastAttemptedAt ? dateTimeText(r.lastAttemptedAt) : "-"}</td>
                <td className="px-5 py-4 text-right">
                  {r.status === "FAILED" && <RetryAction refund={r} busy={busyId === r.refundId} onRetry={() => retry(r)} />}
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
