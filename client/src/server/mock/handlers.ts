import type {
  AccountType,
  BookingDetail,
  BookingStatus,
  BookingSummary,
  CancelRule,
  Genre,
  PerformanceDetail,
  PerformanceSummary,
  Seat,
  SeatStatus,
  Zone,
} from "@/lib/types";
import { consoleRoutes } from "@/server/mock/console";
import { fetchSpringPerformance } from "@/server/spring";
import {
  DEFAULT_CANCEL_POLICY,
  MOCK_PERFORMANCES,
  MOCK_TERMS,
  findMockPerformance,
  isoLocal,
  saleStatusOfMock,
} from "@/server/mock/data";

// 목 응답 처리기. 경로에 맞는 처리기가 없으면 null 을 돌려주고, 프록시가 원래 응답을 쓴다.

export type MockContext = {
  method: string;
  pathname: string;
  search: URLSearchParams;
  cookies: Map<string, string>;
  json: () => Promise<unknown>;
  // 예매 화면이 어느 공연의 회차인지 알려 주는 값 (Spring API 에는 영향 없는 보조 헤더)
  performanceId: number | null;
};

const MINUTE = 60_000;
const DAY = 24 * 60 * MINUTE;
const HOLD_DURATION = 7 * MINUTE;

const MOCK_SEAT_BASE = 9_000_000_000_000;
const ROWS = 4;
const COLS = 8;

export function isMockSeatId(seatId: number): boolean {
  return seatId >= MOCK_SEAT_BASE;
}

// ---------- 응답 도우미 ----------

const MOCK_HEADERS = { "x-mock": "1" };

function ok(data: unknown, status = 200, extra: HeadersInit = {}): Response {
  return Response.json(
    { success: true, code: "0000", message: "", data },
    { status, headers: { ...MOCK_HEADERS, ...extra } },
  );
}

function fail(status: number, code: string, message: string): Response {
  return Response.json(
    { success: false, code, message, data: null },
    { status, headers: MOCK_HEADERS },
  );
}

function pageOf<T>(items: T[], search: URLSearchParams, defaultSize = 20, maxSize = 20) {
  const page = Math.max(0, Number(search.get("page")) || 0);
  const requested = Number(search.get("size")) || defaultSize;
  const size = Math.min(Math.max(1, requested), maxSize);
  return {
    content: items.slice(page * size, page * size + size),
    page,
    size,
    totalElements: items.length,
    totalPages: Math.max(1, Math.ceil(items.length / size)),
  };
}

// ---------- 상태 (개발 서버가 다시 컴파일돼도 유지되게 globalThis 에 둔다) ----------

type SeatRecord = { status: SeatStatus; holdUntil?: number; bookingId?: number };

type MockBookingSeat = {
  bookingSeatId: number;
  seatId: number;
  zoneName: string;
  rowLabel: string;
  seatNumber: string;
  gradeName: string;
  price: number;
  bookingFee: number;
  active: boolean;
  canceledAt: string | null;
  cancelFee: number | null;
};

type MockBooking = {
  bookingId: number;
  bookingNo: string;
  ownerKey: string;
  ownerEmail: string;
  performance: { performanceId: number; title: string; posterUrl: string | null };
  scheduleId: number;
  startAt: string;
  allowedReceive: "ONSITE" | "DELIVERY" | "BOTH";
  maxPerPerson: number;
  seats: MockBookingSeat[];
  status: BookingStatus;
  receiveType: "ONSITE" | "DELIVERY";
  delivery: BookingDetail["delivery"];
  expiresAtMs: number;
  createdAt: string;
  canceledAt: string | null;
  payment: BookingDetail["payment"];
  paymentOrderId: string | null;
  refundedAmount: number;
  cancelPolicy: CancelRule[];
};


type MockState = {
  seats: Map<number, Map<number, SeatRecord>>;
  bookings: MockBooking[];
  nextBookingId: number;
  nextBookingSeatId: number;
  nextPaymentId: number;
  nextRefundId: number;
};

const globalState = globalThis as unknown as { __ticktingMock?: MockState };

function state(): MockState {
  if (!globalState.__ticktingMock) {
    globalState.__ticktingMock = {
      seats: new Map(),
      bookings: [],
      nextBookingId: 1001,
      nextBookingSeatId: 5001,
      nextPaymentId: 801,
      nextRefundId: 301,
    };
  }
  return globalState.__ticktingMock;
}

// ---------- 인증 ----------

type MockSession = { type: AccountType; email: string; key: string };

const ACCOUNT_BY_PATH: Record<string, AccountType> = {
  users: "USER",
  sellers: "SELLER",
  admins: "ADMIN",
};

