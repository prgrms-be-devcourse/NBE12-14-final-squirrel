"use client";

import { useState } from "react";
import { gradeLabel, gradeMapColor, won } from "@/lib/format";
import type { Grade, ZoneAssignment } from "@/lib/types";
import type { Point, VenueLayout } from "@/lib/venue-layouts/types";

// 판매자가 구역마다 등급을 정하는 화면. 구역을 하나씩 select 로 바꾸지 않고
//   1. 지도(도면이 없는 공연장은 층별 구역 버튼)에서 구역을 여러 개 고르고
//   2. 등급 버튼을 한 번 눌러 선택한 구역 전체에 지정한다.
// 서버의 구역 설정 API(SLR-11)는 여러 구역을 한 요청으로 저장하므로, 여기서는 바뀐 값만 edits 에 모아 두고 저장은 호출한 쪽이 한다.
// 값은 "grade:번호" / "UNSOLD"(판매 제외) / "UNSET"(미설정) 문자열이다.

export const zoneValue = (z: ZoneAssignment): string =>
  z.assignmentStatus === "ASSIGNED" ? `grade:${z.gradeId}` : z.assignmentStatus === "INTENTIONALLY_UNSOLD" ? "UNSOLD" : "UNSET";

const UNSOLD_COLOR = "#5f6372";
const UNSET_COLOR = "#d9dce4";

type Props = {
  layout: VenueLayout | null;
  zones: ZoneAssignment[];
  grades: Grade[];
  // 바뀐 구역만 담은 값. 키는 구역 번호다.
  edits: Record<number, string>;
  onEdits: (next: Record<number, string>) => void;
  // 판매 좌석을 만들었거나 취소된 공연은 바꿀 수 없다. 지도는 보기만 한다.
  disabled: boolean;
};

const centroid = (points: Point[]): Point => [
  points.reduce((s, p) => s + p[0], 0) / points.length,
  points.reduce((s, p) => s + p[1], 0) / points.length,
];

