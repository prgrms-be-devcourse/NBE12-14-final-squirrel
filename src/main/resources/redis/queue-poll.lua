-- 대기열 입장·상태 확인. 한 번의 원자적 실행으로 "만료 정리 → (필요하면) 줄 서기 → 빈 자리만큼 입장 → 내 상태"를 처리한다.
-- 서버가 여러 대여도 각자 이 스크립트를 불러도 정원이 넘지 않고 순서가 섞이지 않는다. (Redis 는 스크립트를 한 번에 하나씩 실행한다)
-- 시각은 서버 시계가 아니라 Redis 의 TIME 을 쓴다. 서버마다 시계가 달라도 입장 만료가 어긋나지 않게 하려는 것이다.
--
-- KEYS: [1] 대기 ZSET(점수=도착 순번)  [2] 입장 ZSET(점수=만료 시각 ms)  [3] 도착 순번 카운터  [4] 내 확인 표시 키
--       [5] 좌석 화면을 떠난다고 알렸을 때 보관한 원래 만료 시각 키
-- ARGV: [1] 내 멤버(USER:번호)  [2] 줄 서기 여부(1=들어가기, 0=상태만 보기)  [3] 정원  [4] 입장 유효 ms
--       [5] 확인 표시 유효 ms  [6] 키 유효 ms  [7] 확인 표시 키 접두사
-- 반환: {상태, 값1, 값2}  상태 0=없음, 1=입장(값1=만료 시각 ms), 2=대기(값1=내 순번, 값2=전체 대기 인원)

local wait, active, seq, seen, keep = KEYS[1], KEYS[2], KEYS[3], KEYS[4], KEYS[5]
local member = ARGV[1]
local join = ARGV[2] == '1'
local max_active = tonumber(ARGV[3])
local entry_ttl = tonumber(ARGV[4])
local seen_ttl = tonumber(ARGV[5])
local key_ttl = tonumber(ARGV[6])
local seen_prefix = ARGV[7]

local t = redis.call('TIME')
local now = t[1] * 1000 + math.floor(t[2] / 1000)

redis.call('ZREMRANGEBYSCORE', active, '-inf', now)

-- 이미 입장해 있으면 그대로 돌려준다. (다시 줄을 서지 않는다)
local expires = redis.call('ZSCORE', active, member)
if expires then
	-- 유예 안에 돌아왔으면(새로고침, 뒤로 갔다 다시 들어옴) 원래 만료 시각을 되살린다.
	local original = redis.call('GET', keep)
	if original then
		if tonumber(original) > tonumber(expires) then
			redis.call('ZADD', active, original, member)
			expires = original
		end
		redis.call('DEL', keep)
	end
	return {1, tonumber(expires), 0}
end

if not redis.call('ZRANK', wait, member) then
	if not join then
		return {0, 0, 0}
	end
	-- 도착 순번을 카운터로 만들어 같은 시각에 온 사람도 순서가 갈린다. 이미 줄에 있으면 순번을 바꾸지 않는다.
	redis.call('ZADD', wait, redis.call('INCR', seq), member)
end
redis.call('SET', seen, '1', 'PX', seen_ttl)

-- 빈 자리만큼 앞에서부터 입장시킨다. 오래 확인하지 않은 사람(확인 표시가 사라진 사람)은 자리를 주지 않고 건너뛴다.
local free = max_active - redis.call('ZCARD', active)
while free > 0 do
	local popped = redis.call('ZPOPMIN', wait, 1)
	if #popped == 0 then
		break
	end
	local who = popped[1]
	if redis.call('EXISTS', seen_prefix .. who) == 1 then
		redis.call('ZADD', active, now + entry_ttl, who)
		free = free - 1
	end
end

-- 끝난 회차의 키가 계속 남지 않게 유효 시간을 둔다. 쓸 때마다 늘어난다.
redis.call('PEXPIRE', wait, key_ttl)
redis.call('PEXPIRE', active, key_ttl)
redis.call('PEXPIRE', seq, key_ttl)

expires = redis.call('ZSCORE', active, member)
if expires then
	return {1, tonumber(expires), 0}
end
return {2, redis.call('ZRANK', wait, member) + 1, redis.call('ZCARD', wait)}
