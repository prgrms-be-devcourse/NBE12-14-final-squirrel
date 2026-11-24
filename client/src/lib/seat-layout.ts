import type { Seat } from "@/lib/types";
import type { Point, ZoneShape } from "@/lib/venue-layouts/types";

// 구역 모양 안에 좌석 점을 놓는다. 좌표는 도면(viewBox) 좌표다.
// 좌석 번호는 "열 안의 칸 위치"라서(시드 규칙) 짧은 열은 한쪽이 비고, 그 차이가 삼각형·사다리꼴 모양이 된다.
// 열은 서버가 준 순서(sortOrder)대로 놓고, 번호가 숫자가 아니면 그 열 안의 순서를 칸 위치로 쓴다.

export type PlacedSeat = { seat: Seat; x: number; y: number };
export type RowLabel = { label: string; x: number; y: number; anchor: "start" | "middle" | "end" };

export type ZoneSeatLayout = {
  seats: PlacedSeat[];
  rowLabels: RowLabel[];
  pitch: number;
  // 구역 이름표를 놓을 자리
  titleX: number;
  titleY: number;
};

const TITLE_HEIGHT = 15;
const MARGIN = 3;

export function boundsOf(points: Point[]) {
  const xs = points.map((p) => p[0]);
  const ys = points.map((p) => p[1]);
  const x0 = Math.min(...xs);
  const y0 = Math.min(...ys);
  const x1 = Math.max(...xs);
  const y1 = Math.max(...ys);
  return { x0, y0, x1, y1, width: x1 - x0, height: y1 - y0 };
}

export function layoutZoneSeats(shape: ZoneShape, seats: Seat[], venueCenterX: number): ZoneSeatLayout {
  const { x0, y0, x1, width, height } = boundsOf(shape.points);
  const result: ZoneSeatLayout = { seats: [], rowLabels: [], pitch: 0, titleX: x0 + width / 2, titleY: y0 + 9 };
  if (seats.length === 0) return result;

  // 열 순서: 각 열의 가장 앞 sortOrder 로 정한다.
  const firstOrder = new Map<string, number>();
  for (const seat of seats) {
    const current = firstOrder.get(seat.row);
    if (current === undefined || seat.sortOrder < current) firstOrder.set(seat.row, seat.sortOrder);
  }
  const rowNames = [...firstOrder.entries()].sort((a, b) => a[1] - b[1]).map(([row]) => row);
  const rowIndex = new Map(rowNames.map((row, index) => [row, index]));

  const fallback = new Map<string, number>();
  const positioned = seats.map((seat) => {
    const parsed = Number(seat.number);
    let position = parsed;
    if (!Number.isInteger(parsed) || parsed < 1) {
      position = (fallback.get(seat.row) ?? 0) + 1;
      fallback.set(seat.row, position);
    }
    return { seat, position, row: rowIndex.get(seat.row) ?? 0 };
  });

  const rowCount = rowNames.length;
  const maxPosition = Math.max(...positioned.map((s) => s.position));
  const vertical = shape.rows === "vertical";
  // 세로 열이 있는 날개는 지도 가운데에서 가까운 쪽이 A열이다.
  const leftSide = vertical && (x0 + x1) / 2 < venueCenterX;

  const innerWidth = width - 2 * MARGIN;
  const innerHeight = height - TITLE_HEIGHT - MARGIN;
  const pitch = vertical
    ? Math.min(innerWidth / rowCount, innerHeight / maxPosition)
    : Math.min(innerWidth / maxPosition, innerHeight / rowCount);
  const gridWidth = (vertical ? rowCount : maxPosition) * pitch;
  const gridHeight = (vertical ? maxPosition : rowCount) * pitch;
  const gx0 = x0 + (width - gridWidth) / 2;
  const gy0 = y0 + TITLE_HEIGHT + (innerHeight - gridHeight) / 2;
  result.pitch = pitch;

  const columnX = (row: number) => (leftSide ? gx0 + (rowCount - 1 - row) * pitch : gx0 + row * pitch) + pitch / 2;

  for (const { seat, position, row } of positioned) {
    const x = vertical ? columnX(row) : gx0 + (position - 1) * pitch + pitch / 2;
    const y = vertical ? gy0 + (position - 1) * pitch + pitch / 2 : gy0 + row * pitch + pitch / 2;
    result.seats.push({ seat, x, y });
  }

  rowNames.forEach((label, row) => {
    result.rowLabels.push(
      vertical
        ? { label, x: columnX(row), y: y0 + TITLE_HEIGHT - 1.5, anchor: "middle" }
        : { label, x: gx0 - 1.5, y: gy0 + row * pitch + pitch / 2 + pitch * 0.25, anchor: "end" },
    );
  });
  return result;
}
