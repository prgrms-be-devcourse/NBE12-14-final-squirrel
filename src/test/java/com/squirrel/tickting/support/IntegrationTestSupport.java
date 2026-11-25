package com.squirrel.tickting.support;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.squirrel.tickting.TestcontainersConfiguration;
import com.squirrel.tickting.domain.auth.service.EmailVerificationCodeService;
import com.squirrel.tickting.domain.terms.infra.entity.Terms;
import com.squirrel.tickting.domain.terms.infra.repository.TermsRepository;
import com.squirrel.tickting.domain.terms.type.TermsType;
import com.squirrel.tickting.global.jwt.JwtProperties;
import com.squirrel.tickting.global.jwt.JwtProvider;
import com.squirrel.tickting.global.security.AccountType;
import com.squirrel.tickting.global.util.EmailUtil;
import com.squirrel.tickting.support.EmailTestConfiguration.ControllableClock;
import com.squirrel.tickting.support.EmailTestConfiguration.ControllableEmailSendExecutor;
import com.squirrel.tickting.support.EmailTestConfiguration.FakeEmailSender;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.IntFunction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.ObjectMapper;

/**
 * 실제 PostgreSQL(Testcontainers) 위에서 Flyway 마이그레이션까지 적용한 상태로 API 를 호출하는 통합 테스트의 공통 틀.
 * 테스트마다 계정·약관 데이터를 비우고 약관을 새로 넣는다.
 */
// 대기열은 기본으로 꺼 둔다. 구역·좌석 조회와 점유를 확인하는 다른 테스트가 입장 없이도 돌게 하려는 것이다.
// 대기열 테스트는 @DynamicPropertySource 로 켠다. (그 값이 여기 값보다 우선한다)
@SpringBootTest(properties = "app.queue.enabled=false")
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, EmailTestConfiguration.class})
public abstract class IntegrationTestSupport {

	protected static final String PASSWORD = "password1!";

	/** 서명 키는 코드에 적지 않고 실행할 때 만든다. (DB 는 Testcontainers 가 연결 정보를 덮어쓴다) */
	@DynamicPropertySource
	static void jwtSecret(DynamicPropertyRegistry registry) {
		registry.add("jwt.secret", TestSecrets::randomJwtSecret);
		// 앱 시드(db/seed)에는 개발용 공연장이 없다. 여러 테스트가 이름으로 찾아 쓰는 가상 아레나·소극장은 테스트 전용 시드에서 넣는다.
		// 시연 시드(db/seed-demo)는 운영에는 없지만 로컬과 같게 더한다. 좌석 점유 테스트(ScheduleSeatServiceTest)가 시연 공연의 판매 좌석·등급을 쓴다.
		registry.add("spring.flyway.locations",
			() -> "classpath:db/migration,classpath:db/seed,classpath:db/seed-demo,classpath:db/test-seed");
		// 주기 작업이 테스트가 만든 데이터를 먼저 처리하지 않게 끈다. 작업이 하는 일은 파사드를 직접 불러서 확인한다.
		registry.add("app.booking.expiry-enabled", () -> "false");
		registry.add("app.payment.refund-retry.enabled", () -> "false");
		registry.add("app.payment.reconcile.enabled", () -> "false");
		registry.add("app.performance.seat-generation.enabled", () -> "false");
		// 토스 키 없이 결제 흐름을 확인한다. 토스를 부르는 코드는 TossPaymentGatewayTest 가 가짜 서버로 따로 확인한다.
		registry.add("app.payment.gateway", () -> "mock");
		// 구글을 부르지 않는다. 토큰이 있으면 통과, "fail" 이면 실패다. 구글을 부르는 코드는 RecaptchaVerifierTest 가 가짜 서버로 따로 확인한다.
		registry.add("app.captcha.verifier", () -> "mock");
		// 업로드 주소는 서버 안에서 서명만 해서 만들므로 가짜 키로도 만들어진다. 이미지 주소 검증은 base-url 로 시작하는 값만 통과시킨다.
		registry.add("app.s3.bucket", () -> "test-bucket");
		registry.add("app.s3.region", () -> "ap-northeast-2");
		registry.add("app.s3.access-key-id", TestSecrets::randomAwsAccessKeyId);
		registry.add("app.s3.secret-access-key", TestSecrets::randomAwsSecretKey);
		registry.add("app.s3.base-url", () -> "https://cdn.example.com");
		// 메일 발송기는 FakeEmailSender 로 갈아 끼워서 실제로는 접속하지 않는다. 접속 정보가 있어야 앱이 뜬다.
		registry.add("spring.mail.host", () -> "localhost");
		registry.add("spring.mail.port", () -> "1025");
		registry.add("spring.mail.username", () -> "");
		registry.add("spring.mail.password", () -> "");
		registry.add("app.auth.email-verification.from-address", () -> "no-reply@tickting.local");
		// 재설정 메일의 링크 앞부분. 메일은 FakeEmailSender 가 받아서 실제로 열리는 주소일 필요는 없다.
		registry.add("app.auth.password-reset.frontend-base-url", () -> "http://localhost:3000");
	}

