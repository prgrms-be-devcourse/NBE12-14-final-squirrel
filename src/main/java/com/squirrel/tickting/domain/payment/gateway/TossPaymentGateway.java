package com.squirrel.tickting.domain.payment.gateway;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Base64;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.ObjectMapper;

/**
 * 토스페이먼츠 결제 승인·취소. https://docs.tosspayments.com 의 결제 승인·결제 취소 API 를 부른다.
 *
 * 인증은 시크릿 키 뒤에 ":" 를 붙여 base64 로 인코딩한 Basic 헤더다.
 * 승인 요청마다 주문 ID 를 멱등키(Idempotency-Key)로 보낸다. 응답을 못 받아 같은 요청을 다시 보내도 토스가 두 번 승인하지 않고 처음 결과를 돌려준다.
 *
 * 결과를 두 가지로 나눈다. 이 구분이 결제 상태를 정한다.
 *   - 4xx: 토스가 요청을 받아 판단하고 거절했다 → GatewayRejectedException (결제 실패로 기록)
 *   - 통신 오류·시간 초과·5xx·그 밖의 이상한 응답: 승인됐는지 모른다 → GatewayUnavailableException (승인 중으로 남기고 나중에 조회)
 * 모르는 것을 실패로 단정하면, 돈은 나갔는데 우리 쪽은 실패로 기록되는 최악의 경우가 생긴다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "app.payment", name = "gateway", havingValue = "toss")
public class TossPaymentGateway implements PaymentGateway {

	private static final String CONFIRM_PATH = "/v1/payments/confirm";
	private static final String STATUS_DONE = "DONE";
	private static final String DEFAULT_REJECT_MESSAGE = "결제가 승인되지 않았습니다.";
	private static final String CANCEL_PATH = "/v1/payments/{paymentKey}/cancel";
	private static final String DEFAULT_CANCEL_REJECT_MESSAGE = "환불이 처리되지 않았습니다.";
	/** 취소에 성공하면 결제 상태가 부분 취소 또는 전액 취소가 된다. */
	private static final Set<String> CANCELED_STATUSES = Set.of("PARTIAL_CANCELED", "CANCELED");
	/** 4xx 이지만 이미 승인된 결제라서 "거절"이 아니다. 실패로 기록하지 말고 승인 중으로 남겨 조회로 확정한다. */
	private static final Set<String> ALREADY_APPROVED_CODES = Set.of("ALREADY_PROCESSED_PAYMENT");
	private static final String LOOKUP_PATH = "/v1/payments/{paymentKey}";
	/** 조회한 결제가 토스에 없다. 사용자가 없는 결제 키를 보낸 경우 등이다. */
	private static final String LOOKUP_NOT_FOUND_CODE = "NOT_FOUND_PAYMENT";
	/**
	 * 돈이 나가지 않았고 앞으로도 나가지 않는 상태. 승인 실패(ABORTED), 시간 만료(EXPIRED), 취소됨(CANCELED)이다.
	 * 취소됨은 승인된 뒤 취소돼 돈이 이미 돌아간 경우도 있어서 우리가 환불할 것이 없다.
	 */
	private static final Set<String> NOT_PAID_STATUSES = Set.of("ABORTED", "EXPIRED", "CANCELED");

	private final RestClient tossRestClient;
	private final TossProperties properties;
	private final ObjectMapper objectMapper;

	@Override
	public GatewayPayment confirm(String paymentKey, String orderId, int amount) {
		TossPayment payment;
		try {
			payment = tossRestClient.post()
				.uri(CONFIRM_PATH)
				.header(HttpHeaders.AUTHORIZATION, authorization())
				.header("Idempotency-Key", orderId)
				.contentType(MediaType.APPLICATION_JSON)
				.body(new ConfirmRequest(paymentKey, orderId, amount))
				.retrieve()
				.onStatus(HttpStatusCode::is4xxClientError, (request, response) -> {
					throw toException(response.getBody().readAllBytes(), orderId);
				})
				.body(TossPayment.class);
		} catch (RestClientException e) {
			// 연결 실패, 시간 초과, 5xx, 읽을 수 없는 응답. 로그에는 주문 ID 만 남기고 요청 값(키)은 남기지 않는다.
			log.warn("토스 승인 요청의 결과를 알 수 없다. 주문 ID: {}", orderId, e);
			throw new GatewayUnavailableException("토스 승인 요청 실패", e);
		}

		if (payment == null || !STATUS_DONE.equals(payment.status())) {
			log.warn("토스가 승인 완료(DONE)가 아닌 응답을 줬다. 주문 ID: {}, 상태: {}", orderId, payment == null ? null : payment.status());
			throw new GatewayUnavailableException("토스 응답이 승인 완료 상태가 아니다");
		}
		return new GatewayPayment(
			payment.paymentKey() != null ? payment.paymentKey() : paymentKey,
			payment.method(),
			toLocalDateTime(payment.approvedAt()));
	}

	/**
	 * 결제 취소(환불). 토스의 결제 취소 API 에 취소 금액을 넣으면 부분 취소, 결제 금액 전부면 전액 취소가 된다.
	 * 승인과 같은 규칙으로 4xx 는 거절, 그 밖의 실패는 "모름"으로 나눈다. 모름이어도 같은 멱등키로 다시 보내면 되므로 이중 환불은 없다.
	 */
	@Override
	public void cancel(String paymentKey, int amount, String reason, String idempotencyKey) {
		TossPayment payment;
		try {
			payment = tossRestClient.post()
				.uri(CANCEL_PATH, paymentKey)
				.header(HttpHeaders.AUTHORIZATION, authorization())
				.header("Idempotency-Key", idempotencyKey)
				.contentType(MediaType.APPLICATION_JSON)
				.body(new CancelRequest(reason, amount))
				.retrieve()
				.onStatus(HttpStatusCode::is4xxClientError, (request, response) -> {
					throw toCancelException(response.getBody().readAllBytes(), idempotencyKey);
				})
				.body(TossPayment.class);
		} catch (RestClientException e) {
			log.warn("토스 환불 요청의 결과를 알 수 없다. 멱등키: {}", idempotencyKey, e);
			throw new GatewayUnavailableException("토스 환불 요청 실패", e);
		}

		if (payment == null || !CANCELED_STATUSES.contains(payment.status())) {
			log.warn("토스가 취소 상태가 아닌 응답을 줬다. 멱등키: {}, 상태: {}", idempotencyKey,
				payment == null ? null : payment.status());
			throw new GatewayUnavailableException("토스 응답이 취소 상태가 아니다");
		}
	}

	/**
	 * 결제 키로 결제를 조회한다. (GET /v1/payments/{paymentKey}) 승인 요청의 응답을 받지 못한 결제의 결과를 확인하는 용도다.
	 * 토스 문서도 "응답을 못 받았다면 결과를 추측하지 말고 결제 조회로 status 를 먼저 확인하라"고 안내한다.
	 * 주문 ID 조회(GET /v1/payments/orders/{orderId})를 쓰지 않는 이유: 토스는 결제위젯 시크릿 키(test_gsk_, live_gsk_)로
	 * 그 API 를 부를 수 없다고 안내하고, 결제 키 조회는 이 키의 사용 범위에 넣어 두었다.
	 *
	 * 결과를 나누는 기준은 돈이 나갔는지다. 모르면 모른다고 답해서 호출한 쪽이 결제를 그대로 두게 한다.
	 *   - 조회된 결제의 주문 ID 가 orderId 와 다름: 사용자가 다른 주문의 키를 보낸 것이다. 이 주문은 나가지 않았다.
	 *   - DONE: 돈이 나갔다.
	 *   - 조회되는 결제가 없음(404 NOT_FOUND_PAYMENT), ABORTED·EXPIRED·CANCELED: 나가지 않았고 앞으로도 나가지 않는다.
	 *   - 그 밖의 상태(READY, IN_PROGRESS, WAITING_FOR_DEPOSIT, PARTIAL_CANCELED 등)와 읽을 수 없는 응답: 아직 모른다.
	 *   - 통신 오류·시간 초과·5xx, 404 가 아닌 4xx(키 설정 문제 등): 모른다 → GatewayUnavailableException
	 */
	@Override
	public GatewayLookup lookup(String paymentKey, String orderId) {
		TossPayment payment;
		try {
			payment = tossRestClient.get()
				.uri(LOOKUP_PATH, paymentKey)
				.header(HttpHeaders.AUTHORIZATION, authorization())
				.retrieve()
				.body(TossPayment.class);
		} catch (HttpClientErrorException e) {
			String code = readErrorCode(e.getResponseBodyAsByteArray(), orderId);
			if (e.getStatusCode().value() == 404 && LOOKUP_NOT_FOUND_CODE.equals(code)) {
				return GatewayLookup.notPaid();
			}
			log.warn("토스 결제 조회가 거절됐다. 주문 ID: {}, 상태 코드: {}, 에러 코드: {}", orderId, e.getStatusCode().value(), code);
			throw new GatewayUnavailableException("토스 결제 조회가 거절됐다: " + code, e);
		} catch (RestClientException e) {
			log.warn("토스 결제 조회의 결과를 알 수 없다. 주문 ID: {}", orderId, e);
			throw new GatewayUnavailableException("토스 결제 조회 실패", e);
		}

		if (payment == null || payment.status() == null || payment.orderId() == null) {
			throw new GatewayUnavailableException("토스 결제 조회 응답을 읽을 수 없다");
		}
		if (!orderId.equals(payment.orderId())) {
			log.error("승인 요청에 온 결제 키가 다른 주문의 것이다. 이 주문은 결제되지 않은 것으로 본다. 주문 ID: {}, 조회된 주문 ID: {}",
				orderId, payment.orderId());
			return GatewayLookup.notPaid();
		}
		if (STATUS_DONE.equals(payment.status())) {
			if (payment.paymentKey() == null || payment.totalAmount() == null) {
				throw new GatewayUnavailableException("토스 결제 조회 응답에 결제 키나 금액이 없다");
			}
			return GatewayLookup.paid(
				new GatewayPayment(payment.paymentKey(), payment.method(), toLocalDateTime(payment.approvedAt())),
				payment.totalAmount());
		}
		if (NOT_PAID_STATUSES.contains(payment.status())) {
			return GatewayLookup.notPaid();
		}
		if ("PARTIAL_CANCELED".equals(payment.status())) {
			log.error("승인 중으로 남은 결제가 토스에서 부분 취소 상태다. 사람이 확인해야 한다. 주문 ID: {}", orderId);
		}
		return GatewayLookup.undecided();
	}

	private String readErrorCode(byte[] body, String orderId) {
		try {
			TossError error = objectMapper.readValue(body, TossError.class);
			return error.code() != null ? error.code() : "UNKNOWN";
		} catch (RuntimeException e) {
			log.warn("토스 에러 응답을 읽지 못했다. 주문 ID: {}", orderId);
			return "UNKNOWN";
		}
	}

	/** 환불 거절은 승인 거절과 달리 "이미 처리됨" 같은 예외 코드가 없다. 같은 멱등키의 재요청은 토스가 처음 결과를 돌려준다. */
	private RuntimeException toCancelException(byte[] body, String idempotencyKey) {
		String code = "UNKNOWN";
		String message = DEFAULT_CANCEL_REJECT_MESSAGE;
		try {
			TossError error = objectMapper.readValue(body, TossError.class);
			code = error.code() != null ? error.code() : code;
			message = error.message() != null ? error.message() : message;
		} catch (RuntimeException e) {
			log.warn("토스 환불 에러 응답을 읽지 못했다. 멱등키: {}", idempotencyKey);
		}
		return new GatewayRejectedException(code, message);
	}

	private String authorization() {
		String credentials = properties.secretKey() + ":";
		return "Basic " + Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
	}

	/** 토스의 에러 본문({"code": "...", "message": "..."})을 읽어 거절 예외로 바꾼다. 읽지 못해도 거절은 거절이다. */
	private RuntimeException toException(byte[] body, String orderId) {
		String code = "UNKNOWN";
		String message = DEFAULT_REJECT_MESSAGE;
		try {
			TossError error = objectMapper.readValue(body, TossError.class);
			code = error.code() != null ? error.code() : code;
			message = error.message() != null ? error.message() : message;
		} catch (RuntimeException e) {
			log.warn("토스 에러 응답을 읽지 못했다. 주문 ID: {}", orderId);
		}
		if (ALREADY_APPROVED_CODES.contains(code)) {
			return new GatewayUnavailableException("토스에서 이미 처리된 결제: " + code);
		}
		return new GatewayRejectedException(code, message);
	}

	private static LocalDateTime toLocalDateTime(OffsetDateTime approvedAt) {
		return approvedAt == null
			? LocalDateTime.now()
			: approvedAt.atZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime();
	}

	record ConfirmRequest(String paymentKey, String orderId, int amount) {
	}

	record CancelRequest(String cancelReason, int cancelAmount) {
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	record TossPayment(String paymentKey, String orderId, String status, String method, OffsetDateTime approvedAt,
					   Integer totalAmount) {
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	record TossError(String code, String message) {
	}
}