function sessionOf(ctx: MockContext): MockSession | null {
  const mock = ctx.cookies.get("MOCK_SESSION");
  if (mock) {
    const [type, email] = decodeURIComponent(mock).split("|");
    if (type && email) return { type: type as AccountType, email, key: `mock:${mock}` };
  }
  // 실제 Spring 에서 로그인했지만 아직 목으로 답하는 API 를 부르는 경우.
  const real = ctx.cookies.get("SESSION");
  if (real) return { type: "USER", email: "user@tickting.example", key: `real:${real}` };
  return null;
}

function requireSession(ctx: MockContext, type: AccountType): MockSession | Response {
  const session = sessionOf(ctx);
  if (!session) return fail(401, "AUTH200", "로그인이 필요합니다.");
  const fromRealSession = session.key.startsWith("real:");
  if (!fromRealSession && session.type !== type) return fail(403, "AUTH100", "권한이 없습니다.");
  return session;
}

function nameOf(email: string): string {
  const local = email.split("@")[0] ?? "회원";
  return local.length > 0 ? local : "회원";
}

// ---------- 공연 ----------

async function resolvePerformance(
  performanceId: number | null,
  scheduleId?: number,
): Promise<PerformanceDetail> {
  const id = performanceId ?? (scheduleId ? Math.floor(scheduleId / 10) : null);
  if (id !== null) {
    const real = await fetchSpringPerformance(id);
    if (real) return real;
    const mock = findMockPerformance(id);
    if (mock) return mock;
  }
  return MOCK_PERFORMANCES[0];
}

function summaryOf(p: PerformanceDetail): PerformanceSummary {
  const starts = p.schedules.map((s) => s.startAt).sort();
  return {
    performanceId: p.performanceId,
    title: p.title,
    genre: p.genre,
    posterUrl: p.posterUrl,
    ageLimit: p.ageLimit,
    venueName: p.venue?.name ?? null,
    region: p.venue?.region ?? null,
    firstStartAt: starts[0] ?? null,
    lastStartAt: starts[starts.length - 1] ?? null,
    saleOpenAt: p.sale.saleOpenAt,
    saleStatus: saleStatusOfMock(p.performanceId),
  };
}

const GENRES: Genre[] = ["CONCERT", "MUSICAL", "PLAY", "CLASSIC", "ETC"];

function listPerformances(ctx: MockContext): Response {
  const genre = ctx.search.get("genre");
  if (genre && !GENRES.includes(genre as Genre)) {
    return fail(400, "COMMON000", "genre: 알 수 없는 장르입니다.");
  }
  const region = ctx.search.get("region")?.trim();
  const keyword = ctx.search.get("keyword")?.trim().toLowerCase();

  const items = MOCK_PERFORMANCES.filter(
    (p) =>
      (!genre || p.genre === genre) &&
      (!region || p.venue?.region === region) &&
      (!keyword ||
        p.title.toLowerCase().includes(keyword) ||
        (p.castInfo ?? "").toLowerCase().includes(keyword)),
  )
    .sort((a, b) => b.performanceId - a.performanceId)
    .map(summaryOf);

  return ok(pageOf(items, ctx.search));
}

function performanceDetail(performanceId: number): Response {
  const found = findMockPerformance(performanceId);
  if (!found) return fail(404, "PERFORMANCE000", "공연을 찾을 수 없습니다.");
  return ok(found);
}

// ---------- 좌석 ----------

const ZONE_DEFS = [
  { zoneId: 1, name: "A구역", floor: "1층", gradeIdx: 0 },
  { zoneId: 2, name: "B구역", floor: "1층", gradeIdx: 1 },
  { zoneId: 3, name: "C구역", floor: "1층", gradeIdx: 1 },
  { zoneId: 4, name: "D구역", floor: "2층", gradeIdx: 2 },
  { zoneId: 5, name: "E구역", floor: "2층", gradeIdx: 2 },
];

function seatIdOf(scheduleId: number, zoneId: number, index: number): number {
  return MOCK_SEAT_BASE + scheduleId * 1000 + zoneId * 100 + index;
}

function decodeSeatId(seatId: number) {
  const rest = seatId - MOCK_SEAT_BASE;
  return {
    scheduleId: Math.floor(rest / 1000),
    zoneId: Math.floor((rest % 1000) / 100),
    index: rest % 100,
  };
}

function seatLabel(index: number) {
  return { row: `${Math.floor(index / COLS) + 1}열`, number: String((index % COLS) + 1) };
}

function seatMap(scheduleId: number): Map<number, SeatRecord> {
  const s = state();
  let map = s.seats.get(scheduleId);
  if (!map) {
    map = new Map();
    for (const zone of ZONE_DEFS) {
      for (let i = 0; i < ROWS * COLS; i += 1) {
        const id = seatIdOf(scheduleId, zone.zoneId, i);
        // 일부는 이미 팔린 좌석으로 시작해서 화면이 비어 보이지 않게 한다.
        const sold = ((id % 9973) * 7919) % 100 < 22;
        map.set(id, { status: sold ? "SOLD" : "AVAILABLE" });
      }
    }
    s.seats.set(scheduleId, map);
  }
  return map;
}

