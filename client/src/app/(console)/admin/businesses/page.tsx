"use client";

import Link from "next/link";
import { useState } from "react";
import { useApi } from "@/lib/api";
import { dateTimeText } from "@/lib/format";
import { BUSINESS_BADGE, BUSINESS_LABEL } from "@/lib/console";
import type { BusinessStatus, BusinessSummary, Page } from "@/lib/types";
import { ConsoleTitle, Table } from "@/components/console";
import { Empty, ErrorBox, Loading, Pagination } from "@/components/ui";

const STATUSES: (BusinessStatus | "ALL")[] = ["PENDING", "APPROVED", "REJECTED", "SUSPENDED", "ALL"];

export default function BusinessesPage() {
  const [status, setStatus] = useState<BusinessStatus | "ALL">("PENDING");
  const [keywordInput, setKeywordInput] = useState("");
  const [keyword, setKeyword] = useState("");
  const [page, setPage] = useState(0);
  const { data, error, loading, reload } = useApi<Page<BusinessSummary>>("/api/v1/admin/seller-businesses", {
    status,
    keyword,
    page,
  });

  return (
    <>
      <ConsoleTitle title="사업체 심사" sub="판매자 가입 신청을 확인하고 승인하거나 반려합니다" />

      <form
        className="mb-5 flex flex-wrap items-center gap-2"
        onSubmit={(e) => {
          e.preventDefault();
          setPage(0);
          setKeyword(keywordInput.trim());
        }}
      >
        {STATUSES.map((value) => (
          <button
            key={value}
            type="button"
            className="chip"
            aria-pressed={status === value}
            onClick={() => {
              setStatus(value);
              setPage(0);
            }}
          >
            {BUSINESS_LABEL[value]}
          </button>
        ))}
        <input
          className="input ml-auto w-64 rounded-full"
          placeholder="상호, 대표자, 사업자번호"
          value={keywordInput}
          onChange={(e) => setKeywordInput(e.target.value)}
          aria-label="검색어"
        />
      </form>

      {loading && <Loading />}
      {error && <ErrorBox error={error} onRetry={reload} />}
      {data && data.content.length === 0 && <Empty>해당하는 사업체가 없습니다</Empty>}
      {data && data.content.length > 0 && (
        <>
          <Table head={["상호", "대표자", "사업자번호", "상태", "신청일", "처리일"]}>
            {data.content.map((b) => (
              <tr key={b.businessId} className="hover:bg-raised/60">
                <td className="px-5 py-4 font-medium">
                  <Link href={`/admin/businesses/${b.businessId}`} className="hover:underline">
                    {b.companyName}
                  </Link>
                </td>
                <td className="px-5 py-4">{b.representativeName}</td>
                <td className="px-5 py-4 text-muted">{b.businessNumber}</td>
                <td className="px-5 py-4">
                  <span className={BUSINESS_BADGE[b.status]}>{BUSINESS_LABEL[b.status]}</span>
                </td>
                <td className="px-5 py-4 text-muted">{dateTimeText(b.createdAt)}</td>
                <td className="px-5 py-4 text-muted">{b.reviewedAt ? dateTimeText(b.reviewedAt) : "-"}</td>
              </tr>
            ))}
          </Table>
          <Pagination page={data.page} totalPages={data.totalPages} onChange={setPage} />
        </>
      )}
    </>
  );
}
