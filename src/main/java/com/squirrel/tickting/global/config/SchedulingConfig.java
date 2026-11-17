package com.squirrel.tickting.global.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** 주기 작업(@Scheduled)을 켠다. 작업 자체는 각 도메인의 scheduler 패키지에 있다. */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