export function ZoneGradeMap({ layout, zones, grades, edits, onEdits, disabled }: Props) {
  const [selected, setSelected] = useState<Set<number>>(new Set());
  const [notice, setNotice] = useState<string | null>(null);

  const gradeById = new Map(grades.map((g) => [g.gradeId, g]));
  const valueOf = (z: ZoneAssignment) => edits[z.zoneId] ?? zoneValue(z);
  const colorOf = (value: string) => {
    if (value === "UNSOLD") return UNSOLD_COLOR;
    if (value.startsWith("grade:")) return gradeMapColor(gradeById.get(Number(value.slice(6)))?.name);
    return UNSET_COLOR;
  };
  const labelOf = (value: string) => {
    if (value === "UNSOLD") return "판매 제외";
    if (value.startsWith("grade:")) return gradeLabel(gradeById.get(Number(value.slice(6)))?.name);
    return "미설정";
  };

  const zoneByName = new Map(zones.map((z) => [z.name, z]));
  const floors = [...new Set(zones.map((z) => z.floor))];
  const unsetCount = zones.filter((z) => valueOf(z) === "UNSET").length;
  const sortedGrades = [...grades].sort((a, b) => b.price - a.price);

  function toggle(zoneId: number) {
    if (disabled) return;
    setNotice(null);
    setSelected((current) => {
      const next = new Set(current);
      if (next.has(zoneId)) next.delete(zoneId);
      else next.add(zoneId);
      return next;
    });
  }

  function select(predicate: (z: ZoneAssignment) => boolean) {
    setNotice(null);
    setSelected(new Set(zones.filter(predicate).map((z) => z.zoneId)));
  }

  // 같은 값이면 바뀐 것이 아니므로 edits 에서 뺀다. (저장할 것이 없는 상태가 정확히 보이게)
  function apply(value: string) {
    if (selected.size === 0) {
      setNotice("먼저 구역을 골라 주세요");
      return;
    }
    const next = { ...edits };
    for (const z of zones) {
      if (!selected.has(z.zoneId)) continue;
      if (value === zoneValue(z)) delete next[z.zoneId];
      else next[z.zoneId] = value;
    }
    onEdits(next);
    // 조사가 등급 이름에 따라 달라져서(석으로/제외로) 문장 대신 "구역 → 값" 모양으로 적는다.
    setNotice(`${selected.size}개 구역 → ${labelOf(value)}. 저장해야 반영됩니다`);
    setSelected(new Set());
  }

  const groupOptions =
    layout?.groups
      .map((names) => names.map((n) => zoneByName.get(n)).filter((z): z is ZoneAssignment => Boolean(z)))
      .filter((group) => group.length > 0) ?? [];
  const unmapped = layout ? zones.filter((z) => !layout.zones[z.name]) : zones;

  return (
    <div className="grid gap-6 xl:grid-cols-[minmax(0,1.4fr)_minmax(0,1fr)]">
      <div className="min-w-0">
        {layout ? (
          <div className="rounded-xl bg-deep p-2">
            <svg
              viewBox={layout.viewBox.join(" ")}
              className="w-full"
              role="group"
              aria-label="구역 지도"
            >
              {layout.decorations.map((d, i) => (
                <g key={i}>
                  <rect x={d.x} y={d.y} width={d.width} height={d.height} rx={d.rx ?? 0} fill="#c9cedb" />
                  {d.label && (
                    <text x={d.x + d.width / 2} y={d.y + d.height / 2 + 5} textAnchor="middle" fontSize={d.label === "STAGE" ? 18 : 11} fontWeight={500} fill="#ffffff">
                      {d.label}
                    </text>
                  )}
                </g>
              ))}
              {Object.entries(layout.zones).map(([name, shape]) => {
                const zone = zoneByName.get(name);
                if (!zone) return null;
                const value = valueOf(zone);
                const picked = selected.has(zone.zoneId);
                const [cx, cy] = centroid(shape.points);
                const unset = value === "UNSET";
                return (
                  <g
                    key={name}
                    role="button"
                    tabIndex={disabled ? -1 : 0}
                    aria-pressed={picked}
                    aria-label={`${name} ${labelOf(value)}`}
                    className="arena-zone"
                    data-disabled={disabled || undefined}
                    onClick={() => toggle(zone.zoneId)}
                    onKeyDown={(e) => {
                      if (e.key === "Enter" || e.key === " ") {
                        e.preventDefault();
                        toggle(zone.zoneId);
                      }
                    }}
                  >
                    <title>
                      {name} · {zone.seatCount}석 · {labelOf(value)}
                    </title>
                    <polygon
                      points={shape.points.map((p) => p.join(",")).join(" ")}
                      fill={colorOf(value)}
                      // 공용 CSS(.arena-zone polygon)가 속성값 stroke 를 덮어써서 선택 표시는 인라인 스타일로 준다.
                      style={
                        picked
                          ? { stroke: "#191b21", strokeWidth: 4 }
                          : unset
                            ? { strokeDasharray: "4 3" }
                            : undefined
                      }
                    />
                    <text x={cx} y={cy + 5} className="arena-zone-label" fill={unset ? "#5f6372" : "#ffffff"} style={unset ? { fill: "#5f6372" } : undefined}>
                      {shape.label}
                    </text>
                  </g>
                );
              })}
            </svg>
          </div>
        ) : null}

        {unmapped.length > 0 && (
          <div className={layout ? "mt-4" : ""}>
            {layout && <p className="mb-2 text-xs text-muted">도면에 없는 구역</p>}
            {/* 도면이 없는 공연장은 층별 구역 버튼으로 고른다 */}
            <div className="space-y-3">
              {floors.map((floor) => {
                const inFloor = unmapped.filter((z) => z.floor === floor);
                if (inFloor.length === 0) return null;
                return (
                  <div key={floor}>
                    <p className="mb-1.5 text-xs text-muted">{floor}</p>
                    <ul className="flex flex-wrap gap-2">
                      {inFloor.map((z) => {
                        const value = valueOf(z);
                        const picked = selected.has(z.zoneId);
                        return (
                          <li key={z.zoneId}>
                            <button
                              type="button"
                              disabled={disabled}
                              aria-pressed={picked}
                              onClick={() => toggle(z.zoneId)}
                              className={`inline-flex items-center gap-2 rounded-lg border px-3 py-2 text-sm ${picked ? "border-foreground" : "border-line"} disabled:opacity-60`}
                            >
                              <i className="size-3 rounded-sm" style={{ backgroundColor: colorOf(value) }} />
                              {z.name}
                              <span className="text-xs text-muted">{labelOf(value)}</span>
                            </button>
                          </li>
                        );
                      })}
                    </ul>
                  </div>
                );
              })}
            </div>
          </div>
        )}
      </div>

      <div className="min-w-0 space-y-5">
        <div>
          <p className="mb-2 text-sm font-medium">1. 구역 고르기</p>
          <div className="flex flex-wrap gap-2">
            <button type="button" className="chip" disabled={disabled} onClick={() => select(() => true)}>
              전체
            </button>
            {floors.map((floor) => (
              <button key={floor} type="button" className="chip" disabled={disabled} onClick={() => select((z) => z.floor === floor)}>
                {floor} 전체
              </button>
            ))}
            <button type="button" className="chip" disabled={disabled} onClick={() => select((z) => valueOf(z) === "UNSET")}>
              미설정만
            </button>
            <button type="button" className="chip" disabled={disabled || selected.size === 0} onClick={() => setSelected(new Set())}>
              선택 해제
            </button>
          </div>
          {groupOptions.length > 0 && (
            <select
              className="input mt-3 h-10"
              disabled={disabled}
              value=""
              aria-label="구역 묶음으로 고르기"
              onChange={(e) => {
                const group = groupOptions[Number(e.target.value)];
                if (group) {
                  setNotice(null);
                  setSelected(new Set(group.map((z) => z.zoneId)));
                }
              }}
            >
              <option value="">구역 묶음으로 고르기</option>
              {groupOptions.map((group, i) => (
                <option key={i} value={i}>
                  {group.map((z) => layout?.zones[z.name]?.label ?? z.name).join(" · ")}
                </option>
              ))}
            </select>
          )}
          <p className="mt-3 text-sm text-secondary">
            선택 <span className="font-medium text-foreground">{selected.size}</span>구역 · 미설정{" "}
            <span className={`font-medium ${unsetCount > 0 ? "text-accent-strong" : "text-foreground"}`}>{unsetCount}</span>구역 / 전체 {zones.length}구역
          </p>
        </div>

        <div>
          <p className="mb-2 text-sm font-medium">2. 등급 지정</p>
          {sortedGrades.length === 0 && <p className="mb-2 text-sm text-muted">등급이 없습니다. 위에서 등급을 먼저 추가해 주세요</p>}
          <ul className="space-y-2">
            {sortedGrades.map((g) => (
              <li key={g.gradeId}>
                <button
                  type="button"
                  disabled={disabled}
                  onClick={() => apply(`grade:${g.gradeId}`)}
                  className="flex w-full items-center gap-3 rounded-xl border border-line px-4 py-2.5 text-left text-sm transition-colors duration-150 hover:bg-raised disabled:opacity-50"
                >
                  <i className="size-3.5 rounded-sm" style={{ backgroundColor: gradeMapColor(g.name) }} />
                  <span className="flex-1">{gradeLabel(g.name)}</span>
                  <span className="text-secondary">{won(g.price)}</span>
                </button>
              </li>
            ))}
            <li>
              <button
                type="button"
                disabled={disabled}
                onClick={() => apply("UNSOLD")}
                className="flex w-full items-center gap-3 rounded-xl border border-line px-4 py-2.5 text-left text-sm transition-colors duration-150 hover:bg-raised disabled:opacity-50"
              >
                <i className="size-3.5 rounded-sm" style={{ backgroundColor: UNSOLD_COLOR }} />
                <span className="flex-1">판매 제외</span>
              </button>
            </li>
            <li>
              <button
                type="button"
                disabled={disabled}
                onClick={() => apply("UNSET")}
                className="flex w-full items-center gap-3 rounded-xl border border-dashed border-line px-4 py-2.5 text-left text-sm text-secondary transition-colors duration-150 hover:bg-raised disabled:opacity-50"
              >
                <i className="size-3.5 rounded-sm" style={{ backgroundColor: UNSET_COLOR }} />
                <span className="flex-1">미설정으로 되돌리기</span>
              </button>
            </li>
          </ul>
          {notice && (
            <p role="status" className="mt-3 text-sm text-secondary">
              {notice}
            </p>
          )}
          {disabled && <p className="mt-3 text-sm text-muted">판매 좌석을 만들었거나 취소된 공연이라 바꿀 수 없습니다</p>}
        </div>
      </div>
    </div>
  );
}
