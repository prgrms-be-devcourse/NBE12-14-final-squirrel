package com.squirrel.tickting.domain.payment.infra.repository;

import com.squirrel.tickting.domain.payment.infra.entity.Refund;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RefundRepository extends JpaRepository<Refund, Long> {

	/** 다시 보낼 때가 된 환불. 오래 기다린 것부터. (부분 인덱스 idx_refund_retry) */
	@Query(value = """
		SELECT id FROM refund
		WHERE status IN ('FAILED', 'PROCESSING')
		  AND last_attempted_at < :cutoff
		  AND (status = 'PROCESSING' OR retry_count < :maxCount)
		ORDER BY last_attempted_at
		LIMIT :limit
		""", nativeQuery = true)
	List<Long> findRetryDueIds(@Param("cutoff") LocalDateTime cutoff, @Param("maxCount") int maxCount,
		@Param("limit") int limit);

	/**
	 * 재시도할 환불을 가져간다. 조건을 UPDATE 에 함께 걸어서, 여러 서버가 같은 환불을 동시에 가져가려 해도 한 서버만 1행을 얻는다.
	 * (먼저 바꾼 서버가 last_attempted_at 을 지금으로 올려서 뒤에 온 UPDATE 는 조건에 맞지 않는다)
	 */
	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query("""
		UPDATE Refund r
		   SET r.status = com.squirrel.tickting.domain.payment.type.RefundStatus.PROCESSING,
		       r.retryCount = r.retryCount + 1,
		       r.lastAttemptedAt = :now
		 WHERE r.id = :id
		   AND r.lastAttemptedAt < :cutoff
		   AND (r.status = com.squirrel.tickting.domain.payment.type.RefundStatus.PROCESSING
		        OR (r.status = com.squirrel.tickting.domain.payment.type.RefundStatus.FAILED
		            AND r.retryCount < :maxCount))
		""")
	int claimForRetry(@Param("id") Long id, @Param("now") LocalDateTime now, @Param("cutoff") LocalDateTime cutoff,
		@Param("maxCount") int maxCount);

	/** 토스에 보낼 결제 키가 필요해서 결제를 함께 읽는다. 트랜잭션이 끝난 뒤 토스를 부르므로 지연 로딩에 기댈 수 없다. */
	@Query("SELECT r FROM Refund r JOIN FETCH r.payment WHERE r.id = :id")
	Optional<Refund> findWithPaymentById(@Param("id") Long id);
}
