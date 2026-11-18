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

// 서버는 등급을 코드(VIP, R, S ...)로만 준다. 화면 이름과 색은 프론트가 정한다. (api-spec SLR-08)
const GRADE_LABEL: Record<string, string> = {
  VIP: "VIP석",
  R: "R석",
  S: "S석",
  A: "A석",
  B: "B석",
  C: "C석",
  OP: "OP석",
  VIEW_LIMITED: "시야제한석",
  ALL: "전석",
  STANDING: "스탠딩석",
  VIP_STANDING: "VIP 스탠딩석",
  RESERVED: "지정석",
};

const GRADE_COLOR: Record<string, string> = {
  VIP: "#8a5a12",
  VIP_STANDING: "#8a5a12",
  R: "#c2361c",
  S: "#245c9c",
  A: "#2b7556",
  B: "#6a4a9c",
  C: "#4b5060",
};

export function gradeLabel(code: string | null | undefined): string {
  if (!code) return "";
  return GRADE_LABEL[code] ?? code;
}

// 서버가 색을 주면 그것을, 아니면 등급 코드로 정한다.
export function gradeColor(code: string | null | undefined, given?: string | null): string | null {
  if (given) return given;
  if (!code) return null;
  return GRADE_COLOR[code] ?? "#4b5060";
}
