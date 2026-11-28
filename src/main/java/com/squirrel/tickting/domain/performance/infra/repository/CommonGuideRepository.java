package com.squirrel.tickting.domain.performance.infra.repository;

import com.squirrel.tickting.domain.performance.infra.entity.CommonGuide;
import com.squirrel.tickting.domain.performance.type.GuideType;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CommonGuideRepository extends JpaRepository<CommonGuide, Long> {

	/** 유형 순서대로. 관리자 목록에 쓴다. */
	@Query("SELECT g FROM CommonGuide g ORDER BY g.type")
	List<CommonGuide> findAllOrderByType();

	/**
	 * 수정할 안내를 잠그고 읽는다. 두 관리자가 동시에 고쳐도 한 명씩 차례로 바꿔서, 감사 로그의 "바뀌기 전 내용"이 실제 직전 내용과 같다.
	 * 유형마다 한 행(uq_common_guide_type)이라 잠그는 행은 하나뿐이다.
	 */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("SELECT g FROM CommonGuide g WHERE g.type = :type")
	Optional<CommonGuide> findForUpdate(@Param("type") GuideType type);
}
