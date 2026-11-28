package com.squirrel.tickting.domain.user.infra.repository;

import com.squirrel.tickting.domain.user.infra.entity.UserMember;
import java.time.LocalDateTime;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface UserMemberRepository extends JpaRepository<UserMember, Long> {

	Optional<UserMember> findByEmailAndDeletedAtIsNull(String email);

	Optional<UserMember> findByIdAndDeletedAtIsNull(Long id);

	boolean existsByEmailAndDeletedAtIsNull(String email);

	/**
	 * 비밀번호 재설정. 탈퇴하지 않은 회원만 바꾸는 조건부 UPDATE 다. 엔티티를 읽어 고치면 같은 순간의 탈퇴(비밀번호를 지움)를 덮어써서
	 * 탈퇴한 회원에게 진짜 비밀번호가 남을 수 있어서 DB 에서 조건을 걸어 바꾼다. 바뀐 행 수를 돌려준다. (0 이면 그 사이 탈퇴한 회원)
	 * 해시 계산(수십 ms)을 마친 뒤에 부르고 DB 연결은 이 UPDATE 하나 동안만 쥐려고 호출하는 서비스는 트랜잭션을 열지 않는다.
	 */
	@Transactional
	@Modifying
	@Query("""
		UPDATE UserMember u
		SET u.password = :password, u.updatedAt = :now
		WHERE u.id = :id AND u.deletedAt IS NULL
		""")
	int updatePassword(@Param("id") Long id, @Param("password") String password, @Param("now") LocalDateTime now);

	/**
	 * 로그인한 상태의 비밀번호 변경. 확인에 쓴 해시가 그대로일 때만 바꾸는 조건부 UPDATE 다. 같은 계정을 동시에 바꾸면 한 요청만 1행을 얻는다.
	 * 0 이면 그 사이 다른 변경·재설정이나 탈퇴가 먼저 된 것이다. (트랜잭션은 이 UPDATE 하나 동안만 연다)
	 */
	@Transactional
	@Modifying
	@Query("""
		UPDATE UserMember u
		SET u.password = :newPassword, u.updatedAt = :now
		WHERE u.id = :id AND u.password = :oldPassword AND u.deletedAt IS NULL
		""")
	int updatePasswordIfUnchanged(@Param("id") Long id, @Param("oldPassword") String oldPassword,
		@Param("newPassword") String newPassword, @Param("now") LocalDateTime now);
}
