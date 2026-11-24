package com.squirrel.tickting.domain.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.squirrel.tickting.domain.captcha.interceptor.CaptchaInterceptor;
import com.squirrel.tickting.support.IntegrationTestSupport;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

/**
 * 판매자 가입 전 이메일 인증. 번호를 만들고 확인하는 규칙 자체(횟수·만료·한도)는 예매자와 같은 코드라 EmailVerificationApiTest 가 확인하고,
 * 여기서는 판매자에게 새로 생긴 것만 본다: 가입이 인증을 요구하는지, 예매자와 판매자의 인증 상태가 섞이지 않는지,
 * 발송이 판매자 테이블 기준으로 중복을 막는지, 캡챠가 걸리는지.
 */
class SellerEmailVerificationApiTest extends IntegrationTestSupport {

	private static final String SELLER_SEND = "/api/v1/auth/sellers/email-verification";
	private static final String SELLER_CONFIRM = SELLER_SEND + "/confirm";
	private static final String USER_SEND = "/api/v1/auth/users/email-verification";
	private static final String SELLER_SIGNUP = "/api/v1/auth/sellers/signup";
	private static final String USER_SIGNUP = "/api/v1/auth/users/signup";
	private static final String EMAIL = "owner@company.com";
	private static final String BUSINESS_NUMBER = "1234567890";
	private static final String SELLER_KEY_PREFIX = "tickting:email-verify:SELLER:";

	@Autowired
	StringRedisTemplate redis;

	/** 발송은 캡챠를 거친다. 테스트의 Mock 검증기는 토큰이 있으면 통과시킨다. */
	private ResultActions send(String path, String email) throws Exception {
		return mockMvc.perform(post(path)
			.header(CaptchaInterceptor.TOKEN_HEADER, "pass")
			.contentType(MediaType.APPLICATION_JSON)
			.content(objectMapper.writeValueAsString(Map.of("email", email))));
	}

	/** 번호를 받아서 확인까지 마친다. */
	private void verify(String sendPath, String email) throws Exception {
		send(sendPath, email).andExpect(status().isOk());
		postJson(sendPath + "/confirm", Map.of("email", email, "code", fakeEmailSender.lastCode(email)))
			.andExpect(status().isOk());
	}

	private Set<String> sellerKeys(String suffix) {
		return redis.keys(SELLER_KEY_PREFIX + "*:" + suffix);
	}

	private int sellerCount(String email) {
		return jdbc.queryForObject("SELECT count(*) FROM seller_member WHERE email = ?", Integer.class, email);
	}

	@Test
	@DisplayName("판매자가 인증번호를 받아 확인한 이메일로 가입 신청하면 사업체와 계정이 만들어지고, 인증 표시는 지워진다")
	void verifyThenSignup() throws Exception {
		send(SELLER_SEND, EMAIL)
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.expiresAt").exists());
		assertThat(fakeEmailSender.sentCount(EMAIL)).isEqualTo(1);
		postJson(SELLER_CONFIRM, Map.of("email", EMAIL, "code", fakeEmailSender.lastCode(EMAIL)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.verifiedUntil").exists());
		assertThat(sellerKeys("verified")).hasSize(1);

		postJson(SELLER_SIGNUP, unverifiedSellerSignupBody(EMAIL, BUSINESS_NUMBER)).andExpect(status().isCreated());

		assertThat(sellerCount(EMAIL)).isEqualTo(1);
		assertThat(sellerKeys("verified")).isEmpty();
	}

	@Test
	@DisplayName("인증하지 않고 판매자 가입 신청하면 400(AUTH307)이고 사업체도 계정도 만들어지지 않는다")
	void signupWithoutVerification() throws Exception {
		postJson(SELLER_SIGNUP, unverifiedSellerSignupBody(EMAIL, BUSINESS_NUMBER))
			.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("AUTH307"));

		assertThat(sellerCount(EMAIL)).isZero();
		assertThat(jdbc.queryForObject("SELECT count(*) FROM seller_business", Integer.class)).isZero();
	}

	@Test
	@DisplayName("다른 이메일로 인증한 표시로는 판매자 가입 신청을 할 수 없다")
	void verificationIsBoundToEmail() throws Exception {
		verify(SELLER_SEND, "other@company.com");

		postJson(SELLER_SIGNUP, unverifiedSellerSignupBody(EMAIL, BUSINESS_NUMBER))
			.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("AUTH307"));
	}

