"use client";

import { useState } from "react";
import { useRouteParams } from "@/lib/route-params";
import { api, errorText, useApi } from "@/lib/api";
import { gradeLabel } from "@/lib/format";
import type {
  Seat,
  SeatReservationChanged,
  SeatStatus,
  SellerScheduleZone,
  SellerScheduleZones,
  Zone,
  ZoneSeats,
} from "@/lib/types";
import { ArenaMap, type ArenaMapMode } from "@/components/arena-map";
import { ConsoleTitle, Modal, Panel, Table } from "@/components/console";
import { ErrorBox, Loading } from "@/components/ui";
import { getVenueLayout } from "@/lib/venue-layouts";

// 한 요청에 보낼 수 있는 좌석 수 (api-spec SLR-32). 더 많으면 구역 통째로 보낸다.
const MAX_SEATS = 500;
// 보류·해제하지 못한 좌석을 이름으로 적는 개수
const NAMED_SKIPS = 10;

const STATUS_LABEL: Record<SeatStatus, string> = {
  AVAILABLE: "판매 가능",
  HELD: "점유 중",
  SOLD: "판매 완료",
  BLOCKED: "판매 제외",
  RESERVED: "보류",
};

const VERB = { reserve: "보류", release: "해제" } as const;
type Kind = keyof typeof VERB;
type Picked = { status: SeatStatus; label: string };
type Target = { seatIds: number[] } | { zoneIds: number[] };

const pickable = (status: SeatStatus) => status === "AVAILABLE" || status === "RESERVED";

