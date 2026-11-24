-- 등급명을 자유 문자열에서 정해진 등급 코드(SeatGradeName)로 바꾼다.
-- 눈으로 같은 등급(R석·R 석·r석)이 여럿 생기고 공연마다 표기가 달라지는 것을 막으려는 것이다. 화면 이름은 프론트가 코드마다 정한다.
-- 기존 행은 표시명이 정확히 같을 때만 코드로 바꾼다. 목록에 없는 이름은 바꿀 수 없어서 그대로 둔다.
UPDATE seat_grade g
SET name = m.code
FROM (VALUES ('VIP석', 'VIP'), ('R석', 'R'), ('S석', 'S'), ('A석', 'A'), ('B석', 'B'), ('C석', 'C'),
             ('OP석', 'OP'), ('시야제한석', 'VIEW_LIMITED'), ('전석', 'ALL'), ('스탠딩석', 'STANDING'),
             ('VIP 스탠딩석', 'VIP_STANDING'), ('지정석', 'RESERVED')) AS m(label, code)
WHERE g.name = m.label;

-- 좌석도 색은 프론트가 등급 코드마다 정한다.
ALTER TABLE seat_grade
    DROP COLUMN color;
