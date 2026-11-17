"use client";

import { Plus, X } from "lucide-react";
import { useState } from "react";
import { ApiError, api } from "@/lib/api";
import type { VenueForm } from "@/lib/types";
import { ConsoleTitle, Field, Panel } from "@/components/console";

type ZoneDraft = { name: string; floor: string; rows: { label: string; from: string; to: string }[] };

const emptyZone = (): ZoneDraft => ({ name: "", floor: "1층", rows: [{ label: "1열", from: "1", to: "10" }] });

export default function AdminVenuesPage() {
  const [info, setInfo] = useState({ name: "", address: "", region: "서울", layoutKey: "" });
  const [zones, setZones] = useState<ZoneDraft[]>([emptyZone()]);
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState<{ text: string; error: boolean } | null>(null);

  const totalSeats = zones.reduce(
    (sum, z) =>
      sum + z.rows.reduce((s, r) => s + Math.max(0, Number(r.to) - Number(r.from) + 1 || 0), 0),
    0,
  );

  const updateZone = (zi: number, patch: Partial<ZoneDraft>) =>
    setZones(zones.map((z, i) => (i === zi ? { ...z, ...patch } : z)));
  const updateRow = (zi: number, ri: number, patch: Partial<ZoneDraft["rows"][number]>) =>
    updateZone(zi, { rows: zones[zi].rows.map((r, i) => (i === ri ? { ...r, ...patch } : r)) });

  async function submit(event: React.FormEvent) {
    event.preventDefault();
    setBusy(true);
    setMessage(null);
    const body: VenueForm = {
      ...info,
      zones: zones.map((z) => ({
        name: z.name,
        floor: z.floor,
        rows: z.rows.map((r) => ({ label: r.label, from: Number(r.from), to: Number(r.to) })),
      })),
    };
    try {
      const created = await api<{ name: string; totalSeats: number; zoneCount: number }>("/api/v1/admin/venues", {
        method: "POST",
        body,
      });
      setMessage({
        text: `${created.name}을(를) 등록했습니다 (구역 ${created.zoneCount}개, 좌석 ${created.totalSeats.toLocaleString("ko-KR")}석)`,
        error: false,
      });
      setInfo({ name: "", address: "", region: "서울", layoutKey: "" });
      setZones([emptyZone()]);
    } catch (error) {
      setMessage({ text: error instanceof ApiError ? error.message : "등록하지 못했습니다", error: true });
    } finally {
      setBusy(false);
    }
  }

  return (
    <>
      <ConsoleTitle title="공연장 등록" sub="공연장, 구역, 좌석을 한 번에 만듭니다. 판매자는 등록된 공연장에서 공연을 엽니다" />

      {message && (
        <p role="status" className={`mb-6 rounded-xl px-4 py-3 text-sm ${message.error ? "bg-accent-soft text-accent-strong" : "bg-[var(--ok-soft)] text-ok"}`}>
          {message.text}
        </p>
      )}

      <form onSubmit={submit} className="space-y-6">
        <Panel title="공연장 정보">
          <div className="grid gap-5 sm:grid-cols-2">
            <Field label="이름">
              <input className="input" value={info.name} onChange={(e) => setInfo({ ...info, name: e.target.value })} required />
            </Field>
            <Field label="지역" hint="공연 목록의 지역 필터와 같은 값을 씁니다">
              <input className="input" value={info.region} onChange={(e) => setInfo({ ...info, region: e.target.value })} required />
            </Field>
            <Field label="주소" className="sm:col-span-2">
              <input className="input" value={info.address} onChange={(e) => setInfo({ ...info, address: e.target.value })} required />
            </Field>
            <Field label="배치 키" hint="구역 지도 모양을 고르는 이름입니다 (예: sample-arena)" className="sm:col-span-2">
              <input className="input" value={info.layoutKey} onChange={(e) => setInfo({ ...info, layoutKey: e.target.value })} required />
            </Field>
          </div>
        </Panel>

        {zones.map((zone, zi) => (
          <Panel
            key={zi}
            title={`구역 ${zi + 1}`}
            actions={
              zones.length > 1 && (
                <button type="button" aria-label="구역 삭제" className="text-muted hover:text-foreground" onClick={() => setZones(zones.filter((_, i) => i !== zi))}>
                  <X size={18} strokeWidth={1.5} />
                </button>
              )
            }
          >
            <div className="grid gap-5 sm:grid-cols-2">
              <Field label="구역 이름">
                <input className="input" value={zone.name} onChange={(e) => updateZone(zi, { name: e.target.value })} placeholder="F2구역" required />
              </Field>
              <Field label="층">
                <input className="input" value={zone.floor} onChange={(e) => updateZone(zi, { floor: e.target.value })} required />
              </Field>
            </div>

            <p className="mt-6 mb-2 text-sm font-medium">열</p>
            <ul className="space-y-2">
              {zone.rows.map((row, ri) => (
                <li key={ri} className="flex flex-wrap items-center gap-2">
                  <input className="input w-36" value={row.label} onChange={(e) => updateRow(zi, ri, { label: e.target.value })} placeholder="1열" aria-label="열 이름" required />
                  <input className="input w-24" type="number" min={1} value={row.from} onChange={(e) => updateRow(zi, ri, { from: e.target.value })} aria-label="시작 번호" required />
                  <span className="text-muted">~</span>
                  <input className="input w-24" type="number" min={1} value={row.to} onChange={(e) => updateRow(zi, ri, { to: e.target.value })} aria-label="끝 번호" required />
                  {zone.rows.length > 1 && (
                    <button type="button" aria-label="열 삭제" className="text-muted hover:text-foreground" onClick={() => updateZone(zi, { rows: zone.rows.filter((_, i) => i !== ri) })}>
                      <X size={18} strokeWidth={1.5} />
                    </button>
                  )}
                </li>
              ))}
            </ul>
            <button
              type="button"
              className="btn mt-3"
              onClick={() =>
                updateZone(zi, {
                  rows: [...zone.rows, { label: `${zone.rows.length + 1}열`, from: "1", to: "10" }],
                })
              }
            >
              <Plus size={16} strokeWidth={1.5} />열 추가
            </button>
          </Panel>
        ))}

        <button type="button" className="btn" onClick={() => setZones([...zones, emptyZone()])}>
          <Plus size={16} strokeWidth={1.5} />
          구역 추가
        </button>

        <div className="flex items-center justify-between border-t border-line pt-6">
          <p className="text-secondary">
            구역 {zones.length}개 · 좌석 <span className="font-semibold text-foreground">{totalSeats.toLocaleString("ko-KR")}</span>석
          </p>
          <button type="submit" className="btn btn-primary btn-lg" disabled={busy}>
            {busy ? "등록 중" : "공연장 등록"}
          </button>
        </div>
      </form>
    </>
  );
}
