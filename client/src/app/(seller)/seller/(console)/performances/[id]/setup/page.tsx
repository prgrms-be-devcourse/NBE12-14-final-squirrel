"use client";


import { useRouteParams } from "@/lib/route-params";
import { PerformanceWizard } from "@/components/performance-wizard/performance-wizard";

// 작성 중인 공연을 마법사로 이어서 만든다.
export default function SetupPerformancePage() {
  const { id } = useRouteParams<{ id: string }>("/seller/performances/[id]/setup");
  return <PerformanceWizard key={id} performanceId={Number(id)} />;
}
