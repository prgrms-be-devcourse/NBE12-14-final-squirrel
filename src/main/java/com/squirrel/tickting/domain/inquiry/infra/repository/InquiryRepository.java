package com.squirrel.tickting.domain.inquiry.infra.repository;

import com.squirrel.tickting.domain.inquiry.infra.entity.Inquiry;
import com.squirrel.tickting.global.security.AccountType;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InquiryRepository extends JpaRepository<Inquiry, Long> {

	/** 내 문의만 찾는다. 남의 문의·삭제한 문의는 비어 있다. (호출한 쪽이 404 로 답해 번호가 있는지 알려주지 않는다) */
	Optional<Inquiry> findByIdAndAuthorTypeAndAuthorIdAndDeletedAtIsNull(Long id, AccountType authorType, Long authorId);

	/** 내 문의 목록. 삭제한 문의는 빠진다. 정렬은 호출한 쪽이 pageable 로 정한다. */
	Page<Inquiry> findByAuthorTypeAndAuthorIdAndDeletedAtIsNull(AccountType authorType, Long authorId,
		Pageable pageable);
}