	private static final Pattern REFRESH_COOKIE = Pattern.compile("refreshToken=([^;]*)");

	@Autowired
	protected MockMvc mockMvc;

	@Autowired
	protected JdbcTemplate jdbc;

	@Autowired
	protected TermsRepository termsRepository;

	@Autowired
	protected JwtProvider jwtProvider;

	@Autowired
	protected JwtProperties jwtProperties;

	@Autowired
	protected ObjectMapper objectMapper;

	/** 필수 약관(서비스 이용, 개인정보)의 번호 */
	protected List<Long> requiredTermIds;
	/** 선택 약관(탈퇴 약관)의 번호 */
	protected Long optionalTermId;

	@Autowired
	private StringRedisTemplate stringRedisTemplate;

	@Autowired
	protected FakeEmailSender fakeEmailSender;

	@Autowired
	protected ControllableEmailSendExecutor emailSendExecutor;

	@Autowired
	private EmailVerificationCodeService emailVerificationCodeService;

	@Autowired
	protected ControllableClock emailClock;

	/** 인증번호·재발송 간격·하루 횟수는 Redis 에 남아서 다음 테스트에 섞일 수 있다. 가짜 메일함·실행기·시계와 함께 테스트마다 비운다. */
	@BeforeEach
	void resetEmailVerification() {
		var keys = stringRedisTemplate.keys("tickting:email-verify:*");
		if (keys != null && !keys.isEmpty()) {
			stringRedisTemplate.delete(keys);
		}
		fakeEmailSender.reset();
		emailSendExecutor.reset();
		emailClock.reset();
	}

	/** 재설정 링크·간격·하루 횟수도 Redis 에 남아서 다음 테스트에 섞일 수 있다. 테스트마다 지운다. */
	@BeforeEach
	void clearPasswordReset() {
		var keys = stringRedisTemplate.keys("tickting:pw-reset:*");
		if (keys != null && !keys.isEmpty()) {
			stringRedisTemplate.delete(keys);
		}
	}

	/** 로그인 실패 횟수는 Redis 에 남아서 다음 테스트에서 같은 이메일이 잠길 수 있다. 테스트마다 지운다. */
	@BeforeEach
	void clearLoginFailures() {
		var keys = stringRedisTemplate.keys("tickting:login-fail:*");
		if (keys != null && !keys.isEmpty()) {
			stringRedisTemplate.delete(keys);
		}
	}

	@BeforeEach
	void resetDatabase() {
		jdbc.execute("""
			TRUNCATE TABLE user_member, user_terms_agreement, terms, seller_business, seller_member,
			               seller_invitation, admin_member, audit_log RESTART IDENTITY
			""");
		Long service = saveTerms(TermsType.SERVICE, "1.0", true);
		Long privacy = saveTerms(TermsType.PRIVACY, "1.0", true);
		requiredTermIds = List.of(service, privacy);
		optionalTermId = saveTerms(TermsType.WITHDRAW, "1.0", false);
	}

	protected Long saveTerms(TermsType type, String version, boolean required) {
		return termsRepository.save(Terms.builder()
			.type(type).version(version).content(type + " " + version).required(required)
			.build()).getId();
	}

	// ---- 요청 만들기 ----

