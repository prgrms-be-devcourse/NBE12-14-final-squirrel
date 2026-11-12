-- 로컬 개발용 공연장 시드. 좌석 점유·예매를 바로 테스트할 수 있게 공연장 2곳을 넣는다.
--   1) 가상 아레나 (샘플): 22개 구역, 3,022석. doc/venue-seed-sample-arena.json 과 같은 구조다. (많은 좌석, 부하 테스트용)
--   2) 소극장 (테스트용): 3개 구역, 30석. 동시성 테스트나 손으로 확인할 때 쓰는 작은 공연장이다.
-- 좌석은 generate_series 로 펼친다. 구역마다 (열 범위, 번호 범위) 묶음을 적고 열 x 번호만큼 좌석을 만든다.
--
-- 시드는 V100 번대를 쓴다. 팀원이 새 마이그레이션(V7, V8 ...)을 계속 추가해도 번호가 겹치지 않게 하려는 것이다.
-- (db/migration 과 db/seed 는 같은 버전 번호를 공유한다. 로컬은 out-of-order 로 중간 번호도 적용한다.)

INSERT INTO venue (name, address, region, total_seats, layout_key, status, created_at, updated_at) VALUES
    ('가상 아레나 (샘플)', '서울 (가상)', '서울', 0, 'sample-arena', 'ACTIVE', NOW(), NOW()),
    -- layout_key 가 비어 있으면 프론트가 기본 격자 도면(구역 → 열 → 번호)으로 그린다.
    ('소극장 (테스트용)', '서울 (가상)', '서울', 0, NULL, 'ACTIVE', NOW(), NOW());

-- 구역 (적은 순서가 화면에 보이는 순서 sort_order 가 된다)
INSERT INTO venue_zone (venue_id, name, floor, sort_order)
SELECT v.id, z.name, z.floor, z.sort_order
FROM venue v,
     (VALUES (1, 'Floor석', '1층'), (2, 'F1구역', '1층'), (3, 'F2구역', '1층'), (4, 'F3구역', '1층'),
             (5, 'A1구역', '2층'), (6, 'A2구역', '2층'), (7, 'A3구역', '2층'), (8, 'A4구역', '2층'),
             (9, 'G구역', '2층'), (10, 'B1구역', '2층'), (11, 'B2구역', '2층'),
             (12, 'C1구역', '2층'), (13, 'C2-1구역', '2층'), (14, 'C2-2구역', '2층'), (15, 'C3구역', '2층'),
             (16, 'D1구역', '2층'), (17, 'D2구역', '2층'),
             (18, 'E1구역', '2층'), (19, 'E2구역', '2층'), (20, 'E3구역', '2층'), (21, 'E4구역', '2층'),
             (22, 'H구역', '2층')) AS z(sort_order, name, floor)
WHERE v.name = '가상 아레나 (샘플)';

INSERT INTO venue_zone (venue_id, name, floor, sort_order)
SELECT v.id, z.name, z.floor, z.sort_order
FROM venue v,
     (VALUES (1, 'A구역', '1층'), (2, 'B구역', '1층'), (3, 'C구역', '1층')) AS z(sort_order, name, floor)
WHERE v.name = '소극장 (테스트용)';

-- 가상 아레나 좌석.
-- 한 줄이 "열 묶음" 하나다: (순서, 구역, 열 시작, 열 끝, 열 이름 뒤에 붙일 글자, 열 이름 직접 지정, 번호 시작, 번호 끝)
-- 열 이름은 직접 지정하지 않으면 "열 번호 + 글자"(예: 9열)가 된다. 스탠딩처럼 번호가 없는 열은 이름을 직접 적는다.
INSERT INTO venue_seat (venue_id, zone_id, row_label, seat_number, sort_order)
SELECT v.id,
       z.id,
       COALESCE(d.row_name, rn || d.suffix),
       sn::TEXT,
       ROW_NUMBER() OVER (PARTITION BY z.id ORDER BY d.ord, rn, sn)
