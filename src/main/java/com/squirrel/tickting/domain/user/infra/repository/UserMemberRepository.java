package com.squirrel.tickting.domain.user.infra.repository;

import com.squirrel.tickting.domain.user.infra.entity.UserMember;
import java.time.LocalDateTime;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserMemberRepository extends JpaRepository<UserMember, Long> {

	Optional<UserMember> findByEmailAndDeletedAtIsNull(String email);

	Optional<UserMember> findByIdAndDeletedAtIsNull(Long id);

	boolean existsByEmailAndDeletedAtIsNull(String email);

	/** 탈퇴하지 않은 회원이 이메일 인증을 마쳤는지. 예매하기(대기열 입장)마다 부르므로 엔티티를 읽지 않고 한 건만 확인한다. */
	boolean existsByIdAndEmailVerifiedAtIsNotNullAndDeletedAtIsNull(Long id);

	/**
	 * 이메일 인증 시각을 남긴다. 아직 인증하지 않은 회원만 바꾸는 조건부 UPDATE 라서, 같은 회원의 확인이 겹쳐도 한 번만 반영된다.
	 * 바뀐 행 수를 돌려준다. (0 이면 이미 인증했거나 탈퇴한 회원)
	 */
	@Modifying
	@Query("""
		UPDATE UserMember u
		SET u.emailVerifiedAt = :now, u.updatedAt = :now
		WHERE u.id = :id AND u.emailVerifiedAt IS NULL AND u.deletedAt IS NULL
		""")
	int markEmailVerified(@Param("id") Long id, @Param("now") LocalDateTime now);
}
