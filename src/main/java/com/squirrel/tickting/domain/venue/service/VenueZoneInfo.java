package com.squirrel.tickting.domain.venue.service;

/** 공연장 구역 하나의 표시 정보와 좌석 수. 다른 도메인(공연의 구역 설정)이 구역 번호로 합쳐 쓴다. */
public record VenueZoneInfo(
	Long zoneId,
	String name,
	String floor,
	int sortOrder,
	long seatCount
) {
}
