"use client";

import Link from "next/link";
import { useEffect, useState } from "react";
import { ApiError, api } from "@/lib/api";
import { CAPTCHA_HEADER, getCaptchaToken } from "@/lib/captcha";
import type { EmailVerificationConfirmed, EmailVerificationSent } from "@/lib/types";

// 가입 전 이메일 인증. 가입 화면에서 이메일을 입력하고 인증번호를 받아 확인한다. (api-spec AUTH-17·18)
// 발송 200 은 "접수했다"는 뜻이지 메일이 도착했다는 뜻이 아니다.
// 인증은 verifiedUntil(20분)까지 유효해서, 그 안에 가입을 마쳐야 한다.

const SEND_PATH = "/api/v1/auth/users/email-verification";

const useCountdown = (untilIso: string | null): number => {
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => {
    if (!untilIso) return;
    const timer = window.setInterval(() => setNow(Date.now()), 1000);
    return () => window.clearInterval(timer);
  }, [untilIso]);
  return untilIso ? Math.max(0, Math.ceil((new Date(untilIso).getTime() - now) / 1000)) : 0;
};

export function EmailVerifyField({
  email,
  onEmailChange,
  verifiedUntil,
  onVerified,
  onUnverify,
  onExpired,
}: {
  email: string;
  onEmailChange: (email: string) => void;
  // 인증을 마친 이메일의 유효 시각. null 이면 아직 인증하지 않았다.
  verifiedUntil: string | null;
  onVerified: (until: string) => void;
  // "변경"을 눌러 인증 상태를 푼다.
  onUnverify: () => void;
  // verifiedUntil 이 지나서 인증이 풀렸다.
  onExpired: () => void;
}) {
  const [sent, setSent] = useState<EmailVerificationSent | null>(null);
  const [code, setCode] = useState("");
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState<string | null>(null);
  // 발송이 USER000(409)이면 로그인·비밀번호 찾기로 안내한다.
  const [taken, setTaken] = useState(false);
  const resendIn = useCountdown(sent?.resendAvailableAt ?? null);
  const validFor = useCountdown(sent?.expiresAt ?? null);
  const verifiedLeft = useCountdown(verifiedUntil);

  useEffect(() => {
    if (verifiedUntil && verifiedLeft === 0) onExpired();
    // onExpired 는 부모가 매번 새로 만든다. 인증 시각이 지났을 때만 부르면 된다.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [verifiedUntil, verifiedLeft]);

  function changeEmail(value: string) {
    onEmailChange(value);
    // 다른 이메일로 받은 번호는 쓸 수 없다.
    setSent(null);
    setCode("");
    setMessage(null);
    setTaken(false);
  }

  async function send() {
    setBusy(true);
    setMessage(null);
    setTaken(false);
    try {
      // 로그인 없이 아무 이메일로나 메일을 보낼 수 있는 API 라 캡챠를 건다. 토큰은 한 번만 쓸 수 있어서 보낼 때마다 새로 받는다.
      let token: string;
      try {
        token = await getCaptchaToken("email_verify_send");
      } catch {
        setMessage("자동 입력 방지 확인을 불러오지 못했습니다. 잠시 뒤 다시 시도해 주세요");
        return;
      }
      setSent(
        await api<EmailVerificationSent>(SEND_PATH, {
          method: "POST",
          body: { email: email.trim() },
          headers: { [CAPTCHA_HEADER]: token },
        }),
      );
      setCode("");
    } catch (error) {
      if (error instanceof ApiError && error.code === "USER000") setTaken(true);
      else setMessage(error instanceof ApiError ? error.message : "인증번호를 받지 못했습니다");
    } finally {
      setBusy(false);
    }
  }

  async function confirm(event: React.FormEvent) {
    event.preventDefault();
    setBusy(true);
    setMessage(null);
    try {
      const result = await api<EmailVerificationConfirmed>(`${SEND_PATH}/confirm`, {
        method: "POST",
        body: { email: email.trim(), code: code.trim() },
      });
      setSent(null);
      setCode("");
      onVerified(result.verifiedUntil);
    } catch (error) {
      if (error instanceof ApiError && error.code === "AUTH306") {
        // 유효한 인증번호가 없다 (만료, 5번 틀려 폐기). 새로 받아야 한다.
        setCode("");
        setMessage("인증번호를 다시 받아 주세요");
      } else {
        // AUTH305 는 남은 횟수가 메시지에 들어 있어서 그대로 보여 준다.
        setMessage(error instanceof ApiError ? error.message : "인증하지 못했습니다");
      }
    } finally {
      setBusy(false);
    }
  }

  const verified = verifiedUntil !== null;
  const minutes = Math.floor(validFor / 60);

  return (
    <div className="space-y-3">
      <div>
        <label className="label" htmlFor="email">
          이메일
        </label>
        <div className="flex gap-2">
          <input
            id="email"
            type="email"
            autoComplete="email"
            className="input"
            value={email}
            readOnly={verified}
            onChange={(e) => changeEmail(e.target.value)}
            required
          />
          {verified ? (
            <button type="button" className="btn shrink-0" onClick={onUnverify}>
              변경
            </button>
          ) : (
            <button
              type="button"
              className="btn shrink-0"
              disabled={busy || email.trim() === "" || (sent !== null && resendIn > 0)}
              onClick={send}
            >
              {sent === null ? "인증번호 받기" : resendIn > 0 ? `${resendIn}초 후 다시 받기` : "다시 받기"}
            </button>
          )}
        </div>
      </div>

      {verified && (
        <p role="status" className="text-sm text-ok">
          이메일 인증을 마쳤습니다. {Math.floor(verifiedLeft / 60)}분 안에 가입을 마쳐 주세요
        </p>
      )}

      {taken && (
        <p role="alert" className="text-sm text-accent-strong">
          이미 가입한 이메일입니다.{" "}
          <Link href="/login" className="underline">
            로그인
          </Link>
          {" 또는 "}
          <Link href="/forgot-password" className="underline">
            비밀번호 찾기
          </Link>
        </p>
      )}

      {!verified && sent && (
        <div className="space-y-3 rounded-xl border border-line p-4">
          <p className="text-sm text-secondary">
            메일로 보낸 인증번호 6자리를 입력해 주세요. 메일을 여러 통 받았다면{" "}
            <strong className="font-medium text-foreground">가장 최근 메일</strong>의 번호를 입력하세요
          </p>
          <div>
            <label className="label" htmlFor="verify-code">
              인증번호
            </label>
            <div className="flex gap-2">
              <input
                id="verify-code"
                className="input tracking-widest"
                inputMode="numeric"
                autoComplete="one-time-code"
                maxLength={6}
                pattern="\d{6}"
                value={code}
                onChange={(e) => setCode(e.target.value.replace(/\D/g, ""))}
                // 가입 폼 안이라 Enter 가 가입을 제출하지 않고 인증을 확인하게 한다.
                onKeyDown={(e) => {
                  if (e.key !== "Enter") return;
                  e.preventDefault();
                  if (code.length === 6 && !busy) void confirm(e);
                }}
              />
              <button type="button" className="btn btn-primary shrink-0" disabled={busy || code.length !== 6} onClick={confirm}>
                {busy ? "확인 중" : "인증하기"}
              </button>
            </div>
            <p className="mt-1 text-xs text-muted">
              {validFor > 0
                ? `남은 유효 시간 ${minutes}:${String(validFor % 60).padStart(2, "0")}`
                : "유효 시간이 지났습니다. 다시 받아 주세요"}
            </p>
          </div>
          <p className="text-xs text-muted">메일이 오지 않으면 스팸함을 확인하고, 60초 뒤 다시 받아 주세요</p>
        </div>
      )}

      {message && (
        <p role="alert" className="text-sm text-accent-strong">
          {message}
        </p>
      )}
    </div>
  );
}