	/**
	 * 예매자 가입 본문. 가입은 이메일 인증을 마친 이메일만 받으므로, 인증을 마친 것으로 표시해 두고 만든다.
	 * (가입이 아니라 다른 기능을 확인하는 테스트가 인증 절차를 매번 거치지 않게 하려는 것이다. 인증 절차 자체는 EmailVerificationApiTest 가 확인한다)
	 */
	protected Map<String, Object> userSignupBody(String email) {
		emailVerificationCodeService.markVerified(AccountType.USER, EmailUtil.normalize(email));
		return unverifiedUserSignupBody(email);
	}

	/** 인증 표시를 하지 않은 예매자 가입 본문. 인증 없이 가입하면 막히는지 확인할 때 쓴다. */
	protected Map<String, Object> unverifiedUserSignupBody(String email) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("email", email);
		body.put("password", PASSWORD);
		body.put("passwordConfirm", PASSWORD);
		body.put("nickname", "tester");
		body.put("name", "홍길동");
		body.put("phone", "01012345678");
		body.put("agreedTermIds", requiredTermIds);
		return body;
	}

	/**
	 * 판매자 가입 신청 본문. 예매자와 같이, 인증을 마친 것으로 표시해 두고 만든다. (인증 절차 자체는 EmailVerificationApiTest 가 확인한다)
	 * 판매자용 인증 표시는 예매자와 따로라서 SELLER 로 표시한다.
	 */
	protected Map<String, Object> sellerSignupBody(String email, String businessNumber) {
		emailVerificationCodeService.markVerified(AccountType.SELLER, EmailUtil.normalize(email));
		return unverifiedSellerSignupBody(email, businessNumber);
	}

	/** 인증 표시를 하지 않은 판매자 가입 본문. 인증 없이 가입하면 막히는지 확인할 때 쓴다. */
	protected Map<String, Object> unverifiedSellerSignupBody(String email, String businessNumber) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("email", email);
		body.put("password", PASSWORD);
		body.put("passwordConfirm", PASSWORD);
		body.put("name", "김대표");
		body.put("phone", "01099998888");
		body.put("companyName", "(주)가상기획");
		body.put("representativeName", "김대표");
		body.put("businessNumber", businessNumber);
		body.put("contactPhone", "01011112222");
		body.put("contactEmail", "Contact@Company.com");
		body.put("termsAgreed", true);
		return body;
	}

	protected Map<String, Object> loginBody(String email, String password) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("email", email);
		body.put("password", password);
		return body;
	}

	protected ResultActions postJson(String url, Object body) throws Exception {
		return mockMvc.perform(post(url)
			.contentType(MediaType.APPLICATION_JSON)
			.content(objectMapper.writeValueAsString(body)));
	}

	// ---- 응답 읽기 ----

	protected static String read(MvcResult result, String jsonPath) throws Exception {
		Object value = JsonPath.read(result.getResponse().getContentAsString(), jsonPath);
		return String.valueOf(value);
	}

	protected static String accessToken(MvcResult result) throws Exception {
		return read(result, "$.data.accessToken");
	}

	/** Set-Cookie 헤더에서 리프레시 토큰 값을 꺼낸다. 쿠키가 없으면 null */
	protected static String refreshCookie(MvcResult result) {
		String header = result.getResponse().getHeader("Set-Cookie");
		if (header == null) {
			return null;
		}
		Matcher matcher = REFRESH_COOKIE.matcher(header);
		return matcher.find() ? matcher.group(1) : null;
	}

	protected long count(String table) {
		return jdbc.queryForObject("SELECT count(*) FROM " + table, Long.class);
	}

	// ---- 동시성 ----

	/** 같은 순간에 시작하도록 맞춰 여러 스레드에서 작업을 실행하고 결과를 모은다. */
	protected <T> List<T> runConcurrently(int threads, IntFunction<Callable<T>> taskFactory) throws Exception {
		ExecutorService executor = Executors.newFixedThreadPool(threads);
		CountDownLatch ready = new CountDownLatch(threads);
		CountDownLatch start = new CountDownLatch(1);
		try {
			List<Future<T>> futures = new ArrayList<>();
			for (int i = 0; i < threads; i++) {
				Callable<T> task = taskFactory.apply(i);
				futures.add(executor.submit(() -> {
					ready.countDown();
					start.await();
					return task.call();
				}));
			}
			ready.await();
			start.countDown();

			List<T> results = new ArrayList<>();
			for (Future<T> future : futures) {
				results.add(future.get());
			}
			return results;
		} finally {
			executor.shutdownNow();
		}
	}
}
