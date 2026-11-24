// 응답 모양은 doc/api-spec.md 를 그대로 따른다. 명세가 바뀌면 여기를 먼저 고친다.

export type ApiEnvelope<T> = {
  success: boolean;
  code: string;
  message: string;
  data: T;
};

export type Page<T> = {
  content: T[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
};

export type AccountType = "USER" | "SELLER" | "ADMIN";

export type LoginResult = { accountType: AccountType; role: string | null };

export type Genre = "CONCERT" | "MUSICAL" | "PLAY" | "CLASSIC" | "ETC";
export type SaleStatus = "UPCOMING" | "ON_SALE" | "SOLD_OUT" | "ENDED";
export type ReceiveType = "ONSITE" | "DELIVERY" | "BOTH";

export type Term = {
  termId: number;
  type: "SERVICE" | "PRIVACY" | "WITHDRAW";
  version: string;
  content: string;
  required: boolean;
};

export type UserMe = {
  userId: number;
  email: string;
  name: string;
  nickname: string;
  phone: string;
  profileImg: string | null;
  createdAt: string;
};

// 가입 전 이메일 인증. (AUTH-17·18)
export type EmailVerificationSent = { expiresAt: string; resendAvailableAt: string };
export type EmailVerificationConfirmed = { verifiedUntil: string };

// 비밀번호 찾기. (AUTH-19~22)
export type PasswordResetSent = { email: string; expiresAt: string; resendAvailableAt: string };
export type PasswordResetValidity = { valid: boolean; accountType: AccountType | null };

export type PerformanceSummary = {
  performanceId: number;
  title: string;
  genre: Genre;
  posterUrl: string | null;
  ageLimit: number;
  venueName: string | null;
  region: string | null;
  firstStartAt: string | null;
  lastStartAt: string | null;
  saleOpenAt: string;
  saleStatus: SaleStatus;
};

export type CancelRule = {
  daysFrom: number;
  daysTo: number | null;
  feeRate: number;
  cancelable: boolean;
};

export type PerformanceDetail = {
  performanceId: number;
  title: string;
  genre: Genre;
  ageLimit: number;
  runningTime: number;
  castInfo: string | null;
  posterUrl: string | null;
  images: { url: string; sortOrder: number }[];
  notice: string | null;
  guide: string | null;
  venue: { venueId: number; name: string; address: string; region: string } | null;
  sale: {
    saleOpenAt: string;
    saleCloseAt: string;
    maxPerPerson: number;
    receiveType: ReceiveType;
    bookingFee: number;
  };
  schedules: {
    scheduleId: number;
    startAt: string;
    status: string;
    saleStatus: SaleStatus;
  }[];
  grades: { gradeId: number; name: string; price: number }[];
  cancelPolicy: CancelRule[];
  commonGuide: { bookingGuide: string | null; refundGuide: string | null } | null;
  seller: {
    companyName: string;
    representativeName: string;
    businessNumber: string;
    contactPhone: string;
    contactEmail: string;
  } | null;
};

export type Zone = {
  zoneId: number;
  name: string;
  floor: string;
  grade: string | null;
  color?: string | null;
  price: number | null;
  remaining: number;
  total: number;
};

export type ZoneSummary = { scheduleId: number; layoutKey: string | null; zones: Zone[] };

export type SeatStatus = "AVAILABLE" | "HELD" | "SOLD" | "BLOCKED" | "RESERVED";

export type Seat = {
  seatId: number;
  row: string;
  number: string;
  sortOrder: number;
  status: SeatStatus;
};

export type ZoneSeats = { zoneId: number; name: string; seats: Seat[] };

export type BookingStatus = "PENDING" | "PAID" | "PARTIAL_CANCELED" | "CANCELED" | "EXPIRED";

export type BookingSeat = {
  bookingSeatId: number;
  zoneName: string;
  rowLabel: string;
  seatNumber: string;
  gradeName: string;
  price: number;
  bookingFee: number;
};

export type BookingCreated = {
  bookingId: number;
  bookingNo: string;
  status: BookingStatus;
  expiresAt: string;
  seats: BookingSeat[];
  ticketAmount: number;
  bookingFeeAmount: number;
  totalAmount: number;
};

export type PaymentReady = {
  paymentId: number;
  orderId: string;
  orderName: string;
  amount: number;
  status: string;
  expiresAt: string;
};

export type PaymentConfirmed = {
  bookingId: number;
  bookingNo: string;
  bookingStatus: BookingStatus;
  paymentId: number;
  paymentStatus: string;
  method: string;
  amount: number;
  approvedAt: string;
};

export type BookingSummary = {
  bookingId: number;
  bookingNo: string;
  status: BookingStatus;
  performanceTitle: string;
  posterUrl: string | null;
  scheduleStartAt: string;
  seatCount: number;
  totalAmount: number;
  createdAt: string;
};

export type BookingDetail = {
  bookingId: number;
  bookingNo: string;
  status: BookingStatus;
  performance: { performanceId: number; title: string; posterUrl: string | null };
  schedule: { scheduleId: number; startAt: string; status: string };
  receiveType: "ONSITE" | "DELIVERY";
  orderer: { name: string; phone: string; email: string };
  delivery: {
    recipientName: string;
    recipientPhone: string;
    recipientAddress: string;
    carrier: string | null;
    trackingNo: string | null;
  } | null;
  seats: (BookingSeat & {
    active: boolean;
    canceledAt: string | null;
    cancelFee: number | null;
  })[];
  ticketAmount: number;
  bookingFeeAmount: number;
  totalAmount: number;
  refundedAmount: number;
  payment: {
    paymentId: number;
    method: string;
    amount: number;
    status: string;
    approvedAt: string;
  } | null;
  cancelPolicy: CancelRule[];
  cancelable: boolean;
  // 취소할 수 없으면 null
  currentFeeRate: number | null;
  expiresAt: string | null;
  createdAt: string;
  canceledAt: string | null;
};

export type CancelResult = {
  bookingId: number;
  bookingStatus: BookingStatus;
  canceledSeatIds: number[];
  cancelFeeTotal: number;
  // 토스 환불이 실패해도 좌석 취소는 끝나고 status 가 FAILED 로 온다.
  refund: { refundId: number; amount: number; status: "REQUESTED" | "PROCESSING" | "DONE" | "FAILED" };
};

export type SellerPerformanceSummary = {
  performanceId: number;
  title: string;
  status: "DRAFT" | "PUBLISHED" | "CANCELED";
  posterUrl: string | null;
  venueName: string | null;
  scheduleCount: number;
  createdAt: string;
};

export type CancelPolicySummary = {
  cancelPolicyId: number;
  name: string;
  isDefault: boolean;
  editable: boolean;
};

export type BusinessStatus = "PENDING" | "APPROVED" | "REJECTED" | "SUSPENDED";

export type BusinessSummary = {
  businessId: number;
  companyName: string;
  representativeName: string;
  businessNumber: string;
  status: BusinessStatus;
  createdAt: string;
  reviewedAt: string | null;
};

// ---------- 판매자 ----------

export type VenueItem = {
  venueId: number;
  name: string;
  address: string;
  region: string;
  totalSeats: number;
  layoutKey: string;
};

export type PerformanceStatus = "DRAFT" | "PUBLISHED" | "CANCELED";
export type ScheduleStatus = "ON_SALE" | "PAUSED" | "POSTPONED" | "CANCELED";

export type SellerSchedule = {
  scheduleId: number;
  startAt: string;
  status: ScheduleStatus;
  seatsGeneratedAt: string | null;
};

export type SellerPerformanceDetail = {
  performanceId: number;
  status: PerformanceStatus;
  venue: { venueId: number; name: string };
  title: string;
  genre: Genre;
  ageLimit: number;
  runningTime: number;
  castInfo: string | null;
  posterUrl: string | null;
  notice: string | null;
  guide: string | null;
  images: { url: string; sortOrder: number }[];
  cancelPolicyId: number;
  saleOpenAt: string | null;
  saleCloseAt: string | null;
  maxPerPerson: number | null;
  receiveType: ReceiveType | null;
  bookingFee: number | null;
  schedules: SellerSchedule[];
  createdAt: string;
  updatedAt: string;
};

export type PerformanceForm = {
  venueId: number;
  cancelPolicyId: number;
  title: string;
  genre: Genre;
  ageLimit: number;
  runningTime: number;
  castInfo: string | null;
  posterUrl: string;
  notice: string | null;
  guide: string | null;
  saleOpenAt: string | null;
  saleCloseAt: string | null;
  maxPerPerson: number | null;
  receiveType: ReceiveType | null;
  bookingFee: number | null;
  images: { url: string; sortOrder: number }[];
};

export type Grade = { gradeId: number; name: string; price: number };

export type ZoneAssignmentStatus = "ASSIGNED" | "INTENTIONALLY_UNSOLD" | "UNSET";

export type ZoneAssignment = {
  zoneId: number;
  name: string;
  floor: string;
  sortOrder: number;
  seatCount: number;
  assignmentStatus: ZoneAssignmentStatus;
  gradeId: number | null;
};

export type GradesResponse = { locked: boolean; grades: Grade[]; zones: ZoneAssignment[] };

export type SalesSchedule = {
  scheduleId: number;
  startAt: string;
  status: ScheduleStatus;
  seatsGenerated: boolean;
  totalSeats: number;
  soldSeats: number | null;
  heldSeats: number | null;
  availableSeats: number | null;
  reservedSeats: number | null;
  blockedSeats: number | null;
};

// 등급·가격별로 묶은 표. 금액은 수수료를 뺀 표값이고 예매할 때 저장한 값이다. (SLR-18)
export type TicketGroup = { gradeName: string; price: number; count: number; amount: number };

export type ScheduleBooking = {
  bookingId: number;
  bookingNo: string;
  status: BookingStatus;
  ordererName: string;
  ordererPhone: string;
  receiveType: "ONSITE" | "DELIVERY";
  delivery: { recipientName: string; recipientPhone: string; recipientAddress: string } | null;
  tickets: TicketGroup[];
  canceledTickets: TicketGroup[];
  seatCount: number;
  canceledSeatCount: number;
  ticketAmount: number;
  createdAt: string;
};

export type PolicyDetail = {
  cancelPolicyId: number;
  name: string;
  isDefault: boolean;
  editable: boolean;
  rules: CancelRule[];
};

// ---------- 관리자 ----------

export type BusinessDetail = BusinessSummary & {
  contactPhone: string;
  contactEmail: string;
  rejectReason: string | null;
  reviewedBy: number | null;
  termsAgreedAt: string;
  owner: { memberId: number; email: string; phone: string; status: string } | null;
};

export type RefundStatus = "REQUESTED" | "PROCESSING" | "DONE" | "FAILED";

export type RefundItem = {
  refundId: number;
  bookingId: number;
  bookingNo: string;
  paymentId: number;
  amount: number;
  reasonType: "USER_CANCEL" | "PERFORMANCE_CANCEL" | "POSTPONE_CANCEL" | "SYSTEM" | "ADMIN";
  reasonDetail: string | null;
  status: RefundStatus;
  retryCount: number;
  failReason: string | null;
  requestedAt: string;
};

export type VenueRow = {
  label: string;
  from: number;
  to: number;
};

export type VenueForm = {
  name: string;
  address: string;
  region: string;
  layoutKey: string;
  zones: { name: string; floor: string; rows: VenueRow[] }[];
};

// 회차 대기열. 입장(ADMITTED)해야 구역·좌석 조회와 좌석 점유를 할 수 있다. (doc/queue-design.md)
export type QueueStatus = {
  status: "WAITING" | "ADMITTED" | "NONE";
  position: number | null;
  waitingCount: number | null;
  expiresAt: string | null;
};
