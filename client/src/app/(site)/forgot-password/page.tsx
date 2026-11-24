"use client";

import Link from "next/link";
import { useEffect, useState } from "react";
import { ApiError, api } from "@/lib/api";
import type { PasswordResetSent } from "@/lib/types";

// 비밀번호 찾기 요청. 같은 이메일이 예매자·판매자 양쪽에 있을 수 있어서 종류를 여기서 고른다. (api-spec AUTH-19·20)
// 200 은 "접수했다"는 뜻이지 메일이 도착했다는 뜻이 아니다.
const TABS = [
  { label: "예매자", path: "users" },
  { label: "판매자", path: "sellers" },
];

export default function ForgotPasswordPage() {
  const [tab, setTab] = useState(TABS[0]);
  const [email, setEmail] = useState("");
  const [sent, setSent] = useState<PasswordResetSent | null>(null);
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState<string | null>(null);
  const [now, setNow] = useState(() => Date.now());

  useEffect(() => {
    if (!sent) return;
    const timer = window.setInterval(() => setNow(Date.now()), 1000);
    return () => window.clearInterval(timer);
  }, [sent]);

  const resendIn = sent ? Math.max(0, Math.ceil((new Date(sent.resendAvailableAt).getTime() - now) / 1000)) : 0;

  async function submit(event: React.FormEvent) {
    event.preventDefault();
    setBusy(true);
    setMessage(null);
    try {
      const result = await api<PasswordResetSent>(`/api/v1/auth/${tab.path}/password-reset/request`, {
        method: "POST",
        body: { email: email.trim() },
      });
      setNow(Date.now());
      setSent(result);
    } catch (error) {
      if (error instanceof ApiError && error.code === "AUTH308") {
        setMessage(`${error.message} 계정 종류와 이메일을 확인해 주세요`);
      } else if (error instanceof ApiError && error.code === "AUTH302") {
        setMessage("잠시 후 다시 보낼 수 있습니다");
      } else if (error instanceof ApiError && error.code === "AUTH303") {
        setMessage("오늘은 더 보낼 수 없습니다. 내일 다시 시도해 주세요");
      } else if (error instanceof ApiError && error.code === "AUTH304") {
        setMessage("요청이 많습니다. 잠시 후 다시 시도해 주세요");
      } else {
        setMessage(error instanceof ApiError ? error.message : "메일을 보내지 못했습니다");
      }
    } finally {
      setBusy(false);
    }
  }

  function chooseTab(next: (typeof TABS)[number]) {
    setTab(next);
    setEmail("");
    setSent(null);
    setMessage(null);
  }

  return (
    <div className="mx-auto max-w-sm">
      <h1 className="mb-3 text-3xl font-semibold">비밀번호 찾기</h1>
      <p className="mb-8 text-secondary">가입한 이메일로 비밀번호를 다시 정하는 링크를 보내 드립니다</p>
      <div className="mb-5 flex gap-2">
        {TABS.map((t) => (
          <button
            key={t.path}
            type="button"
            onClick={() => chooseTab(t)}
            className={`btn flex-1 ${tab.path === t.path ? "btn-dark" : ""}`}
          >
            {t.label}
          </button>
        ))}
      </div>
      <form onSubmit={submit} className="card space-y-4 p-6">
        <div>
          <label className="label" htmlFor="email">
            가입한 이메일
          </label>
          <input
            id="email"
            type="email"
            autoComplete="email"
            className="input"
            value={email}
            onChange={(e) => setEmail(e.target.value)}
            required
          />
        </div>
        {sent && (
          <div role="status" className="space-y-1 rounded-xl bg-[var(--ok-soft)] px-4 py-3 text-sm text-ok">
            <p>{sent.email}로 재설정 링크를 보냈습니다. 링크는 15분 동안 유효합니다</p>
            <p className="text-xs">메일이 오지 않으면 스팸함을 확인하고, 60초 뒤 다시 받아 주세요</p>
          </div>
        )}
        {message && (
          <p role="alert" className="text-sm text-accent-strong">
            {message}
          </p>
        )}
        <button type="submit" className="btn btn-primary w-full" disabled={busy || resendIn > 0}>
          {busy ? "보내는 중" : resendIn > 0 ? `${resendIn}초 후 다시 받기` : sent ? "다시 받기" : "재설정 링크 받기"}
        </button>
      </form>
      <p className="mt-4 text-center text-sm text-muted">
        <Link href="/login" className="text-foreground underline">
          로그인으로 돌아가기
        </Link>
      </p>
    </div>
  );
}
