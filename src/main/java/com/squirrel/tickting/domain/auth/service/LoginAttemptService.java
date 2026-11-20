package com.squirrel.tickting.domain.auth.service;

import com.squirrel.tickting.global.exception.BusinessException;
import com.squirrel.tickting.global.exception.code.AuthExceptionCode;
import com.squirrel.tickting.global.security.AccountType;
import com.squirrel.tickting.global.util.EmailUtil;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

/**
 * 로그인 실패 제한. 같은 IP 에서 같은 이메일로 여러 번 틀리면 잠시 로그인을 막는다.
 *
 * 서버가 여러 대라 횟수를 서버 메모리가 아니라 Redis 에 둔다.
 * 이메일만으로 세면 남이 일부러 틀려서 다른 사람 계정을 잠글 수 있어서 이메일과 IP 를 함께 센다.
 * 잠긴 동안은 비밀번호를 비교하지 않으므로, 무차별 대입이 BCrypt 로 CPU 를 쓰는 것도 막는다.
 */
@Service
@RequiredArgsConstructor
@EnableConfigurationProperties(LoginAttemptProperties.class)
public class LoginAttemptService {

	private static final String PREFIX = "tickting:login-fail:";
	private static final DefaultRedisScript<Long> FAIL_SCRIPT = failScript();

	private final StringRedisTemplate redis;
	private final LoginAttemptProperties properties;

	/** 잠겨 있으면 BusinessException(AUTH004). 비밀번호를 비교하기 전에 부른다. */
	public void checkNotLocked(AccountType type, String email, String ip) {
		String count = redis.opsForValue().get(key(type, email, ip));
		if (count != null && Long.parseLong(count) >= properties.maxFailures()) {
			throw new BusinessException(AuthExceptionCode.LOGIN_TOO_MANY_ATTEMPTS);
		}
	}

	/** 비밀번호가 틀렸을 때 부른다. 정해진 횟수에 닿으면 그때부터 잠금 시간 동안 막힌다. */
	public void recordFailure(AccountType type, String email, String ip) {
		redis.execute(FAIL_SCRIPT, List.of(key(type, email, ip)),
			String.valueOf(properties.maxFailures()), String.valueOf(properties.lockDuration().toSeconds()));
	}

	/** 로그인에 성공하면 실패 횟수를 지운다. (가끔 틀리는 사람이 쌓인 횟수 때문에 잠기지 않게) */
	public void reset(AccountType type, String email, String ip) {
		redis.delete(key(type, email, ip));
	}

	/**
	 * 같은 이메일이 예매자·판매자·관리자에 따로 있을 수 있어서 계정 종류로 나눈다.
	 * 이메일과 IP 는 개인정보라 키에 그대로 남기지 않고 해시한다.
	 */
	private static String key(AccountType type, String email, String ip) {
		return PREFIX + type.name() + ":" + sha256(EmailUtil.normalize(email) + "|" + ip);
	}

	private static String sha256(String value) {
		try {
			byte[] hash = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(hash);
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("SHA-256 을 쓸 수 없습니다.", e);
		}
	}

	private static DefaultRedisScript<Long> failScript() {
		DefaultRedisScript<Long> script = new DefaultRedisScript<>();
		script.setLocation(new ClassPathResource("redis/login-fail.lua"));
		script.setResultType(Long.class);
		return script;
	}
}
