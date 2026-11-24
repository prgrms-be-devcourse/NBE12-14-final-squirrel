-- DRAFT 공연은 판매 정책을 나중에 설정할 수 있다.
-- PUBLISHED 전환 시 필수값 검증은 서비스에서 수행한다.
ALTER TABLE performance
    ALTER COLUMN sale_open_at DROP NOT NULL,
    ALTER COLUMN sale_close_at DROP NOT NULL,
    ALTER COLUMN max_per_person DROP NOT NULL,
    ALTER COLUMN receive_type DROP NOT NULL,
    ALTER COLUMN booking_fee DROP NOT NULL;
