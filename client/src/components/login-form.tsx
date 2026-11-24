"use client";

import { useRouter } from "next/navigation";
import { useState } from "react";
import { ApiError, api } from "@/lib/api";
import { saveAccount } from "@/lib/auth";
import type { AccountType, LoginResult } from "@/lib/types";

// 계정 종류마다 로그인 화면을 따로 둔다. (/login 예매자, /seller/login 판매자, /admin/login 관리자)
// 한 화면에서 종류를 고르게 하면 판매자·관리자 입구가 공개 화면에 그대로 드러나기 때문이다.
const LOGIN_PATH: Record<AccountType, string> = { USER: "users", SELLER: "sellers", ADMIN: "admins" };

export function LoginForm({
  accountType,
  title,
  home,
  children,
}: {
  accountType: AccountType;
  title: string;
  // 로그인에 성공하면 이동할 곳
  home: string;
  // 폼 아래에 둘 링크 (비밀번호 찾기, 가입 등)
  children?: React.ReactNode;
}) {
  const router = useRouter();
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
      const result = await api<LoginResult>(`/api/v2/auth/${LOGIN_PATH[accountType]}/login`, {
        method: "POST",
        body: { email, password },
      });
      saveAccount({ accountType: result.accountType, role: result.role });
      // 화면을 떠나도 이 페이지의 상태는 보관된다(cacheComponents). 로그아웃 뒤 다시 와도 비밀번호가 남지 않게 지운다.
      setEmail("");
      setPassword("");
      router.push(home);
    } catch (error) {
      setMessage(error instanceof ApiError ? error.message : "로그인에 실패했습니다");
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="mx-auto max-w-sm">
      <h1 className="mb-8 text-3xl font-semibold">{title}</h1>
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
        {message && (
          <p role="alert" className="text-sm text-accent-strong">
            {message}
          </p>
        )}
        <button type="submit" className="btn btn-primary w-full" disabled={busy}>
          {busy ? "로그인 중" : "로그인"}
        </button>
      </form>
      {children}
    </div>
  );
}
