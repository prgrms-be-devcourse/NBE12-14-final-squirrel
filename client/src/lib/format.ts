import type { BookingStatus, Genre, SaleStatus } from "@/lib/types";

const WEEKDAYS = ["일", "월", "화", "수", "목", "금", "토"];

// 서버 시각은 시간대 없이 yyyy-MM-ddTHH:mm:ss 로 내려온다. 그대로 로컬 시각으로 읽는다.
function parse(iso: string): Date {
  return new Date(iso);
}

const pad = (n: number) => String(n).padStart(2, "0");

export function won(amount: number): string {
  return `${amount.toLocaleString("ko-KR")}원`;
}

export function dateText(iso: string): string {
  const d = parse(iso);
  return `${d.getFullYear()}.${pad(d.getMonth() + 1)}.${pad(d.getDate())} (${WEEKDAYS[d.getDay()]})`;
}

export function timeText(iso: string): string {
  const d = parse(iso);
  return `${pad(d.getHours())}:${pad(d.getMinutes())}`;
}

export function dateTimeText(iso: string): string {
  return `${dateText(iso)} ${timeText(iso)}`;
}

export function periodText(first: string | null, last: string | null): string {
  if (!first) return "일정 미정";
  if (!last || dateText(first) === dateText(last)) return dateText(first);
  return `${dateText(first)} ~ ${dateText(last)}`;
}

export const GENRE_LABEL: Record<Genre, string> = {
  CONCERT: "콘서트",
  MUSICAL: "뮤지컬",
  PLAY: "연극",
  CLASSIC: "클래식",
  ETC: "기타",
};

export const SALE_LABEL: Record<SaleStatus, string> = {
  UPCOMING: "오픈 예정",
  ON_SALE: "예매 중",
  SOLD_OUT: "매진",
  ENDED: "판매 종료",
};

export const BOOKING_BADGE: Record<BookingStatus, string> = {
  PENDING: "badge badge-warn",
  PAID: "badge badge-ok",
  PARTIAL_CANCELED: "badge badge-warn",
  CANCELED: "badge",
  EXPIRED: "badge",
};

export const BOOKING_LABEL: Record<BookingStatus, string> = {
  PENDING: "결제 대기",
  PAID: "예매 완료",
  PARTIAL_CANCELED: "부분 취소",
  CANCELED: "취소",
  EXPIRED: "기한 만료",
};

// 포스터가 없을 때 장르별로 쓰는 색. 제목마다 색이 바뀌면 산만해서 장르당 하나로 고정한다.
export const GENRE_COLOR: Record<Genre, string> = {
  CONCERT: "#c2361c",
  MUSICAL: "#245c9c",
  PLAY: "#2b7556",
  CLASSIC: "#8a5a12",
  ETC: "#4b5060",
};
