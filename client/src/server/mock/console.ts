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
      zones: ZONES.map((z, idx) => ({
        ...z,
        assignmentStatus: "ASSIGNED" as const,
        gradeId: grades[Math.min(GRADE_INDEX_BY_ZONE[idx], grades.length - 1)]?.gradeId ?? null,
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
    zones: ZONES.map((z) => ({ ...z, assignmentStatus: "UNSET" as const, gradeId: null })),
  };
  return [draft, ...published.reverse()];
}

function seedRefunds(): RefundRec[] {
  const at = (days: number) => isoLocal(new Date(Date.now() - days * DAY));
  return [
    {
      refundId: 288, bookingId: 874, bookingNo: "20261110QWRT91K", paymentId: 702, amount: 156000,
      reasonType: "PERFORMANCE_CANCEL", reasonDetail: "출연자 사정으로 회차 취소", status: "FAILED",
      retryCount: 3, failReason: "PG 응답 시간 초과", requestedAt: at(5),
    },
    {
      refundId: 291, bookingId: 880, bookingNo: "20261111M3ZA7PD", paymentId: 709, amount: 99000,
      reasonType: "USER_CANCEL", reasonDetail: null, status: "FAILED",
      retryCount: 3, failReason: "카드사 점검으로 승인 취소 거절", requestedAt: at(4),
    },
    {
      refundId: 295, bookingId: 891, bookingNo: "20261112K8CC02X", paymentId: 716, amount: 330000,
      reasonType: "PERFORMANCE_CANCEL", reasonDetail: "공연 전체 취소", status: "FAILED",
      retryCount: 1, failReason: "PG 일시 오류", requestedAt: at(3),
    },
    {
      refundId: 280, bookingId: 860, bookingNo: "20261105H2LL81B", paymentId: 690, amount: 77000,
      reasonType: "USER_CANCEL", reasonDetail: null, status: "DONE",
      retryCount: 0, failReason: null, requestedAt: at(12),
    },
  ];
}

const g = globalThis as unknown as { __ticktingConsole?: ConsoleState };

function state(): ConsoleState {
  if (!g.__ticktingConsole) {
    g.__ticktingConsole = {
      performances: seedPerformances(),
      venues: MOCK_VENUES.map((v) => ({ ...v, totalSeats: 160, layoutKey: "mock-hall" })),
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

  const totalSeats = () => ZONES.reduce((sum, z) => sum + z.seatCount, 0);

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
        zones: ZONES.map((z) => ({ ...z, assignmentStatus: "UNSET" as const, gradeId: null })),
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
      if (perf.status === "CANCELED") return fail(409, "PERFORMANCE005", "취소된 공연은 수정할 수 없습니다");
      const b = await body<PerformanceForm>(ctx);
      const problems = validatePerformance(b);
      if (problems.length > 0) return fail(400, "COMMON000", problems.join(", "));
      if (b.saleOpenAt && b.saleCloseAt && b.saleCloseAt <= b.saleOpenAt) {
        return fail(400, "PERFORMANCE001", "판매 종료는 판매 오픈보다 늦어야 합니다");
      }
      if (perf.status === "PUBLISHED" && b.cancelPolicyId !== perf.cancelPolicyId) {
        return fail(409, "PERFORMANCE006", "공개된 공연은 취소 규정을 바꿀 수 없습니다");
      }
      if (b.venueId !== undefined && b.venueId !== perf.venue.venueId) {
        if (perf.status !== "DRAFT") return fail(409, "PERFORMANCE007", "공개된 공연은 공연장을 바꿀 수 없습니다");
        const venue = state().venues.find((v) => v.venueId === b.venueId);
        if (!venue) return fail(404, "VENUE000", "공연장을 찾을 수 없습니다");
        perf.venue = { venueId: venue.venueId, name: venue.name };
        perf.zones = ZONES.map((z) => ({ ...z, assignmentStatus: "UNSET" as const, gradeId: null }));
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
      if (perf.status !== "DRAFT") return fail(409, "PERFORMANCE008", "작성 중인 공연만 공개할 수 있습니다");
      if (
        !perf.saleOpenAt || !perf.saleCloseAt || perf.maxPerPerson == null ||
        !perf.receiveType || perf.bookingFee == null
      ) {
        return fail(400, "PERFORMANCE009", "판매 정보(예매 기간, 1인 최대 매수, 수령 방법, 예매 수수료)를 모두 입력해 주세요");
      }
      if (perf.schedules.length === 0) return fail(400, "PERFORMANCE010", "회차를 하나 이상 추가해 주세요");
      const unset = perf.zones.filter((z) => z.assignmentStatus === "UNSET");
      if (unset.length > 0) {
        return fail(400, "PERFORMANCE011", `등급을 정하지 않은 구역이 있습니다: ${unset.map((z) => z.name).join(", ")}`);
      }
      if (!perf.zones.some((z) => z.assignmentStatus === "ASSIGNED")) {
        return fail(400, "PERFORMANCE012", "판매하는 구역이 하나도 없습니다");
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
      const perf = findPerf(id);
      if (!perf) return notFound();
      const b = await body<Grade>(ctx);
      if (!GRADE_CODES.includes(b.name ?? "") || typeof b.price !== "number" || b.price < 0 || b.price > 10_000_000) {
        return fail(400, "COMMON000", "name, price: 등급 코드와 0원 이상 1,000만원 이하의 가격을 입력해 주세요");
      }
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
      const perf = findPerf(id);
      if (!perf) return notFound();
      const grade = perf.grades.find((x) => x.gradeId === Number(gradeId));
      if (!grade) return fail(404, "PERFORMANCE300", "등급을 찾을 수 없습니다");
      const b = await body<Grade>(ctx);
      if (!GRADE_CODES.includes(b.name ?? "") || typeof b.price !== "number" || b.price < 0 || b.price > 10_000_000) {
        return fail(400, "COMMON000", "name, price: 등급 코드와 0원 이상 1,000만원 이하의 가격을 입력해 주세요");
      }
      if (perf.grades.some((x) => x.gradeId !== grade.gradeId && x.name === b.name)) {
        return fail(409, "PERFORMANCE302", "이미 같은 등급이 있습니다");
      }
      if (gradesView(perf).locked) return fail(409, "PERFORMANCE301", "판매 좌석을 만든 뒤에는 등급을 바꿀 수 없습니다");
      Object.assign(grade, { name: b.name!, price: b.price });
      return ok(grade);
    }],
    ["PATCH", "/api/v1/seller/performances/:id/zone-grades", async (ctx, [id]) => {
      const s = seller(ctx);
      if (s instanceof Response) return s;
      const perf = findPerf(id);
      if (!perf) return notFound();
      if (gradesView(perf).locked) return fail(409, "PERFORMANCE014", "판매 좌석을 만든 뒤에는 구역 설정을 바꿀 수 없습니다");
      const b = await body<{
        assignments: { zoneId: number; assignmentStatus: ZoneAssignment["assignmentStatus"]; gradeId: number | null }[];
      }>(ctx);
      for (const a of b.assignments ?? []) {
        const zone = perf.zones.find((z) => z.zoneId === a.zoneId);
        if (!zone) return fail(400, "COMMON000", "다른 공연장의 구역입니다");
        if (a.assignmentStatus === "ASSIGNED") {
          if (!perf.grades.some((x) => x.gradeId === a.gradeId)) {
            return fail(400, "COMMON000", "이 공연의 등급이 아닙니다");
          }
          zone.gradeId = a.gradeId;
        } else {
          zone.gradeId = null;
        }
        zone.assignmentStatus = a.assignmentStatus;
      }
      return ok(gradesView(perf));
    }],

    // ----- 판매자: 회차 -----
    ["POST", "/api/v1/seller/performances/:id/schedules", async (ctx, [id]) => {
      const s = seller(ctx);
      if (s instanceof Response) return s;
      const perf = findPerf(id);
      if (!perf) return notFound();
      if (perf.status === "CANCELED") return fail(409, "PERFORMANCE005", "취소된 공연에는 회차를 추가할 수 없습니다");
      const b = await body<{ startAt: string }>(ctx);
      if (!b.startAt || Number.isNaN(new Date(b.startAt).getTime())) {
        return fail(400, "COMMON000", "startAt: 공연 일시를 입력해 주세요");
      }
      const startAt = b.startAt.length === 16 ? `${b.startAt}:00` : b.startAt;
      const gap = perf.runningTime * 60_000 + 30 * 60_000;
      const clash = state().performances
        .filter((p) => p.venue.venueId === perf.venue.venueId && p.status !== "CANCELED")
        .flatMap((p) => p.schedules)
        .some((x) => x.status !== "CANCELED" && Math.abs(new Date(x.startAt).getTime() - new Date(startAt).getTime()) < gap);
      if (clash) return fail(409, "SCHEDULE001", "같은 공연장의 다른 회차와 시간이 겹칩니다");
      const schedule = { scheduleId: state().nextScheduleId++, startAt, status: "ON_SALE" as const, seatsGeneratedAt: null };
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
      const found = findSchedule(sid);
      if (!found) return fail(404, "SCHEDULE000", "회차를 찾을 수 없습니다");
      const { perf, schedule } = found;
      if (schedule.seatsGeneratedAt) return fail(409, "SCHEDULE002", "이미 판매 좌석을 만들었습니다");
      if (!perf.saleOpenAt || perf.bookingFee == null) {
        return fail(400, "PERFORMANCE009", "판매 정보를 먼저 입력해 주세요");
      }
      if (perf.zones.some((z) => z.assignmentStatus === "UNSET")) {
        return fail(400, "PERFORMANCE011", "등급을 정하지 않은 구역이 있습니다");
      }
      schedule.seatsGeneratedAt = isoLocal(new Date());
      const blocked = perf.zones
        .filter((z) => z.assignmentStatus === "INTENTIONALLY_UNSOLD")
        .reduce((sum, z) => sum + z.seatCount, 0);
      return ok({
        scheduleId: schedule.scheduleId,
        seatsGeneratedAt: schedule.seatsGeneratedAt,
        totalSeats: totalSeats(),
        availableSeats: totalSeats() - blocked,
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
            totalSeats: totalSeats(), soldSeats: null, heldSeats: null, availableSeats: null,
            reservedSeats: null, blockedSeats: null,
          };
        }
        const sold = 30 + ((x.scheduleId * 7) % 50);
        const held = 4;
        return {
          scheduleId: x.scheduleId, startAt: x.startAt, status: x.status, seatsGenerated: true,
          totalSeats: totalSeats(), soldSeats: sold, heldSeats: held,
          availableSeats: totalSeats() - sold - held - blocked, reservedSeats: 0, blockedSeats: blocked,
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
      const exhaustedOnly = (ctx.search.get("exhaustedOnly") ?? "true") === "true";
      const items = state().refunds
        .filter((r) => r.status === status && (!exhaustedOnly || r.retryCount >= 3))
        .sort((a, b) => b.refundId - a.refundId);
      return ok(pageOf(items, ctx.search));
    }],
    ["POST", "/api/v1/admin/refunds/:id/retry", (ctx, [id]) => {
      const s = admin(ctx);
      if (s instanceof Response) return s;
      const refund = state().refunds.find((r) => r.refundId === Number(id));
      if (!refund) return fail(404, "REFUND000", "환불을 찾을 수 없습니다");
      if (refund.status !== "FAILED") return fail(409, "REFUND001", "실패한 환불만 다시 처리할 수 있습니다");
      refund.status = "DONE";
      refund.failReason = null;
      return ok({ refundId: refund.refundId, status: refund.status });
    }],
  ];

  return routes;
}
