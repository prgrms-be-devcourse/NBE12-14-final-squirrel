package com.squirrel.tickting.domain.performance.dto;

import com.squirrel.tickting.domain.performance.infra.entity.CancelPolicyRule;
import com.squirrel.tickting.domain.performance.infra.entity.Performance;
import com.squirrel.tickting.domain.performance.infra.entity.PerformanceImage;
import com.squirrel.tickting.domain.performance.infra.entity.PerformanceSchedule;
import com.squirrel.tickting.domain.performance.infra.entity.SeatGrade;
import com.squirrel.tickting.domain.performance.infra.entity.ZoneGrade;
import com.squirrel.tickting.domain.performance.infra.repository.UserPerformanceRepository.PublishedRow;
import com.squirrel.tickting.domain.performance.infra.repository.UserPerformanceRepository.ZoneName;
import com.squirrel.tickting.domain.performance.infra.repository.UserPerformanceRepository.ZoneRemaining;
import com.squirrel.tickting.domain.performance.infra.repository.UserPerformanceRepository.ZoneSeat;
import com.squirrel.tickting.domain.performance.service.UserPerformanceService;
import com.squirrel.tickting.domain.performance.type.Genre;
import com.squirrel.tickting.domain.performance.type.GuideType;
import com.squirrel.tickting.domain.performance.type.ReceiveType;
import com.squirrel.tickting.domain.performance.type.SaleStatus;
import com.squirrel.tickting.domain.performance.type.ScheduleStatus;
import com.squirrel.tickting.domain.performance.type.SeatGradeName;
import com.squirrel.tickting.domain.performance.type.SeatStatus;
import com.squirrel.tickting.domain.performance.type.ZoneAssignmentStatus;
import com.squirrel.tickting.domain.seller.infra.entity.SellerBusiness;
import com.squirrel.tickting.domain.venue.infra.entity.Venue;
import com.squirrel.tickting.domain.venue.infra.entity.VenueZone;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;
import java.util.List;
import lombok.Builder;

public class UserPerformanceResponse {

	private UserPerformanceResponse() {
	}

	/** 공연 목록의 한 항목. (API 명세 PFM-01) 판매 종료 시각은 판매 상태 계산에만 쓰고 내리지 않는다. */
	@Builder
	@Schema(name = "UserPerformanceSummary", description = "공연 목록 항목")
	public record Summary(
		@Schema(description = "공연 번호")
		Long performanceId,
		@Schema(description = "공연 제목", example = "가상 콘서트")
		String title,
		@Schema(description = "장르")
		Genre genre,
		@Schema(description = "포스터 이미지 주소")
		String posterUrl,
		@Schema(description = "관람 가능 나이 (만)", example = "12")
		Integer ageLimit,
		@Schema(description = "공연장 이름")
		String venueName,
		@Schema(description = "공연장 지역", example = "서울")
		String region,
		@Schema(description = "첫 회차 일시 (취소된 회차 제외). 회차가 없으면 null")
		LocalDateTime firstStartAt,
		@Schema(description = "마지막 회차 일시 (취소된 회차 제외). 회차가 없으면 null")
		LocalDateTime lastStartAt,
		@Schema(description = "판매 오픈 일시")
		LocalDateTime saleOpenAt,
		@Schema(description = "판매 상태 (계산값)")
		SaleStatus saleStatus
	) {

		/** 판매 상태는 쿼리가 아니라 서비스가 현재 시각으로 계산해서 넘긴다. */
		public static Summary from(PublishedRow row, SaleStatus saleStatus) {
			return Summary.builder()
				.performanceId(row.id())
				.title(row.title())
				.genre(row.genre())
				.posterUrl(row.posterUrl())
				.ageLimit(row.ageLimit())
				.venueName(row.venueName())
				.region(row.region())
				.firstStartAt(row.firstStartAt())
				.lastStartAt(row.lastStartAt())
				.saleOpenAt(row.saleOpenAt())
				.saleStatus(saleStatus)
				.build();
		}
	}

