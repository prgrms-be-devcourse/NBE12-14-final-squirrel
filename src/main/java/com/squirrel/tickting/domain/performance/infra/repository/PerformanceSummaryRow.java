package com.squirrel.tickting.domain.performance.infra.repository;

import com.squirrel.tickting.domain.performance.type.Genre;
import java.time.LocalDateTime;

/**
 * 예매자 공연 목록 쿼리의 한 행. 엔티티를 통째로 읽으면 목록에 안 쓰는 긴 글(공지·안내)까지 읽어서, 필요한 값만 받는다.
 * 회차 일시와 남은 좌석은 같은 공연 도메인의 테이블이라 쿼리에서 함께 모으고,
 * 공연장은 다른 도메인이라 번호(venueId)만 담는다. 이름·지역은 파사드가 공연장 서비스에서 받아 붙인다.
 *
 * @param firstStartAt     취소되지 않은 회차 중 가장 이른 공연 일시. 회차가 없으면 null
 * @param lastStartAt      취소되지 않은 회차 중 가장 늦은 공연 일시. 회차가 없으면 null
 * @param hasAvailableSeat 아직 시작하지 않은 회차에 판매 가능 좌석이 하나라도 있는지 (매진 판단용)
 */
public record PerformanceSummaryRow(
	Long performanceId,
	String title,
	Genre genre,
	String posterUrl,
	Integer ageLimit,
	Long venueId,
	LocalDateTime firstStartAt,
	LocalDateTime lastStartAt,
	LocalDateTime saleOpenAt,
	LocalDateTime saleCloseAt,
	boolean hasAvailableSeat
) {
}
