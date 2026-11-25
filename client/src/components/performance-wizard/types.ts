import type { GradesResponse, SellerPerformanceDetail } from "@/lib/types";
import type { VenueLayout } from "@/lib/venue-layouts/types";

// 마법사 단계. 공연 만들기 흐름은 이 순서다.
export type StepKey = "basic" | "grades" | "zones" | "schedules" | "review";

export const STEPS: { key: StepKey; label: string }[] = [
  { key: "basic", label: "기본 정보" },
  { key: "grades", label: "등급·가격" },
  { key: "zones", label: "구역" },
  { key: "schedules", label: "회차" },
  { key: "review", label: "검토·공개" },
];

// 서버가 한 번에 공연 전체를 만드는 API 가 없어서 단계마다 저장한다. 각 단계는 저장하고 나면 onSaved 로 새로 읽게 하고,
// 다음으로 넘어갈 때만 onNext 를 부른다.
export type StepProps = {
  detail: SellerPerformanceDetail;
  grades: GradesResponse;
  layout: VenueLayout | null;
  onSaved: () => void;
  onNext: () => void;
  onBack: () => void;
  goTo: (step: StepKey) => void;
};
