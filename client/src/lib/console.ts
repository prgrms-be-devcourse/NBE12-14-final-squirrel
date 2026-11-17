import type { BusinessStatus, PerformanceStatus, ScheduleStatus } from "@/lib/types";

// 판매자·관리자 화면에서 같이 쓰는 라벨과 배지

export const PERFORMANCE_LABEL: Record<PerformanceStatus, string> = {
  DRAFT: "작성 중",
  PUBLISHED: "공개",
  CANCELED: "취소",
};

export const PERFORMANCE_BADGE: Record<PerformanceStatus, string> = {
  DRAFT: "badge badge-warn",
  PUBLISHED: "badge badge-ok",
  CANCELED: "badge",
};

export const SCHEDULE_LABEL: Record<ScheduleStatus, string> = {
  ON_SALE: "판매 중",
  PAUSED: "일시 중지",
  POSTPONED: "연기",
  CANCELED: "취소",
};

export const BUSINESS_LABEL: Record<BusinessStatus | "ALL", string> = {
  PENDING: "승인 대기",
  APPROVED: "승인",
  REJECTED: "반려",
  SUSPENDED: "정지",
  ALL: "전체",
};

export const BUSINESS_BADGE: Record<BusinessStatus, string> = {
  PENDING: "badge badge-warn",
  APPROVED: "badge badge-ok",
  REJECTED: "badge badge-accent",
  SUSPENDED: "badge",
};

export const RECEIVE_LABEL = { ONSITE: "현장 수령", DELIVERY: "배송", BOTH: "현장 수령 또는 배송" } as const;

// <input type="datetime-local"> 값(yyyy-MM-ddTHH:mm)과 서버 값(yyyy-MM-ddTHH:mm:ss)을 오간다
export const toInputDateTime = (value: string | null): string => (value ? value.slice(0, 16) : "");
export const toServerDateTime = (value: string): string | null => (value ? `${value}:00` : null);
