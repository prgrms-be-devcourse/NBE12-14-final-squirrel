"use client";

import { useRouter } from "next/navigation";
import { useState } from "react";
import { useApi } from "@/lib/api";
import { dateTimeText } from "@/lib/format";
import type { CancelPolicySummary, Page, SellerPerformanceSummary } from "@/lib/types";
import { Empty, ErrorBox, Loading, PageTitle, Pagination } from "@/components/ui";

const STATUS: Record<SellerPerformanceSummary["status"], { label: string; badge: string }> = {
  DRAFT: { label: "작성 중", badge: "badge" },
  PUBLISHED: { label: "공개", badge: "badge badge-ok" },
  CANCELED: { label: "취소", badge: "badge badge-warn" },
};

export default function SellerPage() {
  const router = useRouter();
  const [page, setPage] = useState(0);
  const performances = useApi<Page<SellerPerformanceSummary>>("/api/v1/seller/performances", { page });
  const policies = useApi<Page<CancelPolicySummary>>("/api/v1/seller/cancel-policies", { size: 20 });

  const unauthorized = performances.error?.status === 401 || performances.error?.status === 403;
  if (performances.error?.status === 401) {
    router.push("/login");
    return null;
  }

  return (
    <>
      <PageTitle title="판매자 센터" sub="내 사업체의 공연과 취소 규정" />

      {unauthorized && <ErrorBox error={performances.error!} />}

      <section className="mb-10">
        <h2 className="mb-3 font-medium">내 공연</h2>
        {performances.loading && <Loading />}
        {performances.error && !unauthorized && (
          <ErrorBox error={performances.error} onRetry={performances.reload} />
        )}
        {performances.data && performances.data.content.length === 0 && <Empty>등록한 공연이 없습니다</Empty>}
        {performances.data && performances.data.content.length > 0 && (
          <>
            <table className="card w-full overflow-hidden text-sm">
              <thead className="bg-raised text-left text-muted">
                <tr>
                  <th className="px-4 py-2 font-normal">공연</th>
                  <th className="px-4 py-2 font-normal">상태</th>
                  <th className="hidden px-4 py-2 font-normal sm:table-cell">공연장</th>
                  <th className="px-4 py-2 text-right font-normal">회차</th>
                  <th className="hidden px-4 py-2 font-normal md:table-cell">등록일</th>
                </tr>
              </thead>
              <tbody>
                {performances.data.content.map((p) => (
                  <tr key={p.performanceId} className="border-t border-line">
                    <td className="px-4 py-3 font-medium">{p.title}</td>
                    <td className="px-4 py-3">
                      <span className={STATUS[p.status].badge}>{STATUS[p.status].label}</span>
                    </td>
                    <td className="hidden px-4 py-3 text-muted sm:table-cell">{p.venueName ?? "-"}</td>
                    <td className="px-4 py-3 text-right">{p.scheduleCount}</td>
                    <td className="hidden px-4 py-3 text-muted md:table-cell">{dateTimeText(p.createdAt)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
            <Pagination
              page={performances.data.page}
              totalPages={performances.data.totalPages}
              onChange={setPage}
            />
          </>
        )}
      </section>

      <section>
        <h2 className="mb-3 font-medium">취소 규정</h2>
        {policies.loading && <Loading />}
        {policies.error && <ErrorBox error={policies.error} onRetry={policies.reload} />}
        {policies.data && (
          <ul className="card divide-y divide-line text-sm">
            {policies.data.content.map((policy) => (
              <li key={policy.cancelPolicyId} className="flex items-center justify-between px-4 py-3">
                <span className="font-medium">{policy.name}</span>
                <span className="flex gap-2">
                  {policy.isDefault && <span className="badge">기본</span>}
                  <span className={policy.editable ? "badge badge-ok" : "badge"}>
                    {policy.editable ? "수정 가능" : "수정 불가"}
                  </span>
                </span>
              </li>
            ))}
          </ul>
        )}
      </section>
    </>
  );
}
