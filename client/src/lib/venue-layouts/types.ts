// 공연장 도면. 서버는 구역의 "위치"를 모르고 공연장의 layoutKey 만 준다. 그림은 프론트가 이 설정으로 그린다.
// 구역은 이름으로 찾는다. (DB 의 구역 이름과 같아야 한다 — 시드의 구역 이름을 바꾸면 이 도면과 연결이 끊긴다)

export type Point = [number, number];

export type ZoneShape = {
  // 지도에 쓰는 짧은 이름 (예: "108", "A")
  label: string;
  points: Point[];
  // 열이 놓이는 방향. vertical 은 열이 세로 줄로 서고(번호는 위에서 아래), horizontal 은 가로 줄로 선다(번호는 왼쪽에서 오른쪽).
  rows: "vertical" | "horizontal";
};

// 무대·통로 같은 구역이 아닌 도형
export type Decoration = {
  x: number;
  y: number;
  width: number;
  height: number;
  rx?: number;
  label?: string;
};

export type VenueLayout = {
  key: string;
  viewBox: [x: number, y: number, width: number, height: number];
  decorations: Decoration[];
  zones: Record<string, ZoneShape>;
  // 구역 하나를 눌렀을 때 함께 보여 줄 구역 묶음. 구역 이름의 목록이다.
  groups: string[][];
};
