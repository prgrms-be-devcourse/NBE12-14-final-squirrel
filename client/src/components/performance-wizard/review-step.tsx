"use client";

import { Check, Circle } from "lucide-react";
import { useRouter } from "next/navigation";
import { useState } from "react";
import { ApiError, api } from "@/lib/api";
import { dateTimeText, gradeLabel, gradeMapColor, won } from "@/lib/format";
import { Panel } from "@/components/console";
import type { StepKey, StepProps } from "@/components/performance-wizard/types";

type Check = { label: string; ok: boolean; detail?: string; fix: StepKey };

const MINUTE = 60_000;
// 공개 조건의 "임시" 값(api-spec SLR-04 4번). 서버 app.performance.publish-lead-time 과 같게 맞춘다.
// 테스트 환경에서만 줄이려고 환경 변수로 뺐다. 빌드할 때 박히므로 바꾸면 다시 빌드한다. 없으면 3시간이다.
const PUBLISH_LEAD_MINUTES = Number(process.env.NEXT_PUBLIC_PUBLISH_LEAD_MINUTES ?? 180);
const PUBLISH_LEAD_TEXT = PUBLISH_LEAD_MINUTES % 60 === 0 ? `${PUBLISH_LEAD_MINUTES / 60}시간` : `${PUBLISH_LEAD_MINUTES}분`;

