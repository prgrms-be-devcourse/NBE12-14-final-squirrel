package com.squirrel.tickting.domain.performance.dto;

import com.squirrel.tickting.domain.performance.infra.entity.Performance;
import com.squirrel.tickting.domain.performance.infra.entity.PerformanceImage;
import com.squirrel.tickting.domain.performance.infra.entity.PerformanceSchedule;
import com.squirrel.tickting.domain.performance.infra.entity.SchedulePostponement;
import com.squirrel.tickting.domain.performance.infra.repository.ScheduleSeatRepository;
import com.squirrel.tickting.domain.performance.type.Genre;
import com.squirrel.tickting.domain.performance.type.PerformanceStatus;
import com.squirrel.tickting.domain.performance.type.ReceiveType;
import com.squirrel.tickting.domain.performance.type.ScheduleStatus;
import com.squirrel.tickting.domain.performance.type.SeatStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;
import java.util.List;
import lombok.Builder;

/** 판매자의 내 공연 응답. 예매자용 공연 응답과 모양이 달라서 이름을 구분했다. */
public class SellerPerformanceResponse {

	private SellerPerformanceResponse() {
	}

	/** 공연 등록 결과. 나머지 값은 판매자가 방금 보낸 값이라 번호와 상태만 돌려주고, 전체는 상세(SLR-06)에서 본다. */
	@Builder
	@Schema(name = "SellerPerformanceCreated", description = "공연 등록 결과")
	public record Created(
		@Schema(description = "공연 번호")
		Long performanceId,
		@Schema(description = "공연 상태. 등록 직후에는 항상 DRAFT", example = "DRAFT")
		PerformanceStatus status,
		@Schema(description = "공연장 번호")
		Long venueId,
		@Schema(description = "등록 일시")
		LocalDateTime createdAt
	) {

		public static Created from(Performance performance) {
			return Created.builder()
				.performanceId(performance.getId())
				.status(performance.getStatus())
				.venueId(performance.getVenueId())
				.createdAt(performance.getCreatedAt())
				.build();
		}
	}

	/** 공연 수정 결과. 보낸 값을 다시 돌려주지 않고 번호와 상태만 주며, 전체는 상세(SLR-06)에서 본다. */
	@Builder
	@Schema(name = "SellerPerformanceUpdated", description = "공연 수정 결과")
	public record Updated(
		@Schema(description = "공연 번호")
		Long performanceId,
		@Schema(description = "공연 상태. 수정해도 바뀌지 않는다", example = "DRAFT")
		PerformanceStatus status,
		@Schema(description = "수정 일시. 바뀐 값이 없어 저장하지 않았으면 이전 값이다")
		LocalDateTime updatedAt
	) {

		public static Updated from(Performance performance) {
			return Updated.builder()
				.performanceId(performance.getId())
				.status(performance.getStatus())
				.updatedAt(performance.getUpdatedAt())
				.build();
		}
	}

	/** 내 공연 목록의 한 줄. 상세에서 보는 값은 넣지 않고 목록에서 고를 때 필요한 것만 둔다. */
	@Builder
	@Schema(name = "SellerPerformanceSummary", description = "내 공연 목록 항목")
	public record Summary(
		@Schema(description = "공연 번호")
		Long performanceId,
		@Schema(description = "공연명")
		String title,
		@Schema(description = "공연 상태", example = "PUBLISHED")
		PerformanceStatus status,
		@Schema(description = "포스터 주소")
		String posterUrl,
		@Schema(description = "공연장명 (사용 중지된 공연장도 보인다)")
		String venueName,
		@Schema(description = "회차 수. 취소·연기된 회차도 센다.")
		long scheduleCount,
		@Schema(description = "등록 일시")
		LocalDateTime createdAt
	) {

		/** 공연장명과 회차 수는 공연의 읽기 전용 연관에서 꺼낸다. 지연 로딩이라 트랜잭션 안에서 불러야 한다. */
		public static Summary from(Performance performance) {
			return Summary.builder()
				.performanceId(performance.getId())
				.title(performance.getTitle())
				.status(performance.getStatus())
				.posterUrl(performance.getPosterUrl())
				.venueName(performance.getVenue().getName())
				.scheduleCount(performance.getSchedules().size())
				.createdAt(performance.getCreatedAt())
				.build();
		}
	}

