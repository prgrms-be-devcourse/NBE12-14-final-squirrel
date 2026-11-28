package com.squirrel.tickting.domain.admin.dto;

import com.squirrel.tickting.domain.performance.type.PerformanceStatus;
import com.squirrel.tickting.domain.venue.infra.entity.Venue;
import com.squirrel.tickting.domain.venue.type.VenueDeactivationMode;
import com.squirrel.tickting.domain.venue.type.VenueStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;
import java.util.List;
import lombok.Builder;

public class AdminVenueResponse {

	private AdminVenueResponse() {
	}

	/** 관리자 공연장 목록(ADM-10)의 한 줄. */
	@Builder
	@Schema(name = "AdminVenueSummary", description = "관리자 공연장 목록 항목")
	public record Summary(
		Long venueId,
		String name,
		String address,
		@Schema(example = "서울")
		String region,
		Integer totalSeats,
		@Schema(nullable = true)
		String layoutKey,
		@Schema(example = "ACTIVE")
		VenueStatus status,
		LocalDateTime updatedAt
	) {

		public static Summary from(Venue venue) {
			return Summary.builder()
				.venueId(venue.getId())
				.name(venue.getName())
				.address(venue.getAddress())
				.region(venue.getRegion())
				.totalSeats(venue.getTotalSeats())
				.layoutKey(venue.getLayoutKey())
				.status(venue.getStatus())
				.updatedAt(venue.getUpdatedAt())
				.build();
		}
	}

	/** 사용 중지(ADM-11) 결과. 새 일정만 막으면 뒤의 세 수는 0 이다. */
	@Builder
	@Schema(name = "AdminVenueDeactivated", description = "공연장 사용 중지 결과")
	public record Deactivated(
		Long venueId,
		String name,
		VenueStatus status,
		VenueDeactivationMode mode,
		@Schema(description = "이번에 취소한 회차 수", example = "12")
		int canceledScheduleCount,
		@Schema(description = "취소되지 않은 회차가 남지 않아 함께 취소한 공연 수", example = "3")
		int canceledPerformanceCount,
		@Schema(description = "이번에 취소한 회차의 환불 대상 예매 수(결제 완료·일부 취소). 환불은 이 요청 뒤에 처리한다", example = "4210")
		long refundRequestedCount
	) {
	}

	/** 재개(ADM-12) 결과. */
	@Schema(name = "AdminVenueActivated", description = "공연장 사용 재개 결과")
	public record Activated(
		Long venueId,
		String name,
		VenueStatus status
	) {
	}

	/** 사용 중지 영향 미리보기(ADM-13). 실행 전이라 그 뒤 예매가 늘 수 있다. */
	@Builder
	@Schema(name = "AdminVenueImpact", description = "공연장 사용 중지 영향 미리보기")
	public record Impact(
		Long venueId,
		String name,
		VenueStatus status,
		@Schema(description = "전부 취소하면 취소될 회차 수(공개 공연의 시작 전·일정 미정 회차)")
		int upcomingScheduleCount,
		@Schema(description = "그 회차의 결제 완료·일부 취소 예매 수")
		long refundTargetBookingCount,
		@Schema(description = "그 회차의 결제 대기 예매 수. 취소하면 결제할 수 없다")
		long pendingBookingCount,
		@Schema(description = "이 공연장의 작성 중 공연 수. 취소하지 않지만 공개할 수 없게 된다")
		int draftPerformanceCount,
		@Schema(description = "취소될 회차가 있는 공개 공연과 작성 중 공연. 환불 대상이 많은 순, 같으면 번호순")
		List<ImpactPerformance> performances
	) {
	}

	@Builder
	@Schema(name = "AdminVenueImpactPerformance", description = "영향을 받는 공연")
	public record ImpactPerformance(
		Long performanceId,
		String title,
		PerformanceStatus status,
		Long businessId,
		String businessName,
		@Schema(description = "취소될 회차 수. 작성 중 공연은 취소하지 않아 0")
		int upcomingScheduleCount,
		long refundTargetBookingCount
	) {
	}
}
