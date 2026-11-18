package com.squirrel.tickting.domain.performance.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.squirrel.tickting.domain.performance.dto.SellerGradeRequest;
import com.squirrel.tickting.domain.performance.infra.entity.Performance;
import com.squirrel.tickting.domain.performance.infra.repository.PerformanceRepository;
import com.squirrel.tickting.domain.performance.infra.repository.PerformanceScheduleRepository;
import com.squirrel.tickting.domain.performance.infra.repository.SeatGradeRepository;
import com.squirrel.tickting.domain.performance.infra.repository.ZoneGradeRepository;
import com.squirrel.tickting.domain.performance.type.ScheduleStatus;
import com.squirrel.tickting.domain.performance.type.SeatGradeName;
import com.squirrel.tickting.domain.performance.type.ZoneAssignmentStatus;
import com.squirrel.tickting.global.exception.BusinessException;
import com.squirrel.tickting.global.exception.code.CommonExceptionCode;
import com.squirrel.tickting.global.exception.code.PerformanceExceptionCode;
import com.squirrel.tickting.global.lock.DistributedLockExecutor;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class GradeServiceTest {

	private static final long BUSINESS_ID = 1L;
	private static final long PERFORMANCE_ID = 10L;

	@Mock
	private SeatGradeRepository seatGradeRepository;
	@Mock
	private ZoneGradeRepository zoneGradeRepository;
	@Mock
	private PerformanceRepository performanceRepository;
	@Mock
	private PerformanceScheduleRepository performanceScheduleRepository;
	@Mock
	private DistributedLockExecutor lockExecutor;

	@InjectMocks
	private GradeService gradeService;

	@Test
	@DisplayName("다른 사업체의 공연이거나 없는 공연이면 PERFORMANCE000 이고 등급·구역을 읽지 않는다")
	void notMine() {
		when(performanceRepository.findByIdAndBusinessId(PERFORMANCE_ID, BUSINESS_ID)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> gradeService.getOverview(BUSINESS_ID, PERFORMANCE_ID))
			.isInstanceOf(BusinessException.class)
			.extracting("exceptionCode").isEqualTo(PerformanceExceptionCode.PERFORMANCE_NOT_FOUND);
		verify(seatGradeRepository, never()).findByPerformanceIdOrderByPriceDescIdAsc(any());
		verify(zoneGradeRepository, never()).findAllWithGradeByPerformanceId(any());
	}

	@ParameterizedTest
	@ValueSource(booleans = {true, false})
	@DisplayName("locked 는 취소 회차를 뺀 좌석 생성 여부를 그대로 따른다")
	void lockedFollowsSeatsGeneratedExceptCanceled(boolean seatsGenerated) {
		Performance performance = Performance.builder().id(PERFORMANCE_ID).build();
		when(performanceRepository.findByIdAndBusinessId(PERFORMANCE_ID, BUSINESS_ID))
			.thenReturn(Optional.of(performance));
		when(seatGradeRepository.findByPerformanceIdOrderByPriceDescIdAsc(PERFORMANCE_ID)).thenReturn(List.of());
		when(zoneGradeRepository.findAllWithGradeByPerformanceId(PERFORMANCE_ID)).thenReturn(List.of());
		when(performanceScheduleRepository.existsSeatsGenerated(PERFORMANCE_ID, ScheduleStatus.CANCELED))
			.thenReturn(seatsGenerated);

		GradeOverview overview = gradeService.getOverview(BUSINESS_ID, PERFORMANCE_ID);

		assertThat(overview.performance()).isSameAs(performance);
		assertThat(overview.locked()).isEqualTo(seatsGenerated);
	}

	@Test
	@DisplayName("등급 추가에서 다른 사업체의 공연이거나 없는 공연이면 PERFORMANCE000 이고 같은 등급·좌석 생성 여부를 보지 않고 저장하지 않는다")
	void addNotMine() {
		when(performanceRepository.findByIdAndBusinessId(PERFORMANCE_ID, BUSINESS_ID)).thenReturn(Optional.empty());
		var request = new SellerGradeRequest.Save(SeatGradeName.R, 1000);

		assertThatThrownBy(() -> gradeService.add(BUSINESS_ID, PERFORMANCE_ID, request))
			.isInstanceOf(BusinessException.class)
			.extracting("exceptionCode").isEqualTo(PerformanceExceptionCode.PERFORMANCE_NOT_FOUND);
		verify(seatGradeRepository, never()).existsByPerformanceIdAndName(any(), any());
		verify(performanceScheduleRepository, never()).existsSeatsGenerated(any(), any());
		verify(seatGradeRepository, never()).save(any());
	}

	@Test
	@DisplayName("등급 수정에서 다른 사업체의 공연이거나 없는 공연이면 락을 잡기 전에 PERFORMANCE000 으로 끝난다")
	void updateNotMineBeforeLock() {
		when(performanceRepository.findByIdAndBusinessId(PERFORMANCE_ID, BUSINESS_ID)).thenReturn(Optional.empty());
		var request = new SellerGradeRequest.Save(SeatGradeName.R, 1000);

		assertThatThrownBy(() -> gradeService.update(BUSINESS_ID, PERFORMANCE_ID, 5L, request))
			.isInstanceOf(BusinessException.class)
			.extracting("exceptionCode").isEqualTo(PerformanceExceptionCode.PERFORMANCE_NOT_FOUND);
		verifyNoInteractions(lockExecutor);
		verify(seatGradeRepository, never()).findByIdAndPerformanceId(any(), any());
	}

	@Test
	@DisplayName("구역 설정에 같은 구역이 두 번 오면 COMMON000 이고 공연 조회·락 전에 끝난다")
	void zoneAssignDuplicatedZone() {
		var request = new SellerGradeRequest.ZoneAssign(List.of(
			new SellerGradeRequest.Item(1L, ZoneAssignmentStatus.UNSET, null),
			new SellerGradeRequest.Item(1L, ZoneAssignmentStatus.INTENTIONALLY_UNSOLD, null)));

		assertThatThrownBy(() -> gradeService.updateZoneGrades(BUSINESS_ID, PERFORMANCE_ID, request))
			.isInstanceOf(BusinessException.class)
			.extracting("exceptionCode").isEqualTo(CommonExceptionCode.INVALID_INPUT);
		verifyNoInteractions(performanceRepository, zoneGradeRepository, seatGradeRepository, lockExecutor);
	}

	@ParameterizedTest
	@CsvSource(value = {"ASSIGNED,", "UNSET,5", "INTENTIONALLY_UNSOLD,5"})
	@DisplayName("구역 상태와 gradeId 가 맞지 않으면 PERFORMANCE306 이고 공연 조회·락 전에 끝난다")
	void zoneAssignInvalidCombination(ZoneAssignmentStatus status, Long gradeId) {
		var request = new SellerGradeRequest.ZoneAssign(List.of(
			new SellerGradeRequest.Item(1L, ZoneAssignmentStatus.UNSET, null),
			new SellerGradeRequest.Item(2L, status, gradeId)));

		assertThatThrownBy(() -> gradeService.updateZoneGrades(BUSINESS_ID, PERFORMANCE_ID, request))
			.isInstanceOf(BusinessException.class)
			.extracting("exceptionCode").isEqualTo(PerformanceExceptionCode.ZONE_ASSIGNMENT_INVALID);
		verifyNoInteractions(performanceRepository, zoneGradeRepository, seatGradeRepository, lockExecutor);
	}

	@Test
	@DisplayName("구역 설정에서 다른 사업체의 공연이거나 없는 공연이면 락을 잡기 전에 PERFORMANCE000 으로 끝난다")
	void zoneAssignNotMineBeforeLock() {
		when(performanceRepository.findByIdAndBusinessId(PERFORMANCE_ID, BUSINESS_ID)).thenReturn(Optional.empty());
		var request = new SellerGradeRequest.ZoneAssign(List.of(
			new SellerGradeRequest.Item(1L, ZoneAssignmentStatus.ASSIGNED, 5L)));

		assertThatThrownBy(() -> gradeService.updateZoneGrades(BUSINESS_ID, PERFORMANCE_ID, request))
			.isInstanceOf(BusinessException.class)
			.extracting("exceptionCode").isEqualTo(PerformanceExceptionCode.PERFORMANCE_NOT_FOUND);
		verifyNoInteractions(lockExecutor, zoneGradeRepository);
	}
}
