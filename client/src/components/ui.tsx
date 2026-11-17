"use client";

import { ChevronLeft, ChevronRight } from "lucide-react";
import { useState } from "react";
import { GENRE_COLOR, GENRE_LABEL } from "@/lib/format";
import type { ApiError } from "@/lib/api";
import type { Genre } from "@/lib/types";

// 포스터가 없거나 불러오지 못하면 장르 색 단색 타일에 제목을 보여 준다.
// 색은 제목이 아니라 장르마다 하나로 고정해야 목록이 산만하지 않다.
export function Poster({
  title,
  genre,
  url,
  className = "",
}: {
  title: string;
  genre?: Genre;
  url: string | null;
  className?: string;
}) {
  const [failed, setFailed] = useState(false);

  if (url && !failed) {
    return (
      // eslint-disable-next-line @next/next/no-img-element
      <img
        src={url}
        alt={title}
        onError={() => setFailed(true)}
        className={`aspect-[3/4] w-full rounded-2xl object-cover ${className}`}
      />
    );
  }
  return (
    <div
      className={`flex aspect-[3/4] w-full flex-col justify-between rounded-2xl p-5 text-white ${className}`}
      style={{ backgroundColor: genre ? GENRE_COLOR[genre] : "#4b5060" }}
      role="img"
      aria-label={title}
    >
      <span className="text-xs font-medium text-white/80">{genre ? GENRE_LABEL[genre] : "공연"}</span>
      <span className="text-xl leading-snug font-semibold break-keep">{title}</span>
    </div>
  );
}

export function Loading({ label = "불러오는 중" }: { label?: string }) {
  return <p className="py-16 text-center text-sm text-muted">{label}</p>;
}

export function Empty({ children }: { children: React.ReactNode }) {
  return <p className="py-16 text-center text-sm text-muted">{children}</p>;
}

export function ErrorBox({ error, onRetry }: { error: ApiError; onRetry?: () => void }) {
  return (
    <div className="rounded-xl border border-accent/30 bg-accent-soft px-4 py-3 text-sm text-accent-strong">
      <p>{error.message}</p>
      {onRetry && (
        <button type="button" onClick={onRetry} className="mt-2 underline">
          다시 시도
        </button>
      )}
    </div>
  );
}

export function Pagination({
  page,
  totalPages,
  onChange,
}: {
  page: number;
  totalPages: number;
  onChange: (page: number) => void;
}) {
  if (totalPages <= 1) return null;
  return (
    <div className="mt-10 flex items-center justify-center gap-4 text-sm">
      <button
        type="button"
        className="btn rounded-full"
        disabled={page === 0}
        onClick={() => onChange(page - 1)}
        aria-label="이전 페이지"
      >
        <ChevronLeft size={16} strokeWidth={1.5} />
      </button>
      <span className="text-secondary">
        {page + 1} / {totalPages}
      </span>
      <button
        type="button"
        className="btn rounded-full"
        disabled={page + 1 >= totalPages}
        onClick={() => onChange(page + 1)}
        aria-label="다음 페이지"
      >
        <ChevronRight size={16} strokeWidth={1.5} />
      </button>
    </div>
  );
}

export function PageTitle({ title, sub }: { title: string; sub?: string }) {
  return (
    <div className="mb-8">
      <h1 className="text-3xl leading-tight font-semibold">{title}</h1>
      {sub && <p className="mt-3 text-secondary">{sub}</p>}
    </div>
  );
}
