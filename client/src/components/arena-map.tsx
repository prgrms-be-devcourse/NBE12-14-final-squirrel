"use client";

import { Minus, Plus } from "lucide-react";
import { useMemo, useState } from "react";
import { useApi } from "@/lib/api";
import { gradeLabel, gradeMapColor, won } from "@/lib/format";
import { boundsOf, layoutZoneSeats } from "@/lib/seat-layout";
import type { Seat, Zone, ZoneSeats } from "@/lib/types";
import type { Point, VenueLayout, ZoneShape } from "@/lib/venue-layouts/types";
import { Loading } from "@/components/ui";

// 도면이 있는 공연장의 구역 지도. 두 화면이 한 자리를 번갈아 쓴다.
//  1. 전체 지도: 구역을 등급 색으로 칠해 보여 준다. 구역을 누르면
//  2. 그 구역이 속한 묶음의 좌석 화면으로 바뀐다. 묶음의 구역들이 지도 위치 그대로 함께 보이고 좌석 점이 찍힌다.
// 좌석은 묶음을 열 때 그 묶음의 구역만 불러온다. (구역 좌석 API 는 구역 하나씩이라 묶음 크기만큼 동시에 부른다)

const ZOOM_MIN = 1;
const ZOOM_MAX = 4;
// 묶음 전체가 한 화면에 들어오게 그릴 때의 기준 높이(px). 이 높이에 맞는 너비까지만 키우고, 더 보려면 확대한다.
const FIT_HEIGHT = 840;

// 도면에 없는 구역(DB 에는 있는데 도면에 이름이 없는 구역)을 보여 줄 때 쓰는 임시 모양
const FALLBACK_SHAPE = (name: string): ZoneShape => ({
  label: name.replace("구역", ""),
  points: [[0, 0], [300, 0], [300, 170], [0, 170]] as Point[],
  rows: "horizontal",
});

// 좌석을 어디서 읽고 무엇을 고를 수 있는지. 판매자 보류석 화면이 바꿔 끼우고, 주지 않으면 예매자 화면의 동작이다.
export type ArenaMapMode = {
  seatsPath: (scheduleId: string, zoneId: number) => string;
  seatLook: (seat: Seat) => { pickable: boolean; className: string; note: string };
  zoneStatus: (zone: Zone) => { text: string; dimmed: boolean };
  legend: React.ReactNode;
};

const BOOKER_MODE: ArenaMapMode = {
  seatsPath: (scheduleId, zoneId) => `/api/v1/schedules/${scheduleId}/zones/${zoneId}/seats`,
  seatLook: (seat) =>
    seat.status === "AVAILABLE"
      ? { pickable: true, className: "arena-seat", note: "" }
      : { pickable: false, className: "arena-seat arena-seat-off", note: " · 선택 불가" },
  zoneStatus: (zone) =>
    zone.remaining === 0 ? { text: "매진", dimmed: true } : { text: `${zone.remaining}석 남음`, dimmed: false },
  legend: <Legend />,
};

type Props = {
  mode?: ArenaMapMode;
  layout: VenueLayout;
  zones: Zone[];
  scheduleId: string;
  pickedIds: Set<number>;
  onToggle: (seat: Seat, zone: Zone) => void;
  // 구역 좌석을 다시 읽게 하려고 올리는 값 (좌석을 놓쳤을 때)
  version: number;
};

