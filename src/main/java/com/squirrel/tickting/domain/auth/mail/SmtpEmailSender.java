package com.squirrel.tickting.domain.auth.mail;

import com.squirrel.tickting.domain.auth.service.EmailVerificationProperties;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import java.io.UnsupportedEncodingException;
import java.time.Duration;
import lombok.RequiredArgsConstructor;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

/**
 * SMTP 로 인증번호 메일을 보낸다. 로컬은 Mailpit(개발용 가짜 메일 서버), 운영은 진짜 메일 서버이고 접속 정보만 다르다.
 * 접속·읽기·쓰기 제한 시간은 spring.mail.properties.mail.smtp.* 에 있다. (없으면 메일 서버가 멈출 때 무한정 기다린다)
 *
 * <p>메일은 텍스트다. 링크를 넣지 않고(피싱 메일과 헷갈리지 않게), 이름 같은 개인정보도 넣지 않는다.
 */
@Component
@RequiredArgsConstructor
public class SmtpEmailSender implements EmailSender {

	private final JavaMailSender mailSender;
	private final EmailVerificationProperties properties;

	@Override
	public void sendVerificationCode(String to, String code, Duration validFor) {
		try {
			MimeMessage message = mailSender.createMimeMessage();
			MimeMessageHelper helper = new MimeMessageHelper(message, false, "UTF-8");
			helper.setFrom(properties.fromAddress(), properties.senderName());
			helper.setTo(to);
			helper.setSubject("[" + properties.senderName() + "] 이메일 인증번호 안내");
			helper.setText("""
				인증번호: %s
				%d분 동안 유효합니다.
				본인이 요청하지 않았다면 이 메일을 무시해 주세요.
				""".formatted(code, validFor.toMinutes()), false);
			mailSender.send(message);
		} catch (MessagingException | UnsupportedEncodingException e) {
			throw new IllegalStateException("인증번호 메일을 만들지 못했습니다.", e);
		}
	}
}
