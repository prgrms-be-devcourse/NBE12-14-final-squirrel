"use client";

import { Pencil, Plus } from "lucide-react";
import Link from "next/link";

import { Fragment, useState } from "react";
import { useRouteParams } from "@/lib/route-params";
import { ApiError, api, useApi } from "@/lib/api";
import { GENRE_LABEL, GRADE_CODES, dateTimeText, gradeColor, gradeLabel, scheduleText, won } from "@/lib/format";
import {
  PERFORMANCE_BADGE,
  PERFORMANCE_LABEL,
  RECEIVE_LABEL,
  SCHEDULE_BADGE,
  SCHEDULE_LABEL,
  toServerDateTime,
} from "@/lib/console";
import type {
  Grade,
  GradesResponse,
  Page,
  SalesSchedule,
  ScheduleSaleChanged,
  ScheduleSkipReason,
  SellerPerformanceDetail,
  SellerSchedule,
  VenueItem,
} from "@/lib/types";
import { ConsoleTitle, Field, Modal, Panel } from "@/components/console";
import { ZoneGradeMap } from "@/components/zone-grade-map";
import { getVenueLayout } from "@/lib/venue-layouts";
import { ErrorBox, Loading } from "@/components/ui";

type ModalState =
  | { kind: "schedule" }
  | { kind: "grade"; grade?: Grade }
  | { kind: "cancel-performance" }
  | { kind: "cancel-schedule"; scheduleId: number }
  | { kind: "postpone" | "confirm-postpone"; scheduleId: number }
  // scheduleIds 가 null 이면 모두
  | { kind: "pause" | "resume"; scheduleIds: number[] | null }
  | null;

const SKIP_LABEL: Record<ScheduleSkipReason, string> = {
  CANCELED: "취소된 회차",
  POSTPONED: "연기된 회차",
  STARTED: "이미 시작함",
  ALREADY_PAUSED: "이미 중지됨",
  ALREADY_ON_SALE: "이미 판매 중",
};

// 판매 중지·재개 대상이 될 수 있는 회차: 아직 시작하지 않은 판매 중·일시 중지
const isSwitchable = (s: SellerSchedule, now: number) =>
  (s.status === "ON_SALE" || s.status === "PAUSED") && new Date(s.startAt).getTime() > now;

