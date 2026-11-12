package com.squirrel.tickting.domain.performance.infra.entity;

import com.squirrel.tickting.domain.performance.type.Genre;
import com.squirrel.tickting.domain.performance.type.PerformanceStatus;
import com.squirrel.tickting.domain.performance.type.ReceiveType;
import com.squirrel.tickting.global.entity.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
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
	@Column(nullable = false)
	private Long businessId;

	/** 공연이 열리는 공연장 번호 (venue) */
	@Column(nullable = false)
	private Long venueId;

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
}
