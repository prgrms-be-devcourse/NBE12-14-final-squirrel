"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { useState } from "react";
import { ApiError, api, useApi } from "@/lib/api";
import { saveAccount } from "@/lib/auth";
import type { LoginResult, Term } from "@/lib/types";
import { EmailVerifyField } from "@/components/email-verification";
import { ErrorBox, Loading } from "@/components/ui";

const TERM_LABEL = { SERVICE: "서비스 이용약관", PRIVACY: "개인정보 수집·이용", WITHDRAW: "탈퇴 안내" };

const EMPTY_FORM = {
  email: "",
  password: "",
  passwordConfirm: "",
  nickname: "",
  name: "",
  phone: "",
};

export default function SignupPage() {
  const router = useRouter();
  const terms = useApi<Term[]>("/api/v1/terms");

  const [form, setForm] = useState(EMPTY_FORM);
  // 이메일 인증을 마친 이메일의 유효 시각. 이때까지 가입을 마쳐야 한다. (api-spec AUTH-18)
  const [verifiedUntil, setVerifiedUntil] = useState<string | null>(null);
  const [agreed, setAgreed] = useState<number[]>([]);
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState<string | null>(null);

  const set = (key: keyof typeof form) => (e: React.ChangeEvent<HTMLInputElement>) =>
    setForm({ ...form, [key]: e.target.value });

  async function submit(event: React.FormEvent) {
    event.preventDefault();
    if (!verifiedUntil) {
      setMessage("이메일 인증을 해 주세요");
      return;
    }
    if (form.password !== form.passwordConfirm) {
      setMessage("비밀번호와 비밀번호 확인이 다릅니다");
      return;
    }
    setBusy(true);
    setMessage(null);
    try {
      const result = await api<LoginResult>("/api/v2/auth/users/signup", {
        method: "POST",
        body: {
          email: form.email.trim(),
          password: form.password,
          passwordConfirm: form.passwordConfirm,
          nickname: form.nickname,
          name: form.name.trim(),
          phone: form.phone,
          agreedTermIds: agreed,
        },
      });
      saveAccount({ accountType: result.accountType, role: result.role });
      // 화면을 떠나도 이 페이지의 상태는 보관된다(cacheComponents). 비밀번호·개인정보가 남지 않게 지운다.
      setForm(EMPTY_FORM);
      setAgreed([]);
      setVerifiedUntil(null);
      router.push("/");
    } catch (error) {
      if (error instanceof ApiError && error.code === "AUTH307") {
        // 인증이 풀렸다. 다른 이유로 실패한 가입은 인증이 남아 있으므로 풀지 않는다.
        setVerifiedUntil(null);
        setMessage("이메일 인증을 다시 해 주세요");
      } else {
        setMessage(error instanceof ApiError ? error.message : "가입에 실패했습니다");
      }
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="mx-auto max-w-md">
      <h1 className="mb-8 text-3xl font-semibold">예매자 가입</h1>
      <form onSubmit={submit} className="card space-y-4 p-6">
        <EmailVerifyField
          email={form.email}
          onEmailChange={(email) => setForm({ ...form, email })}
          verifiedUntil={verifiedUntil}
          onVerified={setVerifiedUntil}
          onUnverify={() => setVerifiedUntil(null)}
          onExpired={() => {
            setVerifiedUntil(null);
            setMessage("이메일 인증 시간이 지났습니다. 다시 인증해 주세요");
          }}
        />
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
          <label className="label" htmlFor="passwordConfirm">
            비밀번호 확인
          </label>
          <input
            id="passwordConfirm"
            type="password"
            autoComplete="new-password"
            className="input"
            value={form.passwordConfirm}
            onChange={set("passwordConfirm")}
            required
          />
        </div>
        <div>
          <label className="label" htmlFor="nickname">
            닉네임 (2~20자)
          </label>
          <input id="nickname" className="input" value={form.nickname} onChange={set("nickname")} required />
        </div>

        <div>
          <label className="label" htmlFor="name">
            이름
          </label>
          <input id="name" className="input" maxLength={50} value={form.name} onChange={set("name")} required />
        </div>
        <div>
          <label className="label" htmlFor="phone">
            휴대폰 (하이픈 없이)
          </label>
          <input
            id="phone"
            className="input"
            placeholder="01012345678"
            inputMode="tel"
            value={form.phone}
            onChange={set("phone")}
            required
          />
        </div>

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
        <button type="submit" className="btn btn-primary w-full" disabled={busy || !verifiedUntil}>
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
