-- 본인인증(휴대폰 인증)은 유료라 쓰지 않기로 했다. 가입할 때 이름·휴대폰을 직접 입력받고 이메일 인증(V11)으로 대신한다.
-- 본인인증으로만 채우던 컬럼과, 그 값을 쓰던 UNIQUE 인덱스(uq_user_ci, uq_seller_business_ci)를 지운다.
-- 컬럼을 지우면 PostgreSQL 이 그 컬럼의 인덱스도 함께 지운다.
ALTER TABLE user_member
    DROP COLUMN ci_hash,
    DROP COLUMN birth_date;

ALTER TABLE seller_member
    DROP COLUMN ci_hash;
