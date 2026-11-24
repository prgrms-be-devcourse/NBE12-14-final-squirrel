-- 인증번호를 확인한다. 맞으면 바로 지워서 한 번만 쓰이고, 같은 번호로 동시에 확인해도 하나만 성공한다.
-- KEYS[1] 인증번호 키(해시)
-- ARGV[1] 사용자가 낸 번호, ARGV[2] 틀려도 되는 최대 횟수
-- 반환: 0 성공, -1 유효한 번호 없음(만료·받은 적 없음·틀린 횟수를 다 써서 폐기), 1 이상 = 틀렸고 남은 횟수
local stored = redis.call('HGET', KEYS[1], 'code')
if not stored then
	return -1
end
if stored == ARGV[1] then
	redis.call('DEL', KEYS[1])
	return 0
end
local fails = redis.call('HINCRBY', KEYS[1], 'fails', 1)
local max = tonumber(ARGV[2])
if fails >= max then
	redis.call('DEL', KEYS[1])
	return -1
end
return max - fails
