package com.squirrel.tickting.domain.seller.infra.repository;

import com.squirrel.tickting.domain.seller.infra.entity.SellerMember;
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
}
