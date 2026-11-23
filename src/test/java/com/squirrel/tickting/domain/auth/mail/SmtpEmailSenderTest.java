package com.squirrel.tickting.domain.auth.mail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.squirrel.tickting.domain.auth.service.EmailVerificationProperties;
import com.squirrel.tickting.global.security.AccountType;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 진짜 SMTP 발송 코드(SmtpEmailSender)가 만드는 메일을 확인한다. 다른 테스트는 가짜 발송기로 갈아 끼워서 이 코드를 실행하지 않는다.
 * 로컬에서 쓰는 것과 같은 개발용 메일 서버(Mailpit)를 컨테이너로 띄우고, 도착한 메일의 받는 사람·제목·본문을 읽는다.
 * (토스의 TossPaymentGatewayTest 와 같은 방식이다. SMTP 는 HTTP 가 아니라 가짜 서버를 따로 띄운다)
 */
class SmtpEmailSenderTest {

	private static final int SMTP_PORT = 1025;
	private static final int WEB_PORT = 8025;

	private static final GenericContainer<?> MAILPIT = new GenericContainer<>(
		DockerImageName.parse("axllent/mailpit:v1.34.0")).withExposedPorts(SMTP_PORT, WEB_PORT);

	private final HttpClient http = HttpClient.newHttpClient();
	private final JsonMapper json = JsonMapper.builder().build();

	@BeforeAll
	static void startMailpit() {
		MAILPIT.start();
	}

	@BeforeEach
	void clearMailbox() throws Exception {
		// 컨테이너는 테스트들이 같이 쓴다. 앞 테스트가 보낸 메일이 개수 확인을 어긋나지 않게 비운다
		HttpRequest request = HttpRequest.newBuilder(URI.create(
			"http://" + MAILPIT.getHost() + ":" + MAILPIT.getMappedPort(WEB_PORT) + "/api/v1/messages")).DELETE().build();
		http.send(request, HttpResponse.BodyHandlers.discarding());
	}

	@AfterAll
	static void stopMailpit() {
		MAILPIT.stop();
	}

	private static EmailVerificationProperties properties() {
		return new EmailVerificationProperties(Duration.ofMinutes(5), 5, Duration.ofSeconds(60), 10, "리셀노",
			"no-reply@tickting.local", 10, 200);
	}

	private static JavaMailSenderImpl mailSender(String host, int port) {
		JavaMailSenderImpl sender = new JavaMailSenderImpl();
		sender.setHost(host);
		sender.setPort(port);
		sender.setDefaultEncoding("UTF-8");
		// application.yaml 의 spring.mail.properties.mail.smtp.* 와 같은 제한
		sender.getJavaMailProperties().put("mail.smtp.connectiontimeout", "3000");
		sender.getJavaMailProperties().put("mail.smtp.timeout", "5000");
		sender.getJavaMailProperties().put("mail.smtp.writetimeout", "5000");
		return sender;
	}

	@Test
	@DisplayName("인증번호 메일이 받는 사람·제목·본문(번호, 유효 시간, 무시 안내)을 담아 한 통 나간다")
	void sendsOneMailWithCode() throws Exception {
		SmtpEmailSender sender = new SmtpEmailSender(
			mailSender(MAILPIT.getHost(), MAILPIT.getMappedPort(SMTP_PORT)), properties());

		sender.sendVerificationCode("receiver@example.com", "482915", Duration.ofMinutes(5));

		JsonNode messages = get("/api/v1/messages").path("messages");
		assertThat(messages).hasSize(1);
		JsonNode summary = messages.get(0);
		assertThat(summary.path("To").get(0).path("Address").asString()).isEqualTo("receiver@example.com");
		assertThat(summary.path("From").path("Address").asString()).isEqualTo("no-reply@tickting.local");
		assertThat(summary.path("From").path("Name").asString()).isEqualTo("리셀노");
		assertThat(summary.path("Subject").asString()).isEqualTo("[리셀노] 이메일 인증번호 안내");

		String text = get("/api/v1/message/" + summary.path("ID").asString()).path("Text").asString();
		assertThat(text).contains("인증번호: 482915").contains("5분 동안 유효합니다.").contains("본인이 요청하지 않았다면");
		assertThat(text).doesNotContain("http");
	}

	@Test
	@DisplayName("재설정 메일은 계정 종류를 제목에 담고, 텍스트와 HTML 본문 모두에 같은 링크와 유효 시간을 담아 한 통 나간다")
	void sendsPasswordResetMailWithTextAndHtml() throws Exception {
		SmtpEmailSender sender = new SmtpEmailSender(
			mailSender(MAILPIT.getHost(), MAILPIT.getMappedPort(SMTP_PORT)), properties());
		String link = "http://localhost:3000/reset-password#token=abc_DEF-123";

		sender.sendPasswordReset("receiver@example.com", AccountType.SELLER, link, Duration.ofMinutes(15));

		JsonNode summary = get("/api/v1/messages").path("messages").get(0);
		assertThat(summary.path("Subject").asString()).isEqualTo("[리셀노] 판매자 계정 비밀번호 재설정 안내");
		JsonNode message = get("/api/v1/message/" + summary.path("ID").asString());
		assertThat(message.path("Text").asString()).contains("판매자 계정").contains(link).contains("15분 동안 유효합니다.");
		String html = message.path("HTML").asString();
		assertThat(html).contains("판매자").contains("href=\"" + link + "\"").contains("15분 동안").contains("#d23a1f");
		// 링크를 글자로도 한 번 더 넣어서 버튼이 보이지 않는 메일 앱에서도 쓸 수 있다
		assertThat(html.split(java.util.regex.Pattern.quote(link), -1)).hasSize(3);
	}

