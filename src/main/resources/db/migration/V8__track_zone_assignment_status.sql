-- 구역 등급이 없는 상태와 의도적 미판매 상태를 구분한다.
ALTER TABLE zone_grade
    ADD COLUMN assignment_status VARCHAR(30);

-- 기존 등급 연결은 ASSIGNED로 보존한다.
UPDATE zone_grade
SET assignment_status = 'ASSIGNED';

ALTER TABLE zone_grade
    ALTER COLUMN grade_id DROP NOT NULL;

-- 기존에 행이 없던 구역은 과거 정책의 의미를 보존한다.
-- 공개·취소 공연은 미판매, 작성 중 공연은 아직 미설정으로 본다.
INSERT INTO zone_grade (performance_id, zone_id, grade_id, assignment_status)
SELECT p.id,
       vz.id,
       NULL,
       CASE WHEN p.status = 'DRAFT' THEN 'UNSET' ELSE 'INTENTIONALLY_UNSOLD' END
FROM performance p
JOIN venue_zone vz ON vz.venue_id = p.venue_id
LEFT JOIN zone_grade zg ON zg.performance_id = p.id AND zg.zone_id = vz.id
WHERE zg.id IS NULL;

ALTER TABLE zone_grade
    ALTER COLUMN assignment_status SET NOT NULL;

ALTER TABLE zone_grade
    ADD CONSTRAINT ck_zone_grade_assignment_state
    CHECK (
        (assignment_status = 'ASSIGNED' AND grade_id IS NOT NULL)
        OR (assignment_status IN ('UNSET', 'INTENTIONALLY_UNSOLD') AND grade_id IS NULL)
    );
