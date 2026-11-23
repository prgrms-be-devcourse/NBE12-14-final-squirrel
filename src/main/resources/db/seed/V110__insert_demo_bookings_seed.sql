-- 시연 유저와 예매. 공연 화면이 텅 비어 보이지 않게, 이미 예매한 사람들이 있는 상태로 시작한다.
-- 시연 유저 60명, 결제 완료 예매 48건(판매 완료 좌석 138석), 결제 대기(점유 중) 예매 12건(점유 좌석 18석).
-- 자주 팔리는 앞쪽 열의 플로어·1층 구역에 많이 몰리고 2·3층은 드문드문 있다. 두 회차에 나눠 담았다. (V107 의 시연 공연)
--
-- 주의 (이 시드는 모든 환경, 운영 포함에 적용된다)
--   - 시연 유저는 로그인할 수 있는 계정이 아니다. 이메일은 .invalid 이고, 비밀번호는 아무도 모르는 무작위 값의 BCrypt 해시다. (V106 과 같은 이유)
--   - 결제 완료 예매의 결제(payment)는 시연용 기록이다. 결제 키는 seed_ 로 시작하는 가짜 값이고 토스에 실제 결제가 없다. 환불·정산의 기준으로 쓰지 않는다.
--   - 결제 대기 예매는 만료 시각을 1년 뒤로 두었다. 만료 스케줄러(1분마다)가 만료된 결제 대기를 풀기 때문에, 7분으로 두면 시드를 넣자마자
--     점유가 풀려서 "점유 중인 좌석"이 보이지 않는다. 1년 뒤에는 스케줄러가 풀어 준다.
--   - 한 유저에 예매 한 건이고 좌석은 4장 이하다. (1인 최대 매수 4장)
--   - 어느 구역·열·번호인지는 아래 표에 직접 적었다. 좌석은 판매 좌석(V107)에 있는 AVAILABLE 이어야 하고, 하나라도 없으면 시드가 실패한다.

-- 1) 시연 유저
INSERT INTO user_member (email, password, name, nickname, phone, status, email_verified_at, created_at, updated_at)
SELECT 'demo-user-' || lpad(u.no::text, 2, '0') || '@tickting.invalid', '$2a$10$x9spbNaGAunnyVfAgLQDou0yvN.tnwooja4TYwCVk.eKANHQOE6o.', u.name, u.nickname, u.phone, 'ACTIVE',
       NOW() - INTERVAL '3 days', NOW() - INTERVAL '3 days', NOW() - INTERVAL '3 days'
