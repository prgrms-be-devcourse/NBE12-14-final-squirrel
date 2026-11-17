"use client";

import { ArrowRight, ChevronLeft, ChevronRight, Drama, Mic, Piano, Theater, Ticket } from "lucide-react";
import type { LucideIcon } from "lucide-react";
import Link from "next/link";
import { useState } from "react";
import { useApi } from "@/lib/api";
import { GENRE_COLOR, dateTimeText, periodText } from "@/lib/format";
import type { Genre, Page, PerformanceSummary } from "@/lib/types";
import { PerformanceCard } from "@/components/performance-card";
import { Poster } from "@/components/ui";

const CONTAINER = "mx-auto w-full max-w-6xl px-4 sm:px-6";

const CATEGORIES: { genre: Genre | ""; label: string; icon: LucideIcon }[] = [
  { genre: "", label: "전체 공연", icon: Ticket },
  { genre: "CONCERT", label: "콘서트", icon: Mic },
  { genre: "MUSICAL", label: "뮤지컬", icon: Drama },
  { genre: "PLAY", label: "연극", icon: Theater },
  { genre: "CLASSIC", label: "클래식", icon: Piano },
];

const STEPS = [
  {
    no: "1",
    title: "구역을 고릅니다",
    body: "구역마다 등급, 가격, 남은 좌석 수를 보고 원하는 구역을 정합니다.",
  },
  {
    no: "2",
    title: "좌석을 직접 고릅니다",
    body: "열과 번호를 보고 좌석을 선택하면 7분간 잡아 둡니다. 1인 최대 매수는 공연마다 다릅니다.",
  },
  {
    no: "3",
    title: "결제하고, 필요하면 취소합니다",
    body: "취소 수수료는 공연일까지 남은 기간에 따라 달라지고, 예매 전에 공연 상세에서 확인할 수 있습니다.",
  },
];

// 배너 한 장. 포스터가 있으면 그 이미지를, 없으면 장르 색 타일을 오른쪽에 둔다.
function BannerSlide({ p }: { p: PerformanceSummary }) {
  const upcoming = p.saleStatus === "UPCOMING";
  return (
    <div
      className="relative flex h-full items-center justify-between gap-6 overflow-hidden rounded-3xl p-8 text-white sm:p-12"
      style={{ backgroundColor: GENRE_COLOR[p.genre] }}
    >
      <div className="min-w-0 max-w-md">
        <span className="inline-flex rounded-full bg-white/20 px-3 py-1 text-xs font-medium">
          {upcoming ? `${dateTimeText(p.saleOpenAt)} 오픈` : "지금 예매 중"}
        </span>
        <h2 className="mt-5 text-3xl leading-tight font-semibold break-keep sm:text-5xl">{p.title}</h2>
        <p className="mt-4 text-white/85">
          {p.venueName ?? "장소 미정"} · {periodText(p.firstStartAt, p.lastStartAt)}
        </p>
        <Link
          href={`/performances/${p.performanceId}`}
          className="mt-8 inline-flex h-12 items-center gap-2 rounded-full bg-white px-6 text-sm font-semibold text-foreground transition-opacity duration-150 hover:opacity-90"
        >
          {upcoming ? "자세히 보기" : "예매하기"}
          <ArrowRight size={16} strokeWidth={1.5} />
        </Link>
      </div>
      <div className="hidden w-44 shrink-0 sm:block md:w-52">
        <div className="rotate-3 overflow-hidden rounded-2xl shadow-[0_16px_40px_rgba(0,0,0,0.3)]">
          <Poster title={p.title} genre={p.genre} url={p.posterUrl} className="!bg-white/15 ring-1 ring-white/30" />
        </div>
      </div>
    </div>
  );
}

