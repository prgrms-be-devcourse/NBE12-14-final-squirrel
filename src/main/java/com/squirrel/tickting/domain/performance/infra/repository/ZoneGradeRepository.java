package com.squirrel.tickting.domain.performance.infra.repository;

import com.squirrel.tickting.domain.performance.infra.entity.SeatGrade;
import com.squirrel.tickting.domain.performance.infra.entity.ZoneGrade;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ZoneGradeRepository extends JpaRepository<ZoneGrade, Long> {

	/**
	 * 공연의 구역 설정 전체를 등급과 함께 읽는다. 등급이 없는 구역(UNSET, INTENTIONALLY_UNSOLD)도 나오도록 LEFT JOIN 이다.
	 * 정렬은 공연장 구역의 순서(sort_order)가 필요해서 여기서 하지 않고 공연장 정보와 합치는 쪽이 한다.
	 */
	@Query("SELECT z FROM ZoneGrade z LEFT JOIN FETCH z.grade WHERE z.performance.id = :performanceId")
	List<ZoneGrade> findAllWithGradeByPerformanceId(@Param("performanceId") Long performanceId);

	/** 구역에 지정(ASSIGNED)된 등급. 전석과 다른 등급이 함께 지정되는지 보는 데 쓴다. (여러 구역에 붙은 등급도 한 번) */
	@Query("""
		SELECT DISTINCT zg.grade
		FROM ZoneGrade zg
		WHERE zg.performance.id = :performanceId
		  AND zg.assignmentStatus = com.squirrel.tickting.domain.performance.type.ZoneAssignmentStatus.ASSIGNED
		""")
	List<SeatGrade> findAssignedGrades(@Param("performanceId") Long performanceId);
}
