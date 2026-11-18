package com.squirrel.tickting.domain.payment.gateway;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 토스페이먼츠 연동 설정. 값은 application.yaml 의 toss 에 있고, 비밀 값은 .env 에서 읽는다.
 *
 * @param secretKey      서버 전용 시크릿 키(test_sk_… 또는 live_sk_…). 브라우저로 내려보내거나 로그에 남기면 안 된다. .env 의 TOSS_SECRET_KEY
 * @param baseUrl        토스 API 주소. 테스트·운영이 같은 주소이고 키로 구분된다
 * @param connectTimeout 연결 시간 제한
 * @param readTimeout    응답을 기다리는 시간 제한. 넘으면 승인됐는지 알 수 없는 상태로 처리한다
 */
@ConfigurationProperties(prefix = "toss")
public record TossProperties(String secretKey, String baseUrl, Duration connectTimeout, Duration readTimeout) {
}
