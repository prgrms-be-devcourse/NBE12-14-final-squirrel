"use client";

import { Plus } from "lucide-react";
import Link from "next/link";
import { useApi } from "@/lib/api";
import { dateTimeText } from "@/lib/format";
import type { CancelPolicySummary, Page, SellerPerformanceSummary } from "@/lib/types";
import { ConsoleTitle, Panel, StatCard, Table } from "@/components/console";
import { Empty, ErrorBox, Loading } from "@/components/ui";
import { PERFORMANCE_BADGE, PERFORMANCE_LABEL } from "@/lib/console";

export default function SellerDashboard() {
  const performances = useApi<Page<SellerPerformanceSummary>>("/api/v1/seller/performances", { size: 20 });
  const policies = useApi<Page<CancelPolicySummary>>("/api/v1/seller/cancel-policies", { size: 20 });

  const items = performances.data?.content ?? [];
  const count = (status: SellerPerformanceSummary["status"]) => items.filter((p) => p.status === status).length;
  const drafts = items.filter((p) => p.status === "DRAFT");

  return (
    <>
      <ConsoleTitle
        title="대시보드"
        sub="내 사업체의 공연 현황"
        actions={
          <Link href="/seller/performances/new" className="btn btn-primary">
            <Plus size={16} strokeWidth={1.5} />
            공연 등록
          </Link>
        }
      />

      {performances.error && <ErrorBox error={performances.error} onRetry={performances.reload} />}

      <div className="grid grid-cols-2 gap-4 lg:grid-cols-4">
        <StatCard label="전체 공연" value={performances.data?.totalElements ?? "-"} />
        <StatCard label="공개 중" value={performances.data ? count("PUBLISHED") : "-"} />
        <StatCard label="작성 중" value={performances.data ? count("DRAFT") : "-"} tone={drafts.length ? "accent" : undefined} hint={drafts.length ? "공개하려면 마무리가 필요합니다" : undefined} />
        <StatCard label="취소" value={performances.data ? count("CANCELED") : "-"} />
      </div>

      <div className="mt-8 grid gap-6 xl:grid-cols-[1fr_320px]">
        <Panel title="최근 공연" actions={<Link href="/seller/performances" className="text-sm text-secondary hover:text-foreground">전체 보기</Link>}>
          {performances.loading && <Loading />}
          {performances.data && items.length === 0 && <Empty>등록한 공연이 없습니다</Empty>}
          {items.length > 0 && (
            <div className="-mx-6 -mb-6 overflow-x-auto">
              <Table head={["공연", "상태", "회차", "등록일"]}>
                {items.slice(0, 5).map((p) => (
                  <tr key={p.performanceId} className="hover:bg-raised/60">
                    <td className="px-5 py-3 font-medium">
                      <Link href={`/seller/performances/${p.performanceId}`} className="hover:underline">
                        {p.title}
                      </Link>
                    </td>
                    <td className="px-5 py-3">
                      <span className={PERFORMANCE_BADGE[p.status]}>{PERFORMANCE_LABEL[p.status]}</span>
                    </td>
                    <td className="px-5 py-3">{p.scheduleCount}</td>
                    <td className="px-5 py-3 text-muted">{dateTimeText(p.createdAt)}</td>
                  </tr>
                ))}
              </Table>
            </div>
          )}
        </Panel>

        <div className="space-y-6">
          <Panel title="마무리할 공연">
            {drafts.length === 0 ? (
              <p className="text-sm text-muted">작성 중인 공연이 없습니다</p>
            ) : (
              <ul className="space-y-3 text-sm">
                {drafts.map((p) => (
                  <li key={p.performanceId}>
                    <Link href={`/seller/performances/${p.performanceId}`} className="block rounded-xl bg-raised px-4 py-3 transition-colors duration-150 hover:bg-line">
                      <span className="font-medium">{p.title}</span>
                      <span className="mt-1 block text-xs text-muted">회차, 등급, 구역을 정하고 공개하세요</span>
                    </Link>
                  </li>
                ))}
              </ul>
            )}
          </Panel>
          <Panel title="취소 규정" actions={<Link href="/seller/cancel-policies" className="text-sm text-secondary hover:text-foreground">관리</Link>}>
            {policies.data && (
              <ul className="space-y-2 text-sm">
                {policies.data.content.map((p) => (
                  <li key={p.cancelPolicyId} className="flex items-center justify-between">
                    <span>{p.name}</span>
                    {p.isDefault && <span className="badge">기본</span>}
                  </li>
                ))}
              </ul>
            )}
          </Panel>
        </div>
      </div>
    </>
  );
}
