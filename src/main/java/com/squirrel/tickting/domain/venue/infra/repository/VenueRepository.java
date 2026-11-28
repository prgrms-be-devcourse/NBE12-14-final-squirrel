package com.squirrel.tickting.domain.venue.infra.repository;

import com.squirrel.tickting.domain.venue.infra.entity.Venue;
import com.squirrel.tickting.domain.venue.type.VenueStatus;
import java.time.LocalDateTime;
import java.util.Collection;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface VenueRepository extends JpaRepository<Venue, Long> {

	/**
	 * 공연장 목록(판매자 선택 목록·관리자 목록). 상태가 주어진 것 중 하나이고 이름에 검색어가 들어간 공연장을 이름순으로 준다.
	 * 이름이 같은 공연장끼리는 번호순이다. 정렬이 흔들리면 페이지를 넘길 때 같은 공연장이 겹치거나 빠질 수 있어서 쿼리에 고정했다.
	 * 조건이 비는 경우(null)를 쿼리 안에서 처리하지 않도록 호출하는 쪽이 항상 값을 채워서 넘긴다.
	 *   - region : 지역. 서울, 경기처럼 값이 정해져 있어서 부분 일치가 아니라 정확히 같은 것만 찾는다.
	 *              지역을 따지지 않을 때는 빈 문자열("")을 넘긴다. 아래 (:region = '' OR ...) 는
	 *              "지역이 비어 있으면 모든 지역, 아니면 그 지역만"이라는 뜻이다.
	 *              null 을 넘기면 PostgreSQL 이 파라미터 타입을 정하지 못해 오류가 난다.
	 *   - pattern: LikeUtil.containsPattern 으로 만든 값. 소문자이고 %, _ 는 '!' 로 이스케이프되어 있다.
	 *              그래서 LIKE 에 ESCAPE '!' 가 꼭 필요하다. (LikeUtil.ESCAPE 와 같은 값)
	 * 정렬은 쿼리에 고정했으므로 pageable 에는 정렬을 넣지 않는다.
	 */
	@Query(value = """
		SELECT v
		FROM Venue v
		WHERE v.status IN :statuses
		  AND (:region = '' OR v.region = :region)
		  AND LOWER(v.name) LIKE :pattern ESCAPE '!'
		ORDER BY v.name ASC, v.id ASC
		""",
		countQuery = """
			SELECT COUNT(v)
			FROM Venue v
			WHERE v.status IN :statuses
			  AND (:region = '' OR v.region = :region)
			  AND LOWER(v.name) LIKE :pattern ESCAPE '!'
			""")
	Page<Venue> search(
		@Param("statuses") Collection<VenueStatus> statuses,
		@Param("region") String region,
		@Param("pattern") String pattern,
		Pageable pageable
	);

	/** 상태가 from 일 때만 to 로 바꾸고 바뀐 행 수를 돌려준다. 락이 듣지 않아도 같은 상태로 두 번 바뀌지 않게 하는 상태 조건부 UPDATE 다. */
	@Modifying(flushAutomatically = true)
	@Query("""
		UPDATE Venue v
		SET v.status = :to,
		    v.updatedAt = :now
		WHERE v.id = :id
		  AND v.status = :from
		""")
	int changeStatus(@Param("id") Long id, @Param("from") VenueStatus from, @Param("to") VenueStatus to,
		@Param("now") LocalDateTime now);
}
