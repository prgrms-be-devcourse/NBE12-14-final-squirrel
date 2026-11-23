-- 시연용 공연(모든 환경): 인스파이어 아레나(V105)에서 판매 중인 공연 1개와 회차 2개.
-- 예매자 화면(공연 목록 → 구역 도면 → 좌석 → 결제)을 이 공연장으로 바로 확인하려는 시드다. 공연장 생성·공연 등록 흐름이 아직 다 없어서 시드로 넣는다.
-- 판매자는 V106 의 시연용 사업체다. 날짜는 시드를 적용한 시점 기준 상대값이라 DB 를 새로 만들 때마다 "지금 판매 중"인 상태가 된다.
--
-- 이 파일은 모든 환경(운영 포함)에 적용된다. 판매 좌석(schedule_seat)은 운영에서 스케줄러가 만드는 방식과 같게 이 공연의 회차에만 만든다. 운영에서는 스케줄러가 판매 오픈 24시간 전에 만들지만,
-- 이 시드는 바로 팔리는 상태로 시작하려고 여기서 만든다. (회차마다 10,813석)

-- 공연
INSERT INTO performance (business_id, venue_id, created_by, cancel_policy_id, title, genre, age_limit, running_time,
                         cast_info, poster_url, notice, guide, status, sale_open_at, sale_close_at,
                         max_per_person, receive_type, booking_fee, created_at, updated_at)
SELECT b.id, v.id, m.id, (SELECT id FROM cancel_policy WHERE business_id IS NULL AND name = '기본 규정'),
       '가상 월드투어 콘서트 (샘플)', 'CONCERT', 12, 150,
       '가상 밴드', 'https://team08-tickting-yjh.s3.ap-northeast-2.amazonaws.com/public/124112.jpg',
       '공연 시작 30분 전부터 입장할 수 있습니다.', '촬영·녹음은 금지됩니다.',
       'PUBLISHED', NOW() - INTERVAL '1 day', DATE_TRUNC('day', NOW()) + INTERVAL '8 days 18 hours',
       4, 'BOTH', 2000, NOW(), NOW()
FROM venue v
JOIN seller_business b ON b.business_number = '0000000002'
JOIN seller_member m ON m.business_id = b.id AND m.role = 'OWNER'
WHERE v.name = '인스파이어 아레나';

-- 공연 상세 설명용 이미지
INSERT INTO performance_image (performance_id, url, sort_order)
SELECT p.id, i.url, i.sort_order
FROM performance p,
     (VALUES ('https://team08-tickting-yjh.s3.ap-northeast-2.amazonaws.com/public/images22.jpg', 1),
             ('https://team08-tickting-yjh.s3.ap-northeast-2.amazonaws.com/public/images4242.jpg', 2)) AS i(url, sort_order)
WHERE p.title = '가상 월드투어 콘서트 (샘플)';

-- 등급과 가격. 등급명은 등급 코드(SeatGradeName)이고 색은 프론트가 정한다. (샘플 가격)
INSERT INTO seat_grade (performance_id, name, price)
SELECT p.id, g.name, g.price
FROM performance p,
     (VALUES ('VIP', 165000), ('R', 154000), ('S', 132000), ('A', 99000)) AS g(name, price)
WHERE p.title = '가상 월드투어 콘서트 (샘플)';

-- 구역별 등급. 44개 구역 전부 판매 구역이다.
--   플로어 A~F구역 = VIP, 제한석(101·115·201·215·315구역) = A, 그 밖의 300번대(308~314구역) = S, 나머지 100·200번대 = R
INSERT INTO zone_grade (performance_id, zone_id, grade_id, assignment_status)
SELECT p.id, z.id, g.id, 'ASSIGNED'
FROM performance p
JOIN venue_zone z ON z.venue_id = p.venue_id
JOIN seat_grade g ON g.performance_id = p.id
                 AND g.name = CASE
                                  WHEN z.name LIKE '플로어%' THEN 'VIP'
                                  WHEN z.name IN ('101구역', '115구역', '201구역', '215구역', '315구역') THEN 'A'
                                  WHEN z.name LIKE '3%' THEN 'S'
                                  ELSE 'R'
                              END
WHERE p.title = '가상 월드투어 콘서트 (샘플)';

-- 회차 2개. 판매 좌석을 이미 만든 회차(seats_generated_at)라서 아래에서 좌석을 만든다.
INSERT INTO performance_schedule (performance_id, start_at, status, pause_reason, seats_generated_at,
                                  created_at, updated_at)
SELECT p.id, DATE_TRUNC('day', NOW()) + s.start_offset, 'ON_SALE', NULL, NOW(), NOW(), NOW()
FROM performance p,
     (VALUES (INTERVAL '7 days 19 hours'), (INTERVAL '8 days 19 hours')) AS s(start_offset)
WHERE p.title = '가상 월드투어 콘서트 (샘플)';

-- 판매 좌석을 만들기 전에 구역이 모두 지정됐는지 확인한다. 하나라도 빠지면 그 구역 좌석이 판매 좌석에서 빠진다.
DO $$
DECLARE
    zones    INTEGER;
    assigned INTEGER;
BEGIN
    SELECT COUNT(*) INTO zones
    FROM venue_zone z JOIN venue v ON v.id = z.venue_id WHERE v.name = '인스파이어 아레나';
    SELECT COUNT(*) INTO assigned
    FROM zone_grade zg JOIN performance p ON p.id = zg.performance_id
    WHERE p.title = '가상 월드투어 콘서트 (샘플)' AND zg.assignment_status = 'ASSIGNED';
    IF zones <> assigned THEN
        RAISE EXCEPTION '시드 공연의 구역 등급이 모자랍니다. 구역 %개 중 %개만 지정했습니다.', zones, assigned;
    END IF;
END
$$;

-- 판매 좌석. 공연장 좌석을 회차마다 복사하고, 가격은 구역의 등급 가격이다. (운영에서 스케줄러가 하는 일과 같다. doc/part3 3-5)
INSERT INTO schedule_seat (schedule_id, venue_seat_id, zone_id, grade_id, price, status, version, updated_at)
SELECT s.id, vs.id, vs.zone_id, zg.grade_id, g.price, 'AVAILABLE', 0, NOW()
FROM performance p
JOIN performance_schedule s ON s.performance_id = p.id AND s.seats_generated_at IS NOT NULL
JOIN venue_seat vs ON vs.venue_id = p.venue_id
JOIN zone_grade zg ON zg.performance_id = p.id AND zg.zone_id = vs.zone_id
JOIN seat_grade g ON g.id = zg.grade_id
WHERE p.title = '가상 월드투어 콘서트 (샘플)';

-- 공통 안내. 이미 있으면(예전 로컬 시드가 먼저 넣은 DB) 그대로 둔다. 등록한 관리자 번호는 1 로 적는다. (관리자 계정은 V109)
INSERT INTO common_guide (type, content, updated_by, updated_at) VALUES
    ('BOOKING_GUIDE', '좌석을 선택한 뒤 7분 안에 결제해야 합니다. 시간이 지나면 선택한 좌석이 풀립니다.', 1, NOW()),
    ('REFUND_GUIDE', '취소 수수료는 공연일까지 남은 기간에 따라 달라지며, 공연 당일에는 취소할 수 없습니다.', 1, NOW())
ON CONFLICT (type) DO NOTHING;
