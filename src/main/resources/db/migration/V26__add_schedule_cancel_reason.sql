-- 회차 취소(SLR-15)·공연 취소(SLR-16) 사유. 환불을 만드는 스케줄러(추후 구현)가 환불의 상세 사유(refund.reason_detail)에 넣는다.
ALTER TABLE performance_schedule ADD COLUMN cancel_reason VARCHAR(500);