export default function ScheduleReservedSeatsPage() {
  const { id } = useRouteParams<{ id: string }>("/seller/schedules/[id]/seats");
  const base = `/api/v1/seller/schedules/${id}`;
  const summary = useApi<SellerScheduleZones>(`${base}/zones`);

  const [picked, setPicked] = useState<Map<number, Picked>>(new Map());
  const [checkedZones, setCheckedZones] = useState<number[]>([]);
  const [openZoneId, setOpenZoneId] = useState<number | null>(null);
  // 바꾼 뒤 좌석을 다시 읽게 하려고 올리는 값
  const [version, setVersion] = useState(0);
  const [releasing, setReleasing] = useState<Target | null>(null);
  const [generating, setGenerating] = useState(false);
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState<{ text: string; error: boolean } | null>(null);

  const layout = getVenueLayout(summary.data?.layoutKey);
  const zones = summary.data?.zones ?? [];
  const gridZoneId = openZoneId ?? zones.find((z) => z.price !== null)?.zoneId ?? null;
  const gridSeats = useApi<ZoneSeats>(
    layout || !summary.data?.seatsGenerated || gridZoneId === null ? null : `${base}/zones/${gridZoneId}/seats`,
    version > 0 ? { v: version } : undefined,
  );

  function refresh() {
    summary.reload();
    setVersion((v) => v + 1);
  }

  function toggle(seat: Seat, zoneName: string) {
    const next = new Map(picked);
    if (next.has(seat.seatId)) {
      next.delete(seat.seatId);
    } else if (next.size >= MAX_SEATS) {
      setMessage({ text: `한 번에 ${MAX_SEATS}석까지 고를 수 있습니다. 더 많으면 구역 통째로 처리해 주세요.`, error: true });
      return;
    } else {
      next.set(seat.seatId, { status: seat.status, label: `${zoneName} ${seat.row} ${seat.number}번` });
    }
    setPicked(next);
  }

  function resultText(kind: Kind, result: SeatReservationChanged): string {
    const changed = result.changedSeatIds?.length ?? result.zones?.reduce((sum, z) => sum + z.changedSeats, 0) ?? 0;
    const done = `${changed}석을 ${VERB[kind]}했습니다`;
    const skipped = result.skipped ?? [];
    if (skipped.length === 0) return done;
    const names = skipped
      .slice(0, NAMED_SKIPS)
      .map((s) => `${picked.get(s.seatId)?.label ?? s.seatId}(${STATUS_LABEL[s.status]})`);
    const rest = skipped.length > NAMED_SKIPS ? ` 외 ${skipped.length - NAMED_SKIPS}석` : "";
    return `${done}. ${skipped.length}석은 ${VERB[kind]}하지 못했습니다: ${names.join(", ")}${rest}`;
  }

  async function change(kind: Kind, target: Target) {
    setBusy(true);
    setMessage(null);
    try {
      const result = await api<SeatReservationChanged>(`${base}/seats/${kind}`, { method: "POST", body: target });
      setMessage({ text: resultText(kind, result), error: false });
      // 보낸 것만 선택에서 뺀다. 보류할 좌석과 해제할 좌석을 함께 골라 두고 차례로 처리할 수 있다.
      if ("seatIds" in target) {
        setPicked(new Map([...picked].filter(([seatId]) => !target.seatIds.includes(seatId))));
      } else {
        setPicked(new Map());
        setCheckedZones([]);
      }
    } catch (error) {
      setMessage({ text: errorText(error, "처리하지 못했습니다"), error: true });
    } finally {
      setReleasing(null);
      setBusy(false);
      // 거절돼도 그 사이 좌석 상태가 달라졌을 수 있어 다시 읽는다.
      refresh();
    }
  }

  async function generate() {
    setBusy(true);
    setMessage(null);
    try {
      await api(`${base}/seats/generate`, { method: "POST" });
      setMessage({ text: "판매 좌석을 만들었습니다", error: false });
    } catch (error) {
      setMessage({ text: errorText(error, "처리하지 못했습니다"), error: true });
    } finally {
      setGenerating(false);
      setBusy(false);
      refresh();
    }
  }

  const zoneById = new Map(zones.map((z) => [z.zoneId, z]));
  const mapZones: Zone[] = zones.map((z) => ({
    zoneId: z.zoneId,
    name: z.name,
    floor: z.floor ?? "",
    grade: z.grade,
    price: z.price,
    remaining: z.availableSeats ?? 0,
    total: z.total,
  }));
  const mode: ArenaMapMode = {
    seatsPath: (_, zoneId) => `${base}/zones/${zoneId}/seats`,
    seatLook: (seat) => ({
      pickable: pickable(seat.status),
      className:
        seat.status === "RESERVED"
          ? "arena-seat arena-seat-reserved"
          : seat.status === "AVAILABLE"
            ? "arena-seat"
            : "arena-seat arena-seat-off",
      note: ` · ${STATUS_LABEL[seat.status]}`,
    }),
    zoneStatus: (zone) => {
      const z = zoneById.get(zone.zoneId);
      return {
        text: `판매 가능 ${z?.availableSeats ?? 0}석 · 보류 ${z?.reservedSeats ?? 0}석`,
        dimmed: (z?.availableSeats ?? 0) + (z?.reservedSeats ?? 0) === 0,
      };
    },
    legend: <Legend />,
  };

  const idsOf = (status: SeatStatus) => [...picked].filter(([, p]) => p.status === status).map(([seatId]) => seatId);
  const toReserve = idsOf("AVAILABLE");
  const toRelease = idsOf("RESERVED");
  const gridRows = new Map<string, Seat[]>();
  gridSeats.data?.seats.forEach((seat) => gridRows.set(seat.row, [...(gridRows.get(seat.row) ?? []), seat]));

  return (
    <>
      <ConsoleTitle title="보류석 관리" sub={`회차 번호 ${id}`} back={{ href: "/seller/performances", label: "공연 목록" }} />

      {message && (
        <p
          role="status"
          className={`mb-6 rounded-xl px-4 py-3 text-sm break-words ${message.error ? "bg-accent-soft text-accent-strong" : "bg-[var(--ok-soft)] text-ok"}`}
        >
          {message.text}
        </p>
      )}

      {summary.loading && <Loading />}
      {summary.error && <ErrorBox error={summary.error} onRetry={summary.reload} />}

      {summary.data && !summary.data.seatsGenerated && (
        <Panel title="판매 좌석이 아직 없습니다" sub="보류석은 판매 좌석을 만든 뒤에 지정할 수 있습니다">
          <button type="button" className="btn btn-primary" disabled={busy} onClick={() => setGenerating(true)}>
            좌석 지금 생성
          </button>
        </Panel>
      )}

      {summary.data?.seatsGenerated && (
        <>
          <p className="mb-4 text-sm text-secondary">
            지정은 판매 오픈 전까지, 해제는 회차 시작 전까지 할 수 있습니다. 좌석을 누르면 고릅니다.
          </p>
          <div className="grid gap-6 xl:grid-cols-[1fr_280px]">
            <div className="min-w-0 space-y-6">
              {layout ? (
                <ArenaMap
                  mode={mode}
                  layout={layout}
                  zones={mapZones}
                  scheduleId={id}
                  pickedIds={new Set(picked.keys())}
                  onToggle={(seat, zone) => toggle(seat, zone.name)}
                  version={version}
                />
              ) : (
                <Panel title="좌석">
                  <div className="mb-5 flex flex-wrap gap-2">
                    {zones
                      .filter((z) => z.price !== null)
                      .map((z) => (
                        <button
                          key={z.zoneId}
                          type="button"
                          className="chip"
                          aria-pressed={z.zoneId === gridZoneId}
                          onClick={() => setOpenZoneId(z.zoneId)}
                        >
                          {z.name}
                        </button>
                      ))}
                  </div>
                  {gridSeats.loading && <Loading />}
                  {gridSeats.error && <ErrorBox error={gridSeats.error} onRetry={gridSeats.reload} />}
                  <div className="space-y-1 overflow-x-auto">
                    {[...gridRows.entries()].map(([row, rowSeats]) => (
                      <div key={row} className="flex items-center gap-1">
                        <span className="w-10 shrink-0 text-xs text-muted">{row}</span>
                        {rowSeats.map((seat) => {
                          const chosen = picked.has(seat.seatId);
                          return (
                            <button
                              key={seat.seatId}
                              type="button"
                              disabled={!chosen && !pickable(seat.status)}
                              aria-pressed={chosen}
                              aria-label={`${seat.row} ${seat.number}번 ${STATUS_LABEL[seat.status]}`}
                              title={`${seat.row} ${seat.number}번 · ${STATUS_LABEL[seat.status]}`}
                              onClick={() => toggle(seat, gridSeats.data!.name)}
                              className={`size-11 shrink-0 rounded-md border text-sm transition-colors duration-150 ${
                                chosen
                                  ? "border-accent bg-accent text-white"
                                  : seat.status === "RESERVED"
                                    ? "border-warn bg-warn text-white"
                                    : seat.status === "AVAILABLE"
                                      ? "border-line hover:bg-raised"
                                      : "border-transparent bg-raised text-muted/50"
                              }`}
                            >
                              {seat.number}
                            </button>
                          );
                        })}
                      </div>
                    ))}
                  </div>
                  <Legend />
                </Panel>
              )}
            </div>

            <aside className="card h-fit space-y-4 p-5 xl:sticky xl:top-8">
              <h2 className="font-semibold">
                고른 좌석 <span className="text-muted">{picked.size}석</span>
              </h2>
              {picked.size === 0 ? (
                <p className="text-sm text-muted">좌석을 고르세요</p>
              ) : (
                <ul className="max-h-64 space-y-1 overflow-y-auto text-sm">
                  {[...picked].map(([seatId, p]) => (
                    <li key={seatId}>
                      {p.label} <span className="text-muted">{STATUS_LABEL[p.status]}</span>
                    </li>
                  ))}
                </ul>
              )}
              <button
                type="button"
                className="btn btn-primary w-full"
                disabled={busy || toReserve.length === 0}
                onClick={() => change("reserve", { seatIds: toReserve })}
              >
                {toReserve.length}석 보류
              </button>
              <button
                type="button"
                className="btn w-full"
                disabled={busy || toRelease.length === 0}
                onClick={() => setReleasing({ seatIds: toRelease })}
              >
                {toRelease.length}석 해제
              </button>
              {picked.size > 0 && (
                <button type="button" className="text-sm text-secondary hover:text-foreground" onClick={() => setPicked(new Map())}>
                  선택 비우기
                </button>
              )}
            </aside>
          </div>

          <Panel
            className="mt-6"
            title="구역 통째로"
            sub="고른 구역의 판매 가능 좌석을 모두 보류하거나 보류석을 모두 해제합니다"
            actions={
              <>
                <button
                  type="button"
                  className="btn btn-primary"
                  disabled={busy || checkedZones.length === 0}
                  onClick={() => change("reserve", { zoneIds: checkedZones })}
                >
                  구역 보류
                </button>
                <button
                  type="button"
                  className="btn"
                  disabled={busy || checkedZones.length === 0}
                  onClick={() => setReleasing({ zoneIds: checkedZones })}
                >
                  구역 해제
                </button>
              </>
            }
          >
            <div className="max-h-96 overflow-y-auto">
              <Table head={["선택", "구역", "등급", "판매 가능", "보류", "전체"]}>
                {zones.map((z: SellerScheduleZone) => (
                  <tr key={z.zoneId}>
                    <td className="px-5 py-3">
                      <input
                        type="checkbox"
                        className="size-4"
                        aria-label={`${z.name} 선택`}
                        disabled={z.price === null}
                        checked={checkedZones.includes(z.zoneId)}
                        onChange={(e) =>
                          setCheckedZones((ids) =>
                            e.target.checked ? [...ids, z.zoneId] : ids.filter((zoneId) => zoneId !== z.zoneId),
                          )
                        }
                      />
                    </td>
                    <td className="px-5 py-3 font-medium">{z.name}</td>
                    <td className="px-5 py-3">{z.price === null ? "판매 제외" : gradeLabel(z.grade)}</td>
                    <td className="px-5 py-3">{z.availableSeats}</td>
                    <td className="px-5 py-3">{z.reservedSeats}</td>
                    <td className="px-5 py-3 text-muted">{z.total}</td>
                  </tr>
                ))}
              </Table>
            </div>
          </Panel>
        </>
      )}

      {releasing && (
        <Modal title="보류석 해제" onClose={() => setReleasing(null)}>
          <p className="text-sm text-secondary">
            {"seatIds" in releasing
              ? `${releasing.seatIds.length}석의 보류를 해제합니다.`
              : `${releasing.zoneIds.map((zoneId) => zoneById.get(zoneId)?.name).join(", ")}의 보류석을 모두 해제합니다.`}{" "}
            판매가 열린 회차라면 해제한 좌석은 바로 예매할 수 있게 됩니다. 판매가 시작된 뒤에는 다시 보류할 수 없습니다.
          </p>
          <div className="mt-6 flex justify-end gap-2">
            <button type="button" className="btn" onClick={() => setReleasing(null)}>
              닫기
            </button>
            <button type="button" className="btn btn-primary" disabled={busy} onClick={() => change("release", releasing)}>
              해제
            </button>
          </div>
        </Modal>
      )}

      {generating && (
        <Modal title="좌석 지금 생성" onClose={() => setGenerating(false)}>
          <p className="text-sm text-secondary">
            판매 좌석을 만든 뒤에는 이 공연의 등급·가격·구역을 바꿀 수 없고 되돌릴 수 없습니다.
          </p>
          <div className="mt-6 flex justify-end gap-2">
            <button type="button" className="btn" onClick={() => setGenerating(false)}>
              닫기
            </button>
            <button type="button" className="btn btn-primary" disabled={busy} onClick={generate}>
              생성
            </button>
          </div>
        </Modal>
      )}
    </>
  );
}

function Legend() {
  return (
    <div className="mt-3 flex flex-wrap gap-5 text-xs text-secondary">
      <span className="inline-flex items-center gap-2">
        <i className="size-3 rounded-full bg-muted/50" />
        판매 가능
      </span>
      <span className="inline-flex items-center gap-2">
        <i className="size-3 rounded-full bg-warn" />
        보류
      </span>
      <span className="inline-flex items-center gap-2">
        <i className="size-3 rounded-full bg-accent" />
        고른 좌석
      </span>
      <span className="inline-flex items-center gap-2">
        <i className="size-3 rounded-full bg-muted/15" />
        바꿀 수 없음
      </span>
    </div>
  );
}
