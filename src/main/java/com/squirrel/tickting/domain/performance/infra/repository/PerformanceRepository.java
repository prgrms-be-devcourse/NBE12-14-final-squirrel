package com.squirrel.tickting.domain.performance.infra.repository;

import com.squirrel.tickting.domain.performance.infra.entity.Performance;
import com.squirrel.tickting.domain.performance.type.PerformanceStatus;
import java.util.Collection;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PerformanceRepository extends JpaRepository<Performance, Long> {

	/** 내 사업체의 공연. 남의 공연은 없는 것과 같게 비어서 돌아온다. */
	Optional<Performance> findByIdAndBusinessId(Long id, Long businessId);

	/**
	 * 내 사업체의 공연 목록. 최신 등록이 먼저 나오고 등록 시각이 같으면 번호가 큰 것이 먼저다.
	 * 상태가 비는 경우(null)를 쿼리 안에서 처리하지 않도록 호출하는 쪽이 항상 값을 채워서 넘긴다.
	 *   - statuses: 비지 않은 상태 목록 (상태를 따지지 않으면 모든 상태)
	 * 정렬은 쿼리에 고정했으므로 pageable 에는 정렬을 넣지 않는다.
	 */
	@Query(value = """
		SELECT p
		FROM Performance p
		WHERE p.businessId = :businessId
		  AND p.status IN :statuses
		ORDER BY p.createdAt DESC, p.id DESC
		""",
		countQuery = """
			SELECT COUNT(p)
			FROM Performance p
			WHERE p.businessId = :businessId
			  AND p.status IN :statuses
			""")
	Page<Performance> search(
		@Param("businessId") Long businessId,
		@Param("statuses") Collection<PerformanceStatus> statuses,
		Pageable pageable
	);
}
