package com.squirrel.tickting.global.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

/**
 * BaseTimeEntity 의 created_at / updated_at 자동 기록을 켠다.
 * 메인 클래스가 아니라 별도 설정으로 둔 이유: 메인 클래스에 붙이면 JPA가 없는 슬라이스 테스트(@WebMvcTest)가 깨진다.
 */
@Configuration
@EnableJpaAuditing
public class JpaAuditingConfig {
}
