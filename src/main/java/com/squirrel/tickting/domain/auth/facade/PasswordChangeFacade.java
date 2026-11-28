package com.squirrel.tickting.domain.auth.facade;

import com.squirrel.tickting.domain.auth.dto.AuthRequest;
import com.squirrel.tickting.domain.auth.service.LoginAttemptService;
import com.squirrel.tickting.domain.auth.service.PasswordResetTokenService.Target;
import com.squirrel.tickting.domain.seller.infra.entity.SellerMember;
import com.squirrel.tickting.domain.seller.service.SellerMemberService;
import com.squirrel.tickting.domain.user.infra.entity.UserMember;
import com.squirrel.tickting.domain.user.service.UserMemberService;
import com.squirrel.tickting.global.exception.BusinessException;
import com.squirrel.tickting.global.exception.code.AuthExceptionCode;
import com.squirrel.tickting.global.security.AccountType;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * 로그인한 상태의 비밀번호 변경(AUTH-29·30). 계정(user·seller 도메인)과 로그인 실패 잠금·재설정 링크·메일(auth 도메인)을 엮는다.
 *
 * <p>확인 순서: 비밀번호 확인(400 AUTH005) → 새 비밀번호가 현재와 같음(400 AUTH312) → 계정 → 잠금(429 AUTH004) → 현재 비밀번호(400 AUTH311).
 * BCrypt 계산 동안 DB 연결을 쥐지 않도록 트랜잭션을 열지 않는다. (서비스가 각자 짧게 연다)
 * 지금 세션과 다른 기기의 세션은 끊지 않는다. (재설정과 같다)
 */
@Component
@RequiredArgsConstructor
public class PasswordChangeFacade {

	private final UserMemberService userMemberService;
	private final SellerMemberService sellerMemberService;
	private final LoginAttemptService loginAttemptService;
	private final PasswordChangedNotifier passwordChangedNotifier;
	private final PasswordEncoder passwordEncoder;
	private final Clock clock;

	public void changeForUser(Long userId, AuthRequest.PasswordChange request, String ip) {
		requireNewPassword(request);
		UserMember user = userMemberService.getMe(userId);
		verifyCurrent(AccountType.USER, user.getEmail(), ip, request.currentPassword(), user.getPassword());
		userMemberService.changePassword(userId, user.getPassword(), request.newPassword(), now());
		passwordChangedNotifier.passwordChanged(new Target(AccountType.USER, userId), user.getEmail());
	}

	public void changeForSeller(Long memberId, AuthRequest.PasswordChange request, String ip) {
		requireNewPassword(request);
		SellerMember member = sellerMemberService.getPasswordChangeTarget(memberId);
		verifyCurrent(AccountType.SELLER, member.getEmail(), ip, request.currentPassword(), member.getPassword());
		sellerMemberService.changePassword(memberId, member.getPassword(), request.newPassword(), now());
		passwordChangedNotifier.passwordChanged(new Target(AccountType.SELLER, memberId), member.getEmail());
	}

	/** DB 없이 끝낼 수 있는 검사라 계정 조회보다 먼저 한다. 요청자가 보낸 값끼리만 비교해서 남의 정보가 드러나지 않는다. */
	private void requireNewPassword(AuthRequest.PasswordChange request) {
		if (!request.newPassword().equals(request.newPasswordConfirm())) {
			throw new BusinessException(AuthExceptionCode.PASSWORD_CONFIRM_MISMATCH);
		}
		if (request.newPassword().equals(request.currentPassword())) {
			throw new BusinessException(AuthExceptionCode.NEW_PASSWORD_SAME_AS_CURRENT);
		}
	}

	/**
	 * 틀린 횟수는 로그인 실패와 같은 키에 합쳐 센다. 따로 세면 로그인과 변경을 번갈아 시도해 기회가 두 배가 된다.
	 * 로그인과 달리 성공해도 횟수를 지우지 않는다. 지우면 변경 성공으로 로그인 잠금을 풀 수 있다.
	 */
	private void verifyCurrent(AccountType type, String email, String ip, String rawPassword, String storedHash) {
		loginAttemptService.checkNotLocked(type, email, ip);
		if (!passwordEncoder.matches(rawPassword, storedHash)) {
			loginAttemptService.recordFailure(type, email, ip);
			throw new BusinessException(AuthExceptionCode.CURRENT_PASSWORD_MISMATCH);
		}
	}

	private LocalDateTime now() {
		return LocalDateTime.now(clock).truncatedTo(ChronoUnit.SECONDS);
	}
}
