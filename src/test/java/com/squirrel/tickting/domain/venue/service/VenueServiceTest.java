package com.squirrel.tickting.domain.venue.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.squirrel.tickting.domain.venue.infra.entity.Venue;
import com.squirrel.tickting.domain.venue.infra.repository.VenueRepository;
import com.squirrel.tickting.domain.venue.infra.repository.VenueSeatRepository;
import com.squirrel.tickting.domain.venue.infra.repository.VenueZoneRepository;
import com.squirrel.tickting.domain.venue.type.VenueStatus;
import com.squirrel.tickting.global.exception.BusinessException;
import com.squirrel.tickting.global.exception.code.VenueExceptionCode;
import java.util.List;
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
}