	@Test
	@DisplayName("변경 알림 메일은 계정 종류를 담고 링크는 담지 않는다")
	void sendsPasswordChangedMailWithoutLink() throws Exception {
		SmtpEmailSender sender = new SmtpEmailSender(
			mailSender(MAILPIT.getHost(), MAILPIT.getMappedPort(SMTP_PORT)), properties());

		sender.sendPasswordChanged("receiver@example.com", AccountType.USER);

		JsonNode summary = get("/api/v1/messages").path("messages").get(0);
		assertThat(summary.path("Subject").asString()).isEqualTo("[리셀노] 예매자 계정 비밀번호가 변경되었습니다");
		JsonNode message = get("/api/v1/message/" + summary.path("ID").asString());
		assertThat(message.path("Text").asString()).contains("예매자 계정").doesNotContain("http");
		assertThat(message.path("HTML").asString()).contains("예매자").doesNotContain("href=");
	}

	@Test
	@DisplayName("로컬 설정(암호화는 쓰되 필수는 아님, 로그인 끔, 사용자·비밀번호 빈 값)으로 암호화·로그인을 지원하지 않는 서버에도 보내진다")
	void sendsWithLocalSettingsToServerWithoutEncryption() throws Exception {
		JavaMailSenderImpl mailSender = mailSender(MAILPIT.getHost(), MAILPIT.getMappedPort(SMTP_PORT));
		// application.yaml + application-local.yaml 가 합쳐진 로컬 값. .env 의 MAIL_USERNAME·MAIL_PASSWORD 가 빈 값이다
		mailSender.setUsername("");
		mailSender.setPassword("");
		mailSender.getJavaMailProperties().put("mail.smtp.starttls.enable", "true");
		mailSender.getJavaMailProperties().put("mail.smtp.starttls.required", "false");
		mailSender.getJavaMailProperties().put("mail.smtp.auth", "false");

		new SmtpEmailSender(mailSender, properties())
			.sendVerificationCode("receiver@example.com", "482915", Duration.ofMinutes(5));

		assertThat(get("/api/v1/messages").path("messages")).hasSize(1);
	}

	@Test
	@DisplayName("암호화를 필수로 한 운영 설정이면 암호화를 지원하지 않는 서버에는 보내지 않는다 (비밀번호·인증번호가 평문으로 나가지 않는다)")
	void refusesToSendWithoutEncryptionWhenRequired() throws Exception {
		JavaMailSenderImpl mailSender = mailSender(MAILPIT.getHost(), MAILPIT.getMappedPort(SMTP_PORT));
		// application.yaml 의 운영 기본값. 로그인 정보가 있어야 로그인 단계에서 먼저 실패하지 않고 암호화 단계까지 간다
		mailSender.setUsername("smtp-user");
		mailSender.setPassword("smtp-password");
		mailSender.getJavaMailProperties().put("mail.smtp.starttls.enable", "true");
		mailSender.getJavaMailProperties().put("mail.smtp.starttls.required", "true");
		mailSender.getJavaMailProperties().put("mail.smtp.auth", "true");

		SmtpEmailSender sender = new SmtpEmailSender(mailSender, properties());

		// 로그인 실패 같은 다른 이유가 아니라 암호화를 못 해서 거절된 것이어야 한다
		assertThatThrownBy(() -> sender.sendVerificationCode("receiver@example.com", "482915", Duration.ofMinutes(5)))
			.isInstanceOf(RuntimeException.class)
			.hasStackTraceContaining("STARTTLS");
		assertThat(get("/api/v1/messages").path("messages")).isEmpty();
	}

	@Test
	@DisplayName("메일 서버에 연결하지 못하면 예외가 난다 (비동기 발송이 이것을 받아 번호와 횟수를 되돌린다)")
	void failsWhenServerIsUnreachable() {
		// 닫혀 있는 포트. 연결 제한 시간(3초) 안에 실패한다
		SmtpEmailSender sender = new SmtpEmailSender(mailSender("localhost", 1), properties());

		assertThatThrownBy(() -> sender.sendVerificationCode("receiver@example.com", "482915", Duration.ofMinutes(5)))
			.isInstanceOf(RuntimeException.class);
	}

	private JsonNode get(String path) throws Exception {
		HttpRequest request = HttpRequest.newBuilder(
			URI.create("http://" + MAILPIT.getHost() + ":" + MAILPIT.getMappedPort(WEB_PORT) + path)).build();
		HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
		return json.readTree(response.body());
	}
}
