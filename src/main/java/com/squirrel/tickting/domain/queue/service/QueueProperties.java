package com.squirrel.tickting.domain.queue.service;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 대기열 설정값. 값은 application.yaml 의 app.queue 에 있다. (서비스 고정 정책이라 판매자가 고르지 않는다)
 *
 * @param enabled        끄면 누구나 바로 입장한 것으로 본다. 테스트와 로컬에서 대기열 없이 확인할 때 쓴다
 * @param maxActive      회차마다 동시에 입장해 있을 수 있는 인원. 서버가 버티는 요청 수에 맞춰 부하 테스트로 정한다
 * @param entryTtl       입장한 뒤 구역·좌석을 보고 좌석을 잡을 수 있는 시간. 지나면 자리가 비고 다시 줄을 서야 한다
 * @param waitingTimeout 대기 중인 사람이 이 시간 동안 상태 확인(폴링)을 하지 않으면 떠난 것으로 보고 건너뛴다
 * @param leaveGrace     입장한 사람이 좌석 화면을 떠난다고 알려 온 뒤 자리를 비우기까지의 시간. 그 안에 돌아오면 자리를 지킨다
 */
@ConfigurationProperties(prefix = "app.queue")
public record QueueProperties(boolean enabled, int maxActive, Duration entryTtl, Duration waitingTimeout,
							  Duration leaveGrace) {
}
