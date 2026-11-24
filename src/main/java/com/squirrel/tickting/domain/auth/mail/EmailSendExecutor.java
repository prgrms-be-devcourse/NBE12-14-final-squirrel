package com.squirrel.tickting.domain.auth.mail;

import java.util.concurrent.RejectedExecutionException;

/**
 * 메일 발송 작업을 맡아 뒤에서 실행한다. 요청 스레드가 발송이 끝나기를 기다리며 묶이지 않게 하려는 것이다.
 * 테스트는 같은 스레드에서 바로 실행하는 구현으로 갈아 끼운다.
 */
public interface EmailSendExecutor {

	/** 작업을 접수한다. 발송 대기가 가득 차서 받지 못하면 RejectedExecutionException. */
	void execute(Runnable task);
}
