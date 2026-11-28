package com.squirrel.tickting.domain.inquiry.infra.repository;

import com.squirrel.tickting.domain.inquiry.infra.entity.InquiryHistory;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InquiryHistoryRepository extends JpaRepository<InquiryHistory, Long> {

	/** 문의 하나의 이력. 남긴 순서대로다. */
	List<InquiryHistory> findByInquiryIdOrderByIdAsc(Long inquiryId);
}
