package com.squirrel.tickting.domain.admin.dto;

import com.squirrel.tickting.domain.payment.infra.entity.Refund;
import com.squirrel.tickting.domain.payment.type.RefundReasonType;
import com.squirrel.tickting.domain.payment.type.RefundStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;
import lombok.Builder;

public class AdminRefundResponse {

	private AdminRefundResponse() {
	}

	/** 관리자 재처리 결과. 토스가 또 실패해도 요청은 처리된 것이라(횟수·감사 로그가 남음) status 와 failReason 으로 알린다. */
	@Builder
	@Schema(name = "AdminRefundRetried", description = "관리자 환불 재처리 결과")
	public record Retried(
		@Schema(description = "환불 번호")
		Long refundId,
		@Schema(description = "결과 상태. DONE 완료, FAILED 또 실패", example = "DONE")
		RefundStatus status,
		@Schema(description = "토스에 다시 보낸 횟수 (이번 재처리 포함)")
		Integer retryCount,
		@Schema(description = "실패 사유. 완료면 null")
		String failReason,
		@Schema(description = "환불 완료 일시. 실패면 null")
		LocalDateTime completedAt
	) {

		public static Retried from(Refund refund) {
			return Retried.builder()
				.refundId(refund.getId())
				.status(refund.getStatus())
				.retryCount(refund.getRetryCount())
				.failReason(refund.getFailReason())
				.completedAt(refund.getCompletedAt())
				.build();
		}
	}

	/** 관리자 환불 목록의 한 줄. 멱등키는 토스 재요청에만 쓰는 값이라 내리지 않는다. */
	@Builder
	@Schema(name = "AdminRefundSummary", description = "관리자 환불 목록 항목")
	public record Summary(
		@Schema(description = "환불 번호")
		Long refundId,
		@Schema(description = "예매 번호")
		Long bookingId,
		@Schema(description = "사용자에게 보이는 예매번호", example = "20261125QWRT91K")
		String bookingNo,
		@Schema(description = "결제 번호")
		Long paymentId,
		@Schema(description = "환불 금액(원)")
		Integer amount,
		@Schema(description = "환불 사유 종류", example = "USER_CANCEL")
		RefundReasonType reasonType,
		@Schema(description = "상세 사유. 없으면 null")
		String reasonDetail,
		@Schema(description = "상태", example = "FAILED")
		RefundStatus status,
		@Schema(description = "토스에 다시 보낸 횟수")
		Integer retryCount,
		@Schema(description = "마지막 실패 사유. 토스의 오류 코드와 메시지")
		String failReason,
		@Schema(description = "처음 환불을 요청한 일시")
		LocalDateTime requestedAt,
		@Schema(description = "마지막으로 토스에 보낸 일시")
		LocalDateTime lastAttemptedAt
	) {

		public static Summary from(Refund refund) {
			return Summary.builder()
				.refundId(refund.getId())
				.bookingId(refund.getBookingId())
				.bookingNo(refund.getBooking().getBookingNo())
				.paymentId(refund.getPayment().getId())
				.amount(refund.getAmount())
				.reasonType(refund.getReasonType())
				.reasonDetail(refund.getReasonDetail())
				.status(refund.getStatus())
				.retryCount(refund.getRetryCount())
				.failReason(refund.getFailReason())
				.requestedAt(refund.getRequestedAt())
				.lastAttemptedAt(refund.getLastAttemptedAt())
				.build();
		}
	}
}
