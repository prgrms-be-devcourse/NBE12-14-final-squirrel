-- 비밀번호 재설정 링크를 새로 발급한다. 간격·하루 한도 확인과 저장을 한 번에 해야 두 번 빠르게 눌러도 메일이 한 통만 간다.
-- 이전 링크는 지우지 않는다. (남이 내 이메일로 반복 요청해 내가 받은 링크를 죽이지 못하게. 변경에 성공할 때 한꺼번에 폐기한다)
-- KEYS[1] 재요청 간격 키, KEYS[2] 하루 요청 횟수 키(날짜가 들어 있다), KEYS[3] 토큰 키, KEYS[4] 그 계정의 유효 토큰 집합 키
-- ARGV[1] 재요청 간격(초), ARGV[2] 하루 한도, ARGV[3] 하루 횟수 키의 만료(초, 지난 키를 치우는 용도)
-- ARGV[4] 토큰 해시, ARGV[5] 토큰에 담을 값(계정 종류:번호), ARGV[6] 링크 유효 시간(초)
-- 반환: 1 발급함, -1 재요청 간격 안, -2 하루 한도 초과
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
redis.call('SET', KEYS[3], ARGV[5], 'EX', ARGV[6])
redis.call('SADD', KEYS[4], ARGV[4])
-- 새 링크가 가장 늦게 만료되므로 집합도 그때까지 둔다
redis.call('EXPIRE', KEYS[4], ARGV[6])
return 1
