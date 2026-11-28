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
import { ImageUpload } from "@/components/image-upload";
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
  // 상세 이미지. 순서는 목록 순서이고 저장할 때 1부터 번호를 매긴다.
  const [images, setImages] = useState<string[]>(() =>
    [...(existing?.images ?? [])].sort((a, b) => a.sortOrder - b.sortOrder).map((image) => image.url),
  );
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState<string | null>(null);

  // 공개된 공연은 공연장과 취소 규정을 바꿀 수 없다
  const locked = existing?.status === "PUBLISHED";
  // 지금 공연장이 선택 목록에 없을 수 있다(사용 중지됐거나 목록 밖). 그래도 지금 값이 보여야 해서 따로 넣는다.
  const current =
    existing && !venues.data?.content.some((venue) => venue.venueId === existing.venue.venueId) ? existing.venue : null;

  const set =
    (key: keyof Values) =>
    (e: React.ChangeEvent<HTMLInputElement | HTMLSelectElement | HTMLTextAreaElement>) =>
      setV({ ...v, [key]: e.target.value });

  async function submit(event: React.FormEvent) {
    event.preventDefault();
    if (!v.posterUrl) {
      setMessage("포스터 사진을 올려 주세요");
      return;
    }
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
        images: images.map((url, index) => ({ url, sortOrder: index + 1 })),
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
              {current && (
                <option value={current.venueId}>
                  {current.name}
                  {current.status === "INACTIVE" ? " (사용 중지)" : ""}
                </option>
              )}
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
          <div className="sm:col-span-2">
            <span className="label">포스터</span>
            <ImageUpload value={v.posterUrl} purpose="POSTER" label="포스터" onChange={(url) => setV({ ...v, posterUrl: url })} />
            <span className="mt-1 block text-xs text-muted">올린 사진만 쓸 수 있습니다. 다른 사이트 주소는 받지 않습니다</span>
          </div>
          <div className="sm:col-span-2">
            <span className="label">상세 이미지</span>
            <ul className="space-y-3">
              {images.map((url, index) => (
                <li key={url} className="flex items-center gap-3">
                  {/* eslint-disable-next-line @next/next/no-img-element */}
                  <img src={url} alt={`상세 이미지 ${index + 1}`} className="h-16 w-24 rounded-lg border border-line object-cover" />
                  <span className="text-sm text-muted">{index + 1}번</span>
                  <span className="ml-auto flex gap-2">
                    <button
                      type="button"
                      className="btn"
                      disabled={index === 0}
                      onClick={() => setImages(images.map((_, i) => images[i === index - 1 ? index : i === index ? index - 1 : i]))}
                    >
                      위로
                    </button>
                    <button type="button" className="btn" onClick={() => setImages(images.filter((_, i) => i !== index))}>
                      삭제
                    </button>
                  </span>
                </li>
              ))}
            </ul>
            <div className="mt-3">
              <ImageUpload
                key={images.length}
                value=""
                purpose="DETAIL"
                label="상세 이미지"
                onChange={(url) => setImages([...images, url])}
              />
            </div>
            <span className="mt-1 block text-xs text-muted">공연 상세 화면에 순서대로 보입니다</span>
          </div>
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
