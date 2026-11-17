package com.squirrel.tickting.domain.performance.infra.repository;

import com.squirrel.tickting.domain.performance.infra.entity.SeatGrade;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SeatGradeRepository extends JpaRepository<SeatGrade, Long> {

	/** 공연에 만든 등급 전부. 예매 때 좌석의 등급 번호를 이름으로 바꾸는 데 쓴다. */
	List<SeatGrade> findByPerformanceId(Long performanceId);
}
