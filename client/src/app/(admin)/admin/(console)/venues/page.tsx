"use client";

import { Plus } from "lucide-react";
import Link from "next/link";
import { useState } from "react";
import { ApiError, api, useApi } from "@/lib/api";
import { dateTimeText } from "@/lib/format";
import { PERFORMANCE_LABEL } from "@/lib/console";
import type { AdminVenue, Page, VenueDeactivated, VenueDeactivationMode, VenueImpact, VenueStatus } from "@/lib/types";
import { ConsoleTitle, Field, Modal, Table } from "@/components/console";
import { Empty, ErrorBox, Loading, Pagination } from "@/components/ui";

const STATUSES: { value: VenueStatus | ""; label: string }[] = [
  { value: "", label: "전체" },
  { value: "ACTIVE", label: "사용 중" },
  { value: "INACTIVE", label: "사용 중지" },
];

type ModalState =
  // onlyCancel: 이미 중지된 공연장의 "기존 회차 전부 취소"
  | { kind: "deactivate"; venue: AdminVenue; onlyCancel: boolean }
  | { kind: "activate"; venue: AdminVenue }
  | null;

export default function AdminVenuesPage() {
  const [status, setStatus] = useState<VenueStatus | "">("");
  const [keywordInput, setKeywordInput] = useState("");
  const [keyword, setKeyword] = useState("");
  const [page, setPage] = useState(0);
  const { data, error, loading, reload } = useApi<Page<AdminVenue>>("/api/v1/admin/venues", { status, keyword, page });

  const [modal, setModal] = useState<ModalState>(null);
  const [message, setMessage] = useState<{ text: string; error: boolean } | null>(null);
  const [busy, setBusy] = useState(false);

  async function run<T>(action: () => Promise<T>, success: (result: T) => string) {
    setBusy(true);
    setMessage(null);
    try {
      const result = await action();
      setMessage({ text: success(result), error: false });
      setModal(null);
      reload();
    } catch (e) {
      setMessage({ text: e instanceof ApiError ? e.message : "처리하지 못했습니다", error: true });
      setModal(null);
    } finally {
      setBusy(false);
    }
  }

  function open(next: ModalState) {
    setMessage(null);
    setModal(next);
  }

  return (
    <>
      <ConsoleTitle
        title="공연장"
        sub="공연장을 사용 중지하거나 다시 사용합니다"
        actions={
          <Link href="/admin/venues/new" className="btn btn-primary">
            <Plus size={16} strokeWidth={1.5} />
            공연장 등록
          </Link>
        }
      />

      {message && (
        <p
          role="status"
          className={`mb-6 rounded-xl px-4 py-3 text-sm ${message.error ? "bg-accent-soft text-accent-strong" : "bg-[var(--ok-soft)] text-ok"}`}
        >
          {message.text}
        </p>
      )}

      <form
        className="mb-5 flex flex-wrap items-center gap-2"
        onSubmit={(e) => {
          e.preventDefault();
          setPage(0);
          setKeyword(keywordInput.trim());
        }}
      >
        {STATUSES.map((s) => (
          <button
            key={s.value || "ALL"}
            type="button"
            className="chip"
            aria-pressed={status === s.value}
            onClick={() => {
              setStatus(s.value);
              setPage(0);
            }}
          >
            {s.label}
          </button>
        ))}
        <input
          className="input ml-auto w-64 rounded-full"
          placeholder="공연장 이름"
          value={keywordInput}
          onChange={(e) => setKeywordInput(e.target.value)}
          aria-label="검색어"
        />
      </form>

      {loading && <Loading />}
      {error && <ErrorBox error={error} onRetry={reload} />}
      {data && data.content.length === 0 && <Empty>해당하는 공연장이 없습니다</Empty>}
      {data && data.content.length > 0 && (
        <>
          <Table head={["공연장", "지역", "좌석", "상태", "수정일", ""]}>
            {data.content.map((v) => (
              <tr key={v.venueId}>
                <td className="px-5 py-4">
                  <p className="font-medium">{v.name}</p>
                  <p className="text-xs text-muted">{v.address}</p>
                </td>
                <td className="px-5 py-4">{v.region}</td>
                <td className="px-5 py-4">{v.totalSeats.toLocaleString("ko-KR")}</td>
                <td className="px-5 py-4">
                  <span className={v.status === "ACTIVE" ? "badge badge-ok" : "badge badge-warn"}>
                    {v.status === "ACTIVE" ? "사용 중" : "사용 중지"}
                  </span>
                </td>
                <td className="px-5 py-4 text-muted">{dateTimeText(v.updatedAt)}</td>
                <td className="px-5 py-4">
                  <div className="flex flex-wrap justify-end gap-2">
                    {v.status === "ACTIVE" ? (
                      <button type="button" className="btn" disabled={busy} onClick={() => open({ kind: "deactivate", venue: v, onlyCancel: false })}>
                        사용 중지
                      </button>
                    ) : (
                      <>
                        <button type="button" className="btn" disabled={busy} onClick={() => open({ kind: "activate", venue: v })}>
                          사용 재개
                        </button>
                        <button type="button" className="btn" disabled={busy} onClick={() => open({ kind: "deactivate", venue: v, onlyCancel: true })}>
                          기존 회차 전부 취소
                        </button>
                      </>
                    )}
                  </div>
                </td>
              </tr>
            ))}
          </Table>
          <Pagination page={data.page} totalPages={data.totalPages} onChange={setPage} />
        </>
      )}

      {modal?.kind === "deactivate" && (
        <DeactivateModal
          venue={modal.venue}
          onlyCancel={modal.onlyCancel}
          busy={busy}
          onClose={() => setModal(null)}
          onSubmit={(body) =>
            run(
              () => api<VenueDeactivated>(`/api/v1/admin/venues/${modal.venue.venueId}/deactivate`, { method: "POST", body }),
              (r) =>
                r.mode === "BLOCK_NEW"
                  ? `${r.name}의 새 일정을 막았습니다`
                  : `${r.name}을(를) 중지하고 회차 ${r.canceledScheduleCount}개, 공연 ${r.canceledPerformanceCount}개를 취소했습니다. 환불 대상 예매 ${r.refundRequestedCount}건`,
            )
          }
        />
      )}
      {modal?.kind === "activate" && (
        <Modal title="공연장 사용 재개" onClose={() => setModal(null)}>
          <div className="space-y-5">
            <p className="text-sm text-secondary">
              {modal.venue.name}에서 다시 공연을 등록·공개하고 회차를 추가할 수 있게 됩니다. 이미 취소한 회차와 공연은 되돌아오지 않습니다
            </p>
            <div className="flex justify-end gap-2">
              <button type="button" className="btn" onClick={() => setModal(null)}>
                닫기
              </button>
              <button
                type="button"
                className="btn btn-primary"
                disabled={busy}
                onClick={() =>
                  run(
                    () => api(`/api/v1/admin/venues/${modal.venue.venueId}/activate`, { method: "POST" }),
                    () => `${modal.venue.name}의 사용을 재개했습니다`,
                  )
                }
              >
                재개하기
              </button>
            </div>
          </div>
        </Modal>
      )}
    </>
  );
}

