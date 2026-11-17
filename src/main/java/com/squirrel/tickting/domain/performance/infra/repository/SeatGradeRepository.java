package com.squirrel.tickting.domain.performance.infra.repository;

import com.squirrel.tickting.domain.performance.infra.entity.SeatGrade;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SeatGradeRepository extends JpaRepository<SeatGrade, Long> {

	/** 공연의 모든 등급. 예매자 화면(findAssignedGrades)과 같이 비싼 등급부터, 가격이 같으면 번호순이다. */
	List<SeatGrade> findByPerformanceIdOrderByPriceDescIdAsc(Long performanceId);

	/** 공연에 만든 등급 전부. 예매 때 좌석의 등급 번호를 이름으로 바꾸는 데 쓴다. */
	List<SeatGrade> findByPerformanceId(Long performanceId);
}
