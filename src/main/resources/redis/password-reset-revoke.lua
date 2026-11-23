-- 발급을 되돌린다. 하루 횟수는 돌려주고 이 발급의 토큰만 지운다. (다른 링크는 건드리지 않는다)
-- KEYS[1] 토큰 키, KEYS[2] 하루 요청 횟수 키(발급 때의 날짜), KEYS[3] 재요청 간격 키, KEYS[4] 그 계정의 유효 토큰 집합 키
-- ARGV[1] 토큰 해시, ARGV[2] "1" 이면 재요청 간격도 지운다 (발송 대기가 가득 차서 접수하지 못한 경우. 나중에 발송이 실패한 경우는 간격을 그대로 둔다)
local count = tonumber(redis.call('GET', KEYS[2]) or '0')
if count > 0 then
	redis.call('DECR', KEYS[2])
end
redis.call('DEL', KEYS[1])
redis.call('SREM', KEYS[4], ARGV[1])
if ARGV[2] == '1' then
	redis.call('DEL', KEYS[3])
end
return 1
