-- 판매자의 "내 사업체 전체 예매" 목록을 공연 테이블 조인 없이 최신순으로 읽으려고 예매에 사업체 번호를 복사해 둔다.
-- 조인으로 거르면 사업체의 공연마다 예매를 모두 모아 정렬해야 해서, 예매가 많은 판매자일수록 느려진다.
-- 공연의 사업체는 바뀌지 않아서 복사본이 원본과 어긋날 일이 없다. (performance_id 를 복사해 둔 것과 같은 이유)
ALTER TABLE booking ADD COLUMN business_id BIGINT;

-- 이미 있는 예매는 공연에서 채운 뒤에 NOT NULL 을 건다.
UPDATE booking b
   SET business_id = p.business_id
  FROM performance p
 WHERE p.id = b.performance_id;

ALTER TABLE booking ALTER COLUMN business_id SET NOT NULL;

-- 판매자 예매 목록: 사업체별 최신 예매순. 앞에서 한 페이지만 읽고 끝난다.
CREATE INDEX idx_booking_business_created ON booking (business_id, created_at DESC, id DESC);

-- 관리자 예매 목록: 전체 최신 예매순.
CREATE INDEX idx_booking_created ON booking (created_at DESC, id DESC);
