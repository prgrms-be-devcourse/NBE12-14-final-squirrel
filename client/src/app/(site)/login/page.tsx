"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { useState } from "react";
import { ApiError, api } from "@/lib/api";
import { saveAccount } from "@/lib/auth";
import type { AccountType, LoginResult } from "@/lib/types";

const TABS: { type: AccountType; label: string; path: string; home: string }[] = [
  { type: "USER", label: "예매자", path: "users", home: "/" },
  { type: "SELLER", label: "판매자", path: "sellers", home: "/seller" },
  { type: "ADMIN", label: "관리자", path: "admins", home: "/admin" },
];

export default function LoginPage() {
  const router = useRouter();
  const [tab, setTab] = useState(TABS[0]);
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState<string | null>(null);

  async function submit(event: React.FormEvent) {
    event.preventDefault();
    setBusy(true);
    setMessage(null);
    try {
      // v2 Redis 세션 로그인. 성공하면 SESSION 쿠키가 내려오고 이후 요청에 자동으로 실린다.
      const result = await api<LoginResult>(`/api/v2/auth/${tab.path}/login`, {
        method: "POST",
        body: { email, password },
      });
      saveAccount({ accountType: result.accountType, role: result.role });
      // 화면을 떠나도 이 페이지의 상태는 보관된다(cacheComponents). 로그아웃 뒤 다시 와도 비밀번호가 남지 않게 지운다.
      setEmail("");
      setPassword("");
      router.push(tab.home);
    } catch (error) {
      setMessage(error instanceof ApiError ? error.message : "로그인에 실패했습니다");
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="mx-auto max-w-sm">
      <h1 className="mb-8 text-3xl font-semibold">로그인</h1>
      <div className="mb-5 flex gap-2">
        {TABS.map((t) => (
          <button
            key={t.type}
            type="button"
            onClick={() => {
              // 다른 계정 종류의 이메일·비밀번호가 넘어가지 않게 지운다.
              setTab(t);
              setEmail("");
              setPassword("");
              setMessage(null);
            }}
            className={`btn flex-1 ${tab.type === t.type ? "btn-dark" : ""}`}
          >
            {t.label}
          </button>
        ))}
      </div>
      <form onSubmit={submit} className="card space-y-4 p-6">
        <div>
          <label className="label" htmlFor="email">
            이메일
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
        <div>
          <label className="label" htmlFor="password">
            비밀번호
          </label>
          <input
            id="password"
            type="password"
            autoComplete="current-password"
            className="input"
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            required
          />
        </div>
        {message && <p className="text-sm text-accent-strong">{message}</p>}
        <button type="submit" className="btn btn-primary w-full" disabled={busy}>
          {busy ? "로그인 중" : "로그인"}
        </button>
      </form>
      {/* 관리자는 비밀번호 찾기 대상이 아니다. 계정 종류는 찾기 화면에서 다시 고른다. */}
      {tab.type !== "ADMIN" && (
        <p className="mt-4 text-center text-sm text-muted">
          비밀번호를 잊었나요?{" "}
          <Link href="/forgot-password" className="text-foreground underline">
            비밀번호 찾기
          </Link>
        </p>
      )}
      {tab.type === "USER" && (
        <p className="mt-4 text-center text-sm text-muted">
          계정이 없나요?{" "}
          <Link href="/signup" className="text-foreground underline">
            가입하기
          </Link>
        </p>
      )}
      {tab.type === "SELLER" && (
        <p className="mt-4 text-center text-sm text-muted">
          판매자로 등록하려면{" "}
          <Link href="/signup/seller" className="text-foreground underline">
            가입 신청
          </Link>
        </p>
      )}
    </div>
  );
}
