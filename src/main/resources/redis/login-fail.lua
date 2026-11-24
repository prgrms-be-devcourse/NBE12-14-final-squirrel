-- 로그인 실패를 하나 센다. 세기와 만료 설정을 한 번에 해야 둘 사이에 앱이 죽어도 만료 없는 키(영원한 잠금)가 남지 않는다.
-- KEYS[1] 실패 횟수 키
-- ARGV[1] 잠그는 실패 횟수, ARGV[2] 잠금 시간(초)
-- 첫 실패에서 만료를 걸어 그 시간 안의 실패만 세고, 잠기는 순간 만료를 다시 걸어 그때부터 잠금 시간을 센다.
local count = redis.call('INCR', KEYS[1])
if count == 1 or count == tonumber(ARGV[1]) then
	redis.call('EXPIRE', KEYS[1], ARGV[2])
end
return count
