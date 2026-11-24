"use client";

import { useEffect, useState } from "react";
import { ApiError, api } from "@/lib/api";
import type { EmailVerificationSent } from "@/lib/types";

// 이메일 인증번호 받기·확인. 예매하기(대기열 입장)가 403 AUTH307 로 막혔을 때와 내 정보에서 같이 쓴다.
// 발송 200 은 "접수했다"는 뜻이지 메일이 도착했다는 뜻이 아니다. (api-spec AUTH-17)

const useCountdown = (untilIso: string | null): number => {
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => {
    if (!untilIso) return;
    const timer = window.setInterval(() => setNow(Date.now()), 1000);
    return () => window.clearInterval(timer);
  }, [untilIso]);
  return untilIso ? Math.max(0, Math.ceil((new Date(untilIso).getTime() - now) / 1000)) : 0;
};

export function EmailVerification({ onVerified, onCancel }: { onVerified: () => void; onCancel?: () => void }) {
  const [sent, setSent] = useState<EmailVerificationSent | null>(null);
  const [code, setCode] = useState("");
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState<string | null>(null);
  const resendIn = useCountdown(sent?.resendAvailableAt ?? null);
  const validFor = useCountdown(sent?.expiresAt ?? null);

  // 다른 창·기기에서 이미 인증한 경우(AUTH301)는 오류가 아니라 성공으로 본다.
  const alreadyVerified = (error: unknown) => error instanceof ApiError && error.code === "AUTH301";

  async function send() {
    setBusy(true);
    setMessage(null);
    try {
      setSent(await api<EmailVerificationSent>("/api/v1/users/me/email-verification", { method: "POST" }));
      setCode("");
    } catch (error) {
      if (alreadyVerified(error)) onVerified();
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
      await api("/api/v1/users/me/email-verification/confirm", { method: "POST", body: { code: code.trim() } });
      onVerified();
    } catch (error) {
      if (alreadyVerified(error)) {
        onVerified();
        return;
      }
      if (error instanceof ApiError && error.code === "AUTH306") {
        // 유효한 인증번호가 없다 (만료, 5번 틀려 폐기). 새로 받아야 한다.
        setCode("");
        setMessage("인증번호를 다시 받아 주세요");
      } else {
        setMessage(error instanceof ApiError ? error.message : "인증하지 못했습니다");
      }
    } finally {
      setBusy(false);
    }
  }

  if (!sent) {
    return (
      <div className="space-y-4">
        <p className="text-sm text-secondary">예매하려면 가입한 이메일의 주인임을 확인해야 합니다. 이메일로 인증번호 6자리를 보내 드립니다</p>
        {message && (
          <p role="alert" className="text-sm text-accent-strong">
            {message}
          </p>
        )}
        <div className="flex justify-end gap-2">
          {onCancel && (
            <button type="button" className="btn" onClick={onCancel}>
              나중에
            </button>
          )}
          <button type="button" className="btn btn-primary" disabled={busy} onClick={send}>
            {busy ? "요청 중" : "인증번호 받기"}
          </button>
        </div>
      </div>
    );
  }

  const minutes = Math.floor(validFor / 60);
  return (
    <form onSubmit={confirm} className="space-y-4">
      <p className="text-sm text-secondary">
        메일로 보낸 인증번호 6자리를 입력해 주세요. 메일을 여러 통 받았다면 <strong className="font-medium text-foreground">가장 최근 메일</strong>의 번호를
        입력하세요
      </p>
      <div>
        <label className="label" htmlFor="verify-code">
          인증번호
        </label>
        <input
          id="verify-code"
          className="input tracking-widest"
          inputMode="numeric"
          autoComplete="one-time-code"
          maxLength={6}
          pattern="\d{6}"
          value={code}
          onChange={(e) => setCode(e.target.value.replace(/\D/g, ""))}
          required
        />
        <p className="mt-1 text-xs text-muted">
          {validFor > 0 ? `남은 유효 시간 ${minutes}:${String(validFor % 60).padStart(2, "0")}` : "유효 시간이 지났습니다. 다시 받아 주세요"}
        </p>
      </div>
      <p className="text-xs text-muted">메일이 오지 않으면 스팸함을 확인하고, 60초 뒤 다시 받아 주세요</p>
      {message && (
        <p role="alert" className="text-sm text-accent-strong">
          {message}
        </p>
      )}
      <div className="flex flex-wrap justify-end gap-2">
        <button type="button" className="btn" disabled={busy || resendIn > 0} onClick={send}>
          {resendIn > 0 ? `${resendIn}초 후 다시 받기` : "다시 받기"}
        </button>
        <button type="submit" className="btn btn-primary" disabled={busy || code.length !== 6}>
          {busy ? "확인 중" : "인증하기"}
        </button>
      </div>
    </form>
  );
}
