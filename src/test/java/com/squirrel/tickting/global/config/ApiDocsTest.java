package com.squirrel.tickting.global.config;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.squirrel.tickting.support.IntegrationTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ApiDocsTest extends IntegrationTestSupport {

	@Test
	@DisplayName("Swagger 문서(/v3/api-docs)는 토큰 없이 열리고, 가입·로그인 API 와 Bearer 인증 설정이 들어 있다")
	void apiDocsArePublic() throws Exception {
		mockMvc.perform(get("/v3/api-docs"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.info.title").value("tickting API"))
			.andExpect(jsonPath("$.components.securitySchemes.bearerAuth.scheme").value("bearer"))
			.andExpect(jsonPath("$.paths['/api/v1/auth/users/signup']").exists())
			.andExpect(jsonPath("$.paths['/api/v1/auth/sellers/login']").exists())
			.andExpect(jsonPath("$.paths['/api/v1/auth/refresh']").exists())
			.andExpect(jsonPath("$.paths['/api/v1/me']").exists());
	}

	@Test
	@DisplayName("Swagger UI 주소도 토큰 없이 접근할 수 있다")
	void swaggerUiIsPublic() throws Exception {
		mockMvc.perform(get("/swagger-ui/index.html"))
			.andExpect(status().isOk())
			.andExpect(content().string(org.hamcrest.Matchers.containsString("Swagger UI")));
	}
}
