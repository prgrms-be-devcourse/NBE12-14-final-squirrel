package com.squirrel.tickting.domain.performance.dto;

import com.squirrel.tickting.domain.performance.infra.repository.PerformanceSummaryRow;
import com.squirrel.tickting.domain.performance.service.PerformanceSummary;
import com.squirrel.tickting.domain.performance.type.Genre;
import com.squirrel.tickting.domain.performance.type.SaleStatus;
import com.squirrel.tickting.domain.venue.infra.entity.Venue;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;
import lombok.Builder;

public class PerformanceResponse {

	private PerformanceResponse() {
	}

	/** 공연 목록의 한 항목. (API 명세 PFM-01) 판매 종료 시각은 판매 상태 계산에만 쓰고 내리지 않는다. */
	@Builder
	@Schema(name = "PerformanceSummary", description = "공연 목록 항목")
	public record Summary(
		@Schema(description = "공연 번호")
		Long performanceId,
		@Schema(description = "공연 제목", example = "가상 콘서트")
		String title,
		@Schema(description = "장르")
		Genre genre,
		@Schema(description = "포스터 이미지 주소")
		String posterUrl,
		@Schema(description = "관람 가능 나이 (만)", example = "12")
		Integer ageLimit,
		@Schema(description = "공연장 이름")
		String venueName,
		@Schema(description = "공연장 지역", example = "서울")
		String region,
		@Schema(description = "첫 회차 일시 (취소된 회차 제외). 회차가 없으면 null")
		LocalDateTime firstStartAt,
		@Schema(description = "마지막 회차 일시 (취소된 회차 제외). 회차가 없으면 null")
		LocalDateTime lastStartAt,
		@Schema(description = "판매 오픈 일시")
		LocalDateTime saleOpenAt,
		@Schema(description = "판매 상태 (계산값)")
		SaleStatus saleStatus
	) {

		/**
		 * 공연장은 논리 FK 라 DB 가 존재를 보장하지 않는다. 공연장 행이 없어도 목록 전체가 실패하지 않도록
		 * 이름·지역만 비우고(null) 나머지는 그대로 보여 준다.
		 */
		public static Summary from(PerformanceSummary summary, Venue venue) {
			PerformanceSummaryRow row = summary.row();
			return Summary.builder()
				.performanceId(row.performanceId())
				.title(row.title())
				.genre(row.genre())
				.posterUrl(row.posterUrl())
				.ageLimit(row.ageLimit())
				.venueName(venue == null ? null : venue.getName())
				.region(venue == null ? null : venue.getRegion())
				.firstStartAt(row.firstStartAt())
				.lastStartAt(row.lastStartAt())
				.saleOpenAt(row.saleOpenAt())
				.saleStatus(summary.saleStatus())
				.build();
		}
	}
}
