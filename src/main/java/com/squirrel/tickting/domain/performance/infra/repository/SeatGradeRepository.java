package com.squirrel.tickting.domain.performance.infra.repository;

import com.squirrel.tickting.domain.performance.infra.entity.SeatGrade;
import com.squirrel.tickting.domain.performance.type.SeatGradeName;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SeatGradeRepository extends JpaRepository<SeatGrade, Long> {

	/** 공연의 모든 등급. 예매자 화면(findAssignedGrades)과 같이 비싼 등급부터, 가격이 같으면 번호순이다. */
	List<SeatGrade> findByPerformanceIdOrderByPriceDescIdAsc(Long performanceId);

	/** 공연에 만든 등급 전부. 예매 때 좌석의 등급 번호를 이름으로 바꾸는 데 쓴다. */
	List<SeatGrade> findByPerformanceId(Long performanceId);

	/** 같은 공연에 같은 등급이 있는가. 동시 추가의 마지막 방어선인 UNIQUE (performance_id, name) 는 아직 TODO 다. */
	boolean existsByPerformanceIdAndName(Long performanceId, SeatGradeName name);

	/** 등급 수정용. 고치는 등급 자신은 빼서 가격만 바꾸는 수정은 허용한다. */
	boolean existsByPerformanceIdAndNameAndIdNot(Long performanceId, SeatGradeName name, Long gradeId);

	/** 이 공연의 등급. 다른 공연의 등급 번호는 없는 등급과 같게 비어 있다. */
	Optional<SeatGrade> findByIdAndPerformanceId(Long id, Long performanceId);
}