	/** 공연 상세. (API 명세 PFM-02) */
	@Builder
	@Schema(name = "UserPerformanceDetail", description = "공연 상세")
	public record Detail(
		@Schema(description = "공연 번호")
		Long performanceId,
		@Schema(description = "공연 제목", example = "가상 콘서트")
		String title,
		@Schema(description = "장르")
		Genre genre,
		@Schema(description = "관람 가능 나이 (만)", example = "12")
		Integer ageLimit,
		@Schema(description = "러닝타임 (분)", example = "150")
		Integer runningTime,
		@Schema(description = "출연진. 없으면 null")
		String castInfo,
		@Schema(description = "포스터 이미지 주소")
		String posterUrl,
		@Schema(description = "상세 이미지. 보여줄 순서대로")
		List<Image> images,
		@Schema(description = "이 공연만의 공지. 없으면 null")
		String notice,
		@Schema(description = "이 공연만의 안내. 없으면 null")
		String guide,
		@Schema(description = "공연장. 공연장 정보가 없으면 null")
		VenueInfo venue,
		@Schema(description = "판매 설정")
		Sale sale,
		@Schema(description = "회차. 취소된 회차는 빠지고 공연 일시가 빠른 순")
		List<Schedule> schedules,
		@Schema(description = "좌석 등급. 구역에 지정된 등급만, 비싼 순")
		List<Grade> grades,
		@Schema(description = "취소 규정 구간. 공연에서 먼 구간부터(daysFrom 큰 순)")
		List<CancelRule> cancelPolicy,
		@Schema(description = "모든 공연에 공통인 안내")
		CommonGuide commonGuide,
		@Schema(description = "판매자 정보. 사업체 정보가 없으면 null")
		Seller seller
	) {

		/** 공연장·판매자는 공연의 읽기 전용 연관에서 꺼낸다. 지연 로딩이라 트랜잭션 안에서 불러야 한다. */
		public static Detail from(UserPerformanceService.Detail detail) {
			Performance performance = detail.performance();
			return Detail.builder()
				.performanceId(performance.getId())
				.title(performance.getTitle())
				.genre(performance.getGenre())
				.ageLimit(performance.getAgeLimit())
				.runningTime(performance.getRunningTime())
				.castInfo(performance.getCastInfo())
				.posterUrl(performance.getPosterUrl())
				.images(detail.images().stream().map(Image::from).toList())
				.notice(performance.getNotice())
				.guide(performance.getGuide())
				.venue(VenueInfo.from(performance.getVenue()))
				.sale(Sale.from(performance))
				.schedules(detail.schedules().stream().map(Schedule::from).toList())
				.grades(detail.grades().stream().map(Grade::from).toList())
				.cancelPolicy(detail.cancelRules().stream().map(CancelRule::from).toList())
				.commonGuide(CommonGuide.from(detail.commonGuides()))
				.seller(Seller.from(performance.getBusiness()))
				.build();
		}
	}

	@Builder
	@Schema(name = "UserPerformanceImage", description = "공연 상세 이미지")
	public record Image(
		@Schema(description = "이미지 주소")
		String url,
		@Schema(description = "보여줄 순서", example = "1")
		Integer sortOrder
	) {

		public static Image from(PerformanceImage image) {
			return Image.builder()
				.url(image.getUrl())
				.sortOrder(image.getSortOrder())
				.build();
		}
	}

	@Builder
	@Schema(name = "UserPerformanceVenue", description = "공연장")
	public record VenueInfo(
		@Schema(description = "공연장 번호")
		Long venueId,
		@Schema(description = "공연장 이름", example = "가상 아레나")
		String name,
		@Schema(description = "주소")
		String address,
		@Schema(description = "지역", example = "서울")
		String region
	) {

		public static VenueInfo from(Venue venue) {
			return VenueInfo.builder()
				.venueId(venue.getId())
				.name(venue.getName())
				.address(venue.getAddress())
				.region(venue.getRegion())
				.build();
		}
	}

