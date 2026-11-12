-- 로컬 개발용 공연 시드. 판매자·예매자 기능을 바로 테스트할 수 있게 상황별 공연 4개를 넣는다.
--   1) 가상 콘서트 (샘플)      가상 아레나, 판매 중, 회차 2개. 3,022석 x 2회차 (많은 데이터, 부하 테스트용)
--   2) 작은 연극 (샘플)        소극장, 판매 중, 회차 1개, 30석. 1인 최대 2장이라 매수 한도를 확인하기 쉽다.
--   3) 곧 오픈하는 뮤지컬 (샘플) 소극장, 판매 오픈 12시간 전이라 판매 좌석을 아직 만들지 않은 회차. 좌석 생성 스케줄러 테스트용
--   4) 작성 중인 공연 (샘플)    DRAFT. 예매자에게 보이지 않는지 확인하는 용도
--
-- 사업체 번호와 등록한 판매자 번호는 둘 다 1 로 넣는다. 계정(비밀번호가 필요하다)은 시드로 넣지 않고,
-- 로컬에서 가장 먼저 가입한 판매자 사업체(번호 1)가 이 공연들의 주인이 된다.
-- 날짜는 시드를 적용한 시점 기준 상대값이라 DB 를 새로 만들 때마다 "지금 판매 중"인 상태가 된다.

-- 공연 1: 가상 콘서트 (샘플)
INSERT INTO performance (business_id, venue_id, created_by, cancel_policy_id, title, genre, age_limit, running_time,
                         cast_info, poster_url, notice, guide, status, sale_open_at, sale_close_at,
                         max_per_person, receive_type, booking_fee, created_at, updated_at)
SELECT 1, v.id, 1, (SELECT id FROM cancel_policy WHERE business_id IS NULL AND name = '기본 규정'),
       '가상 콘서트 (샘플)', 'CONCERT', 12, 150,
       '가상 밴드', 'https://example.com/posters/sample-concert.jpg',
       '공연 시작 30분 전부터 입장할 수 있습니다.', '촬영·녹음은 금지됩니다.',
       'PUBLISHED', NOW() - INTERVAL '1 day', DATE_TRUNC('day', NOW()) + INTERVAL '30 days 18 hours',
       4, 'BOTH', 2000, NOW(), NOW()
FROM venue v WHERE v.name = '가상 아레나 (샘플)';

-- 공연 2: 작은 연극 (샘플)
INSERT INTO performance (business_id, venue_id, created_by, cancel_policy_id, title, genre, age_limit, running_time,
                         cast_info, poster_url, notice, guide, status, sale_open_at, sale_close_at,
                         max_per_person, receive_type, booking_fee, created_at, updated_at)
SELECT 1, v.id, 1, (SELECT id FROM cancel_policy WHERE business_id IS NULL AND name = '기본 규정'),
       '작은 연극 (샘플)', 'PLAY', 0, 90,
       '가상 극단', 'https://example.com/posters/sample-play.jpg',
       NULL, NULL,
       'PUBLISHED', NOW() - INTERVAL '1 day', DATE_TRUNC('day', NOW()) + INTERVAL '7 days 18 hours',
       2, 'ONSITE', 1000, NOW(), NOW()
FROM venue v WHERE v.name = '소극장 (테스트용)';

-- 공연 3: 곧 오픈하는 뮤지컬 (샘플). 판매 오픈 12시간 전이면 이미 "오픈 24시간 전"을 지난 상태다.
INSERT INTO performance (business_id, venue_id, created_by, cancel_policy_id, title, genre, age_limit, running_time,
                         cast_info, poster_url, notice, guide, status, sale_open_at, sale_close_at,
                         max_per_person, receive_type, booking_fee, created_at, updated_at)
SELECT 1, v.id, 1, (SELECT id FROM cancel_policy WHERE business_id IS NULL AND name = '기본 규정'),
       '곧 오픈하는 뮤지컬 (샘플)', 'MUSICAL', 8, 120,
       '가상 배우들', 'https://example.com/posters/sample-musical.jpg',
       NULL, NULL,
       'PUBLISHED', NOW() + INTERVAL '12 hours', DATE_TRUNC('day', NOW()) + INTERVAL '10 days 18 hours',
       4, 'BOTH', 1500, NOW(), NOW()
FROM venue v WHERE v.name = '소극장 (테스트용)';

-- 공연 4: 작성 중인 공연 (샘플)
INSERT INTO performance (business_id, venue_id, created_by, cancel_policy_id, title, genre, age_limit, running_time,
                         cast_info, poster_url, notice, guide, status, sale_open_at, sale_close_at,
                         max_per_person, receive_type, booking_fee, created_at, updated_at)
SELECT 1, v.id, 1, (SELECT id FROM cancel_policy WHERE business_id IS NULL AND name = '기본 규정'),
       '작성 중인 공연 (샘플)', 'CLASSIC', 7, 100,
       NULL, 'https://example.com/posters/sample-draft.jpg',
       NULL, NULL,
       'DRAFT', NOW() + INTERVAL '30 days', NOW() + INTERVAL '60 days',
       4, 'ONSITE', 0, NOW(), NOW()