FROM venue v
JOIN venue_zone z ON z.venue_id = v.id
JOIN (VALUES
        (1,  'Floor석',  NULL::INTEGER, NULL::INTEGER, NULL::TEXT, '스탠딩'::TEXT, 1, 500),
        (2,  'F1구역',   1, 11, '열', NULL, 1, 6),
        (3,  'F2구역',   1, 11, '열', NULL, 1, 20),
        (4,  'F3구역',   1, 11, '열', NULL, 1, 6),
        (5,  'A1구역',   1, 7,  '열', NULL, 1, 10),
        (6,  'A1구역',   8, 8,  '열', NULL, 1, 10),
        (7,  'A1구역',   9, 9,  '열', NULL, 5, 10),
        (8,  'A2구역',   1, 10, '열', NULL, 1, 12),
        (9,  'A3구역',   1, 10, '열', NULL, 1, 12),
        (10, 'A4구역',   1, 7,  '열', NULL, 1, 14),
        (11, 'A4구역',   8, 8,  '열', NULL, 1, 11),
        (12, 'A4구역',   9, 9,  '열', NULL, 1, 6),
        -- G, H 구역은 계단형이라 열마다 좌석 수가 다르다. (1열 2석, 2열 4석 ... 6열 12석)
        (13, 'G구역',    1, 1,  '열', NULL, 1, 2),
        (14, 'G구역',    2, 2,  '열', NULL, 1, 4),
        (15, 'G구역',    3, 3,  '열', NULL, 1, 6),
        (16, 'G구역',    4, 4,  '열', NULL, 1, 8),
        (17, 'G구역',    5, 5,  '열', NULL, 1, 10),
        (18, 'G구역',    6, 6,  '열', NULL, 1, 12),
        (19, 'B1구역',   1, 13, '열', NULL, 1, 10),
        (20, 'B2구역',   1, 9,  '열', NULL, 1, 23),
        (21, 'C1구역',   1, 5,  '열', NULL, 12, 20),
        (22, 'C1구역',   6, 12, '열', NULL, 5, 20),
        (23, 'C2-1구역', 1, 12, '열', NULL, 1, 9),
        (24, 'C2-2구역', 1, 12, '열', NULL, 10, 18),
        (25, 'C3구역',   1, 5,  '열', NULL, 1, 9),
        (26, 'C3구역',   6, 12, '열', NULL, 1, 16),
        (27, 'D1구역',   1, 13, '열', NULL, 1, 10),
        (28, 'D2구역',   1, 9,  '열', NULL, 1, 23),
        (29, 'E1구역',   1, 7,  '열', NULL, 1, 10),
        (30, 'E1구역',   8, 8,  '열', NULL, 1, 10),
        (31, 'E1구역',   9, 9,  '열', NULL, 5, 10),
        (32, 'E2구역',   1, 10, '열', NULL, 1, 12),
        (33, 'E3구역',   1, 10, '열', NULL, 1, 12),
        (34, 'E4구역',   1, 7,  '열', NULL, 1, 14),
        (35, 'E4구역',   8, 8,  '열', NULL, 1, 11),
        (36, 'E4구역',   9, 9,  '열', NULL, 1, 6),
        (37, 'H구역',    1, 1,  '열', NULL, 1, 2),
        (38, 'H구역',    2, 2,  '열', NULL, 1, 4),
        (39, 'H구역',    3, 3,  '열', NULL, 1, 6),
        (40, 'H구역',    4, 4,  '열', NULL, 1, 8),
        (41, 'H구역',    5, 5,  '열', NULL, 1, 10),
        (42, 'H구역',    6, 6,  '열', NULL, 1, 12)
     ) AS d(ord, zone_name, r1, r2, suffix, row_name, n1, n2) ON d.zone_name = z.name
CROSS JOIN LATERAL generate_series(COALESCE(d.r1, 1), COALESCE(d.r2, 1)) AS g1(rn)
CROSS JOIN LATERAL generate_series(d.n1, d.n2) AS g2(sn)
WHERE v.name = '가상 아레나 (샘플)';

-- 소극장 좌석: A구역 2열 x 5석, B구역 2열 x 5석, C구역 1열 x 10석 = 30석
INSERT INTO venue_seat (venue_id, zone_id, row_label, seat_number, sort_order)
SELECT v.id,
       z.id,
       rn || '열',
       sn::TEXT,
       ROW_NUMBER() OVER (PARTITION BY z.id ORDER BY rn, sn)
FROM venue v
JOIN venue_zone z ON z.venue_id = v.id
JOIN (VALUES ('A구역', 1, 2, 1, 5), ('B구역', 1, 2, 1, 5), ('C구역', 1, 1, 1, 10)) AS d(zone_name, r1, r2, n1, n2)
    ON d.zone_name = z.name
CROSS JOIN LATERAL generate_series(d.r1, d.r2) AS g1(rn)
CROSS JOIN LATERAL generate_series(d.n1, d.n2) AS g2(sn)
WHERE v.name = '소극장 (테스트용)';

-- 총 좌석 수는 만든 좌석을 세서 넣는다. (좌석 생성 스크립트가 하는 일과 같다)
UPDATE venue v
SET total_seats = s.cnt
FROM (SELECT venue_id, COUNT(*) AS cnt FROM venue_seat GROUP BY venue_id) s
WHERE s.venue_id = v.id;