FROM (VALUES
        (1, '최지민', '무대앞줄01', '01000000001'),
        (2, '조현우', '티켓팅고수02', '01000000002'),
        (3, '임서연', '밴드덕후03', '01000000003'),
        (4, '박시우', '앞열사수04', '01000000004'),
        (5, '강지안', '공연메이트05', '01000000005'),
        (6, '장도윤', '음악산책06', '01000000006'),
        (7, '이하은', '라이브좋아07', '01000000007'),
        (8, '정준서', '응원봉08', '01000000008'),
        (9, '윤지우', '앵콜요청09', '01000000009'),
        (10, '김주원', '콘서트러버10', '01000000010'),
        (11, '최채원', '무대앞줄11', '01000000011'),
        (12, '조하준', '티켓팅고수12', '01000000012'),
        (13, '임수아', '밴드덕후13', '01000000013'),
        (14, '박우진', '앞열사수14', '01000000014'),
        (15, '강서윤', '공연메이트15', '01000000015'),
        (16, '장건우', '음악산책16', '01000000016'),
        (17, '이소연', '라이브좋아17', '01000000017'),
        (18, '정예준', '응원봉18', '01000000018'),
        (19, '윤다은', '앵콜요청19', '01000000019'),
        (20, '김민준', '콘서트러버20', '01000000020'),
        (21, '최지민', '무대앞줄21', '01000000021'),
        (22, '조현우', '티켓팅고수22', '01000000022'),
        (23, '임서연', '밴드덕후23', '01000000023'),
        (24, '박시우', '앞열사수24', '01000000024'),
        (25, '강지안', '공연메이트25', '01000000025'),
        (26, '장도윤', '음악산책26', '01000000026'),
        (27, '이하은', '라이브좋아27', '01000000027'),
        (28, '정준서', '응원봉28', '01000000028'),
        (29, '윤지우', '앵콜요청29', '01000000029'),
        (30, '김주원', '콘서트러버30', '01000000030'),
        (31, '최채원', '무대앞줄31', '01000000031'),
        (32, '조하준', '티켓팅고수32', '01000000032'),
        (33, '임수아', '밴드덕후33', '01000000033'),
        (34, '박우진', '앞열사수34', '01000000034'),
        (35, '강서윤', '공연메이트35', '01000000035'),
        (36, '장건우', '음악산책36', '01000000036'),
        (37, '이소연', '라이브좋아37', '01000000037'),
        (38, '정예준', '응원봉38', '01000000038'),
        (39, '윤다은', '앵콜요청39', '01000000039'),
        (40, '김민준', '콘서트러버40', '01000000040'),
        (41, '최지민', '무대앞줄41', '01000000041'),
        (42, '조현우', '티켓팅고수42', '01000000042'),
        (43, '임서연', '밴드덕후43', '01000000043'),
        (44, '박시우', '앞열사수44', '01000000044'),
        (45, '강지안', '공연메이트45', '01000000045'),
        (46, '장도윤', '음악산책46', '01000000046'),
        (47, '이하은', '라이브좋아47', '01000000047'),
        (48, '정준서', '응원봉48', '01000000048'),
        (49, '윤지우', '앵콜요청49', '01000000049'),
        (50, '김주원', '콘서트러버50', '01000000050'),
        (51, '최채원', '무대앞줄51', '01000000051'),
        (52, '조하준', '티켓팅고수52', '01000000052'),
        (53, '임수아', '밴드덕후53', '01000000053'),
        (54, '박우진', '앞열사수54', '01000000054'),
        (55, '강서윤', '공연메이트55', '01000000055'),
        (56, '장건우', '음악산책56', '01000000056'),
        (57, '이소연', '라이브좋아57', '01000000057'),
        (58, '정예준', '응원봉58', '01000000058'),
        (59, '윤다은', '앵콜요청59', '01000000059'),
        (60, '김민준', '콘서트러버60', '01000000060')
     ) AS u(no, name, nickname, phone)
WHERE NOT EXISTS (SELECT 1 FROM user_member m WHERE m.email = 'demo-user-' || lpad(u.no::text, 2, '0') || '@tickting.invalid');

