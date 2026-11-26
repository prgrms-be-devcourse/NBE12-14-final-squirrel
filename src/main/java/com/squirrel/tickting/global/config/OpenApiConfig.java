package com.squirrel.tickting.global.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Swagger(OpenAPI) 문서 설정.
 * 로그인은 Authorize 버튼이 아니라 로그인 API(/api/v2/auth/**)를 Swagger 에서 직접 호출한다. 그러면 SESSION 쿠키가
 * 브라우저에 저장되어 이후 요청에 같이 간다.
 * 주소: /swagger-ui/index.html
 */
@Configuration
public class OpenApiConfig {

	@Bean
	OpenAPI openAPI() {
		return new OpenAPI()
			.info(new Info()
				.title("tickting API")
				.version("v1")
				.description("로그인 API(/api/v2/auth/**)를 먼저 호출하면 SESSION 쿠키가 저장되어 이후 요청에 같이 보내집니다."));
	}
}
