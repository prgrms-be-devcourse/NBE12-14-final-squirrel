"use client";

import { useState } from "react";
import { ApiError, useApi } from "@/lib/api";
import { GENRE_LABEL } from "@/lib/format";
import { RECEIVE_LABEL, toInputDateTime, toServerDateTime } from "@/lib/console";
import type {
  CancelPolicySummary,
  Genre,
  Page,
  PerformanceForm,
  ReceiveType,
  SellerPerformanceDetail,
  VenueItem,
} from "@/lib/types";
import { Field, Panel } from "@/components/console";
import { Loading } from "@/components/ui";

const GENRES: Genre[] = ["CONCERT", "MUSICAL", "PLAY", "CLASSIC", "ETC"];

type Values = {
  venueId: string;
  cancelPolicyId: string;
  title: string;
  genre: Genre;
  ageLimit: string;
  runningTime: string;
  castInfo: string;
  posterUrl: string;
  notice: string;
  guide: string;
  saleOpenAt: string;
  saleCloseAt: string;
  maxPerPerson: string;
  receiveType: ReceiveType | "";
  bookingFee: string;
};

function initial(p?: SellerPerformanceDetail): Values {
  return {
    venueId: p ? String(p.venue.venueId) : "",
    cancelPolicyId: p ? String(p.cancelPolicyId) : "",
    title: p?.title ?? "",
    genre: p?.genre ?? "CONCERT",
    ageLimit: String(p?.ageLimit ?? 12),
    runningTime: String(p?.runningTime ?? 120),
    castInfo: p?.castInfo ?? "",
    posterUrl: p?.posterUrl ?? "",
    notice: p?.notice ?? "",
    guide: p?.guide ?? "",
    saleOpenAt: toInputDateTime(p?.saleOpenAt ?? null),
    saleCloseAt: toInputDateTime(p?.saleCloseAt ?? null),
    maxPerPerson: p?.maxPerPerson != null ? String(p.maxPerPerson) : "",
    receiveType: p?.receiveType ?? "",
    bookingFee: p?.bookingFee != null ? String(p.bookingFee) : "",
  };
}

