package com.squirrel.tickting.domain.seller.infra.repository;

import com.squirrel.tickting.domain.seller.infra.entity.SellerBusiness;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SellerBusinessRepository extends JpaRepository<SellerBusiness, Long> {

	boolean existsByBusinessNumber(String businessNumber);
}
