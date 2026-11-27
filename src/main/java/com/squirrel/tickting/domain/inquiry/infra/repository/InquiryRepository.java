package com.squirrel.tickting.domain.inquiry.infra.repository;

import com.squirrel.tickting.domain.inquiry.infra.entity.Inquiry;
import com.squirrel.tickting.domain.inquiry.type.InquiryCategory;
import com.squirrel.tickting.domain.inquiry.type.InquiryStatus;
import com.squirrel.tickting.global.security.AccountType;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface InquiryRepository extends JpaRepository<Inquiry, Long> {

	/** 내 문의만 찾는다. 남의 문의·삭제한 문의는 비어 있다. (호출한 쪽이 404 로 답해 번호가 있는지 알려주지 않는다) */
	Optional<Inquiry> findByIdAndAuthorTypeAndAuthorIdAndDeletedAtIsNull(Long id, AccountType authorType, Long authorId);

	/** 내 문의 목록. 삭제한 문의는 빠진다. 정렬은 호출한 쪽이 pageable 로 정한다. */
	Page<Inquiry> findByAuthorTypeAndAuthorIdAndDeletedAtIsNull(AccountType authorType, Long authorId,
		Pageable pageable);

	/** 우리 사업체 문의 하나. 사업체 번호는 판매자 문의에만 있어서 예매자 문의는 걸리지 않는다. 다른 사업체·삭제한 문의는 비어 있다. */
	Optional<Inquiry> findByIdAndBusinessIdAndDeletedAtIsNull(Long id, Long businessId);

	/** 우리 사업체 문의 목록 (대표·담당자 모두). 삭제한 문의는 빠진다. 정렬은 호출한 쪽이 pageable 로 정한다. */
	Page<Inquiry> findByBusinessIdAndDeletedAtIsNull(Long businessId, Pageable pageable);

	/**
	 * 답변 대기인 문의만 내용을 통째로 바꾸고 바뀐 행 수를 돌려준다.
	 * 서비스가 미리 상태를 확인해도 그 사이 답변이 끝날 수 있어서, 상태 조건을 UPDATE 에 함께 걸어 답변과 겹치면 한쪽만 성공하게 한다.
	 * 관리자 답변도 같은 조건(WAITING, 삭제 안 됨)으로 바꿔야 이 방어가 성립한다.
	 */
	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query("""
		UPDATE Inquiry i
		   SET i.category = :category,
		       i.bookingId = :bookingId,
		       i.title = :title,
		       i.content = :content,
		       i.imageUrls = :imageUrls,
		       i.updatedAt = :now
		 WHERE i.id = :id
		   AND i.status = com.squirrel.tickting.domain.inquiry.type.InquiryStatus.WAITING
		   AND i.deletedAt IS NULL
		""")
	int updateIfWaiting(@Param("id") Long id, @Param("category") InquiryCategory category,
		@Param("bookingId") Long bookingId, @Param("title") String title, @Param("content") String content,
		@Param("imageUrls") List<String> imageUrls, @Param("now") LocalDateTime now);

	/** 삭제하지 않은 문의 하나. 관리자가 답변할 때 쓴다. */
	Optional<Inquiry> findByIdAndDeletedAtIsNull(Long id);

	/**
	 * 관리자 목록 (기본: 삭제하지 않은 문의). 작성자 종류·상태는 거르지 않을 때도 모든 값을 넘긴다.
	 * PostgreSQL 에서 null 파라미터가 타입 오류를 내서 "값이 있으면 거르기" 대신 허용할 값 목록을 받는다. (BookingQueryRepository 와 같다)
	 */
	Page<Inquiry> findByAuthorTypeInAndStatusInAndDeletedAtIsNull(Collection<AccountType> authorTypes,
		Collection<InquiryStatus> statuses, Pageable pageable);

	/** 관리자 목록 중 작성자가 삭제한 문의만. */
	Page<Inquiry> findByAuthorTypeInAndStatusInAndDeletedAtIsNotNull(Collection<AccountType> authorTypes,
		Collection<InquiryStatus> statuses, Pageable pageable);

	/**
	 * 답변 대기이고 삭제하지 않은 문의에만 답변을 달고 바뀐 행 수를 돌려준다.
	 * 작성자의 수정·삭제(updateIfWaiting, deleteIfWaiting)와 같은 조건이라, 둘이 겹치면 먼저 커밋한 쪽만 성공한다.
	 * 그래서 "옛 내용을 보고 쓴 답변이 바뀐 내용에 붙는" 일과 두 관리자의 답변이 겹쳐 덮어쓰는 일이 생기지 않는다.
	 */
	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query("""
		UPDATE Inquiry i
		   SET i.status = com.squirrel.tickting.domain.inquiry.type.InquiryStatus.ANSWERED,
		       i.answer = :answer,
		       i.answeredBy = :adminId,
		       i.answeredAt = :now,
		       i.updatedAt = :now
		 WHERE i.id = :id
		   AND i.status = com.squirrel.tickting.domain.inquiry.type.InquiryStatus.WAITING
		   AND i.deletedAt IS NULL
		""")
	int answerIfWaiting(@Param("id") Long id, @Param("answer") String answer, @Param("adminId") Long adminId,
		@Param("now") LocalDateTime now);

	/** 답변 대기인 문의만 소프트 삭제하고 바뀐 행 수를 돌려준다. 동시에 여러 번 지워도 deleted_at 조건 때문에 한 번만 성공한다. */
	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query("""
		UPDATE Inquiry i
		   SET i.deletedAt = :now,
		       i.updatedAt = :now
		 WHERE i.id = :id
		   AND i.status = com.squirrel.tickting.domain.inquiry.type.InquiryStatus.WAITING
		   AND i.deletedAt IS NULL
		""")
	int deleteIfWaiting(@Param("id") Long id, @Param("now") LocalDateTime now);
}
