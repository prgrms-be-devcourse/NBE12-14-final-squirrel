"use client";

import Link from "next/link";
import { useApi } from "@/lib/api";
import { dateTimeText } from "@/lib/format";
import type { BusinessSummary, Page, RefundItem } from "@/lib/types";
import { ConsoleTitle, Panel, StatCard } from "@/components/console";
import { ErrorBox, Loading } from "@/components/ui";

export default function AdminOverviewPage() {
  const pending = useApi<Page<BusinessSummary>>("/api/v1/admin/seller-businesses", { status: "PENDING", size: 5 });
  const refunds = useApi<Page<RefundItem>>("/api/v1/admin/refunds", { status: "FAILED", exhaustedOnly: "true", size: 5 });

  return (
    <>
      <ConsoleTitle title="개요" sub="지금 처리해야 할 일" />

      <div className="grid gap-4 sm:grid-cols-2">
        <StatCard
          label="승인 대기 사업체"
          value={pending.data?.totalElements ?? "-"}
          tone={pending.data?.totalElements ? "accent" : undefined}
          hint="판매자 가입 신청 심사"
        />
        <StatCard
          label="다시 처리할 환불"
          value={refunds.data?.totalElements ?? "-"}
          tone={refunds.data?.totalElements ? "accent" : undefined}
          hint="자동 재시도를 다 쓰고도 실패한 환불"
        />
      </div>

      <div className="mt-8 grid gap-6 xl:grid-cols-2">
        <Panel title="승인 대기" actions={<Link href="/admin/businesses" className="text-sm text-secondary hover:text-foreground">전체 보기</Link>}>
          {pending.loading && <Loading />}
          {pending.error && <ErrorBox error={pending.error} onRetry={pending.reload} />}
          {pending.data && pending.data.content.length === 0 && <p className="text-sm text-muted">대기 중인 신청이 없습니다</p>}
          <ul className="divide-y divide-line text-sm">
            {pending.data?.content.map((b) => (
              <li key={b.businessId}>
                <Link href={`/admin/businesses/${b.businessId}`} className="flex items-center justify-between py-3 hover:underline">
                  <span>
                    <span className="font-medium">{b.companyName}</span>
                    <span className="ml-2 text-muted">{b.representativeName}</span>
                  </span>
                  <span className="text-xs text-muted">{dateTimeText(b.createdAt)}</span>
                </Link>
              </li>
            ))}
          </ul>
        </Panel>

        <Panel title="실패한 환불" actions={<Link href="/admin/refunds" className="text-sm text-secondary hover:text-foreground">처리하기</Link>}>
          {refunds.loading && <Loading />}
          {refunds.error && <ErrorBox error={refunds.error} onRetry={refunds.reload} />}
          {refunds.data && refunds.data.content.length === 0 && <p className="text-sm text-muted">다시 처리할 환불이 없습니다</p>}
          <ul className="divide-y divide-line text-sm">
            {refunds.data?.content.map((r) => (
              <li key={r.refundId} className="flex items-center justify-between py-3">
                <span>
                  <span className="font-medium">{r.bookingNo}</span>
                  <span className="ml-2 text-muted">{r.failReason}</span>
                </span>
                <span>{r.amount.toLocaleString("ko-KR")}원</span>
              </li>
            ))}
          </ul>
        </Panel>
      </div>
    </>
  );
}
