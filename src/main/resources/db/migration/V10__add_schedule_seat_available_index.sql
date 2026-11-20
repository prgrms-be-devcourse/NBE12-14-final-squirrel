-- 구역 요약의 잔여석 집계(회차의 판매 가능 좌석을 구역별로 센다)를 위한 부분 인덱스.
-- 회차마다 판매 좌석이 수천 개인데 그중 판매 가능한 것만 담아서 훑는 양이 적고, 팔릴수록 인덱스도 줄어든다.
-- 부하 테스트에서 이 집계가 DB 시간의 약 28%였다. (기존 idx_schedule_seat_zone_status 는 구역 좌석 조회가 계속 쓴다)
CREATE INDEX idx_schedule_seat_available ON schedule_seat (schedule_id, zone_id) WHERE status = 'AVAILABLE';