-- 2) 예매 계획. 한 줄이 예매 한 건이다: (번호, 유저 번호, 회차 순서(1 = 먼저 열리는 회차), 구역, 열, 시작 번호, 좌석 수, 상태, 몇 분 전에 예매했는지)
CREATE TEMPORARY TABLE demo_booking (
    no INTEGER, user_no INTEGER, schedule_no INTEGER, zone_name TEXT, row_label TEXT,
    seat_from INTEGER, seat_count INTEGER, status TEXT, minutes_ago INTEGER
) ON COMMIT DROP;
INSERT INTO demo_booking VALUES
        (1, 1, 1, '플로어 B구역', '1열', 1, 2, 'PAID', 20),
        (2, 2, 1, '플로어 C구역', '2열', 6, 3, 'PAID', 39),
        (3, 3, 2, '플로어 D구역', '3열', 23, 4, 'PAID', 58),
        (4, 4, 1, '플로어 E구역', '4열', 16, 2, 'PAID', 77),
        (5, 5, 1, '플로어 F구역', '1열', 21, 3, 'PAID', 96),
        (6, 6, 2, '플로어 A구역', '2열', 9, 2, 'PAID', 115),
        (7, 7, 1, '플로어 B구역', '3열', 1, 4, 'PAID', 134),
        (8, 8, 1, '플로어 C구역', '4열', 12, 3, 'PAID', 153),
        (9, 9, 2, '플로어 D구역', '1열', 14, 2, 'PAID', 172),
        (10, 10, 1, '플로어 E구역', '2열', 22, 3, 'PAID', 191),
        (11, 11, 1, '플로어 F구역', '3열', 5, 4, 'PAID', 210),
        (12, 12, 2, '플로어 A구역', '4열', 5, 2, 'PAID', 229),
        (13, 13, 1, '플로어 B구역', '1열', 13, 3, 'PAID', 248),
        (14, 14, 1, '플로어 C구역', '2열', 1, 2, 'PAID', 267),
        (15, 15, 1, '103구역', 'C열', 8, 4, 'PAID', 286),
        (16, 16, 1, '112구역', 'D열', 8, 3, 'PAID', 305),
        (17, 17, 2, '107구역', 'E열', 11, 2, 'PAID', 324),
        (18, 18, 1, '102구역', 'F열', 14, 3, 'PAID', 343),
        (19, 19, 1, '110구역', 'A열', 1, 4, 'PAID', 362),
        (20, 20, 2, '105구역', 'B열', 16, 2, 'PAID', 381),
        (21, 21, 1, '114구역', 'C열', 6, 3, 'PAID', 400),
        (22, 22, 1, '109구역', 'D열', 16, 2, 'PAID', 419),
        (23, 23, 2, '104구역', 'E열', 6, 4, 'PAID', 438),
        (24, 24, 1, '113구역', 'F열', 6, 3, 'PAID', 457),
        (25, 25, 1, '108구역', 'A열', 5, 2, 'PAID', 476),
        (26, 26, 2, '103구역', 'B열', 16, 3, 'PAID', 495),
        (27, 27, 1, '112구역', 'C열', 3, 4, 'PAID', 514),
        (28, 28, 1, '107구역', 'D열', 10, 2, 'PAID', 533),
        (29, 29, 1, '203구역', 'D열', 9, 3, 'PAID', 552),
        (30, 30, 1, '212구역', 'E열', 13, 2, 'PAID', 571),
        (31, 31, 2, '208구역', 'A열', 1, 4, 'PAID', 590),
        (32, 32, 1, '204구역', 'B열', 12, 3, 'PAID', 609),
        (33, 33, 1, '213구역', 'C열', 23, 2, 'PAID', 628),
        (34, 34, 2, '210구역', 'D열', 6, 3, 'PAID', 647),
        (35, 35, 1, '206구역', 'E열', 9, 4, 'PAID', 666),
        (36, 36, 1, '202구역', 'A열', 5, 2, 'PAID', 685),
        (37, 37, 2, '211구역', 'B열', 1, 3, 'PAID', 704),
        (38, 38, 1, '207구역', 'C열', 4, 2, 'PAID', 723),
        (39, 39, 1, '203구역', 'D열', 2, 4, 'PAID', 742),
        (40, 40, 2, '212구역', 'E열', 16, 3, 'PAID', 761),
        (41, 41, 1, '309구역', 'A열', 19, 2, 'PAID', 780),
        (42, 42, 1, '311구역', 'B열', 24, 3, 'PAID', 799),
        (43, 43, 2, '312구역', 'C열', 3, 4, 'PAID', 818),
        (44, 44, 1, '313구역', 'D열', 16, 2, 'PAID', 837),
        (45, 45, 1, '314구역', 'A열', 1, 3, 'PAID', 856),
        (46, 46, 2, '308구역', 'B열', 31, 2, 'PAID', 875),
        (47, 47, 1, '309구역', 'C열', 29, 4, 'PAID', 894),
        (48, 48, 1, '311구역', 'D열', 2, 3, 'PAID', 913),
        (49, 49, 1, '플로어 B구역', '1열', 7, 1, 'PENDING', 932),
        (50, 50, 1, '플로어 C구역', '2열', 12, 2, 'PENDING', 951),
        (51, 51, 2, '플로어 D구역', '4열', 14, 1, 'PENDING', 970),
        (52, 52, 1, '플로어 E구역', '4열', 6, 2, 'PENDING', 989),
        (53, 53, 1, '103구역', 'E열', 21, 1, 'PENDING', 1008),
        (54, 54, 1, '112구역', 'F열', 14, 2, 'PENDING', 1027),
        (55, 55, 2, '107구역', 'A열', 7, 1, 'PENDING', 1046),
        (56, 56, 1, '102구역', 'B열', 10, 2, 'PENDING', 1065),
        (57, 57, 1, '203구역', 'B열', 17, 1, 'PENDING', 1084),
        (58, 58, 1, '212구역', 'C열', 1, 2, 'PENDING', 1103),
        (59, 59, 1, '309구역', 'C열', 35, 1, 'PENDING', 1122),
        (60, 60, 1, '311구역', 'D열', 26, 2, 'PENDING', 1141);