// 공개하기 전에 서버가 확인하는 조건(api-spec SLR-04)을 미리 보여 준다. 최종 판단은 서버가 한다.
export function ReviewStep({ detail, grades, onBack, goTo }: StepProps) {
  const router = useRouter();
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState<string | null>(null);

  // 이 단계를 열 때의 시각이다. 렌더 중에 Date.now() 를 부르지 않으려고 상태로 둔다.
  const [now] = useState(() => Date.now());
  const active = detail.schedules.filter((s) => s.status !== "CANCELED");
  const starts = active.map((s) => new Date(s.startAt).getTime());
  const open = detail.saleOpenAt ? new Date(detail.saleOpenAt).getTime() : null;
  const close = detail.saleCloseAt ? new Date(detail.saleCloseAt).getTime() : null;

  const unset = grades.zones.filter((z) => z.assignmentStatus === "UNSET");
  const assigned = grades.zones.filter((z) => z.assignmentStatus === "ASSIGNED");
  const unsold = grades.zones.filter((z) => z.assignmentStatus === "INTENTIONALLY_UNSOLD");
  const gradeById = new Map(grades.grades.map((g) => [g.gradeId, g]));
  const assignedNames = new Set(assigned.map((z) => gradeById.get(z.gradeId ?? -1)?.name));
  const allMixed = assignedNames.has("ALL") && assignedNames.size > 1;

  const salesFieldsSet =
    open !== null && close !== null && detail.maxPerPerson !== null && detail.receiveType !== null && detail.bookingFee !== null;

  const checks: Check[] = [
    { label: "예매 기간·1인 최대 매수·수령 방법·수수료를 모두 정했다", ok: salesFieldsSet, fix: "basic" },
    {
      label: "예매 마감이 지금보다 늦다",
      ok: close !== null && close > now,
      fix: "basic",
    },
    {
      label: `예매 오픈까지 ${PUBLISH_LEAD_TEXT}보다 더 남았다`,
      ok: open !== null && open - now > PUBLISH_LEAD_MINUTES * MINUTE,
      detail: "오픈 직전에 공개하면 판매 좌석이 없는 틈이 생겨서 막습니다",
      fix: "basic",
    },
    { label: "회차가 하나 이상 있다", ok: active.length > 0, fix: "schedules" },
    {
      label: "모든 회차가 예매 오픈보다 늦게 시작한다",
      ok: open !== null && starts.every((s) => s > open),
      fix: "schedules",
    },
    {
      label: "예매 마감이 마지막 회차 시작보다 늦지 않다",
      ok: close !== null && starts.length > 0 && close <= Math.max(...starts),
      fix: "schedules",
    },
    {
      label: "모든 구역의 등급을 정했다",
      ok: unset.length === 0,
      detail: unset.length > 0 ? `정하지 않은 구역: ${unset.map((z) => z.name).join(", ")}` : undefined,
      fix: "zones",
    },
    { label: "판매하는 구역이 하나 이상 있다", ok: assigned.length > 0, fix: "zones" },
    {
      label: "전석 등급과 다른 등급을 함께 쓰지 않았다",
      ok: !allMixed,
      fix: "grades",
    },
  ];
  const failing = checks.filter((c) => !c.ok);

  async function publish() {
    setBusy(true);
    setMessage(null);
    try {
      await api(`/api/v1/seller/performances/${detail.performanceId}/publish`, { method: "POST" });
      router.push(`/seller/performances/${detail.performanceId}`);
    } catch (error) {
      setMessage(error instanceof ApiError ? error.message : "공개하지 못했습니다");
      setBusy(false);
    }
  }

  const seatsOf = (zones: typeof assigned) => zones.reduce((sum, z) => sum + z.seatCount, 0);

  return (
    <div className="space-y-6">
      <Panel title="공개 전 확인" sub="공개하면 등급·구역·취소 규정은 되돌릴 수 없습니다">
        <ul className="space-y-3">
          {checks.map((c) => (
            <li key={c.label} className="flex items-start gap-3 text-sm">
              {c.ok ? (
                <Check size={18} strokeWidth={1.5} className="mt-0.5 shrink-0 text-ok" aria-label="통과" />
              ) : (
                <Circle size={18} strokeWidth={1.5} className="mt-0.5 shrink-0 text-accent-strong" aria-label="확인 필요" />
              )}
              <div className="flex-1">
                <p className={c.ok ? "text-secondary" : "font-medium"}>{c.label}</p>
                {!c.ok && c.detail && <p className="mt-0.5 text-xs text-muted">{c.detail}</p>}
              </div>
              {!c.ok && (
                <button type="button" className="text-secondary underline underline-offset-4 hover:text-foreground" onClick={() => goTo(c.fix)}>
                  고치러 가기
                </button>
              )}
            </li>
          ))}
        </ul>
      </Panel>

      <div className="grid gap-6 lg:grid-cols-2">
        <Panel title="공연">
          <dl className="grid grid-cols-[96px_1fr] gap-x-4 gap-y-3 text-sm">
            <dt className="text-muted">제목</dt>
            <dd className="font-medium">{detail.title}</dd>
            <dt className="text-muted">공연장</dt>
            <dd>{detail.venue.name}</dd>
            <dt className="text-muted">예매 기간</dt>
            <dd>{detail.saleOpenAt && detail.saleCloseAt ? `${dateTimeText(detail.saleOpenAt)} ~ ${dateTimeText(detail.saleCloseAt)}` : "-"}</dd>
            <dt className="text-muted">회차</dt>
            <dd>{active.length}개</dd>
          </dl>
        </Panel>

        <Panel title="등급과 좌석">
          <ul className="divide-y divide-line text-sm">
            {[...grades.grades]
              .sort((a, b) => b.price - a.price)
              .map((g) => {
                const zones = assigned.filter((z) => z.gradeId === g.gradeId);
                return (
                  <li key={g.gradeId} className="flex items-center justify-between py-2.5">
                    <span className="flex items-center gap-2.5">
                      <i className="size-3.5 rounded-sm" style={{ backgroundColor: gradeMapColor(g.name) }} />
                      {gradeLabel(g.name)}
                    </span>
                    <span className="text-secondary">
                      {won(g.price)} · {zones.length}구역 {seatsOf(zones).toLocaleString("ko-KR")}석
                    </span>
                  </li>
                );
              })}
            {unsold.length > 0 && (
              <li className="flex items-center justify-between py-2.5 text-secondary">
                <span>판매 제외</span>
                <span>
                  {unsold.length}구역 {seatsOf(unsold).toLocaleString("ko-KR")}석
                </span>
              </li>
            )}
          </ul>
        </Panel>
      </div>

      {message && (
        <p role="alert" className="text-sm text-accent-strong">
          {message}
        </p>
      )}
      <div className="flex justify-between gap-2">
        <button type="button" className="btn" disabled={busy} onClick={onBack}>
          이전
        </button>
        <button type="button" className="btn btn-primary btn-lg" disabled={busy || failing.length > 0} onClick={publish}>
          {busy ? "공개 중" : failing.length > 0 ? `확인할 것 ${failing.length}개` : "공개하기"}
        </button>
      </div>
    </div>
  );
}
