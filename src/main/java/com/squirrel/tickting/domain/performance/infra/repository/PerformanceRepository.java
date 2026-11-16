package com.squirrel.tickting.domain.performance.infra.repository;

import com.squirrel.tickting.domain.performance.infra.entity.Performance;
import com.squirrel.tickting.domain.performance.type.PerformanceStatus;
import java.util.Collection;
import java.util.List;
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

	/** 이 취소 규정을 쓰는 공연 중에 주어진 상태가 있는가. 규정 수정(SLR-22)의 잠금 검사와 상세의 editable 계산에 쓴다. */
	@Query("""
		SELECT COUNT(p) > 0
		FROM Performance p
		WHERE p.cancelPolicy.id = :cancelPolicyId
		  AND p.status IN :statuses
		""")
	boolean existsByCancelPolicyAndStatuses(
		@Param("cancelPolicyId") Long cancelPolicyId,
		@Param("statuses") Collection<PerformanceStatus> statuses
	);

	/**
	 * 주어진 규정들 중에 주어진 상태의 공연이 쓰는 규정 번호. 규정 목록의 editable 을 한 페이지에 쿼리 한 번으로 계산한다.
	 * 규정마다 따로 묻지 않으려는 것이고, 조건은 existsByCancelPolicyAndStatuses 와 같아야 한다.
	 */
	@Query("""
		SELECT DISTINCT p.cancelPolicy.id
		FROM Performance p
		WHERE p.cancelPolicy.id IN :cancelPolicyIds
		  AND p.status IN :statuses
		""")
	List<Long> findCancelPolicyIdsInUse(
		@Param("cancelPolicyIds") Collection<Long> cancelPolicyIds,
		@Param("statuses") Collection<PerformanceStatus> statuses
	);
}
