"use client";

import { ArrowLeft } from "lucide-react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { useCallback, useEffect, useRef, useState } from "react";
import { ApiError, api } from "@/lib/api";
import { CAPTCHA_HEADER, getCaptchaToken } from "@/lib/captcha";
import type { QueueStatus } from "@/lib/types";
import { SALE_PAUSED_CODE, SCHEDULE_POSTPONED_CODE, SalePausedModal } from "@/components/sale-paused-modal";
import { Loading } from "@/components/ui";

// 대기열 입구. 서버는 회차마다 정원만큼만 들여보내고, 입장하지 않으면 구역·좌석 조회와 좌석 점유가 403(QUEUE000)이다.
// 줄을 서 있는 동안은 2~3초마다 상태를 불러야 한다. 부르는 것이 "아직 기다린다"는 표시이고
// 30초 동안 부르지 않으면 서버가 떠난 것으로 보고 건너뛴다. (doc/queue-design.md)
const POLL_MS = 2500;

type Phase =
  | { kind: "entering" }
  | { kind: "waiting"; position: number; waitingCount: number; paused: boolean }
  | { kind: "admitted" }
  | { kind: "salePaused" }
  | { kind: "postponed"; wasWaiting: boolean }
  | { kind: "error"; message: string; retryable: boolean };

