package com.squirrel.tickting.global.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.squirrel.tickting.global.security.AccountType;
import com.squirrel.tickting.global.security.CustomUserDetails;
import com.squirrel.tickting.support.IntegrationTestSupport;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.session.web.http.CookieSerializer;

/** Redis 세션을 쓰기 위한 준비. 세션 쿠키 설정과, 세션에 저장되는 로그인 정보가 직렬화되는지 본다. */
class SessionSetupTest extends IntegrationTestSupport {

	@Autowired
	CookieSerializer cookieSerializer;

	@Test
	@DisplayName("세션 쿠키는 이름이 SESSION 이고 HttpOnly, SameSite=Lax 이며 모든 경로에 보낸다 (로컬은 http 라서 Secure 는 붙지 않는다)")
	void sessionCookieAttributes() {
		var request = new MockHttpServletRequest();
		var response = new MockHttpServletResponse();

		cookieSerializer.writeCookieValue(new CookieSerializer.CookieValue(request, response, "session-id"));

		String setCookie = response.getHeader("Set-Cookie");
		assertThat(setCookie).startsWith("SESSION=").contains("HttpOnly", "SameSite=Lax", "Path=/");
		assertThat(setCookie).doesNotContain("Secure");
	}

	@Test
	@DisplayName("로그인 정보(CustomUserDetails)는 직렬화해서 저장했다가 그대로 복원된다")
	void userDetailsSurvivesSerialization() throws Exception {
		var original = new CustomUserDetails(7L, AccountType.SELLER, "OWNER", 3L);

		var bytes = new ByteArrayOutputStream();
		try (var out = new ObjectOutputStream(bytes)) {
			out.writeObject(original);
		}
		CustomUserDetails restored;
		try (var in = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
			restored = (CustomUserDetails) in.readObject();
		}

		assertThat(restored.getId()).isEqualTo(7L);
		assertThat(restored.getAccountType()).isEqualTo(AccountType.SELLER);
		assertThat(restored.getRole()).isEqualTo("OWNER");
		assertThat(restored.getBusinessId()).isEqualTo(3L);
		assertThat(restored.getAuthorities()).extracting("authority")
			.containsExactlyInAnyOrder("ROLE_SELLER", "ROLE_SELLER_OWNER");
	}
}
