package com.squirrel.tickting.domain.inquiry.infra.repository;

import com.squirrel.tickting.domain.inquiry.infra.entity.InquiryHistory;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InquiryHistoryRepository extends JpaRepository<InquiryHistory, Long> {
}
