package com.squirrel.tickting.domain.inquiry.facade;

import com.squirrel.tickting.domain.booking.service.BookingService;
import com.squirrel.tickting.domain.inquiry.dto.UserInquiryRequest;
import com.squirrel.tickting.domain.inquiry.dto.UserInquiryResponse;
import com.squirrel.tickting.domain.inquiry.service.InquiryService;
import com.squirrel.tickting.domain.user.infra.entity.UserMember;
import com.squirrel.tickting.domain.user.service.UserMemberService;
import com.squirrel.tickting.global.exception.BusinessException;
import com.squirrel.tickting.global.exception.code.BookingExceptionCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 예매자 문의 작성. 예매(내 예매인가), 회원(작성자 이름·이메일), 문의(저장) 세 도메인이 얽혀서
 * 서비스끼리 부르지 않고 이 파사드가 순서대로 부른다. 목록·상세는 읽기만 해서 컨트롤러가 서비스를 바로 부른다.
 */
@Component
@RequiredArgsConstructor
public class UserInquiryFacade {

	private final TransactionTemplate transactionTemplate;
	private final BookingService bookingService;
	private final UserMemberService userMemberService;
	private final InquiryService inquiryService;

	/**
	 * 예매를 골랐다면 내 예매여야 한다. 남의 예매·없는 예매는 같은 404 로 답해서 번호가 있는지 알려주지 않는다.
	 * 예매 상태는 따지지 않는다. 취소·만료된 예매에 대한 문의가 오히려 많기 때문이다.
	 * 탈퇴한 회원은 회원 조회가 404 로 막는다.
	 */
	public UserInquiryResponse.Created create(Long userId, UserInquiryRequest.Create request) {
		return transactionTemplate.execute(status -> {
			if (request.bookingId() != null) {
				bookingService.findMine(userId, request.bookingId())
					.orElseThrow(() -> new BusinessException(BookingExceptionCode.BOOKING_NOT_FOUND));
			}
			UserMember user = userMemberService.getMe(userId);
			return inquiryService.createForUser(userId, user.getName(), user.getEmail(), request);
		});
	}
}
