"use client";

import { Pencil, Plus } from "lucide-react";
import Link from "next/link";
import { useParams } from "next/navigation";
import { useState } from "react";
import { ApiError, api, useApi } from "@/lib/api";
import { GENRE_LABEL, GRADE_CODES, dateTimeText, gradeColor, gradeLabel, won } from "@/lib/format";
import {
  PERFORMANCE_BADGE,
  PERFORMANCE_LABEL,
  RECEIVE_LABEL,
  SCHEDULE_LABEL,
  toServerDateTime,
} from "@/lib/console";
import type {
  Grade,
  GradesResponse,
  SalesSchedule,
  SellerPerformanceDetail,
  ZoneAssignment,
} from "@/lib/types";
import { ConsoleTitle, Field, Modal, Panel } from "@/components/console";
import { ErrorBox, Loading } from "@/components/ui";

type ModalState =
  | { kind: "schedule" }
  | { kind: "grade"; grade?: Grade }
  | { kind: "cancel-performance" }
  | { kind: "cancel-schedule"; scheduleId: number }
  | null;

// 구역 설정 select 의 값: "grade:20" / "UNSOLD" / "UNSET"
const zoneValue = (z: ZoneAssignment) =>
  z.assignmentStatus === "ASSIGNED" ? `grade:${z.gradeId}` : z.assignmentStatus === "INTENTIONALLY_UNSOLD" ? "UNSOLD" : "UNSET";

export default function SellerPerformanceDetailPage() {
  const { id } = useParams<{ id: string }>();
  const base = `/api/v1/seller/performances/${id}`;
  const detail = useApi<SellerPerformanceDetail>(base);
  const grades = useApi<GradesResponse>(`${base}/grades`);
  const sales = useApi<{ schedules: SalesSchedule[] }>(`${base}/sales`);

  const [modal, setModal] = useState<ModalState>(null);
  const [message, setMessage] = useState<{ text: string; error: boolean } | null>(null);
  const [zoneEdits, setZoneEdits] = useState<Record<number, string>>({});
  const [busy, setBusy] = useState(false);

  function reloadAll() {
    detail.reload();
    grades.reload();
    sales.reload();
  }

  // 모든 변경 요청의 공통 처리: 성공하면 안내하고 새로 읽고, 실패하면 서버 메시지를 보여 준다
  async function run(action: () => Promise<unknown>, success: string) {
    setBusy(true);
    setMessage(null);
    try {
      await action();
      setMessage({ text: success, error: false });
      setModal(null);
      setZoneEdits({});
      reloadAll();
    } catch (error) {
      setMessage({ text: error instanceof ApiError ? error.message : "처리하지 못했습니다", error: true });
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
  const gradeById = new Map((grades.data?.grades ?? []).map((g) => [g.gradeId, g]));
  const zoneChanged = Object.keys(zoneEdits).length > 0;

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
        sub="판매 좌석을 만들어야 예매가 시작됩니다"
        actions={
          !canceled && (
            <button type="button" className="btn" onClick={() => setModal({ kind: "schedule" })}>
              <Plus size={16} strokeWidth={1.5} />
              회차 추가
            </button>
          )
        }
      >
        {p.schedules.length === 0 ? (
          <p className="text-sm text-muted">등록된 회차가 없습니다</p>
        ) : (
          <ul className="divide-y divide-line">
            {p.schedules.map((s) => (
              <li key={s.scheduleId} className="flex flex-wrap items-center justify-between gap-3 py-4 text-sm">
                <div>
                  <p className="font-medium">{dateTimeText(s.startAt)}</p>
                  <p className="mt-1 flex items-center gap-2 text-xs text-muted">
                    <span className={s.status === "CANCELED" ? "badge" : "badge badge-ok"}>{SCHEDULE_LABEL[s.status]}</span>
                    {s.seatsGeneratedAt ? `좌석 생성 ${dateTimeText(s.seatsGeneratedAt)}` : "좌석 미생성"}
                  </p>
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

        <Panel
          title="구역 설정"
          sub="모든 구역의 등급이나 판매 제외를 정해야 공개할 수 있습니다"
          actions={
            zoneChanged && (
              <button type="button" className="btn btn-primary" disabled={busy} onClick={saveZones}>
                변경 저장
              </button>
            )
          }
        >
          <ul className="divide-y divide-line text-sm">
            {grades.data?.zones.map((z) => {
              const value = zoneEdits[z.zoneId] ?? zoneValue(z);
              const color = value.startsWith("grade:") ? gradeColor(gradeById.get(Number(value.slice(6)))?.name) : undefined;
              return (
                <li key={z.zoneId} className="flex items-center justify-between gap-3 py-3">
                  <span className="flex items-center gap-3">
                    <span className="size-3 rounded-full bg-raised" style={color ? { backgroundColor: color } : undefined} />
                    <span>
                      {z.floor} {z.name}
                      <span className="ml-2 text-xs text-muted">{z.seatCount}석</span>
                    </span>
                  </span>
                  <select
                    className="input h-10 w-40"
                    value={value}
                    disabled={locked || canceled}
                    aria-label={`${z.name} 설정`}
                    onChange={(e) => {
                      const next = { ...zoneEdits, [z.zoneId]: e.target.value };
                      if (e.target.value === zoneValue(z)) delete next[z.zoneId];
                      setZoneEdits(next);
                    }}
                  >
                    <option value="UNSET">미설정</option>
                    <option value="UNSOLD">판매 제외</option>
                    {grades.data?.grades.map((g) => (
                      <option key={g.gradeId} value={`grade:${g.gradeId}`}>
                        {gradeLabel(g.name)}
                      </option>
                    ))}
                  </select>
                </li>
              );
            })}
          </ul>
        </Panel>
      </div>

      <Panel className="mt-6" title="판매 현황" sub="회차별 좌석 상태">
        {sales.loading && <Loading />}
        {sales.error && <ErrorBox error={sales.error} onRetry={sales.reload} />}
        {sales.data && sales.data.schedules.length === 0 && <p className="text-sm text-muted">회차가 없습니다</p>}
        <ul className="space-y-6">
          {sales.data?.schedules.map((s) => (
            <li key={s.scheduleId}>
              <div className="flex items-center justify-between text-sm">
                <span className="font-medium">{dateTimeText(s.startAt)}</span>
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
  busy,
  onClose,
  onSubmit,
}: {
  title: string;
  description: string;
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
        <Field label="취소 사유">
          <textarea className="input h-28 py-3" value={reason} onChange={(e) => setReason(e.target.value)} required />
        </Field>
        <div className="flex justify-end gap-2">
          <button type="button" className="btn" onClick={onClose}>
            닫기
          </button>
          <button type="submit" className="btn btn-primary" disabled={busy}>
            취소하기
          </button>
        </div>
      </form>
    </Modal>
  );
}
