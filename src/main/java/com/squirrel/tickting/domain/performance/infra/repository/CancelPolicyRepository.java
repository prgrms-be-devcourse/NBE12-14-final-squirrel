package com.squirrel.tickting.domain.performance.infra.repository;

import com.squirrel.tickting.domain.performance.infra.entity.CancelPolicy;
import java.util.Optional;
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

	/**
	 * 판매자가 볼 수 있는 규정 하나(서비스 기본 규정 또는 내 사업체 규정). 목록(searchVisible)과 같은 조건에 번호만 더한다.
	 * 기본 규정은 사업체 번호가 비어 있어서 findByIdAndBusinessId 로는 걸러지므로 조건을 직접 쓴다.
	 */
	@Query("""
		SELECT c
		FROM CancelPolicy c
		WHERE c.id = :id
		  AND (c.businessId IS NULL OR c.businessId = :businessId)
		""")
	Optional<CancelPolicy> findVisible(@Param("id") Long id, @Param("businessId") Long businessId);

	/**
	 * 내 사업체 규정이나 서비스 기본 규정 중에 대소문자를 무시하고 같은 이름이 있는가.
	 * 나중에 만들 UNIQUE (business_id, lower(name)) 와 기준이 같도록 Java 가 아니라 DB 의 LOWER 로 비교한다.
	 * 기본 규정은 사업체 번호가 비어 있어 그 UNIQUE 로는 못 막으므로 여기서만 확인한다.
	 */
	@Query("""
		SELECT COUNT(c) > 0
		FROM CancelPolicy c
		WHERE (c.businessId IS NULL OR c.businessId = :businessId)
		  AND LOWER(c.name) = LOWER(:name)
		""")
	boolean existsVisibleName(@Param("businessId") Long businessId, @Param("name") String name);

	/**
	 * 규정 수정(SLR-22)의 이름 중복 확인. existsVisibleName 과 같고 수정하는 규정 자신만 뺀다.
	 * 제외할 번호를 null 로 넘겨 한 쿼리로 합치면 PostgreSQL 이 파라미터 타입을 정하지 못해서 따로 둔다.
	 */
	@Query("""
		SELECT COUNT(c) > 0
		FROM CancelPolicy c
		WHERE (c.businessId IS NULL OR c.businessId = :businessId)
		  AND LOWER(c.name) = LOWER(:name)
		  AND c.id <> :excludeId
		""")
	boolean existsVisibleNameExcept(@Param("businessId") Long businessId, @Param("name") String name,
		@Param("excludeId") Long excludeId);

	/**
	 * 수정할 내 사업체 규정. 기본 규정은 사업체 번호 조건으로 빠져서 비어 돌아온다.
	 * 같은 규정의 동시 수정과 공연 공개(SLR-04)는 DB 행 잠금이 아니라 분산락 cancel-policy:{규정 번호} 로 막는다.
	 */
	@Query("""
		SELECT c
		FROM CancelPolicy c
		WHERE c.id = :id
		  AND c.businessId = :businessId
		""")
	Optional<CancelPolicy> findMine(@Param("id") Long id, @Param("businessId") Long businessId);
}
