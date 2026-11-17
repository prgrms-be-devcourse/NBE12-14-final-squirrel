package com.squirrel.tickting.domain.performance.infra.repository;

import com.squirrel.tickting.domain.performance.infra.entity.SeatGrade;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface SeatGradeRepository extends JpaRepository<SeatGrade, Long> {

	/** 공연의 모든 등급. 예매자 화면(findAssignedGrades)과 같이 비싼 등급부터, 가격이 같으면 번호순이다. */
	List<SeatGrade> findByPerformanceIdOrderByPriceDescIdAsc(Long performanceId);

	/** 공연에 만든 등급 전부. 예매 때 좌석의 등급 번호를 이름으로 바꾸는 데 쓴다. */
	List<SeatGrade> findByPerformanceId(Long performanceId);

	/**
	 * 같은 공연에 대소문자와 공백을 무시하면 같은 이름의 등급이 있는가.
	 * 공백은 PostgreSQL 의 \s(띄어쓰기·탭·줄바꿈·전각 공백)에 NBSP(U+00A0)를 더한 것이다. \s 는 NBSP 를 잡지 않는데,
	 * NBSP 는 웹·문서에서 붙여 넣으면 섞여 들어오고 화면에서는 띄어쓰기와 똑같이 보여서 같은 이름으로 본다.
	 * 나중에 만들 UNIQUE 식 인덱스와 기준이 같도록 Java 가 아니라 DB 의 식으로 비교한다. (JPQL 에 정규식 함수가 없어 네이티브)
	 */
	@Query(value = """
		SELECT EXISTS (
		    SELECT 1
		    FROM seat_grade g
		    WHERE g.performance_id = :performanceId
		      AND LOWER(REGEXP_REPLACE(g.name, '[\\s\\u00A0]', '', 'g'))
		          = LOWER(REGEXP_REPLACE(:name, '[\\s\\u00A0]', '', 'g'))
		)
		""", nativeQuery = true)
	boolean existsSameName(Long performanceId, String name);

	/** 등급 수정용. existsSameName 과 같은 기준이고 고치는 등급 자신은 뺀다. (자기 이름의 대소문자·띄어쓰기만 바꾸는 것은 허용) */
	@Query(value = """
		SELECT EXISTS (
		    SELECT 1
		    FROM seat_grade g
		    WHERE g.performance_id = :performanceId
		      AND g.id <> :gradeId
		      AND LOWER(REGEXP_REPLACE(g.name, '[\\s\\u00A0]', '', 'g'))
		          = LOWER(REGEXP_REPLACE(:name, '[\\s\\u00A0]', '', 'g'))
		)
		""", nativeQuery = true)
	boolean existsSameNameExcept(Long performanceId, String name, Long gradeId);

	/** 이 공연의 등급. 다른 공연의 등급 번호는 없는 등급과 같게 비어 있다. */
	Optional<SeatGrade> findByIdAndPerformanceId(Long id, Long performanceId);
}
