package com.squirrel.tickting.domain.booking.service;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 좌석 점유 요청 횟수 제한 설정값. 값은 application.yaml 의 app.booking.hold-limit 에 있다. (모두 임시 정책)
 *
 * @param enabled     끄면 세지 않는다. 테스트와, 제한 전후를 비교하는 부하 시험에서 쓴다
 * @param maxRequests 한 계정이 window 동안 보낼 수 있는 점유 요청 수. 성공과 실패를 모두 센다
 * @param window      요청을 세는 시간. 첫 요청부터 재고, 지나면 횟수가 처음부터 다시 시작한다
 */
@ConfigurationProperties(prefix = "app.booking.hold-limit")
public record HoldLimitProperties(boolean enabled, int maxRequests, Duration window) {
}
