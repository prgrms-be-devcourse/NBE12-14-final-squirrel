-- 로컬 개발용 약관 시드. 가입 API 를 바로 써 볼 수 있게 필수 약관 2개를 넣는다.
-- 운영의 약관은 별도로 관리한다. (db/seed 는 로컬 프로필에서만 적용된다)
INSERT INTO terms (type, version, content, required, created_at) VALUES
    ('SERVICE', '1.0', '서비스 이용약관 (로컬 개발용 샘플)', TRUE, NOW()),
    ('PRIVACY', '1.0', '개인정보 수집·이용 동의 (로컬 개발용 샘플)', TRUE, NOW());
