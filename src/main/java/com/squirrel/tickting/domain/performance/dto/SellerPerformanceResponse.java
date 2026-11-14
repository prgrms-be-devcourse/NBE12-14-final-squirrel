package com.squirrel.tickting.domain.performance.dto;

import com.squirrel.tickting.domain.performance.infra.entity.Performance;
import com.squirrel.tickting.domain.performance.infra.entity.PerformanceImage;
import com.squirrel.tickting.domain.performance.infra.entity.PerformanceSchedule;
import com.squirrel.tickting.domain.performance.service.PerformanceDetail;
import com.squirrel.tickting.domain.performance.type.Genre;
import com.squirrel.tickting.domain.performance.type.PerformanceStatus;
import com.squirrel.tickting.domain.performance.type.ReceiveType;
import com.squirrel.tickting.domain.performance.type.ScheduleStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;
import java.util.List;
import lombok.Builder;

/** 판매자의 내 공연 응답. 예매자용 공연 응답과 모양이 달라서 이름을 구분했다. */
public class SellerPerformanceResponse {

	private SellerPerformanceResponse() {
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

		public static Summary from(Performance performance, String venueName, long scheduleCount) {
			return Summary.builder()
				.performanceId(performance.getId())
				.title(performance.getTitle())
				.status(performance.getStatus())
				.posterUrl(performance.getPosterUrl())
				.venueName(venueName)
				.scheduleCount(scheduleCount)
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

		public static Detail from(PerformanceDetail detail, String venueName) {
			Performance performance = detail.performance();
			return Detail.builder()
				.performanceId(performance.getId())
				.status(performance.getStatus())
				.venue(new Venue(performance.getVenueId(), venueName))
				.title(performance.getTitle())
				.genre(performance.getGenre())
				.ageLimit(performance.getAgeLimit())
				.runningTime(performance.getRunningTime())
				.castInfo(performance.getCastInfo())
				.posterUrl(performance.getPosterUrl())
				.notice(performance.getNotice())
				.guide(performance.getGuide())
				.images(detail.images().stream().map(Image::from).toList())
				.cancelPolicyId(performance.getCancelPolicy().getId())
				.saleOpenAt(performance.getSaleOpenAt())
				.saleCloseAt(performance.getSaleCloseAt())
				.maxPerPerson(performance.getMaxPerPerson())
				.receiveType(performance.getReceiveType())
				.bookingFee(performance.getBookingFee())
				.schedules(detail.schedules().stream().map(Schedule::from).toList())
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
		LocalDateTime seatsGeneratedAt
	) {

		public static Schedule from(PerformanceSchedule schedule) {
			return new Schedule(schedule.getId(), schedule.getStartAt(), schedule.getStatus(),
				schedule.getSeatsGeneratedAt());
		}
	}
}
