"use client";

import { Download, Upload } from "lucide-react";

import { useState } from "react";
import { useRouteParams } from "@/lib/route-params";
import { ApiError, api, errorText, useApi } from "@/lib/api";
import { CARRIERS, carrierName, readCsvFile, targetsToCsv, toFileMessage, toShipments } from "@/lib/delivery";
import { dateTimeText } from "@/lib/format";
import type { DeliveryTarget, SellerPerformanceDetail, ShipmentResult } from "@/lib/types";
import { ConsoleTitle, Field, Modal, Panel, Table } from "@/components/console";
import { Empty, ErrorBox, Loading, Pagination } from "@/components/ui";

// 배송 대상은 서버가 한 번에 모두 주므로 화면에서만 나눠 보여 준다.
const PAGE_SIZE = 50;

type Uploaded = { result: ShipmentResult; lines: number[] };

function download(name: string, text: string) {
  const url = URL.createObjectURL(new Blob([text], { type: "text/csv;charset=utf-8" }));
  const link = document.createElement("a");
  link.href = url;
  link.download = name;
  link.click();
  URL.revokeObjectURL(url);
}

function CarrierSelect({ value, onChange }: { value: string; onChange: (code: string) => void }) {
  return (
    <select className="input" value={value} onChange={(e) => onChange(e.target.value)}>
      {CARRIERS.map((c) => (
        <option key={c.code} value={c.code}>
          {c.name}
        </option>
      ))}
    </select>
  );
}

export default function DeliveriesPage() {
  const { id } = useRouteParams<{ id: string }>("/seller/performances/[id]/deliveries");
  const base = `/api/v1/seller/performances/${id}`;
  const [shipped, setShipped] = useState(false);
  const [scheduleId, setScheduleId] = useState("");
  const [page, setPage] = useState(0);
  const [editing, setEditing] = useState<DeliveryTarget | null>(null);
  const detail = useApi<SellerPerformanceDetail>(base);
  const targets = useApi<DeliveryTarget[]>(`${base}/deliveries`, { shipped: String(shipped), scheduleId });

  const list = targets.data ?? [];
  const totalPages = Math.ceil(list.length / PAGE_SIZE);
  // 송장을 넣으면 목록이 줄어서 보던 페이지가 없어질 수 있다.
  const current = Math.min(page, Math.max(totalPages - 1, 0));

  return (
    <>
      <ConsoleTitle
        title="배송 관리"
        sub={detail.data?.title}
        back={{ href: `/seller/performances/${id}`, label: "공연 상세" }}
      />

      <UploadPanel path={`${base}/deliveries`} onUploaded={targets.reload} />

      <div className="mt-8 mb-5 flex flex-wrap items-center gap-2">
        {[false, true].map((value) => (
          <button
            key={String(value)}
            type="button"
            className="chip"
            aria-pressed={shipped === value}
            onClick={() => {
              setShipped(value);
              setPage(0);
            }}
          >
            {value ? "발송 완료" : "미발송"}
          </button>
        ))}
        <select
          className="input h-10 w-56"
          value={scheduleId}
          aria-label="회차"
          onChange={(e) => {
            setScheduleId(e.target.value);
            setPage(0);
          }}
        >
          <option value="">모든 회차</option>
          {detail.data?.schedules.map((s) => (
            <option key={s.scheduleId} value={s.scheduleId}>
              {dateTimeText(s.startAt)}
            </option>
          ))}
        </select>
        <button
          type="button"
          className="btn ml-auto"
          disabled={list.length === 0}
          onClick={() => download(`배송대상_${id}_${shipped ? "발송완료" : "미발송"}.csv`, targetsToCsv(list))}
        >
          <Download size={16} strokeWidth={1.5} />
          CSV 내려받기{list.length > 0 && ` (${list.length}건)`}
        </button>
      </div>

      {targets.loading && <Loading />}
      {targets.error && <ErrorBox error={targets.error} onRetry={targets.reload} />}
      {targets.data && list.length === 0 && (
        <Empty>{shipped ? "발송한 예매가 없습니다" : "보낼 예매가 없습니다"}</Empty>
      )}
      {list.length > 0 && (
        <>
          <Table head={["예매번호", "회차", "받는 사람", "매수", "송장", ""]}>
            {list.slice(current * PAGE_SIZE, (current + 1) * PAGE_SIZE).map((t) => (
              <tr key={t.bookingId}>
                <td className="px-5 py-3 font-medium">{t.bookingNo}</td>
                <td className="px-5 py-3 text-muted">{dateTimeText(t.scheduleStartAt)}</td>
                <td className="px-5 py-3">
                  {t.recipientName} · {t.recipientPhone}
                  <span className="block text-xs text-muted">{t.recipientAddress}</span>
                </td>
                <td className="px-5 py-3">{t.seatCount}매</td>
                <td className="px-5 py-3">
                  {t.carrier ? (
                    <>
                      {carrierName(t.carrier)} {t.trackingNo}
                      {t.shippedAt && (
                        <span className="block text-xs text-muted">발송 {dateTimeText(t.shippedAt)}</span>
                      )}
                    </>
                  ) : (
                    "-"
                  )}
                </td>
                <td className="px-5 py-3 text-right">
                  <button type="button" className="btn whitespace-nowrap" onClick={() => setEditing(t)}>
                    {t.carrier ? "송장 수정" : "송장 입력"}
                  </button>
                </td>
              </tr>
            ))}
          </Table>
          <Pagination page={current} totalPages={totalPages} onChange={setPage} />
        </>
      )}

      {editing && (
        <TrackingModal
          target={editing}
          onClose={() => setEditing(null)}
          onSaved={() => {
            setEditing(null);
            targets.reload();
          }}
        />
      )}
    </>
  );
}

