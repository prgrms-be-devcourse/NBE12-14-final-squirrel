-- 이메일 인증번호를 새로 발급한다. 간격·하루 한도 확인과 저장을 한 번에 해야 두 번 빠르게 눌러도 메일이 한 통만 간다.
-- KEYS[1] 재발송 간격 키, KEYS[2] 하루 발송 횟수 키(날짜가 들어 있다), KEYS[3] 인증번호 키(해시)
-- ARGV[1] 재발송 간격(초), ARGV[2] 하루 한도, ARGV[3] 하루 횟수 키의 만료(초, 지난 키를 치우는 용도)
-- ARGV[4] 인증번호, ARGV[5] 이 발급의 식별값(나중에 실패했을 때 "내가 만든 번호일 때만" 지우려는 것), ARGV[6] 인증번호 유효 시간(초)
-- 반환: 1 발급함, -1 재발송 간격 안, -2 하루 한도 초과
if redis.call('EXISTS', KEYS[1]) == 1 then
	return -1
end
local count = tonumber(redis.call('GET', KEYS[2]) or '0')
if count >= tonumber(ARGV[2]) then
	return -2
end
if redis.call('INCR', KEYS[2]) == 1 then
	redis.call('EXPIRE', KEYS[2], ARGV[3])
end
redis.call('SET', KEYS[1], '1', 'EX', ARGV[1])
-- 이전 인증번호는 여기서 바로 무효가 된다
redis.call('DEL', KEYS[3])
redis.call('HSET', KEYS[3], 'code', ARGV[4], 'nonce', ARGV[5], 'fails', 0)
redis.call('EXPIRE', KEYS[3], ARGV[6])
return 1