	/** 내 공연 상세. 판매 필드는 작성 중(DRAFT)에는 비어 있을 수 있다. */
	@Builder
	@Schema(name = "SellerPerformanceDetail", description = "내 공연 상세")
	public record Detail(
		@Schema(description = "공연 번호")
		Long performanceId,
		@Schema(description = "공연 상태", example = "PUBLISHED")
		PerformanceStatus status,
		@Schema(description = "공연장")
		Venue venue,
		@Schema(description = "공연명")
		String title,
		@Schema(description = "장르", example = "CONCERT")
		Genre genre,
		@Schema(description = "관람 연령 (만 N세 이상, 전체 관람가는 0)")
		Integer ageLimit,
		@Schema(description = "러닝타임(분)")
		Integer runningTime,
		@Schema(description = "출연진", nullable = true)
		String castInfo,
		@Schema(description = "포스터 주소")
		String posterUrl,
		@Schema(description = "판매자 공지", nullable = true)
		String notice,
		@Schema(description = "이용 안내", nullable = true)
		String guide,
		@Schema(description = "상세 이미지. 보여줄 순서대로")
		List<Image> images,
		@Schema(description = "적용한 취소 규정 번호")
		Long cancelPolicyId,
		@Schema(description = "판매 오픈 일시", nullable = true)
		LocalDateTime saleOpenAt,
		@Schema(description = "판매 종료 일시", nullable = true)
		LocalDateTime saleCloseAt,
		@Schema(description = "1인 최대 매수", nullable = true)
		Integer maxPerPerson,
		@Schema(description = "수령 방법", example = "BOTH", nullable = true)
		ReceiveType receiveType,
		@Schema(description = "예매 수수료(티켓 1장당 원)", nullable = true)
		Integer bookingFee,
		@Schema(description = "회차. 취소·연기된 회차도 포함하고 공연 일시가 빠른 순")
		List<Schedule> schedules,
		@Schema(description = "등록 일시")
		LocalDateTime createdAt,
		@Schema(description = "수정 일시")
		LocalDateTime updatedAt
	) {

		public static Detail from(Performance performance, List<PerformanceImage> images,
			List<PerformanceSchedule> schedules) {
			return Detail.builder()
				.performanceId(performance.getId())
				.status(performance.getStatus())
				.venue(new Venue(performance.getVenueId(), performance.getVenue().getName()))
				.title(performance.getTitle())
				.genre(performance.getGenre())
				.ageLimit(performance.getAgeLimit())
				.runningTime(performance.getRunningTime())
				.castInfo(performance.getCastInfo())
				.posterUrl(performance.getPosterUrl())
				.notice(performance.getNotice())
				.guide(performance.getGuide())
				.images(images.stream().map(Image::from).toList())
				.cancelPolicyId(performance.getCancelPolicy().getId())
				.saleOpenAt(performance.getSaleOpenAt())
				.saleCloseAt(performance.getSaleCloseAt())
				.maxPerPerson(performance.getMaxPerPerson())
				.receiveType(performance.getReceiveType())
				.bookingFee(performance.getBookingFee())
				.schedules(schedules.stream().map(Schedule::from).toList())
				.createdAt(performance.getCreatedAt())
				.updatedAt(performance.getUpdatedAt())
				.build();
		}
	}

	@Schema(name = "SellerPerformanceVenue", description = "공연장")
	public record Venue(
		@Schema(description = "공연장 번호")
		Long venueId,
		@Schema(description = "공연장명")
		String name
	) {
	}

	@Schema(name = "SellerPerformanceImage", description = "공연 상세 이미지")
	public record Image(
		@Schema(description = "이미지 주소")
		String url,
		@Schema(description = "보여줄 순서")
		Integer sortOrder
	) {

		public static Image from(PerformanceImage image) {
			return new Image(image.getUrl(), image.getSortOrder());
		}
	}

	@Schema(name = "SellerPerformanceSchedule", description = "공연 회차")
	public record Schedule(
		@Schema(description = "회차 번호")
		Long scheduleId,
		@Schema(description = "공연 일시")
		LocalDateTime startAt,
		@Schema(description = "회차 판매 상태", example = "ON_SALE")
		ScheduleStatus status,
		@Schema(description = "판매 좌석을 만든 시각. 아직 안 만들었으면 null", nullable = true)
		LocalDateTime seatsGeneratedAt,
		@Schema(description = "일시 중지(PAUSED) 회차의 중지 사유. 그 밖은 null", nullable = true)
		String pauseReason,
		@Schema(description = "가장 최근 연기. 연기한 적이 없으면 null", nullable = true)
		Postponement postponement
	) {

		/** 연기 이력은 지연 로딩이라 트랜잭션 안에서 불러야 한다. */
		public static Schedule from(PerformanceSchedule schedule) {
			return new Schedule(schedule.getId(), schedule.getStartAt(), schedule.getStatus(),
				schedule.getSeatsGeneratedAt(), schedule.getPauseReason(),
				schedule.findLatestPostponement().map(Postponement::from).orElse(null));
		}
	}

