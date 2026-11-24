package com.squirrel.tickting.domain.seller.infra.repository;

import com.squirrel.tickting.domain.seller.infra.entity.SellerBusiness;
import com.squirrel.tickting.domain.seller.type.SellerBusinessStatus;
import java.util.Collection;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SellerBusinessRepository extends JpaRepository<SellerBusiness, Long> {

	boolean existsByBusinessNumber(String businessNumber);

	/**
	 * 관리자의 사업체 검색. 상호·대표자명·사업자번호에서 부분 일치로 찾고, 최신 신청이 먼저 나온다.
	 * 조건이 비는 경우(null)를 쿼리 안에서 처리하지 않도록 호출하는 쪽이 항상 값을 채워서 넘긴다.
	 *   - statuses: 비지 않은 상태 목록 (전체 검색이면 모든 상태)
	 *   - pattern : LikeUtil.containsPattern 으로 만든 값. 소문자이고 %, _ 는 '!' 로 이스케이프되어 있다.
	 *               그래서 아래 LIKE 에 ESCAPE '!' 가 꼭 필요하다. (LikeUtil.ESCAPE 와 같은 값)
	 * 정렬은 쿼리에 고정했으므로 pageable 에는 정렬을 넣지 않는다.
	 */
	@Query(value = """
		SELECT b
		FROM SellerBusiness b
		WHERE b.status IN :statuses
		  AND (LOWER(b.companyName) LIKE :pattern ESCAPE '!'
		       OR LOWER(b.representativeName) LIKE :pattern ESCAPE '!'
		       OR b.businessNumber LIKE :pattern ESCAPE '!')
		ORDER BY b.createdAt DESC, b.id DESC
		""",
		countQuery = """
			SELECT COUNT(b)
			FROM SellerBusiness b
			WHERE b.status IN :statuses
			  AND (LOWER(b.companyName) LIKE :pattern ESCAPE '!'
			       OR LOWER(b.representativeName) LIKE :pattern ESCAPE '!'
			       OR b.businessNumber LIKE :pattern ESCAPE '!')
			""")
	Page<SellerBusiness> search(
		@Param("statuses") Collection<SellerBusinessStatus> statuses,
		@Param("pattern") String pattern,
		Pageable pageable
	);
}
