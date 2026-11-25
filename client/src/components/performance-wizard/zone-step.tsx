"use client";

import { useState } from "react";
import { ApiError, api } from "@/lib/api";
import { Panel } from "@/components/console";
import { ZoneGradeMap } from "@/components/zone-grade-map";
import type { StepProps } from "@/components/performance-wizard/types";

// 구역마다 등급(또는 판매 제외)을 정한다. 지도 화면은 공연 상세의 구역 설정과 같은 부품을 쓴다. (SLR-11)
export function ZoneStep({ detail, grades, layout, onSaved, onNext, onBack }: StepProps) {
  const [edits, setEdits] = useState<Record<number, string>>({});
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState<string | null>(null);

  const changed = Object.keys(edits).length > 0;
  const noGrades = grades.grades.length === 0;
  // 고치는 중인 값(edits)이 있으면 그것이, 없으면 서버 값이 현재 상태다.
  const unsetNow = grades.zones.filter((z) => (z.zoneId in edits ? edits[z.zoneId] === "UNSET" : z.assignmentStatus === "UNSET")).length;

  async function save(andNext: boolean) {
    setMessage(null);
    if (changed) {
      setBusy(true);
      try {
        const assignments = Object.entries(edits).map(([zoneId, value]) => ({
          zoneId: Number(zoneId),
          assignmentStatus: value.startsWith("grade:")
            ? ("ASSIGNED" as const)
            : value === "UNSOLD"
              ? ("INTENTIONALLY_UNSOLD" as const)
              : ("UNSET" as const),
          gradeId: value.startsWith("grade:") ? Number(value.slice(6)) : null,
        }));
        await api(`/api/v1/seller/performances/${detail.performanceId}/zone-grades`, {
          method: "PATCH",
          body: { assignments },
        });
        setEdits({});
        onSaved();
      } catch (error) {
        setMessage(error instanceof ApiError ? error.message : "저장하지 못했습니다");
        return;
      } finally {
        setBusy(false);
      }
    }
    if (andNext) onNext();
  }

  return (
    <Panel
      title="구역별 등급"
      sub="지도에서 구역을 여러 개 골라 등급을 한 번에 지정하세요. 판매하지 않을 구역은 판매 제외로 두면 됩니다"
    >
      {noGrades ? (
        <p className="text-sm text-muted">등급이 없습니다. 이전 단계에서 등급을 먼저 추가해 주세요</p>
      ) : (
        <ZoneGradeMap
          layout={layout}
          zones={grades.zones}
          grades={grades.grades}
          edits={edits}
          onEdits={setEdits}
          disabled={grades.locked}
        />
      )}
      {!noGrades && unsetNow > 0 && (
        <p className="mt-4 text-sm text-secondary">
          아직 정하지 않은 구역이 <span className="font-medium text-accent-strong">{unsetNow}개</span> 있습니다. 모두 정해야 공개할 수 있습니다
        </p>
      )}
      {message && (
        <p role="alert" className="mt-4 text-sm text-accent-strong">
          {message}
        </p>
      )}
      <div className="mt-6 flex justify-between gap-2">
        <button type="button" className="btn" disabled={busy} onClick={onBack}>
          이전
        </button>
        <div className="flex gap-2">
          {changed && (
            <button type="button" className="btn" disabled={busy} onClick={() => save(false)}>
              저장
            </button>
          )}
          <button type="button" className="btn btn-primary" disabled={busy} onClick={() => save(true)}>
            {busy ? "저장 중" : changed ? "저장하고 다음" : "다음"}
          </button>
        </div>
      </div>
    </Panel>
  );
}
