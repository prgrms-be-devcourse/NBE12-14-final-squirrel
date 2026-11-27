"use client";

import { ArrowRight } from "lucide-react";
import Link from "next/link";

import { useState } from "react";
import { useRouteParams } from "@/lib/route-params";
import { useApi } from "@/lib/api";
import { GENRE_LABEL, SALE_LABEL, dateTimeText, gradeColor, gradeLabel, won } from "@/lib/format";
import type { CancelRule, PerformanceDetail } from "@/lib/types";
import { ErrorBox, Loading, Poster } from "@/components/ui";

const RECEIVE_LABEL = { ONSITE: "현장 수령", DELIVERY: "배송", BOTH: "현장 수령 또는 배송" };

function ruleLabel(rule: CancelRule): string {
  if (rule.daysTo === null) return `${rule.daysFrom}일 전까지`;
  if (rule.daysFrom === rule.daysTo) return rule.daysFrom === 0 ? "공연 당일" : `${rule.daysFrom}일 전`;
  return `${rule.daysTo}일 전 ~ ${rule.daysFrom}일 전`;
}

export default function PerformanceDetailPage() {
  const { id } = useRouteParams<{ id: string }>("/performances/[id]");
  const { data: p, error, loading, reload } = useApi<PerformanceDetail>(`/api/v1/performances/${id}`);
  const [chosen, setChosen] = useState<number | null>(null);

  if (loading) return <Loading />;
  if (error) return <ErrorBox error={error} onRetry={reload} />;
  if (!p) return null;

  const bookable = (s: PerformanceDetail["schedules"][number]) =>
    s.status === "ON_SALE" && s.saleStatus === "ON_SALE";
  const selected = chosen ?? p.schedules.find(bookable)?.scheduleId ?? null;
  const selectedSchedule = p.schedules.find((s) => s.scheduleId === selected);
  const maxPrice = Math.max(...p.grades.map((g) => g.price), 0);

  return (
    <div className="grid gap-12 lg:grid-cols-[1fr_380px]">
      <div className="min-w-0 space-y-14">
        <section className="flex flex-col gap-8 sm:flex-row">
          <div className="w-48 shrink-0 sm:w-56">
            <Poster title={p.title} genre={p.genre} url={p.posterUrl} />
          </div>
          <div className="min-w-0">
            <div className="flex flex-wrap items-center gap-2">
              <span className="badge">{GENRE_LABEL[p.genre]}</span>
              <span className="badge">{p.ageLimit === 0 ? "전체 관람가" : `${p.ageLimit}세 이상`}</span>
            </div>
            <h1 className="mt-4 text-4xl leading-tight font-semibold break-keep">{p.title}</h1>
            <dl className="mt-8 grid grid-cols-[88px_1fr] gap-x-4 gap-y-3 text-sm">
              <dt className="text-muted">장소</dt>
              <dd>{p.venue ? `${p.venue.name} · ${p.venue.address}` : "미정"}</dd>
              <dt className="text-muted">관람 시간</dt>
              <dd>{p.runningTime}분</dd>
              {p.castInfo && (
                <>
                  <dt className="text-muted">출연</dt>
                  <dd>{p.castInfo}</dd>
                </>
              )}
              <dt className="text-muted">예매 기간</dt>
              <dd>
                {dateTimeText(p.sale.saleOpenAt)} ~ {dateTimeText(p.sale.saleCloseAt)}
              </dd>
              <dt className="text-muted">수령 방법</dt>
              <dd>{RECEIVE_LABEL[p.sale.receiveType]}</dd>
              <dt className="text-muted">1인 최대</dt>
              <dd>{p.sale.maxPerPerson}매</dd>
            </dl>
          </div>
        </section>

        {(p.notice || p.guide || p.commonGuide) && (
          <section>
            <h2 className="text-2xl font-semibold">관람 안내</h2>
            <ul className="mt-6 space-y-3 leading-7 text-secondary">
              {p.notice && <li>{p.notice}</li>}
              {p.guide && <li>{p.guide}</li>}
              {p.commonGuide?.bookingGuide && <li>{p.commonGuide.bookingGuide}</li>}
              {p.commonGuide?.refundGuide && <li>{p.commonGuide.refundGuide}</li>}
            </ul>
          </section>
        )}

        <section>
          <h2 className="text-2xl font-semibold">취소 수수료</h2>
          <p className="mt-2 text-sm text-secondary">공연일까지 남은 기간에 따라 티켓 금액의 일부를 수수료로 냅니다</p>
          <ul className="card mt-6 divide-y divide-line">
            {p.cancelPolicy.map((rule) => (
              <li key={rule.daysFrom} className="flex items-center gap-4 px-5 py-4 text-sm">
                <span className="w-32 shrink-0">{ruleLabel(rule)}</span>
                <span className="h-1.5 flex-1 overflow-hidden rounded-full bg-raised">
                  <span
                    className={`block h-full rounded-full ${rule.cancelable ? "bg-accent" : "bg-transparent"}`}
                    style={{ width: `${rule.cancelable ? Math.max(rule.feeRate, 2) : 0}%` }}
                  />
                </span>
                <span className="w-20 shrink-0 text-right">
                  {rule.cancelable ? `${rule.feeRate}%` : <span className="text-muted">취소 불가</span>}
                </span>
              </li>
            ))}
          </ul>
        </section>

        {p.seller && (
          <section className="border-t border-line pt-8 text-sm text-muted">
            <p>
              판매자 {p.seller.companyName} · 대표 {p.seller.representativeName} · 사업자번호{" "}
              {p.seller.businessNumber}
            </p>
            <p className="mt-1">
              문의 {p.seller.contactPhone} · {p.seller.contactEmail}
            </p>
          </section>
        )}
      </div>

      {/* 예매 패널: 회차를 고르고 좌석 선택으로 넘어간다 */}
      <aside className="lg:sticky lg:top-28 lg:self-start">
        <div className="card p-6">
          <h2 className="font-semibold">회차 선택</h2>
          {p.schedules.length === 0 && <p className="mt-4 text-sm text-muted">등록된 회차가 없습니다</p>}
          <ul className="mt-4 space-y-2">
            {p.schedules.map((s) => {
              const ok = bookable(s);
              const active = s.scheduleId === selected;
              return (
                <li key={s.scheduleId}>
                  <button
                    type="button"
                    disabled={!ok}
                    onClick={() => setChosen(s.scheduleId)}
                    aria-pressed={active}
                    className={`flex w-full items-center justify-between rounded-xl border px-4 py-3 text-left text-sm transition-colors duration-150 disabled:cursor-not-allowed disabled:opacity-50 ${
                      active ? "border-foreground bg-raised" : "border-line hover:border-[rgba(25,27,33,0.35)]"
                    }`}
                  >
                    <span>{dateTimeText(s.startAt)}</span>
                    {!ok && <span className="text-xs text-muted">{SALE_LABEL[s.saleStatus]}</span>}
                  </button>
                  {/* 선택할 수 없는 버튼은 흐려지므로 사유는 버튼 밖에 둔다 */}
                  {s.pauseReason && (
                    <p className="mt-1 px-4 text-xs break-words text-secondary">중지 사유: {s.pauseReason}</p>
                  )}
                </li>
              );
            })}
          </ul>

          <h2 className="mt-8 font-semibold">가격</h2>
          <ul className="mt-4 space-y-3 text-sm">
            {p.grades.map((g) => (
              <li key={g.gradeId}>
                <div className="flex items-center justify-between">
                  <span className="flex items-center gap-2">
                    <span className="size-3 rounded-full" style={{ backgroundColor: gradeColor(g.name) ?? undefined }} />
                    {gradeLabel(g.name)}
                  </span>
                  <span>{won(g.price)}</span>
                </div>
                <span className="mt-2 block h-1 rounded-full bg-raised">
                  <span
                    className="block h-full rounded-full"
                    style={{ width: `${maxPrice ? (g.price / maxPrice) * 100 : 0}%`, backgroundColor: gradeColor(g.name) ?? undefined }}
                  />
                </span>
              </li>
            ))}
          </ul>
          <p className="mt-4 text-xs text-muted">예매 수수료 1매 {won(p.sale.bookingFee)}</p>

          {selectedSchedule ? (
            <Link
              href={`/performances/${p.performanceId}/schedules/${selectedSchedule.scheduleId}`}
              className="btn btn-primary btn-lg mt-8 w-full"
            >
              좌석 선택하기
              <ArrowRight size={16} strokeWidth={1.5} />
            </Link>
          ) : (
            <button type="button" disabled className="btn btn-lg mt-8 w-full">
              지금은 예매할 수 없습니다
            </button>
          )}
        </div>
      </aside>
    </div>
  );
}