function DeactivateModal({
  venue,
  onlyCancel,
  busy,
  onClose,
  onSubmit,
}: {
  venue: AdminVenue;
  onlyCancel: boolean;
  busy: boolean;
  onClose: () => void;
  onSubmit: (body: { reason: string; mode: VenueDeactivationMode }) => void;
}) {
  const impact = useApi<VenueImpact>(`/api/v1/admin/venues/${venue.venueId}/impact`);
  // 되돌릴 수 없는 선택이 있어 기본값을 두지 않는다
  const [mode, setMode] = useState<VenueDeactivationMode | null>(onlyCancel ? "CANCEL_ALL" : null);
  const [reason, setReason] = useState("");
  const [confirmName, setConfirmName] = useState("");
  const cancelAll = mode === "CANCEL_ALL";
  const data = impact.data;
  const ready = data && mode && reason.trim() && (!cancelAll || confirmName.trim() === venue.name);

  return (
    <Modal title={onlyCancel ? "기존 회차 전부 취소" : "공연장 사용 중지"} onClose={onClose}>
      <form
        className="max-h-[70vh] space-y-5 overflow-y-auto"
        onSubmit={(e) => {
          e.preventDefault();
          if (ready) onSubmit({ reason: reason.trim(), mode });
        }}
      >
        {impact.loading && <Loading />}
        {impact.error && <ErrorBox error={impact.error} onRetry={impact.reload} />}
        {data && (
          <div className="space-y-3 text-sm">
            <p>
              전부 취소하면 회차 <b>{data.upcomingScheduleCount}</b>개가 취소되고 예매 <b>{data.refundTargetBookingCount}</b>건이
              환불 대상입니다. 결제 대기 {data.pendingBookingCount}건, 작성 중 공연 {data.draftPerformanceCount}개
            </p>
            {data.performances.length > 0 && (
              <ul className="max-h-48 divide-y divide-line overflow-y-auto">
                {data.performances.map((p) => (
                  <li key={p.performanceId} className="py-2">
                    <p className="font-medium">
                      {p.title} <span className="text-xs text-muted">{PERFORMANCE_LABEL[p.status]}</span>
                    </p>
                    <p className="text-xs text-secondary">
                      <Link href={`/admin/businesses/${p.businessId}`} className="underline underline-offset-4">
                        {p.businessName}
                      </Link>
                      {p.status === "PUBLISHED" && ` · 회차 ${p.upcomingScheduleCount}개 · 환불 대상 ${p.refundTargetBookingCount}건`}
                    </p>
                  </li>
                ))}
              </ul>
            )}
          </div>
        )}

        {!onlyCancel && (
          <fieldset className="space-y-2 text-sm">
            <legend className="label">중지 방식</legend>
            <label className="flex items-start gap-2">
              <input type="radio" name="mode" className="mt-1" checked={mode === "BLOCK_NEW"} onChange={() => setMode("BLOCK_NEW")} />
              <span>새 일정만 막기. 이미 만든 회차는 그대로 팝니다</span>
            </label>
            <label className="flex items-start gap-2">
              <input type="radio" name="mode" className="mt-1" checked={cancelAll} onChange={() => setMode("CANCEL_ALL")} />
              <span>기존 회차도 전부 취소</span>
            </label>
          </fieldset>
        )}

        {cancelAll && data && (
          <p role="alert" className="rounded-xl bg-accent-soft px-4 py-3 text-sm text-accent-strong">
            회차 {data.upcomingScheduleCount}개를 취소하고 예매 {data.refundTargetBookingCount}건을 수수료 없이 전액 환불합니다.
            되돌릴 수 없습니다. 환불은 차례로 처리되어 몇 시간이 걸릴 수 있고, 사유는 판매자와 예매자에게 보입니다
          </p>
        )}

        <Field label="중지 사유">
          <textarea className="input h-24 py-3" value={reason} maxLength={400} onChange={(e) => setReason(e.target.value)} required />
        </Field>
        {cancelAll && (
          <Field label={`확인을 위해 공연장 이름을 입력해 주세요: ${venue.name}`}>
            <input className="input" value={confirmName} onChange={(e) => setConfirmName(e.target.value)} />
          </Field>
        )}
        <div className="flex justify-end gap-2">
          <button type="button" className="btn" onClick={onClose}>
            닫기
          </button>
          <button type="submit" className="btn btn-primary" disabled={busy || !ready}>
            {cancelAll ? "전부 취소하기" : "중지하기"}
          </button>
        </div>
      </form>
    </Modal>
  );
}
