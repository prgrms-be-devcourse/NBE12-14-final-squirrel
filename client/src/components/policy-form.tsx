"use client";

import { Plus, X } from "lucide-react";
import { useState } from "react";
import { ApiError } from "@/lib/api";
import type { CancelRule, PolicyDetail } from "@/lib/types";
import { Field, Panel } from "@/components/console";

type RuleRow = { daysFrom: string; daysTo: string; feeRate: string; cancelable: boolean };

const toRow = (r: CancelRule): RuleRow => ({
  daysFrom: String(r.daysFrom),
  daysTo: r.daysTo === null ? "" : String(r.daysTo),
  feeRate: String(r.feeRate),
  cancelable: r.cancelable,
});

// 서비스 기본 규정과 같은 모양으로 시작해서 고쳐 쓴다
const DEFAULT_ROWS: RuleRow[] = [
  { daysFrom: "7", daysTo: "", feeRate: "0", cancelable: true },
  { daysFrom: "3", daysTo: "6", feeRate: "10", cancelable: true },
  { daysFrom: "1", daysTo: "2", feeRate: "20", cancelable: true },
  { daysFrom: "0", daysTo: "0", feeRate: "0", cancelable: false },
];

// 취소 규정 만들기(SLR-21)와 수정(SLR-22)이 같이 쓰는 입력 화면
export function PolicyFormView({
  policy,
  readOnly,
  onSubmit,
}: {
  policy?: PolicyDetail;
  readOnly?: boolean;
  onSubmit: (body: { name: string; rules: CancelRule[] }) => Promise<void>;
}) {
  const [name, setName] = useState(policy?.name ?? "");
  const [rows, setRows] = useState<RuleRow[]>(policy ? policy.rules.map(toRow) : DEFAULT_ROWS);
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState<string | null>(null);

  const update = (index: number, patch: Partial<RuleRow>) =>
    setRows(rows.map((row, i) => (i === index ? { ...row, ...patch } : row)));

  async function submit(event: React.FormEvent) {
    event.preventDefault();
    setBusy(true);
    setMessage(null);
    try {
      await onSubmit({
        name,
        rules: rows.map((r) => ({
          daysFrom: Number(r.daysFrom),
          daysTo: r.daysTo.trim() === "" ? null : Number(r.daysTo),
          feeRate: Number(r.feeRate),
          cancelable: r.cancelable,
        })),
      });
    } catch (error) {
      setMessage(error instanceof ApiError ? error.message : "저장하지 못했습니다");
    } finally {
      setBusy(false);
    }
  }

  const cell = "input h-10 w-24 px-3 disabled:opacity-60";

  return (
    <form onSubmit={submit} className="space-y-6">
      <Panel title="규정 이름">
        <Field label="이름" hint="같은 사업체 안에서 겹치지 않아야 하고 50자까지 쓸 수 있습니다">
          <input className="input max-w-md" value={name} onChange={(e) => setName(e.target.value)} maxLength={50} disabled={readOnly} required />
        </Field>
      </Panel>

      <Panel
        title="구간"
        sub="구간은 양 끝 날짜를 포함합니다. 가장 가까운 구간은 공연 당일(0일 전)부터 시작하고, 빈 날짜나 겹치는 날짜가 없어야 합니다"
      >
        <div className="overflow-x-auto">
          <table className="w-full min-w-[560px] text-sm">
            <thead className="text-left text-secondary">
              <tr>
                <th className="pb-3 font-medium">공연 며칠 전부터</th>
                <th className="pb-3 font-medium">며칠 전까지</th>
                <th className="pb-3 font-medium">수수료율 (%)</th>
                <th className="pb-3 font-medium">취소 가능</th>
                <th className="pb-3" />
              </tr>
            </thead>
            <tbody>
              {rows.map((row, i) => (
                <tr key={i}>
                  <td className="pr-3 pb-3">
                    <input className={cell} type="number" min={0} max={90} value={row.daysFrom} onChange={(e) => update(i, { daysFrom: e.target.value })} disabled={readOnly} required aria-label="시작 일수" />
                  </td>
                  <td className="pr-3 pb-3">
                    <input className={cell} type="number" min={0} max={90} value={row.daysTo} onChange={(e) => update(i, { daysTo: e.target.value })} placeholder="제한 없음" disabled={readOnly} aria-label="끝 일수" />
                  </td>
                  <td className="pr-3 pb-3">
                    <input className={cell} type="number" min={0} max={100} value={row.feeRate} onChange={(e) => update(i, { feeRate: e.target.value })} disabled={readOnly || !row.cancelable} required aria-label="수수료율" />
                  </td>
                  <td className="pr-3 pb-3">
                    <input type="checkbox" className="size-5" checked={row.cancelable} onChange={(e) => update(i, { cancelable: e.target.checked })} disabled={readOnly} aria-label="취소 가능" />
                  </td>
                  <td className="pb-3 text-right">
                    {!readOnly && rows.length > 1 && (
                      <button type="button" aria-label="구간 삭제" className="text-muted hover:text-foreground" onClick={() => setRows(rows.filter((_, idx) => idx !== i))}>
                        <X size={18} strokeWidth={1.5} />
                      </button>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
        {!readOnly && rows.length < 10 && (
          <button type="button" className="btn mt-2" onClick={() => setRows([...rows, { daysFrom: "", daysTo: "", feeRate: "0", cancelable: true }])}>
            <Plus size={16} strokeWidth={1.5} />
            구간 추가
          </button>
        )}
      </Panel>

      {message && <p className="text-sm text-accent-strong">{message}</p>}
      {!readOnly && (
        <div className="flex justify-end">
          <button type="submit" className="btn btn-primary btn-lg" disabled={busy}>
            {busy ? "저장 중" : "저장"}
          </button>
        </div>
      )}
    </form>
  );
}
