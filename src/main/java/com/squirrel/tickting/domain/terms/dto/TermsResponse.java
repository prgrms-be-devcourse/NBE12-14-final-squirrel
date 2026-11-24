package com.squirrel.tickting.domain.terms.dto;

import com.squirrel.tickting.domain.terms.infra.entity.Terms;
import com.squirrel.tickting.domain.terms.type.TermsType;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;

public class TermsResponse {

	private TermsResponse() {
	}

	/** 가입 화면에서 보여 주고 동의를 받는 약관 한 건. termId 는 가입 요청의 agreedTermIds 에 그대로 넣는다. */
	@Builder
	@Schema(name = "TermsItem", description = "약관 (종류별 최신 버전)")
	public record Item(
		@Schema(description = "약관 번호. 가입할 때 agreedTermIds 로 보낸다")
		Long termId,
		@Schema(description = "약관 종류", example = "SERVICE")
		TermsType type,
		@Schema(description = "버전", example = "1.0")
		String version,
		@Schema(description = "약관 본문")
		String content,
		@Schema(description = "필수 여부. 필수 약관에 모두 동의해야 가입할 수 있다")
		boolean required
	) {

		public static Item from(Terms terms) {
			return Item.builder()
				.termId(terms.getId())
				.type(terms.getType())
				.version(terms.getVersion())
				.content(terms.getContent())
				.required(terms.isRequired())
				.build();
		}
	}
}