	@Builder
	@Schema(name = "UserPerformanceSale", description = "판매 설정. 모든 회차에 공통")
	public record Sale(
		@Schema(description = "판매 오픈 일시")
		LocalDateTime saleOpenAt,
		@Schema(description = "판매 종료 일시")
		LocalDateTime saleCloseAt,
		@Schema(description = "1인 최대 매수 (모든 회차 합계)", example = "4")
		Integer maxPerPerson,
		@Schema(description = "티켓 수령 방법")
		ReceiveType receiveType,
		@Schema(description = "티켓 1장당 예매 수수료 (원)", example = "2000")
		Integer bookingFee
	) {

		public static Sale from(Performance performance) {
			return Sale.builder()
				.saleOpenAt(performance.getSaleOpenAt())
				.saleCloseAt(performance.getSaleCloseAt())
				.maxPerPerson(performance.getMaxPerPerson())
				.receiveType(performance.getReceiveType())
				.bookingFee(performance.getBookingFee())
				.build();
		}
	}

	@Builder
	@Schema(name = "UserPerformanceSchedule", description = "공연 상세의 회차")
	public record Schedule(
		@Schema(description = "회차 번호")
		Long scheduleId,
		@Schema(description = "공연 일시")
		LocalDateTime startAt,
		@Schema(description = "회차 상태. PAUSED(일시 중지)·POSTPONED(연기)면 화면은 판매 상태보다 이 값을 먼저 보여 준다")
		ScheduleStatus status,
		@Schema(description = "판매 상태 (계산값). 판매 기간·회차 시작 일시·일시 중지 여부·남은 좌석으로 정한다")
		SaleStatus saleStatus,
		@Schema(description = "판매 일시 중지 사유. saleStatus 가 PAUSED 이고 판매자가 입력한 경우만 있고, 그 밖은 null", example = "좌석 점검")
		String pauseReason
	) {

		public static Schedule from(UserPerformanceService.ScheduleItem item) {
			return Schedule.builder()
				.scheduleId(item.schedule().getId())
				.startAt(item.schedule().getStartAt())
				.status(item.schedule().getStatus())
				.saleStatus(item.saleStatus())
				.pauseReason(item.pauseReason())
				.build();
		}
	}

	@Builder
	@Schema(name = "UserPerformanceGrade", description = "좌석 등급과 가격")
	public record Grade(
		@Schema(description = "등급 번호")
		Long gradeId,
		@Schema(description = "등급 코드. 화면 이름과 색은 프론트가 정한다", example = "VIP")
		SeatGradeName name,
		@Schema(description = "가격 (원)", example = "165000")
		Integer price
	) {

		public static Grade from(SeatGrade grade) {
			return Grade.builder()
				.gradeId(grade.getId())
				.name(grade.getName())
				.price(grade.getPrice())
				.build();
		}
	}

	/** 취소 규정 구간. 판매자 응답과 모양이 같아도 따로 두어, 판매자 쪽 응답이 바뀌어도 예매자 응답이 함께 바뀌지 않게 한다. */
	@Builder
	@Schema(name = "UserPerformanceCancelRule", description = "취소 규정 구간")
	public record CancelRule(
		@Schema(description = "공연 며칠 전부터", example = "3")
		Integer daysFrom,
		@Schema(description = "공연 며칠 전까지. null 이면 제한 없음", example = "6")
		Integer daysTo,
		@Schema(description = "취소 수수료율(%)", example = "10")
		Integer feeRate,
		@Schema(description = "false 면 이 구간에는 취소할 수 없다")
		boolean cancelable
	) {

		public static CancelRule from(CancelPolicyRule rule) {
			return CancelRule.builder()
				.daysFrom(rule.getDaysFrom())
				.daysTo(rule.getDaysTo())
				.feeRate(rule.getFeeRate())
				.cancelable(rule.isCancelable())
				.build();
		}
	}

