-- 회차 취소(SLR-15) 사유. 환불을 만드는 스케줄러가 나중에 환불의 상세 사유(refund.reason_detail)에 넣는다.
ALTER TABLE performance_schedule ADD COLUMN cancel_reason VARCHAR(500);

-- 회차 취소 환불은 예매당 한 건이다. 환불을 만들 때 예매 상태는 바뀌지 않아서, 락이 듣지 않을 때의 마지막 방어선이다.
-- 실패한 환불도 한 건으로 세어, 새로 만들지 않고 같은 환불을 다시 보낸다.
CREATE UNIQUE INDEX uq_refund_performance_cancel
    ON refund (booking_id)
    WHERE reason_type = 'PERFORMANCE_CANCEL';
