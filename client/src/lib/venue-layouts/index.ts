import { concertArena44 } from "@/lib/venue-layouts/concert-arena-44";
import type { VenueLayout } from "@/lib/venue-layouts/types";

// layoutKey → 도면. 도면이 없는 공연장(layoutKey 가 비었거나 등록되지 않음)은 null 이고,
// 화면은 층별 구역 격자(기본 도면)로 그린다.
const LAYOUTS: Record<string, VenueLayout> = {
  [concertArena44.key]: concertArena44,
};

export function getVenueLayout(layoutKey: string | null | undefined): VenueLayout | null {
  return layoutKey ? (LAYOUTS[layoutKey] ?? null) : null;
}
