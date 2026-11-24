package com.squirrel.tickting.domain.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.squirrel.tickting.domain.payment.gateway.GatewayPayment;
import com.squirrel.tickting.domain.payment.gateway.GatewayRejectedException;
import com.squirrel.tickting.domain.payment.gateway.GatewayUnavailableException;
import com.squirrel.tickting.domain.payment.gateway.TossPaymentGateway;
import com.squirrel.tickting.domain.payment.gateway.TossProperties;
import com.squirrel.tickting.support.TestSecrets;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Base64;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

/**
 * 토스 결제 승인 호출. 토스 키가 없어도 돌도록 실제 토스 대신 가짜 서버(MockRestServiceServer)를 상대로
 * 요청의 모양(주소·인증 헤더·멱등키·본문)과 응답을 결과로 바꾸는 규칙을 확인한다.
 * 스프링 컨텍스트나 Mockito 를 쓰지 않는다.
 */
class TossPaymentGatewayTest {

	private static final String BASE_URL = "https://api.tosspayments.com";
	private static final String CONFIRM_URL = BASE_URL + "/v1/payments/confirm";
	private static final String ORDER_ID = "BK20261125KCZB03R-01";

	private final String secretKey = TestSecrets.randomTossSecretKey();
	private MockRestServiceServer server;
	private TossPaymentGateway gateway;

	@BeforeEach
	void setUp() {
		RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
		server = MockRestServiceServer.bindTo(builder).build();
		gateway = new TossPaymentGateway(builder.build(),
			new TossProperties(secretKey, BASE_URL, Duration.ofSeconds(2), Duration.ofSeconds(10)),
			JsonMapper.builder().build());
	}

	private static String errorBody(String code, String message) {
		return "{\"code\":\"" + code + "\",\"message\":\"" + message + "\"}";
	}

	@Test
	@DisplayName("승인 요청은 결제 승인 주소로, 시크릿 키 Basic 인증과 주문 ID 멱등키를 달고 결제 키·주문 ID·금액을 보낸다")
	void sendsConfirmRequest() {
		String expectedAuth = "Basic " + Base64.getEncoder().encodeToString((secretKey + ":").getBytes(StandardCharsets.UTF_8));
		server.expect(requestTo(CONFIRM_URL))
			.andExpect(method(HttpMethod.POST))
			.andExpect(header("Authorization", expectedAuth))
			.andExpect(header("Idempotency-Key", ORDER_ID))
			.andExpect(header("Content-Type", MediaType.APPLICATION_JSON_VALUE))
			.andExpect(jsonPath("$.paymentKey").value("pay_key_1"))
			.andExpect(jsonPath("$.orderId").value(ORDER_ID))
			.andExpect(jsonPath("$.amount").value(312000))
			.andRespond(withSuccess("""
				{"paymentKey":"pay_key_1","orderId":"%s","status":"DONE","method":"카드",
				 "approvedAt":"2026-11-25T20:05:12+09:00","totalAmount":312000,"card":{"number":"1234****"},"unknown":1}
				""".formatted(ORDER_ID), MediaType.APPLICATION_JSON));

		GatewayPayment payment = gateway.confirm("pay_key_1", ORDER_ID, 312000);

		server.verify();
		assertThat(payment.paymentKey()).isEqualTo("pay_key_1");
		assertThat(payment.method()).isEqualTo("카드");
		// 토스의 승인 시각(+09:00)은 서버의 시간대로 바꿔서 저장한다
		assertThat(payment.approvedAt()).isEqualTo(
			OffsetDateTime.parse("2026-11-25T20:05:12+09:00").atZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime());
	}

	@Test
	@DisplayName("토스가 4xx 로 거절하면 토스의 에러 코드와 문구를 담은 거절 예외로 바꾼다")
	void rejectedByToss() {
		server.expect(requestTo(CONFIRM_URL))
			.andRespond(withStatus(HttpStatus.BAD_REQUEST).contentType(MediaType.APPLICATION_JSON)
				.body(errorBody("REJECT_CARD_COMPANY", "카드 한도를 초과했습니다.")));

		assertThatThrownBy(() -> gateway.confirm("pay_key_1", ORDER_ID, 312000))
			.isInstanceOfSatisfying(GatewayRejectedException.class, e -> {
				assertThat(e.getCode()).isEqualTo("REJECT_CARD_COMPANY");
				assertThat(e.getMessage()).isEqualTo("카드 한도를 초과했습니다.");
			});
	}

	@Test
	@DisplayName("4xx 인데 에러 본문을 읽을 수 없어도 거절로 처리하고 기본 문구를 쓴다")
	void rejectedWithUnreadableBody() {
		server.expect(requestTo(CONFIRM_URL))
			.andRespond(withStatus(HttpStatus.UNAUTHORIZED).contentType(MediaType.TEXT_PLAIN).body("not json"));

		assertThatThrownBy(() -> gateway.confirm("pay_key_1", ORDER_ID, 312000))
			.isInstanceOfSatisfying(GatewayRejectedException.class, e -> {
				assertThat(e.getCode()).isEqualTo("UNKNOWN");
				assertThat(e.getMessage()).isEqualTo("결제가 승인되지 않았습니다.");
			});
	}

