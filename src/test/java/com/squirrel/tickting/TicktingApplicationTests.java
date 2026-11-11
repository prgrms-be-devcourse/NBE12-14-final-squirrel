package com.squirrel.tickting;

import com.squirrel.tickting.support.TestSecrets;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
class TicktingApplicationTests {

	/** 서명 키는 코드에 적지 않고 실행할 때 만든다. */
	@DynamicPropertySource
	static void jwtSecret(DynamicPropertyRegistry registry) {
		registry.add("jwt.secret", TestSecrets::randomJwtSecret);
	}

	@Test
	void contextLoads() {
	}

}