	@Test
	@DisplayName("가입 신청이 실패하면 인증은 그대로라서 인증을 다시 하지 않고 고쳐서 신청할 수 있다")
	void failedSignupKeepsVerification() throws Exception {
		verify(SELLER_SEND, EMAIL);
		Map<String, Object> wrong = unverifiedSellerSignupBody(EMAIL, BUSINESS_NUMBER);
		wrong.put("passwordConfirm", "different1!");

		postJson(SELLER_SIGNUP, wrong).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("AUTH005"));

		postJson(SELLER_SIGNUP, unverifiedSellerSignupBody(EMAIL, BUSINESS_NUMBER)).andExpect(status().isCreated());
	}

	@Test
	@DisplayName("예매자로 인증한 이메일로는 판매자 가입 신청을 할 수 없다")
	void userVerificationDoesNotCountForSeller() throws Exception {
		verify(USER_SEND, EMAIL);

		postJson(SELLER_SIGNUP, unverifiedSellerSignupBody(EMAIL, BUSINESS_NUMBER))
			.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("AUTH307"));
	}

	@Test
	@DisplayName("판매자로 인증한 이메일로는 예매자 가입을 할 수 없다")
	void sellerVerificationDoesNotCountForUser() throws Exception {
		verify(SELLER_SEND, EMAIL);

		postJson(USER_SIGNUP, unverifiedUserSignupBody(EMAIL))
			.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("AUTH307"));
	}

	@Test
	@DisplayName("재발송 간격은 예매자와 판매자가 따로 센다: 예매자로 보낸 직후에도 판매자 발송이 된다")
	void resendIntervalIsSeparate() throws Exception {
		send(USER_SEND, EMAIL).andExpect(status().isOk());

		send(SELLER_SEND, EMAIL).andExpect(status().isOk());
		// 같은 종류로 바로 다시 보내면 간격에 걸린다
		send(SELLER_SEND, EMAIL).andExpect(status().isTooManyRequests()).andExpect(jsonPath("$.code").value("AUTH302"));
	}

	@Test
	@DisplayName("이미 판매자로 가입한 이메일은 판매자 발송이 409(SELLER000)이고 메일도 번호도 키도 만들지 않는다")
	void alreadyRegisteredSellerEmail() throws Exception {
		postJson(SELLER_SIGNUP, sellerSignupBody(EMAIL, BUSINESS_NUMBER)).andExpect(status().isCreated());

		send(SELLER_SEND, EMAIL).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SELLER000"));

		assertThat(fakeEmailSender.sentCount(EMAIL)).isZero();
		assertThat(redis.keys(SELLER_KEY_PREFIX + "*")).isEmpty();
	}

	@Test
	@DisplayName("예매자로 가입한 이메일도 판매자 인증과 가입은 할 수 있다 (두 계정은 테이블이 다르다)")
	void userEmailCanStillRegisterAsSeller() throws Exception {
		postJson(USER_SIGNUP, userSignupBody(EMAIL)).andExpect(status().isCreated());

		// 예매자 쪽은 막히고, 판매자 쪽은 통한다
		send(USER_SEND, EMAIL).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("USER000"));
		verify(SELLER_SEND, EMAIL);
		postJson(SELLER_SIGNUP, unverifiedSellerSignupBody(EMAIL, BUSINESS_NUMBER)).andExpect(status().isCreated());
	}

	@Test
	@DisplayName("판매자 발송도 캡챠 토큰이 없거나 실패하면 403(CAPTCHA000)이고 번호·메일이 생기지 않는다. 확인은 토큰이 필요 없다")
	void sendRequiresCaptcha() throws Exception {
		mockMvc.perform(post(SELLER_SEND).contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("email", EMAIL))))
			.andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("CAPTCHA000"));
		mockMvc.perform(post(SELLER_SEND).header(CaptchaInterceptor.TOKEN_HEADER, "fail")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("email", EMAIL))))
			.andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("CAPTCHA000"));

		assertThat(fakeEmailSender.sentCount(EMAIL)).isZero();
		assertThat(redis.keys(SELLER_KEY_PREFIX + "*")).isEmpty();
		// 확인은 캡챠 없이 부를 수 있다 (번호가 없어서 AUTH306 이지 CAPTCHA000 이 아니다)
		postJson(SELLER_CONFIRM, Map.of("email", EMAIL, "code", "123456"))
			.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("AUTH306"));
	}
}
