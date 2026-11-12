-- 로컬 개발용 관리자 계정 1명. 관리자는 가입이 없어서 시드로 넣는다. (사업체 승인·반려, 환불 재처리 등 관리자 API 를 로컬에서 써 보려는 용도)
--
-- 주의
--   - 이 시드는 로컬(local 프로필)에서만 적용된다. 운영 설정(flyway.locations)에는 db/seed 를 넣지 않는다.
--     운영 관리자 계정은 이 파일이 아니라 배포 때 따로 만든다.
--   - 비밀번호는 저장소에 적지 않는다. 아래는 BCrypt 해시일 뿐이고, 비밀번호는 팀에서 따로 전달한다.
--   - 로그인 아이디(이메일)는 admin@tickting.local 이다.

INSERT INTO admin_member (email, password, name, status, created_at, updated_at)
VALUES ('admin@tickting.local', '$2a$10$9F1fWUhQ27SjgnNIUlIGmeMLzvSUXcpjsoYRJor5TLsL6IDgYxMwW', '개발용 관리자', 'ACTIVE',
        NOW(), NOW());
