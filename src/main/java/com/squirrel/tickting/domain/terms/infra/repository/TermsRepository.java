package com.squirrel.tickting.domain.terms.infra.repository;

import com.squirrel.tickting.domain.terms.infra.entity.Terms;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface TermsRepository extends JpaRepository<Terms, Long> {

	/** 종류마다 가장 최근에 추가된 약관. 개정된 약관은 새 버전 행이므로 id 가 가장 큰 행이 최신이다. */
	@Query("""
		SELECT t
		FROM Terms t
		WHERE t.id IN (SELECT MAX(t2.id) FROM Terms t2 GROUP BY t2.type)
		""")
	List<Terms> findLatestOfEachType();
}
