package com.squirrel.tickting.domain.performance.dto;

import com.squirrel.tickting.domain.performance.infra.entity.Performance;
import com.squirrel.tickting.domain.performance.infra.entity.SeatGrade;
import com.squirrel.tickting.domain.performance.infra.entity.ZoneGrade;
import com.squirrel.tickting.domain.performance.type.SeatGradeName;
import com.squirrel.tickting.domain.performance.type.ZoneAssignmentStatus;
import com.squirrel.tickting.domain.venue.infra.entity.VenueZone;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.Comparator;
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

		/**
		 * 공연장 구역의 이름·층·좌석 수는 공연의 읽기 전용 연관(공연장의 구역)에서 꺼낸다. 지연 로딩이라 트랜잭션 안에서 불러야 한다.
		 * 구역 설정은 공연장 구역 번호로만 연결되어 있어서(논리 FK) 맞는 구역이 없으면 데이터가 깨진 것이다.
		 * 조용히 빼면 판매자가 구역이 빠진 줄 모르므로 서버 오류로 드러낸다.
		 */
		public static Overview from(Performance performance, List<SeatGrade> grades, List<ZoneGrade> zoneGrades,
			boolean locked) {
			List<VenueZone> venueZones = performance.getVenue().getZones();
			List<Zone> zones = zoneGrades.stream()
				.map(zoneGrade -> Zone.from(zoneGrade, venueZones.stream()
					.filter(venueZone -> venueZone.getId().equals(zoneGrade.getZoneId()))
					.findFirst()
					.orElseThrow(() -> new IllegalStateException(
						"구역 설정이 가리키는 공연장 구역이 없습니다. zoneId=" + zoneGrade.getZoneId()))))
				.sorted(Comparator.comparing(Zone::sortOrder).thenComparing(Zone::zoneId))
				.toList();
			return Overview.builder()
				.locked(locked)
				.grades(grades.stream().map(Grade::from).toList())
				.zones(zones)
				.build();
		}
	}

	@Builder
	@Schema(name = "SellerGradeItem", description = "등급")
	public record Grade(
		@Schema(description = "등급 번호")
		Long gradeId,
		@Schema(description = "등급 코드. 화면 이름과 색은 프론트가 정한다", example = "VIP")
		SeatGradeName name,
		@Schema(description = "가격(원)", example = "165000")
		Integer price
	) {

		public static Grade from(SeatGrade grade) {
			return Grade.builder()
				.gradeId(grade.getId())
				.name(grade.getName())
				.price(grade.getPrice())
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

		public static Zone from(ZoneGrade zoneGrade, VenueZone venueZone) {
			return Zone.builder()
				.zoneId(zoneGrade.getZoneId())
				.name(venueZone.getName())
				.floor(venueZone.getFloor())
				.sortOrder(venueZone.getSortOrder())
				.seatCount(venueZone.getSeatCount())
				.assignmentStatus(zoneGrade.getAssignmentStatus())
				.gradeId(zoneGrade.getGrade() == null ? null : zoneGrade.getGrade().getId())
				.build();
		}
	}
}
