-- 끝난 회차 좌석 정리 (doc/part3-performance-area.md 3-5, doc/part5-operations-area.md 5-3)
-- 공연 뒤 일정 기간이 지난 회차의 판매 좌석(schedule_seat)을 지운다. 지우면 판매 현황(SLR-17)이 셀 좌석이 없어지므로,
-- 지우기 직전의 상태별 좌석 수를 회차에 남겨 판매 현황이 그 값을 그대로 보여 주게 한다.
-- 공연 당일부터는 예매자가 취소할 수 없어서, 지울 무렵에는 판매 수가 더 바뀌지 않는다.
ALTER TABLE performance_schedule
    -- 판매 좌석을 지운 시각. 비어 있으면 아직 지우지 않은 회차다
    ADD COLUMN seats_purged_at       TIMESTAMP,
    -- 지우기 직전의 상태별 좌석 수. seats_purged_at 이 있을 때만 값이 있다
    ADD COLUMN final_sold_seats      INTEGER,
    ADD COLUMN final_held_seats      INTEGER,
    ADD COLUMN final_available_seats INTEGER,
    ADD COLUMN final_reserved_seats  INTEGER,
    ADD COLUMN final_blocked_seats   INTEGER;

-- 정리 대상 찾기: 좌석을 만들었고 아직 지우지 않은 회차를 공연 일시로 찾는다.
-- 지운 회차는 인덱스에서 빠져서, 회차가 쌓여도 인덱스는 좌석이 남아 있는 회차만큼만 커진다.
CREATE INDEX idx_performance_schedule_seats_purge ON performance_schedule (start_at)
    WHERE seats_generated_at IS NOT NULL AND seats_purged_at IS NULL;
