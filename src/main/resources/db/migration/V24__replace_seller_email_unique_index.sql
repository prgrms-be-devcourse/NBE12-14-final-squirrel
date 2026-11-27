-- 판매자 이메일 중복 규칙을 바꾼다. (doc/part1-user-area.md 1-3, doc/api-spec.md AUTH-23~28)
-- 원래는 삭제되지 않은 판매자 계정끼리 이메일이 겹치지 않게 했다. 담당자는 퇴사해도 지우지 않고 비활성화만 해서,
-- A 회사에서 비활성화된 담당자가 같은 이메일로 B 회사에 들어갈 수 없었다.
-- 이제 같은 이메일로 회사마다 계정이 하나씩 있을 수 있고, 사용 중(활성) 계정은 이메일당 하나다.
DROP INDEX uq_seller_email;

-- 사용 중(활성) 계정은 이메일당 하나. 로그인할 계정이 하나로 정해지고, 다른 회사에서 아직 쓰는 사람은 복구·가입이 막힌다
CREATE UNIQUE INDEX uq_seller_email_active ON seller_member (email) WHERE deleted_at IS NULL AND status = 'ACTIVE';

-- 같은 회사 안에서는 이메일당 계정 하나. 같은 회사로 돌아오면 새 계정이 아니라 비활성 계정을 다시 쓴다
CREATE UNIQUE INDEX uq_seller_business_email ON seller_member (business_id, email) WHERE deleted_at IS NULL;
