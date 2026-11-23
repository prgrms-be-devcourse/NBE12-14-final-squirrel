package com.squirrel.tickting.domain.auth.mail;

import com.squirrel.tickting.domain.auth.service.EmailVerificationProperties;
import com.squirrel.tickting.global.security.AccountType;
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

	/** 재설정 메일은 버튼(HTML)과 같은 링크를 글자로도 넣고, HTML 을 못 보는 메일 앱을 위해 텍스트 본문도 함께 담는다. 본문 모양은 PasswordResetMailTemplate 이 만든다. */
	@Override
	public void sendPasswordReset(String to, AccountType type, String link, Duration validFor) {
		String account = accountLabel(type);
		send(to, "[" + properties.senderName() + "] " + account + " 계정 비밀번호 재설정 안내",
			PasswordResetMailTemplate.resetText(account, validFor.toMinutes(), link),
			PasswordResetMailTemplate.resetHtml(properties.senderName(), account, validFor.toMinutes(), link));
	}

	@Override
	public void sendPasswordChanged(String to, AccountType type) {
		String account = accountLabel(type);
		send(to, "[" + properties.senderName() + "] " + account + " 계정 비밀번호가 변경되었습니다",
			PasswordResetMailTemplate.changedText(account),
			PasswordResetMailTemplate.changedHtml(properties.senderName(), account));
	}

	private void send(String to, String subject, String text, String html) {
		try {
			MimeMessage message = mailSender.createMimeMessage();
			MimeMessageHelper helper = new MimeMessageHelper(message, html != null, "UTF-8");
			helper.setFrom(properties.fromAddress(), properties.senderName());
			helper.setTo(to);
			helper.setSubject(subject);
			if (html == null) {
				helper.setText(text, false);
			} else {
				helper.setText(text, html);
			}
			mailSender.send(message);
		} catch (MessagingException | UnsupportedEncodingException e) {
			throw new IllegalStateException("메일을 만들지 못했습니다.", e);
		}
	}

	private static String accountLabel(AccountType type) {
		return switch (type) {
			case USER -> "예매자";
			case SELLER -> "판매자";
			case ADMIN -> throw new IllegalArgumentException("관리자는 비밀번호 재설정 대상이 아닙니다.");
		};
	}
}
