package com.squirrel.tickting;

import com.squirrel.tickting.support.IntegrationTestSupport;
import org.junit.jupiter.api.Test;

/**
 * 앱 전체가 뜨는지. 비밀 값·외부 연동 설정은 IntegrationTestSupport 가 실행할 때 넣는다.
 * 따로 설정하면 .env 가 있는 PC 에서만 통과하고 CI(.env 없음)에서는 설정 값을 찾지 못해 실패한다.
 */
class TicktingApplicationTests extends IntegrationTestSupport {

	@Test
	void contextLoads() {
	}

}
