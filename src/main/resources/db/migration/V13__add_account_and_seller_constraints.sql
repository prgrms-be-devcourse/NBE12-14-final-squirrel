-- 서비스가 먼저 확인하는 규칙의 마지막 방어선을 DB 에 둔다.
-- 서비스 확인은 동시 요청이 함께 통과할 수 있고, 시드·관리 작업·다른 코드 경로처럼 서비스를 거치지 않는 쓰기도 막지 못한다.
-- UNIQUE 는 유니크 인덱스로 만들어져 쓰기 때 인덱스 갱신 비용이 들지만, 서비스의 중복 확인 조회도 이 인덱스를 쓴다.
-- CHECK 는 쓰는 행 하나에서 식만 계산해서 비용이 거의 없다.
-- 정책 숫자(가격 상한, 최대 매수 5장, 구간 90일)는 바뀔 수 있어 요청 DTO 에만 두고, 여기에는 정책이 바뀌어도 변하지 않는 규칙만 둔다.

-- 이메일 중복(uq_user_email, uq_seller_email)은 "저장 전에 소문자로 맞춘다"는 전제로 대소문자 구분 없이 막는다.
-- 어떤 경로가 소문자 변환을 빠뜨리면 A@x.com 과 a@x.com 이 둘 다 저장되므로, 소문자가 아닌 값은 저장하지 못하게 한다.
-- (대소문자 무시 식 인덱스로 바꾸지 않은 것은 로그인·중복 확인 조회가 지금처럼 email 그대로를 비교하게 두려는 것이다)
ALTER TABLE user_member
    ADD CONSTRAINT ck_user_email_lowercase CHECK (email = lower(email));

ALTER TABLE seller_member
    ADD CONSTRAINT ck_seller_email_lowercase CHECK (email = lower(email));

-- 반려(REJECTED)는 사유가 남아야 판매자가 무엇을 고쳐야 하는지 안다. (승인하면 사유를 지운다)
ALTER TABLE seller_business
    ADD CONSTRAINT ck_seller_business_reject_reason CHECK (status <> 'REJECTED' OR reject_reason IS NOT NULL);

-- 같은 공연에 같은 등급이 둘이면 예매자 가격표에 같은 이름이 두 줄 나와 구분할 수 없다. (PERFORMANCE302)
-- 등급 수정끼리는 공연 단위 분산락으로 막지만, 같은 등급의 동시 추가와 추가·수정이 겹치는 경우는 이 인덱스가 막는다.
CREATE UNIQUE INDEX uq_seat_grade_performance_name ON seat_grade (performance_id, name);

-- 음수 가격은 결제 금액을 틀어지게 한다.
ALTER TABLE seat_grade
    ADD CONSTRAINT ck_seat_grade_price CHECK (price >= 0);

-- 같은 사업체 안에서 대소문자만 다른 규정 이름도 같은 이름으로 본다. (PERFORMANCE104, 서비스 비교도 LOWER)
-- 기본 규정은 사업체 번호가 비어 있어서 이 인덱스로는 "기본 규정과 같은 이름"을 막지 못하고 서비스 확인만 한다.
CREATE UNIQUE INDEX uq_cancel_policy_business_name ON cancel_policy (business_id, lower(name));

-- 규정 수정은 구간을 모두 지운 뒤 새로 넣는다. 분산락이 듣지 않을 때(Redis 장애·락 만료) 동시 수정이 구간을 두 벌로 저장하는 것을 막는다.
CREATE UNIQUE INDEX uq_cancel_policy_rule_days_from ON cancel_policy_rule (policy_id, days_from);

-- 환불 수수료 계산에 직접 쓰는 값이라 범위를 벗어나면 돈이 틀어진다. days_to 가 비어 있으면 상한이 없는 구간이다.
ALTER TABLE cancel_policy_rule
    ADD CONSTRAINT ck_cancel_policy_rule_fee_rate CHECK (fee_rate BETWEEN 0 AND 100),
    ADD CONSTRAINT ck_cancel_policy_rule_days CHECK (days_from >= 0 AND (days_to IS NULL OR days_to >= days_from));

-- 작성 중(DRAFT) 공연은 판매 정책을 비워 둘 수 있다(V7). CHECK 는 NULL 이면 통과하므로 값이 있을 때만 검사한다.
-- 판매 종료가 오픈보다 늦어야 한다(PERFORMANCE001). 수수료가 음수이거나 1인 매수가 0이면 결제·예매 제한이 틀어진다.
ALTER TABLE performance
    ADD CONSTRAINT ck_performance_sale_period CHECK (sale_close_at > sale_open_at),
    ADD CONSTRAINT ck_performance_booking_fee CHECK (booking_fee >= 0),
    ADD CONSTRAINT ck_performance_max_per_person CHECK (max_per_person >= 1);