export default function SellerPerformanceDetailPage() {
  const { id } = useRouteParams<{ id: string }>("/seller/performances/[id]");
  const base = `/api/v1/seller/performances/${id}`;
  const detail = useApi<SellerPerformanceDetail>(base);
  const grades = useApi<GradesResponse>(`${base}/grades`);
  const sales = useApi<{ schedules: SalesSchedule[] }>(`${base}/sales`);
  // 공연 상세(SLR-06)는 공연장의 도면 이름표(layoutKey)를 주지 않아서 공연장 목록(SLR-01)에서 같은 공연장을 찾는다.
  const venues = useApi<Page<VenueItem>>("/api/v1/seller/venues", { size: 20 });

  const [modal, setModal] = useState<ModalState>(null);
  const [message, setMessage] = useState<{ text: string; error: boolean } | null>(null);
  const [zoneEdits, setZoneEdits] = useState<Record<number, string>>({});
  const [busy, setBusy] = useState(false);
  const [selected, setSelected] = useState<number[]>([]);
  // 시작한 회차를 고르지 못하게 하는 기준 시각. 실제 판단은 서버가 한다.
  const [openedAt] = useState(() => Date.now());

  function reloadAll() {
    detail.reload();
    grades.reload();
    sales.reload();
  }

  // 모든 변경 요청의 공통 처리: 성공하면 안내하고 새로 읽고, 실패하면 서버 메시지를 보여 준다
  async function run<T>(action: () => Promise<T>, success: string | ((result: T) => string)) {
    setBusy(true);
    setMessage(null);
    try {
      const result = await action();
      setMessage({ text: typeof success === "string" ? success : success(result), error: false });
      setModal(null);
      setZoneEdits({});
      setSelected([]);
      reloadAll();
    } catch (error) {
      // 원래 일시가 지난 미정 회차만 남으면 서버는 "회차가 모두 시작됨"으로 답해서, 먼저 할 일을 덧붙인다.
      const hint =
        error instanceof ApiError &&
        error.code === "PERFORMANCE010" &&
        detail.data?.schedules.some((s) => s.status === "POSTPONED")
          ? " 일정 미정 회차의 새 일정을 먼저 확정해 주세요."
          : "";
      setMessage({ text: error instanceof ApiError ? error.message + hint : "처리하지 못했습니다", error: true });
    } finally {
      setBusy(false);
    }
  }

  if (detail.loading) return <Loading />;
  if (detail.error) return <ErrorBox error={detail.error} onRetry={detail.reload} />;
  const p = detail.data;
  if (!p) return null;

  const draft = p.status === "DRAFT";
  const canceled = p.status === "CANCELED";
  const locked = grades.data?.locked ?? false;
  const zoneChanged = Object.keys(zoneEdits).length > 0;
  const layout = getVenueLayout(venues.data?.content.find((v) => v.venueId === p.venue.venueId)?.layoutKey);
  const published = p.status === "PUBLISHED";
  const switchable = published ? p.schedules.filter((s) => isSwitchable(s, openedAt)) : [];

  function changeSale(kind: "pause" | "resume", scheduleIds: number[] | null, reason?: string) {
    const target = scheduleIds === null ? { all: true } : { scheduleIds };
    run(
      () =>
        api<ScheduleSaleChanged>(`${base}/schedules/${kind}`, {
          method: "POST",
          body: reason === undefined ? target : { ...target, reason },
        }),
      (result) => {
        const done = `${result.changedScheduleIds.length}개 회차의 판매를 ${kind === "pause" ? "중지" : "재개"}했습니다`;
        const skipped = result.skipped.map((item) => {
          const startAt = p?.schedules.find((s) => s.scheduleId === item.scheduleId)?.startAt;
          return `${startAt ? dateTimeText(startAt) : item.scheduleId} ${SKIP_LABEL[item.reason]}`;
        });
        return skipped.length === 0 ? done : `${done}. 건너뜀: ${skipped.join(", ")}`;
      },
    );
  }

  // 연기·확정 창은 거절 사유(마감 범위, 시간 겹침 등)를 창 안에서 보여 주므로 열 때 이전 메시지를 지운다.
  function openPostpone(kind: "postpone" | "confirm-postpone", scheduleId: number) {
    setMessage(null);
    setModal({ kind, scheduleId });
  }

  // 공개 공연이 통째로 멈춘 동안에는 회차를 더하지 못한다. 막는 것은 서버다.
  const allPaused = published && switchable.length > 0 && switchable.every((s) => s.status === "PAUSED");

  function saveZones() {
    const assignments = Object.entries(zoneEdits).map(([zoneId, value]) => ({
      zoneId: Number(zoneId),
      assignmentStatus: value.startsWith("grade:")
        ? ("ASSIGNED" as const)
        : value === "UNSOLD"
          ? ("INTENTIONALLY_UNSOLD" as const)
          : ("UNSET" as const),
      gradeId: value.startsWith("grade:") ? Number(value.slice(6)) : null,
    }));
    run(() => api(`${base}/zone-grades`, { method: "PATCH", body: { assignments } }), "구역 설정을 저장했습니다");
  }

  return (
    <>
      <ConsoleTitle
        title={p.title}
        sub={`${GENRE_LABEL[p.genre]} · ${p.venue.name}`}
        back={{ href: "/seller/performances", label: "공연 목록" }}
        actions={
          <>
            <span className={PERFORMANCE_BADGE[p.status]}>{PERFORMANCE_LABEL[p.status]}</span>
            {draft && (
              <Link href={`/seller/performances/${id}/setup`} className="btn">
                단계별로 이어서 만들기
              </Link>
            )}
            {!canceled && (
              <Link href={`/seller/performances/${id}/edit`} className="btn">
                <Pencil size={16} strokeWidth={1.5} />
                수정
              </Link>
            )}
            {draft && (
              <button
                type="button"
                className="btn btn-primary"
                disabled={busy}
                onClick={() => run(() => api(`${base}/publish`, { method: "POST" }), "공연을 공개했습니다")}
              >
                공개하기
              </button>
            )}
            {!canceled && (
              <button type="button" className="btn" onClick={() => setModal({ kind: "cancel-performance" })}>
                공연 취소
              </button>
            )}
          </>
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

      <div className="grid gap-6 xl:grid-cols-2">
        <Panel title="기본 정보">
          <dl className="grid grid-cols-[96px_1fr] gap-x-4 gap-y-3 text-sm">
            <dt className="text-muted">관람 연령</dt>
            <dd>{p.ageLimit === 0 ? "전체 관람가" : `${p.ageLimit}세 이상`}</dd>
            <dt className="text-muted">관람 시간</dt>
            <dd>{p.runningTime}분</dd>
            <dt className="text-muted">출연진</dt>
            <dd>{p.castInfo ?? "-"}</dd>
            <dt className="text-muted">공지</dt>
            <dd>{p.notice ?? "-"}</dd>
            <dt className="text-muted">관람 안내</dt>
            <dd>{p.guide ?? "-"}</dd>
            <dt className="text-muted">등록일</dt>
            <dd>{dateTimeText(p.createdAt)}</dd>
          </dl>
        </Panel>

        <Panel title="판매 설정">
          <dl className="grid grid-cols-[96px_1fr] gap-x-4 gap-y-3 text-sm">
            <dt className="text-muted">예매 기간</dt>
            <dd>{p.saleOpenAt && p.saleCloseAt ? `${dateTimeText(p.saleOpenAt)} ~ ${dateTimeText(p.saleCloseAt)}` : <span className="text-accent-strong">미설정</span>}</dd>
            <dt className="text-muted">1인 최대</dt>
            <dd>{p.maxPerPerson != null ? `${p.maxPerPerson}매` : <span className="text-accent-strong">미설정</span>}</dd>
            <dt className="text-muted">수령 방법</dt>
            <dd>{p.receiveType ? RECEIVE_LABEL[p.receiveType] : <span className="text-accent-strong">미설정</span>}</dd>
            <dt className="text-muted">예매 수수료</dt>
            <dd>{p.bookingFee != null ? `1매 ${won(p.bookingFee)}` : <span className="text-accent-strong">미설정</span>}</dd>
            <dt className="text-muted">취소 규정</dt>
            <dd>
              <Link href={`/seller/cancel-policies/${p.cancelPolicyId}`} className="underline decoration-line underline-offset-4 hover:decoration-foreground">
                규정 보기
              </Link>
            </dd>
          </dl>
        </Panel>
      </div>

      <Panel
        className="mt-6"
        title="회차"
        sub={
          allPaused
            ? "모든 회차가 판매 중지 상태라 회차를 추가할 수 없습니다. 먼저 판매를 재개해 주세요"
            : "판매 좌석을 만들어야 예매가 시작됩니다"
        }
        actions={
          !canceled && (
            <>
              {switchable.length > 0 && (
                <>
                  {(["pause", "resume"] as const).map((kind) => {
                    const from = kind === "pause" ? "ON_SALE" : "PAUSED";
                    const verb = kind === "pause" ? "중지" : "재개";
                    return (
                      <Fragment key={kind}>
                        <button
                          type="button"
                          className="btn"
                          disabled={busy || !switchable.some((s) => s.status === from && selected.includes(s.scheduleId))}
                          onClick={() => setModal({ kind, scheduleIds: selected })}
                        >
                          선택 {verb}
                        </button>
                        <button
                          type="button"
                          className="btn"
                          disabled={busy || !switchable.some((s) => s.status === from)}
                          onClick={() => setModal({ kind, scheduleIds: null })}
                        >
                          모두 {verb}
                        </button>
                      </Fragment>
                    );
                  })}
                </>
              )}
              <button type="button" className="btn" disabled={allPaused} onClick={() => setModal({ kind: "schedule" })}>
                <Plus size={16} strokeWidth={1.5} />
                회차 추가
              </button>
            </>
          )
        }
      >
        {p.schedules.length === 0 ? (
          <p className="text-sm text-muted">등록된 회차가 없습니다</p>
        ) : (
          <ul className="divide-y divide-line">
            {p.schedules.map((s) => (
              <li key={s.scheduleId} className="flex flex-wrap items-center justify-between gap-3 py-4 text-sm">
                <div className="flex items-start gap-3">
                  {switchable.some((x) => x.scheduleId === s.scheduleId) && (
                    <input
                      type="checkbox"
                      className="mt-1 size-4"
                      aria-label={`${dateTimeText(s.startAt)} 회차 선택`}
                      checked={selected.includes(s.scheduleId)}
                      onChange={(e) =>
                        setSelected((ids) =>
                          e.target.checked ? [...ids, s.scheduleId] : ids.filter((id) => id !== s.scheduleId),
                        )
                      }
                    />
                  )}
                  <div>
                    <p className="font-medium">{scheduleText(s.startAt, s.status)}</p>
                    <p className="mt-1 flex items-center gap-2 text-xs text-muted">
                      <span className={SCHEDULE_BADGE[s.status]}>{SCHEDULE_LABEL[s.status]}</span>
                      {s.seatsGeneratedAt ? `좌석 생성 ${dateTimeText(s.seatsGeneratedAt)}` : "좌석 미생성"}
                    </p>
                    {s.status === "PAUSED" && s.pauseReason && (
                      <p className="mt-1 text-xs text-secondary">중지 사유: {s.pauseReason}</p>
                    )}
                    {s.postponement && (
                      <p className="mt-1 text-xs break-words text-secondary">
                        {s.status !== "POSTPONED" && `일정 변경(원래 ${dateTimeText(s.postponement.oldStartAt)}) · `}
                        연기 사유: {s.postponement.reason} ·{" "}
                        {s.postponement.freeCancelUntil
                          ? `무료 취소 마감 ${dateTimeText(s.postponement.freeCancelUntil)}`
                          : "일정 확정 전까지 무료 취소"}
                      </p>
                    )}
                  </div>
                </div>
                {s.status !== "CANCELED" && (
                  <div className="flex flex-wrap gap-2">
                    {!s.seatsGeneratedAt && (
                      <button
                        type="button"
                        className="btn"
                        disabled={busy}
                        onClick={() =>
                          run(() => api(`/api/v1/seller/schedules/${s.scheduleId}/seats/generate`, { method: "POST" }), "판매 좌석을 만들었습니다")
                        }
                      >
                        좌석 생성
                      </button>
                    )}
                    {switchable.some((x) => x.scheduleId === s.scheduleId) && (
                      <button type="button" className="btn" disabled={busy} onClick={() => openPostpone("postpone", s.scheduleId)}>
                        연기
                      </button>
                    )}
                    {s.status === "POSTPONED" && (
                      <button
                        type="button"
                        className="btn btn-primary"
                        disabled={busy}
                        onClick={() => openPostpone("confirm-postpone", s.scheduleId)}
                      >
                        새 일정 확정
                      </button>
                    )}
                    <Link href={`/seller/schedules/${s.scheduleId}/seats`} className="btn">
                      보류석
                    </Link>
                    <Link href={`/seller/schedules/${s.scheduleId}/bookings`} className="btn">
                      예매 내역
                    </Link>
                    <button type="button" className="btn" onClick={() => setModal({ kind: "cancel-schedule", scheduleId: s.scheduleId })}>
                      회차 취소
                    </button>
                  </div>
                )}
              </li>
            ))}
          </ul>
        )}
      </Panel>

      <div className="mt-6 grid gap-6 xl:grid-cols-2">
        <Panel
          title="등급"
          sub={locked ? "판매 좌석을 만든 뒤에는 바꿀 수 없습니다" : "등급마다 가격과 색을 정합니다"}
          actions={
            !locked && !canceled && (
              <button type="button" className="btn" onClick={() => setModal({ kind: "grade" })}>
                <Plus size={16} strokeWidth={1.5} />
                등급 추가
              </button>
            )
          }
        >
          {grades.loading && <Loading />}
          {grades.error && <ErrorBox error={grades.error} onRetry={grades.reload} />}
          {grades.data && grades.data.grades.length === 0 && <p className="text-sm text-muted">등급이 없습니다. 등급을 추가하고 구역에 지정하세요</p>}
          <ul className="divide-y divide-line text-sm">
            {grades.data?.grades.map((g) => (
              <li key={g.gradeId} className="flex items-center justify-between py-3">
                <span className="flex items-center gap-3">
                  <span className="size-4 rounded-full" style={{ backgroundColor: gradeColor(g.name) ?? undefined }} />
                  {gradeLabel(g.name)}
                </span>
                <span className="flex items-center gap-4">
                  {won(g.price)}
                  {!locked && !canceled && (
                    <button type="button" className="text-secondary hover:text-foreground" onClick={() => setModal({ kind: "grade", grade: g })}>
                      수정
                    </button>
                  )}
                </span>
              </li>
            ))}
          </ul>
        </Panel>

      </div>

      <Panel
        className="mt-6"
        title="구역 설정"
        sub="모든 구역의 등급이나 판매 제외를 정해야 공개할 수 있습니다. 지도에서 구역을 여러 개 골라 등급을 한 번에 지정하세요"
        actions={
          zoneChanged && (
            <>
              <button type="button" className="btn" disabled={busy} onClick={() => setZoneEdits({})}>
                되돌리기
              </button>
              <button type="button" className="btn btn-primary" disabled={busy} onClick={saveZones}>
                변경 저장
              </button>
            </>
          )
        }
      >
        {grades.loading && <Loading />}
        {grades.data && (
          <ZoneGradeMap
            layout={layout}
            zones={grades.data.zones}
            grades={grades.data.grades}
            edits={zoneEdits}
            onEdits={setZoneEdits}
            disabled={locked || canceled}
          />
        )}
      </Panel>

      <Panel className="mt-6" title="판매 현황" sub="회차별 좌석 상태">
        {sales.loading && <Loading />}
        {sales.error && <ErrorBox error={sales.error} onRetry={sales.reload} />}
        {sales.data && sales.data.schedules.length === 0 && <p className="text-sm text-muted">회차가 없습니다</p>}
        <ul className="space-y-6">
          {sales.data?.schedules.map((s) => (
            <li key={s.scheduleId}>
              <div className="flex items-center justify-between text-sm">
                <span className="font-medium">{scheduleText(s.startAt, s.status)}</span>
                <span className="text-muted">
                  {s.seatsGenerated ? `${s.soldSeats}/${s.totalSeats}석 판매` : "좌석 미생성"}
                </span>
              </div>
              {s.seatsGenerated ? (
                <>
                  <div className="mt-3 flex h-3 overflow-hidden rounded-full bg-raised" role="img" aria-label="좌석 상태 비율">
                    <span style={{ width: `${((s.soldSeats ?? 0) / s.totalSeats) * 100}%` }} className="bg-accent" />
                    <span style={{ width: `${((s.heldSeats ?? 0) / s.totalSeats) * 100}%` }} className="bg-[var(--warn)]" />
                    <span style={{ width: `${((s.blockedSeats ?? 0) / s.totalSeats) * 100}%` }} className="bg-[rgba(25,27,33,0.25)]" />
                  </div>
                  <p className="mt-2 flex flex-wrap gap-x-5 gap-y-1 text-xs text-secondary">
                    <span>판매 {s.soldSeats}</span>
                    <span>점유 {s.heldSeats}</span>
                    <span>남음 {s.availableSeats}</span>
                    <span>판매 제외 {s.blockedSeats}</span>
                  </p>
                </>
              ) : (
                <div className="mt-3 h-3 rounded-full bg-raised" />
              )}
            </li>
          ))}
        </ul>
      </Panel>

      {modal?.kind === "schedule" && (
        <ScheduleModal
          busy={busy}
          onClose={() => setModal(null)}
          onSubmit={(startAt) => run(() => api(`${base}/schedules`, { method: "POST", body: { startAt } }), "회차를 추가했습니다")}
        />
      )}
      {(modal?.kind === "postpone" || modal?.kind === "confirm-postpone") && (
        <PostponeModal
          confirming={modal.kind === "confirm-postpone"}
          error={message?.error ? message.text : null}
          busy={busy}
          onClose={() => setModal(null)}
          onSubmit={(body) =>
            run(
              () =>
                api(
                  `/api/v1/seller/schedules/${modal.scheduleId}/postpone${modal.kind === "confirm-postpone" ? "/confirm" : ""}`,
                  { method: "POST", body },
                ),
              modal.kind === "confirm-postpone" ? "새 일정을 확정하고 판매를 열었습니다" : "회차를 연기했습니다",
            )
          }
        />
      )}
      {modal?.kind === "pause" && (
        <ReasonModal
          title={modal.scheduleIds === null ? "모든 회차 판매 중지" : "선택한 회차 판매 중지"}
          description="중지하면 대기열 입장, 좌석 선택, 결제가 막힙니다. 이미 승인 중인 결제는 완료될 수 있습니다. 판매 중이고 시작 전인 회차만 중지합니다"
          label="중지 사유 (예매자 공연 상세에 보입니다)"
          maxLength={255}
          submitLabel="중지하기"
          busy={busy}
          onClose={() => setModal(null)}
          onSubmit={(reason) => changeSale("pause", modal.scheduleIds, reason)}
        />
      )}
      {modal?.kind === "resume" && (
        <Modal
          title={modal.scheduleIds === null ? "모든 회차 판매 재개" : "선택한 회차 판매 재개"}
          onClose={() => setModal(null)}
        >
          <div className="space-y-5">
            <p className="text-sm text-secondary">아래 회차의 판매를 다시 시작합니다. 중지 사유를 확인해 주세요</p>
            <ul className="max-h-64 divide-y divide-line overflow-y-auto text-sm">
              {switchable
                .filter((s) => s.status === "PAUSED")
                .filter((s) => modal.scheduleIds === null || modal.scheduleIds.includes(s.scheduleId))
                .map((s) => (
                  <li key={s.scheduleId} className="py-3">
                    <p className="font-medium">{dateTimeText(s.startAt)}</p>
                    <p className="mt-1 text-xs text-secondary">중지 사유: {s.pauseReason ?? "-"}</p>
                  </li>
                ))}
            </ul>
            <div className="flex justify-end gap-2">
              <button type="button" className="btn" onClick={() => setModal(null)}>
                닫기
              </button>
              <button
                type="button"
                className="btn btn-primary"
                disabled={busy}
                onClick={() => changeSale("resume", modal.scheduleIds)}
              >
                재개하기
              </button>
            </div>
          </div>
        </Modal>
      )}
      {modal?.kind === "grade" && (
        <GradeModal
          grade={modal.grade}
          taken={(grades.data?.grades ?? []).filter((g) => g.gradeId !== modal.grade?.gradeId).map((g) => g.name)}
          busy={busy}
          onClose={() => setModal(null)}
          onSubmit={(form) =>
            run(
              () =>
                modal.grade
                  ? api(`${base}/grades/${modal.grade.gradeId}`, { method: "PUT", body: form })
                  : api(`${base}/grades`, { method: "POST", body: form }),
              modal.grade ? "등급을 수정했습니다" : "등급을 추가했습니다",
            )
          }
        />
      )}
      {(modal?.kind === "cancel-performance" || modal?.kind === "cancel-schedule") && (
        <ReasonModal
          title={modal.kind === "cancel-performance" ? "공연 취소" : "회차 취소"}
          description="취소하면 판매 좌석의 예매는 수수료 없이 환불됩니다. 되돌릴 수 없습니다"
          busy={busy}
          onClose={() => setModal(null)}
          onSubmit={(reason) =>
            run(
              () =>
                modal.kind === "cancel-performance"
                  ? api(`${base}/cancel`, { method: "POST", body: { reason } })
                  : api(`/api/v1/seller/schedules/${modal.scheduleId}/cancel`, { method: "POST", body: { reason } }),
              modal.kind === "cancel-performance" ? "공연을 취소했습니다" : "회차를 취소했습니다",
            )
          }
        />
      )}
    </>
  );
}

function ScheduleModal({
  busy,
  onClose,
  onSubmit,
}: {
  busy: boolean;
  onClose: () => void;
  onSubmit: (startAt: string) => void;
}) {
  const [value, setValue] = useState("");
  return (
    <Modal title="회차 추가" onClose={onClose}>
      <form
        onSubmit={(e) => {
          e.preventDefault();
          const startAt = toServerDateTime(value);
          if (startAt) onSubmit(startAt);
        }}
        className="space-y-5"
      >
        <Field label="공연 일시" hint="같은 공연장의 다른 회차와 시간이 겹치면 추가할 수 없습니다">
          <input className="input" type="datetime-local" value={value} onChange={(e) => setValue(e.target.value)} required />
        </Field>
        <div className="flex justify-end gap-2">
          <button type="button" className="btn" onClick={onClose}>
            닫기
          </button>
          <button type="submit" className="btn btn-primary" disabled={busy}>
            추가
          </button>
        </div>
      </form>
    </Modal>
  );
}

// 연기(SLR-28)와 새 일정 확정(SLR-29). 확정은 사유가 없고 새 일시가 필수다.
function PostponeModal({
  confirming,
  error,
  busy,
  onClose,
  onSubmit,
}: {
  confirming: boolean;
  error: string | null;
  busy: boolean;
  onClose: () => void;
  onSubmit: (body: { reason?: string; newStartAt: string | null; freeCancelUntil: string | null }) => void;
}) {
  const [reason, setReason] = useState("");
  const [startAt, setStartAt] = useState("");
  const [deadline, setDeadline] = useState("");
  return (
    <Modal title={confirming ? "새 일정 확정" : "회차 연기"} onClose={onClose}>
      <form
        onSubmit={(e) => {
          e.preventDefault();
          if (!confirming && !reason.trim()) return;
          onSubmit({
            reason: confirming ? undefined : reason.trim(),
            newStartAt: toServerDateTime(startAt),
            // 새 일시를 지우면 마감 칸이 꺼지므로 남아 있던 값은 보내지 않는다
            freeCancelUntil: startAt ? toServerDateTime(deadline) : null,
          });
        }}
        className="space-y-5"
      >
        <p className="text-sm text-secondary">
          {confirming
            ? "확정하면 바로 판매가 열립니다. 중지한 상태에서 연기한 회차도 열립니다. 연기 전에 예매한 사람은 무료 취소 마감까지 수수료 없이 취소할 수 있습니다"
            : "예매는 그대로 두고, 연기 전에 예매한 사람은 수수료 없이 취소할 수 있습니다. 새 일시를 비우면 일정 미정이 되어 대기열·좌석 선택·결제가 막힙니다. 되돌릴 수 없습니다"}
        </p>
        {!confirming && (
          <Field label="연기 사유 (예매자 공연 상세에 보입니다)">
            <textarea
              className="input h-24 py-3"
              value={reason}
              maxLength={255}
              onChange={(e) => setReason(e.target.value)}
              required
            />
          </Field>
        )}
        <Field label={confirming ? "새 공연 일시" : "새 공연 일시 (선택)"} hint="같은 공연장의 다른 회차와 시간이 겹치면 안 됩니다">
          <input
            className="input"
            type="datetime-local"
            value={startAt}
            onChange={(e) => setStartAt(e.target.value)}
            required={confirming}
          />
        </Field>
        <Field
          label="무료 취소 마감 (선택)"
          hint={`비우면 14일 뒤${confirming ? "" : "(남은 이전 마감이 더 늦으면 그때)"}입니다. 7일 뒤${confirming ? "" : "와 남은 이전 마감"}보다 이를 수 없고, 새 공연 일시를 넘지 않습니다`}
        >
          <input
            className="input"
            type="datetime-local"
            value={deadline}
            disabled={!startAt}
            onChange={(e) => setDeadline(e.target.value)}
          />
        </Field>
        {error && (
          <p role="alert" className="text-sm text-accent-strong">
            {error}
          </p>
        )}
        <div className="flex justify-end gap-2">
          <button type="button" className="btn" onClick={onClose}>
            닫기
          </button>
          <button type="submit" className="btn btn-primary" disabled={busy}>
            {confirming ? "확정하고 판매 열기" : "연기하기"}
          </button>
        </div>
      </form>
    </Modal>
  );
}

function GradeModal({
  grade,
  taken,
  busy,
  onClose,
  onSubmit,
}: {
  grade?: Grade;
  // 같은 공연에는 같은 등급을 하나만 둘 수 있어서 이미 쓰는 코드는 고르지 못하게 한다.
  taken: string[];
  busy: boolean;
  onClose: () => void;
  onSubmit: (form: { name: string; price: number }) => void;
}) {
  const available = GRADE_CODES.filter((code) => !taken.includes(code));
  const [name, setName] = useState(grade?.name ?? available[0] ?? "");
  const [price, setPrice] = useState(String(grade?.price ?? ""));
  return (
    <Modal title={grade ? "등급 수정" : "등급 추가"} onClose={onClose}>
      <form
        onSubmit={(e) => {
          e.preventDefault();
          onSubmit({ name, price: Number(price) });
        }}
        className="space-y-5"
      >
        <Field label="등급" hint="화면에 보이는 이름과 색은 등급마다 정해져 있습니다">
          <select className="input" value={name} onChange={(e) => setName(e.target.value)} required>
            {available.map((code) => (
              <option key={code} value={code}>
                {gradeLabel(code)}
              </option>
            ))}
          </select>
        </Field>
        <Field label="가격 (원)" hint="0원 이상 1,000만원 이하">
          <input className="input" type="number" min={0} max={10000000} step={1000} value={price} onChange={(e) => setPrice(e.target.value)} required />
        </Field>
        <div className="flex justify-end gap-2">
          <button type="button" className="btn" onClick={onClose}>
            닫기
          </button>
          <button type="submit" className="btn btn-primary" disabled={busy}>
            저장
          </button>
        </div>
      </form>
    </Modal>
  );
}

function ReasonModal({
  title,
  description,
  label = "취소 사유",
  maxLength,
  submitLabel = "취소하기",
  busy,
  onClose,
  onSubmit,
}: {
  title: string;
  description: string;
  label?: string;
  maxLength?: number;
  submitLabel?: string;
  busy: boolean;
  onClose: () => void;
  onSubmit: (reason: string) => void;
}) {
  const [reason, setReason] = useState("");
  return (
    <Modal title={title} onClose={onClose}>
      <form
        onSubmit={(e) => {
          e.preventDefault();
          if (reason.trim()) onSubmit(reason.trim());
        }}
        className="space-y-5"
      >
        <p className="text-sm text-secondary">{description}</p>
        <Field label={label}>
          <textarea
            className="input h-28 py-3"
            value={reason}
            maxLength={maxLength}
            onChange={(e) => setReason(e.target.value)}
            required
          />
        </Field>
        <div className="flex justify-end gap-2">
          <button type="button" className="btn" onClick={onClose}>
            닫기
          </button>
          <button type="submit" className="btn btn-primary" disabled={busy}>
            {submitLabel}
          </button>
        </div>
      </form>
    </Modal>
  );
}
