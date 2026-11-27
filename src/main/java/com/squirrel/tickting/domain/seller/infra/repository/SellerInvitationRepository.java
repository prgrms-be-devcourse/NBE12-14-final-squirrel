package com.squirrel.tickting.domain.seller.infra.repository;

import com.squirrel.tickting.domain.seller.infra.entity.SellerInvitation;
import java.time.LocalDateTime;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SellerInvitationRepository extends JpaRepository<SellerInvitation, Long> {

	/**
	 * 같은 회사가 같은 이메일로 보낸, 아직 쓸 수 있는 초대를 모두 무효로 만든다. 다시 초대하면 가장 최근 링크만 살아 있게 하려는 것이다.
	 * 만료 시각을 지금으로 당겨서 "만료된 링크"와 같게 다룬다. (컬럼을 늘리지 않으려고)
	 */
	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query("""
		UPDATE SellerInvitation i
		   SET i.expiresAt = :now
		 WHERE i.business.id = :businessId
		   AND i.email = :email
		   AND i.usedAt IS NULL
		   AND i.expiresAt > :now
		""")
	int expirePending(@Param("businessId") Long businessId, @Param("email") String email,
		@Param("now") LocalDateTime now);

	/** 아직 쓸 수 있는 초대를 토큰 해시로 찾는다. 링크 확인 화면에 회사 이름을 보여 주려고 사업체를 함께 읽는다. */
	@Query("""
		SELECT i
		  FROM SellerInvitation i
		  JOIN FETCH i.business
		 WHERE i.token = :token
		   AND i.usedAt IS NULL
		   AND i.expiresAt > :now
		""")
	Optional<SellerInvitation> findUsableByToken(@Param("token") String token, @Param("now") LocalDateTime now);

	/**
	 * 초대를 "썼음"으로 기록한다. 아직 쓰지 않았고 만료되지 않은 초대만 바꿔서, 같은 링크로 동시에 가입해도 한 요청만 1행을 얻는다.
	 * 먼저 바꾼 요청이 커밋할 때까지 뒤에 온 UPDATE 는 행 잠금을 기다렸다가 조건을 다시 보고 0행이 된다.
	 */
	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query("""
		UPDATE SellerInvitation i
		   SET i.usedAt = :now
		 WHERE i.id = :id
		   AND i.usedAt IS NULL
		   AND i.expiresAt > :now
		""")
	int markUsed(@Param("id") Long id, @Param("now") LocalDateTime now);

	/** 초대 한 건을 무효로 만든다. 만든 직후 메일 발송을 접수하지 못해 되돌릴 때 쓴다. */
	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query("""
		UPDATE SellerInvitation i
		   SET i.expiresAt = :now
		 WHERE i.id = :id
		   AND i.usedAt IS NULL
		""")
	int expire(@Param("id") Long id, @Param("now") LocalDateTime now);
}
