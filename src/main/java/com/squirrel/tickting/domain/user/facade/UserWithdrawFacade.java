package com.squirrel.tickting.domain.user.facade;

import com.squirrel.tickting.domain.booking.service.UserBookingService;
import com.squirrel.tickting.domain.user.service.UserMemberService;
import com.squirrel.tickting.global.exception.BusinessException;
import com.squirrel.tickting.global.exception.code.UserExceptionCode;
import com.squirrel.tickting.global.security.SessionAuthenticator;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 예매자 탈퇴. 예매 도메인에서 진행 중인 예매를 확인하고 회원 도메인에서 탈퇴 처리를 해서 파사드가 엮는다.
 */
@Component
@RequiredArgsConstructor
public class UserWithdrawFacade {

	private final UserBookingService userBookingService;
	private final UserMemberService userMemberService;
	private final SessionAuthenticator sessionAuthenticator;
	private final TransactionTemplate transactionTemplate;

	/**
	 * 진행 중인 예매가 있으면 막는다. 결제했거나 결제 중인 예매가 주인 없이 남으면 환불·문의를 받을 수 없기 때문이다.
	 * 탈퇴가 커밋된 뒤에 지금 세션을 지운다. 다른 기기의 세션은 회원 번호로 찾을 수 없어서 지우지 못하고,
	 * 그 세션으로 내 정보를 부르면 USER_NOT_FOUND 가 난다.
	 */
	public void withdraw(Long userId, HttpServletRequest request, HttpServletResponse response) {
		transactionTemplate.executeWithoutResult(status -> {
			if (userBookingService.hasOngoingBooking(userId, LocalDateTime.now())) {
				throw new BusinessException(UserExceptionCode.ONGOING_BOOKING_EXISTS);
			}
			userMemberService.withdraw(userId);
		});
		sessionAuthenticator.logout(request, response);
	}
}
