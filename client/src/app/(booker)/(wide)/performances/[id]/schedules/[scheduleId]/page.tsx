"use client";

import { ArrowLeft, X } from "lucide-react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { useCallback, useEffect, useState } from "react";
import { useRouteParams } from "@/lib/route-params";
import { ApiError, api, errorText, useApi, usePolledApi } from "@/lib/api";
import { useAccount } from "@/lib/auth";
import { getVenueLayout } from "@/lib/venue-layouts";
import { saveBooking } from "@/lib/booking-cache";
import { dateTimeText, gradeColor, gradeLabel, won } from "@/lib/format";
import type { BookingCreated, PerformanceDetail, Seat, Zone, ZoneSeats, ZoneSummary } from "@/lib/types";
import { ArenaMap } from "@/components/arena-map";
import { QueueGate } from "@/components/queue-gate";
import { ErrorBox, Loading } from "@/components/ui";

type Picked = {
  seatId: number;
  zoneName: string;
  label: string;
  gradeName: string | null;
  price: number;
};

// 구역 색(#rrggbb)에 투명도를 얹어 옅은 면으로 쓴다.
const tint = (color: string | null, alpha: string) => (color ? `${color}${alpha}` : undefined);

export default function SeatSelectPage() {
  const { id, scheduleId } = useRouteParams<{ id: string; scheduleId: string }>("/performances/[id]/schedules/[scheduleId]");
  // 예매하기를 누르면 먼저 대기열에 입장해야 구역·좌석을 볼 수 있다.
  return (
    <QueueGate scheduleId={scheduleId} backHref={`/performances/${id}`}>
      {(reenter) => <SeatSelect onNotAdmitted={reenter} />}
    </QueueGate>
  );
}

