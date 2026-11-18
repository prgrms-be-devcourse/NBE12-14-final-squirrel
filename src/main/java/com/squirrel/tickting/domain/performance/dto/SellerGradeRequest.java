package com.squirrel.tickting.domain.performance.dto;

import com.squirrel.tickting.domain.performance.type.SeatGradeName;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/** 판매자의 등급 요청. 추가(SLR-09)와 수정(SLR-10)이 같은 본문을 쓴다. */
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
}
