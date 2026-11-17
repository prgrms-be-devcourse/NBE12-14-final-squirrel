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
  birthDate: string;
  createdAt: string;
};

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
  grades: { gradeId: number; name: string; price: number; color: string }[];
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
  color: string | null;
  price: number | null;
  remaining: number;
  total: number;
};

export type ZoneSummary = { scheduleId: number; layoutKey: string; zones: Zone[] };

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
  currentFeeRate: number;
  expiresAt: string | null;
  createdAt: string;
  canceledAt: string | null;
};

export type CancelResult = {
  bookingId: number;
  bookingStatus: BookingStatus;
  canceledSeatIds: number[];
  cancelFeeTotal: number;
  refund: { refundId: number; amount: number; status: string };
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