export function QueueGate({
  scheduleId,
  backHref,
  children,
}: {
  scheduleId: string;
  backHref: string;
  // 입장한 뒤 그릴 화면. 입장이 풀렸을 때(만료 등) 다시 입장시키는 함수를 받는다.
  children: (reenter: () => void) => React.ReactNode;
}) {
  const router = useRouter();
  const toLogin = () => router.push("/login");
  const [phase, setPhase] = useState<Phase>({ kind: "entering" });
  // 입장을 다시 시도하려고 효과를 다시 돌린다.
  const [attempt, setAttempt] = useState(0);
  const path = `/api/v1/schedules/${scheduleId}/queue`;
  const waitingRef = useRef(false);
  // 줄에 있거나(대기) 입장해 있는 동안 true. 창을 닫을 때 자리를 돌려줄지 정하는 데 쓴다.
  const joinedRef = useRef(false);

  useEffect(() => {
    let cancelled = false;
    let timer: number | undefined;

    function apply(status: QueueStatus, enteredJustNow: boolean) {
      if (cancelled) return;
      if (status.status === "ADMITTED") {
        waitingRef.current = false;
        joinedRef.current = true;
        setPhase({ kind: "admitted" });
        return;
      }
      if (status.status === "WAITING") {
        waitingRef.current = true;
        joinedRef.current = true;
        setPhase({
          kind: "waiting",
          position: status.position ?? 0,
          waitingCount: status.waitingCount ?? 0,
          paused: status.paused,
        });
        timer = window.setTimeout(poll, POLL_MS);
        return;
      }
      // NONE: 줄에도 입장 목록에도 없다. 시간이 지났거나 오래 확인하지 않아 밀려난 것이라 다시 입장한다.
      waitingRef.current = false;
      joinedRef.current = false;
      if (enteredJustNow) {
        setPhase({ kind: "error", message: "대기열에 들어가지 못했습니다", retryable: true });
        return;
      }
      void enter();
    }

    function fail(error: unknown) {
      if (cancelled) return;
      const wasWaiting = waitingRef.current;
      waitingRef.current = false;
      joinedRef.current = false;
      if (error instanceof ApiError && error.status === 401) {
        toLogin();
        return;
      }
      // 새로 줄을 서려는데 중지된 회차다. 이미 기다리는 사람은 오류가 아니라 paused 상태를 받아 순번을 지킨다.
      if (error instanceof ApiError && error.code === SALE_PAUSED_CODE) {
        setPhase({ kind: "salePaused" });
        return;
      }
      // 일정 미정 연기는 기다리던 사람도 서버가 줄에서 뺀다.
      if (error instanceof ApiError && error.code === SCHEDULE_POSTPONED_CODE) {
        setPhase({ kind: "postponed", wasWaiting });
        return;
      }
      setPhase({
        kind: "error",
        message: error instanceof ApiError ? error.message : "대기열에 연결하지 못했습니다",
        retryable:
          !(error instanceof ApiError) ||
          error.status >= 500 ||
          error.status === 409 ||
          error.status === 429 ||
          error.code === "CAPTCHA000" ||
          error.code === "CLIENT",
      });
    }

    async function enter() {
      try {
        // 줄을 서기 전에 캡챠 토큰을 확인한다. 토큰은 한 번만 쓸 수 있어서 입장할 때마다 새로 받는다.
        let token: string;
        try {
          token = await getCaptchaToken("queue_enter");
        } catch {
          fail(new ApiError(0, "CLIENT", "자동 입력 방지 확인을 불러오지 못했습니다. 잠시 뒤 다시 시도해 주세요"));
          return;
        }
        apply(await api<QueueStatus>(path, { method: "POST", headers: { [CAPTCHA_HEADER]: token } }), true);
      } catch (error) {
        fail(error);
      }
    }

    async function poll() {
      try {
        apply(await api<QueueStatus>(path), false);
      } catch (error) {
        fail(error);
      }
    }

    // 창을 닫거나 다른 사이트로 가면(pagehide) 자리를 바로 돌려줘서 뒷사람이 들어오게 한다.
    // 새로고침도 같은 신호라 자리를 잃는다. 사용자의 실수로 보고 감수한다. (doc/queue-release-design.md)
    // 닫히는 중이라 일반 요청은 취소될 수 있어서 keepalive 로 보낸다. 우리 사이트 안의 이동(클라이언트 라우팅)은 이 신호가 나지 않는다.
    function onPageHide() {
      if (!joinedRef.current) return;
      joinedRef.current = false;
      waitingRef.current = false;
      void fetch(path, { method: "DELETE", keepalive: true }).catch(() => {});
    }
    // 뒤로 가기로 화면이 그대로 복원되면(bfcache) 자리는 이미 돌려준 뒤라서 처음부터 다시 확인한다.
    function onPageShow(event: PageTransitionEvent) {
      if (!event.persisted) return;
      setPhase({ kind: "entering" });
      setAttempt((n) => n + 1);
    }
    window.addEventListener("pagehide", onPageHide);
    window.addEventListener("pageshow", onPageShow);

    void enter();
    return () => {
      cancelled = true;
      window.clearTimeout(timer);
      window.removeEventListener("pagehide", onPageHide);
      window.removeEventListener("pageshow", onPageShow);
      // 줄을 선 채로 화면을 떠나면 자리를 비워 뒷사람이 들어오게 한다. (입장해 있는 사람은 그대로 둔다)
      if (waitingRef.current) {
        waitingRef.current = false;
        joinedRef.current = false;
        void api(path, { method: "DELETE" }).catch(() => {});
      }
    };
    // toLogin 은 router 만 쓰므로 다시 만들어져도 입장을 다시 시작하지 않는다.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [path, attempt]);

  const reenter = useCallback(() => {
    setPhase({ kind: "entering" });
    setAttempt((n) => n + 1);
  }, []);

  if (phase.kind === "admitted") return <>{children(reenter)}</>;
  if (phase.kind === "entering") return <Loading label="대기열에 입장하는 중" />;
  if (phase.kind === "salePaused") {
    return <SalePausedModal href={backHref}>지금은 예매할 수 없습니다. 재개되면 다시 예매할 수 있습니다</SalePausedModal>;
  }
  if (phase.kind === "postponed") {
    return (
      <SalePausedModal href={backHref} title="공연이 연기되었습니다">
        일정이 정해지지 않아 지금은 예매할 수 없습니다
        {phase.wasWaiting && ". 대기 순번은 사라졌습니다"}
      </SalePausedModal>
    );
  }

  return (
    <div className="mx-auto max-w-md py-12">
      <Link
        href={backHref}
        className="mb-6 inline-flex items-center gap-2 text-sm text-secondary transition-colors duration-150 hover:text-foreground"
      >
        <ArrowLeft size={16} strokeWidth={1.5} />
        공연으로 돌아가기
      </Link>
      {phase.kind === "waiting" ? (
        <div className="card space-y-4 p-8 text-center">
          <h1 className="text-xl font-semibold">대기 중입니다</h1>
          <p className="text-5xl font-semibold tabular-nums">{phase.position.toLocaleString("ko-KR")}번째</p>
          <p className="text-sm text-secondary">
            내 앞에 {Math.max(phase.position - 1, 0).toLocaleString("ko-KR")}명 · 전체 대기{" "}
            {phase.waitingCount.toLocaleString("ko-KR")}명
          </p>
          {phase.paused ? (
            <p role="status" className="rounded-xl bg-[var(--warn-soft)] px-4 py-3 text-sm text-[var(--warn)]">
              판매가 일시 중지되었습니다. 재개 시각은 정해지지 않았습니다. 이 화면을 유지하면 순번이 유지되고, 나가면
              사라집니다
            </p>
          ) : (
            <p className="text-xs text-muted">이 화면을 벗어나면 순번이 사라집니다. 자리가 나면 자동으로 이동합니다</p>
          )}
          <button type="button" className="btn" onClick={() => router.push(backHref)}>
            나가기
          </button>
        </div>
      ) : (
        <div className="card space-y-4 p-8 text-center">
          <h1 className="text-xl font-semibold">예매를 시작하지 못했습니다</h1>
          <p role="alert" className="text-sm text-secondary">
            {phase.message}
          </p>
          {phase.retryable && (
            <button type="button" className="btn btn-primary" onClick={reenter}>
              다시 시도
            </button>
          )}
        </div>
      )}
    </div>
  );
}
