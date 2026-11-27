package com.squirrel.tickting.domain.seller.facade;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 담당자 초대 (app.seller.invitation.*). 값은 임시 정책이다.
 * 메일의 보낸 사람 이름·주소와 발송 스레드는 이메일 인증과 같이 쓴다. (EmailVerificationProperties)
 *
 * @param linkTtl         초대 링크 유효 시간. 메일이 도착해서 담당자가 가입을 마칠 때까지를 모두 포함한다
 * @param frontendBaseUrl 메일 링크의 앞부분(프론트 주소). 끝에 / 가 있어도 없어도 된다
 */
@ConfigurationProperties(prefix = "app.seller.invitation")
public record SellerInvitationProperties(Duration linkTtl, String frontendBaseUrl) {
}
