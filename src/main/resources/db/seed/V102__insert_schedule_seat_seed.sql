-- 로컬 개발용 판매 좌석 시드. 판매 좌석을 이미 만든 회차(seats_generated_at 이 있는 회차)에 공연장 좌석을 복사한다.
-- 운영에서는 스케줄러가 판매 오픈 24시간 전에 회차 하나씩 같은 방식으로 만든다. (doc/part3 3-5)
--   - 등급이 지정된 구역은 AVAILABLE, 의도적 미판매 구역은 BLOCKED로 만든다.
--   - 곧 오픈하는 뮤지컬 회차는 seats_generated_at 이 비어 있어서 좌석이 만들어지지 않는다.

DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM performance_schedule s
        JOIN zone_grade zg ON zg.performance_id = s.performance_id
        WHERE s.seats_generated_at IS NOT NULL
          AND zg.assignment_status = 'UNSET'
    ) THEN
        RAISE EXCEPTION '판매 좌석 시드 대상에 아직 설정하지 않은 구역이 있습니다.';
    END IF;
END
$$;

INSERT INTO schedule_seat (schedule_id, venue_seat_id, zone_id, grade_id, price, status, version, updated_at)
SELECT s.id,
       vs.id,
       vs.zone_id,
       zg.grade_id,
       g.price,
       CASE
           WHEN zg.assignment_status = 'ASSIGNED' THEN 'AVAILABLE'
           WHEN zg.assignment_status = 'INTENTIONALLY_UNSOLD' THEN 'BLOCKED'
       END,
       0,
       NOW()
FROM performance_schedule s
JOIN performance p ON p.id = s.performance_id
JOIN venue_seat vs ON vs.venue_id = p.venue_id
JOIN zone_grade zg ON zg.performance_id = p.id AND zg.zone_id = vs.zone_id
LEFT JOIN seat_grade g ON g.id = zg.grade_id AND zg.assignment_status = 'ASSIGNED'
WHERE s.seats_generated_at IS NOT NULL;
