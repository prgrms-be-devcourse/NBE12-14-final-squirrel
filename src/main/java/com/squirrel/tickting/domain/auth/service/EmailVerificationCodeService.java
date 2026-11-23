package com.squirrel.tickting.domain.auth.service;

import com.squirrel.tickting.global.exception.BusinessException;
import com.squirrel.tickting.global.exception.code.AuthExceptionCode;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

/**
 * 이메일 인증번호의 저장·확인·횟수 제한. 서버가 여러 대라 모두 Redis 에 두고, 확인과 변경은 Lua 스크립트 하나로 한다.
 * (인증을 마쳤다는 사실은 영구히 남아야 해서 DB(user_member.email_verified_at)에 있고, 여기에는 몇 분 뒤 사라져도 되는 값만 있다)
 *
 * <p>Redis 키 (회원 번호를 중괄호로 묶어 같은 회원의 키가 같은 슬롯에 놓이게 했다. 이메일은 키에 남기지 않는다)
 * <ul>
 *   <li>{@code tickting:email-verify:USER:{번호}:code} 해시. 인증번호·발급 식별값·틀린 횟수. 유효 시간이 지나면 사라진다</li>
 *   <li>{@code tickting:email-verify:USER:{번호}:resend} 재발송 간격 동안만 있는 표시</li>
 *   <li>{@code tickting:email-verify:USER:{번호}:daily:{한국 날짜}} 그날 발송 횟수. 날짜가 키에 있어서 자정이 지나면 0부터 시작한다</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class EmailVerificationCodeService {

	private static final String PREFIX = "tickting:email-verify:USER:";
	private static final int CODE_DIGITS = 6;
	/** 지난 날짜의 횟수 키를 치우는 용도다. 하루 한도 계산에는 쓰이지 않는다 */
	private static final Duration DAILY_KEY_TTL = Duration.ofDays(2);

	private static final int ISSUED = 1;
	private static final int TOO_SOON = -1;
	private static final int DAILY_LIMIT = -2;
	private static final int NO_VALID_CODE = -1;

	private static final DefaultRedisScript<Long> ISSUE_SCRIPT = script("redis/email-verify-issue.lua");
	private static final DefaultRedisScript<Long> CONFIRM_SCRIPT = script("redis/email-verify-confirm.lua");
	private static final DefaultRedisScript<Long> REVOKE_SCRIPT = script("redis/email-verify-revoke.lua");

	private final StringRedisTemplate redis;
	private final EmailVerificationProperties properties;
	private final Clock clock;

	private final SecureRandom random = new SecureRandom();

	/**
	 * 발급한 인증번호와 되돌리는 데 필요한 값. 응답에 들어갈 값(두 시각)과 메일에 들어갈 값(번호)이 섞여 있어
	 * 응답 DTO 하나로 표현할 수 없어서 서비스 안에 둔다.
	 *
	 * @param nonce    이 발급의 식별값. 나중에 되돌릴 때 저장된 번호가 이 발급의 것인지 알아보는 데 쓴다
	 * @param dailyKey 하루 횟수를 센 키. 자정을 넘겨 되돌려도 센 날의 키를 되돌린다
	 */
	public record Issued(Long userId, String code, String nonce, String dailyKey, LocalDateTime expiresAt,
						 LocalDateTime resendAvailableAt) {
	}

	/**
	 * 새 인증번호를 만들어 저장한다. 이전 번호는 바로 무효가 된다.
	 * 재발송 간격 안이면 EMAIL_VERIFICATION_TOO_SOON, 하루 한도를 넘었으면 EMAIL_VERIFICATION_DAILY_LIMIT. (이 순서로 확인한다)
	 */
	public Issued issue(Long userId) {
		LocalDateTime now = now();
		String code = generateCode();
		String nonce = UUID.randomUUID().toString();
		String dailyKey = dailyKey(userId, now.toLocalDate());

		Long result = redis.execute(ISSUE_SCRIPT, List.of(resendKey(userId), dailyKey, codeKey(userId)),
			String.valueOf(properties.resendInterval().toSeconds()),
			String.valueOf(properties.dailyLimit()),
			String.valueOf(DAILY_KEY_TTL.toSeconds()),
			code, nonce,
			String.valueOf(properties.codeTtl().toSeconds()));

		if (result == null) {
			throw new IllegalStateException("인증번호 발급 스크립트가 값을 돌려주지 않았습니다.");
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
		return new Issued(userId, code, nonce, dailyKey,
			now.plus(properties.codeTtl()), now.plus(properties.resendInterval()));
	}

	/**
	 * 인증번호를 확인한다. 맞으면 번호를 지운다. (한 번만 쓰이고, 같은 번호로 동시에 확인해도 하나만 성공한다)
	 * 유효한 번호가 없으면 EMAIL_VERIFICATION_CODE_INVALID, 틀렸으면 남은 횟수를 담은 EMAIL_VERIFICATION_CODE_MISMATCH.
	 * 마지막 기회까지 틀리면 번호가 폐기되므로 MISMATCH 가 아니라 INVALID 다.
	 */
	public void confirm(Long userId, String code) {
		Long result = redis.execute(CONFIRM_SCRIPT, List.of(codeKey(userId)), code,
			String.valueOf(properties.maxAttempts()));

		if (result == null) {
			throw new IllegalStateException("인증번호 확인 스크립트가 값을 돌려주지 않았습니다.");
		}
		if (result == NO_VALID_CODE) {
			throw new BusinessException(AuthExceptionCode.EMAIL_VERIFICATION_CODE_INVALID);
		}
		if (result > 0) {
			throw new BusinessException(AuthExceptionCode.EMAIL_VERIFICATION_CODE_MISMATCH,
				"인증번호가 올바르지 않습니다. (남은 횟수 " + result + "회)");
		}
	}

	/** 발송 대기가 가득 차서 접수하지 못했을 때. 번호, 하루 횟수, 재발송 간격을 모두 되돌려 바로 다시 시도할 수 있게 한다. */
	public void cancel(Issued issued) {
		revoke(issued, true);
	}

	/**
	 * 접수한 뒤 발송이 실패했을 때. 하루 횟수는 돌려주고 번호는 그 사이 새로 받지 않았을 때만 지운다.
	 * 재발송 간격은 그대로라서 사용자는 화면의 타이머가 끝난 뒤에 다시 받는다.
	 */
	public void discardAfterSendFailure(Issued issued) {
		revoke(issued, false);
	}

	private void revoke(Issued issued, boolean restoreInterval) {
		redis.execute(REVOKE_SCRIPT,
			List.of(codeKey(issued.userId()), issued.dailyKey(), resendKey(issued.userId())),
			issued.nonce(), restoreInterval ? "1" : "0");
	}

	private LocalDateTime now() {
		return LocalDateTime.now(clock).truncatedTo(ChronoUnit.SECONDS);
	}

	private String generateCode() {
		int bound = (int) Math.pow(10, CODE_DIGITS);
		return String.format("%0" + CODE_DIGITS + "d", random.nextInt(bound));
	}

	private static String codeKey(Long userId) {
		return PREFIX + "{" + userId + "}:code";
	}

	private static String resendKey(Long userId) {
		return PREFIX + "{" + userId + "}:resend";
	}

	private static String dailyKey(Long userId, LocalDate date) {
		return PREFIX + "{" + userId + "}:daily:" + date;
	}

	private static DefaultRedisScript<Long> script(String path) {
		DefaultRedisScript<Long> script = new DefaultRedisScript<>();
		script.setLocation(new ClassPathResource(path));
		script.setResultType(Long.class);
		return script;
	}
}
