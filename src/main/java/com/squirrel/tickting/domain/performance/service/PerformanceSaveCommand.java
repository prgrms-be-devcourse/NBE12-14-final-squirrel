package com.squirrel.tickting.domain.performance.service;

import com.squirrel.tickting.domain.performance.type.Genre;
import com.squirrel.tickting.domain.performance.type.ReceiveType;
import java.time.LocalDateTime;
import java.util.List;
import lombok.Builder;

/**
 * 공연 등록·수정에 넣는 값. 서비스가 요청 DTO(SellerPerformanceRequest)를 모르게 하려고 따로 둔다.
 * 사업체 번호·등록자·공연장·취소 규정은 요청 그대로가 아니라 토큰과 확인을 거친 값이라 서비스 메서드의 인자로 따로 받는다.
 */
@Builder
public record PerformanceSaveCommand(
	String title,
	Genre genre,
	Integer ageLimit,
	Integer runningTime,
	String castInfo,
	String posterUrl,
	String notice,
	String guide,
	LocalDateTime saleOpenAt,
	LocalDateTime saleCloseAt,
	Integer maxPerPerson,
	ReceiveType receiveType,
	Integer bookingFee,
	List<Image> images
) {

	public PerformanceSaveCommand {
		images = images == null ? List.of() : List.copyOf(images);
	}

	/** 상세 설명용 이미지 한 장 */
	public record Image(String url, Integer sortOrder) {
	}
}