function gradeForZone(performance: PerformanceDetail, gradeIdx: number) {
  const grades = [...performance.grades].sort((a, b) => b.price - a.price);
  return grades[Math.min(gradeIdx, grades.length - 1)];
}

// 만료된 결제 대기 예매를 정리하고 좌석을 풀어 준다. (실서비스의 스케줄러 역할)
function sweep() {
  const s = state();
  const now = Date.now();
  for (const booking of s.bookings) {
    if (booking.status === "PENDING" && booking.expiresAtMs <= now) {
      booking.status = "EXPIRED";
      releaseSeats(booking);
    }
  }
}

function releaseSeats(booking: MockBooking) {
  const map = state().seats.get(booking.scheduleId);
  if (!map) return;
  for (const seat of booking.seats) {
    const record = map.get(seat.seatId);
    if (record && record.bookingId === booking.bookingId) map.set(seat.seatId, { status: "AVAILABLE" });
  }
}

async function zoneSummary(ctx: MockContext, scheduleId: number): Promise<Response> {
  sweep();
  const performance = await resolvePerformance(ctx.performanceId, scheduleId);
  const map = seatMap(scheduleId);
  const zones: Zone[] = ZONE_DEFS.map((def) => {
    const grade = gradeForZone(performance, def.gradeIdx);
    let remaining = 0;
    for (let i = 0; i < ROWS * COLS; i += 1) {
      if (map.get(seatIdOf(scheduleId, def.zoneId, i))?.status === "AVAILABLE") remaining += 1;
    }
    return {
      zoneId: def.zoneId,
      name: def.name,
      floor: def.floor,
      grade: grade?.name ?? null,
      price: grade?.price ?? null,
      remaining,
      total: ROWS * COLS,
    };
  });
  return ok({ scheduleId, layoutKey: "mock-hall", zones });
}

function zoneSeats(scheduleId: number, zoneId: number): Response {
  sweep();
  const def = ZONE_DEFS.find((z) => z.zoneId === zoneId);
  if (!def) return fail(404, "COMMON002", "구역을 찾을 수 없습니다.");
  const map = seatMap(scheduleId);
  const seats: Seat[] = [];
  for (let i = 0; i < ROWS * COLS; i += 1) {
    const id = seatIdOf(scheduleId, zoneId, i);
    const { row, number } = seatLabel(i);
    seats.push({ seatId: id, row, number, sortOrder: i + 1, status: map.get(id)?.status ?? "AVAILABLE" });
  }
  return ok({ zoneId, name: def.name, seats });
}

// ---------- 예매 ----------

function bookingNoOf(): string {
  const d = new Date();
  const pad = (n: number) => String(n).padStart(2, "0");
  const random = Math.random().toString(36).slice(2, 9).toUpperCase().padEnd(7, "X");
  return `${d.getFullYear()}${pad(d.getMonth() + 1)}${pad(d.getDate())}${random}`;
}

function activeSeats(booking: MockBooking) {
  return booking.seats.filter((seat) => seat.active);
}

function totals(booking: MockBooking) {
  const ticketAmount = booking.seats.reduce((sum, seat) => sum + seat.price, 0);
  const bookingFeeAmount = booking.seats.reduce((sum, seat) => sum + seat.bookingFee, 0);
  return { ticketAmount, bookingFeeAmount, totalAmount: ticketAmount + bookingFeeAmount };
}

function seatView(seat: MockBookingSeat) {
  return {
    bookingSeatId: seat.bookingSeatId,
    zoneName: seat.zoneName,
    rowLabel: seat.rowLabel,
    seatNumber: seat.seatNumber,
    gradeName: seat.gradeName,
    price: seat.price,
    bookingFee: seat.bookingFee,
  };
}

function ruleFor(booking: MockBooking): CancelRule | undefined {
  const days = Math.floor((new Date(booking.startAt).getTime() - Date.now()) / DAY);
  return booking.cancelPolicy.find(
    (rule) => days >= rule.daysFrom && (rule.daysTo === null || days <= rule.daysTo),
  );
}