// 공연 등록(SLR-02)과 수정(SLR-03)이 같이 쓰는 입력 화면
export function PerformanceFormView({
  existing,
  submitLabel,
  onSubmit,
}: {
  existing?: SellerPerformanceDetail;
  submitLabel: string;
  onSubmit: (form: PerformanceForm) => Promise<void>;
}) {
  const venues = useApi<Page<VenueItem>>("/api/v1/seller/venues", { size: 20 });
  const policies = useApi<Page<CancelPolicySummary>>("/api/v1/seller/cancel-policies", { size: 20 });

  const [v, setV] = useState<Values>(() => initial(existing));
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState<string | null>(null);

  // 공개된 공연은 공연장과 취소 규정을 바꿀 수 없다
  const locked = existing?.status === "PUBLISHED";

  const set =
    (key: keyof Values) =>
    (e: React.ChangeEvent<HTMLInputElement | HTMLSelectElement | HTMLTextAreaElement>) =>
      setV({ ...v, [key]: e.target.value });

  async function submit(event: React.FormEvent) {
    event.preventDefault();
    setBusy(true);
    setMessage(null);
    const number = (text: string) => (text.trim() === "" ? null : Number(text));
    try {
      await onSubmit({
        venueId: Number(v.venueId),
        cancelPolicyId: Number(v.cancelPolicyId),
        title: v.title,
        genre: v.genre,
        ageLimit: Number(v.ageLimit),
        runningTime: Number(v.runningTime),
        castInfo: v.castInfo.trim() || null,
        posterUrl: v.posterUrl.trim(),
        notice: v.notice.trim() || null,
        guide: v.guide.trim() || null,
        saleOpenAt: toServerDateTime(v.saleOpenAt),
        saleCloseAt: toServerDateTime(v.saleCloseAt),
        maxPerPerson: number(v.maxPerPerson),
        receiveType: v.receiveType || null,
        bookingFee: number(v.bookingFee),
        images: existing?.images ?? [],
      });
    } catch (error) {
      setMessage(error instanceof ApiError ? error.message : "저장하지 못했습니다");
    } finally {
      setBusy(false);
    }
  }

  if (venues.loading || policies.loading) return <Loading />;

  return (
    <form onSubmit={submit} className="space-y-6">
      <Panel title="기본 정보">
        <div className="grid gap-5 sm:grid-cols-2">
          <Field label="공연 제목" className="sm:col-span-2">
            <input className="input" value={v.title} onChange={set("title")} maxLength={200} required />
          </Field>
          <Field label="장르">
            <select className="input" value={v.genre} onChange={set("genre")}>
              {GENRES.map((g) => (
                <option key={g} value={g}>
                  {GENRE_LABEL[g]}
                </option>
              ))}
            </select>
          </Field>
          <Field label="공연장" hint={locked ? "공개된 공연은 바꿀 수 없습니다" : "작성 중일 때만 바꿀 수 있습니다"}>
            <select className="input" value={v.venueId} onChange={set("venueId")} disabled={locked} required>
              <option value="">선택하세요</option>
              {venues.data?.content.map((venue) => (
                <option key={venue.venueId} value={venue.venueId}>
                  {venue.name} · {venue.region}
                </option>
              ))}
            </select>
          </Field>
          <Field label="관람 연령" hint="0이면 전체 관람가">
            <input className="input" type="number" min={0} max={100} value={v.ageLimit} onChange={set("ageLimit")} required />
          </Field>
          <Field label="관람 시간 (분)">
            <input className="input" type="number" min={1} max={1440} value={v.runningTime} onChange={set("runningTime")} required />
          </Field>
          <Field label="출연진" className="sm:col-span-2">
            <input className="input" value={v.castInfo} onChange={set("castInfo")} />
          </Field>
          <Field label="포스터 주소" hint="http:// 또는 https:// 로 시작해야 합니다" className="sm:col-span-2">
            <input className="input" type="url" value={v.posterUrl} onChange={set("posterUrl")} placeholder="https://" required />
          </Field>
          <Field label="공지" className="sm:col-span-2">
            <textarea className="input h-24 py-3" value={v.notice} onChange={set("notice")} />
          </Field>
          <Field label="관람 안내" className="sm:col-span-2">
            <textarea className="input h-24 py-3" value={v.guide} onChange={set("guide")} />
          </Field>
        </div>
      </Panel>

      <Panel title="판매 설정" sub="공개하기 전까지 모두 입력해야 합니다">
        <div className="grid gap-5 sm:grid-cols-2">
          <Field label="예매 오픈">
            <input className="input" type="datetime-local" value={v.saleOpenAt} onChange={set("saleOpenAt")} />
          </Field>
          <Field label="예매 마감">
            <input className="input" type="datetime-local" value={v.saleCloseAt} onChange={set("saleCloseAt")} />
          </Field>
          <Field label="1인 최대 매수" hint="1~5매">
            <input className="input" type="number" min={1} max={5} value={v.maxPerPerson} onChange={set("maxPerPerson")} />
          </Field>
          <Field label="예매 수수료 (원)" hint="0~10,000원, 1매 기준">
            <input className="input" type="number" min={0} max={10000} step={100} value={v.bookingFee} onChange={set("bookingFee")} />
          </Field>
          <Field label="수령 방법">
            <select className="input" value={v.receiveType} onChange={set("receiveType")}>
              <option value="">선택하세요</option>
              {(Object.keys(RECEIVE_LABEL) as ReceiveType[]).map((type) => (
                <option key={type} value={type}>
                  {RECEIVE_LABEL[type]}
                </option>
              ))}
            </select>
          </Field>
          <Field label="취소 규정" hint={locked ? "공개된 공연은 바꿀 수 없습니다" : undefined}>
            <select className="input" value={v.cancelPolicyId} onChange={set("cancelPolicyId")} disabled={locked} required>
              <option value="">선택하세요</option>
              {policies.data?.content.map((policy) => (
                <option key={policy.cancelPolicyId} value={policy.cancelPolicyId}>
                  {policy.name}
                  {policy.isDefault ? " (기본)" : ""}
                </option>
              ))}
            </select>
          </Field>
        </div>
      </Panel>

      {message && <p className="text-sm text-accent-strong">{message}</p>}
      <div className="flex justify-end">
        <button type="submit" className="btn btn-primary btn-lg" disabled={busy}>
          {busy ? "저장 중" : submitLabel}
        </button>
      </div>
    </form>
  );
}