export function ArenaMap({ mode = BOOKER_MODE, layout, zones, scheduleId, pickedIds, onToggle, version }: Props) {
  const [view, setView] = useState<string[] | null>(null);
  const [zoom, setZoom] = useState(1);

  const zoneByName = useMemo(() => new Map(zones.map((z) => [z.name, z])), [zones]);
  const shapeOf = (name: string): ZoneShape => layout.zones[name] ?? FALLBACK_SHAPE(name);
  const unmapped = zones.filter((z) => !layout.zones[z.name]);

  // 누른 구역이 속한 묶음 중 API 에 실제로 있는 구역만 연다.
  function open(name: string) {
    const group = layout.groups.find((g) => g.includes(name));
    const names = (group ?? [name]).filter((n) => zoneByName.has(n));
    setZoom(1);
    setView(names.length > 0 ? names : [name]);
  }

  if (view) {
    const shapes = view.map((name) => ({ name, shape: shapeOf(name) }));
    const all = boundsOf(shapes.flatMap((s) => s.shape.points));
    // 좌석 바탕과 열 번호가 구역 모양 밖으로 조금 나오므로 여유를 둔다.
    const pad = 14;
    const box = { x: all.x0 - pad, y: all.y0 - pad, w: all.width + 2 * pad, h: all.height + 2 * pad };
    // 묶음 전체가 한 화면(높이 FIT_HEIGHT)에 들어오는 너비를 기준으로 하고, 확대하면 그 너비를 배로 키운다.
    const fitWidth = Math.round((FIT_HEIGHT * box.w) / box.h);
    return (
      <section>
        <div className="mb-3 flex flex-wrap items-center gap-3">
          <button type="button" className="btn" onClick={() => setView(null)}>
            전체 지도
          </button>
          <h2 className="min-w-0 flex-1 font-semibold break-keep">
            {shapes.map((s) => s.shape.label).join(" · ")}
          </h2>
          <span className="inline-flex items-center gap-1">
            <button
              type="button"
              aria-label="축소"
              className="btn px-3"
              disabled={zoom <= ZOOM_MIN}
              onClick={() => setZoom((z) => Math.max(ZOOM_MIN, Math.round((z - 0.5) * 10) / 10))}
            >
              <Minus size={16} strokeWidth={1.5} />
            </button>
            <button
              type="button"
              aria-label="확대"
              className="btn px-3"
              disabled={zoom >= ZOOM_MAX}
              onClick={() => setZoom((z) => Math.min(ZOOM_MAX, Math.round((z + 0.5) * 10) / 10))}
            >
              <Plus size={16} strokeWidth={1.5} />
            </button>
          </span>
        </div>
        <div className="card overflow-auto bg-deep p-2" style={{ maxHeight: "84vh" }}>
          <svg
            viewBox={`${box.x} ${box.y} ${box.w} ${box.h}`}
            className="mx-auto block"
            style={{ width: `calc(min(100%, ${fitWidth}px) * ${zoom})`, aspectRatio: `${box.w} / ${box.h}` }}
            role="group"
            aria-label="좌석 지도"
          >
            {shapes.map(({ name, shape }) => (
              <ZoneLayer
                key={name}
                name={name}
                shape={shape}
                zone={zoneByName.get(name)}
                scheduleId={scheduleId}
                mode={mode}
                venueCenterX={layout.viewBox[0] + layout.viewBox[2] / 2}
                pickedIds={pickedIds}
                onToggle={onToggle}
                version={version}
              />
            ))}
          </svg>
        </div>
        <p className="mt-2 text-xs text-muted">확대하면 좌석을 누르기 쉽습니다</p>
        {mode.legend}
      </section>
    );
  }

  const [vx, vy, vw, vh] = layout.viewBox;
  return (
    <section>
      <h2 className="mb-3 font-semibold">구역</h2>
      <div className="card bg-deep p-2">
        <svg viewBox={`${vx} ${vy} ${vw} ${vh}`} className="w-full" role="group" aria-label="구역 지도">
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
            const unsold = zone.price === null;
            const { text, dimmed } = mode.zoneStatus(zone);
            const [cx, cy] = centroid(shape.points);
            return (
              <g
                key={name}
                role="button"
                tabIndex={unsold ? -1 : 0}
                aria-label={`${name} ${unsold ? "판매 제외" : `${gradeLabel(zone.grade)} ${text}`}`}
                className="arena-zone"
                data-disabled={unsold || undefined}
                onClick={() => !unsold && open(name)}
                onKeyDown={(e) => {
                  if ((e.key === "Enter" || e.key === " ") && !unsold) {
                    e.preventDefault();
                    open(name);
                  }
                }}
              >
                <title>
                  {name}
                  {unsold ? " · 판매 제외" : ` · ${gradeLabel(zone.grade)} ${won(zone.price!)} · ${text}`}
                </title>
                <polygon
                  points={shape.points.map((p) => p.join(",")).join(" ")}
                  fill={unsold ? "#aab0bf" : gradeMapColor(zone.grade)}
                  opacity={dimmed ? 0.4 : 1}
                />
                <text x={cx} y={cy + 5} className="arena-zone-label">
                  {shape.label}
                </text>
              </g>
            );
          })}
        </svg>
      </div>
      <div className="mt-3 flex flex-wrap gap-4 text-xs text-secondary">
        {gradesIn(zones).map((grade) => (
          <span key={grade} className="inline-flex items-center gap-2">
            <i className="size-3 rounded-sm" style={{ backgroundColor: gradeMapColor(grade) }} />
            {gradeLabel(grade)} {won(zones.find((z) => z.grade === grade)?.price ?? 0)}
          </span>
        ))}
      </div>
      {unmapped.length > 0 && (
        <div className="mt-4">
          <p className="mb-2 text-xs text-muted">도면에 없는 구역</p>
          <ul className="flex flex-wrap gap-2">
            {unmapped.map((z) => (
              <li key={z.zoneId}>
                <button type="button" className="chip" disabled={z.price === null} onClick={() => open(z.name)}>
                  {z.name}
                </button>
              </li>
            ))}
          </ul>
        </div>
      )}
    </section>
  );
}