function UploadPanel({ path, onUploaded }: { path: string; onUploaded: () => void }) {
  const [defaultCarrier, setDefaultCarrier] = useState(CARRIERS[0].code);
  const [text, setText] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [uploaded, setUploaded] = useState<Uploaded | null>(null);
  const [busy, setBusy] = useState(false);
  // 올린 뒤에는 파일 칸의 이름도 지운다.
  const [inputKey, setInputKey] = useState(0);
  const file = text === null ? null : toShipments(text, defaultCarrier);

  async function choose(chosen: File | undefined) {
    setError(null);
    setUploaded(null);
    setText(null);
    if (!chosen) return;
    try {
      setText(await readCsvFile(chosen));
    } catch {
      setError("파일을 읽지 못했습니다");
    }
  }

  async function upload() {
    if (!file) return;
    setBusy(true);
    setError(null);
    try {
      const result = await api<ShipmentResult>(path, { method: "PUT", body: { items: file.items } });
      setUploaded({ result, lines: file.lines });
      setText(null);
      setInputKey((key) => key + 1);
      onUploaded();
    } catch (e) {
      setError(toFileMessage(errorText(e, "올리지 못했습니다"), file.lines));
    } finally {
      setBusy(false);
    }
  }

  return (
    <Panel
      title="송장 한꺼번에 올리기"
      sub="아래에서 내려받은 CSV 의 송장번호 칸을 채워 올려 주세요. 한 번에 1,000건까지이고, 이미 입력한 송장은 새 값으로 바뀝니다. 엑셀에서는 송장번호 칸을 '텍스트' 서식으로 두어야 번호가 바뀌지 않습니다"
    >
      <div className="grid gap-4 sm:grid-cols-[200px_1fr]">
        <Field label="택배사 칸이 비어 있으면">
          <CarrierSelect value={defaultCarrier} onChange={setDefaultCarrier} />
        </Field>
        <Field label="CSV 파일">
          <input
            key={inputKey}
            type="file"
            accept=".csv,text/csv"
            className="input py-2"
            disabled={busy}
            // 값을 비워 두지 않으면 고친 파일을 같은 이름으로 다시 골라도 change 가 일어나지 않아 옛 내용을 올리게 된다.
            onClick={(e) => {
              e.currentTarget.value = "";
              setText(null);
            }}
            onChange={(e) => choose(e.target.files?.[0])}
          />
        </Field>
      </div>

      {file && file.errors.length > 0 && (
        <ul role="alert" className="mt-4 rounded-xl bg-accent-soft px-4 py-3 text-sm text-accent-strong">
          {file.errors.slice(0, 10).map((message) => (
            <li key={message}>{message}</li>
          ))}
          {file.errors.length > 10 && <li>그 밖에 {file.errors.length - 10}건</li>}
        </ul>
      )}
      {file && file.errors.length === 0 && (
        <div className="mt-4 flex flex-wrap items-center justify-between gap-3 text-sm">
          <p>
            보낼 송장 {file.items.length}건
            {file.blank > 0 && ` · 송장번호가 빈 ${file.blank}줄은 건너뜁니다`}
          </p>
          <button type="button" className="btn btn-primary" disabled={busy} onClick={upload}>
            <Upload size={16} strokeWidth={1.5} />
            {file.items.length}건 올리기
          </button>
        </div>
      )}
      {error && (
        <p role="alert" className="mt-4 rounded-xl bg-accent-soft px-4 py-3 text-sm text-accent-strong">
          {error}
        </p>
      )}
      {uploaded && (
        <div role="status" className="mt-4 text-sm">
          <p className="mb-3 font-medium">
            {uploaded.result.total}건 중 {uploaded.result.succeeded}건을 반영했습니다
            {uploaded.result.failed.length > 0 && `. ${uploaded.result.failed.length}건은 반영하지 못했습니다`}
          </p>
          {uploaded.result.failed.length > 0 && (
            <Table head={["파일 줄", "예매번호", "이유"]}>
              {uploaded.result.failed.map((f) => (
                <tr key={f.row}>
                  <td className="px-5 py-3">{uploaded.lines[f.row]}</td>
                  <td className="px-5 py-3">{f.bookingNo}</td>
                  <td className="px-5 py-3">{f.message}</td>
                </tr>
              ))}
            </Table>
          )}
        </div>
      )}
    </Panel>
  );
}

