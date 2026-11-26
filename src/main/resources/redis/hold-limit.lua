-- 좌석 점유 요청을 하나 센다. 세기와 만료 설정을 한 번에 해야 둘 사이에 앱이 죽어도 만료 없는 키(영원한 제한)가 남지 않는다.
-- KEYS[1] 계정의 요청 횟수 키
-- ARGV[1] 세는 시간(ms)
-- 첫 요청에서만 만료를 걸어서, 막힌 뒤에 계속 보내도 제한 시간이 늘어나지 않는다.
local count = redis.call('INCR', KEYS[1])
if count == 1 then
	redis.call('PEXPIRE', KEYS[1], ARGV[1])
end
return count
