import type {
  BusinessSummary,
  CancelPolicySummary,
  CancelRule,
  Genre,
  PerformanceDetail,
  SaleStatus,
  SellerPerformanceSummary,
  Term,
} from "@/lib/types";

// Spring 이 꺼져 있거나 아직 만들어지지 않은 API 를 대신하는 목 데이터.
// 실제 API 가 생기면 BFF 가 Spring 응답을 먼저 쓰므로 여기를 지우지 않아도 된다.

const HOUR = 3_600_000;
const DAY = 24 * HOUR;

export function isoLocal(date: Date): string {
  const pad = (n: number) => String(n).padStart(2, "0");
  return (
    `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}` +
    `T${pad(date.getHours())}:${pad(date.getMinutes())}:${pad(date.getSeconds())}`
  );
}

// 오늘 기준 며칠 뒤 몇 시 정각.
function at(daysFromNow: number, hour: number): string {
  const d = new Date(Date.now() + daysFromNow * DAY);
  d.setHours(hour, 0, 0, 0);
  return isoLocal(d);
}

export const DEFAULT_CANCEL_POLICY: CancelRule[] = [
  { daysFrom: 7, daysTo: null, feeRate: 0, cancelable: true },
  { daysFrom: 3, daysTo: 6, feeRate: 10, cancelable: true },
  { daysFrom: 1, daysTo: 2, feeRate: 20, cancelable: true },
  { daysFrom: 0, daysTo: 0, feeRate: 0, cancelable: false },
];

type MockSeed = {
  id: number;
  title: string;
  genre: Genre;
  ageLimit: number;
  runningTime: number;
  castInfo: string;
  venue: { venueId: number; name: string; address: string; region: string };
  firstDay: number;
  scheduleCount: number;
  saleOpenDay: number;
  grades: { name: string; price: number; color: string }[];
  maxPerPerson: number;
  bookingFee: number;
  receiveType: "ONSITE" | "DELIVERY" | "BOTH";
  notice: string;
};

const VENUES = {
  arena: { venueId: 1, name: "가상 아레나 (샘플)", address: "서울 송파구 올림픽로 424 (가상)", region: "서울" },
  hall: { venueId: 2, name: "가상 아트홀 (샘플)", address: "서울 서초구 남부순환로 2406 (가상)", region: "서울" },
  theater: { venueId: 3, name: "가상 대극장 (샘플)", address: "부산 해운대구 센텀남대로 35 (가상)", region: "부산" },
  small: { venueId: 4, name: "가상 소극장 (샘플)", address: "경기 성남시 분당구 판교역로 235 (가상)", region: "경기" },
};

