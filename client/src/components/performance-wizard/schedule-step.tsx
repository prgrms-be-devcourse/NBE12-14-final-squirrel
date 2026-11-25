"use client";

import { Plus, X } from "lucide-react";
import { useState } from "react";
import { ApiError, api } from "@/lib/api";
import { dateTimeText } from "@/lib/format";
import { Field, Panel } from "@/components/console";
import type { StepProps } from "@/components/performance-wizard/types";

// 서버 규칙(api-spec SLR-12)의 "임시" 값. 서버가 바뀌면 같이 고친다. 여기서는 미리 알려 주기만 하고 막지는 않는다.
const MIN_LEAD_HOURS = 3;
const TURNAROUND_MINUTES = 60;
const MAX_CANDIDATES = 60;

const WEEKDAYS = ["일", "월", "화", "수", "목", "금", "토"];
const HOUR = 3_600_000;
const MINUTE = 60_000;

type Candidate = { value: string; error?: string };

const pad = (n: number) => String(n).padStart(2, "0");
// <input type="datetime-local"> 값 형식(yyyy-MM-ddTHH:mm)이다. 문자열 그대로 비교·정렬해도 시간 순서가 맞다.
const toValue = (d: Date) => `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}`;

export function ScheduleStep({ detail, onSaved, onNext, onBack }: StepProps) {
  const base = `/api/v1/seller/performances/${detail.performanceId}`;
  const active = detail.schedules.filter((s) => s.status !== "CANCELED").sort((a, b) => a.startAt.localeCompare(b.startAt));

  const [candidates, setCandidates] = useState<Candidate[]>([]);
  const [single, setSingle] = useState("");
  const [from, setFrom] = useState("");
  const [to, setTo] = useState("");
  const [time, setTime] = useState("19:00");
  const [days, setDays] = useState<number[]>([]);
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState<{ text: string; error: boolean } | null>(null);
  // 렌더 중에는 현재 시각을 읽을 수 없어서(렌더가 매번 같은 결과여야 한다) 담을 때마다 갱신해 둔다.
  const [now, setNow] = useState(() => Date.now());

  const duration = (detail.runningTime + TURNAROUND_MINUTES) * MINUTE;
  const ranges = [
    ...active.map((s) => new Date(s.startAt).getTime()),
    ...candidates.map((c) => new Date(c.value).getTime()),
  ].sort((a, b) => a - b);

  function add(values: string[]) {
    setNow(() => Date.now());
    const known = new Set([...candidates.map((c) => c.value), ...active.map((s) => s.startAt.slice(0, 16))]);
    const fresh = [...new Set(values)].filter((v) => !known.has(v));
    const merged = [...candidates, ...fresh.map((value) => ({ value }))].sort((a, b) => a.value.localeCompare(b.value));
    if (merged.length > MAX_CANDIDATES) {
      setMessage({ text: `한 번에 ${MAX_CANDIDATES}개까지 추가할 수 있습니다`, error: true });
      return;
    }
    setCandidates(merged);
    setMessage(
      values.length > fresh.length
        ? { text: `${values.length - fresh.length}개는 이미 있는 회차라 빼고 담았습니다`, error: false }
        : null,
    );
  }

  function addSingle() {
    if (!single) return;
    add([single]);
    setSingle("");
  }

  // 시작일~종료일 사이에서 고른 요일마다 같은 시각의 회차를 만든다.
  function generate() {
    if (!from || !to || days.length === 0 || !time) {
      setMessage({ text: "시작일, 종료일, 요일, 시각을 모두 정해 주세요", error: true });
      return;
    }
    const [fy, fm, fd] = from.split("-").map(Number);
    const [ty, tm, td] = to.split("-").map(Number);
    const [hh, mm] = time.split(":").map(Number);
    const last = new Date(ty, tm - 1, td);
    const values: string[] = [];
    for (let d = new Date(fy, fm - 1, fd); d <= last && values.length <= MAX_CANDIDATES; d.setDate(d.getDate() + 1)) {
      if (days.includes(d.getDay())) values.push(toValue(new Date(d.getFullYear(), d.getMonth(), d.getDate(), hh, mm)));
    }
    if (values.length === 0) {
      setMessage({ text: "고른 기간에 해당하는 날이 없습니다", error: true });
      return;
    }
    add(values);
  }

  // 미리 알려 줄 수 있는 문제. 막지는 않고 서버가 최종으로 판단한다.
  function warningOf(value: string): string | null {
    const start = new Date(value).getTime();
    if (start <= now) return "지난 시각입니다";
    if (detail.saleOpenAt && start <= new Date(detail.saleOpenAt).getTime()) return "예매 오픈보다 늦어야 합니다";
    if (start < now + MIN_LEAD_HOURS * HOUR) return `지금부터 ${MIN_LEAD_HOURS}시간 뒤부터 가능합니다`;
    if (ranges.some((other) => other !== start && Math.abs(other - start) < duration)) {
      return "다른 회차와 시간이 겹칩니다 (공연 시간 + 정리 1시간)";
    }
    return null;
  }
  const blocked = candidates.some((c) => new Date(c.value).getTime() <= now);

  async function submit(andNext: boolean) {
    setMessage(null);
    setNow(() => Date.now());
    if (candidates.length === 0) {
      if (andNext) onNext();
      return;
    }
    setBusy(true);
    const failed: Candidate[] = [];
    let ok = 0;
    // 서버가 공연 단위 락을 잡아서 한 건씩 차례로 보낸다. 실패한 회차는 사유와 함께 남겨 둔다.
    for (const c of candidates) {
      try {
        await api(`${base}/schedules`, { method: "POST", body: { startAt: `${c.value}:00` } });
        ok += 1;
      } catch (error) {
        failed.push({ value: c.value, error: error instanceof ApiError ? error.message : "추가하지 못했습니다" });
      }
    }
    setBusy(false);
    setCandidates(failed);
    onSaved();
    if (failed.length === 0) {
      setMessage({ text: `${ok}개 회차를 추가했습니다`, error: false });
      if (andNext) onNext();
    } else {
      setMessage({ text: `${ok}개 추가했고 ${failed.length}개는 추가하지 못했습니다. 사유를 확인해 주세요`, error: true });
    }
  }

  return (
    <Panel title="회차" sub="한 날짜씩 담거나, 기간과 요일로 여러 회차를 한꺼번에 만들 수 있습니다">
      <div className="grid gap-6 lg:grid-cols-2">
        <div className="space-y-6">
          <div>
            <p className="mb-2 text-sm font-medium">날짜 하나 담기</p>
            <div className="flex gap-2">
              <input className="input" type="datetime-local" aria-label="공연 일시" value={single} onChange={(e) => setSingle(e.target.value)} />
              <button type="button" className="btn shrink-0" disabled={!single} onClick={addSingle}>
                <Plus size={16} strokeWidth={1.5} />
                담기
              </button>
            </div>
          </div>

          <div>
            <p className="mb-2 text-sm font-medium">기간과 요일로 만들기</p>
            <div className="grid gap-3 sm:grid-cols-3">
              <Field label="시작일">
                <input className="input" type="date" value={from} onChange={(e) => setFrom(e.target.value)} />
              </Field>
              <Field label="종료일">
                <input className="input" type="date" value={to} min={from} onChange={(e) => setTo(e.target.value)} />
              </Field>
              <Field label="시각">
                <input className="input" type="time" value={time} onChange={(e) => setTime(e.target.value)} />
              </Field>
            </div>
            <div className="mt-3 flex flex-wrap gap-2" role="group" aria-label="요일">
              {WEEKDAYS.map((label, day) => (
                <button
                  key={label}
                  type="button"
                  className="chip"
                  aria-pressed={days.includes(day)}
                  onClick={() => setDays(days.includes(day) ? days.filter((d) => d !== day) : [...days, day])}
                >
                  {label}
                </button>
              ))}
            </div>
            <button type="button" className="btn mt-3" onClick={generate}>
              회차 목록 만들기
            </button>
          </div>
        </div>

        <div className="space-y-6">
          <div>
            <p className="mb-2 text-sm font-medium">추가할 회차 ({candidates.length})</p>
            {candidates.length === 0 ? (
              <p className="text-sm text-muted">왼쪽에서 담으면 여기에 모입니다. 추가를 눌러야 저장됩니다</p>
            ) : (
              <ul className="max-h-80 divide-y divide-line overflow-y-auto rounded-xl border border-line">
                {candidates.map((c) => {
                  const warning = c.error ?? warningOf(c.value);
                  return (
                    <li key={c.value} className="flex items-start justify-between gap-3 px-4 py-3 text-sm">
                      <div>
                        <p className="font-medium tabular-nums">{dateTimeText(`${c.value}:00`)}</p>
                        {warning && <p className="mt-0.5 text-xs text-accent-strong">{warning}</p>}
                      </div>
                      <button
                        type="button"
                        aria-label={`${c.value} 빼기`}
                        className="mt-0.5 text-muted hover:text-foreground"
                        onClick={() => setCandidates(candidates.filter((x) => x.value !== c.value))}
                      >
                        <X size={18} strokeWidth={1.5} />
                      </button>
                    </li>
                  );
                })}
              </ul>
            )}
          </div>

          <div>
            <p className="mb-2 text-sm font-medium">등록된 회차 ({active.length})</p>
            {active.length === 0 ? (
              <p className="text-sm text-muted">아직 없습니다. 공개하려면 하나 이상 필요합니다</p>
            ) : (
              <ul className="max-h-60 divide-y divide-line overflow-y-auto rounded-xl border border-line">
                {active.map((s) => (
                  <li key={s.scheduleId} className="px-4 py-3 text-sm tabular-nums">
                    {dateTimeText(s.startAt)}
                  </li>
                ))}
              </ul>
            )}
          </div>
        </div>
      </div>

      {message && (
        <p role={message.error ? "alert" : "status"} className={`mt-5 text-sm ${message.error ? "text-accent-strong" : "text-ok"}`}>
          {message.text}
        </p>
      )}
      <div className="mt-6 flex justify-between gap-2">
        <button type="button" className="btn" disabled={busy} onClick={onBack}>
          이전
        </button>
        <div className="flex gap-2">
          {candidates.length > 0 && (
            <button type="button" className="btn" disabled={busy || blocked} onClick={() => submit(false)}>
              {candidates.length}개 추가
            </button>
          )}
          <button type="button" className="btn btn-primary" disabled={busy || blocked} onClick={() => submit(true)}>
            {busy ? "추가 중" : candidates.length > 0 ? "추가하고 다음" : "다음"}
          </button>
        </div>
      </div>
    </Panel>
  );
}
