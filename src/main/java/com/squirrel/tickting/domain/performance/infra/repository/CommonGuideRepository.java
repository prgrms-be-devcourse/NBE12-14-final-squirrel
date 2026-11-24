package com.squirrel.tickting.domain.performance.infra.repository;

import com.squirrel.tickting.domain.performance.infra.entity.CommonGuide;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CommonGuideRepository extends JpaRepository<CommonGuide, Long> {
}
