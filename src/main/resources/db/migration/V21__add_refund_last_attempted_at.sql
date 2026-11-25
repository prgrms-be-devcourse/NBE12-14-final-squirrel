-- 환불 재시도 스케줄러는 "마지막으로 토스에 보낸 지 얼마나 지났나"로 대상을 고른다.
-- requested_at 은 처음 요청한 시각이라, 재시도 중인 환불이 요청 중(PROCESSING)으로 오래 남은 것인지 판단할 수 없어서 따로 둔다.
ALTER TABLE refund ADD COLUMN last_attempted_at TIMESTAMP;
UPDATE refund SET last_attempted_at = requested_at;
ALTER TABLE refund ALTER COLUMN last_attempted_at SET NOT NULL;

-- 재시도 대상(실패·요청 중)만 담는 부분 인덱스. 완료된 환불이 쌓여도 스케줄러가 읽는 인덱스는 작게 유지된다.
CREATE INDEX idx_refund_retry ON refund (last_attempted_at) WHERE status IN ('FAILED', 'PROCESSING');
