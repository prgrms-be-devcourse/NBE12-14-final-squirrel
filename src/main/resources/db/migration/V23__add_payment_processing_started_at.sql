-- 결제 보정 스케줄러는 "승인을 시작한 지 얼마나 지났나"로 승인 중(PROCESSING) 결제를 고른다.
-- created_at 은 결제를 준비한 시각이라, 준비한 지 오래된 결제를 방금 승인 중으로 바꿨을 때 지금 토스에서 응답을 기다리는 승인을
-- "오래 남은 결제"로 오해하고 건드릴 수 있어서 승인을 시작한 시각을 따로 둔다. (refund.last_attempted_at 과 같은 이유)
ALTER TABLE payment ADD COLUMN processing_started_at TIMESTAMP;
-- 이미 승인 중으로 남은 결제가 있으면 준비한 시각으로 채운다. 보정 작업이 곧바로 대상으로 삼아 토스에 조회해서 확정한다.
UPDATE payment SET processing_started_at = created_at WHERE status = 'PROCESSING';

-- 보정 대상(준비·승인 중)만 담는 부분 인덱스. 승인이 끝난 결제가 쌓여도 스케줄러가 읽는 인덱스는 작게 유지된다.
CREATE INDEX idx_payment_reconcile ON payment (created_at) WHERE status IN ('READY', 'PROCESSING');