-- 3) 계획한 좌석이 모두 판매 가능한지 확인한다. 모자라면 멈춘다.
DO $$
DECLARE
    expected INTEGER;
    found    INTEGER;
BEGIN
    SELECT SUM(seat_count) INTO expected FROM demo_booking;
    SELECT COUNT(*) INTO found
    FROM demo_booking d
    JOIN performance p ON p.title = '가상 월드투어 콘서트 (샘플)'
    JOIN (SELECT s.id, s.performance_id, ROW_NUMBER() OVER (PARTITION BY s.performance_id ORDER BY s.start_at) AS rn
      FROM performance_schedule s) sch ON sch.performance_id = p.id AND sch.rn = d.schedule_no
    JOIN venue_zone z ON z.venue_id = p.venue_id AND z.name = d.zone_name
    CROSS JOIN LATERAL generate_series(d.seat_from, d.seat_from + d.seat_count - 1) AS n(num)
    JOIN venue_seat vs ON vs.zone_id = z.id AND vs.row_label = d.row_label AND vs.seat_number = n.num::text
    JOIN schedule_seat ss ON ss.schedule_id = sch.id AND ss.venue_seat_id = vs.id AND ss.status = 'AVAILABLE';
    IF found <> expected THEN
        RAISE EXCEPTION '시연 예매의 좌석이 모자랍니다. 계획 %석 중 판매 가능한 좌석은 %석입니다.', expected, found;
    END IF;
END
$$;

-- 4) 예매. 금액은 좌석의 등급 가격 합계에 수수료(좌석당 공연의 예매 수수료)를 더한다. 취소 규정은 공연의 규정을 예매 시점 값으로 복사한다.
INSERT INTO booking (booking_no, user_id, schedule_id, performance_id, status, receive_type, orderer_name, orderer_phone,
                     orderer_email, ticket_amount, booking_fee_amount, total_amount, cancel_policy_snapshot, expires_at,
                     created_at, updated_at)
SELECT to_char(NOW(), 'YYYYMMDD') || 'SEED' || lpad(d.no::text, 3, '0'),
       m.id, sch.id, p.id, d.status, 'ONSITE', m.name, m.phone, m.email,
       t.ticket_amount, p.booking_fee * d.seat_count, t.ticket_amount + p.booking_fee * d.seat_count,
       (SELECT jsonb_agg(jsonb_build_object('daysFrom', r.days_from, 'daysTo', r.days_to, 'feeRate', r.fee_rate,
                                           'cancelable', r.cancelable) ORDER BY r.days_from DESC)
        FROM cancel_policy_rule r WHERE r.policy_id = p.cancel_policy_id),
       CASE d.status WHEN 'PAID' THEN NOW() - make_interval(mins => d.minutes_ago) + INTERVAL '7 minutes'
                     ELSE NOW() + INTERVAL '365 days' END,
       NOW() - make_interval(mins => d.minutes_ago),
       NOW() - make_interval(mins => d.minutes_ago) + CASE d.status WHEN 'PAID' THEN INTERVAL '3 minutes' ELSE INTERVAL '0 minutes' END
FROM demo_booking d
JOIN user_member m ON m.email = 'demo-user-' || lpad(d.user_no::text, 2, '0') || '@tickting.invalid'
JOIN performance p ON p.title = '가상 월드투어 콘서트 (샘플)'
JOIN (SELECT s.id, s.performance_id, ROW_NUMBER() OVER (PARTITION BY s.performance_id ORDER BY s.start_at) AS rn
      FROM performance_schedule s) sch ON sch.performance_id = p.id AND sch.rn = d.schedule_no