const SEEDS: MockSeed[] = [
  {
    id: 101,
    title: "별빛 아래 라이브 2026",
    genre: "CONCERT",
    ageLimit: 12,
    runningTime: 150,
    castInfo: "밴드 노을, 게스트 다수",
    venue: VENUES.arena,
    firstDay: 21,
    scheduleCount: 3,
    saleOpenDay: -3,
    grades: [
      { name: "VIP석", price: 165000, color: "#c8452d" },
      { name: "R석", price: 132000, color: "#d98a2b" },
      { name: "S석", price: 99000, color: "#4a7c59" },
    ],
    maxPerPerson: 4,
    bookingFee: 2000,
    receiveType: "BOTH",
    notice: "공연 시작 30분 전부터 입장할 수 있습니다.",
  },
  {
    id: 102,
    title: "뮤지컬 〈달의 정원〉",
    genre: "MUSICAL",
    ageLimit: 8,
    runningTime: 165,
    castInfo: "이서윤, 박도현, 최하린",
    venue: VENUES.theater,
    firstDay: 14,
    scheduleCount: 4,
    saleOpenDay: -10,
    grades: [
      { name: "VIP석", price: 150000, color: "#c8452d" },
      { name: "R석", price: 120000, color: "#d98a2b" },
      { name: "S석", price: 90000, color: "#4a7c59" },
    ],
    maxPerPerson: 6,
    bookingFee: 1000,
    receiveType: "ONSITE",
    notice: "지정된 시간 이후에는 입장이 제한됩니다.",
  },
  {
    id: 103,
    title: "연극 〈마지막 기차〉",
    genre: "PLAY",
    ageLimit: 15,
    runningTime: 100,
    castInfo: "정민재, 한소이",
    venue: VENUES.small,
    firstDay: 9,
    scheduleCount: 2,
    saleOpenDay: -20,
    grades: [
      { name: "전석", price: 55000, color: "#4a7c59" },
      { name: "스탠딩", price: 35000, color: "#5f6f8f" },
    ],
    maxPerPerson: 4,
    bookingFee: 1000,
    receiveType: "ONSITE",
    notice: "공연 중 재입장은 불가합니다.",
  },
  {
    id: 104,
    title: "겨울 오케스트라 갈라",
    genre: "CLASSIC",
    ageLimit: 7,
    runningTime: 120,
    castInfo: "가상 필하모닉, 지휘 오세현",
    venue: VENUES.hall,
    firstDay: 30,
    scheduleCount: 2,
    saleOpenDay: 4,
    grades: [
      { name: "R석", price: 110000, color: "#d98a2b" },
      { name: "S석", price: 80000, color: "#4a7c59" },
      { name: "A석", price: 50000, color: "#5f6f8f" },
    ],
    maxPerPerson: 4,
    bookingFee: 1000,
    receiveType: "BOTH",
    notice: "미취학 아동은 입장이 제한될 수 있습니다.",
  },
  {
    id: 105,
    title: "인디 페스티벌 〈골목〉",
    genre: "CONCERT",
    ageLimit: 0,
    runningTime: 240,
    castInfo: "다수 인디 아티스트",
    venue: VENUES.small,
    firstDay: 45,
    scheduleCount: 2,
    saleOpenDay: -1,
    grades: [
      { name: "일반", price: 77000, color: "#4a7c59" },
      { name: "스탠딩", price: 66000, color: "#5f6f8f" },
    ],
    maxPerPerson: 2,
    bookingFee: 1000,
    receiveType: "DELIVERY",
    notice: "야외 공연이라 우천 시에도 진행됩니다.",
  },
  {
    id: 106,
    title: "지난 가을의 재즈 나이트",
    genre: "CONCERT",
    ageLimit: 19,
    runningTime: 110,
    castInfo: "재즈 트리오 블루문",
    venue: VENUES.hall,
    firstDay: -4,
    scheduleCount: 1,
    saleOpenDay: -40,
    grades: [{ name: "전석", price: 88000, color: "#4a7c59" }],
    maxPerPerson: 4,
    bookingFee: 1000,
    receiveType: "ONSITE",
    notice: "종료된 공연입니다.",
  },
];

function saleStatusOf(seed: MockSeed): SaleStatus {
  if (seed.firstDay < 0) return "ENDED";
  if (seed.saleOpenDay > 0) return "UPCOMING";
  if (seed.id === 103) return "SOLD_OUT";
  return "ON_SALE";
}

function buildDetail(seed: MockSeed): PerformanceDetail {
  const status = saleStatusOf(seed);
  const schedules = Array.from({ length: seed.scheduleCount }, (_, i) => ({
    scheduleId: seed.id * 10 + i + 1,
    startAt: at(seed.firstDay + i, i % 2 === 0 ? 19 : 15),
    status: "ON_SALE",
    saleStatus: status,
  }));
  return {
    performanceId: seed.id,
    title: seed.title,
    genre: seed.genre,
    ageLimit: seed.ageLimit,
    runningTime: seed.runningTime,
    castInfo: seed.castInfo,
    posterUrl: null,
    images: [],
    notice: seed.notice,
    guide: "촬영·녹음은 금지됩니다.",
    venue: seed.venue,
    sale: {
      saleOpenAt: at(seed.saleOpenDay, 20),
      saleCloseAt: at(seed.firstDay + seed.scheduleCount - 1, 18),
      maxPerPerson: seed.maxPerPerson,
      receiveType: seed.receiveType,
      bookingFee: seed.bookingFee,
    },
    schedules,
    grades: seed.grades.map((grade, i) => ({ gradeId: seed.id * 10 + i, ...grade })),
    cancelPolicy: DEFAULT_CANCEL_POLICY,
    commonGuide: {
      bookingGuide: "좌석 선택 후 7분 안에 결제해야 합니다.",
      refundGuide: "취소 수수료는 공연일까지 남은 기간에 따라 달라집니다.",
    },
    seller: {
      companyName: "(주)가상기획",
      representativeName: "김대표",
      businessNumber: "1234567890",
      contactPhone: "0212345678",
      contactEmail: "contact@example.com",
    },
  };
}

