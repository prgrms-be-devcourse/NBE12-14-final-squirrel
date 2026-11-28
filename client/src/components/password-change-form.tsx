"use client";

import { useState } from "react";
import { ApiError, api } from "@/lib/api";

// 로그인한 상태의 비밀번호 변경. (api-spec AUTH-29·30) 예매자와 판매자는 부르는 주소만 다르다.
// 서버 오류는 원인이 된 입력칸 아래에 보여 주고, 성공하면 지금 화면에 그대로 둔다. (로그인도 유지된다)

type Field = "currentPassword" | "newPassword" | "newPasswordConfirm";
type Problem = { field: Field | null; text: string };

const FIELDS: { name: Field; label: string; autoComplete: string }[] = [
  { name: "currentPassword", label: "현재 비밀번호", autoComplete: "current-password" },
  { name: "newPassword", label: "새 비밀번호 (8~25자, 영문·숫자·특수문자)", autoComplete: "new-password" },
  { name: "newPasswordConfirm", label: "새 비밀번호 확인", autoComplete: "new-password" },
];

const ERROR_FIELD: Record<string, Field> = {
  AUTH311: "currentPassword",
  AUTH312: "newPassword",
  AUTH005: "newPasswordConfirm",
};

const EMPTY: Record<Field, string> = { currentPassword: "", newPassword: "", newPasswordConfirm: "" };

function problemOf(error: unknown): Problem {
  if (!(error instanceof ApiError)) return { field: null, text: "비밀번호를 바꾸지 못했습니다" };
  // 서버 메시지는 로그인 기준이라, 변경 화면에 맞는 말로 바꾸고 로그인도 같이 막힌다는 것을 알린다.
  if (error.code === "AUTH004") {
    return { field: null, text: "여러 번 틀려서 잠시 뒤 다시 시도해 주세요. 그동안에는 새로 로그인할 수도 없습니다" };
  }
  return { field: ERROR_FIELD[error.code] ?? null, text: error.message };
}

export function PasswordChangeForm({ endpoint }: { endpoint: "/api/v1/users/me/password" | "/api/v1/seller/me/password" }) {
  const [values, setValues] = useState(EMPTY);
  const [busy, setBusy] = useState(false);
  const [problem, setProblem] = useState<Problem | null>(null);
  const [done, setDone] = useState(false);

  async function submit(event: React.FormEvent) {
    event.preventDefault();
    setDone(false);
    if (values.newPassword !== values.newPasswordConfirm) {
      setProblem({ field: "newPasswordConfirm", text: "비밀번호와 비밀번호 확인이 다릅니다" });
      return;
    }
    setBusy(true);
    setProblem(null);
    try {
      await api(endpoint, { method: "PATCH", body: values });
      // 바뀐 비밀번호가 화면에 남지 않게 지운다.
      setValues(EMPTY);
      setDone(true);
    } catch (error) {
      setProblem(problemOf(error));
    } finally {
      setBusy(false);
    }
  }

  return (
    <form onSubmit={submit} className="card space-y-4 p-6">
      {FIELDS.map(({ name, label, autoComplete }) => (
        <div key={name}>
          <label className="label" htmlFor={name}>
            {label}
          </label>
          <input
            id={name}
            type="password"
            autoComplete={autoComplete}
            className="input"
            value={values[name]}
            aria-invalid={problem?.field === name}
            aria-describedby={problem?.field === name ? `${name}-error` : undefined}
            onChange={(e) => setValues((previous) => ({ ...previous, [name]: e.target.value }))}
            required
          />
          {problem?.field === name && (
            <p id={`${name}-error`} role="alert" className="mt-1 text-sm text-accent-strong">
              {problem.text}
            </p>
          )}
        </div>
      ))}
      {problem && problem.field === null && (
        <p role="alert" className="text-sm text-accent-strong">
          {problem.text}
        </p>
      )}
      {done && (
        <p role="status" className="rounded-xl bg-[var(--ok-soft)] px-4 py-3 text-sm text-ok">
          비밀번호가 변경되었습니다
        </p>
      )}
      <div className="flex justify-end">
        <button type="submit" className="btn btn-primary" disabled={busy}>
          {busy ? "변경 중" : "비밀번호 변경"}
        </button>
      </div>
    </form>
  );
}
