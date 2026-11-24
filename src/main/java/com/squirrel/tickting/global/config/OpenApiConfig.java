package com.squirrel.tickting.global.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Swagger(OpenAPI) 문서 설정.
 * UI 우측 상단 Authorize 에 JWT 를 넣으면 이후 요청에 Authorization: Bearer 가 붙는다.
 * 주소: /swagger-ui/index.html
 */
@Configuration
public class OpenApiConfig {

	public static final String BEARER_SCHEME = "bearerAuth";

	@Bean
	OpenAPI openAPI() {
		SecurityScheme bearer = new SecurityScheme()
			.type(SecurityScheme.Type.HTTP)
			.scheme("bearer")
			.bearerFormat("JWT")
			.in(SecurityScheme.In.HEADER)
			.name("Authorization");

		return new OpenAPI()
			.info(new Info()
				.title("tickting API")
				.version("v1")
				.description("Access Token 을 Authorize 에 넣으면 됩니다. Bearer 접두사는 자동으로 붙습니다."))
			.components(new Components().addSecuritySchemes(BEARER_SCHEME, bearer))
			.addSecurityItem(new SecurityRequirement().addList(BEARER_SCHEME));
	}
}