FROM venue v WHERE v.name = '소극장 (테스트용)';

-- 공연 상세 설명용 이미지
INSERT INTO performance_image (performance_id, url, sort_order)
SELECT p.id, i.url, i.sort_order
FROM performance p,
     (VALUES ('https://example.com/details/sample-concert-1.jpg', 1),
             ('https://example.com/details/sample-concert-2.jpg', 2)) AS i(url, sort_order)
WHERE p.title = '가상 콘서트 (샘플)';

-- 등급과 가격. 가상 콘서트는 4등급, 소극장 공연은 3등급이다.
INSERT INTO seat_grade (performance_id, name, price, color)
SELECT p.id, g.name, g.price, g.color
FROM performance p,
     (VALUES ('VIP석', 165000, '#E8503A'), ('R석', 154000, '#F2994A'),
             ('S석', 132000, '#2D9CDB'), ('A석', 99000, '#27AE60')) AS g(name, price, color)
WHERE p.title = '가상 콘서트 (샘플)';

INSERT INTO seat_grade (performance_id, name, price, color)
SELECT p.id, g.name, g.price, g.color
FROM performance p,
     (VALUES ('R석', 30000, '#F2994A'), ('S석', 20000, '#2D9CDB'), ('A석', 10000, '#27AE60')) AS g(name, price, color)
WHERE p.title IN ('작은 연극 (샘플)', '곧 오픈하는 뮤지컬 (샘플)');

-- 구역별 등급. 가상 콘서트는 3,022석 전부 판매 구역이다. (VIP 500 / R 568 / S 1,196 / A 758)
INSERT INTO zone_grade (performance_id, zone_id, grade_id)
SELECT p.id, z.id, g.id
FROM performance p
JOIN venue_zone z ON z.venue_id = p.venue_id
JOIN (VALUES ('VIP석', 'Floor석'),
             ('R석', 'F1구역'), ('R석', 'F2구역'), ('R석', 'F3구역'), ('R석', 'C2-1구역'), ('R석', 'C2-2구역'),
             ('S석', 'A1구역'), ('S석', 'A2구역'), ('S석', 'A3구역'), ('S석', 'A4구역'),
             ('S석', 'E1구역'), ('S석', 'E2구역'), ('S석', 'E3구역'), ('S석', 'E4구역'),
             ('S석', 'C1구역'), ('S석', 'C3구역'),
             ('A석', 'B1구역'), ('A석', 'B2구역'), ('A석', 'D1구역'), ('A석', 'D2구역'),
             ('A석', 'G구역'), ('A석', 'H구역')) AS m(grade_name, zone_name) ON m.zone_name = z.name
JOIN seat_grade g ON g.performance_id = p.id AND g.name = m.grade_name
WHERE p.title = '가상 콘서트 (샘플)';

-- 소극장 공연: A구역 R석, B구역 S석, C구역 A석
INSERT INTO zone_grade (performance_id, zone_id, grade_id)
SELECT p.id, z.id, g.id
FROM performance p
JOIN venue_zone z ON z.venue_id = p.venue_id
JOIN (VALUES ('R석', 'A구역'), ('S석', 'B구역'), ('A석', 'C구역')) AS m(grade_name, zone_name) ON m.zone_name = z.name
JOIN seat_grade g ON g.performance_id = p.id AND g.name = m.grade_name
WHERE p.title IN ('작은 연극 (샘플)', '곧 오픈하는 뮤지컬 (샘플)');

-- 회차. seats_generated_at 이 있으면 판매 좌석을 이미 만든 회차이고(다음 시드가 만든다), 비어 있으면 아직 만들기 전이다.
INSERT INTO performance_schedule (performance_id, start_at, status, pause_reason, seats_generated_at,
                                  created_at, updated_at)
SELECT p.id, DATE_TRUNC('day', NOW()) + s.start_offset, 'ON_SALE', NULL,
       CASE WHEN s.generated THEN NOW() END, NOW(), NOW()
FROM performance p
JOIN (VALUES ('가상 콘서트 (샘플)', INTERVAL '30 days 19 hours', TRUE),
             ('가상 콘서트 (샘플)', INTERVAL '31 days 19 hours', TRUE),
             ('작은 연극 (샘플)', INTERVAL '7 days 19 hours', TRUE),
             ('곧 오픈하는 뮤지컬 (샘플)', INTERVAL '10 days 19 hours', FALSE)) AS s(title, start_offset, generated)
    ON s.title = p.title;

-- 공통 안내. 등록한 관리자 번호는 1 로 넣는다. (관리자 계정은 시드로 넣지 않는다)
INSERT INTO common_guide (type, content, updated_by, updated_at) VALUES
    ('BOOKING_GUIDE', '좌석을 선택한 뒤 7분 안에 결제해야 합니다. 시간이 지나면 선택한 좌석이 풀립니다.', 1, NOW()),
    ('REFUND_GUIDE', '취소 수수료는 공연일까지 남은 기간에 따라 달라지며, 공연 당일에는 취소할 수 없습니다.', 1, NOW());
