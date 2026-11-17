"use client";

import { useRouter } from "next/navigation";
import { api } from "@/lib/api";
import type { PerformanceForm } from "@/lib/types";
import { ConsoleTitle } from "@/components/console";
import { PerformanceFormView } from "@/components/performance-form";

export default function NewPerformancePage() {
  const router = useRouter();

  async function create(form: PerformanceForm) {
    const created = await api<{ performanceId: number }>("/api/v1/seller/performances", {
      method: "POST",
      body: form,
    });
    router.push(`/seller/performances/${created.performanceId}`);
  }

  return (
    <>
      <ConsoleTitle
        title="공연 등록"
        sub="작성 중 상태로 저장되고, 회차와 등급을 정한 뒤 공개할 수 있습니다"
        back={{ href: "/seller/performances", label: "공연 목록" }}
      />
      <PerformanceFormView submitLabel="작성 중으로 저장" onSubmit={create} />
    </>
  );
}
