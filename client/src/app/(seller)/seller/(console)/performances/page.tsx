"use client";

import { Plus } from "lucide-react";
import Link from "next/link";
import { useState } from "react";
import { useApi } from "@/lib/api";
import { dateTimeText } from "@/lib/format";
import type { Page, PerformanceStatus, SellerPerformanceSummary } from "@/lib/types";
import { ConsoleTitle, Table } from "@/components/console";
import { Empty, ErrorBox, Loading, Pagination } from "@/components/ui";
import { PERFORMANCE_BADGE, PERFORMANCE_LABEL } from "@/lib/console";

const FILTERS: { value: PerformanceStatus | ""; label: string }[] = [
  { value: "", label: "전체" },
  { value: "DRAFT", label: "작성 중" },
  { value: "PUBLISHED", label: "공개" },
  { value: "CANCELED", label: "취소" },
];

export default function SellerPerformancesPage() {
  const [status, setStatus] = useState<PerformanceStatus | "">("");
  const [page, setPage] = useState(0);
  const { data, error, loading, reload } = useApi<Page<SellerPerformanceSummary>>("/api/v1/seller/performances", {
    status,
    page,
  });

  return (
    <>
      <ConsoleTitle
        title="공연"
        sub="공연을 만들고 회차, 등급, 구역을 정한 뒤 공개합니다"
        actions={
          <Link href="/seller/performances/new" className="btn btn-primary">
            <Plus size={16} strokeWidth={1.5} />
            공연 등록
          </Link>
        }
      />

      <div className="mb-5 flex flex-wrap gap-2">
        {FILTERS.map((f) => (
          <button
            key={f.value || "ALL"}
            type="button"
            className="chip"
            aria-pressed={status === f.value}
            onClick={() => {
              setStatus(f.value);
              setPage(0);
            }}
          >
            {f.label}
          </button>
        ))}
      </div>

      {loading && <Loading />}
      {error && <ErrorBox error={error} onRetry={reload} />}
      {data && data.content.length === 0 && <Empty>해당하는 공연이 없습니다</Empty>}
      {data && data.content.length > 0 && (
        <>
          <Table head={["공연", "상태", "공연장", "회차", "등록일"]}>
            {data.content.map((p) => (
              <tr key={p.performanceId} className="hover:bg-raised/60">
                <td className="px-5 py-4 font-medium">
                  <Link href={`/seller/performances/${p.performanceId}`} className="hover:underline">
                    {p.title}
                  </Link>
                </td>
                <td className="px-5 py-4">
                  <span className={PERFORMANCE_BADGE[p.status]}>{PERFORMANCE_LABEL[p.status]}</span>
                </td>
                <td className="px-5 py-4 text-secondary">{p.venueName ?? "-"}</td>
                <td className="px-5 py-4">{p.scheduleCount}</td>
                <td className="px-5 py-4 text-muted">{dateTimeText(p.createdAt)}</td>
              </tr>
            ))}
          </Table>
          <Pagination page={data.page} totalPages={data.totalPages} onChange={setPage} />
        </>
      )}
    </>
  );
}
