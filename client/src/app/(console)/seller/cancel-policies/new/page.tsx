"use client";

import { useRouter } from "next/navigation";
import { api } from "@/lib/api";
import type { CancelRule, PolicyDetail } from "@/lib/types";
import { ConsoleTitle } from "@/components/console";
import { PolicyFormView } from "@/components/policy-form";

export default function NewPolicyPage() {
  const router = useRouter();

  async function create(body: { name: string; rules: CancelRule[] }) {
    const created = await api<PolicyDetail>("/api/v1/seller/cancel-policies", { method: "POST", body });
    router.push(`/seller/cancel-policies/${created.cancelPolicyId}`);
  }

  return (
    <>
      <ConsoleTitle
        title="취소 규정 만들기"
        sub="서비스 기본 규정을 바탕으로 구간을 고쳐서 만듭니다"
        back={{ href: "/seller/cancel-policies", label: "취소 규정" }}
      />
      <PolicyFormView onSubmit={create} />
    </>
  );
}
