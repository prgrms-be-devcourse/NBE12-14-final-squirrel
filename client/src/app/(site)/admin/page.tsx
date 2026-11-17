"use client";

import { useRouter } from "next/navigation";
import { useState } from "react";
import { ApiError, api, useApi } from "@/lib/api";
import { dateTimeText } from "@/lib/format";
import type { BusinessStatus, BusinessSummary, Page } from "@/lib/types";
import { Empty, ErrorBox, Loading, PageTitle, Pagination } from "@/components/ui";

const STATUS_LABEL: Record<BusinessStatus | "ALL", string> = {
  PENDING: "승인 대기",
  APPROVED: "승인",
  REJECTED: "반려",
  SUSPENDED: "정지",
  ALL: "전체",
};

const STATUS_BADGE: Record<BusinessStatus, string> = {
  PENDING: "badge badge-warn",
  APPROVED: "badge badge-ok",
  REJECTED: "badge badge-accent",
  SUSPENDED: "badge",
};

export default function AdminPage() {
  const router = useRouter();
  const [status, setStatus] = useState<BusinessStatus | "ALL">("PENDING");
  const [keyword, setKeyword] = useState("");
  const [page, setPage] = useState(0);
  const { data, error, loading, reload } = useApi<Page<BusinessSummary>>("/api/v1/admin/seller-businesses", {
    status,
    keyword,
    page,
  });
  const [busyId, setBusyId] = useState<number | null>(null);
  const [message, setMessage] = useState<string | null>(null);

  if (error?.status === 401) {
    router.push("/login");
    return null;
  }

  async function review(business: BusinessSummary, action: "approve" | "reject") {
    let body: { reason: string } | undefined;
    if (action === "reject") {
      const reason = window.prompt(`${business.companyName} 반려 사유 (최대 500자)`)?.trim();
      if (!reason) return;
      body = { reason };
    }
    setBusyId(business.businessId);
    setMessage(null);
    try {
      await api(`/api/v1/admin/seller-businesses/${business.businessId}/${action}`, {
        method: "POST",
        body,
      });
      setMessage(`${business.companyName}을(를) ${action === "approve" ? "승인" : "반려"}했습니다`);
      reload();
    } catch (e) {
      setMessage(e instanceof ApiError ? e.message : "처리하지 못했습니다");
    } finally {
      setBusyId(null);
    }
  }

  return (
    <>
      <PageTitle title="사업체 심사" sub="판매자 가입 신청을 승인하거나 반려합니다" />

      <form
        className="mb-5 flex flex-wrap items-center gap-2"
        onSubmit={(event) => {
          event.preventDefault();
          setPage(0);
          reload();
        }}
      >
        {(Object.keys(STATUS_LABEL) as (BusinessStatus | "ALL")[]).map((value) => (
          <button
            key={value}
            type="button"
            className={`btn ${status === value ? "btn-dark" : ""}`}
            onClick={() => {
              setStatus(value);
              setPage(0);
            }}
          >
            {STATUS_LABEL[value]}
          </button>
        ))}
        <input
          className="input ml-auto max-w-xs"
          placeholder="상호, 대표자, 사업자번호"
          value={keyword}
          onChange={(e) => {
            setKeyword(e.target.value);
            setPage(0);
          }}
        />
      </form>

      {message && <p className="mb-4 text-sm text-accent-strong">{message}</p>}
      {loading && <Loading />}
      {error && <ErrorBox error={error} onRetry={reload} />}
      {data && data.content.length === 0 && <Empty>해당하는 사업체가 없습니다</Empty>}
      {data && data.content.length > 0 && (
        <>
          <table className="card w-full overflow-hidden text-sm">
            <thead className="bg-raised text-left text-muted">
              <tr>
                <th className="px-4 py-2 font-normal">상호</th>
                <th className="hidden px-4 py-2 font-normal sm:table-cell">대표자</th>
                <th className="hidden px-4 py-2 font-normal md:table-cell">사업자번호</th>
                <th className="px-4 py-2 font-normal">상태</th>
                <th className="hidden px-4 py-2 font-normal md:table-cell">신청일</th>
                <th className="px-4 py-2" />
              </tr>
            </thead>
            <tbody>
              {data.content.map((b) => (
                <tr key={b.businessId} className="border-t border-line">
                  <td className="px-4 py-3 font-medium">{b.companyName}</td>
                  <td className="hidden px-4 py-3 sm:table-cell">{b.representativeName}</td>
                  <td className="hidden px-4 py-3 text-muted md:table-cell">{b.businessNumber}</td>
                  <td className="px-4 py-3">
                    <span className={STATUS_BADGE[b.status]}>{STATUS_LABEL[b.status]}</span>
                  </td>
                  <td className="hidden px-4 py-3 text-muted md:table-cell">{dateTimeText(b.createdAt)}</td>
                  <td className="px-4 py-3 text-right">
                    {b.status === "PENDING" && (
                      <span className="inline-flex gap-2">
                        <button
                          type="button"
                          className="btn"
                          disabled={busyId === b.businessId}
                          onClick={() => review(b, "approve")}
                        >
                          승인
                        </button>
                        <button
                          type="button"
                          className="btn"
                          disabled={busyId === b.businessId}
                          onClick={() => review(b, "reject")}
                        >
                          반려
                        </button>
                      </span>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
          <Pagination page={data.page} totalPages={data.totalPages} onChange={setPage} />
        </>
      )}
    </>
  );
}