function Banner({ items }: { items: PerformanceSummary[] }) {
  const [index, setIndex] = useState(0);
  const last = items.length - 1;
  const go = (next: number) => setIndex(Math.min(Math.max(next, 0), last));

  return (
    <div className="relative overflow-hidden">
      {/* 다음 배너가 오른쪽에 살짝 보이게 해서 넘겨 볼 수 있다는 걸 알린다 */}
      <div
        className="flex gap-4 transition-transform duration-500 [transition-timing-function:var(--ease)]"
        style={{ transform: `translateX(calc(${index} * (-92% - 16px)))` }}
      >
        {items.map((p) => (
          <div key={p.performanceId} className="h-[320px] shrink-0 basis-[92%] sm:h-[380px]">
            <BannerSlide p={p} />
          </div>
        ))}
      </div>
      {items.length > 1 && (
        <>
          <button
            type="button"
            aria-label="이전 배너"
            disabled={index === 0}
            onClick={() => go(index - 1)}
            className="absolute top-1/2 left-4 flex size-11 -translate-y-1/2 items-center justify-center rounded-full bg-white text-foreground shadow-md transition-opacity duration-150 disabled:opacity-0"
          >
            <ChevronLeft size={20} strokeWidth={1.5} />
          </button>
          <button
            type="button"
            aria-label="다음 배너"
            disabled={index === last}
            onClick={() => go(index + 1)}
            className="absolute top-1/2 right-[9%] flex size-11 -translate-y-1/2 items-center justify-center rounded-full bg-white text-foreground shadow-md transition-opacity duration-150 disabled:opacity-0"
          >
            <ChevronRight size={20} strokeWidth={1.5} />
          </button>
          <span className="absolute bottom-5 left-[calc(92%-88px)] rounded-full bg-black/45 px-3 py-1 text-xs font-medium text-white">
            {index + 1} / {items.length}
          </span>
        </>
      )}
    </div>
  );
}

// 가로로 넘겨 보는 포스터 줄
function PosterRow({ items }: { items: PerformanceSummary[] }) {
  return (
    <ul className="no-scrollbar -mx-4 flex gap-5 overflow-x-auto px-4 pb-2 sm:-mx-6 sm:px-6">
      {items.map((p) => (
        <li key={p.performanceId} className="w-40 shrink-0 sm:w-52">
          <PerformanceCard p={p} />
        </li>
      ))}
    </ul>
  );
}

function SectionHeader({ title, sub, href }: { title: string; sub: string; href?: string }) {
  return (
    <div className="mb-6 flex items-end justify-between gap-4">
      <div>
        <h2 className="text-2xl font-semibold">{title}</h2>
        <p className="mt-1 text-secondary">{sub}</p>
      </div>
      {href && (
        <Link
          href={href}
          className="inline-flex shrink-0 items-center gap-1 text-sm text-secondary transition-colors duration-150 hover:text-foreground"
        >
          전체 보기
          <ChevronRight size={16} strokeWidth={1.5} />
        </Link>
      )}
    </div>
  );
}

