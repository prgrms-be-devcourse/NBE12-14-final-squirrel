package com.squirrel.tickting.domain.performance.infra.repository;

import com.squirrel.tickting.domain.performance.infra.entity.Performance;
import com.squirrel.tickting.domain.performance.type.PerformanceStatus;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
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

	/**
	 * 공연이 쓰는 취소 규정 중 락을 잡을 규정 번호. 서비스 기본 규정이면 비어서 돌아온다.
	 * 기본 규정은 항상 수정할 수 없어 공개와 부딪히지 않고, 모든 사업체가 함께 쓰는 규정이라 락을 잡으면 공개가 한 줄로 서기 때문이다.
	 * 지연 로딩 연관을 트랜잭션 밖에서 건드리지 않으려고 번호와 기본 규정 여부를 쿼리 한 번으로 읽는다.
	 */
	@Query("""
		SELECT c.id
		FROM Performance p
		JOIN p.cancelPolicy c
		WHERE p.id = :performanceId
		  AND c.businessId IS NOT NULL
		""")
	Optional<Long> findLockableCancelPolicyId(@Param("performanceId") Long performanceId);

	/**
	 * 작성 중일 때만 공개로 바꾼다. 공개 두 번이나 삭제와 겹칠 때 락이 듣지 않아도 DB 가 막는 마지막 방어선이다.
	 * 대량 UPDATE 는 updated_at 자동 갱신을 거치지 않아 직접 넣는다. 바뀐 행이 0이면 작성 중이 아니라는 뜻이다.
	 */
	@Modifying(clearAutomatically = true)
	@Query("""
		UPDATE Performance p
		SET p.status = com.squirrel.tickting.domain.performance.type.PerformanceStatus.PUBLISHED,
		    p.updatedAt = :now
		WHERE p.id = :id
		  AND p.status = com.squirrel.tickting.domain.performance.type.PerformanceStatus.DRAFT
		""")
	int publishIfDraft(@Param("id") Long id, @Param("now") LocalDateTime now);

	/**
	 * 공개 중일 때만 취소로 바꾸고 바뀐 행 수를 돌려준다. 락이 듣지 않아도 두 번 취소되지 않게 하는 상태 조건부 UPDATE 다.
	 * 일괄 UPDATE 는 updated_at 을 자동으로 채우지 못해 직접 넣고, 영속성 컨텍스트는 비우지 않는다.
	 */
	@Modifying(flushAutomatically = true)
	@Query("""
		UPDATE Performance p
		SET p.status = com.squirrel.tickting.domain.performance.type.PerformanceStatus.CANCELED,
		    p.updatedAt = :now
		WHERE p.id = :id
		  AND p.status = com.squirrel.tickting.domain.performance.type.PerformanceStatus.PUBLISHED
		""")
	int cancelIfPublished(@Param("id") Long id, @Param("now") LocalDateTime now);

	/** 작성 중일 때만 지우고 지운 행 수를 돌려준다. 확인한 뒤에 공개된 공연이 지워지지 않게 한다. */
	@Modifying(flushAutomatically = true)
	@Query("""
		DELETE FROM Performance p
		WHERE p.id = :id
		  AND p.status = com.squirrel.tickting.domain.performance.type.PerformanceStatus.DRAFT
		""")
	int deleteIfDraft(@Param("id") Long id);

	/** 공연장의 해당 상태 공연 번호(번호순). 공연장 사용 중지가 락 이름과 락 안의 재확인에 쓴다. */
	@Query("SELECT p.id FROM Performance p WHERE p.venueId = :venueId AND p.status = :status ORDER BY p.id")
	List<Long> findIdsByVenueIdAndStatus(@Param("venueId") Long venueId, @Param("status") PerformanceStatus status);

	List<Performance> findByVenueIdAndStatusOrderById(Long venueId, PerformanceStatus status);
}
