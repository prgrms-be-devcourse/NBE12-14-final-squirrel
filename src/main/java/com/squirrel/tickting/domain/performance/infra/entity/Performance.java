package com.squirrel.tickting.domain.performance.infra.entity;

import com.squirrel.tickting.domain.performance.type.Genre;
import com.squirrel.tickting.domain.performance.type.PerformanceStatus;
import com.squirrel.tickting.domain.performance.type.ReceiveType;
import com.squirrel.tickting.domain.seller.infra.entity.SellerBusiness;
import com.squirrel.tickting.domain.venue.infra.entity.Venue;
import com.squirrel.tickting.global.entity.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.ConstraintMode;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import java.util.List;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 공연. 판매 상태(예정·판매 중·종료·매진)는 저장하지 않고 계산한다.
 * 판매 오픈·종료와 1인 최대 매수는 공연의 모든 회차가 같아서 공연이 가진다.
 */
@Entity
@Table(name = "performance")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
public class Performance extends BaseTimeEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	/** 공연을 올린 사업체 번호 (seller_business) */
	@Column(name = "business_id", nullable = false)
	private Long businessId;

	/** 공연이 열리는 공연장 번호 (venue) */
	@Column(name = "venue_id", nullable = false)
	private Long venueId;

	/**
	 * 읽기 전용 연관. 응답을 만들 때 공연에서 바로 공연장·사업체·회차를 꺼내 쓰려는 것이다. (DB 에 FK 제약은 없다)
	 * 저장하거나 바꿀 때는 위의 번호(businessId, venueId)를 쓰고, 이 필드들은 INSERT·UPDATE 에 들어가지 않는다.
	 * 지연 로딩이라 트랜잭션 안에서만 꺼낼 수 있고, 목록은 default_batch_fetch_size 로 묶어서 읽는다.
	 */
	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "venue_id", insertable = false, updatable = false,
		foreignKey = @ForeignKey(ConstraintMode.NO_CONSTRAINT))
	private Venue venue;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "business_id", insertable = false, updatable = false,
		foreignKey = @ForeignKey(ConstraintMode.NO_CONSTRAINT))
	private SellerBusiness business;

	/** 이 공연의 회차 전부(취소된 회차 포함). 읽기 전용이고 회차를 만들거나 바꿀 때는 PerformanceSchedule 을 직접 다룬다. */
	@OneToMany(mappedBy = "performance")
	private List<PerformanceSchedule> schedules;

	/** 등록한 판매자 계정 번호 (seller_member) */
	@Column(nullable = false)
	private Long createdBy;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "cancel_policy_id", nullable = false)
	private CancelPolicy cancelPolicy;

	@Column(nullable = false, length = 200)
	private String title;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private Genre genre;

	/** 만 N세 이상. 전체 관람가는 0 */
	@Column(nullable = false)
	private Integer ageLimit;

	/** 분. 같은 공연장의 회차 시간 겹침 검사에 쓴다. */
	@Column(nullable = false)
	private Integer runningTime;

	@Column(columnDefinition = "text")
	private String castInfo;

	@Column(nullable = false, length = 2048)
	private String posterUrl;

	/** 이 공연만의 공지 */
	@Column(columnDefinition = "text")
	private String notice;

	/** 이 공연만의 안내. 모든 공연에 보이는 안내는 common_guide 에 있다. */
	@Column(columnDefinition = "text")
	private String guide;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private PerformanceStatus status;

	/** DRAFT 상태에서는 비어 있을 수 있고, 공개할 때 서비스에서 필수 여부를 검증한다. */
	@Column
	private LocalDateTime saleOpenAt;

	@Column
	private LocalDateTime saleCloseAt;

	/** 공연의 모든 회차를 합친 누적 기준이다. 예매가 생긴 뒤에는 늘리는 것만 가능하다. */
	@Column
	private Integer maxPerPerson;

	@Enumerated(EnumType.STRING)
	@Column(length = 20)
	private ReceiveType receiveType;

	/** 티켓 1장당 원. 판매자가 공연마다 정한다. */
	@Column
	private Integer bookingFee;

	/**
	 * 공연 수정(전체 교체)으로 일반 필드와 판매 필드를 바꾼다. 상태·공연장·취소 규정은 바꾸지 않는다.
	 * 공연장·취소 규정은 작성 중에만 바뀌고 구역 설정·판매 좌석 정리가 따라와야 해서 아래의 따로 둔 메서드가 맡는다.
	 */
	public void update(String title, Genre genre, Integer ageLimit, Integer runningTime, String castInfo,
		String posterUrl, String notice, String guide, LocalDateTime saleOpenAt, LocalDateTime saleCloseAt,
		Integer maxPerPerson, ReceiveType receiveType, Integer bookingFee) {
		this.title = title;
		this.genre = genre;
		this.ageLimit = ageLimit;
		this.runningTime = runningTime;
		this.castInfo = castInfo;
		this.posterUrl = posterUrl;
		this.notice = notice;
		this.guide = guide;
		this.saleOpenAt = saleOpenAt;
		this.saleCloseAt = saleCloseAt;
		this.maxPerPerson = maxPerPerson;
		this.receiveType = receiveType;
		this.bookingFee = bookingFee;
	}

	/** 작성 중 공연의 공연장을 바꾼다. 읽기 전용 연관(venue)은 이 트랜잭션 안에서는 옛 값을 가리키므로 바꾼 뒤에 읽지 않는다. */
	public void changeVenue(Long venueId) {
		this.venueId = venueId;
	}

	/** 작성 중 공연의 취소 규정을 바꾼다. */
	public void changeCancelPolicy(CancelPolicy cancelPolicy) {
		this.cancelPolicy = cancelPolicy;
	}
}