	@Schema(name = "SellerPerformanceSchedulePostponement", description = "회차 연기 이력")
	public record Postponement(
		@Schema(description = "연기 번호")
		Long postponementId,
		@Schema(description = "원래 공연 일시")
		LocalDateTime oldStartAt,
		@Schema(description = "새 공연 일시. 일정 미정이면 null", nullable = true)
		LocalDateTime newStartAt,
		@Schema(description = "연기 사유")
		String reason,
		@Schema(description = "무료 취소 마감. 일정 미정이면 null(확정 전까지)", nullable = true)
		LocalDateTime freeCancelUntil,
		@Schema(description = "연기한 일시. 이보다 먼저 생긴 예매가 무료 취소 대상이다")
		LocalDateTime createdAt
	) {

		public static Postponement from(SchedulePostponement postponement) {
			return new Postponement(postponement.getId(), postponement.getOldStartAt(), postponement.getNewStartAt(),
				postponement.getReason(), postponement.findDeadline(), postponement.getCreatedAt());
		}
	}

	/** 회차 연기(SLR-28)·새 일정 확정(SLR-29) 결과. */
	@Schema(name = "SellerPerformanceSchedulePostponed", description = "회차 연기·새 일정 확정 결과")
	public record SchedulePostponed(
		@Schema(description = "회차 번호")
		Long scheduleId,
		@Schema(description = "바꾼 뒤의 회차 상태. 일정 미정이면 POSTPONED", example = "POSTPONED")
		ScheduleStatus status,
		@Schema(description = "바꾼 뒤의 공연 일시. 일정 미정이면 원래 일시")
		LocalDateTime startAt,
		@Schema(description = "이번에 만들거나 확정한 연기 이력")
		Postponement postponement
	) {
	}

	/** 판매 일시 중지(SLR-26)·재개(SLR-27) 결과. 두 목록은 공연 일시가 빠른 순(같으면 회차 번호순)이다. */
	@Schema(name = "SellerPerformanceScheduleSaleChanged", description = "회차 판매 일시 중지·재개 결과")
	public record ScheduleSaleChanged(
		@Schema(description = "공연 번호")
		Long performanceId,
		@Schema(description = "상태를 바꾼 회차 번호")
		List<Long> changedScheduleIds,
		@Schema(description = "바꾸지 않고 건너뛴 회차")
		List<SkippedSchedule> skipped
	) {
	}

	@Schema(name = "SellerPerformanceSkippedSchedule", description = "건너뛴 회차")
	public record SkippedSchedule(
		@Schema(description = "회차 번호")
		Long scheduleId,
		@Schema(description = "건너뛴 이유", example = "ALREADY_PAUSED")
		SkipReason reason
	) {
	}

	/** 회차를 건너뛴 이유. 겹치면 CANCELED → POSTPONED → STARTED → ALREADY_* 순으로 하나만 알린다. */
	public enum SkipReason {
		CANCELED, POSTPONED, STARTED, ALREADY_PAUSED, ALREADY_ON_SALE
	}

	/** 공연 공개 결과. 공개되면 고칠 수 없는 구역 설정 중 판매하지 않기로 한 구역을 함께 알려 판매자가 마지막으로 확인하게 한다. */
	@Builder
	@Schema(name = "SellerPerformancePublished", description = "공연 공개 결과")
	public record Published(
		@Schema(description = "공연 번호")
		Long performanceId,
		@Schema(description = "공연 상태. 공개 직후에는 항상 PUBLISHED", example = "PUBLISHED")
		PerformanceStatus status,
		@Schema(description = "의도적으로 판매하지 않는(INTENTIONALLY_UNSOLD) 구역. 공연장 구역 순서(sortOrder)순")
		List<UnsoldZone> intentionallyUnsoldZones
	) {
	}

	/** 회차 추가(SLR-12) 결과. */
	@Schema(name = "SellerPerformanceScheduleCreated", description = "회차 추가 결과")
	public record ScheduleCreated(
		@Schema(description = "회차 번호")
		Long scheduleId,
		@Schema(description = "공연 번호")
		Long performanceId,
		@Schema(description = "공연 일시")
		LocalDateTime startAt,
		@Schema(description = "회차 판매 상태. 아직 시작하지 않은 다른 회차가 모두 중지 상태면 PAUSED, 아니면 ON_SALE", example = "ON_SALE")
		ScheduleStatus status,
		@Schema(description = "판매 좌석을 바로 만들었으면 그 시각, 아니면 null", nullable = true)
		LocalDateTime seatsGeneratedAt
	) {

		/** 좌석 생성 시각은 일괄 UPDATE 로 채워 엔티티에 반영되지 않으므로 따로 받는다. */
		public static ScheduleCreated from(PerformanceSchedule schedule, LocalDateTime seatsGeneratedAt) {
			return new ScheduleCreated(schedule.getId(), schedule.getPerformance().getId(), schedule.getStartAt(),
				schedule.getStatus(), seatsGeneratedAt);
		}
	}