function detailOf(booking: MockBooking): BookingDetail {
  const rule = ruleFor(booking);
  const paid = booking.status === "PAID" || booking.status === "PARTIAL_CANCELED";
  return {
    bookingId: booking.bookingId,
    bookingNo: booking.bookingNo,
    status: booking.status,
    performance: booking.performance,
    schedule: { scheduleId: booking.scheduleId, startAt: booking.startAt, status: "ON_SALE" },
    receiveType: booking.receiveType,
    orderer: { name: nameOf(booking.ownerEmail), phone: "01012345678", email: booking.ownerEmail },
    delivery: booking.delivery,
    seats: booking.seats.map((seat) => ({
      ...seatView(seat),
      active: seat.active,
      canceledAt: seat.canceledAt,
      cancelFee: seat.cancelFee,
    })),
    ...totals(booking),
    refundedAmount: booking.refundedAmount,
    payment: booking.payment,
    cancelPolicy: booking.cancelPolicy,
    cancelable: paid && !!rule?.cancelable && activeSeats(booking).length > 0,
    currentFeeRate: rule?.feeRate ?? 0,
    expiresAt: booking.status === "PENDING" ? isoLocal(new Date(booking.expiresAtMs)) : null,
    createdAt: booking.createdAt,
    canceledAt: booking.canceledAt,
  };
}

function ownBooking(ctx: MockContext, session: MockSession, idText: string): MockBooking | null {
  sweep();
  const id = Number(idText);
  return state().bookings.find((b) => b.bookingId === id && b.ownerKey === session.key) ?? null;
}

async function createBooking(ctx: MockContext, scheduleId: number): Promise<Response> {
  const session = requireSession(ctx, "USER");
  if (session instanceof Response) return session;

  const body = (await ctx.json().catch(() => null)) as { seatIds?: unknown } | null;
  const seatIds = Array.isArray(body?.seatIds) ? (body.seatIds as unknown[]) : [];
  if (seatIds.length === 0 || seatIds.some((id) => typeof id !== "number")) {
    return fail(400, "COMMON000", "seatIds: 좌석을 한 개 이상 선택해 주세요.");
  }
  if (new Set(seatIds).size !== seatIds.length) {
    return fail(400, "COMMON000", "seatIds: 중복된 좌석이 있습니다.");
  }
  const ids = seatIds as number[];

  sweep();
  const performance = await resolvePerformance(ctx.performanceId, scheduleId);
  const schedule = performance.schedules.find((s) => s.scheduleId === scheduleId);
  if (schedule && (schedule.saleStatus === "UPCOMING" || schedule.saleStatus === "ENDED")) {
    return fail(400, "BOOKING002", "판매 중인 회차가 아닙니다.");
  }

  const map = seatMap(scheduleId);
  for (const id of ids) {
    if (!isMockSeatId(id) || decodeSeatId(id).scheduleId !== scheduleId || !map.has(id)) {
      return fail(404, "BOOKING005", "존재하지 않는 좌석입니다.");
    }
  }
  if (ids.some((id) => map.get(id)?.status !== "AVAILABLE")) {
    return fail(409, "BOOKING000", "이미 선택되었거나 판매된 좌석이 있습니다.");
  }

  const s = state();
  const already = s.bookings
    .filter(
      (b) =>
        b.ownerKey === session.key &&
        b.performance.performanceId === performance.performanceId &&
        (b.status === "PENDING" || b.status === "PAID" || b.status === "PARTIAL_CANCELED"),
    )
    .reduce((sum, b) => sum + activeSeats(b).length, 0);
  if (already + ids.length > performance.sale.maxPerPerson) {
    return fail(409, "BOOKING001", `1인 최대 ${performance.sale.maxPerPerson}매까지 예매할 수 있습니다.`);
  }

  const bookingId = s.nextBookingId++;
  const expiresAtMs = Date.now() + HOLD_DURATION;
  const seats: MockBookingSeat[] = ids.map((seatId) => {
    const { zoneId, index } = decodeSeatId(seatId);
    const def = ZONE_DEFS.find((z) => z.zoneId === zoneId)!;
    const grade = gradeForZone(performance, def.gradeIdx);
    const { row, number } = seatLabel(index);
    map.set(seatId, { status: "HELD", holdUntil: expiresAtMs, bookingId });
    return {
      bookingSeatId: s.nextBookingSeatId++,
      seatId,
      zoneName: def.name,
      rowLabel: row,
      seatNumber: number,
      gradeName: grade?.name ?? "일반",
      price: grade?.price ?? 0,
      bookingFee: performance.sale.bookingFee,
      active: true,
      canceledAt: null,
      cancelFee: null,
    };
  });

  const booking: MockBooking = {
    bookingId,
    bookingNo: bookingNoOf(),
    ownerKey: session.key,
    ownerEmail: session.email,
    performance: {
      performanceId: performance.performanceId,
      title: performance.title,
      posterUrl: performance.posterUrl,
    },
    scheduleId,
    startAt: schedule?.startAt ?? isoLocal(new Date(Date.now() + 14 * DAY)),
    allowedReceive: performance.sale.receiveType,
    maxPerPerson: performance.sale.maxPerPerson,
    seats,
    status: "PENDING",
    receiveType: performance.sale.receiveType === "DELIVERY" ? "DELIVERY" : "ONSITE",
    delivery: null,
    expiresAtMs,
    createdAt: isoLocal(new Date()),
    canceledAt: null,
    payment: null,
    paymentOrderId: null,
    refundedAmount: 0,
    cancelPolicy: performance.cancelPolicy.length > 0 ? performance.cancelPolicy : DEFAULT_CANCEL_POLICY,
  };
  s.bookings.push(booking);

  return ok(
    {
      bookingId,
      bookingNo: booking.bookingNo,
      status: booking.status,
      expiresAt: isoLocal(new Date(expiresAtMs)),
      seats: seats.map(seatView),
      ...totals(booking),
    },
    201,
  );
}

