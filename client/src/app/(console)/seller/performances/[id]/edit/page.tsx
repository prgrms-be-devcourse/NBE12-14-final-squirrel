"use client";

import { useParams, useRouter } from "next/navigation";
import { api, useApi } from "@/lib/api";
import type { PerformanceForm, SellerPerformanceDetail } from "@/lib/types";
import { ConsoleTitle } from "@/components/console";
import { PerformanceFormView } from "@/components/performance-form";
import { ErrorBox, Loading } from "@/components/ui";

export default function EditPerformancePage() {
  const { id } = useParams<{ id: string }>();
  const router = useRouter();
  const { data, error, loading, reload } = useApi<SellerPerformanceDetail>(`/api/v1/seller/performances/${id}`);

  async function save(form: PerformanceForm) {
    await api(`/api/v1/seller/performances/${id}`, { method: "PUT", body: form });
    router.push(`/seller/performances/${id}`);
  }

  if (loading) return <Loading />;
  if (error) return <ErrorBox error={error} onRetry={reload} />;
  if (!data) return null;

  return (
    <>
      <ConsoleTitle title="공연 수정" back={{ href: `/seller/performances/${id}`, label: data.title }} />
      <PerformanceFormView existing={data} submitLabel="저장" onSubmit={save} />
    </>
  );
}
