-- 시연용 판매자(모든 환경). 시드 공연(V107)의 주인이 되는 사업체와 대표 계정을 넣는다.
-- 공연 상세에는 판매자 정보(회사명 등)가 나오는데, 공연의 사업체 행이 없으면 상세가 500 이다. 공연장·공연을 만드는 관리자·판매자 흐름을
-- 시드로 대신하는 것이라 이 사업체도 시드로 넣는다.
--
-- 주의
--   - 이 시드는 모든 환경(운영 포함)에 적용된다. 그래서 로그인할 수 있는 계정이 아니다. 비밀번호를 아무도 모르는 무작위 값의 BCrypt 해시만 넣었다.
--     (운영에 팀이 아는 비밀번호를 넣지 않으려는 것이다) 이메일도 .invalid 라서 실제 주소가 아니다.
--   - 실제 판매자는 가입 → 관리자 승인 흐름으로 들어온다. (관리자 계정은 V109)
--   - 사업체 번호를 정해 넣지 않고 사업자번호로 찾는다. 이미 판매자가 있는 DB 에서도 번호가 겹치지 않는다.

INSERT INTO seller_business (company_name, representative_name, business_number, contact_phone, contact_email,
                             terms_agreed_at, status, reviewed_by, reviewed_at, created_at, updated_at)
SELECT '(주)가상기획 (시연)', '김대표', '0000000002', '01000000000', 'demo-seller@tickting.invalid',
       NOW(), 'APPROVED', NULL, NOW(), NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM seller_business WHERE business_number = '0000000002');

INSERT INTO seller_member (business_id, email, password, name, phone, role, status, created_at, updated_at)
SELECT b.id, 'demo-seller@tickting.invalid', '$2a$10$/RSGBH6XU3Y8XHBBUz9H4.arNaAFAtGfklyZTaG.fI6RdOzQjV46e', '시연 판매자', '01000000000', 'OWNER',
       'ACTIVE', NOW(), NOW()
FROM seller_business b
WHERE b.business_number = '0000000002'
  AND NOT EXISTS (SELECT 1 FROM seller_member WHERE email = 'demo-seller@tickting.invalid');