function listBookings(ctx: MockContext): Response {
  const session = requireSession(ctx, "USER");
  if (session instanceof Response) return session;
  sweep();
  const status = ctx.search.get("status");
  // 시간이 지난(EXPIRED) 예매는 내역에 나오지 않고, 상태로 요청하면 400 이다. (api-spec BKG-04)
  if (status === "EXPIRED") return fail(400, "COMMON000", "요청 값이 올바르지 않습니다.");
  const items: BookingSummary[] = state()
    .bookings.filter((b) => b.ownerKey === session.key && b.status !== "EXPIRED" && (!status || b.status === status))
    .sort((a, b) => b.bookingId - a.bookingId)
    .map((b) => ({
      bookingId: b.bookingId,
      bookingNo: b.bookingNo,
      status: b.status,
      performanceTitle: b.performance.title,
      posterUrl: b.performance.posterUrl,
      scheduleStartAt: b.startAt,
      seatCount: b.seats.length,
      totalAmount: totals(b).totalAmount,
      createdAt: b.createdAt,
    }));
  return ok(pageOf(items, ctx.search));
}

function bookingDetail(ctx: MockContext, idText: string): Response {
  const session = requireSession(ctx, "USER");
  if (session instanceof Response) return session;
  const booking = ownBooking(ctx, session, idText);
  if (!booking) return fail(404, "BOOKING006", "예매를 찾을 수 없습니다.");
  return ok(detailOf(booking));
}

async function preparePayment(ctx: MockContext, idText: string): Promise<Response> {
  const session = requireSession(ctx, "USER");
  if (session instanceof Response) return session;
  const booking = ownBooking(ctx, session, idText);
  if (!booking) return fail(404, "BOOKING006", "예매를 찾을 수 없습니다.");
  if (booking.status !== "PENDING") return fail(409, "BOOKING007", "결제할 수 없는 예매입니다.");

  const body = (await ctx.json().catch(() => null)) as {
    receiveType?: string;
    recipientName?: string;
    recipientPhone?: string;
    recipientAddress?: string;
  } | null;
  const receiveType = body?.receiveType === "DELIVERY" ? "DELIVERY" : "ONSITE";
  if (booking.allowedReceive !== "BOTH" && booking.allowedReceive !== receiveType) {
    return fail(400, "BOOKING008", "허용되지 않는 수령 방법입니다.");
  }
  if (receiveType === "DELIVERY") {
    if (!body?.recipientName?.trim() || !body.recipientPhone?.trim() || !body.recipientAddress?.trim()) {
      return fail(400, "BOOKING009", "배송 정보를 모두 입력해 주세요.");
    }
    booking.delivery = {
      recipientName: body.recipientName.trim(),
      recipientPhone: body.recipientPhone.trim(),
      recipientAddress: body.recipientAddress.trim(),
      carrier: null,
      trackingNo: null,
    };
  } else {
    booking.delivery = null;
  }
  booking.receiveType = receiveType;

  const s = state();
  const paymentId = s.nextPaymentId++;
  booking.paymentOrderId = `BK${booking.bookingNo}-${String(paymentId).padStart(2, "0")}`;
  const { totalAmount } = totals(booking);
  booking.payment = {
    paymentId,
    method: "",
    amount: totalAmount,
    status: "READY",
    approvedAt: "",
  };

  return ok({
    paymentId,
    orderId: booking.paymentOrderId,
    orderName: `${booking.performance.title} ${booking.seats.length}매`,
    amount: totalAmount,
    status: "READY",
    expiresAt: isoLocal(new Date(booking.expiresAtMs)),
  });
}

