package com.squirrel.tickting.domain.performance.dto;

import com.squirrel.tickting.domain.performance.infra.entity.SeatGrade;
import com.squirrel.tickting.domain.performance.infra.entity.ZoneGrade;
import com.squirrel.tickting.domain.performance.type.ZoneAssignmentStatus;
import com.squirrel.tickting.domain.venue.service.VenueZoneInfo;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import lombok.Builder;

/** 판매자의 등급·구역 응답. 구역 상태·등급 설정(SLR-11)도 같은 모양으로 돌려준다. */
public class SellerGradeResponse {

	private SellerGradeResponse() {
	}

	@Builder
	@Schema(name = "SellerGradeOverview", description = "공연의 등급과 구역 설정")
	public record Overview(
		@Schema(description = "판매 좌석이 생성되어 등급·가격·구역 설정을 고칠 수 없는 상태. 취소되지 않은 회차 중 하나라도 좌석을 만들었으면 true")
		boolean locked,
		@Schema(description = "등급. 가격 높은 순(같으면 번호순)")
		List<Grade> grades,
		@Schema(description = "구역 설정. 공연장 구역 순서(sortOrder)순, 같으면 구역 번호순")
		List<Zone> zones
	) {
	}

	@Builder
	@Schema(name = "SellerGradeItem", description = "등급")
	public record Grade(
		@Schema(description = "등급 번호")
		Long gradeId,
		@Schema(description = "등급명", example = "VIP석")
		String name,
		@Schema(description = "가격(원)", example = "165000")
		Integer price,
		@Schema(description = "좌석도 색", example = "#E8503A")
		String color
	) {

		public static Grade from(SeatGrade grade) {
			return Grade.builder()
				.gradeId(grade.getId())
				.name(grade.getName())
				.price(grade.getPrice())
				.color(grade.getColor())
				.build();
		}
	}

	@Builder
	@Schema(name = "SellerGradeZone", description = "구역 설정")
	public record Zone(
		@Schema(description = "구역 번호")
		Long zoneId,
		@Schema(description = "구역명", example = "F2구역")
		String name,
		@Schema(description = "층", example = "1층")
		String floor,
		@Schema(description = "공연장 안 구역 순서")
		Integer sortOrder,
		@Schema(description = "구역 좌석 수")
		long seatCount,
		@Schema(description = "구역 상태", example = "ASSIGNED")
		ZoneAssignmentStatus assignmentStatus,
		@Schema(description = "지정한 등급 번호. ASSIGNED 일 때만 있고 나머지는 null")
		Long gradeId
	) {

		public static Zone from(ZoneGrade zoneGrade, VenueZoneInfo zoneInfo) {
			return Zone.builder()
				.zoneId(zoneGrade.getZoneId())
				.name(zoneInfo.name())
				.floor(zoneInfo.floor())
				.sortOrder(zoneInfo.sortOrder())
				.seatCount(zoneInfo.seatCount())
				.assignmentStatus(zoneGrade.getAssignmentStatus())
				.gradeId(zoneGrade.getGrade() == null ? null : zoneGrade.getGrade().getId())
				.build();
		}
	}
}
