package com.squirrel.tickting.domain.auth.mail;

import com.squirrel.tickting.global.security.AccountType;
import java.time.Duration;

/**
 * 이메일 인증번호 메일을 보낸다. 외부(메일 서버) 호출이라 인터페이스 뒤에 두고, 테스트는 가짜로 갈아 끼운다.
 * 보낼 때 DB 트랜잭션을 쥐고 있지 않도록 호출하는 쪽이 트랜잭션 밖에서 부른다.
 */
public interface EmailSender {

	/**
	 * 인증번호 메일을 보낸다. 메일 서버가 받아 줄 때까지 기다리고, 실패하면 RuntimeException 을 던진다.
	 * (받아 준 뒤 스팸함으로 가거나 상대 메일함에서 거절되는 것은 알 수 없다)
	 */
	void sendVerificationCode(String to, String code, Duration validFor);

	/**
	 * 비밀번호 재설정 링크 메일을 보낸다. 제목과 본문에 계정 종류(예매자·판매자)를 적어서, 같은 이메일이 양쪽에 가입돼 있어도 어느 계정의 메일인지 알 수 있게 한다.
	 * 실패하면 RuntimeException. 인증번호 메일과 같다.
	 */
	void sendPasswordReset(String to, AccountType type, String link, Duration validFor);

	/** 비밀번호가 바뀌었다는 알림 메일. 링크가 새어 바뀐 경우 본인이 알아챌 수 있게 한다. 실패하면 RuntimeException. */
	void sendPasswordChanged(String to, AccountType type);
}
