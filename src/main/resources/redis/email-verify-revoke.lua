-- 발급을 되돌린다. 하루 횟수는 돌려주고, 인증번호는 "저장된 번호가 이 발급의 것일 때만" 지운다.
-- 조건 없이 지우면 늦게 실패한 첫 발송이, 그사이 다시 받아서 만든 새 번호를 지운다.
-- KEYS[1] 인증번호 키(해시), KEYS[2] 하루 발송 횟수 키(발급 때의 날짜), KEYS[3] 재발송 간격 키
-- ARGV[1] 발급 식별값, ARGV[2] "1" 이면 재발송 간격도 지운다 (발송 대기가 가득 차서 접수하지 못한 경우. 나중에 발송이 실패한 경우는 간격을 그대로 둔다)
local count = tonumber(redis.call('GET', KEYS[2]) or '0')
if count > 0 then
	redis.call('DECR', KEYS[2])
end
if redis.call('HGET', KEYS[1], 'nonce') == ARGV[1] then
	redis.call('DEL', KEYS[1])
end
if ARGV[2] == '1' then
	redis.call('DEL', KEYS[3])
end
return 1
