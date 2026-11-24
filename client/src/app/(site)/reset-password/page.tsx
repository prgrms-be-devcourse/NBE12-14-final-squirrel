"use client";

import Link from "next/link";
import { useEffect, useRef, useState } from "react";
import { ApiError, api } from "@/lib/api";
import type { PasswordResetValidity } from "@/lib/types";
import { Loading } from "@/components/ui";

// 메일 링크({프론트 주소}/reset-password#token=…)가 여는 화면. (api-spec AUTH-21·22)
// 토큰이 주소와 서버 로그에 남지 않도록 쿼리가 아니라 # 뒤에 담겨 오고, 읽은 뒤 주소창에서 지운다.
// 먼저 링크가 유효한지만 확인한다. 이 확인은 토큰을 소모하지 않아서 메일 미리보기가 열어도 링크가 죽지 않는다.

type Phase =
  | { kind: "checking" }
  | { kind: "expired" }
  | { kind: "form"; token: string; title: string }
  | { kind: "done" };

const TITLE = { USER: "예매자 비밀번호 재설정", SELLER: "판매자 비밀번호 재설정" } as const;

function readToken(): string | null {
  const params = new URLSearchParams(window.location.hash.replace(/^#/, ""));
  return params.get("token");
}

export default function ResetPasswordPage() {
  const [phase, setPhase] = useState<Phase>({ kind: "checking" });
  const [password, setPassword] = useState("");
  const [passwordConfirm, setPasswordConfirm] = useState("");
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState<string | null>(null);
  // 개발 모드(StrictMode)는 효과를 두 번 돌린다. 주소창의 토큰을 첫 번째에서 지우면 두 번째가 토큰 없음으로 읽어
  // 유효한 링크가 만료로 보이므로, 처음 읽은 값을 ref 에 두고 다시 읽지 않는다.
  const tokenRef = useRef<string | null | undefined>(undefined);

  useEffect(() => {
    let cancelled = false;
    async function check() {
      if (tokenRef.current === undefined) {
        tokenRef.current = readToken();
        // 읽은 뒤 주소창의 토큰은 지운다.
        window.history.replaceState(null, "", window.location.pathname);
      }
      const token = tokenRef.current;
      if (!token) {
        setPhase({ kind: "expired" });
        return;
      }
      try {
        const result = await api<PasswordResetValidity>("/api/v1/auth/password-reset/validate", {
          method: "POST",
          body: { token },
        });
        if (cancelled) return;
        setPhase(
          result.valid && result.accountType && result.accountType !== "ADMIN"
            ? { kind: "form", token, title: TITLE[result.accountType] }
            : { kind: "expired" },
        );
      } catch {
        if (!cancelled) setPhase({ kind: "expired" });
      }
    }
    void check();
    return () => {
      cancelled = true;
    };
  }, []);

  async function submit(event: React.FormEvent) {
    event.preventDefault();
    if (phase.kind !== "form") return;
    if (password !== passwordConfirm) {
      setMessage("비밀번호와 비밀번호 확인이 다릅니다");
      return;
    }
    setBusy(true);
    setMessage(null);
    try {
      await api("/api/v1/auth/password-reset/confirm", {
        method: "POST",
        body: { token: phase.token, password, passwordConfirm },
      });
      // 자동 로그인은 하지 않는다. 로그인 화면으로 보낸다. 새 비밀번호는 화면에 남지 않게 지운다.
      setPassword("");
      setPasswordConfirm("");
      setPhase({ kind: "done" });
    } catch (error) {
      if (error instanceof ApiError && error.code === "AUTH309") setPhase({ kind: "expired" });
      else setMessage(error instanceof ApiError ? error.message : "비밀번호를 바꾸지 못했습니다");
    } finally {
      setBusy(false);
    }
  }

  if (phase.kind === "checking") return <Loading label="링크를 확인하는 중" />;

  if (phase.kind === "expired") {
    return (
      <div className="mx-auto max-w-sm">
        <div className="card space-y-4 p-6">
          <h1 className="text-xl font-semibold">링크를 사용할 수 없습니다</h1>
          <p className="text-sm text-secondary">만료되었거나 이미 사용한 링크입니다. 다시 요청해 주세요</p>
          <Link href="/forgot-password" className="btn btn-primary w-full">
            비밀번호 찾기
          </Link>
        </div>
      </div>
    );
  }

  if (phase.kind === "done") {
    return (
      <div className="mx-auto max-w-sm">
        <div className="card space-y-4 p-6">
          <h1 className="text-xl font-semibold">비밀번호를 바꿨습니다</h1>
          <p className="text-sm text-secondary">새 비밀번호로 로그인해 주세요</p>
          <Link href="/login" className="btn btn-primary w-full">
            로그인하러 가기
          </Link>
        </div>
      </div>
    );
  }

  return (
    <div className="mx-auto max-w-sm">
      <h1 className="mb-8 text-3xl font-semibold">{phase.title}</h1>
      <form onSubmit={submit} className="card space-y-4 p-6">
        <div>
          <label className="label" htmlFor="password">
            새 비밀번호 (8~25자, 영문·숫자·특수문자)
          </label>
          <input
            id="password"
            type="password"
            autoComplete="new-password"
            className="input"
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            required
          />
        </div>
        <div>
          <label className="label" htmlFor="passwordConfirm">
            새 비밀번호 확인
          </label>
          <input
            id="passwordConfirm"
            type="password"
            autoComplete="new-password"
            className="input"
            value={passwordConfirm}
            onChange={(e) => setPasswordConfirm(e.target.value)}
            required
          />
        </div>
        {message && (
          <p role="alert" className="text-sm text-accent-strong">
            {message}
          </p>
        )}
        <button type="submit" className="btn btn-primary w-full" disabled={busy}>
          {busy ? "변경 중" : "비밀번호 변경"}
        </button>
      </form>
    </div>
  );
}
