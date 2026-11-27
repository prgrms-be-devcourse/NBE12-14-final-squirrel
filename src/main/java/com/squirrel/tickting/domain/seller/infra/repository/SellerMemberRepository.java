package com.squirrel.tickting.domain.seller.infra.repository;

import com.squirrel.tickting.domain.seller.infra.entity.SellerMember;
import com.squirrel.tickting.domain.seller.type.SellerMemberStatus;
import com.squirrel.tickting.domain.seller.type.SellerRole;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface SellerMemberRepository extends JpaRepository<SellerMember, Long> {

	/**
	 * 이메일의 판매자 계정들. 회사를 옮긴 사람은 회사마다 계정이 하나씩 있을 수 있어서 여러 개가 나온다.
	 * 활성 계정이 먼저(이메일당 하나, uq_seller_email_active), 그다음 최근 계정 순이다. 로그인·비밀번호 재설정은 맨 앞 계정을 쓴다.
	 * 로그인이 사업체 상태를 같이 확인하므로 사업체를 함께 읽는다.
	 */
	@Query("""
		SELECT m
		FROM SellerMember m
		JOIN FETCH m.business
		WHERE m.email = :email
		  AND m.deletedAt IS NULL
		ORDER BY CASE WHEN m.status = com.squirrel.tickting.domain.seller.type.SellerMemberStatus.ACTIVE THEN 0 ELSE 1 END,
		         m.id DESC
		""")
	List<SellerMember> findAccountsByEmail(@Param("email") String email);

	/** 이 회사의 이 이메일 계정. 같은 회사 안에서는 이메일당 하나다. (uq_seller_business_email) */
	Optional<SellerMember> findByBusinessIdAndEmailAndDeletedAtIsNull(Long businessId, String email);

	/**
	 * 같은 회사의 비활성 계정을 초대 링크로 되살린다. 본인이 새로 입력한 비밀번호·이름·휴대폰으로 바꾼다.
	 * 비활성일 때만 바꾸는 조건부 UPDATE 라서, 그 사이 대표가 복구했으면 0행이다.
	 * 다른 회사에서 같은 이메일이 먼저 활성화되면 uq_seller_email_active 가 막는다.
	 */
	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query("""
		UPDATE SellerMember m
		   SET m.status = com.squirrel.tickting.domain.seller.type.SellerMemberStatus.ACTIVE,
		       m.password = :password,
		       m.name = :name,
		       m.phone = :phone,
		       m.updatedAt = :now
		 WHERE m.id = :id
		   AND m.status = com.squirrel.tickting.domain.seller.type.SellerMemberStatus.INACTIVE
		   AND m.deletedAt IS NULL
		""")
	int reactivateWithProfile(@Param("id") Long id, @Param("password") String password, @Param("name") String name,
		@Param("phone") String phone, @Param("now") LocalDateTime now);

	/** 이 이메일의 계정이 이 상태로 있는가. 활성 계정은 이메일당 하나라서 가입·초대 전에 ACTIVE 로 묻는다. */
	boolean existsByEmailAndStatusAndDeletedAtIsNull(String email, SellerMemberStatus status);

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
