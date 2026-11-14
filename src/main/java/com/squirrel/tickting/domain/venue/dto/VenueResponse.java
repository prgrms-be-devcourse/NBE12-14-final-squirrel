package com.squirrel.tickting.domain.venue.dto;

import com.squirrel.tickting.domain.venue.infra.entity.Venue;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;

public class VenueResponse {

	private VenueResponse() {
	}

	/** 판매자의 공연장 선택 목록의 한 줄. 구역·좌석 같은 상세는 넣지 않고 공연장을 고르는 데 필요한 값만 둔다. */
	@Builder
	@Schema(name = "VenueSummary", description = "공연장 목록 항목")
	public record Summary(
		@Schema(description = "공연장 번호")
		Long venueId,
		@Schema(description = "공연장명")
		String name,
		@Schema(description = "주소")
		String address,
		@Schema(description = "지역", example = "서울")
		String region,
		@Schema(description = "총 좌석 수")
		Integer totalSeats,
		@Schema(description = "프론트가 도면을 고르는 이름표. 비어 있으면 기본 격자 도면", example = "sample-arena", nullable = true)
		String layoutKey
	) {

		public static Summary from(Venue venue) {
			return Summary.builder()
				.venueId(venue.getId())
				.name(venue.getName())
				.address(venue.getAddress())
				.region(venue.getRegion())
				.totalSeats(venue.getTotalSeats())
				.layoutKey(venue.getLayoutKey())
				.build();
		}
	}
}