	@Builder
	@Schema(name = "SellerPerformanceScheduleUpdated", description = "회차 수정 결과")
	public record ScheduleUpdated(
		@Schema(description = "회차 번호")
		Long scheduleId,
		@Schema(description = "공연 일시")
		LocalDateTime startAt,
		@Schema(description = "수정 일시. 같은 일시를 보내 바꾼 것이 없으면 이전 값이다")
		LocalDateTime updatedAt
	) {
	}

	/** 판매 좌석 생성(SLR-14) 결과. 개수는 만든 좌석을 DB에서 상태별로 센 값이다. */
	@Builder
	@Schema(name = "SellerPerformanceSeatsGenerated", description = "판매 좌석 생성 결과")
	public record SeatsGenerated(
		@Schema(description = "회차 번호")
		Long scheduleId,
		@Schema(description = "판매 좌석을 만든 시각")
		LocalDateTime seatsGeneratedAt,
		@Schema(description = "만든 좌석 수 (판매 가능 + 판매 제외)", example = "3022")
		long totalSeats,
		@Schema(description = "판매 가능(AVAILABLE) 좌석 수", example = "2980")
		long availableSeats,
		@Schema(description = "판매 제외(BLOCKED) 좌석 수. 의도적 미판매 구역의 좌석", example = "42")
		long blockedSeats
	) {
	}

	@Schema(name = "SellerPerformanceUnsoldZone", description = "판매하지 않는 구역")
	public record UnsoldZone(
		@Schema(description = "구역 번호")
		Long zoneId,
		@Schema(description = "구역 이름")
		String name
	) {
	}

	@Schema(name = "SellerPerformanceSales", description = "공연 판매 현황")
	public record Sales(
		@Schema(description = "회차별 판매 현황. 취소·연기·중지 회차도 포함하고 공연 일시 빠른 순")
		List<ScheduleSales> schedules
	) {
	}

	@Schema(name = "SellerPerformanceScheduleSales", description = "회차별 판매 현황")
	public record ScheduleSales(
		@Schema(description = "회차 번호")
		Long scheduleId,
		@Schema(description = "공연 일시")
		LocalDateTime startAt,
		@Schema(description = "회차 판매 상태", example = "ON_SALE")
		ScheduleStatus status,
		@Schema(description = "판매 좌석을 만들었는지. 만들지 않았으면 좌석 수는 모두 null")
		boolean seatsGenerated,
		@Schema(description = "총 좌석 수. 판매 좌석 생성 여부와 상관없이 공연장의 좌석 수")
		Integer totalSeats,
		@Schema(description = "판매 완료(SOLD)", nullable = true)
		Long soldSeats,
		@Schema(description = "임시 점유(HELD). 만료됐지만 아직 정리되지 않은 좌석도 센다", nullable = true)
		Long heldSeats,
		@Schema(description = "판매 가능(AVAILABLE)", nullable = true)
		Long availableSeats,
		@Schema(description = "판매자 보류(RESERVED)", nullable = true)
		Long reservedSeats,
		@Schema(description = "판매 제외(BLOCKED)", nullable = true)
		Long blockedSeats
	) {

		/** 좌석을 만든 회차는 상태별 개수(없는 상태는 0)를, 만들지 않은 회차는 null 을 담는다. */
		public static ScheduleSales of(PerformanceSchedule schedule, Integer totalSeats,
			List<ScheduleSeatRepository.SeatStatusCount> counts) {
			boolean generated = schedule.getSeatsGeneratedAt() != null;
			return new ScheduleSales(schedule.getId(), schedule.getStartAt(), schedule.getStatus(), generated,
				totalSeats,
				generated ? count(counts, SeatStatus.SOLD) : null,
				generated ? count(counts, SeatStatus.HELD) : null,
				generated ? count(counts, SeatStatus.AVAILABLE) : null,
				generated ? count(counts, SeatStatus.RESERVED) : null,
				generated ? count(counts, SeatStatus.BLOCKED) : null);
		}

		private static long count(List<ScheduleSeatRepository.SeatStatusCount> counts, SeatStatus status) {
			return counts.stream().filter(c -> c.getStatus() == status)
				.mapToLong(ScheduleSeatRepository.SeatStatusCount::getCount).sum();
		}
	}
}
