package com.squirrel.tickting.domain.performance.dto;

import com.squirrel.tickting.domain.performance.infra.entity.Performance;
import com.squirrel.tickting.domain.performance.type.PerformanceStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;
import lombok.Builder;

/** 판매자의 내 공연 응답. 예매자용 공연 응답과 모양이 달라서 이름을 구분했다. */
public class SellerPerformanceResponse {

	private SellerPerformanceResponse() {
	}

	/** 내 공연 목록의 한 줄. 상세에서 보는 값은 넣지 않고 목록에서 고를 때 필요한 것만 둔다. */
	@Builder
	@Schema(name = "SellerPerformanceSummary", description = "내 공연 목록 항목")
	public record Summary(
		@Schema(description = "공연 번호")
		Long performanceId,
		@Schema(description = "공연명")
		String title,
		@Schema(description = "공연 상태", example = "PUBLISHED")
		PerformanceStatus status,
		@Schema(description = "포스터 주소")
		String posterUrl,
		@Schema(description = "공연장명 (사용 중지된 공연장도 보인다)")
		String venueName,
		@Schema(description = "회차 수. 취소·연기된 회차도 센다.")
		long scheduleCount,
		@Schema(description = "등록 일시")
		LocalDateTime createdAt
	) {

		public static Summary from(Performance performance, String venueName, long scheduleCount) {
			return Summary.builder()
				.performanceId(performance.getId())
				.title(performance.getTitle())
				.status(performance.getStatus())
				.posterUrl(performance.getPosterUrl())
				.venueName(venueName)
				.scheduleCount(scheduleCount)
				.createdAt(performance.getCreatedAt())
				.build();
		}
	}
}
