"use client";

import { Check } from "lucide-react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { useState } from "react";
import { api, useApi } from "@/lib/api";
import type { GradesResponse, PerformanceForm, SellerPerformanceDetail } from "@/lib/types";
import { ConsoleTitle } from "@/components/console";
import { PerformanceFormView } from "@/components/performance-form";
import { GradeStep } from "@/components/performance-wizard/grade-step";
import { ReviewStep } from "@/components/performance-wizard/review-step";
import { ScheduleStep } from "@/components/performance-wizard/schedule-step";
import { STEPS, type StepKey, type StepProps } from "@/components/performance-wizard/types";
import { ZoneStep } from "@/components/performance-wizard/zone-step";
import { ErrorBox, Loading } from "@/components/ui";
import { getVenueLayout } from "@/lib/venue-layouts";

// 공연 만들기 마법사. 서버에 공연을 한 번에 만드는 API 가 없어서 단계마다 저장하고,
// 첫 단계를 저장하면 작성 중 공연이 생겨서 중간에 나가도 /setup 에서 이어서 할 수 있다.
export function PerformanceWizard({ performanceId }: { performanceId?: number }) {
  if (performanceId === undefined) return <WizardFrame current="basic" created={false} done={{}} onStep={() => {}} body={<CreateBasic />} />;
  return <ExistingWizard performanceId={performanceId} />;
}

function CreateBasic() {
  const router = useRouter();
  async function create(form: PerformanceForm) {
    const created = await api<{ performanceId: number }>("/api/v1/seller/performances", { method: "POST", body: form });
    router.replace(`/seller/performances/${created.performanceId}/setup`);
  }
  return <PerformanceFormView submitLabel="저장하고 다음" onSubmit={create} />;
}

function ExistingWizard({ performanceId }: { performanceId: number }) {
  const base = `/api/v1/seller/performances/${performanceId}`;
  const detail = useApi<SellerPerformanceDetail>(base);
  const grades = useApi<GradesResponse>(`${base}/grades`);

  if (detail.loading || grades.loading) return <Loading />;
  if (detail.error) return <ErrorBox error={detail.error} onRetry={detail.reload} />;
  if (grades.error) return <ErrorBox error={grades.error} onRetry={grades.reload} />;
  if (!detail.data || !grades.data) return null;

  if (detail.data.status !== "DRAFT") {
    return (
      <>
        <ConsoleTitle title={detail.data.title} back={{ href: "/seller/performances", label: "공연 목록" }} />
        <p className="text-secondary">
          작성 중인 공연만 이어서 만들 수 있습니다.{" "}
          <Link href={`/seller/performances/${performanceId}`} className="underline underline-offset-4">
            공연 상세로 가기
          </Link>
        </p>
      </>
    );
  }

  const layout = getVenueLayout(detail.data.venue.layoutKey);
  return (
    <WizardBody
      detail={detail.data}
      grades={grades.data}
      layout={layout}
      onSaved={() => {
        detail.reload();
        grades.reload();
      }}
    />
  );
}

// 처음 열었을 때 아직 안 끝낸 첫 단계로 간다.
function firstUnfinished(detail: SellerPerformanceDetail, grades: GradesResponse): StepKey {
  if (grades.grades.length === 0) return "grades";
  if (grades.zones.some((z) => z.assignmentStatus === "UNSET")) return "zones";
  if (!detail.schedules.some((s) => s.status !== "CANCELED")) return "schedules";
  return "review";
}

function WizardBody({
  detail,
  grades,
  layout,
  onSaved,
}: {
  detail: SellerPerformanceDetail;
  grades: GradesResponse;
  layout: StepProps["layout"];
  onSaved: () => void;
}) {
  const [step, setStep] = useState<StepKey>(() => firstUnfinished(detail, grades));
  const order = STEPS.map((s) => s.key);
  const move = (delta: number) => setStep(order[Math.min(order.length - 1, Math.max(0, order.indexOf(step) + delta))]);

  const props: StepProps = {
    detail,
    grades,
    layout,
    onSaved,
    onNext: () => move(1),
    onBack: () => move(-1),
    goTo: setStep,
  };

  const done: Partial<Record<StepKey, boolean>> = {
    basic: true,
    grades: grades.grades.length > 0,
    zones: grades.grades.length > 0 && grades.zones.every((z) => z.assignmentStatus !== "UNSET"),
    schedules: detail.schedules.some((s) => s.status !== "CANCELED"),
  };

  return (
    <WizardFrame
      current={step}
      created
      done={done}
      onStep={setStep}
      title={detail.title}
      body={
        step === "basic" ? (
          <PerformanceFormView
            key={detail.updatedAt}
            existing={detail}
            submitLabel="저장하고 다음"
            onSubmit={async (form) => {
              await api(`/api/v1/seller/performances/${detail.performanceId}`, { method: "PUT", body: form });
              onSaved();
              move(1);
            }}
          />
        ) : step === "grades" ? (
          <GradeStep {...props} />
        ) : step === "zones" ? (
          <ZoneStep {...props} />
        ) : step === "schedules" ? (
          <ScheduleStep {...props} />
        ) : (
          <ReviewStep {...props} />
        )
      }
    />
  );
}

function WizardFrame({
  current,
  created,
  done,
  onStep,
  title,
  body,
}: {
  current: StepKey;
  // 첫 단계를 저장하기 전에는 다른 단계로 갈 수 없다.
  created: boolean;
  done: Partial<Record<StepKey, boolean>>;
  onStep: (step: StepKey) => void;
  title?: string;
  body: React.ReactNode;
}) {
  return (
    <>
      <ConsoleTitle
        title={title ?? "새 공연 만들기"}
        sub="단계마다 저장되어서 중간에 나가도 공연 목록의 작성 중 공연에서 이어서 만들 수 있습니다"
        back={{ href: "/seller/performances", label: "공연 목록" }}
      />
      <nav aria-label="공연 만들기 단계" className="mb-8">
        <ol className="flex flex-wrap gap-2">
          {STEPS.map((s, index) => {
            const active = s.key === current;
            const enabled = created || s.key === "basic";
            return (
              <li key={s.key}>
                <button
                  type="button"
                  disabled={!enabled}
                  aria-current={active ? "step" : undefined}
                  onClick={() => onStep(s.key)}
                  className={`inline-flex h-10 items-center gap-2 rounded-full border px-4 text-sm transition-colors duration-150 disabled:opacity-50 ${
                    active ? "border-foreground bg-foreground text-surface" : "border-line hover:bg-raised"
                  }`}
                >
                  {done[s.key] && !active ? (
                    <Check size={16} strokeWidth={1.5} className="text-ok" aria-label="완료" />
                  ) : (
                    <span className="tabular-nums text-xs opacity-70">{index + 1}</span>
                  )}
                  {s.label}
                </button>
              </li>
            );
          })}
        </ol>
      </nav>
      {body}
    </>
  );
}