async function confirmPayment(ctx: MockContext): Promise<Response> {
  const session = requireSession(ctx, "USER");
  if (session instanceof Response) return session;
  sweep();
  const body = (await ctx.json().catch(() => null)) as {
    paymentKey?: string;
    orderId?: string;
    amount?: number;
  } | null;
  if (!body?.paymentKey || !body.orderId || typeof body.amount !== "number") {
    return fail(400, "COMMON000", "paymentKey, orderId, amount: 필수 값입니다.");
  }
  const booking = state().bookings.find(
    (b) => b.paymentOrderId === body.orderId && b.ownerKey === session.key,
  );
  if (!booking || !booking.payment) return fail(404, "PAYMENT000", "결제 정보를 찾을 수 없습니다.");
  if (booking.status === "EXPIRED") return fail(409, "PAYMENT001", "좌석 점유 시간이 만료되었습니다.");
  if (booking.status !== "PENDING") return fail(409, "PAYMENT002", "이미 처리된 결제입니다.");
  if (body.amount !== booking.payment.amount) {
    return fail(400, "PAYMENT003", "결제 금액이 일치하지 않습니다.");
  }

  const approvedAt = isoLocal(new Date());
  booking.status = "PAID";
  booking.payment = { ...booking.payment, method: "카드", status: "DONE", approvedAt };
  const map = state().seats.get(booking.scheduleId);
  for (const seat of booking.seats) map?.set(seat.seatId, { status: "SOLD", bookingId: booking.bookingId });

  return ok({
    bookingId: booking.bookingId,
    bookingNo: booking.bookingNo,
    bookingStatus: booking.status,
    paymentId: booking.payment.paymentId,
    paymentStatus: "DONE",
    method: "카드",
    amount: booking.payment.amount,
    approvedAt,
  });
}

async function cancelBooking(ctx: MockContext, idText: string): Promise<Response> {
  const session = requireSession(ctx, "USER");
  if (session instanceof Response) return session;
  const booking = ownBooking(ctx, session, idText);
  if (!booking) return fail(404, "BOOKING006", "예매를 찾을 수 없습니다.");
  if (booking.status !== "PAID" && booking.status !== "PARTIAL_CANCELED") {
    return fail(409, "BOOKING010", "취소할 수 없는 예매입니다.");
  }
  const rule = ruleFor(booking);
  if (!rule?.cancelable) return fail(400, "BOOKING011", "지금은 취소할 수 없습니다. (공연 당일 취소 불가)");

  const body = (await ctx.json().catch(() => null)) as { bookingSeatIds?: number[] } | null;
  const requested = body?.bookingSeatIds;
  const targets = requested
    ? booking.seats.filter((seat) => requested.includes(seat.bookingSeatId))
    : activeSeats(booking);
  if (
    targets.length === 0 ||
    (requested && targets.length !== requested.length) ||
    targets.some((seat) => !seat.active)
  ) {
    return fail(400, "BOOKING012", "이미 취소되었거나 존재하지 않는 좌석입니다.");
  }

  const map = state().seats.get(booking.scheduleId);
  const canceledAt = isoLocal(new Date());
  let cancelFeeTotal = 0;
  let refund = 0;
  for (const seat of targets) {
    const fee = Math.round((seat.price * rule.feeRate) / 100);
    seat.active = false;
    seat.canceledAt = canceledAt;
    seat.cancelFee = fee;
    cancelFeeTotal += fee;
    refund += seat.price - fee + seat.bookingFee;
    map?.set(seat.seatId, { status: "AVAILABLE" });
  }
  booking.refundedAmount += refund;
  const allCanceled = activeSeats(booking).length === 0;
  booking.status = allCanceled ? "CANCELED" : "PARTIAL_CANCELED";
  if (allCanceled) booking.canceledAt = canceledAt;

  return ok({
    bookingId: booking.bookingId,
    bookingStatus: booking.status,
    canceledSeatIds: targets.map((seat) => seat.bookingSeatId),
    cancelFeeTotal,
    refund: { refundId: state().nextRefundId++, amount: refund, status: "DONE" },
  });
}

// ---------- 인증 ----------

function mockCookie(type: AccountType, email: string): string {
  const value = encodeURIComponent(`${type}|${email}`);
  return `MOCK_SESSION=${value}; Path=/; HttpOnly; SameSite=Lax`;
}

async function login(ctx: MockContext, who: string): Promise<Response> {
  const type = ACCOUNT_BY_PATH[who];
  const body = (await ctx.json().catch(() => null)) as { email?: string; password?: string } | null;
  if (!body?.email?.includes("@") || !body.password) {
    return fail(400, "COMMON000", "email, password: 이메일과 비밀번호를 입력해 주세요.");
  }
  return ok({ accountType: type, role: type === "SELLER" ? "SELLER_OWNER" : null }, 200, {
    "Set-Cookie": mockCookie(type, body.email.trim()),
  });
}