function SeatSelect({ onNotAdmitted }: { onNotAdmitted: () => void }) {
  const { id, scheduleId } = useRouteParams<{ id: string; scheduleId: string }>("/performances/[id]/schedules/[scheduleId]");
  const router = useRouter();
  const account = useAccount();
  const toLogin = () => router.push("/login");

  const performance = useApi<PerformanceDetail>(`/api/v1/performances/${id}`);
  const zones = useApi<ZoneSummary>(`/api/v1/schedules/${scheduleId}/zones`);
  // 좌석을 고르는 동안 판매자가 판매를 중지하거나 재개할 수 있어 공연 상세를 다시 읽는다. 막는 것은 서버다.
  const latest = usePolledApi<PerformanceDetail>(`/api/v1/performances/${id}`, 10_000);

  // 도면이 있는 공연장은 구역 지도(묶음 단위 좌석 화면)로, 없으면 층별 구역 격자로 그린다.
  const layout = getVenueLayout(zones.data?.layoutKey);
  const [mapVersion, setMapVersion] = useState(0);

  const [zoneId, setZoneId] = useState<number | null>(null);
  const activeZoneId = zoneId ?? zones.data?.zones.find((z) => z.price !== null)?.zoneId ?? null;
  const seats = useApi<ZoneSeats>(
    activeZoneId === null || layout ? null : `/api/v1/schedules/${scheduleId}/zones/${activeZoneId}/seats`,
  );

  // 입장 시간이 지났거나 밀려나면 구역·좌석 조회가 QUEUE000 으로 막힌다. 다시 입장시킨다.
  const notAdmitted = zones.error?.code === "QUEUE000" || seats.error?.code === "QUEUE000";
  const reenter = useCallback(() => onNotAdmitted(), [onNotAdmitted]);
  useEffect(() => {
    if (notAdmitted) reenter();
  }, [notAdmitted, reenter]);

  const [picked, setPicked] = useState<Picked[]>([]);
  const [submitting, setSubmitting] = useState(false);
  const [message, setMessage] = useState<string | null>(null);

  const p = performance.data;
  const schedule = p?.schedules.find((s) => String(s.scheduleId) === scheduleId);
  const paused =
    (latest ?? p)?.schedules.find((s) => String(s.scheduleId) === scheduleId)?.status === "PAUSED";
  const maxPerPerson = p?.sale.maxPerPerson ?? 4;
  const activeZone = zones.data?.zones.find((z) => z.zoneId === activeZoneId);

  function toggle(seat: Seat, zoneOverride?: Zone) {
    const zone = zoneOverride ?? activeZone;
    const chosen = picked.some((s) => s.seatId === seat.seatId);
    if (!zone || (seat.status !== "AVAILABLE" && !chosen)) return;
    setMessage(null);
    if (picked.some((s) => s.seatId === seat.seatId)) {
      setPicked(picked.filter((s) => s.seatId !== seat.seatId));
      return;
    }
    if (picked.length >= maxPerPerson) {
      setMessage(`1인 최대 ${maxPerPerson}매까지 선택할 수 있습니다`);
      return;
    }
    setPicked([
      ...picked,
      {
        seatId: seat.seatId,
        zoneName: zone.name,
        label: `${seat.row} ${seat.number}번`,
        gradeName: zone.grade,
        price: zone.price ?? 0,
      },
    ]);
  }

  async function book() {
    setSubmitting(true);
    setMessage(null);
    try {
      const booking = await api<BookingCreated>(`/api/v1/schedules/${scheduleId}/bookings`, {
        method: "POST",
        body: { seatIds: picked.map((s) => s.seatId) },
      });
      // 결제 화면이 같은 정보를 바로 쓰도록 둔다. (예매 상세 API 가 없어도 결제까지 가야 한다)
      saveBooking({
        ...booking,
        performanceId: Number(id),
        scheduleId: Number(scheduleId),
        performanceTitle: p?.title ?? "",
        scheduleStartAt: schedule?.startAt ?? "",
      });
      router.push(`/bookings/${booking.bookingId}/pay`);
    } catch (error) {
      if (error instanceof ApiError && error.status === 401) {
        toLogin();
        return;
      }
      if (error instanceof ApiError && error.code === "QUEUE000") {
        onNotAdmitted();
        return;
      }
      setMessage(errorText(error, "예매에 실패했습니다"));
      if (error instanceof ApiError && error.code === "BOOKING000") {
        // 그사이 다른 사람이 가져간 좌석이 있다. 선택을 비우고 최신 상태를 다시 읽는다
        setPicked([]);
        zones.reload();
        seats.reload();
        setMapVersion((v) => v + 1);
      }
    } finally {
      setSubmitting(false);
    }
  }

  if (performance.loading || zones.loading) return <Loading />;
  if (performance.error) return <ErrorBox error={performance.error} onRetry={performance.reload} />;
  if (zones.error) return <ErrorBox error={zones.error} onRetry={zones.reload} />;
  if (!p || !zones.data) return null;

  const rows = new Map<string, Seat[]>();
  seats.data?.seats.forEach((seat) => rows.set(seat.row, [...(rows.get(seat.row) ?? []), seat]));
  const ticketTotal = picked.reduce((sum, s) => sum + s.price, 0);
  const feeTotal = picked.length * p.sale.bookingFee;

  const floors = new Map<string, Zone[]>();
  zones.data.zones.forEach((z) => floors.set(z.floor, [...(floors.get(z.floor) ?? []), z]));

  return (
    <div>
      <Link
        href={`/performances/${id}`}
        className="inline-flex items-center gap-2 text-sm text-secondary transition-colors duration-150 hover:text-foreground"
      >
        <ArrowLeft size={16} strokeWidth={1.5} />
        공연 상세
      </Link>
      <h1 className="mt-4 text-3xl leading-tight font-semibold">{p.title}</h1>
      <p className="mt-2 text-secondary">{schedule ? dateTimeText(schedule.startAt) : "회차 정보 없음"}</p>

      <div className="mt-10 grid gap-8 lg:grid-cols-[1fr_340px]">
        <div className="min-w-0 space-y-10">
          {layout && (
            <ArenaMap
              layout={layout}
              zones={zones.data.zones}
              scheduleId={scheduleId}
              pickedIds={new Set(picked.map((x) => x.seatId))}
              onToggle={toggle}
              version={mapVersion}
            />
          )}
          {/* 도면이 없는 공연장: 무대 아래로 층별 구역을 늘어놓는다 */}
          {!layout && (<>
          <section>
            <h2 className="mb-4 font-semibold">구역</h2>
            <div className="card p-5">
              <div className="mx-auto mb-5 w-2/3 rounded-b-[999px] border border-t-0 border-line bg-raised py-3 text-center text-xs text-secondary">
                무대
              </div>
              <div className="space-y-3">
                {[...floors.entries()].map(([floor, floorZones]) => (
                  <div key={floor} className="flex items-stretch gap-3">
                    <span className="flex w-10 shrink-0 items-center text-xs text-muted">{floor}</span>
                    <ul className="grid flex-1 gap-3" style={{ gridTemplateColumns: `repeat(${floorZones.length}, 1fr)` }}>
                      {floorZones.map((zone) => {
                        const unsold = zone.price === null;
                        const active = zone.zoneId === activeZoneId;
                        const soldOut = zone.remaining === 0;
                        return (
                          <li key={zone.zoneId}>
                            <button
                              type="button"
                              disabled={unsold}
                              onClick={() => setZoneId(zone.zoneId)}
                              aria-pressed={active}
                              style={{
                                backgroundColor: tint(gradeColor(zone.grade, zone.color), active ? "40" : "1f"),
                                borderColor: active ? (gradeColor(zone.grade, zone.color) ?? undefined) : "transparent",
                              }}
                              className="h-full w-full rounded-lg border-2 px-3 py-4 text-left transition-colors duration-150 disabled:opacity-40"
                            >
                              <span className="block text-sm font-medium">{zone.name}</span>
                              <span className="mt-1 block text-xs text-secondary">
                                {unsold ? "판매 제외" : `${gradeLabel(zone.grade)} · ${won(zone.price!)}`}
                              </span>
                              {!unsold && (
                                <span className={`mt-2 block text-xs ${soldOut ? "text-muted" : "text-foreground"}`}>
                                  {soldOut ? "매진" : `${zone.remaining}석 남음`}
                                </span>
                              )}
                            </button>
                          </li>
                        );
                      })}
                    </ul>
                  </div>
                ))}
              </div>
            </div>
          </section>

          <section>
            <h2 className="mb-4 font-semibold">좌석{activeZone ? ` · ${activeZone.name}` : ""}</h2>
            <div className="card p-6">
              {seats.loading && <Loading />}
              {seats.error && <ErrorBox error={seats.error} onRetry={seats.reload} />}
              <div className="space-y-1 overflow-x-auto">
                {[...rows.entries()].map(([row, rowSeats]) => (
                  <div key={row} className="flex items-center gap-1">
                    <span className="w-10 shrink-0 text-xs text-muted">{row}</span>
                    {rowSeats.map((seat) => {
                      const chosen = picked.some((s) => s.seatId === seat.seatId);
                      const available = seat.status === "AVAILABLE";
                      return (
                        <button
                          key={seat.seatId}
                          type="button"
                          onClick={() => toggle(seat)}
                          disabled={!available}
                          aria-pressed={chosen}
                          title={`${seat.row} ${seat.number}번 · ${available ? "선택 가능" : "선택 불가"}`}
                          style={
                            chosen
                              ? undefined
                              : available
                                ? {
                                    backgroundColor: tint(gradeColor(activeZone?.grade, activeZone?.color), "26"),
                                    borderColor: tint(gradeColor(activeZone?.grade, activeZone?.color), "80"),
                                  }
                                : undefined
                          }
                          className={`size-11 shrink-0 rounded-md border text-sm transition-colors duration-150 ${
                            chosen
                              ? "border-accent bg-accent text-white"
                              : available
                                ? "hover:brightness-125"
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
              <div className="mt-6 flex flex-wrap gap-5 text-xs text-secondary">
                <span className="inline-flex items-center gap-2">
                  <i className="size-3 rounded-sm border border-secondary" />
                  선택 가능
                </span>
                <span className="inline-flex items-center gap-2">
                  <i className="size-3 rounded-sm bg-accent" />내 선택
                </span>
                <span className="inline-flex items-center gap-2">
                  <i className="size-3 rounded-sm bg-raised" />
                  선택 불가
                </span>
              </div>
            </div>
          </section>
          </>)}
        </div>

        <aside className="card h-fit space-y-5 p-6 lg:sticky lg:top-28">
          <h2 className="font-semibold">
            선택한 좌석 <span className="text-muted">{picked.length}/{maxPerPerson}</span>
          </h2>
          {picked.length === 0 ? (
            <p className="text-sm text-muted">좌석을 선택하세요</p>
          ) : (
            <ul className="divide-y divide-line text-sm">
              {picked.map((s) => (
                <li key={s.seatId} className="flex items-center justify-between gap-2 py-3">
                  <span>
                    {s.zoneName} {s.label}
                    <span className="block text-xs text-muted">{gradeLabel(s.gradeName)}</span>
                  </span>
                  <span className="flex items-center gap-3">
                    {won(s.price)}
                    <button
                      type="button"
                      aria-label="선택 해제"
                      className="text-muted transition-colors duration-150 hover:text-foreground"
                      onClick={() => setPicked((c) => c.filter((x) => x.seatId !== s.seatId))}
                    >
                      <X size={16} strokeWidth={1.5} />
                    </button>
                  </span>
                </li>
              ))}
            </ul>
          )}
          <dl className="space-y-2 border-t border-line pt-5 text-sm">
            <div className="flex justify-between">
              <dt className="text-secondary">티켓 금액</dt>
              <dd>{won(ticketTotal)}</dd>
            </div>
            <div className="flex justify-between">
              <dt className="text-secondary">예매 수수료</dt>
              <dd>{won(feeTotal)}</dd>
            </div>
            <div className="flex items-baseline justify-between pt-2">
              <dt>합계</dt>
              <dd className="text-2xl font-medium">{won(ticketTotal + feeTotal)}</dd>
            </div>
          </dl>
          {paused && (
            <p role="status" className="rounded-xl bg-[var(--warn-soft)] px-4 py-3 text-sm text-[var(--warn)]">
              판매가 일시 중지되어 지금은 예매할 수 없습니다. 재개되면 다시 예매할 수 있습니다
            </p>
          )}
          {message && <p className="text-sm text-accent-strong">{message}</p>}
          {account === null ? (
            <button type="button" className="btn btn-primary btn-lg w-full" onClick={toLogin}>
              로그인하고 예매하기
            </button>
          ) : (
            <button
              type="button"
              className="btn btn-primary btn-lg w-full"
              disabled={paused || picked.length === 0 || submitting}
              onClick={book}
            >
              {submitting ? "좌석 확인 중" : "주문 확인으로"}
            </button>
          )}
          <p className="text-center text-xs text-muted">주문 확인으로 넘어가면 선택한 좌석을 7분간 잡아 둡니다</p>
        </aside>
      </div>
    </div>
  );
}
