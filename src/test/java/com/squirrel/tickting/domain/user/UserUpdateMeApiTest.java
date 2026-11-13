package com.squirrel.tickting.domain.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.squirrel.tickting.global.security.AccountType;
import com.squirrel.tickting.global.security.CustomUserDetails;
import com.squirrel.tickting.support.IntegrationTestSupport;
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

	private static final String SIGNUP = "/api/v1/auth/users/signup";
	private static final String URL = "/api/v1/users/me";
	private static final String IMG = "https://cdn.example.com/profiles/5.png";

	private String signupAndGetBearer() throws Exception {
		MvcResult result = postJson(SIGNUP, userSignupBody("user@example.com", "ci-001"))
			.andExpect(status().isCreated())
			.andReturn();
		return "Bearer " + accessToken(result);
	}

	private ResultActions patchMe(String token, Object body) throws Exception {
		return mockMvc.perform(patch(URL)
			.header("Authorization", token)
			.contentType(MediaType.APPLICATION_JSON)
			.content(objectMapper.writeValueAsString(body)));
	}

	private Map<String, Object> savedProfile() {
		return jdbc.queryForMap("SELECT nickname, profile_img FROM user_member");
	}

	@Test
	@DisplayName("닉네임과 프로필 이미지를 보내면 둘 다 바뀌고, 응답에 바뀐 값과 수정 일시가 온다")
	void updateBoth() throws Exception {
		String token = signupAndGetBearer();
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

	@Test
	@DisplayName("닉네임만 바꾸고 기존 프로필 이미지를 그대로 보내면 사진은 유지된다 (화면은 현재 값을 채워 보낸다)")
	void changeNicknameKeepsProfileImg() throws Exception {
		String token = signupAndGetBearer();
		patchMe(token, Map.of("nickname", "tester", "profileImg", IMG)).andExpect(status().isOk());

		patchMe(token, Map.of("nickname", "길동이2", "profileImg", IMG))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.nickname").value("길동이2"))
			.andExpect(jsonPath("$.data.profileImg").value(IMG));

		assertThat(savedProfile())
			.containsEntry("nickname", "길동이2")
			.containsEntry("profile_img", IMG);
	}

	@Test
	@DisplayName("프로필 이미지를 빼고 보내면 사진이 지워진다 (받은 값으로 통째로 바꾼다)")
	void omittedProfileImgIsCleared() throws Exception {
		String token = signupAndGetBearer();
		patchMe(token, Map.of("nickname", "tester", "profileImg", IMG)).andExpect(status().isOk());

		patchMe(token, Map.of("nickname", "길동이2"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.nickname").value("길동이2"))
			.andExpect(jsonPath("$.data.profileImg").isEmpty());

		assertThat(savedProfile()).containsEntry("nickname", "길동이2");
		assertThat(savedProfile().get("profile_img")).isNull();
	}

	@Test
	@DisplayName("닉네임 없이 보내면 400 COMMON000 이고 값은 바뀌지 않는다")
	void nicknameIsRequired() throws Exception {
		String token = signupAndGetBearer();

		patchMe(token, Map.of("profileImg", IMG))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("COMMON000"));

		assertThat(savedProfile()).containsEntry("nickname", "tester");
		assertThat(savedProfile().get("profile_img")).isNull();
	}

	@Test
	@DisplayName("닉네임 앞뒤 공백은 지우고 저장한다 (가입과 같다)")
	void nicknameIsTrimmed() throws Exception {
		String token = signupAndGetBearer();

		patchMe(token, Map.of("nickname", "  길동이2  "))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.nickname").value("길동이2"));
	}

	@ParameterizedTest
	@ValueSource(strings = {"a", "   ", "123456789012345678901"})
	@DisplayName("닉네임이 2~20자가 아니거나 공백뿐이면 400 COMMON000 이고 값은 바뀌지 않는다")
	void invalidNickname(String nickname) throws Exception {
		String token = signupAndGetBearer();

		patchMe(token, Map.of("nickname", nickname))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("COMMON000"));

		assertThat(savedProfile()).containsEntry("nickname", "tester");
	}

	@ParameterizedTest
	@ValueSource(strings = {"", "javascript:alert(1)", "ftp://cdn.example.com/a.png", "profile.png", "https://cdn.example.com/a b.png"})
	@DisplayName("프로필 이미지가 http(s) 주소가 아니면 400 COMMON000 이다 (지우려면 빈 문자열이 아니라 생략한다)")
	void invalidProfileImg(String profileImg) throws Exception {
		String token = signupAndGetBearer();

		patchMe(token, Map.of("nickname", "길동이2", "profileImg", profileImg))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("COMMON000"));

		assertThat(savedProfile().get("profile_img")).isNull();
	}

	@Test
	@DisplayName("토큰 없이 호출하면 401 AUTH200 이다")
	void needsLogin() throws Exception {
		mockMvc.perform(patch(URL)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"nickname\":\"길동이2\"}"))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").value("AUTH200"));
	}

	@Test
	@DisplayName("판매자 토큰으로 호출하면 403 AUTH100 이고, 번호가 같은 예매자의 값은 바뀌지 않는다")
	void sellerIsForbidden() throws Exception {
		signupAndGetBearer();
		Long userId = jdbc.queryForObject("SELECT id FROM user_member", Long.class);
		String seller = "Bearer " + jwtProvider.createAccessToken(
			new CustomUserDetails(userId, AccountType.SELLER, "OWNER", 1L));

		patchMe(seller, Map.of("nickname", "남이바꿈"))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("AUTH100"));

		assertThat(savedProfile()).containsEntry("nickname", "tester");
	}

	@Test
	@DisplayName("탈퇴한 회원의 토큰이 아직 살아 있어도 404 USER002 이다")
	void withdrawnUserIsNotFound() throws Exception {
		String token = signupAndGetBearer();
		jdbc.update("UPDATE user_member SET deleted_at = NOW()");

		patchMe(token, Map.of("nickname", "길동이2"))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("USER002"));

		assertThat(savedProfile()).containsEntry("nickname", "tester");
	}
}