export default function LandingPage() {
  const list = useApi<Page<PerformanceSummary>>("/api/v1/performances", { size: 20 });
  const items = list.data?.content ?? [];
  const onSale = items.filter((p) => p.saleStatus === "ON_SALE");
  const upcoming = items.filter((p) => p.saleStatus === "UPCOMING");
  const banners = [...onSale, ...upcoming].slice(0, 5);

  return (
    <div className="pb-8">
      {/* 큰 배너: 지금 예매 중이거나 곧 열리는 공연 */}
      <section className={`${CONTAINER} pt-6`}>
        {banners.length > 0 ? (
          <div className="rise">
            <Banner items={banners} />
          </div>
        ) : (
          <div className="rise flex h-[320px] items-center rounded-3xl bg-foreground p-12 text-white sm:h-[380px]">
            <div>
              <h1 className="text-3xl leading-tight font-semibold sm:text-5xl">
                원하는 자리를 직접 골라
                <br />
                공연을 예매하세요
              </h1>
              <p className="mt-4 text-white/80">구역별 남은 좌석을 보고 7분 안에 결제합니다</p>
            </div>
          </div>
        )}
      </section>

      {/* 안내 줄 */}
      <section className={`${CONTAINER} mt-6`}>
        <div className="rise rise-2 flex items-center gap-3 rounded-2xl bg-deep px-5 py-4 text-sm">
          <span className="rounded-full bg-accent px-3 py-1 text-xs font-medium text-white">안내</span>
          <p className="text-secondary">선택한 좌석은 7분간 잡아 두며, 시간이 지나면 자동으로 풀립니다</p>
        </div>
      </section>

      {/* 카테고리 */}
      <section className={`${CONTAINER} mt-12`}>
        <ul className="rise rise-3 grid grid-cols-5 gap-2">
          {CATEGORIES.map(({ genre, label, icon: Icon }) => {
            const color = genre ? GENRE_COLOR[genre] : "#191b21";
            return (
              <li key={label}>
                <Link
                  href={genre ? `/performances?genre=${genre}` : "/performances"}
                  className="group flex flex-col items-center gap-3 py-2 text-sm"
                >
                  <span
                    className="flex size-14 items-center justify-center rounded-2xl transition-transform duration-150 group-active:scale-95 sm:size-16"
                    style={{ backgroundColor: `${color}1a`, color }}
                  >
                    <Icon size={26} strokeWidth={1.5} />
                  </span>
                  <span className="text-secondary transition-colors duration-150 group-hover:text-foreground">
                    {label}
                  </span>
                </Link>
              </li>
            );
          })}
        </ul>
      </section>

      {/* 지금 예매 중 */}
      <section className={`${CONTAINER} mt-16`}>
        <SectionHeader title="지금 예매 중" sub="바로 좌석을 고를 수 있는 공연입니다" href="/performances" />
        {list.loading && <p className="py-12 text-center text-sm text-muted">불러오는 중</p>}
        {list.error && <p className="py-12 text-center text-sm text-muted">{list.error.message}</p>}
        {!list.loading && !list.error && onSale.length === 0 && (
          <p className="py-12 text-center text-sm text-muted">지금 예매할 수 있는 공연이 없습니다</p>
        )}
        <PosterRow items={onSale} />
      </section>

      {/* 오픈 예정 */}
      {upcoming.length > 0 && (
        <section className={`${CONTAINER} mt-16`}>
          <SectionHeader title="오픈 예정" sub="예매가 열리는 시각에 맞춰 들어오세요" />
          <ul className="grid gap-4 md:grid-cols-2">
            {upcoming.slice(0, 4).map((p) => (
              <li key={p.performanceId}>
                <Link
                  href={`/performances/${p.performanceId}`}
                  className="card flex gap-5 p-4 transition-colors duration-150 hover:border-[rgba(25,27,33,0.35)]"
                >
                  <div className="w-24 shrink-0">
                    <Poster title={p.title} genre={p.genre} url={p.posterUrl} className="!p-3 !text-sm" />
                  </div>
                  <div className="flex min-w-0 flex-col justify-center">
                    <span className="badge badge-warn w-fit">{dateTimeText(p.saleOpenAt)} 오픈</span>
                    <h3 className="mt-3 truncate font-medium">{p.title}</h3>
                    <p className="mt-1 text-sm text-secondary">{p.venueName ?? "장소 미정"}</p>
                    <p className="text-sm text-muted">{periodText(p.firstStartAt, p.lastStartAt)}</p>
                  </div>
                </Link>
              </li>
            ))}
          </ul>
        </section>
      )}

      {/* 예매 방법 */}
      <section className={`${CONTAINER} mt-16`}>
        <SectionHeader title="예매는 이렇게 진행됩니다" sub="세 단계면 끝납니다" />
        <ol className="grid gap-4 md:grid-cols-3">
          {STEPS.map((step) => (
            <li key={step.no} className="rounded-3xl bg-deep p-7">
              <span className="flex size-10 items-center justify-center rounded-full bg-accent text-lg font-semibold text-white">
                {step.no}
              </span>
              <h3 className="mt-6 text-lg font-semibold">{step.title}</h3>
              <p className="mt-2 leading-7 text-secondary">{step.body}</p>
            </li>
          ))}
        </ol>
      </section>
    </div>
  );
}
