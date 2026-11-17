"use client";

import { useParams } from "next/navigation";
import { useState } from "react";
import { api, useApi } from "@/lib/api";
import type { CancelRule, PolicyDetail } from "@/lib/types";
import { ConsoleTitle } from "@/components/console";
import { PolicyFormView } from "@/components/policy-form";
import { ErrorBox, Loading } from "@/components/ui";

export default function PolicyDetailPage() {
  const { id } = useParams<{ id: string }>();
  const { data, error, loading, reload } = useApi<PolicyDetail>(`/api/v1/seller/cancel-policies/${id}`);
  const [saved, setSaved] = useState(false);

  async function save(body: { name: string; rules: CancelRule[] }) {
    await api(`/api/v1/seller/cancel-policies/${id}`, { method: "PUT", body });
    setSaved(true);
    reload();
  }

  if (loading) return <Loading />;
  if (error) return <ErrorBox error={error} onRetry={reload} />;
  if (!data) return null;

  return (
    <>
      <ConsoleTitle
        title={data.name}
        sub={
          data.isDefault
            ? "서비스 기본 규정은 수정할 수 없습니다. 새 규정을 만들 때 참고하세요"
            : data.editable
              ? "작성 중인 공연에만 쓰이는 규정이라 수정할 수 있습니다"
              : "공개되었거나 취소된 공연이 쓰고 있어서 수정할 수 없습니다. 바꾸려면 새 규정을 만드세요"
        }
        back={{ href: "/seller/cancel-policies", label: "취소 규정" }}
      />
      {saved && <p className="mb-6 rounded-xl bg-[var(--ok-soft)] px-4 py-3 text-sm text-ok">저장했습니다</p>}
      <PolicyFormView key={data.name + data.rules.length} policy={data} readOnly={!data.editable} onSubmit={save} />
    </>
  );
}
