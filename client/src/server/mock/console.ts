import type {
  AccountType,
  BusinessStatus,
  CancelRule,
  Grade,
  PerformanceForm,
  PolicyDetail,
  RefundItem,
  SalesSchedule,
  ScheduleBooking,
  SellerPerformanceDetail,
  SellerPerformanceSummary,
  TicketGroup,
  VenueForm,
  VenueItem,
  ZoneAssignment,
} from "@/lib/types";
import { GRADE_CODES } from "@/lib/format";
import arenaVenue from "@/server/mock/concert-arena-44.json";
import type { Handler, MockContext } from "@/server/mock/handlers";
import {
  DEFAULT_CANCEL_POLICY,
  MOCK_PERFORMANCES,
  MOCK_VENUES,
  initialBusinesses,
  isoLocal,
} from "@/server/mock/data";

// 판매자·관리자 화면용 목 API. 실제 Spring 응답이 있으면 BFF 가 그쪽을 먼저 쓴다.
// handlers.ts 와 서로 import 하면 순환이 생기므로 필요한 도우미는 인자로 받는다.

export type Helpers = {
  ok: (data: unknown, status?: number, extra?: HeadersInit) => Response;
  fail: (status: number, code: string, message: string) => Response;
  pageOf: <T>(
    items: T[],
    search: URLSearchParams,
    defaultSize?: number,
    maxSize?: number,
  ) => { content: T[]; page: number; size: number; totalElements: number; totalPages: number };
  requireSession: (ctx: MockContext, type: AccountType) => { type: AccountType } | Response;
};

const DAY = 24 * 3_600_000;
const HOUR = 3_600_000;

// 서버 규칙의 "임시" 값(api-spec SLR-04·12). 서버가 바뀌면 같이 고친다.
const PUBLISH_LEAD = 3 * HOUR; // 공개·작성 중 회차는 지금부터 이 시간보다 뒤여야 한다
const TURNAROUND = HOUR; // 회차 뒤 정리 시간. 회차가 차지하는 시간 = 러닝타임 + 이 값
const PRE_GENERATE = DAY; // 판매 오픈 이만큼 전부터는 회차를 추가하면서 판매 좌석을 바로 만든다

// 환불: 자동 재시도 횟수(서버 설정 app.payment.refund-retry.max-count)와 토스 멱등키 유효 기간
const REFUND_MAX_RETRY = 3;
const REFUND_KEY_DAYS = 15;

const toMs = (iso: string) => new Date(iso).getTime();
const clockText = (iso: string) => iso.slice(0, 16).replace("T", " ");
const occupyEnd = (startMs: number, runningTime: number) => startMs + runningTime * 60_000 + TURNAROUND;

type PerfRec = SellerPerformanceDetail & {
  grades: Grade[];
  zones: ZoneAssignment[];
  nextGradeId: number;
};

type PolicyRec = { cancelPolicyId: number; name: string; isDefault: boolean; rules: CancelRule[] };

type RefundRec = RefundItem;

type ConsoleState = {
  performances: PerfRec[];
  venues: VenueItem[];
  policies: PolicyRec[];
  businesses: ReturnType<typeof initialBusinesses>;
  refunds: RefundRec[];
  nextPerformanceId: number;
  nextScheduleId: number;
  nextPolicyId: number;
  nextVenueId: number;
};

const ZONES: Pick<ZoneAssignment, "zoneId" | "name" | "floor" | "sortOrder" | "seatCount">[] = [
  { zoneId: 1, name: "A구역", floor: "1층", sortOrder: 1, seatCount: 32 },
  { zoneId: 2, name: "B구역", floor: "1층", sortOrder: 2, seatCount: 32 },
  { zoneId: 3, name: "C구역", floor: "1층", sortOrder: 3, seatCount: 32 },
  { zoneId: 4, name: "D구역", floor: "2층", sortOrder: 4, seatCount: 32 },
  { zoneId: 5, name: "E구역", floor: "2층", sortOrder: 5, seatCount: 32 },
];
const GRADE_INDEX_BY_ZONE = [0, 1, 1, 2, 2];

// 도면이 있는 공연장(아레나)은 구역 44개를 도면 이름표와 같은 이름으로 준다. 도면 구역 이름과 맞아야 지도에 칠해진다.
const ARENA_VENUE_ID = 1;
const ARENA_ZONES: typeof ZONES = (arenaVenue.zones as { name: string; floor: string; rows: { from: number; to: number }[] }[]).map(
  (zone, index) => ({
    zoneId: index + 1,
    name: zone.name,
    floor: zone.floor,
    sortOrder: index + 1,
    seatCount: zone.rows.reduce((sum, row) => sum + row.to - row.from + 1, 0),
  }),
);
const zonesOf = (venueId: number) => (venueId === ARENA_VENUE_ID ? ARENA_ZONES : ZONES);
const unsetZones = (venueId: number): ZoneAssignment[] =>
  zonesOf(venueId).map((z) => ({ ...z, assignmentStatus: "UNSET" as const, gradeId: null }));

// 시연 공연과 같은 규칙: 플로어=VIP, 제한석 5곳=A, 300번대=S, 나머지=R
function arenaGradeName(name: string): string {
  if (name.startsWith("플로어")) return "VIP";
  if (["101구역", "115구역", "201구역", "215구역", "315구역"].includes(name)) return "A";
  return name.startsWith("3") ? "S" : "R";
}

function seedPerformances(): PerfRec[] {
  const published = MOCK_PERFORMANCES.slice(0, 4).map((p, i): PerfRec => {
    const grades = [...p.grades].sort((a, b) => b.price - a.price);
    return {
      performanceId: p.performanceId,
      status: "PUBLISHED",
      venue: { venueId: p.venue?.venueId ?? 1, name: p.venue?.name ?? "공연장" },
      title: p.title,
      genre: p.genre,
      ageLimit: p.ageLimit,
      runningTime: p.runningTime,
      castInfo: p.castInfo,
      posterUrl: p.posterUrl,
      notice: p.notice,
      guide: p.guide,
      images: [],
      cancelPolicyId: 1,
      saleOpenAt: p.sale.saleOpenAt,
      saleCloseAt: p.sale.saleCloseAt,
      maxPerPerson: p.sale.maxPerPerson,
      receiveType: p.sale.receiveType,
      bookingFee: p.sale.bookingFee,
      schedules: p.schedules.map((s) => ({
        scheduleId: s.scheduleId,
        startAt: s.startAt,
        status: "ON_SALE" as const,
        seatsGeneratedAt: isoLocal(new Date(Date.now() - 3 * DAY)),
      })),
      createdAt: isoLocal(new Date(Date.now() - (30 - i) * DAY)),
      updatedAt: isoLocal(new Date(Date.now() - (10 - i) * DAY)),
      grades,
      nextGradeId: p.performanceId * 10 + 9,
      zones: zonesOf(p.venue?.venueId ?? 1).map((z, idx) => ({
        ...z,
        assignmentStatus: "ASSIGNED" as const,
        gradeId:
          (p.venue?.venueId ?? 1) === ARENA_VENUE_ID
            ? (grades.find((g) => g.name === arenaGradeName(z.name)) ?? grades[grades.length - 1])?.gradeId ?? null
            : grades[Math.min(GRADE_INDEX_BY_ZONE[idx], grades.length - 1)]?.gradeId ?? null,
      })),
    };
  });

  const draft: PerfRec = {
    performanceId: 107,
    status: "DRAFT",
    venue: { venueId: 1, name: MOCK_VENUES[0].name },
    title: "작성 중인 공연",
    genre: "CONCERT",
    ageLimit: 12,
    runningTime: 120,
    castInfo: null,
    posterUrl: null,
    notice: null,
    guide: null,
    images: [],
    cancelPolicyId: 8,
    saleOpenAt: null,
    saleCloseAt: null,
    maxPerPerson: null,
    receiveType: null,
    bookingFee: null,
    schedules: [],
    createdAt: isoLocal(new Date(Date.now() - DAY)),
    updatedAt: isoLocal(new Date(Date.now() - DAY)),
    grades: [],
    nextGradeId: 1071,
    zones: unsetZones(1),
  };
  return [draft, ...published.reverse()];
}

