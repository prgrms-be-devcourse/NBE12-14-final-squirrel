-- 결제 준비(READY) 상태를 created_at 순으로 고르는 부분 인덱스가 둘이었다.
--   V5  idx_payment_ready_created    ON payment (created_at) WHERE status = 'READY'
--   V23 idx_payment_reconcile_ready  ON payment (created_at) WHERE status = 'READY'
-- 컬럼과 조건이 같아서 읽기에는 하나로 충분하고, 결제를 만들고 상태를 바꿀 때마다 인덱스를 두 번 갱신하는 낭비만 남았다.
-- 쓰기 비용 차이는 작았다. (전·후 교차 측정에서 결제 1건당 WAL 약 5% 감소, 측정 오차 안. doc/project-log.md 2026-11-27) 그래서 성능보다 중복 정리가 목적이다.
-- 보정 작업이 쓰는 이름(idx_payment_reconcile_ready)을 남기고 V5 쪽을 지운다.
DROP INDEX IF EXISTS idx_payment_ready_created;
