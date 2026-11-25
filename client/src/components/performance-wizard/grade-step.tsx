"use client";

import { Plus, X } from "lucide-react";
import { useState } from "react";
import { ApiError, api } from "@/lib/api";
import { GRADE_CODES, gradeLabel, gradeMapColor } from "@/lib/format";
import { Panel } from "@/components/console";
import type { StepProps } from "@/components/performance-wizard/types";

type Row = {
  key: number;
  // 이미 서버에 있는 등급이면 번호가 있다. 등급 코드는 바꿀 수 없고 가격만 바꾼다.
  gradeId: number | null;
  name: string;
  price: string;
  original: number | null;
};

const MAX_PRICE = 10_000_000;

// 등급과 가격을 여러 줄로 한 번에 입력한다. (SLR-09·10, 등급 삭제 API 는 없다)
export function GradeStep({ detail, grades, onSaved, onNext, onBack }: StepProps) {
  const base = `/api/v1/seller/performances/${detail.performanceId}`;
  const locked = grades.locked;

  const [rows, setRows] = useState<Row[]>(() =>
    [...grades.grades]
      .sort((a, b) => b.price - a.price)
      .map((g) => ({ key: g.gradeId, gradeId: g.gradeId, name: g.name, price: String(g.price), original: g.price })),
  );
  const [nextKey, setNextKey] = useState(-1);
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState<string | null>(null);

  const used = rows.map((r) => r.name);
  const available = GRADE_CODES.filter((code) => !used.includes(code));

  function addRow() {
    if (available.length === 0) return;
    setRows([...rows, { key: nextKey, gradeId: null, name: available[0], price: "", original: null }]);
    setNextKey(nextKey - 1);
  }

  const update = (key: number, patch: Partial<Row>) => setRows(rows.map((r) => (r.key === key ? { ...r, ...patch } : r)));

  const priceError = (r: Row): string | null => {
    if (r.price.trim() === "") return "가격을 입력해 주세요";
    const n = Number(r.price);
    if (!Number.isInteger(n) || n < 0 || n > MAX_PRICE) return "0원 이상 1,000만원 이하의 정수로 입력해 주세요";
    return null;
  };
  const changed = (r: Row) => r.gradeId === null || Number(r.price) !== r.original;
  const hasError = rows.some((r) => priceError(r) !== null);

  async function save(andNext: boolean) {
    setMessage(null);
    if (rows.length === 0) {
      setMessage("등급을 하나 이상 추가해 주세요");
      return;
    }
    if (hasError) {
      setMessage("가격을 확인해 주세요");
      return;
    }
    setBusy(true);
    try {
      // 한 줄씩 차례로 저장한다. 서버가 공연 단위 락을 잡아서 동시에 보내면 서로 기다리다 충돌한다.
      for (const r of rows.filter(changed)) {
        const body = { name: r.name, price: Number(r.price) };
        if (r.gradeId === null) await api(`${base}/grades`, { method: "POST", body });
        else await api(`${base}/grades/${r.gradeId}`, { method: "PUT", body });
      }
      onSaved();
      if (andNext) onNext();
    } catch (error) {
      setMessage(error instanceof ApiError ? error.message : "저장하지 못했습니다");
      // 중간에 실패해도 앞의 줄은 저장됐으니 서버 기준으로 다시 읽어 화면을 맞춘다.
      onSaved();
    } finally {
      setBusy(false);
    }
  }

  return (
    <Panel
      title="등급과 가격"
      sub={locked ? "판매 좌석을 만든 뒤에는 바꿀 수 없습니다" : "필요한 등급을 한꺼번에 추가하세요. 구역에 붙이는 것은 다음 단계입니다"}
      actions={
        !locked && (
          <button type="button" className="btn" disabled={available.length === 0 || busy} onClick={addRow}>
            <Plus size={16} strokeWidth={1.5} />
            등급 추가
          </button>
        )
      }
    >
      {rows.length === 0 && <p className="text-sm text-muted">등급이 없습니다. 등급 추가를 눌러 VIP석, R석 같은 등급을 만들어 주세요</p>}
      <ul className="space-y-3">
        {rows.map((r) => {
          const error = priceError(r);
          return (
            <li key={r.key}>
              <div className="flex flex-wrap items-center gap-3">
                <i className="size-4 shrink-0 rounded-sm" style={{ backgroundColor: gradeMapColor(r.name) }} />
                {r.gradeId === null ? (
                  <select
                    className="input h-11 w-44"
                    aria-label="등급"
                    value={r.name}
                    onChange={(e) => update(r.key, { name: e.target.value })}
                  >
                    {GRADE_CODES.filter((code) => code === r.name || !used.includes(code)).map((code) => (
                      <option key={code} value={code}>
                        {gradeLabel(code)}
                      </option>
                    ))}
                  </select>
                ) : (
                  <span className="w-44 text-sm font-medium">{gradeLabel(r.name)}</span>
                )}
                <input
                  className="input h-11 w-44"
                  aria-label={`${gradeLabel(r.name)} 가격`}
                  type="number"
                  min={0}
                  max={MAX_PRICE}
                  step={1000}
                  placeholder="가격 (원)"
                  disabled={locked}
                  value={r.price}
                  onChange={(e) => update(r.key, { price: e.target.value })}
                />
                {r.gradeId === null && (
                  <button
                    type="button"
                    aria-label={`${gradeLabel(r.name)} 줄 삭제`}
                    className="text-muted hover:text-foreground"
                    onClick={() => setRows(rows.filter((x) => x.key !== r.key))}
                  >
                    <X size={18} strokeWidth={1.5} />
                  </button>
                )}
              </div>
              {error && r.price !== "" && <p className="mt-1 pl-7 text-xs text-accent-strong">{error}</p>}
            </li>
          );
        })}
      </ul>
      <p className="mt-4 text-xs text-muted">같은 공연에는 같은 등급을 하나만 둘 수 있고, 만든 등급은 지울 수 없습니다. 화면 이름과 색은 등급마다 정해져 있습니다</p>

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
          {!locked && (
            <button type="button" className="btn" disabled={busy || !rows.some(changed)} onClick={() => save(false)}>
              저장
            </button>
          )}
          <button type="button" className="btn btn-primary" disabled={busy} onClick={() => (locked ? onNext() : save(true))}>
            {busy ? "저장 중" : locked ? "다음" : "저장하고 다음"}
          </button>
        </div>
      </div>
    </Panel>
  );
}