	@Test
	@DisplayName("이미 처리된 결제(ALREADY_PROCESSED_PAYMENT)는 거절이 아니라 결과를 알 수 없음으로 처리한다")
	void alreadyProcessedIsUnknown() {
		server.expect(requestTo(CONFIRM_URL))
			.andRespond(withStatus(HttpStatus.BAD_REQUEST).contentType(MediaType.APPLICATION_JSON)
				.body(errorBody("ALREADY_PROCESSED_PAYMENT", "이미 처리된 결제 입니다.")));

		assertThatThrownBy(() -> gateway.confirm("pay_key_1", ORDER_ID, 312000))
			.isInstanceOf(GatewayUnavailableException.class);
	}

	@Test
	@DisplayName("토스가 5xx 를 주거나 연결이 끊기면 승인 여부를 알 수 없는 것으로 처리한다 (실패로 단정하지 않는다)")
	void serverErrorAndIoErrorAreUnknown() {
		server.expect(requestTo(CONFIRM_URL))
			.andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR).contentType(MediaType.APPLICATION_JSON)
				.body(errorBody("FAILED_INTERNAL_SYSTEM_PROCESSING", "내부 시스템 처리 작업이 실패했습니다.")));
		assertThatThrownBy(() -> gateway.confirm("pay_key_1", ORDER_ID, 312000))
			.isInstanceOf(GatewayUnavailableException.class);

		server.reset();
		server.expect(requestTo(CONFIRM_URL)).andRespond(request -> {
			throw new IOException("read timed out");
		});
		assertThatThrownBy(() -> gateway.confirm("pay_key_1", ORDER_ID, 312000))
			.isInstanceOf(GatewayUnavailableException.class);
	}

	@Test
	@DisplayName("200 이지만 승인 완료(DONE) 상태가 아니면 승인 여부를 알 수 없는 것으로 처리한다")
	void notDoneIsUnknown() {
		server.expect(requestTo(CONFIRM_URL))
			.andRespond(withSuccess("{\"paymentKey\":\"pay_key_1\",\"status\":\"IN_PROGRESS\"}", MediaType.APPLICATION_JSON));

		assertThatThrownBy(() -> gateway.confirm("pay_key_1", ORDER_ID, 312000))
			.isInstanceOf(GatewayUnavailableException.class);
	}

	// ---------------------------------------------------------------- 환불(결제 취소)

	private static final String CANCEL_URL = BASE_URL + "/v1/payments/pay_key_1/cancel";
	private static final String REFUND_KEY = "3f2c8a1e-6b1d-4c3e-9f0a-1b2c3d4e5f60";

	@Test
	@DisplayName("환불 요청은 결제 키의 취소 주소로, 환불 행의 멱등키를 달고 취소 사유와 부분 취소 금액을 보낸다")
	void sendsCancelRequest() {
		server.expect(requestTo(CANCEL_URL))
			.andExpect(method(HttpMethod.POST))
			.andExpect(header("Idempotency-Key", REFUND_KEY))
			.andExpect(jsonPath("$.cancelReason").value("고객 요청 취소"))
			.andExpect(jsonPath("$.cancelAmount").value(140600))
			.andRespond(withSuccess("""
				{"paymentKey":"pay_key_1","status":"PARTIAL_CANCELED","cancels":[{"cancelAmount":140600}]}
				""", MediaType.APPLICATION_JSON));

		gateway.cancel("pay_key_1", 140600, "고객 요청 취소", REFUND_KEY);

		server.verify();
	}

	@Test
	@DisplayName("토스가 환불을 4xx 로 거절하면 거절 예외, 5xx 면 결과를 알 수 없음 예외다")
	void cancelFailures() {
		server.expect(requestTo(CANCEL_URL))
			.andRespond(withStatus(HttpStatus.BAD_REQUEST).contentType(MediaType.APPLICATION_JSON)
				.body(errorBody("NOT_CANCELABLE_AMOUNT", "취소할 수 없는 금액입니다.")));
		server.expect(requestTo(CANCEL_URL))
			.andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));

		assertThatThrownBy(() -> gateway.cancel("pay_key_1", 140600, "고객 요청 취소", REFUND_KEY))
			.isInstanceOfSatisfying(GatewayRejectedException.class,
				e -> assertThat(e.getCode()).isEqualTo("NOT_CANCELABLE_AMOUNT"));
		assertThatThrownBy(() -> gateway.cancel("pay_key_1", 140600, "고객 요청 취소", REFUND_KEY))
			.isInstanceOf(GatewayUnavailableException.class);
	}
}
