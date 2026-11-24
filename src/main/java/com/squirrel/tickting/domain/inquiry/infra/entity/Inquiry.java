package com.squirrel.tickting.domain.inquiry.infra.entity;

import com.squirrel.tickting.domain.booking.infra.entity.Booking;
import com.squirrel.tickting.domain.inquiry.type.InquiryCategory;
import com.squirrel.tickting.domain.inquiry.type.InquiryStatus;
import com.squirrel.tickting.global.entity.SoftDeletableEntity;
import com.squirrel.tickting.global.security.AccountType;
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
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import java.util.List;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 문의. 예매자·판매자가 함께 쓰고 관리자가 답변한다. 답변이 하나라 같은 행에 둔다.
 * 작성자는 종류 + 번호로 가리킨다. 예매자와 판매자는 계정 테이블이 달라 번호가 겹칠 수 있어서 번호만으로는 누구인지 모른다.
 * 삭제는 행을 지우지 않고 deleted_at 만 남긴다. 조회 메서드 이름에 DeletedAtIsNull 을 드러낸다.
 */
@Entity
@Table(name = "inquiry")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
public class Inquiry extends SoftDeletableEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private AccountType authorType;

	/** 작성자 번호. 예매자면 user_member, 판매자면 seller_member 의 번호다. */
	@Column(nullable = false)
	private Long authorId;

	/** 판매자 문의만. 같은 사업체의 대표·담당자가 함께 본다. */
	@Column
	private Long businessId;

	/** 작성 때 복사한 이름·이메일. 관리자 목록에서 작성자를 보여 주는 데 쓴다. */
	@Column(nullable = false, length = 50)
	private String authorName;

	@Column(nullable = false)
	private String authorEmail;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 30)
	private InquiryCategory category;

	/** 예매자가 고른 내 예매 번호. 고르지 않으면 비어 있다. 읽기 전용 연관(booking)이 같은 컬럼을 써서 이름을 적어 둔다. */
	@Column(name = "booking_id")
	private Long bookingId;

	/**
	 * 읽기 전용 연관. 응답에 예매 번호·공연 제목을 붙이려는 것이다. (DB 에 FK 제약은 없다)
	 * 저장할 때는 위의 bookingId 를 쓴다. 지연 로딩이라 트랜잭션 안에서 꺼낸다.
	 */
	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "booking_id", insertable = false, updatable = false,
		foreignKey = @ForeignKey(ConstraintMode.NO_CONSTRAINT))
	private Booking booking;

	/** 첨부 사진(읽기 전용). 저장은 InquiryImage 를 직접 한다. */
	@OneToMany
	@OrderBy("sortOrder")
	@JoinColumn(name = "inquiry_id", insertable = false, updatable = false,
		foreignKey = @ForeignKey(ConstraintMode.NO_CONSTRAINT))
	private List<InquiryImage> images;

	@Column(nullable = false, length = 100)
	private String title;

	@Column(nullable = false, length = 2000)
	private String content;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private InquiryStatus status;

	@Column(length = 2000)
	private String answer;

	/** 답변한 관리자 번호 (admin_member) */
	@Column
	private Long answeredBy;

	@Column
	private LocalDateTime answeredAt;
}
