-- 입장해 있는지(만료 전인지) 확인하고, 활동이 있으면 입장 유효 시간을 늘린다. 좌석 조회·점유 요청마다 부른다.
-- 남은 시간이 유효 시간의 절반 아래일 때만 늘려서 요청마다 쓰지 않게 한다. 입장 상한 표시 키가 사라졌으면(입장한 지 상한이 지남) 늘리지 않는다.
-- 늘린 만료 시각도 상한 표시 키의 남은 시간을 넘지 않는다. 요청을 계속 보내서 자리를 붙잡고 있는 것을 막으려는 것이다.
-- KEYS: [1] 입장 ZSET  [2] 내 입장 상한 표시 키   ARGV: [1] 내 멤버  [2] 입장 유효 ms   반환: 1=입장, 0=아님
local expires = redis.call('ZSCORE', KEYS[1], ARGV[1])
if not expires then
	return 0
end
local t = redis.call('TIME')
local now = t[1] * 1000 + math.floor(t[2] / 1000)
expires = tonumber(expires)
if expires <= now then
	return 0
end
local ttl = tonumber(ARGV[2])
if expires - now < ttl / 2 then
	local cap = redis.call('PTTL', KEYS[2])
	if cap > 0 then
		local renewed = math.min(now + ttl, now + cap)
		if renewed > expires then
			redis.call('ZADD', KEYS[1], 'XX', renewed, ARGV[1])
		end
	end
end
return 1
