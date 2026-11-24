package com.squirrel.tickting.domain.auth.service;

import com.squirrel.tickting.global.exception.BusinessException;
import com.squirrel.tickting.global.exception.code.AuthExceptionCode;
import com.squirrel.tickting.global.security.AccountType;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

/**
 * 비밀번호 재설정 링크(토큰)의 저장·확인·소모와 요청 횟수 제한. 서버가 여러 대라 모두 Redis 에 둔다.
 * 토큰 원문은 메일에만 있고 Redis 에는 SHA-256 해시만 둔다. (Redis 가 읽혀도 링크를 만들 수 없게). 이메일도 해시로만 남긴다.
 *
 * <p>Redis 키 (운영 Redis 는 단일 노드라서 키가 같은 슬롯에 있어야 하는 제약은 두지 않았다. 클러스터 모드를 켜면 소모·폐기가 바뀌어야 한다)
 * <ul>
 *   <li>{@code tickting:pw-reset:token:{토큰 해시}} 값은 {@code USER:5}처럼 계정 종류와 번호. 링크 유효 시간이 지나면 사라진다</li>
 *   <li>{@code tickting:pw-reset:acct:{계정 종류:번호}} 그 계정의 유효한 토큰 해시 집합. 변경에 성공하면 한꺼번에 폐기하려는 용도</li>
 *   <li>{@code tickting:pw-reset:{계정 종류}:{이메일 해시}:resend} 재요청 간격 동안만 있는 표시</li>
 *   <li>{@code tickting:pw-reset:{계정 종류}:{이메일 해시}:daily:{한국 날짜}} 그날 요청 횟수</li>
 * </ul>
 * 가입되지 않은 이메일에는 간격·하루 키를 만들지 않는다. (임의 이메일로 Redis 키가 늘어나는 것을 막으려고. 파사드가 가입 여부를 먼저 확인한다)
 */
@Service
@RequiredArgsConstructor
public class PasswordResetTokenService {

	private static final String PREFIX = "tickting:pw-reset:";
	private static final int TOKEN_BYTES = 32;
	/** 지난 날짜의 횟수 키를 치우는 용도다. 하루 한도 계산에는 쓰이지 않는다 */
	private static final Duration DAILY_KEY_TTL = Duration.ofDays(2);

	private static final int ISSUED = 1;
	private static final int TOO_SOON = -1;
	private static final int DAILY_LIMIT = -2;

	private static final DefaultRedisScript<Long> ISSUE_SCRIPT = script("redis/password-reset-issue.lua");
	private static final DefaultRedisScript<Long> REVOKE_SCRIPT = script("redis/password-reset-revoke.lua");

	private final StringRedisTemplate redis;
	private final PasswordResetProperties properties;
	private final Clock clock;

	private final SecureRandom random = new SecureRandom();

	/** 링크를 누른 사람이 어느 계정의 링크인지. 토큰에서 읽는다. */
	public record Target(AccountType type, Long id) {
	}

	/**
	 * 발급한 링크와 되돌리는 데 필요한 값. 응답에 들어갈 값(두 시각)과 메일에 들어갈 값(토큰)이 섞여 있어
	 * 응답 DTO 하나로 표현할 수 없어서 서비스 안에 둔다.
	 *
	 * @param token    메일 링크에 들어가는 토큰 원문. 어디에도 저장하지 않는다
	 * @param dailyKey 하루 횟수를 센 키. 자정을 넘겨 되돌려도 센 날의 키를 되돌린다
	 */
	public record Issued(AccountType type, Long memberId, String emailHash, String token, String tokenHash,
						 String dailyKey, LocalDateTime expiresAt, LocalDateTime resendAvailableAt) {
	}

	/**
	 * 새 링크를 만들어 저장한다. 이전 링크는 그대로 유효하다.
	 * 재요청 간격 안이면 EMAIL_VERIFICATION_TOO_SOON, 하루 한도를 넘었으면 EMAIL_VERIFICATION_DAILY_LIMIT. (이 순서로 확인한다)
	 * 에러 코드는 이메일 인증과 같이 쓴다. (사용자가 겪는 상황과 화면 안내가 같다)
	 */
	public Issued issue(AccountType type, Long memberId, String normalizedEmail) {
		LocalDateTime now = now();
		String token = generateToken();
		String tokenHash = sha256(token);
		String emailHash = sha256(normalizedEmail);
		String dailyKey = dailyKey(type, emailHash, now.toLocalDate());

		Long result = redis.execute(ISSUE_SCRIPT,
			List.of(resendKey(type, emailHash), dailyKey, tokenKey(tokenHash), accountKey(type, memberId)),
			String.valueOf(properties.resendInterval().toSeconds()),
			String.valueOf(properties.dailyLimit()),
			String.valueOf(DAILY_KEY_TTL.toSeconds()),
			tokenHash,
			type.name() + ":" + memberId,
			String.valueOf(properties.linkTtl().toSeconds()));

		if (result == null) {
			throw new IllegalStateException("재설정 링크 발급 스크립트가 값을 돌려주지 않았습니다.");
		}
		if (result == TOO_SOON) {
			throw new BusinessException(AuthExceptionCode.EMAIL_VERIFICATION_TOO_SOON);
		}
		if (result == DAILY_LIMIT) {
			throw new BusinessException(AuthExceptionCode.EMAIL_VERIFICATION_DAILY_LIMIT);
		}
		if (result != ISSUED) {
			throw new IllegalStateException("알 수 없는 발급 결과입니다. result=" + result);
		}
		return new Issued(type, memberId, emailHash, token, tokenHash, dailyKey,
			now.plus(properties.linkTtl()), now.plus(properties.resendInterval()));
	}