function seedRefunds(): RefundRec[] {
  const at = (days: number) => isoLocal(new Date(Date.now() - days * DAY));
  return [
    {
      refundId: 288, bookingId: 874, bookingNo: "20261110QWRT91K", paymentId: 702, amount: 156000,
      reasonType: "PERFORMANCE_CANCEL", reasonDetail: "출연자 사정으로 회차 취소", status: "FAILED",
      retryCount: 3, failReason: "PG 응답 시간 초과", requestedAt: at(5), lastAttemptedAt: at(4.9),
    },
    {
      // 다시 처리해도 토스가 계속 거절하는 건. 200 인데 status 가 FAILED 로 남는 경로를 시험한다.
      refundId: 291, bookingId: 880, bookingNo: "20261111M3ZA7PD", paymentId: 709, amount: 99000,
      reasonType: "USER_CANCEL", reasonDetail: null, status: "FAILED",
      retryCount: 3, failReason: "카드사 점검으로 승인 취소 거절", requestedAt: at(4), lastAttemptedAt: at(3.9),
    },
    {
      refundId: 295, bookingId: 891, bookingNo: "20261112K8CC02X", paymentId: 716, amount: 330000,
      reasonType: "PERFORMANCE_CANCEL", reasonDetail: "공연 전체 취소", status: "FAILED",
      retryCount: 1, failReason: "PG 일시 오류", requestedAt: at(3), lastAttemptedAt: at(2.9),
    },
    {
      // 처음 요청한 지 15일이 지난 건. 토스 멱등키가 만료돼 서버가 다시 처리를 거절한다. (PAYMENT010)
      refundId: 270, bookingId: 842, bookingNo: "20261101T7RB44Q", paymentId: 671, amount: 121000,
      reasonType: "POSTPONE_CANCEL", reasonDetail: "공연 연기로 인한 취소", status: "FAILED",
      retryCount: 3, failReason: "카드 승인 취소 기간 초과", requestedAt: at(20), lastAttemptedAt: at(19),
    },
    {
      // 스케줄러나 다른 요청이 토스에 보내는 중인 건. (PAYMENT009)
      refundId: 299, bookingId: 899, bookingNo: "20261124P9XK30D", paymentId: 724, amount: 66000,
      reasonType: "USER_CANCEL", reasonDetail: null, status: "PROCESSING",
      retryCount: 1, failReason: null, requestedAt: at(1), lastAttemptedAt: at(0.01),
    },
    {
      refundId: 280, bookingId: 860, bookingNo: "20261105H2LL81B", paymentId: 690, amount: 77000,
      reasonType: "USER_CANCEL", reasonDetail: null, status: "DONE",
      retryCount: 0, failReason: null, requestedAt: at(12), lastAttemptedAt: at(12),
    },
  ];
}

const g = globalThis as unknown as { __ticktingConsole?: ConsoleState };

function state(): ConsoleState {
  if (!g.__ticktingConsole) {
    g.__ticktingConsole = {
      performances: seedPerformances(),
      venues: MOCK_VENUES.map((v) =>
        v.venueId === ARENA_VENUE_ID
          ? { ...v, totalSeats: ARENA_ZONES.reduce((sum, z) => sum + z.seatCount, 0), layoutKey: "concert-arena-44" }
          : { ...v, totalSeats: 160, layoutKey: "mock-hall" },
      ),
      policies: [
        { cancelPolicyId: 1, name: "기본 규정", isDefault: true, rules: DEFAULT_CANCEL_POLICY },
        {
          cancelPolicyId: 8,
          name: "공연장 자체 규정",
          isDefault: false,
          rules: [
            { daysFrom: 10, daysTo: null, feeRate: 0, cancelable: true },
            { daysFrom: 3, daysTo: 9, feeRate: 15, cancelable: true },
            { daysFrom: 0, daysTo: 2, feeRate: 0, cancelable: false },
          ],
        },
      ],
      businesses: initialBusinesses(),
      refunds: seedRefunds(),
      nextPerformanceId: 108,
      nextScheduleId: 5001,
      nextPolicyId: 9,
      nextVenueId: 10,
    };
  }
  return g.__ticktingConsole;
}

function validateRules(rules: unknown): { code: string; message: string } | null {
  if (!Array.isArray(rules) || rules.length < 1 || rules.length > 10) {
    return { code: "COMMON000", message: "rules: 구간은 1~10개여야 합니다" };
  }
  for (const r of rules as Partial<CancelRule>[]) {
    const bad =
      typeof r.daysFrom !== "number" || r.daysFrom < 0 || r.daysFrom > 90 ||
      (r.daysTo !== null && r.daysTo !== undefined && (typeof r.daysTo !== "number" || r.daysTo < 0 || r.daysTo > 90)) ||
      typeof r.feeRate !== "number" || r.feeRate < 0 || r.feeRate > 100 ||
      typeof r.cancelable !== "boolean";
    if (bad) return { code: "COMMON000", message: "rules: 일수는 0~90, 수수료율은 0~100이고 모든 값이 필요합니다" };
  }
  const sorted = [...(rules as CancelRule[])].sort((a, b) => a.daysFrom - b.daysFrom);
  if (sorted[0].daysFrom !== 0) {
    return { code: "PERFORMANCE101", message: "가장 가까운 구간은 공연 당일(0일 전)부터 시작해야 합니다" };
  }
  const unlimited = sorted.filter((r) => r.daysTo === null || r.daysTo === undefined);
  if (unlimited.length > 1 || (unlimited.length === 1 && sorted[sorted.length - 1].daysTo != null)) {
    return { code: "PERFORMANCE101", message: "제한 없는 구간은 하나이고 가장 먼 구간이어야 합니다" };
  }
  for (let i = 0; i < sorted.length; i += 1) {
    const r = sorted[i];
    if (r.daysTo != null && r.daysTo < r.daysFrom) {
      return { code: "PERFORMANCE101", message: "구간의 끝 날짜는 시작 날짜보다 작을 수 없습니다" };
    }
    const next = sorted[i + 1];
    if (next && (r.daysTo == null || next.daysFrom !== r.daysTo + 1)) {
      return { code: "PERFORMANCE101", message: "구간 사이에 빈 날짜나 겹치는 날짜가 없어야 합니다" };
    }
  }
  return null;
}

function normalizeRules(rules: CancelRule[]): CancelRule[] {
  return [...rules]
    .map((r) => ({ daysFrom: r.daysFrom, daysTo: r.daysTo ?? null, feeRate: r.feeRate, cancelable: r.cancelable }))
    .sort((a, b) => b.daysFrom - a.daysFrom);
}

