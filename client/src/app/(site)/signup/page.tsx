"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { useState } from "react";
import { ApiError, api, useApi } from "@/lib/api";
import { saveAccount } from "@/lib/auth";
import type { LoginResult, Term } from "@/lib/types";
import { ErrorBox, Loading } from "@/components/ui";

const TERM_LABEL = { SERVICE: "서비스 이용약관", PRIVACY: "개인정보 수집·이용", WITHDRAW: "탈퇴 안내" };

export default function SignupPage() {
  const router = useRouter();
  const terms = useApi<Term[]>("/api/v1/terms");

  const [form, setForm] = useState({
    email: "",
    password: "",
    nickname: "",
    name: "",
    phone: "",
    birth: "",
  });
  const [agreed, setAgreed] = useState<number[]>([]);
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState<string | null>(null);

  const set = (key: keyof typeof form) => (e: React.ChangeEvent<HTMLInputElement>) =>
    setForm({ ...form, [key]: e.target.value });

  async function submit(event: React.FormEvent) {
    event.preventDefault();
    setBusy(true);
    setMessage(null);
    try {
      // 본인인증은 아직 Mock 이다. 형식: mock|이름|CI|휴대폰|생년월일(yyyyMMdd)
      const identityToken = ["mock", form.name, `ci-${crypto.randomUUID()}`, form.phone, form.birth].join("|");
      const result = await api<LoginResult>("/api/v2/auth/users/signup", {
        method: "POST",
        body: {
          email: form.email,
          password: form.password,
          nickname: form.nickname,
          identityToken,
          agreedTermIds: agreed,
        },
      });
      saveAccount({ accountType: result.accountType, role: result.role });
      router.push("/");
    } catch (error) {
      setMessage(error instanceof ApiError ? error.message : "가입에 실패했습니다");
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="mx-auto max-w-md">
      <h1 className="mb-8 text-3xl font-semibold">예매자 가입</h1>
      <form onSubmit={submit} className="card space-y-4 p-6">
        <div>
          <label className="label" htmlFor="email">
            이메일
          </label>
          <input id="email" type="email" className="input" value={form.email} onChange={set("email")} required />
        </div>
        <div>
          <label className="label" htmlFor="password">
            비밀번호 (8~25자, 영문·숫자·특수문자)
          </label>
          <input
            id="password"
            type="password"
            autoComplete="new-password"
            className="input"
            value={form.password}
            onChange={set("password")}
            required
          />
        </div>
        <div>
          <label className="label" htmlFor="nickname">
            닉네임 (2~20자)
          </label>
          <input id="nickname" className="input" value={form.nickname} onChange={set("nickname")} required />
        </div>

        <fieldset className="space-y-3 rounded-xl border border-line p-3">
          <legend className="px-1 text-xs text-muted">본인 확인 (테스트용 Mock)</legend>
          <input className="input" placeholder="이름" value={form.name} onChange={set("name")} required />
          <input
            className="input"
            placeholder="휴대폰 (01012345678)"
            inputMode="tel"
            value={form.phone}
            onChange={set("phone")}
            required
          />
          <input
            className="input"
            placeholder="생년월일 (19900101)"
            inputMode="numeric"
            value={form.birth}
            onChange={set("birth")}
            required
          />
        </fieldset>

        <fieldset className="space-y-2">
          <legend className="label">약관</legend>
          {terms.loading && <Loading />}
          {terms.error && <ErrorBox error={terms.error} onRetry={terms.reload} />}
          {terms.data?.map((term) => (
            <details key={term.termId} className="rounded-xl border border-line px-3 py-2 text-sm">
              <summary className="flex cursor-pointer items-center gap-2">
                <input
                  type="checkbox"
                  checked={agreed.includes(term.termId)}
                  onClick={(e) => e.stopPropagation()}
                  onChange={(e) =>
                    setAgreed(
                      e.target.checked ? [...agreed, term.termId] : agreed.filter((x) => x !== term.termId),
                    )
                  }
                />
                {TERM_LABEL[term.type]} {term.required ? "(필수)" : "(선택)"}
              </summary>
              <p className="mt-2 text-xs text-muted">{term.content}</p>
            </details>
          ))}
        </fieldset>

        {message && <p className="text-sm text-accent-strong">{message}</p>}
        <button type="submit" className="btn btn-primary w-full" disabled={busy}>
          {busy ? "가입 중" : "가입하기"}
        </button>
      </form>
      <p className="mt-4 text-center text-sm text-muted">
        이미 계정이 있나요?{" "}
        <Link href="/login" className="text-foreground underline">
          로그인
        </Link>
      </p>
    </div>
  );
}
