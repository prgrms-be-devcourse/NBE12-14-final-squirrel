-- 서비스가 먼저 확인하는 규칙의 마지막 방어선을 DB 에 둔다.
-- 서비스 확인은 동시 요청이 함께 통과할 수 있어서, 같은 값이 동시에 들어올 수 있는 것에만 UNIQUE 인덱스를 둔다.
-- UNIQUE 는 유니크 인덱스로 만들어져 쓰기 때 인덱스 갱신 비용이 들지만, 서비스의 중복 확인 조회도 이 인덱스를 쓴다.
-- 값의 범위(가격 0 이상, 수수료율 0~100, 이메일 소문자 같은 것)는 DB CHECK 로 두지 않고 요청 DTO 와 서비스가 검사한다.

-- 같은 공연에 같은 등급이 둘이면 예매자 가격표에 같은 이름이 두 줄 나와 구분할 수 없다. (PERFORMANCE302)
-- 등급 수정끼리는 공연 단위 분산락으로 막지만, 같은 등급의 동시 추가와 추가·수정이 겹치는 경우는 이 인덱스가 막는다.
CREATE UNIQUE INDEX uq_seat_grade_performance_name ON seat_grade (performance_id, name);

-- 같은 사업체 안에서 대소문자만 다른 규정 이름도 같은 이름으로 본다. (PERFORMANCE104, 서비스 비교도 LOWER)
-- 기본 규정은 사업체 번호가 비어 있어서 이 인덱스로는 "기본 규정과 같은 이름"을 막지 못하고 서비스 확인만 한다.
CREATE UNIQUE INDEX uq_cancel_policy_business_name ON cancel_policy (business_id, lower(name));

-- 규정 수정은 구간을 모두 지운 뒤 새로 넣는다. 분산락이 듣지 않을 때(Redis 장애·락 만료) 동시 수정이 구간을 두 벌로 저장하는 것을 막는다.
CREATE UNIQUE INDEX uq_cancel_policy_rule_days_from ON cancel_policy_rule (policy_id, days_from);