	@Builder
	@Schema(name = "UserPerformanceCommonGuide", description = "모든 공연에 공통인 안내. 관리자가 아직 넣지 않은 안내는 null")
	public record CommonGuide(
		@Schema(description = "예매 안내")
		String bookingGuide,
		@Schema(description = "환불 안내")
		String refundGuide
	) {

		public static CommonGuide from(List<com.squirrel.tickting.domain.performance.infra.entity.CommonGuide> guides) {
			return CommonGuide.builder()
				.bookingGuide(contentOf(guides, GuideType.BOOKING_GUIDE))
				.refundGuide(contentOf(guides, GuideType.REFUND_GUIDE))
				.build();
		}

		/** 유형마다 한 행이다. 아직 넣지 않은 유형은 null 이다. */
		private static String contentOf(List<com.squirrel.tickting.domain.performance.infra.entity.CommonGuide> guides,
			GuideType type) {
			return guides.stream()
				.filter(guide -> guide.getType() == type)
				.map(com.squirrel.tickting.domain.performance.infra.entity.CommonGuide::getContent)
				.findFirst()
				.orElse(null);
		}
	}

	/** 판매자 정보. 사업체 상태·반려 사유·심사자와 판매자 계정의 개인정보는 넣지 않는다. (3부 3-8) */
	@Builder
	@Schema(name = "UserPerformanceSeller", description = "판매자 정보")
	public record Seller(
		@Schema(description = "회사명", example = "(주)가상기획")
		String companyName,
		@Schema(description = "대표자", example = "김대표")
		String representativeName,
		@Schema(description = "사업자등록번호", example = "1234567890")
		String businessNumber,
		@Schema(description = "문의 전화", example = "01012345678")
		String contactPhone,
		@Schema(description = "문의 이메일", example = "contact@company.com")
		String contactEmail
	) {

		public static Seller from(SellerBusiness business) {
			return Seller.builder()
				.companyName(business.getCompanyName())
				.representativeName(business.getRepresentativeName())
				.businessNumber(business.getBusinessNumber())
				.contactPhone(business.getContactPhone())
				.contactEmail(business.getContactEmail())
				.build();
		}
	}

	/** 회차의 구역 요약. (API 명세 PFM-03) 구역은 공연장의 화면 순서(sort_order)대로 나온다. */
	@Builder
	@Schema(name = "UserScheduleZoneSummary", description = "회차의 구역 요약")
	public record ZoneSummary(
		@Schema(description = "회차 번호")
		Long scheduleId,
		@Schema(description = "프론트가 그릴 도면 이름표. 비어 있으면 기본 격자 도면", example = "sample-arena")
		String layoutKey,
		@Schema(description = "구역별 등급·잔여석")
		List<Zone> zones
	) {

		/**
		 * 구역은 공연장의 읽기 전용 연관에서 화면 순서(sort_order, 같으면 구역 번호순)대로 꺼낸다. 지연 로딩이라 트랜잭션 안에서 불러야 한다.
		 */
		public static ZoneSummary from(PerformanceSchedule schedule, List<ZoneGrade> zoneGrades,
			List<ZoneRemaining> remainings) {
			Venue venue = schedule.getPerformance().getVenue();
			return ZoneSummary.builder()
				.scheduleId(schedule.getId())
				.layoutKey(venue.getLayoutKey())
				.zones(venue.getZones().stream()
					.map(zone -> Zone.of(zone, zoneGrades, remainings))
					.toList())
				.build();
		}
	}