export const MOCK_PERFORMANCES: PerformanceDetail[] = SEEDS.map(buildDetail);

export function findMockPerformance(id: number): PerformanceDetail | undefined {
  return MOCK_PERFORMANCES.find((p) => p.performanceId === id);
}

export function saleStatusOfMock(id: number): SaleStatus {
  const seed = SEEDS.find((s) => s.id === id);
  return seed ? saleStatusOf(seed) : "ON_SALE";
}

export const MOCK_TERMS: Term[] = [
  {
    termId: 1,
    type: "SERVICE",
    version: "1.0",
    content: "제1조 (목적) 이 약관은 tickting 의 예매 서비스 이용 조건을 정합니다. (목 데이터)",
    required: true,
  },
  {
    termId: 2,
    type: "PRIVACY",
    version: "1.0",
    content: "개인정보 수집·이용 동의 내용입니다. 이메일, 이름, 휴대폰 번호를 수집합니다. (목 데이터)",
    required: true,
  },
];

export const MOCK_SELLER_PERFORMANCES: SellerPerformanceSummary[] = [
  ...SEEDS.slice(0, 4).map((seed, i) => ({
    performanceId: seed.id,
    title: seed.title,
    status: "PUBLISHED" as const,
    posterUrl: null,
    venueName: seed.venue.name,
    scheduleCount: seed.scheduleCount,
    createdAt: at(-30 + i, 10),
  })),
  {
    performanceId: 107,
    title: "작성 중인 공연",
    status: "DRAFT",
    posterUrl: null,
    venueName: null,
    scheduleCount: 0,
    createdAt: at(-1, 9),
  },
];

export const MOCK_CANCEL_POLICIES: CancelPolicySummary[] = [
  { cancelPolicyId: 1, name: "기본 규정", isDefault: true, editable: false },
  { cancelPolicyId: 8, name: "공연장 자체 규정", isDefault: false, editable: true },
];

export const MOCK_VENUES = [
  { venueId: 1, name: VENUES.arena.name, region: VENUES.arena.region, address: VENUES.arena.address },
  { venueId: 2, name: VENUES.hall.name, region: VENUES.hall.region, address: VENUES.hall.address },
];

export function initialBusinesses(): (BusinessSummary & {
  contactPhone: string;
  contactEmail: string;
  rejectReason: string | null;
})[] {
  const base = [
    ["(주)가상기획", "김대표", "1234567890", "PENDING"],
    ["스튜디오 노을", "이노을", "2345678901", "PENDING"],
    ["하늘공연장", "박하늘", "3456789012", "APPROVED"],
    ["달빛엔터", "최달빛", "4567890123", "REJECTED"],
  ] as const;
  return base.map(([companyName, representativeName, businessNumber, status], i) => ({
    businessId: i + 1,
    companyName,
    representativeName,
    businessNumber,
    status,
    createdAt: at(-(i + 2), 15),
    reviewedAt: status === "PENDING" ? null : at(-1, 11),
    contactPhone: "0212345678",
    contactEmail: "contact@example.com",
    rejectReason: status === "REJECTED" ? "사업자등록번호 확인이 필요합니다." : null,
  }));
}
