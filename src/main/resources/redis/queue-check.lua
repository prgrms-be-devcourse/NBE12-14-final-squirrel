-- 입장해 있는지(만료 전인지) 확인한다. 좌석 조회·점유 요청마다 부르므로 되도록 읽기만 한다.
-- 좌석 화면을 떠난다고 알린 뒤 유예 안에 다시 요청이 오면 아직 쓰는 사람이라, 보관해 둔 원래 만료 시각을 되살린다.
-- KEYS: [1] 입장 ZSET  [2] 원래 만료 시각 보관 키   ARGV: [1] 내 멤버   반환: 1=입장, 0=아님
local expires = redis.call('ZSCORE', KEYS[1], ARGV[1])
if not expires then
	return 0
end
local t = redis.call('TIME')
local now = t[1] * 1000 + math.floor(t[2] / 1000)
if tonumber(expires) <= now then
	return 0
end
local original = redis.call('GET', KEYS[2])
if original then
	-- 새로 입장한 자리를 예전의 더 이른 만료 시각으로 줄이지 않는다.
	if tonumber(original) > tonumber(expires) then
		redis.call('ZADD', KEYS[1], original, ARGV[1])
	end
	redis.call('DEL', KEYS[2])
end
return 1
