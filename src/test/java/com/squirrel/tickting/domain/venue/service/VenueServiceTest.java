package com.squirrel.tickting.domain.venue.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.squirrel.tickting.domain.venue.infra.entity.Venue;
import com.squirrel.tickting.domain.venue.infra.entity.VenueZone;
import com.squirrel.tickting.domain.venue.infra.repository.VenueRepository;
import com.squirrel.tickting.domain.venue.infra.repository.VenueSeatRepository;
import com.squirrel.tickting.domain.venue.infra.repository.VenueZoneRepository;
import com.squirrel.tickting.domain.venue.type.VenueStatus;
import com.squirrel.tickting.global.exception.BusinessException;
import com.squirrel.tickting.global.exception.code.VenueExceptionCode;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class VenueServiceTest {

	@Mock
	private VenueRepository venueRepository;
	@Mock
	private VenueZoneRepository venueZoneRepository;
	@Mock
	private VenueSeatRepository venueSeatRepository;

	@InjectMocks
	private VenueService venueService;

	@Test
	@DisplayName("사용 중인 공연장이면 구역 번호 목록을 돌려준다")
	void activeVenueReturnsZoneIds() {
		when(venueRepository.findById(1L)).thenReturn(Optional.of(Venue.builder().status(VenueStatus.ACTIVE).build()));
		when(venueZoneRepository.findIdsByVenueId(1L)).thenReturn(List.of(11L, 12L));

		assertThat(venueService.getActiveZoneIds(1L)).containsExactly(11L, 12L);
	}

	@Test
	@DisplayName("없는 공연장은 404 VENUE000 이다")
	void missingVenue() {
		when(venueRepository.findById(1L)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> venueService.getActiveZoneIds(1L))
			.isInstanceOf(BusinessException.class)
			.extracting("exceptionCode").isEqualTo(VenueExceptionCode.VENUE_NOT_FOUND);
	}

	@Test
	@DisplayName("사용 중지된 공연장은 400 VENUE001 이고 구역을 읽지 않는다")
	void inactiveVenue() {
		when(venueRepository.findById(1L))
			.thenReturn(Optional.of(Venue.builder().status(VenueStatus.INACTIVE).build()));

		assertThatThrownBy(() -> venueService.getActiveZoneIds(1L))
			.isInstanceOf(BusinessException.class)
			.extracting("exceptionCode").isEqualTo(VenueExceptionCode.VENUE_INACTIVE);
		verify(venueZoneRepository, never()).findIdsByVenueId(any());
	}

	@Test
	@DisplayName("구역 정보에 구역별 좌석 수를 붙이고, 좌석이 없는 구역은 0석이다")
	void zoneInfosWithSeatCounts() {
		when(venueZoneRepository.findByVenueId(1L)).thenReturn(List.of(
			VenueZone.builder().id(11L).name("A구역").floor("1층").sortOrder(1).build(),
			VenueZone.builder().id(12L).name("B구역").floor("2층").sortOrder(2).build()));
		when(venueSeatRepository.countByZone(1L)).thenReturn(List.of(seatCount(11L, 120L)));

		Map<Long, VenueZoneInfo> zoneInfos = venueService.getZoneInfos(1L);

		assertThat(zoneInfos).containsOnlyKeys(11L, 12L);
		assertThat(zoneInfos.get(11L)).isEqualTo(new VenueZoneInfo(11L, "A구역", "1층", 1, 120L));
		assertThat(zoneInfos.get(12L).seatCount()).isZero();
	}

	private VenueSeatRepository.ZoneSeatCount seatCount(Long zoneId, long count) {
		return new VenueSeatRepository.ZoneSeatCount() {
			@Override
			public Long getZoneId() {
				return zoneId;
			}

			@Override
			public long getSeatCount() {
				return count;
			}
		};
	}
}
