package com.squirrel.tickting.global.response;

import java.util.List;
import org.springframework.data.domain.Page;

/**
 * 목록 API 의 공통 응답. ApiResponse 의 data 에 담겨 내려간다.
 * Spring 의 Page 를 그대로 내리면 정렬·pageable 같은 내부 구조가 노출되고 모양이 바뀔 수 있어서,
 * 프론트가 쓰는 값만 골라 고정한다. 페이지 번호는 0부터 시작한다.
 */
public record PageResponse<T>(
	List<T> content,
	int page,
	int size,
	long totalElements,
	int totalPages
) {

	public static <T> PageResponse<T> from(Page<T> page) {
		return new PageResponse<>(page.getContent(), page.getNumber(), page.getSize(),
			page.getTotalElements(), page.getTotalPages());
	}
}