	/** 링크가 아직 유효한지 본다. 토큰을 소모하지 않는다. (메일 보안 프로그램이나 링크 미리보기가 열어도 링크가 죽지 않게) */
	public Optional<Target> peek(String token) {
		return parse(redis.opsForValue().get(tokenKey(sha256(token))));
	}

	/**
	 * 링크를 쓴다. 읽으면서 지우는 한 번의 명령이라서 같은 링크로 동시에 요청해도 하나만 성공한다.
	 * 없거나 만료됐거나 이미 썼으면 PASSWORD_RESET_TOKEN_INVALID.
	 */
	public Target consume(String token) {
		String value = redis.opsForValue().getAndDelete(tokenKey(sha256(token)));
		return parse(value).orElseThrow(() -> new BusinessException(AuthExceptionCode.PASSWORD_RESET_TOKEN_INVALID));
	}

	/** 비밀번호를 바꾼 계정의 재설정 링크를 모두 폐기한다. (메일함에 남은 다른 링크로 또 바꾸지 못하게) */
	public void revokeAll(AccountType type, Long memberId) {
		String accountKey = accountKey(type, memberId);
		Set<String> hashes = redis.opsForSet().members(accountKey);
		if (hashes != null && !hashes.isEmpty()) {
			redis.delete(hashes.stream().map(PasswordResetTokenService::tokenKey).toList());
		}
		redis.delete(accountKey);
	}

	/** 발송 대기가 가득 차서 접수하지 못했을 때. 링크, 하루 횟수, 재요청 간격을 모두 되돌려 바로 다시 시도할 수 있게 한다. */
	public void cancel(Issued issued) {
		revoke(issued, true);
	}

	/**
	 * 접수한 뒤 발송이 실패했을 때. 하루 횟수는 돌려주고 이 링크만 지운다.
	 * 재요청 간격은 그대로라서 사용자는 화면의 타이머가 끝난 뒤에 다시 받는다.
	 */
	public void discardAfterSendFailure(Issued issued) {
		revoke(issued, false);
	}

	private void revoke(Issued issued, boolean restoreInterval) {
		redis.execute(REVOKE_SCRIPT,
			List.of(tokenKey(issued.tokenHash()), issued.dailyKey(), resendKey(issued.type(), issued.emailHash()),
				accountKey(issued.type(), issued.memberId())),
			issued.tokenHash(), restoreInterval ? "1" : "0");
	}

	private static Optional<Target> parse(String value) {
		if (value == null) {
			return Optional.empty();
		}
		int colon = value.indexOf(':');
		return Optional.of(new Target(AccountType.valueOf(value.substring(0, colon)),
			Long.valueOf(value.substring(colon + 1))));
	}

	private LocalDateTime now() {
		return LocalDateTime.now(clock).truncatedTo(ChronoUnit.SECONDS);
	}

	private String generateToken() {
		byte[] bytes = new byte[TOKEN_BYTES];
		random.nextBytes(bytes);
		return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
	}

	private static String tokenKey(String tokenHash) {
		return PREFIX + "token:" + tokenHash;
	}

	private static String accountKey(AccountType type, Long memberId) {
		return PREFIX + "acct:" + type.name() + ":" + memberId;
	}

	private static String resendKey(AccountType type, String emailHash) {
		return PREFIX + type.name() + ":" + emailHash + ":resend";
	}

	private static String dailyKey(AccountType type, String emailHash, LocalDate date) {
		return PREFIX + type.name() + ":" + emailHash + ":daily:" + date;
	}

	private static String sha256(String value) {
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(digest);
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("SHA-256 을 쓸 수 없습니다.", e);
		}
	}

	private static DefaultRedisScript<Long> script(String path) {
		DefaultRedisScript<Long> script = new DefaultRedisScript<>();
		script.setLocation(new ClassPathResource(path));
		script.setResultType(Long.class);
		return script;
	}
}