	@Builder
	@Schema(name = "UserScheduleZone", description = "구역 하나의 등급과 잔여석")
	public record Zone(
		@Schema(description = "구역 번호")
		Long zoneId,
		@Schema(description = "구역 이름", example = "F2구역")
		String name,
		@Schema(description = "층. 단층이거나 스탠딩이면 null", example = "1층")
		String floor,
		@Schema(description = "등급 코드. 판매하지 않는 구역은 null. 화면 이름과 색은 프론트가 정한다", example = "R")
		SeatGradeName grade,
		@Schema(description = "가격 (원). 판매하지 않는 구역은 null", example = "154000")
		Integer price,
		@Schema(description = "지금 살 수 있는(AVAILABLE) 좌석 수", example = "38")
		Integer remaining,
		@Schema(description = "구역의 전체 좌석 수", example = "220")
		Integer total
	) {

		/**
		 * 구역에 해당하는 등급 설정과 잔여석을 목록에서 찾아 채운다. (구역은 많아야 수십 개라 목록을 훑어도 부담이 없다)
		 * 등급이 지정(ASSIGNED)된 구역만 등급·가격을 채우고, 미판매 구역과 설정이 없는 구역은 비운다.
		 * 판매 가능 좌석이 없는 구역은 잔여석 목록에 없으므로 0 이다.
		 */
		static Zone of(VenueZone zone, List<ZoneGrade> zoneGrades, List<ZoneRemaining> remainings) {
			ZoneGrade zoneGrade = zoneGrades.stream()
				.filter(candidate -> candidate.getZoneId().equals(zone.getId()))
				.findFirst().orElse(null);
			int remaining = remainings.stream()
				.filter(candidate -> candidate.getZoneId().equals(zone.getId()))
				.mapToInt(candidate -> candidate.getRemaining().intValue())
				.sum();
			SeatGrade grade = zoneGrade != null && zoneGrade.getAssignmentStatus() == ZoneAssignmentStatus.ASSIGNED
				? zoneGrade.getGrade() : null;
			return Zone.builder()
				.zoneId(zone.getId())
				.name(zone.getName())
				.floor(zone.getFloor())
				.grade(grade == null ? null : grade.getName())
				.price(grade == null ? null : grade.getPrice())
				.remaining(remaining)
				.total(Math.toIntExact(zone.getSeatCount()))
				.build();
		}
	}

	/**
	 * 회차의 한 구역 좌석. (API 명세 PFM-04) 좌석은 구역 안의 표시 순서(sort_order)대로 나온다.
	 * seatId 는 판매 좌석 번호라서 좌석 점유(BKG-01)에 그대로 보낸다.
	 */
	@Builder
	@Schema(name = "UserScheduleZoneSeats", description = "회차의 한 구역 좌석")
	public record ZoneSeats(
		@Schema(description = "구역 번호")
		Long zoneId,
		@Schema(description = "구역 이름", example = "F2구역")
		String name,
		@Schema(description = "좌석. 구역 안의 표시 순서대로")
		List<Seat> seats
	) {

		/**
		 * 구역의 판매 좌석을 구역 안의 표시 순서대로 담는다. 열·번호는 쿼리가 공연장 좌석과 조인해서 함께 읽어 온 값이다.
		 * 판매 좌석은 공연장 좌석을 그대로 복사해 만들어서 짝이 맞고, 판매 좌석이 없는 공연장 좌석(만든 뒤에 생긴 좌석)은 고를 수 없어서 나오지 않는다.
		 */
		public static ZoneSeats from(ZoneName zone, List<ZoneSeat> zoneSeats) {
			return ZoneSeats.builder()
				.zoneId(zone.getId())
				.name(zone.getName())
				.seats(zoneSeats.stream().map(Seat::from).toList())
				.build();
		}
	}

	@Builder
	@Schema(name = "UserScheduleSeat", description = "좌석 하나")
	public record Seat(
		@Schema(description = "판매 좌석 번호. 좌석 점유에 보내는 번호", example = "9301")
		Long seatId,
		@Schema(description = "열", example = "9열")
		String row,
		@Schema(description = "좌석 번호", example = "6")
		String number,
		@Schema(description = "구역 안의 표시 순서", example = "186")
		Integer sortOrder,
		@Schema(description = "상태. AVAILABLE 만 고를 수 있다", example = "AVAILABLE")
		SeatStatus status
	) {

		static Seat from(ZoneSeat zoneSeat) {
			return Seat.builder()
				.seatId(zoneSeat.seatId())
				.row(zoneSeat.rowLabel())
				.number(zoneSeat.seatNumber())
				.sortOrder(zoneSeat.sortOrder())
				.status(zoneSeat.status())
				.build();
		}
	}
}
