-- 발송 처리 (doc/part4-booking-area.md 4-2, doc/api-spec.md SLR-40~42)
-- 발송 일시는 처음 송장을 넣은 시각이다. 예매자에게 발송일을 보여 주고 아직 보내지 않은 예매를 가리는 데 쓴다.
-- 택배사·송장번호·발송 일시는 셋 다 비거나 셋 다 있고, 있으면 배송 예매만이다.
-- 예매 상태는 넣지 않는다. 발송한 뒤에도 회차·공연 취소로 전체 취소될 수 있어서 상태는 발송 처리의 UPDATE 조건이 맡는다.
ALTER TABLE booking
    ADD COLUMN shipped_at TIMESTAMP,
    ADD CONSTRAINT ck_booking_shipment CHECK (
        (carrier IS NULL AND tracking_no IS NULL AND shipped_at IS NULL)
        OR (carrier IS NOT NULL AND tracking_no IS NOT NULL AND shipped_at IS NOT NULL
            AND receive_type = 'DELIVERY')
    );
