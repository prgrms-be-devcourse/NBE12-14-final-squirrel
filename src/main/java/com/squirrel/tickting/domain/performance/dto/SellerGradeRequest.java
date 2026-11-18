package com.squirrel.tickting.domain.performance.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** 판매자의 등급 요청. 추가(SLR-09)와 수정(SLR-10)이 같은 본문을 쓴다. */
public class SellerGradeRequest {

	private SellerGradeRequest() {
	}

	/**
	 * 등급 추가·수정 본문. 같은 공연 안의 이름 중복은 다른 등급을 봐야 해서 서비스가 본다.
	 * 공백 정리는 검증보다 먼저 일어나야 해서 생성자에서 한다. (공백만 있는 이름이 @NotBlank 에 걸리고, 공백뿐인 색은 비운 것으로)
	 */
	@Schema(name = "SellerGradeSaveRequest", description = "등급 추가·수정 요청")
	public record Save(

		@NotBlank(message = "등급명을 입력해 주세요.")
		@Size(max = 50, message = "등급명은 50자 이하로 입력해 주세요.")
		@Schema(description = "등급명. 같은 공연 안에서 겹칠 수 없다(대소문자·공백 무시)", example = "R석")
		String name,

		// 0원은 무료 좌석·초대석이다. 상한은 판매자 입력 실수를 거르는 임시값이고, 100만 원이 넘는 가격의 확인은 프론트가 한다.
		@NotNull(message = "가격을 입력해 주세요.")
		@Min(value = 0, message = "가격은 0원 이상이어야 합니다.")
		@Max(value = 10_000_000, message = "가격은 10,000,000원 이하여야 합니다.")
		@Schema(description = "가격(원). 0 이상 10,000,000 이하", example = "154000")
		Integer price,

		// 좌석도에 칠하는 값이라 색으로 읽을 수 없는 값이 저장되면 예매자 화면의 색이 깨진다.
		@Pattern(regexp = "^#[0-9A-Fa-f]{6}$", message = "색은 #RRGGBB 형식으로 입력해 주세요.")
		@Schema(description = "좌석도 색(#RRGGBB). 비우면 색 없음", example = "#F2994A")
		String color
	) {

		public Save {
			name = name == null ? null : EDGE_SPACES.matcher(name).replaceAll("");
			color = color == null || color.isBlank() ? null : color;
		}
	}

	/**
	 * 앞뒤 공백. trim()·strip() 은 NBSP(U+00A0)를 지우지 않아서 유니코드 공백 전체(White_Space)로 지운다.
	 * 붙여 넣은 이름 끝의 NBSP 가 남으면 눈에 안 보이는 글자가 저장되고, NBSP 만 있는 이름이 @NotBlank 를 통과한다.
	 */
	// 같은 이름의 검증 어노테이션(@Pattern)이 있어서 정규식 클래스는 전체 이름으로 쓴다.
	private static final java.util.regex.Pattern EDGE_SPACES =
		java.util.regex.Pattern.compile("^\\p{IsWhite_Space}+|\\p{IsWhite_Space}+$");
}
