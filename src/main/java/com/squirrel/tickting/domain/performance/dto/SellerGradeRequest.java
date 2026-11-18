package com.squirrel.tickting.domain.performance.dto;

import com.squirrel.tickting.domain.performance.type.SeatGradeName;
import com.squirrel.tickting.domain.performance.type.ZoneAssignmentStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.util.List;

/** 판매자의 등급 요청. 추가(SLR-09)와 수정(SLR-10)이 같은 본문을 쓰고, 구역 상태·등급 설정(SLR-11)도 여기 둔다. */
public class SellerGradeRequest {

	private SellerGradeRequest() {
	}

	/** 등급 추가·수정 본문. 같은 공연에 같은 등급이 있는지는 다른 등급을 봐야 해서 서비스가 본다. */
	@Schema(name = "SellerGradeSaveRequest", description = "등급 추가·수정 요청")
	public record Save(

		// 목록에 없는 코드는 본문을 읽지 못해 400(COMMON000)이 된다.
		@NotNull(message = "등급을 입력해 주세요.")
		@Schema(description = "등급 코드. 같은 공연에 같은 등급은 하나만 둘 수 있다", example = "R")
		SeatGradeName name,

		// 0원은 무료 좌석·초대석이다. 상한은 판매자 입력 실수를 거르는 임시값이고, 100만 원이 넘는 가격의 확인은 프론트가 한다.
		@NotNull(message = "가격을 입력해 주세요.")
		@Min(value = 0, message = "가격은 0원 이상이어야 합니다.")
		@Max(value = 10_000_000, message = "가격은 10,000,000원 이하여야 합니다.")
		@Schema(description = "가격(원). 0 이상 10,000,000 이하", example = "154000")
		Integer price
	) {
	}

	/**
	 * 구역 상태·등급 설정 본문(SLR-11). 요청에 있는 구역만 바꾸고 빠진 구역은 그대로 둔다.
	 * 빈 목록을 200 으로 넘기면 프론트 버그로 변경 목록이 비어도 판매자는 저장된 줄 알게 되어 400 으로 막는다.
	 * 같은 구역이 두 번 오는지와 상태·등급 조합은 항목끼리·필드끼리의 관계라 서비스가 DB 조회 전에 본다.
	 */
	@Schema(name = "SellerZoneGradeRequest", description = "구역 상태·등급 설정 요청")
	public record ZoneAssign(
		@NotEmpty(message = "변경할 구역을 입력해 주세요.")
		@Schema(description = "바꿀 구역 설정. 같은 구역을 두 번 보낼 수 없다")
		List<@Valid @NotNull(message = "구역 설정이 비어 있습니다.") Item> assignments
	) {
	}

	@Schema(name = "SellerZoneGradeItem", description = "구역 하나의 상태와 등급")
	public record Item(
		@NotNull(message = "구역을 입력해 주세요.")
		@Schema(description = "구역 번호", example = "12")
		Long zoneId,

		// 목록에 없는 상태는 본문을 읽지 못해 400(COMMON000)이 된다.
		@NotNull(message = "구역 상태를 입력해 주세요.")
		@Schema(description = "구역 상태", example = "ASSIGNED")
		ZoneAssignmentStatus assignmentStatus,

		@Schema(description = "지정할 등급 번호. ASSIGNED 일 때만 보내고 나머지는 null", example = "21")
		Long gradeId
	) {
	}
}
