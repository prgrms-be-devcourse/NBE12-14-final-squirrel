import type { Seat } from "@/lib/types";
import type { Point, ZoneShape } from "@/lib/venue-layouts/types";

// 구역 모양 안에 좌석 점을 놓는다. 좌표는 도면(viewBox) 좌표다.
// 좌석 번호는 "열 안의 칸 위치"라서(시드 규칙) 짧은 열은 한쪽이 비고, 그 차이가 삼각형·사다리꼴 모양이 된다.
// 열은 서버가 준 순서(sortOrder)대로 놓고, 번호가 숫자가 아니면 그 열 안의 순서를 칸 위치로 쓴다.

export type PlacedSeat = { seat: Seat; x: number; y: number };
export type RowLabel = { label: string; x: number; y: number; anchor: "start" | "middle" | "end" };

export type Rect = { x: number; y: number; w: number; h: number };

export type ZoneSeatLayout = {
  seats: PlacedSeat[];
  rowLabels: RowLabel[];
  pitch: number;
  // 구역 이름표를 놓을 자리
  titleX: number;
  titleY: number;
  // 좌석 뒤에 까는 바탕. 열마다 실제 좌석이 있는 칸까지만 덮고, 이름표·열 번호 자리를 포함한다.
  // 구역 모양(점 몇 개로 근사한 값)과 좌석 배치가 어긋나도 바탕이 좌석을 따라가게 하려는 것이다.
  backdrop: Rect[];
};

const TITLE_HEIGHT = 15;
const MARGIN = 3;
// 바탕이 좌석 바깥으로 나오는 여유(좌석 간격 대비)와 구역 이름표 띠의 높이
const BACKDROP_PAD = 0.4;
const TITLE_BAND = 14;

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
  const result: ZoneSeatLayout = {
    seats: [],
    rowLabels: [],
    pitch: 0,
    titleX: x0 + width / 2,
    titleY: y0 + 9,
    backdrop: [],
  };
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

  const firstInRow = new Map<number, number>();
  const lastInRow = new Map<number, number>();
  for (const { position, row } of positioned) {
    firstInRow.set(row, Math.min(firstInRow.get(row) ?? Infinity, position));
    lastInRow.set(row, Math.max(lastInRow.get(row) ?? 0, position));
  }

  // 가로 열의 번호는 열이 반듯하게 맞는 쪽에 둔다. 왼쪽 끝이 들쭉날쭉하면(왼쪽이 비는 구역) 오른쪽에 둬서
  // 번호가 좌석 바로 옆에 붙게 한다.
  let raggedLeft = 0;
  let raggedRight = 0;
  const minFirst = Math.min(...firstInRow.values());
  for (const [row, last] of lastInRow) {
    raggedLeft += (firstInRow.get(row) ?? 1) - minFirst;
    raggedRight += maxPosition - last;
  }
  const labelsRight = !vertical && raggedLeft > raggedRight;
  const gx1 = gx0 + gridWidth;

  rowNames.forEach((label, row) => {
    const y = gy0 + row * pitch + pitch / 2 + pitch * 0.25;
    result.rowLabels.push(
      vertical
        ? { label, x: columnX(row), y: y0 + TITLE_HEIGHT - 1.5, anchor: "middle" }
        : labelsRight
          ? { label, x: gx1 + 1.5, y, anchor: "start" }
          : { label, x: gx0 - 1.5, y, anchor: "end" },
    );
  });

  // 열마다 실제 좌석이 있는 칸까지만 덮는다. 번호가 있는 쪽 끝은 번호 자리까지, 반대쪽은 가장 먼 좌석까지다.
  // 그래서 짧은 열은 바탕도 짧아지고 구역의 모양이 좌석 배치 그대로 나온다.
  const bands: Rect[] = [];
  for (const [row, last] of lastInRow) {
    if (vertical) {
      const top = Math.min(y0 + TITLE_HEIGHT - 1.5 - pitch, gy0);
      bands.push({ x: columnX(row) - pitch / 2, y: top, w: pitch, h: gy0 + last * pitch - top });
      continue;
    }
    const label = pitch * 1.6;
    const y = gy0 + row * pitch;
    if (labelsRight) {
      const start = gx0 + ((firstInRow.get(row) ?? 1) - 1) * pitch;
      bands.push({ x: start, y, w: gx1 + label - start, h: pitch });
    } else {
      bands.push({ x: gx0 - label, y, w: label + last * pitch, h: pitch });
    }
  }
  const minX = Math.min(...bands.map((b) => b.x));
  const maxX = Math.max(...bands.map((b) => b.x + b.w));
  const minY = Math.min(...bands.map((b) => b.y));
  bands.push({ x: minX, y: minY - TITLE_BAND, w: maxX - minX, h: TITLE_BAND });
  result.titleX = (minX + maxX) / 2;
  result.titleY = minY - TITLE_BAND + 10.5;

  const pad = pitch * BACKDROP_PAD;
  result.backdrop = bands.map((b) => ({ x: b.x - pad, y: b.y - pad, w: b.w + 2 * pad, h: b.h + 2 * pad }));
  return result;
}
