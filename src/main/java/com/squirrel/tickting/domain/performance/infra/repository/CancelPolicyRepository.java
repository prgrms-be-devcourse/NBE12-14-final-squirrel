package com.squirrel.tickting.domain.performance.infra.repository;

import com.squirrel.tickting.domain.performance.infra.entity.CancelPolicy;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CancelPolicyRepository extends JpaRepository<CancelPolicy, Long> {

	/**
	 * 판매자가 볼 수 있는 규정(서비스 기본 규정 + 내 사업체 규정).
	 * 기본 규정을 참고해 새 규정을 만드는 흐름이라 기본 규정을 첫 페이지 맨 앞에 두고,
	 * 내 규정은 방금 만든 것을 바로 찾도록 번호가 큰(최신) 순으로 둔다.
	 */
	@Query(value = """
		SELECT c
		FROM CancelPolicy c
		WHERE c.businessId IS NULL OR c.businessId = :businessId
		ORDER BY CASE WHEN c.businessId IS NULL THEN 0 ELSE 1 END ASC, c.id DESC
		""",
		countQuery = """
			SELECT COUNT(c)
			FROM CancelPolicy c
			WHERE c.businessId IS NULL OR c.businessId = :businessId
			""")
	Page<CancelPolicy> searchVisible(@Param("businessId") Long businessId, Pageable pageable);
}