async function signup(ctx: MockContext): Promise<Response> {
  const body = (await ctx.json().catch(() => null)) as {
    email?: string;
    password?: string;
    nickname?: string;
    agreedTermIds?: number[];
  } | null;
  const problems: string[] = [];
  if (!body?.email?.includes("@")) problems.push("email: 이메일 형식이 올바르지 않습니다");
  if (!body?.password || body.password.length < 8 || body.password.length > 25) {
    problems.push("password: 8~25자로 입력해 주세요");
  }
  if (!body?.nickname || body.nickname.length < 2 || body.nickname.length > 20) {
    problems.push("nickname: 2~20자로 입력해 주세요");
  }
  if (problems.length > 0) return fail(400, "COMMON000", problems.join(", "));
  const required = MOCK_TERMS.filter((t) => t.required).map((t) => t.termId);
  if (!required.every((id) => body?.agreedTermIds?.includes(id))) {
    return fail(400, "TERMS000", "필수 약관에 동의해 주세요.");
  }
  return ok({ accountType: "USER", role: null }, 201, {
    "Set-Cookie": mockCookie("USER", body!.email!.trim()),
  });
}

function logout(): Response {
  return ok(null, 200, { "Set-Cookie": "MOCK_SESSION=; Path=/; Max-Age=0; HttpOnly; SameSite=Lax" });
}

// 내 정보에서 고친 닉네임·프로필 사진. 개발 서버가 다시 컴파일돼도 유지되게 globalThis 에 둔다.
const profileGlobal = globalThis as unknown as { __ticktingProfile?: Map<string, { nickname: string; profileImg: string | null }> };

function profiles() {
  profileGlobal.__ticktingProfile ??= new Map();
  return profileGlobal.__ticktingProfile;
}

function me(ctx: MockContext): Response {
  const session = requireSession(ctx, "USER");
  if (session instanceof Response) return session;
  const profile = profiles().get(session.key);
  return ok({
    userId: 1,
    email: session.email,
    name: nameOf(session.email),
    nickname: profile?.nickname ?? nameOf(session.email),
    phone: "01012345678",
    profileImg: profile?.profileImg ?? null,
    birthDate: "1990-01-01",
    createdAt: isoLocal(new Date(Date.now() - 30 * DAY)),
  });
}

async function updateMe(ctx: MockContext): Promise<Response> {
  const session = requireSession(ctx, "USER");
  if (session instanceof Response) return session;
  const body = (await ctx.json().catch(() => ({}))) as { nickname?: string; profileImg?: string | null };
  const nickname = body.nickname?.trim() ?? "";
  if (nickname.length < 2 || nickname.length > 20) {
    return fail(400, "COMMON000", "닉네임은 2글자 이상 20글자 이하로 입력해 주세요.");
  }
  // 생략하거나 null 이면 사진이 지워진다. (api-spec AUTH-07)
  profiles().set(session.key, { nickname, profileImg: body.profileImg ?? null });
  return ok({ userId: 1, nickname, profileImg: body.profileImg ?? null, updatedAt: isoLocal(new Date()) });
}

function withdraw(ctx: MockContext): Response {
  const session = requireSession(ctx, "USER");
  if (session instanceof Response) return session;
  sweep();
  // 결제 대기나 공연 전 결제 완료 예매가 있으면 탈퇴할 수 없다. (api-spec AUTH-08)
  const active = state().bookings.some(
    (b) => b.ownerKey === session.key && (b.status === "PENDING" || b.status === "PAID" || b.status === "PARTIAL_CANCELED"),
  );
  if (active) return fail(409, "USER003", "진행 중인 예매가 있어 탈퇴할 수 없습니다. 예매를 취소하거나 공연이 끝난 뒤에 탈퇴해 주세요.");
  profiles().delete(session.key);
  return logout();
}

// ---------- 대기열 ----------

// 대기 화면을 확인할 수 있게, 처음 한 번은 줄을 서게 하고 상태를 세 번 확인하면 입장시킨다.
const QUEUE_WAIT_POLLS = 3;
const queueGlobal = globalThis as unknown as { __ticktingQueue?: Map<string, number> };

function queues(): Map<string, number> {
  queueGlobal.__ticktingQueue ??= new Map();
  return queueGlobal.__ticktingQueue;
}

function queueStatus(key: string): Response {
  const polls = queues().get(key);
  if (polls === undefined) return ok({ status: "NONE", position: null, waitingCount: null, expiresAt: null });
  if (polls >= QUEUE_WAIT_POLLS) {
    return ok({ status: "ADMITTED", position: null, waitingCount: null, expiresAt: isoLocal(new Date(Date.now() + 10 * MINUTE)) });
  }
  return ok({ status: "WAITING", position: QUEUE_WAIT_POLLS - polls + 1, waitingCount: 40 - polls * 10, expiresAt: null });
}

function queueEnter(ctx: MockContext, scheduleId: string): Response {
  const session = requireSession(ctx, "USER");
  if (session instanceof Response) return session;
  const key = `${session.key}:${scheduleId}`;
  if (!queues().has(key)) queues().set(key, 0);
  return queueStatus(key);
}

function queuePoll(ctx: MockContext, scheduleId: string): Response {
  const session = requireSession(ctx, "USER");
  if (session instanceof Response) return session;
  const key = `${session.key}:${scheduleId}`;
  const polls = queues().get(key);
  if (polls !== undefined && polls < QUEUE_WAIT_POLLS) queues().set(key, polls + 1);
  return queueStatus(key);
}

