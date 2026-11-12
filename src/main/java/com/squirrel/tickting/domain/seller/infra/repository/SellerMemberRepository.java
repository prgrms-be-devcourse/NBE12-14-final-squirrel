package com.squirrel.tickting.domain.seller.infra.repository;

import com.squirrel.tickting.domain.seller.infra.entity.SellerMember;
import com.squirrel.tickting.domain.seller.type.SellerRole;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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
