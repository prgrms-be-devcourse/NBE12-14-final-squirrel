"use client";

import { Plus } from "lucide-react";
import Link from "next/link";
import { useApi } from "@/lib/api";
import type { CancelPolicySummary, Page } from "@/lib/types";
import { ConsoleTitle, Table } from "@/components/console";
import { Empty, ErrorBox, Loading } from "@/components/ui";

export default function CancelPoliciesPage() {
  const { data, error, loading, reload } = useApi<Page<CancelPolicySummary>>("/api/v1/seller/cancel-policies", {
    size: 20,
  });

  return (
    <>
      <ConsoleTitle
        title="취소 규정"
        sub="공연일까지 남은 기간에 따른 취소 수수료 기준입니다"
        actions={
          <Link href="/seller/cancel-policies/new" className="btn btn-primary">
            <Plus size={16} strokeWidth={1.5} />
            규정 만들기
          </Link>
        }
      />
      {loading && <Loading />}
      {error && <ErrorBox error={error} onRetry={reload} />}
      {data && data.content.length === 0 && <Empty>취소 규정이 없습니다</Empty>}
      {data && data.content.length > 0 && (
        <Table head={["이름", "구분", "수정"]}>
          {data.content.map((p) => (
            <tr key={p.cancelPolicyId} className="hover:bg-raised/60">
              <td className="px-5 py-4 font-medium">
                <Link href={`/seller/cancel-policies/${p.cancelPolicyId}`} className="hover:underline">
                  {p.name}
                </Link>
              </td>
              <td className="px-5 py-4">{p.isDefault ? <span className="badge">서비스 기본</span> : "내 규정"}</td>
              <td className="px-5 py-4">
                <span className={p.editable ? "badge badge-ok" : "badge"}>{p.editable ? "수정 가능" : "수정 불가"}</span>
              </td>
            </tr>
          ))}
        </Table>
      )}
    </>
  );
}