function queueLeave(ctx: MockContext, scheduleId: string): Response {
  const session = requireSession(ctx, "USER");
  if (session instanceof Response) return session;
  queues().delete(`${session.key}:${scheduleId}`);
  return ok(null);
}

// ---------- 이미지 업로드 ----------

// 실제 서버는 파일을 S3 에 올리고 주소를 준다. 목은 파일을 읽지 않고 단색 이미지 주소를 돌려준다.
function uploadImage(ctx: MockContext): Response {
  const session = sessionOf(ctx);
  if (!session) return fail(401, "AUTH200", "로그인이 필요합니다.");
  const hue = Math.floor(Math.random() * 360);
  const svg = `<svg xmlns="http://www.w3.org/2000/svg" width="300" height="400"><rect width="300" height="400" fill="hsl(${hue} 45% 42%)"/></svg>`;
  return ok({ imageUrl: `data:image/svg+xml;utf8,${encodeURIComponent(svg)}` });
}

// ---------- 라우터 ----------

export type Handler = (ctx: MockContext, params: string[]) => Response | Promise<Response>;

const ROUTES: [string, string, Handler][] = [
  ["GET", "/api/v1/terms", (ctx) => {
    const type = ctx.search.get("type");
    return ok(MOCK_TERMS.filter((t) => !type || t.type === type));
  }],
  ["POST", "/api/v2/auth/:who/login", (ctx, [who]) =>
    who in ACCOUNT_BY_PATH ? login(ctx, who) : fail(404, "COMMON002", "존재하지 않는 경로입니다.")],
  ["POST", "/api/v2/auth/users/signup", (ctx) => signup(ctx)],
  ["POST", "/api/v2/auth/logout", () => logout()],
  ["GET", "/api/v1/users/me", (ctx) => me(ctx)],
  ["PATCH", "/api/v1/users/me", (ctx) => updateMe(ctx)],
  ["DELETE", "/api/v1/users/me", (ctx) => withdraw(ctx)],

  ["GET", "/api/v1/performances", (ctx) => listPerformances(ctx)],
  ["GET", "/api/v1/performances/:id", (_ctx, [id]) => performanceDetail(Number(id))],
  ["POST", "/api/v1/images", (ctx) => uploadImage(ctx)],
  ["POST", "/api/v1/schedules/:scheduleId/queue", (ctx, [id]) => queueEnter(ctx, id)],
  ["GET", "/api/v1/schedules/:scheduleId/queue", (ctx, [id]) => queuePoll(ctx, id)],
  ["DELETE", "/api/v1/schedules/:scheduleId/queue", (ctx, [id]) => queueLeave(ctx, id)],
  ["GET", "/api/v1/schedules/:scheduleId/zones", (ctx, [id]) => zoneSummary(ctx, Number(id))],
  ["GET", "/api/v1/schedules/:scheduleId/zones/:zoneId/seats", (_ctx, [id, zoneId]) =>
    zoneSeats(Number(id), Number(zoneId))],

  ["POST", "/api/v1/schedules/:scheduleId/bookings", (ctx, [id]) => createBooking(ctx, Number(id))],
  ["GET", "/api/v1/bookings", (ctx) => listBookings(ctx)],
  ["GET", "/api/v1/bookings/:id", (ctx, [id]) => bookingDetail(ctx, id)],
  ["POST", "/api/v1/bookings/:id/payments", (ctx, [id]) => preparePayment(ctx, id)],
  ["POST", "/api/v1/bookings/:id/cancel", (ctx, [id]) => cancelBooking(ctx, id)],
  ["POST", "/api/v1/payments/confirm", (ctx) => confirmPayment(ctx)],

  // 판매자·관리자 화면용 목 API (console.ts)
  ...consoleRoutes({ ok, fail, pageOf, requireSession }),
];

function match(pattern: string, pathname: string): string[] | null {
  const want = pattern.split("/");
  const have = pathname.split("/");
  if (want.length !== have.length) return null;
  const params: string[] = [];
  for (let i = 0; i < want.length; i += 1) {
    if (want[i].startsWith(":")) params.push(decodeURIComponent(have[i]));
    else if (want[i] !== have[i]) return null;
  }
  return params;
}

export function hasMock(method: string, pathname: string): boolean {
  return ROUTES.some(([m, pattern]) => m === method && match(pattern, pathname) !== null);
}

export async function handleMock(ctx: MockContext): Promise<Response | null> {
  for (const [method, pattern, handler] of ROUTES) {
    if (method !== ctx.method) continue;
    const params = match(pattern, ctx.pathname);
    if (params) return handler(ctx, params);
  }
  return null;
}
