package com.squirrel.tickting.domain.inquiry.infra.repository;

import com.squirrel.tickting.domain.inquiry.infra.entity.InquiryImage;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InquiryImageRepository extends JpaRepository<InquiryImage, Long> {
}