function gradesIn(zones: Zone[]): string[] {
  const seen = new Set<string>();
  for (const z of zones) if (z.grade) seen.add(z.grade);
  return [...seen];
}

function centroid(points: Point[]): Point {
  return [points.reduce((s, p) => s + p[0], 0) / points.length, points.reduce((s, p) => s + p[1], 0) / points.length];
}

function Legend() {
  return (
    <div className="mt-3 flex flex-wrap gap-5 text-xs text-secondary">
      <span className="inline-flex items-center gap-2">
        <i className="size-3 rounded-full bg-muted/50" />
        선택 가능
      </span>
      <span className="inline-flex items-center gap-2">
        <i className="size-3 rounded-full bg-accent" />내 선택
      </span>
      <span className="inline-flex items-center gap-2">
        <i className="size-3 rounded-full bg-muted/15" />
        선택 불가
      </span>
    </div>
  );
}

// 구역 하나: 모양 위에 좌석 점을 찍는다. 좌석은 이 구역 것만 따로 불러온다.
function ZoneLayer({
  name,
  shape,
  zone,
  scheduleId,
  mode,
  venueCenterX,
  pickedIds,
  onToggle,
  version,
}: {
  name: string;
  shape: ZoneShape;
  zone: Zone | undefined;
  scheduleId: string;
  mode: ArenaMapMode;
  venueCenterX: number;
  pickedIds: Set<number>;
  onToggle: (seat: Seat, zone: Zone) => void;
  version: number;
}) {
  const path = zone ? mode.seatsPath(scheduleId, zone.zoneId) : null;
  const seats = useApi<ZoneSeats>(path, version > 0 ? { v: version } : undefined);
  const laid = useMemo(
    () => (seats.data ? layoutZoneSeats(shape, seats.data.seats, venueCenterX) : null),
    [seats.data, shape, venueCenterX],
  );
  const points = shape.points.map((p) => p.join(",")).join(" ");
  const { x0, y0, width } = boundsOf(shape.points);
  const hasBackdrop = laid !== null && laid.backdrop.length > 0;
  // 좌석을 불러오기 전에는 구역 모양을 임시 바탕으로 쓰고, 불러온 뒤에는 좌석이 있는 칸을 따라가는 바탕으로 바꾼다.
  const backdropPath = hasBackdrop
    ? laid.backdrop.map((r) => `M${r.x} ${r.y}h${r.w}v${r.h}h${-r.w}z`).join("")
    : null;
  const titleX = hasBackdrop ? laid.titleX : x0 + width / 2;
  const titleY = hasBackdrop ? laid.titleY : y0 + 9;

  return (
    <g>
      {backdropPath ? <path d={backdropPath} fill="var(--surface)" /> : <polygon points={points} fill="var(--surface)" />}
      <g>
        <text x={titleX} y={titleY} textAnchor="middle" fontSize={9} fontWeight={500} fill="var(--secondary)">
          {shape.label}
        </text>
        {seats.loading && (
          <text x={x0 + width / 2} y={y0 + 24} textAnchor="middle" fontSize={7} fill="var(--muted)">
            불러오는 중
          </text>
        )}
        {seats.error && (
          <text x={x0 + width / 2} y={y0 + 24} textAnchor="middle" fontSize={7} fill="var(--accent-strong)">
            불러오지 못했습니다
          </text>
        )}
        {laid?.rowLabels.map((r) => (
          <text key={r.label} x={r.x} y={r.y} textAnchor={r.anchor} fontSize={Math.max(2.4, laid.pitch * 0.7)} fill="var(--secondary)">
            {r.label.replace("열", "")}
          </text>
        ))}
        {laid?.seats.map(({ seat, x, y }) => {
          const chosen = pickedIds.has(seat.seatId);
          const look = mode.seatLook(seat);
          return (
            <circle
              key={seat.seatId}
              cx={x}
              cy={y}
              r={laid.pitch * 0.42}
              className={chosen ? "arena-seat arena-seat-picked" : look.className}
              onClick={() => zone && (look.pickable || chosen) && onToggle(seat, zone)}
            >
              <title>
                {name} {seat.row} {seat.number}번{chosen ? "" : look.note}
              </title>
            </circle>
          );
        })}
      </g>
    </g>
  );
}

export function ArenaMapLoading() {
  return <Loading label="지도를 불러오는 중" />;
}