function TrackingModal({
  target,
  onClose,
  onSaved,
}: {
  target: DeliveryTarget;
  onClose: () => void;
  onSaved: () => void;
}) {
  const [carrier, setCarrier] = useState(target.carrier ?? CARRIERS[0].code);
  const [trackingNo, setTrackingNo] = useState(target.trackingNo ?? "");
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  async function save(event: React.FormEvent) {
    event.preventDefault();
    setBusy(true);
    setError(null);
    try {
      await api(`/api/v1/seller/bookings/${target.bookingId}/delivery`, {
        method: "PUT",
        body: { carrier, trackingNo: trackingNo.trim() },
      });
      onSaved();
    } catch (e) {
      // 입력값 오류(400)는 서버 메시지의 필드 이름을 빼고 보여 준다.
      setError(
        e instanceof ApiError && e.status === 400
          ? e.message.replace(/\b(carrier|trackingNo): /g, "")
          : errorText(e, "저장하지 못했습니다"),
      );
      setBusy(false);
    }
  }

  return (
    <Modal title={`송장 입력 · ${target.bookingNo}`} onClose={onClose}>
      <form onSubmit={save} className="space-y-4">
        <p className="text-sm text-secondary">
          {target.recipientName} · {target.recipientAddress}
        </p>
        <Field label="택배사">
          <CarrierSelect value={carrier} onChange={setCarrier} />
        </Field>
        <Field label="송장번호" hint="영문·숫자·하이픈 1~50자">
          <input className="input" value={trackingNo} onChange={(e) => setTrackingNo(e.target.value)} required />
        </Field>
        {error && (
          <p role="alert" className="text-sm text-accent-strong">
            {error}
          </p>
        )}
        <button type="submit" className="btn btn-primary btn-lg w-full" disabled={busy}>
          저장
        </button>
      </form>
    </Modal>
  );
}