JOIN venue_zone z ON z.venue_id = p.venue_id AND z.name = d.zone_name
CROSS JOIN LATERAL (
    SELECT COALESCE(SUM(ss.price), 0) AS ticket_amount
    FROM generate_series(d.seat_from, d.seat_from + d.seat_count - 1) AS n(num)
    JOIN venue_seat vs ON vs.zone_id = z.id AND vs.row_label = d.row_label AND vs.seat_number = n.num::text
    JOIN schedule_seat ss ON ss.schedule_id = sch.id AND ss.venue_seat_id = vs.id
) t
WHERE NOT EXISTS (SELECT 1 FROM booking b WHERE b.booking_no = to_char(NOW(), 'YYYYMMDD') || 'SEED' || lpad(d.no::text, 3, '0'));

-- 5) 예매 좌석. 구역·열·번호·등급·가격을 예매에 복사해 저장한다. (예매 서비스가 하는 일과 같다)
INSERT INTO booking_seat (booking_id, schedule_seat_id, zone_name, row_label, seat_number, grade_name, price, booking_fee, active)
SELECT b.id, ss.id, z.name, vs.row_label, vs.seat_number, g.name, ss.price, p.booking_fee, TRUE
FROM demo_booking d
JOIN performance p ON p.title = '가상 월드투어 콘서트 (샘플)'
JOIN (SELECT s.id, s.performance_id, ROW_NUMBER() OVER (PARTITION BY s.performance_id ORDER BY s.start_at) AS rn
      FROM performance_schedule s) sch ON sch.performance_id = p.id AND sch.rn = d.schedule_no
JOIN booking b ON b.booking_no = to_char(NOW(), 'YYYYMMDD') || 'SEED' || lpad(d.no::text, 3, '0')
JOIN venue_zone z ON z.venue_id = p.venue_id AND z.name = d.zone_name
CROSS JOIN LATERAL generate_series(d.seat_from, d.seat_from + d.seat_count - 1) AS n(num)
JOIN venue_seat vs ON vs.zone_id = z.id AND vs.row_label = d.row_label AND vs.seat_number = n.num::text
JOIN schedule_seat ss ON ss.schedule_id = sch.id AND ss.venue_seat_id = vs.id
JOIN seat_grade g ON g.id = ss.grade_id
WHERE NOT EXISTS (SELECT 1 FROM booking_seat x WHERE x.schedule_seat_id = ss.id AND x.active);

-- 6) 결제 완료 예매의 결제 기록. 시연용이라 토스에 실제 결제는 없다.
INSERT INTO payment (booking_id, order_id, payment_key, method, amount, status, approved_at, created_at)
SELECT b.id, 'BK' || b.booking_no || '-01', 'seed_' || b.booking_no, '카드', b.total_amount, 'DONE',
       b.updated_at, b.created_at + INTERVAL '1 minute'
FROM booking b
WHERE b.booking_no LIKE to_char(NOW(), 'YYYYMMDD') || 'SEED%' AND b.status = 'PAID'
  AND NOT EXISTS (SELECT 1 FROM payment x WHERE x.booking_id = b.id);

-- 7) 판매 좌석의 상태. 결제 완료는 판매 완료(SOLD), 결제 대기는 점유 중(HELD, 점유자와 만료 시각을 적는다)
UPDATE schedule_seat ss
SET status = CASE b.status WHEN 'PAID' THEN 'SOLD' ELSE 'HELD' END,
    held_by = CASE b.status WHEN 'PAID' THEN NULL ELSE b.user_id END,
    held_until = CASE b.status WHEN 'PAID' THEN NULL ELSE b.expires_at END,
    updated_at = NOW()
FROM booking_seat bs
JOIN booking b ON b.id = bs.booking_id
WHERE bs.schedule_seat_id = ss.id AND bs.active
  AND b.booking_no LIKE to_char(NOW(), 'YYYYMMDD') || 'SEED%' AND ss.status = 'AVAILABLE';
