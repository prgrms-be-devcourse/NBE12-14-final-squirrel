"use client";

import { useRouter, useSearchParams } from "next/navigation";
import { useState } from "react";
import { useApi } from "@/lib/api";
import { GENRE_LABEL } from "@/lib/format";
import type { Genre, Page, PerformanceSummary } from "@/lib/types";
import { PerformanceCard } from "@/components/performance-card";
import { Empty, ErrorBox, Loading, PageTitle, Pagination } from "@/components/ui";

// 서버에 지역 목록 API 가 없어서 공연장이 있는 지역을 적어 둔다. (시드의 인스파이어 아레나는 인천)
// 공연장 지역은 정확히 같은 값으로만 걸러지므로, 새 지역의 공연장을 등록하면 여기에도 더해야 한다.
const REGIONS = ["서울", "경기", "인천", "부산"];
const GENRES: Genre[] = ["CONCERT", "MUSICAL", "PLAY", "CLASSIC", "ETC"];

export default function PerformancesPage() {
  const router = useRouter();
  const searchParams = useSearchParams();
  // 장르와 검색어는 주소에 담아 두면 헤더 검색창, 메인의 장르 링크, 뒤로 가기가 그대로 동작한다
  const genre = searchParams.get("genre") ?? "";
  const keyword = searchParams.get("keyword") ?? "";

  const [region, setRegion] = useState("");
  const [page, setPage] = useState(0);

  const { data, error, loading, reload } = useApi<Page<PerformanceSummary>>("/api/v1/performances", {
    genre,
    region,
    keyword,
    page,
    size: 12,
  });

  function chooseGenre(value: string) {
    setPage(0);
    const params = new URLSearchParams();
    if (value) params.set("genre", value);
    if (keyword) params.set("keyword", keyword);
    const text = params.toString();
    router.replace(text ? `/performances?${text}` : "/performances");
  }

  const title = keyword ? `“${keyword}” 검색 결과` : genre ? GENRE_LABEL[genre as Genre] ?? "공연" : "전체 공연";

  return (
    <>
      <PageTitle title={title} sub={data ? `${data.totalElements}개의 공연` : undefined} />

      <div className="mb-10 flex flex-col gap-4 sm:flex-row sm:items-center sm:justify-between">
        <div className="no-scrollbar -mx-4 flex gap-2 overflow-x-auto px-4 sm:mx-0 sm:flex-wrap sm:px-0">
          {[["", "전체"], ...GENRES.map((g) => [g, GENRE_LABEL[g]])].map(([value, label]) => (
            <button
              key={value || "ALL"}
              type="button"
              className="chip"
              aria-pressed={genre === value}
              onClick={() => chooseGenre(value)}
            >
              {label}
            </button>
          ))}
        </div>
        <select
          className="input w-32 shrink-0 rounded-full"
          value={region}
          onChange={(event) => {
            setRegion(event.target.value);
            setPage(0);
          }}
          aria-label="지역"
        >
          <option value="">전체 지역</option>
          {REGIONS.map((r) => (
            <option key={r} value={r}>
              {r}
            </option>
          ))}
        </select>
      </div>

      {loading && <Loading />}
      {error && <ErrorBox error={error} onRetry={reload} />}
      {data && data.content.length === 0 && <Empty>조건에 맞는 공연이 없습니다</Empty>}

      {data && data.content.length > 0 && (
        <>
          <ul className="grid grid-cols-2 gap-x-5 gap-y-10 md:grid-cols-3 lg:grid-cols-4">
            {data.content.map((p) => (
              <li key={p.performanceId}>
                <PerformanceCard p={p} />
              </li>
            ))}
          </ul>
          <Pagination page={data.page} totalPages={data.totalPages} onChange={setPage} />
        </>
      )}
    </>
  );
}
