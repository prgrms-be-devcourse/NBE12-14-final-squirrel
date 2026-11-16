package com.squirrel.tickting.domain.performance.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.squirrel.tickting.domain.performance.infra.entity.Performance;
import com.squirrel.tickting.domain.performance.infra.repository.PerformanceImageRepository;
import com.squirrel.tickting.domain.performance.infra.repository.PerformanceRepository;
import com.squirrel.tickting.domain.performance.infra.repository.PerformanceScheduleRepository;
import com.squirrel.tickting.domain.performance.type.Genre;
import com.squirrel.tickting.domain.performance.type.PerformanceStatus;
import com.squirrel.tickting.global.exception.BusinessException;
import com.squirrel.tickting.global.exception.code.CommonExceptionCode;
import com.squirrel.tickting.global.exception.code.PerformanceExceptionCode;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PerformanceServiceTest {

	private static final LocalDateTime OPEN = LocalDateTime.of(2026, 12, 1, 10, 0);

	@Mock
	private PerformanceRepository performanceRepository;
	@Mock
	private PerformanceImageRepository performanceImageRepository;
	@Mock
	private PerformanceScheduleRepository performanceScheduleRepository;

	@InjectMocks
	private PerformanceService performanceService;

	private PerformanceSaveCommand.PerformanceSaveCommandBuilder command() {
		return PerformanceSaveCommand.builder()
			.title("가상 콘서트")
			.genre(Genre.CONCERT)
			.ageLimit(12)
			.runningTime(150)
			.posterUrl("https://cdn.example.com/posters/12.jpg");
	}

	private Performance create(PerformanceSaveCommand command) {
		return performanceService.create(7L, 3L, 1L, null, command);
	}

	@Test
	@DisplayName("작성 중(DRAFT)으로 저장하고 사업체·등록자·공연장은 인자로 받은 값을 쓴다")
	void createsDraft() {
		when(performanceRepository.save(any(Performance.class))).thenAnswer(invocation -> invocation.getArgument(0));

		Performance performance = create(command().build());

		assertThat(performance.getStatus()).isEqualTo(PerformanceStatus.DRAFT);
		assertThat(performance.getBusinessId()).isEqualTo(7L);
		assertThat(performance.getCreatedBy()).isEqualTo(3L);
		assertThat(performance.getVenueId()).isEqualTo(1L);
	}

	@Test
	@DisplayName("판매 종료가 오픈보다 이르거나 같으면 PERFORMANCE001 이고 저장하지 않는다")
	void salePeriodMustMoveForward() {
		for (LocalDateTime closeAt : List.of(OPEN.minusMinutes(1), OPEN)) {
			assertThatThrownBy(() -> create(command().saleOpenAt(OPEN).saleCloseAt(closeAt).build()))
				.isInstanceOf(BusinessException.class)
				.extracting("exceptionCode").isEqualTo(PerformanceExceptionCode.SALE_PERIOD_INVALID);
		}
		verify(performanceRepository, never()).save(any());
	}

	@Test
	@DisplayName("판매 오픈·종료 중 하나만 있거나 둘 다 없으면 순서를 보지 않는다")
	void salePeriodCheckedOnlyWhenBothPresent() {
		when(performanceRepository.save(any(Performance.class))).thenAnswer(invocation -> invocation.getArgument(0));

		create(command().saleOpenAt(OPEN).build());
		create(command().saleCloseAt(OPEN).build());
		create(command().build());
		create(command().saleOpenAt(OPEN).saleCloseAt(OPEN.plusSeconds(1)).build());
	}

	@Test
	@DisplayName("이미지 순서값이 겹치면 COMMON000 이고 저장하지 않는다")
	void duplicatedImageOrder() {
		PerformanceSaveCommand command = command().images(List.of(
			new PerformanceSaveCommand.Image("https://cdn.example.com/a.jpg", 1),
			new PerformanceSaveCommand.Image("https://cdn.example.com/b.jpg", 1))).build();

		assertThatThrownBy(() -> create(command))
			.isInstanceOf(BusinessException.class)
			.extracting("exceptionCode").isEqualTo(CommonExceptionCode.INVALID_INPUT);
		verify(performanceRepository, never()).save(any());
	}
}
