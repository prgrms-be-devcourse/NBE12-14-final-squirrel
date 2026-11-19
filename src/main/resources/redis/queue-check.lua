-- 입장해 있는지(만료 전인지) 확인한다. 좌석 조회·점유 요청마다 부르므로 값만 읽는다.
-- KEYS: [1] 입장 ZSET   ARGV: [1] 내 멤버   반환: 1=입장, 0=아님
local expires = redis.call('ZSCORE', KEYS[1], ARGV[1])
if not expires then
	return 0
end
local t = redis.call('TIME')
local now = t[1] * 1000 + math.floor(t[2] / 1000)
if tonumber(expires) > now then
	return 1
end
return 0
