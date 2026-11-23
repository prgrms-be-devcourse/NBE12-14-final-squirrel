-- 운영 관리자 계정 1명. 관리자는 가입이 없어서 시드로 넣는다. (사업체 승인·반려, 환불 재처리 등 관리자 API 에 필요하다)
--
-- 주의
--   - 이 시드는 모든 환경(운영 포함)에 적용된다. 비밀번호는 저장소에 적지 않고 BCrypt 해시만 넣었다.
--     해시는 무작위로 만든 긴 비밀번호의 것이라 이 파일만으로는 알아낼 수 없다. 비밀번호는 만든 사람이 한 번 전달받아 보관한다.
--     (관리자의 비밀번호 변경 API 는 아직 없다. 바꾸려면 새 해시로 갱신하는 시드·마이그레이션을 추가한다)
--   - 로그인 아이디(이메일)는 admin@noresell.store 이다. 이미 같은 이메일의 관리자가 있으면 넣지 않는다.
INSERT INTO admin_member (email, password, name, status, created_at, updated_at)
SELECT 'admin@noresell.store', '$2a$10$zVzwpequiaJbBiaQP4MO4uQKCFtIvGJUDQi.5VR3edYSmdRW/mUH.', '관리자', 'ACTIVE', NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM admin_member WHERE email = 'admin@noresell.store');
