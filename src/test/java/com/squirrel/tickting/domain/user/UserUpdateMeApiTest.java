package com.squirrel.tickting.domain.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.squirrel.tickting.global.security.AccountType;
import com.squirrel.tickting.global.security.CustomUserDetails;
import com.squirrel.tickting.support.IntegrationTestSupport;
import jakarta.servlet.http.Cookie;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

/** 내 정보 수정. 회원은 실제 가입 API 로 만들고, 바뀐 값은 응답뿐 아니라 DB 에서도 확인한다. */
class UserUpdateMeApiTest extends IntegrationTestSupport {

	private static final String SIGNUP = "/api/v2/auth/users/signup";
	private static final String URL = "/api/v1/users/me";
	private static final String IMG = "https://cdn.example.com/profiles/5.png";

	private Cookie signupAndGetSession() throws Exception {
		MvcResult result = postJson(SIGNUP, userSignupBody("user@example.com"))
			.andExpect(status().isCreated())
			.andReturn();
		return sessionCookie(result);
	}

	private ResultActions patchMe(Cookie token, Object body) throws Exception {
		return mockMvc.perform(patch(URL)
			.cookie(token)
			.contentType(MediaType.APPLICATION_JSON)
			.content(objectMapper.writeValueAsString(body)));
	}

	private Map<String, Object> savedProfile() {
		return jdbc.queryForMap("SELECT nickname, profile_img FROM user_member");
	}

	@Test
	@DisplayName("닉네임과 프로필 이미지를 보내면 둘 다 바뀌고, 응답에 바뀐 값과 수정 일시가 온다")
	void updateBoth() throws Exception {
		Cookie token = signupAndGetSession();
		Long userId = jdbc.queryForObject("SELECT id FROM user_member", Long.class);

		patchMe(token, Map.of("nickname", "길동이2", "profileImg", IMG))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.success").value(true))
			.andExpect(jsonPath("$.data.userId").value(userId))
			.andExpect(jsonPath("$.data.nickname").value("길동이2"))
			.andExpect(jsonPath("$.data.profileImg").value(IMG))
			.andExpect(jsonPath("$.data.updatedAt").isNotEmpty());

		assertThat(savedProfile())
			.containsEntry("nickname", "길동이2")
			.containsEntry("profile_img", IMG);
	}

	@ParameterizedTest
	@ValueSource(strings = {"", "javascript:alert(1)", "ftp://cdn.example.com/a.png", "profile.png", "https://cdn.example.com/a b.png",
		"http://cdn.example.com/a.png", "https://other.example.com/a.png", "https://cdn.example.com.evil.com/a.png"})
	@DisplayName("프로필 이미지가 우리 저장소의 https 주소가 아니면 400 COMMON000 이다 (지우려면 빈 문자열이 아니라 생략한다)")
	void invalidProfileImg(String profileImg) throws Exception {
		Cookie token = signupAndGetSession();

		patchMe(token, Map.of("nickname", "길동이2", "profileImg", profileImg))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("COMMON000"));

		assertThat(savedProfile().get("profile_img")).isNull();
	}

	@Test
	@DisplayName("판매자 토큰으로 호출하면 403 AUTH100 이고, 번호가 같은 예매자의 값은 바뀌지 않는다")
	void sellerIsForbidden() throws Exception {
		signupAndGetSession();
		Long userId = jdbc.queryForObject("SELECT id FROM user_member", Long.class);
		Cookie seller = sessionCookie(
			new CustomUserDetails(userId, AccountType.SELLER, "OWNER", 1L));

		patchMe(seller, Map.of("nickname", "남이바꿈"))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("AUTH100"));

		assertThat(savedProfile()).containsEntry("nickname", "tester");
	}
}
