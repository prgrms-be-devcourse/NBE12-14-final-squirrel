import Link from "next/link";
import { GENRE_LABEL, SALE_LABEL, periodText } from "@/lib/format";
import type { PerformanceSummary, SaleStatus } from "@/lib/types";
import { Poster } from "@/components/ui";

const SALE_BADGE: Record<SaleStatus, string> = {
  ON_SALE: "badge badge-accent",
  UPCOMING: "badge badge-warn",
  PAUSED: "badge badge-warn",
  POSTPONED: "badge badge-warn",
  SOLD_OUT: "badge",
  ENDED: "badge",
};

export function PerformanceCard({ p }: { p: PerformanceSummary }) {
  const postponed = p.saleStatus === "POSTPONED";
  const dim = postponed || p.saleStatus === "ENDED" || p.saleStatus === "SOLD_OUT" || p.saleStatus === "PAUSED";
  return (
    <Link href={`/performances/${p.performanceId}`} className="group block">
      <div className={`transition-opacity duration-150 ${dim ? "opacity-60 group-hover:opacity-100" : ""}`}>
        <Poster title={p.title} genre={p.genre} url={p.posterUrl} />
      </div>
      <div className="mt-4 flex items-center gap-2">
        <span className={SALE_BADGE[p.saleStatus]}>{SALE_LABEL[p.saleStatus]}</span>
        <span className="text-xs text-muted">{GENRE_LABEL[p.genre]}</span>
      </div>
      <h3 className="mt-2 leading-snug font-medium decoration-accent underline-offset-4 group-hover:underline">
        {p.title}
      </h3>
      <p className="mt-1 text-sm text-secondary">{p.venueName ?? "장소 미정"}</p>
      <p className="text-sm text-muted">{postponed ? "일정 미정" : periodText(p.firstStartAt, p.lastStartAt)}</p>
    </Link>
  );
}
