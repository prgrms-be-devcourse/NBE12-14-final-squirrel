package com.squirrel.tickting.domain.performance.service;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 판매자 공연 설정값. 값은 application.yaml 의 app.performance 에 있다.
 *
 * @param lockWait 공연 단위 락(performance:{공연 번호})을 기다리는 최대 시간. 판매자 수정끼리나 판매 좌석 생성과 겹칠 때만
 *                 기다리므로 짧게 둔다. 넘으면 COMMON004
 * @param publishLeadTime 판매 오픈까지 이만큼보다 더 남아야 공개할 수 있다. 작성 중 공연의 회차도 지금부터 이만큼 뒤여야 한다.
 *                        왜 이 값인지는 application.yaml 주석에 있다. 테스트 환경에서 기다리지 않으려고 설정으로 뺐다
 */
@ConfigurationProperties(prefix = "app.performance")
public record PerformanceProperties(Duration lockWait, Duration publishLeadTime) {
}
