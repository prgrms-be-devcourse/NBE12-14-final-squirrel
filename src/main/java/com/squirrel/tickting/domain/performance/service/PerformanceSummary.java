package com.squirrel.tickting.domain.performance.service;

import com.squirrel.tickting.domain.performance.infra.repository.PerformanceSummaryRow;
import com.squirrel.tickting.domain.performance.type.SaleStatus;

/** 공연 목록의 한 항목. 쿼리로 읽은 값과 그 값으로 계산한 판매 상태를 함께 담는다. */
public record PerformanceSummary(PerformanceSummaryRow row, SaleStatus saleStatus) {
}