export function consoleRoutes(h: Helpers): [string, string, Handler][] {
  const { ok, fail, pageOf, requireSession } = h;

  const seller = (ctx: MockContext) => requireSession(ctx, "SELLER");
  const admin = (ctx: MockContext) => requireSession(ctx, "ADMIN");
  const body = async <T>(ctx: MockContext) => ((await ctx.json().catch(() => null)) ?? {}) as Partial<T>;

  const findPerf = (id: string) => state().performances.find((p) => p.performanceId === Number(id));
  const findSchedule = (id: string) => {
    for (const p of state().performances) {
      const s = p.schedules.find((x) => x.scheduleId === Number(id));
      if (s) return { perf: p, schedule: s };
    }
    return null;
  };
  const notFound = () => fail(404, "PERFORMANCE000", "공연을 찾을 수 없습니다");

  const policyEditable = (p: PolicyRec) =>
    !p.isDefault &&
    !state().performances.some(
      (perf) => perf.cancelPolicyId === p.cancelPolicyId && perf.status !== "DRAFT",
    );
  const policyView = (p: PolicyRec): PolicyDetail => ({
    cancelPolicyId: p.cancelPolicyId,
    name: p.name,
    isDefault: p.isDefault,
    editable: policyEditable(p),
    rules: normalizeRules(p.rules),
  });

  // 공연이 가진 구역의 좌석 합. 구역은 공연장마다 달라서 공연 기준으로 센다.
  const totalSeats = (perf: PerfRec) => perf.zones.reduce((sum, z) => sum + z.seatCount, 0);
  const seatsByStatus = (perf: PerfRec, status: ZoneAssignment["assignmentStatus"]) =>
    perf.zones.filter((z) => z.assignmentStatus === status).reduce((sum, z) => sum + z.seatCount, 0);

  const activeSchedules = (perf: PerfRec) => perf.schedules.filter((s) => s.status !== "CANCELED");
  const salesInfoSet = (perf: PerfRec) =>
    Boolean(perf.saleOpenAt && perf.saleCloseAt) && perf.maxPerPerson != null && perf.receiveType != null && perf.bookingFee != null;

  // 전석(ALL)과 다른 등급이 함께 구역에 지정됐는지. 요청을 적용한 뒤의 최종 상태로 판단하려고 구역·등급을 받는다.
  const allMixed = (zones: ZoneAssignment[], grades: Grade[]) => {
    const names = new Set(
      zones.filter((z) => z.assignmentStatus === "ASSIGNED").map((z) => grades.find((x) => x.gradeId === z.gradeId)?.name),
    );
    return names.has("ALL") && names.size > 1;
  };

  // 같은 공연장의 다른 회차와 시간이 겹치는지. 회차가 차지하는 시간은 시작 ~ 시작 + 러닝타임 + 1시간이고
  // 끝나는 시각과 다음 시작이 같으면 겹치지 않는다. 취소된 회차·공연은 세지 않는다. (api-spec SLR-12)
  function findClash(venueId: number, startMs: number, runningTime: number, ignoreScheduleId?: number) {
    const endMs = occupyEnd(startMs, runningTime);
    for (const p of state().performances) {
      if (p.venue.venueId !== venueId || p.status === "CANCELED") continue;
      for (const x of p.schedules) {
        if (x.status === "CANCELED" || x.scheduleId === ignoreScheduleId) continue;
        const xs = toMs(x.startAt);
        const xe = occupyEnd(xs, p.runningTime);
        if (startMs < xe && xs < endMs) return { perf: p, schedule: x, endMs: xe };
      }
    }
    return null;
  }
  const clashMessage = (clash: NonNullable<ReturnType<typeof findClash>>) => {
    const end = isoLocal(new Date(clash.endMs));
    // 끝나는 날이 다르면 날짜까지 적는다.
    const endText = end.slice(0, 10) === clash.schedule.startAt.slice(0, 10) ? end.slice(11, 16) : clockText(end);
    return `같은 공연장의 다른 회차와 시간이 겹칩니다: ${clash.perf.title} ${clockText(clash.schedule.startAt)} (${endText}까지 사용)`;
  };

  const gradesView = (perf: PerfRec) => ({
    locked: perf.schedules.some((s) => s.status !== "CANCELED" && s.seatsGeneratedAt),
    grades: [...perf.grades].sort((a, b) => b.price - a.price),
    zones: perf.zones,
  });

  async function readPolicyBody(
    ctx: MockContext,
    selfId?: number,
  ): Promise<{ error: Response } | { name: string; rules: CancelRule[] }> {
    const b = await body<{ name: string; rules: CancelRule[] }>(ctx);
    const name = b.name?.trim() ?? "";
    if (!name || name.length > 50) {
      return { error: fail(400, "COMMON000", "name: 이름은 1~50자로 입력해 주세요") };
    }
    const ruleError = validateRules(b.rules);
    if (ruleError) return { error: fail(400, ruleError.code, ruleError.message) };
    const lower = name.toLowerCase();
    const duplicate = state().policies.some(
      (p) => p.cancelPolicyId !== selfId && p.name.toLowerCase() === lower,
    );
    if (duplicate) return { error: fail(409, "PERFORMANCE104", "같은 이름의 규정이 이미 있습니다") };
    return { name, rules: b.rules as CancelRule[] };
  }

  function validatePerformance(b: Partial<PerformanceForm>) {
    const problems: string[] = [];
    if (!b.title?.trim() || b.title.trim().length > 200) problems.push("title: 1~200자로 입력해 주세요");
    if (!b.genre) problems.push("genre: 장르를 선택해 주세요");
    if (typeof b.ageLimit !== "number" || b.ageLimit < 0 || b.ageLimit > 100) problems.push("ageLimit: 0~100");
    if (typeof b.runningTime !== "number" || b.runningTime < 1 || b.runningTime > 1440) {
      problems.push("runningTime: 1~1440분");
    }
    if (!b.posterUrl || !/^https?:\/\/\S+$/.test(b.posterUrl)) {
      problems.push("posterUrl: http:// 또는 https:// 로 시작하는 주소를 입력해 주세요");
    }
    if (b.maxPerPerson != null && (b.maxPerPerson < 1 || b.maxPerPerson > 5)) problems.push("maxPerPerson: 1~5");
    if (b.bookingFee != null && (b.bookingFee < 0 || b.bookingFee > 10000)) problems.push("bookingFee: 0~10,000원");
    return problems;
  }

  const routes: [string, string, Handler][] = [
    // ----- 판매자: 공연장 -----
    ["GET", "/api/v1/seller/venues", (ctx) => {
      const s = seller(ctx);
      if (s instanceof Response) return s;
      const region = ctx.search.get("region")?.trim();
      const keyword = ctx.search.get("keyword")?.trim().toLowerCase();
      const items = state().venues
        .filter((v) => (!region || v.region === region) && (!keyword || v.name.toLowerCase().includes(keyword)))
        .sort((a, b) => a.name.localeCompare(b.name, "ko"));
      return ok(pageOf(items, ctx.search));
    }],

    // ----- 판매자: 공연 -----
    ["GET", "/api/v1/seller/performances", (ctx) => {
      const s = seller(ctx);
      if (s instanceof Response) return s;
      const status = ctx.search.get("status");
      const items: SellerPerformanceSummary[] = state().performances
        .filter((p) => !status || p.status === status)
        .sort((a, b) => b.performanceId - a.performanceId)
        .map((p) => ({
          performanceId: p.performanceId,
          title: p.title,
          status: p.status,
          posterUrl: p.posterUrl,
          venueName: p.venue.name,
          scheduleCount: p.schedules.length,
          createdAt: p.createdAt,
        }));
      return ok(pageOf(items, ctx.search));
    }],
    ["POST", "/api/v1/seller/performances", async (ctx) => {
      const s = seller(ctx);
      if (s instanceof Response) return s;
      const b = await body<PerformanceForm>(ctx);
      const problems = validatePerformance(b);
      if (typeof b.venueId !== "number" || typeof b.cancelPolicyId !== "number") {
        problems.push("venueId, cancelPolicyId: 필수입니다");
      }
      if (problems.length > 0) return fail(400, "COMMON000", problems.join(", "));
      if (b.saleOpenAt && b.saleCloseAt && b.saleCloseAt <= b.saleOpenAt) {
        return fail(400, "PERFORMANCE001", "판매 종료는 판매 오픈보다 늦어야 합니다");
      }
      const venue = state().venues.find((v) => v.venueId === b.venueId);
      if (!venue) return fail(404, "VENUE000", "공연장을 찾을 수 없습니다");
      if (!state().policies.some((p) => p.cancelPolicyId === b.cancelPolicyId)) {
        return fail(404, "PERFORMANCE100", "취소 규정을 찾을 수 없습니다");
      }
      const now = isoLocal(new Date());
      const st = state();
      const perf: PerfRec = {
        performanceId: st.nextPerformanceId++,
        status: "DRAFT",
        venue: { venueId: venue.venueId, name: venue.name },
        title: b.title!.trim(),
        genre: b.genre!,
        ageLimit: b.ageLimit!,
        runningTime: b.runningTime!,
        castInfo: b.castInfo?.trim() || null,
        posterUrl: b.posterUrl!,
        notice: b.notice?.trim() || null,
        guide: b.guide?.trim() || null,
        images: b.images ?? [],
        cancelPolicyId: b.cancelPolicyId!,
        saleOpenAt: b.saleOpenAt ?? null,
        saleCloseAt: b.saleCloseAt ?? null,
        maxPerPerson: b.maxPerPerson ?? null,
        receiveType: b.receiveType ?? null,
        bookingFee: b.bookingFee ?? null,
        schedules: [],
        createdAt: now,
        updatedAt: now,
        grades: [],
        nextGradeId: st.nextPerformanceId * 10,
        zones: unsetZones(venue.venueId),
      };
      st.performances.push(perf);
      return ok({ performanceId: perf.performanceId, status: "DRAFT", venueId: venue.venueId, createdAt: now }, 201);
    }],
    ["GET", "/api/v1/seller/performances/:id", (ctx, [id]) => {
      const s = seller(ctx);
      if (s instanceof Response) return s;
      const perf = findPerf(id);
      if (!perf) return notFound();
      const { grades: _g, zones: _z, nextGradeId: _n, ...detail } = perf;
      void _g; void _z; void _n;
      return ok(detail);
    }],
    ["PUT", "/api/v1/seller/performances/:id", async (ctx, [id]) => {
      const s = seller(ctx);
      if (s instanceof Response) return s;
      const perf = findPerf(id);
      if (!perf) return notFound();
      const b = await body<PerformanceForm>(ctx);
      const problems = validatePerformance(b);
      if (problems.length > 0) return fail(400, "COMMON000", problems.join(", "));
      if (b.saleOpenAt && b.saleCloseAt && b.saleCloseAt <= b.saleOpenAt) {
        return fail(400, "PERFORMANCE001", "판매 종료는 판매 오픈보다 늦어야 합니다");
      }
      // 검사 순서는 api-spec SLR-03의 "검사 순서"를 따른다.
      if (perf.status === "CANCELED") return fail(409, "PERFORMANCE002", "취소된 공연은 수정할 수 없습니다");
      const now = Date.now();
      const published = perf.status === "PUBLISHED";
      const venueChanged = b.venueId !== undefined && b.venueId !== perf.venue.venueId;
      const newVenueId = b.venueId ?? perf.venue.venueId;
      // 화면은 분 단위까지만 보내서 초는 비교에서 뺀다.
      const sameMinute = (a: string | null | undefined, c: string | null | undefined) => (a ?? "").slice(0, 16) === (c ?? "").slice(0, 16);
      const openChanged = !sameMinute(b.saleOpenAt, perf.saleOpenAt);
      if (published) {
        if (venueChanged) return fail(409, "PERFORMANCE003", "공개된 공연은 공연장을 바꿀 수 없습니다");
        if (b.cancelPolicyId !== undefined && b.cancelPolicyId !== perf.cancelPolicyId) {
          return fail(409, "PERFORMANCE004", "공개된 공연은 취소 규정을 바꿀 수 없습니다");
        }
        if (!b.saleOpenAt || !b.saleCloseAt || b.maxPerPerson == null || !b.receiveType || b.bookingFee == null) {
          return fail(400, "PERFORMANCE401", "공개된 공연은 판매 정보를 비울 수 없습니다");
        }
        if (openChanged && perf.saleOpenAt && toMs(perf.saleOpenAt) <= now) {
          return fail(409, "PERFORMANCE005", "판매가 열린 뒤에는 예매 오픈 일시를 바꿀 수 없습니다");
        }
        if (toMs(b.saleCloseAt) <= now) return fail(400, "PERFORMANCE405", "예매 종료 일시는 지금보다 늦어야 합니다");
        if (openChanged && toMs(b.saleOpenAt) - now <= PUBLISH_LEAD) {
          return fail(400, "PERFORMANCE406", "예매 오픈까지 3시간보다 더 남아 있어야 합니다");
        }
      }
      if (b.cancelPolicyId !== undefined && !state().policies.some((p) => p.cancelPolicyId === b.cancelPolicyId)) {
        return fail(404, "PERFORMANCE100", "취소 규정을 찾을 수 없습니다");
      }
      const newVenue = state().venues.find((v) => v.venueId === newVenueId);
      if (venueChanged && !newVenue) return fail(404, "VENUE000", "공연장을 찾을 수 없습니다");
      if (published && b.saleOpenAt && b.saleCloseAt) {
        const open = toMs(b.saleOpenAt);
        const early = activeSchedules(perf).find((x) => toMs(x.startAt) <= open);
        if (early) return fail(400, "PERFORMANCE407", `예매 오픈보다 먼저 시작하는 회차가 있습니다: ${clockText(early.startAt)}`);
        const starts = activeSchedules(perf).map((x) => toMs(x.startAt));
        if (starts.length > 0 && toMs(b.saleCloseAt) > Math.max(...starts)) {
          return fail(400, "PERFORMANCE408", "예매 종료가 마지막 회차 시작보다 늦을 수 없습니다");
        }
      }
      // 공연장을 바꾸거나 러닝타임을 늘리면 회차가 차지하는 시간이 달라져서 겹침을 다시 본다.
      if (venueChanged || (b.runningTime ?? 0) > perf.runningTime) {
        for (const x of activeSchedules(perf)) {
          const clash = findClash(newVenueId, toMs(x.startAt), b.runningTime!, x.scheduleId);
          if (clash) return fail(409, "PERFORMANCE007", `같은 공연장의 다른 회차와 시간이 겹칩니다: ${clockText(x.startAt)}`);
        }
      }
      if (venueChanged && newVenue) {
        perf.venue = { venueId: newVenue.venueId, name: newVenue.name };
        perf.zones = unsetZones(newVenue.venueId);
      }
      Object.assign(perf, {
        title: b.title!.trim(),
        genre: b.genre!,
        ageLimit: b.ageLimit!,
        runningTime: b.runningTime!,
        castInfo: b.castInfo?.trim() || null,
        posterUrl: b.posterUrl!,
        notice: b.notice?.trim() || null,
        guide: b.guide?.trim() || null,
        images: b.images ?? [],
        cancelPolicyId: b.cancelPolicyId ?? perf.cancelPolicyId,
        saleOpenAt: b.saleOpenAt ?? null,
        saleCloseAt: b.saleCloseAt ?? null,
        maxPerPerson: b.maxPerPerson ?? null,
        receiveType: b.receiveType ?? null,
        bookingFee: b.bookingFee ?? null,
        updatedAt: isoLocal(new Date()),
      });
      return ok({ performanceId: perf.performanceId, status: perf.status, updatedAt: perf.updatedAt });
    }],
    ["POST", "/api/v1/seller/performances/:id/publish", (ctx, [id]) => {
      const s = seller(ctx);
      if (s instanceof Response) return s;
      const perf = findPerf(id);
      if (!perf) return notFound();
      // 에러는 한 번에 하나씩, api-spec SLR-04의 순서대로 먼저 걸린 것이 나온다.
      if (perf.status !== "DRAFT") return fail(409, "PERFORMANCE400", "작성 중인 공연만 공개할 수 있습니다");
      if (!salesInfoSet(perf)) {
        return fail(400, "PERFORMANCE401", "판매 정보(예매 기간, 1인 최대 매수, 수령 방법, 예매 수수료)를 모두 입력해 주세요");
      }
      const now = Date.now();
      const open = toMs(perf.saleOpenAt!);
      const close = toMs(perf.saleCloseAt!);
      if (close <= now) return fail(400, "PERFORMANCE405", "예매 종료 일시가 지났습니다");
      if (open - now <= PUBLISH_LEAD) {
        return fail(400, "PERFORMANCE406", "예매 오픈까지 3시간보다 더 남아 있어야 공개할 수 있습니다");
      }
      const active = activeSchedules(perf);
      if (active.length === 0) return fail(400, "PERFORMANCE402", "취소되지 않은 회차를 하나 이상 추가해 주세요");
      const early = active.find((x) => toMs(x.startAt) <= open);
      if (early) return fail(400, "PERFORMANCE407", `예매 오픈보다 먼저 시작하는 회차가 있습니다: ${clockText(early.startAt)}`);
      if (close > Math.max(...active.map((x) => toMs(x.startAt)))) {
        return fail(400, "PERFORMANCE408", "예매 종료가 마지막 회차 시작보다 늦을 수 없습니다");
      }
      const unset = perf.zones.filter((z) => z.assignmentStatus === "UNSET");
      if (unset.length > 0) {
        return fail(400, "PERFORMANCE403", `등급을 정하지 않은 구역이 있습니다: ${unset.map((z) => z.name).join(", ")}`);
      }
      if (!perf.zones.some((z) => z.assignmentStatus === "ASSIGNED")) {
        return fail(400, "PERFORMANCE404", "판매하는 구역이 하나도 없습니다");
      }
      if (allMixed(perf.zones, perf.grades)) {
        return fail(409, "PERFORMANCE303", "전석 등급과 다른 등급을 함께 구역에 지정할 수 없습니다");
      }
      perf.status = "PUBLISHED";
      perf.updatedAt = isoLocal(new Date());
      return ok({
        performanceId: perf.performanceId,
        status: perf.status,
        intentionallyUnsoldZones: perf.zones
          .filter((z) => z.assignmentStatus === "INTENTIONALLY_UNSOLD")
          .map((z) => ({ zoneId: z.zoneId, name: z.name })),
      });
    }],
    ["POST", "/api/v1/seller/performances/:id/cancel", async (ctx, [id]) => {
      const s = seller(ctx);
      if (s instanceof Response) return s;
      const perf = findPerf(id);
      if (!perf) return notFound();
      if (perf.status === "CANCELED") return fail(409, "PERFORMANCE013", "이미 취소된 공연입니다");
      const b = await body<{ reason: string }>(ctx);
      if (!b.reason?.trim()) return fail(400, "COMMON000", "reason: 취소 사유를 입력해 주세요");
      const active = perf.schedules.filter((x) => x.status !== "CANCELED");
      active.forEach((x) => {
        x.status = "CANCELED";
      });
      perf.status = "CANCELED";
      perf.updatedAt = isoLocal(new Date());
      return ok({
        performanceId: perf.performanceId,
        status: "CANCELED",
        canceledScheduleCount: active.length,
        refundRequestedCount: active.filter((x) => x.seatsGeneratedAt).length * 12,
      });
    }],

    // ----- 판매자: 등급·구역 -----
    ["GET", "/api/v1/seller/performances/:id/grades", (ctx, [id]) => {
      const s = seller(ctx);
      if (s instanceof Response) return s;
      const perf = findPerf(id);
      return perf ? ok(gradesView(perf)) : notFound();
    }],
    ["POST", "/api/v1/seller/performances/:id/grades", async (ctx, [id]) => {
      const s = seller(ctx);
      if (s instanceof Response) return s;
      // 판정 순서(api-spec SLR-09): 입력 형식 400 → 공연 404 → 취소된 공연 409 → 같은 등급 409 → locked 409
      const b = await body<Grade>(ctx);
      if (!GRADE_CODES.includes(b.name ?? "") || typeof b.price !== "number" || b.price < 0 || b.price > 10_000_000) {
        return fail(400, "COMMON000", "name, price: 등급 코드와 0원 이상 1,000만원 이하의 가격을 입력해 주세요");
      }
      const perf = findPerf(id);
      if (!perf) return notFound();
      if (perf.status === "CANCELED") return fail(409, "PERFORMANCE002", "취소된 공연의 등급은 바꿀 수 없습니다");
      if (perf.grades.some((x) => x.name === b.name)) {
        return fail(409, "PERFORMANCE302", "이미 같은 등급이 있습니다");
      }
      if (gradesView(perf).locked) return fail(409, "PERFORMANCE301", "판매 좌석을 만든 뒤에는 등급을 바꿀 수 없습니다");
      const grade: Grade = { gradeId: perf.nextGradeId++, name: b.name!, price: b.price };
      perf.grades.push(grade);
      return ok(grade, 201);
    }],
    ["PUT", "/api/v1/seller/performances/:id/grades/:gradeId", async (ctx, [id, gradeId]) => {
      const s = seller(ctx);
      if (s instanceof Response) return s;
      // 판정 순서(api-spec SLR-10): 입력 형식 400 → 공연 404 → 취소된 공연 409 → 등급 404 → 같은 등급 409 → 전석 섞임 409 → locked 409
      const b = await body<Grade>(ctx);
      if (!GRADE_CODES.includes(b.name ?? "") || typeof b.price !== "number" || b.price < 0 || b.price > 10_000_000) {
        return fail(400, "COMMON000", "name, price: 등급 코드와 0원 이상 1,000만원 이하의 가격을 입력해 주세요");
      }
      const perf = findPerf(id);
      if (!perf) return notFound();
      if (perf.status === "CANCELED") return fail(409, "PERFORMANCE002", "취소된 공연의 등급은 바꿀 수 없습니다");
      const grade = perf.grades.find((x) => x.gradeId === Number(gradeId));
      if (!grade) return fail(404, "PERFORMANCE300", "등급을 찾을 수 없습니다");
      if (perf.grades.some((x) => x.gradeId !== grade.gradeId && x.name === b.name)) {
        return fail(409, "PERFORMANCE302", "이미 같은 등급이 있습니다");
      }
      // 이름을 바꾼 뒤의 등급 목록으로 전석 섞임을 본다. 구역에 지정되지 않은 등급은 검사하지 않는다.
      const renamed = perf.grades.map((x) => (x.gradeId === grade.gradeId ? { ...x, name: b.name! } : x));
      if (allMixed(perf.zones, renamed)) {
        return fail(409, "PERFORMANCE303", "전석 등급과 다른 등급이 함께 구역에 지정됩니다");
      }
      if (gradesView(perf).locked) return fail(409, "PERFORMANCE301", "판매 좌석을 만든 뒤에는 등급을 바꿀 수 없습니다");
      Object.assign(grade, { name: b.name!, price: b.price });
      return ok(grade);
    }],
    ["PATCH", "/api/v1/seller/performances/:id/zone-grades", async (ctx, [id]) => {
      const s = seller(ctx);
      if (s instanceof Response) return s;
      const b = await body<{
        assignments: { zoneId: number; assignmentStatus: ZoneAssignment["assignmentStatus"]; gradeId: number | null }[];
      }>(ctx);
      // 검사 순서(api-spec SLR-11): 요청 형식 400 → 상태·gradeId 조합 400 → 공연 404 → 취소된 공연 409
      //   → 공연장에 없는 구역 400 → 공연에 없는 등급 400 → 전석 섞임 409 → 좌석 생성 후 409
      const list = b.assignments;
      const statuses: ZoneAssignment["assignmentStatus"][] = ["ASSIGNED", "INTENTIONALLY_UNSOLD", "UNSET"];
      if (
        !Array.isArray(list) || list.length === 0 ||
        list.some((a) => typeof a.zoneId !== "number" || !statuses.includes(a.assignmentStatus)) ||
        new Set(list.map((a) => a.zoneId)).size !== list.length
      ) {
        return fail(400, "COMMON000", "assignments: 구역 설정을 한 개 이상, 같은 구역 없이 보내 주세요");
      }
      if (list.some((a) => (a.assignmentStatus === "ASSIGNED") !== (a.gradeId != null))) {
        return fail(400, "PERFORMANCE306", "지정(ASSIGNED)에는 등급이 필요하고, 미설정·판매 제외에는 등급을 보내지 않습니다");
      }
      const perf = findPerf(id);
      if (!perf) return notFound();
      if (perf.status === "CANCELED") return fail(409, "PERFORMANCE002", "취소된 공연의 구역은 바꿀 수 없습니다");
      if (list.some((a) => !perf.zones.some((z) => z.zoneId === a.zoneId))) {
        return fail(400, "PERFORMANCE304", "이 공연의 공연장에 없는 구역입니다");
      }
      if (list.some((a) => a.assignmentStatus === "ASSIGNED" && !perf.grades.some((x) => x.gradeId === a.gradeId))) {
        return fail(400, "PERFORMANCE305", "이 공연의 등급이 아닙니다");
      }
      // 하나라도 틀리면 아무것도 바꾸지 않아야 해서, 적용한 뒤의 상태를 먼저 만들어 검사하고 한꺼번에 반영한다.
      const next = perf.zones.map((z) => {
        const a = list.find((x) => x.zoneId === z.zoneId);
        return a ? { ...z, assignmentStatus: a.assignmentStatus, gradeId: a.assignmentStatus === "ASSIGNED" ? a.gradeId : null } : z;
      });
      if (allMixed(next, perf.grades)) {
        return fail(409, "PERFORMANCE303", "전석 등급과 다른 등급이 함께 구역에 지정됩니다");
      }
      if (gradesView(perf).locked) return fail(409, "PERFORMANCE301", "판매 좌석을 만든 뒤에는 구역 설정을 바꿀 수 없습니다");
      perf.zones = next;
      return ok(gradesView(perf));
    }],

    // ----- 판매자: 회차 -----
    ["POST", "/api/v1/seller/performances/:id/schedules", async (ctx, [id]) => {
      const s = seller(ctx);
      if (s instanceof Response) return s;
      // 에러는 한 번에 하나씩, api-spec SLR-12의 순서대로 먼저 걸린 것이 나온다.
      const b = await body<{ startAt: string }>(ctx);
      const now = Date.now();
      if (!b.startAt || Number.isNaN(toMs(b.startAt)) || toMs(b.startAt) <= now) {
        return fail(400, "COMMON000", "startAt: 지금보다 늦은 공연 일시를 입력해 주세요");
      }
      const perf = findPerf(id);
      if (!perf) return notFound();
      if (perf.status === "CANCELED") return fail(409, "PERFORMANCE002", "취소된 공연에는 회차를 추가할 수 없습니다");
      const startAt = b.startAt.length === 16 ? `${b.startAt}:00` : b.startAt;
      const startMs = toMs(startAt);
      if (perf.saleOpenAt && startMs <= toMs(perf.saleOpenAt)) {
        return fail(400, "PERFORMANCE407", "회차는 예매 오픈보다 늦게 시작해야 합니다");
      }
      const published = perf.status === "PUBLISHED";
      if (!published && startMs <= now + PUBLISH_LEAD) {
        return fail(400, "PERFORMANCE009", "작성 중인 공연의 회차는 지금부터 3시간보다 뒤여야 합니다");
      }
      if (published) {
        const live = activeSchedules(perf);
        if (live.length > 0 && live.every((x) => toMs(x.startAt) <= now)) {
          return fail(409, "PERFORMANCE010", "모든 회차가 이미 시작한 공연에는 회차를 추가할 수 없습니다");
        }
        if (perf.saleCloseAt && toMs(perf.saleCloseAt) <= now) {
          return fail(400, "PERFORMANCE405", "예매가 끝난 공연에는 회차를 추가할 수 없습니다");
        }
      }
      // 공개 공연은 판매 오픈 24시간 전부터 회차를 추가하면서 판매 좌석을 바로 만든다. 만들 수 없으면 회차도 추가되지 않는다.
      const immediate = published && perf.saleOpenAt != null && toMs(perf.saleOpenAt) - now <= PRE_GENERATE;
      if (immediate) {
        const unset = perf.zones.filter((z) => z.assignmentStatus === "UNSET");
        if (unset.length > 0) {
          return fail(400, "PERFORMANCE403", `등급을 정하지 않은 구역이 있습니다: ${unset.map((z) => z.name).join(", ")}`);
        }
        if (!perf.zones.some((z) => z.assignmentStatus === "ASSIGNED")) {
          return fail(400, "PERFORMANCE404", "판매하는 구역이 하나도 없습니다");
        }
      }
      const clash = findClash(perf.venue.venueId, startMs, perf.runningTime);
      if (clash) return fail(409, "PERFORMANCE007", clashMessage(clash));
      const schedule = {
        scheduleId: state().nextScheduleId++,
        startAt,
        status: "ON_SALE" as const,
        seatsGeneratedAt: immediate ? isoLocal(new Date(now)) : null,
      };
      perf.schedules.push(schedule);
      perf.schedules.sort((a, c) => a.startAt.localeCompare(c.startAt));
      return ok({ ...schedule, performanceId: perf.performanceId }, 201);
    }],
    ["PUT", "/api/v1/seller/schedules/:sid", async (ctx, [sid]) => {
      const s = seller(ctx);
      if (s instanceof Response) return s;
      const found = findSchedule(sid);
      if (!found) return fail(404, "SCHEDULE000", "회차를 찾을 수 없습니다");
      const b = await body<{ startAt: string }>(ctx);
      if (!b.startAt || Number.isNaN(new Date(b.startAt).getTime())) {
        return fail(400, "COMMON000", "startAt: 공연 일시를 입력해 주세요");
      }
      found.schedule.startAt = b.startAt.length === 16 ? `${b.startAt}:00` : b.startAt;
      found.perf.schedules.sort((a, c) => a.startAt.localeCompare(c.startAt));
      return ok({ scheduleId: found.schedule.scheduleId, startAt: found.schedule.startAt, updatedAt: isoLocal(new Date()) });
    }],
    ["POST", "/api/v1/seller/schedules/:sid/seats/generate", (ctx, [sid]) => {
      const s = seller(ctx);
      if (s instanceof Response) return s;
      // 에러는 한 번에 하나씩, api-spec SLR-14의 순서대로 먼저 걸린 것이 나온다.
      const found = findSchedule(sid);
      if (!found) return fail(404, "PERFORMANCE008", "회차를 찾을 수 없습니다");
      const { perf, schedule } = found;
      if (perf.status === "CANCELED") return fail(409, "PERFORMANCE002", "취소된 공연의 판매 좌석은 만들 수 없습니다");
      if (schedule.status === "CANCELED") return fail(409, "PERFORMANCE501", "취소된 회차의 판매 좌석은 만들 수 없습니다");
      if (schedule.seatsGeneratedAt) return fail(409, "PERFORMANCE500", "이미 판매 좌석을 만들었습니다");
      if (toMs(schedule.startAt) <= Date.now()) return fail(400, "PERFORMANCE502", "이미 시작한 회차의 판매 좌석은 만들 수 없습니다");
      if (perf.status === "DRAFT" && !salesInfoSet(perf)) {
        return fail(400, "PERFORMANCE401", "판매 정보(예매 기간, 1인 최대 매수, 수령 방법, 예매 수수료)를 먼저 입력해 주세요");
      }
      const unset = perf.zones.filter((z) => z.assignmentStatus === "UNSET");
      if (unset.length > 0) {
        return fail(400, "PERFORMANCE403", `등급을 정하지 않은 구역이 있습니다: ${unset.map((z) => z.name).join(", ")}`);
      }
      if (!perf.zones.some((z) => z.assignmentStatus === "ASSIGNED")) {
        return fail(400, "PERFORMANCE404", "판매하는 구역이 하나도 없습니다");
      }
      schedule.seatsGeneratedAt = isoLocal(new Date());
      // 판매 구역은 AVAILABLE, 판매 제외 구역은 BLOCKED 로 만들고 판매자 보류석(RESERVED)은 만들지 않는다.
      const available = seatsByStatus(perf, "ASSIGNED");
      const blocked = seatsByStatus(perf, "INTENTIONALLY_UNSOLD");
      return ok({
        scheduleId: schedule.scheduleId,
        seatsGeneratedAt: schedule.seatsGeneratedAt,
        totalSeats: available + blocked,
        availableSeats: available,
        blockedSeats: blocked,
      });
    }],
    ["POST", "/api/v1/seller/schedules/:sid/cancel", async (ctx, [sid]) => {
      const s = seller(ctx);
      if (s instanceof Response) return s;
      const found = findSchedule(sid);
      if (!found) return fail(404, "SCHEDULE000", "회차를 찾을 수 없습니다");
      if (found.schedule.status === "CANCELED") return fail(409, "SCHEDULE003", "이미 취소된 회차입니다");
      const b = await body<{ reason: string }>(ctx);
      if (!b.reason?.trim()) return fail(400, "COMMON000", "reason: 취소 사유를 입력해 주세요");
      found.schedule.status = "CANCELED";
      return ok({
        scheduleId: found.schedule.scheduleId,
        status: "CANCELED",
        refundRequestedCount: found.schedule.seatsGeneratedAt ? 12 : 0,
      });
    }],
    ["GET", "/api/v1/seller/performances/:id/sales", (ctx, [id]) => {
      const s = seller(ctx);
      if (s instanceof Response) return s;
      const perf = findPerf(id);
      if (!perf) return notFound();
      const blocked = perf.zones
        .filter((z) => z.assignmentStatus === "INTENTIONALLY_UNSOLD")
        .reduce((sum, z) => sum + z.seatCount, 0);
      const schedules: SalesSchedule[] = perf.schedules.map((x) => {
        if (!x.seatsGeneratedAt) {
          return {
            scheduleId: x.scheduleId, startAt: x.startAt, status: x.status, seatsGenerated: false,
            totalSeats: totalSeats(perf), soldSeats: null, heldSeats: null, availableSeats: null,
            reservedSeats: null, blockedSeats: null,
          };
        }
        const sold = 30 + ((x.scheduleId * 7) % 50);
        const held = 4;
        return {
          scheduleId: x.scheduleId, startAt: x.startAt, status: x.status, seatsGenerated: true,
          totalSeats: totalSeats(perf), soldSeats: sold, heldSeats: held,
          availableSeats: totalSeats(perf) - sold - held - blocked, reservedSeats: 0, blockedSeats: blocked,
        };
      });
      return ok({ schedules });
    }],
    ["GET", "/api/v1/seller/schedules/:sid/bookings", (ctx, [sid]) => {
      const s = seller(ctx);
      if (s instanceof Response) return s;
      const found = findSchedule(sid);
      if (!found) return fail(404, "PERFORMANCE008", "회차를 찾을 수 없습니다");
      if (!found.schedule.seatsGeneratedAt) return ok(pageOf([], ctx.search));
      const names = ["김민준", "이서연", "박도윤", "최하은", "정시우", "한지아", "오세훈", "윤나린"];
      const status = ctx.search.get("status");
      const receiveType = ctx.search.get("receiveType");
      const bookingNo = ctx.search.get("bookingNo");
      if (status && !["PENDING", "PAID", "PARTIAL_CANCELED", "CANCELED", "EXPIRED"].includes(status)) {
        return fail(400, "COMMON000", "요청 값이 올바르지 않습니다.");
      }
      const items: ScheduleBooking[] = names.map((name, i) => {
        const bookingStatus = (i === 5 ? "CANCELED" : i === 6 ? "PARTIAL_CANCELED" : "PAID") as ScheduleBooking["status"];
        const count = (i % 3) + 1;
        const delivery = i % 4 === 0;
        const canceled = bookingStatus === "CANCELED" ? count : bookingStatus === "PARTIAL_CANCELED" ? 1 : 0;
        const kept = count - canceled;
        const tickets: TicketGroup[] = kept > 0 ? [{ gradeName: i % 2 === 0 ? "VIP" : "R", price: i % 2 === 0 ? 150000 : 120000, count: kept, amount: kept * (i % 2 === 0 ? 150000 : 120000) }] : [];
        const canceledTickets: TicketGroup[] = canceled > 0 ? [{ gradeName: "S", price: 90000, count: canceled, amount: canceled * 90000 }] : [];
        return {
          bookingId: 2000 + Number(sid) + i,
          bookingNo: `20261${110 + i}${(name.charCodeAt(0) * 7919).toString(36).toUpperCase().slice(0, 6)}`,
          status: bookingStatus,
          ordererName: name,
          ordererPhone: `0101234${String(1000 + i * 37).slice(0, 4)}`,
          receiveType: (delivery ? "DELIVERY" : "ONSITE") as ScheduleBooking["receiveType"],
          delivery: delivery ? { recipientName: name, recipientPhone: `0102345${String(1000 + i * 41).slice(0, 4)}`, recipientAddress: "서울 가상구 1" } : null,
          tickets,
          canceledTickets,
          seatCount: kept,
          canceledSeatCount: canceled,
          ticketAmount: tickets.reduce((sum, t) => sum + t.amount, 0),
          createdAt: isoLocal(new Date(Date.now() - (i + 1) * 3_600_000 * 5)),
        };
      }).filter(
        (b) =>
          // 상태를 주지 않으면 남은 표가 있는 예매(PAID, PARTIAL_CANCELED)만 나온다.
          (status ? b.status === status : b.status === "PAID" || b.status === "PARTIAL_CANCELED") &&
          (!receiveType || b.receiveType === receiveType) &&
          (!bookingNo || b.bookingNo === bookingNo),
      );
      return ok(pageOf(items, ctx.search));
    }],

    // ----- 판매자: 취소 규정 -----
    ["GET", "/api/v1/seller/cancel-policies", (ctx) => {
      const s = seller(ctx);
      if (s instanceof Response) return s;
      const items = [...state().policies]
        .sort((a, b) => Number(b.isDefault) - Number(a.isDefault) || b.cancelPolicyId - a.cancelPolicyId)
        .map((p) => ({
          cancelPolicyId: p.cancelPolicyId, name: p.name, isDefault: p.isDefault, editable: policyEditable(p),
        }));
      return ok(pageOf(items, ctx.search));
    }],
    ["GET", "/api/v1/seller/cancel-policies/:id", (ctx, [id]) => {
      const s = seller(ctx);
      if (s instanceof Response) return s;
      const policy = state().policies.find((p) => p.cancelPolicyId === Number(id));
      return policy ? ok(policyView(policy)) : fail(404, "PERFORMANCE100", "취소 규정을 찾을 수 없습니다");
    }],
    ["POST", "/api/v1/seller/cancel-policies", async (ctx) => {
      const s = seller(ctx);
      if (s instanceof Response) return s;
      const parsed = await readPolicyBody(ctx);
      if ("error" in parsed) return parsed.error;
      const policy: PolicyRec = {
        cancelPolicyId: state().nextPolicyId++, name: parsed.name, isDefault: false, rules: parsed.rules,
      };
      state().policies.push(policy);
      return ok(policyView(policy), 201);
    }],
    ["PUT", "/api/v1/seller/cancel-policies/:id", async (ctx, [id]) => {
      const s = seller(ctx);
      if (s instanceof Response) return s;
      const policy = state().policies.find((p) => p.cancelPolicyId === Number(id));
      if (!policy) return fail(404, "PERFORMANCE100", "취소 규정을 찾을 수 없습니다");
      if (policy.isDefault) return fail(409, "PERFORMANCE102", "서비스 기본 규정은 수정할 수 없습니다");
      if (!policyEditable(policy)) {
        return fail(409, "PERFORMANCE103", "공개되었거나 취소된 공연이 쓰는 규정은 수정할 수 없습니다");
      }
      const parsed = await readPolicyBody(ctx, policy.cancelPolicyId);
      if ("error" in parsed) return parsed.error;
      policy.name = parsed.name;
      policy.rules = parsed.rules;
      return ok(policyView(policy));
    }],

    // ----- 관리자: 사업체 -----
    ["GET", "/api/v1/admin/seller-businesses", (ctx) => {
      const s = admin(ctx);
      if (s instanceof Response) return s;
      const status = ctx.search.get("status") ?? "PENDING";
      if (!["PENDING", "APPROVED", "REJECTED", "SUSPENDED", "ALL"].includes(status)) {
        return fail(400, "COMMON000", "status: 알 수 없는 상태입니다");
      }
      const keyword = ctx.search.get("keyword")?.trim().toLowerCase();
      const items = state().businesses
        .filter(
          (b) =>
            (status === "ALL" || b.status === status) &&
            (!keyword ||
              [b.companyName, b.representativeName, b.businessNumber].some((v) => v.toLowerCase().includes(keyword))),
        )
        .sort((a, b) => b.businessId - a.businessId)
        .map((b) => ({
          businessId: b.businessId, companyName: b.companyName, representativeName: b.representativeName,
          businessNumber: b.businessNumber, status: b.status, createdAt: b.createdAt, reviewedAt: b.reviewedAt,
        }));
      return ok(pageOf(items, ctx.search, 10, 20));
    }],
    ["GET", "/api/v1/admin/seller-businesses/:id", (ctx, [id]) => {
      const s = admin(ctx);
      if (s instanceof Response) return s;
      const b = state().businesses.find((x) => x.businessId === Number(id));
      if (!b) return fail(404, "SELLER006", "사업체를 찾을 수 없습니다");
      return ok({
        ...b,
        reviewedBy: b.reviewedAt ? 1 : null,
        termsAgreedAt: b.createdAt,
        owner: { memberId: b.businessId + 4, email: "owner@example.com", phone: "01011112222", status: "ACTIVE" },
      });
    }],
    ...(["approve", "reject"] as const).map((action): [string, string, Handler] => [
      "POST",
      `/api/v1/admin/seller-businesses/:id/${action}`,
      async (ctx, [id]) => {
        const s = admin(ctx);
        if (s instanceof Response) return s;
        const b = state().businesses.find((x) => x.businessId === Number(id));
        if (!b) return fail(404, "SELLER006", "사업체를 찾을 수 없습니다");
        if (b.status !== "PENDING") return fail(409, "SELLER007", "이미 처리된 사업체입니다");
        let reason: string | null = null;
        if (action === "reject") {
          const body2 = await body<{ reason: string }>(ctx);
          reason = body2.reason?.trim() ?? "";
          if (!reason || reason.length > 500) {
            return fail(400, "COMMON000", "reason: 반려 사유를 500자 이내로 입력해 주세요");
          }
        }
        b.status = (action === "approve" ? "APPROVED" : "REJECTED") as BusinessStatus;
        b.reviewedAt = isoLocal(new Date());
        b.rejectReason = reason;
        return ok({
          businessId: b.businessId, status: b.status, ...(reason ? { rejectReason: reason } : {}), reviewedAt: b.reviewedAt,
        });
      },
    ]),

    // ----- 관리자: 공연장 -----
    ["POST", "/api/v1/admin/venues", async (ctx) => {
      const s = admin(ctx);
      if (s instanceof Response) return s;
      const b = await body<VenueForm>(ctx);
      if (!b.name?.trim() || !b.address?.trim() || !b.region?.trim() || !b.layoutKey?.trim()) {
        return fail(400, "COMMON000", "name, address, region, layoutKey: 모두 입력해 주세요");
      }
      if (!Array.isArray(b.zones) || b.zones.length === 0) {
        return fail(400, "COMMON000", "zones: 구역을 하나 이상 추가해 주세요");
      }
      let seats = 0;
      for (const zone of b.zones) {
        if (!zone.name?.trim() || !zone.floor?.trim() || !Array.isArray(zone.rows) || zone.rows.length === 0) {
          return fail(400, "COMMON000", "zones: 구역마다 이름, 층, 열이 필요합니다");
        }
        for (const row of zone.rows) {
          if (!row.label?.trim() || !Number.isInteger(row.from) || !Number.isInteger(row.to) || row.from > row.to) {
            return fail(400, "COMMON000", `${zone.name}: 열 이름과 번호 범위(시작 ≤ 끝)를 확인해 주세요`);
          }
          seats += row.to - row.from + 1;
        }
      }
      if (state().venues.some((v) => v.name === b.name!.trim())) {
        return fail(409, "VENUE002", "같은 이름의 공연장이 있습니다");
      }
      const venue: VenueItem = {
        venueId: state().nextVenueId++, name: b.name.trim(), address: b.address.trim(),
        region: b.region.trim(), totalSeats: seats, layoutKey: b.layoutKey.trim(),
      };
      state().venues.push(venue);
      return ok({ venueId: venue.venueId, name: venue.name, status: "ACTIVE", totalSeats: seats, zoneCount: b.zones.length }, 201);
    }],

    // ----- 관리자: 환불 -----
    ["GET", "/api/v1/admin/refunds", (ctx) => {
      const s = admin(ctx);
      if (s instanceof Response) return s;
      const status = ctx.search.get("status") ?? "FAILED";
      if (!["REQUESTED", "PROCESSING", "DONE", "FAILED"].includes(status)) {
        return fail(400, "COMMON000", "status: 모르는 상태 값입니다");
      }
      // exhaustedOnly 는 상태와 상관없이 재시도 횟수로 거른다. (서버: 켜면 최소 3회, 끄면 0회)
      const exhaustedOnly = (ctx.search.get("exhaustedOnly") ?? "true") === "true";
      // 오래 기다린 요청부터. 정렬은 고정이고 sort 는 무시한다.
      const items = state().refunds
        .filter((r) => r.status === status && r.retryCount >= (exhaustedOnly ? REFUND_MAX_RETRY : 0))
        .sort((a, b) => a.requestedAt.localeCompare(b.requestedAt) || a.refundId - b.refundId);
      return ok(pageOf(items, ctx.search, 20, 20));
    }],
    ["POST", "/api/v1/admin/refunds/:id/retry", (ctx, [id]) => {
      const s = admin(ctx);
      if (s instanceof Response) return s;
      // 판정 순서(api-spec ADM-06): 없음 → 처리 중 → 실패가 아님 → 15일 지남
      const refund = state().refunds.find((r) => r.refundId === Number(id));
      if (!refund) return fail(404, "PAYMENT007", "환불을 찾을 수 없습니다");
      if (refund.status === "PROCESSING") return fail(409, "PAYMENT009", "다른 곳에서 처리 중인 환불입니다. 잠시 후 다시 확인해 주세요");
      if (refund.status !== "FAILED") return fail(409, "PAYMENT008", "실패한 환불만 다시 처리할 수 있습니다");
      if (Date.now() - toMs(refund.requestedAt) > REFUND_KEY_DAYS * DAY) {
        return fail(409, "PAYMENT010", "처음 요청한 지 15일이 지나 다시 보낼 수 없습니다. 토스에서 실제 환불 여부를 확인해 주세요");
      }
      // 보낼 때마다 횟수가 1 늘어난다. 토스가 또 실패해도 200 이고 status 가 FAILED 로 남는다.
      refund.retryCount += 1;
      refund.lastAttemptedAt = isoLocal(new Date());
      if (refund.refundId === 291) {
        refund.failReason = "카드사 점검으로 승인 취소 거절 (REJECT_CARD_COMPANY)";
        return ok({ refundId: refund.refundId, status: refund.status, retryCount: refund.retryCount, failReason: refund.failReason, completedAt: null });
      }
      refund.status = "DONE";
      refund.failReason = null;
      return ok({
        refundId: refund.refundId,
        status: refund.status,
        retryCount: refund.retryCount,
        failReason: null,
        completedAt: refund.lastAttemptedAt,
      });
    }],
  ];

  return routes;
}
