"use client";

import { useApi } from "@/lib/api";
import type { Page, VenueItem } from "@/lib/types";
import { ConsoleTitle, Table } from "@/components/console";
import { Empty, ErrorBox, Loading } from "@/components/ui";

export default function SellerVenuesPage() {
  const { data, error, loading, reload } = useApi<Page<VenueItem>>("/api/v1/seller/venues", { size: 20 });

  return (
    <>
      <ConsoleTitle title="공연장" sub="공연을 올릴 수 있는 사용 중인 공연장입니다. 새 공연장은 관리자가 등록합니다" />
      {loading && <Loading />}
      {error && <ErrorBox error={error} onRetry={reload} />}
      {data && data.content.length === 0 && <Empty>사용할 수 있는 공연장이 없습니다</Empty>}
      {data && data.content.length > 0 && (
        <Table head={["공연장", "지역", "주소", "좌석 수", "배치"]}>
          {data.content.map((v) => (
            <tr key={v.venueId}>
              <td className="px-5 py-4 font-medium">{v.name}</td>
              <td className="px-5 py-4">{v.region}</td>
              <td className="px-5 py-4 text-secondary">{v.address}</td>
              <td className="px-5 py-4">{v.totalSeats.toLocaleString("ko-KR")}석</td>
              <td className="px-5 py-4 text-muted">{v.layoutKey}</td>
            </tr>
          ))}
        </Table>
      )}
    </>
  );
}
