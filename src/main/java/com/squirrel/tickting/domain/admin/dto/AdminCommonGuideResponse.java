package com.squirrel.tickting.domain.admin.dto;

import com.squirrel.tickting.domain.performance.infra.entity.CommonGuide;
import com.squirrel.tickting.domain.performance.type.GuideType;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import lombok.Builder;

public class AdminCommonGuideResponse {

	private AdminCommonGuideResponse() {
	}

	/** 공통 안내 한 줄. 아직 넣지 않은 유형도 내용을 비워서 주므로 화면이 빈 칸을 보여 줄 수 있다. */
	@Builder
	@Schema(name = "AdminCommonGuide", description = "공통 안내")
	public record Guide(
		@Schema(example = "BOOKING_GUIDE")
		GuideType type,
		@Schema(example = "예매 안내")
		String typeName,
		@Schema(description = "안내 내용. 아직 넣지 않았으면 null")
		String content,
		@Schema(description = "마지막으로 수정한 관리자 번호. 아직 넣지 않았으면 null")
		Long updatedBy,
		@Schema(description = "마지막 수정 일시. 아직 넣지 않았으면 null")
		LocalDateTime updatedAt
	) {

		public static Guide from(CommonGuide guide) {
			return Guide.builder()
				.type(guide.getType())
				.typeName(guide.getType().getLabel())
				.content(guide.getContent())
				.updatedBy(guide.getUpdatedBy())
				.updatedAt(guide.getUpdatedAt())
				.build();
		}

		static Guide empty(GuideType type) {
			return Guide.builder()
				.type(type)
				.typeName(type.getLabel())
				.build();
		}

		/** 모든 유형을 선언 순서대로 한 줄씩. 저장된 행이 없는 유형은 빈 줄이다. (유형마다 한 행이라 겹치지 않는다) */
		public static List<Guide> listOf(List<CommonGuide> saved) {
			return Arrays.stream(GuideType.values())
				.map(type -> saved.stream()
					.filter(guide -> guide.getType() == type)
					.findFirst()
					.map(Guide::from)
					.orElseGet(() -> empty(type)))
				.toList();
		}
	}
}
