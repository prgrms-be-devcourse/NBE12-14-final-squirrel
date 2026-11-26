-- 좌석 화면을 떠난 입장자의 자리를 유예 시간 뒤에 비워지게 한다. 원래 만료 시각은 유예 동안 따로 보관해서, 돌아오면 되살린다.
-- (되살리는 일은 queue-check.lua, queue-poll.lua 가 한다)
-- KEYS: [1] 입장 ZSET(점수=만료 시각 ms)  [2] 원래 만료 시각 보관 키   ARGV: [1] 내 멤버  [2] 유예 ms
-- 반환: 1=유예를 걸었음, 0=바꾼 것 없음
local expires = redis.call('ZSCORE', KEYS[1], ARGV[1])
if not expires then
	return 0
end
local t = redis.call('TIME')
local now = t[1] * 1000 + math.floor(t[2] / 1000)
local deadline = now + tonumber(ARGV[2])
-- 남은 시간이 유예보다 짧으면 그대로 둔다. 이미 유예 중인 자리도 여기에 걸려서, 여러 번 알려 와도 원래 만료 시각을
-- 줄어든 값으로 덮어쓰지 않고 유예가 늘어나지도 않는다.
if tonumber(expires) <= deadline then
	return 0
end
redis.call('SET', KEYS[2], expires, 'PX', ARGV[2])
redis.call('ZADD', KEYS[1], deadline, ARGV[1])
return 1
