package com.squirrel.tickting.domain.seller.infra.repository;

import com.squirrel.tickting.domain.seller.infra.entity.SellerMember;
import com.squirrel.tickting.domain.seller.type.SellerRole;
import java.time.LocalDateTime;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface SellerMemberRepository extends JpaRepository<SellerMember, Long> {

	/** 로그인용. 사업체 상태를 같이 확인하므로 한 번에 가져온다. */
	@Query("""
		SELECT m
		FROM SellerMember m
		JOIN FETCH m.business
		WHERE m.email = :email
		  AND m.deletedAt IS NULL
		""")
	Optional<SellerMember> findForLoginByEmail(@Param("email") String email);

	@Query("""
		SELECT m
		FROM SellerMember m
		JOIN FETCH m.business
		WHERE m.id = :id
		  AND m.deletedAt IS NULL
		""")
	Optional<SellerMember> findForLoginById(@Param("id") Long id);

	boolean existsByEmailAndDeletedAtIsNull(String email);

	/** 비밀번호 재설정용. 사업체 상태와 무관하게 계정만 본다. (승인 대기·반려·정지 사업체의 대표도 비밀번호는 바꿀 수 있다) */
	Optional<SellerMember> findByEmailAndDeletedAtIsNull(String email);

	Optional<SellerMember> findByIdAndDeletedAtIsNull(Long id);

	/**
	 * 비밀번호 재설정. 삭제되지 않은 계정만 바꾸는 조건부 UPDATE 다. (예매자의 updatePassword 와 같은 이유)
	 * 바뀐 행 수를 돌려준다. 0 이면 그 사이 삭제된 계정이다.
	 */
	@Transactional
	@Modifying
	@Query("""
		UPDATE SellerMember m
		SET m.password = :password, m.updatedAt = :now
		WHERE m.id = :id AND m.deletedAt IS NULL
		""")
	int updatePassword(@Param("id") Long id, @Param("password") String password, @Param("now") LocalDateTime now);

	/** 관리자 상세용. 사업체의 대표(신청자) 계정. 대표는 사업체마다 1명이다. */
	@Query("""
		SELECT m
		FROM SellerMember m
		WHERE m.business.id = :businessId
		  AND m.role = :role
		  AND m.deletedAt IS NULL
		""")
	Optional<SellerMember> findByBusinessIdAndRole(@Param("businessId") Long businessId,
		@Param("role") SellerRole role);
}
