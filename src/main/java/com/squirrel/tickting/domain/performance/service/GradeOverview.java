package com.squirrel.tickting.domain.performance.service;

import com.squirrel.tickting.domain.performance.infra.entity.Performance;
import com.squirrel.tickting.domain.performance.infra.entity.SeatGrade;
import com.squirrel.tickting.domain.performance.infra.entity.ZoneGrade;
import java.util.List;

/**
 * 등급·구역 조회(SLR-08) 결과. 공연과 등급·구역 설정을 서비스 트랜잭션 안에서 함께 읽어 담는다.
 * 구역 이름·층·좌석 수는 공연장 도메인 값이라 여기 없고 파사드가 공연장 서비스에서 받아 합친다.
 */
public record GradeOverview(
	Performance performance,
	List<SeatGrade> grades,
	List<ZoneGrade> zoneGrades,
	boolean locked
) {
}
