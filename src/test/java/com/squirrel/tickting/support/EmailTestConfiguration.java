package com.squirrel.tickting.support;

import com.squirrel.tickting.domain.auth.mail.EmailSendExecutor;
import com.squirrel.tickting.domain.auth.mail.EmailSender;
import com.squirrel.tickting.global.security.AccountType;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.RejectedExecutionException;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * 이메일 인증에서 바깥(메일 서버, 스레드, 시계)에 닿는 부분을 테스트용으로 갈아 끼운다. (S3 의 FakeS3Uploader 와 같은 방식)
 * 진짜 SmtpEmailSender 는 SmtpEmailSenderTest 가 가짜 메일 서버(Mailpit)를 상대로 따로 확인한다.
 *
 * <ul>
 *   <li>메일: 보낸 번호를 받는 주소별로 메모리에 담는다. 테스트가 번호를 읽어 인증한다</li>
 *   <li>발송 실행기: 기본은 요청한 스레드에서 바로 실행한다. 발송이 끝나기를 기다리지 않아도 되게 하려는 것이다.
 *       나중에 실패하는 경우를 만들려고 접수한 작업을 붙잡아 두거나 접수를 거절하게 바꿀 수 있다</li>
 *   <li>시계: 기본은 실제 한국 시간이고, 자정 초기화를 확인하려고 시각을 바꿀 수 있다</li>
 * </ul>
 */
@TestConfiguration(proxyBeanMethods = false)
public class EmailTestConfiguration {

	@Bean
	@Primary
	FakeEmailSender fakeEmailSender() {
		return new FakeEmailSender();
	}

	@Bean
	@Primary
	ControllableEmailSendExecutor controllableEmailSendExecutor() {
		return new ControllableEmailSendExecutor();
	}

	@Bean
	@Primary
	ControllableClock controllableClock() {
		return new ControllableClock();
	}

	public static class FakeEmailSender implements EmailSender {

		private final Map<String, List<String>> sent = new ConcurrentHashMap<>();
		private volatile boolean failing;

		@Override
		public void sendVerificationCode(String to, String code, Duration validFor) {
			if (failing) {
				throw new IllegalStateException("가짜 메일 서버가 발송에 실패했습니다.");
			}
			sent.computeIfAbsent(to, key -> new CopyOnWriteArrayList<>()).add(code);
		}

		private final Map<String, List<String>> resetLinks = new ConcurrentHashMap<>();
		private final Map<String, List<AccountType>> resetTypes = new ConcurrentHashMap<>();
		private final Map<String, List<AccountType>> changedNotices = new ConcurrentHashMap<>();

		@Override
		public void sendPasswordReset(String to, AccountType type, String link, Duration validFor) {
			if (failing) {
				throw new IllegalStateException("가짜 메일 서버가 발송에 실패했습니다.");
			}
			resetLinks.computeIfAbsent(to, key -> new CopyOnWriteArrayList<>()).add(link);
			resetTypes.computeIfAbsent(to, key -> new CopyOnWriteArrayList<>()).add(type);
		}

		@Override
		public void sendPasswordChanged(String to, AccountType type) {
			if (failing) {
				throw new IllegalStateException("가짜 메일 서버가 발송에 실패했습니다.");
			}
			changedNotices.computeIfAbsent(to, key -> new CopyOnWriteArrayList<>()).add(type);
		}

		private final Map<String, List<String>> invitationLinks = new ConcurrentHashMap<>();
		private final Map<String, List<String>> invitationCompanies = new ConcurrentHashMap<>();

		@Override
		public void sendStaffInvitation(String to, String companyName, String link, Duration validFor) {
			if (failing) {
				throw new IllegalStateException("가짜 메일 서버가 발송에 실패했습니다.");
			}
			invitationLinks.computeIfAbsent(to, key -> new CopyOnWriteArrayList<>()).add(link);
			invitationCompanies.computeIfAbsent(to, key -> new CopyOnWriteArrayList<>()).add(companyName);
		}

		/** 이 주소로 마지막에 보낸 초대 링크. 보낸 적이 없으면 null */
		public String lastInvitationLink(String to) {
			List<String> links = invitationLinks.get(to);
			return links == null || links.isEmpty() ? null : links.get(links.size() - 1);
		}

		/** 이 주소로 마지막에 보낸 초대 링크의 토큰. 링크의 # 뒤에서 읽는다. */
		public String lastInvitationToken(String to) {
			String link = lastInvitationLink(to);
			return link == null ? null : link.substring(link.indexOf("#token=") + "#token=".length());
		}

		/** 이 주소로 마지막에 보낸 초대 메일의 회사 이름. 보낸 적이 없으면 null */
		public String lastInvitationCompany(String to) {
			List<String> companies = invitationCompanies.get(to);
			return companies == null || companies.isEmpty() ? null : companies.get(companies.size() - 1);
		}

		/** 이 주소로 마지막에 보낸 재설정 링크. 보낸 적이 없으면 null */
		public String lastResetLink(String to) {
			List<String> links = resetLinks.get(to);
			return links == null || links.isEmpty() ? null : links.get(links.size() - 1);
		}

		/** 이 주소로 마지막에 보낸 재설정 링크의 토큰. 링크의 # 뒤에서 읽는다. */
		public String lastResetToken(String to) {
			String link = lastResetLink(to);
			return link == null ? null : link.substring(link.indexOf("#token=") + "#token=".length());
		}

		public AccountType lastResetType(String to) {
			List<AccountType> types = resetTypes.get(to);
			return types == null || types.isEmpty() ? null : types.get(types.size() - 1);
		}

		public int resetLinkCount(String to) {
			List<String> links = resetLinks.get(to);
			return links == null ? 0 : links.size();
		}

		public int changedNoticeCount(String to) {
			List<AccountType> notices = changedNotices.get(to);
			return notices == null ? 0 : notices.size();
		}

		/** 이 주소로 마지막에 보낸 번호. 보낸 적이 없으면 null */
		public String lastCode(String to) {
			List<String> codes = sent.get(to);
			return codes == null || codes.isEmpty() ? null : codes.get(codes.size() - 1);
		}

		public int sentCount(String to) {
			List<String> codes = sent.get(to);
			return codes == null ? 0 : codes.size();
		}

		/** true 면 이후 발송이 실패한다. (메일 서버가 멈춘 상황) */
		public void setFailing(boolean failing) {
			this.failing = failing;
		}

		public void reset() {
			sent.clear();
			resetLinks.clear();
			resetTypes.clear();
			changedNotices.clear();
			invitationLinks.clear();
			invitationCompanies.clear();
			failing = false;
		}
	}

	public static class ControllableEmailSendExecutor implements EmailSendExecutor {

		private final List<Runnable> held = new ArrayList<>();
		private boolean holding;
		private boolean rejecting;

		@Override
		public synchronized void execute(Runnable task) {
			if (rejecting) {
				throw new RejectedExecutionException("가짜 발송 대기가 가득 찼습니다.");
			}
			if (holding) {
				held.add(task);
				return;
			}
			task.run();
		}

		/** true 면 이후 접수한 작업을 실행하지 않고 붙잡아 둔다. (발송이 아직 끝나지 않은 상태) */
		public synchronized void setHolding(boolean holding) {
			this.holding = holding;
		}

		/** true 면 이후 접수를 거절한다. (발송 대기가 가득 찬 상태) */
		public synchronized void setRejecting(boolean rejecting) {
			this.rejecting = rejecting;
		}

		/** 붙잡아 둔 작업 중 가장 먼저 접수한 것 하나를 실행한다. */
		public void runFirstHeld() {
			Runnable task;
			synchronized (this) {
				task = held.remove(0);
			}
			task.run();
		}

		/** 붙잡아 둔 작업을 접수한 순서대로 모두 실행한다. */
		public void runAllHeld() {
			while (true) {
				Runnable task;
				synchronized (this) {
					if (held.isEmpty()) {
						return;
					}
					task = held.remove(0);
				}
				task.run();
			}
		}

		public synchronized void reset() {
			held.clear();
			holding = false;
			rejecting = false;
		}
	}

	public static class ControllableClock extends Clock {

		private static final ZoneId ZONE = ZoneId.of("Asia/Seoul");

		private volatile Instant fixed;

		/** 이 한국 시각으로 시계를 고정한다. */
		public void setNow(LocalDateTime koreaTime) {
			this.fixed = koreaTime.atZone(ZONE).toInstant();
		}

		/** 실제 시각으로 되돌린다. */
		public void reset() {
			this.fixed = null;
		}

		@Override
		public ZoneId getZone() {
			return ZONE;
		}

		@Override
		public Clock withZone(ZoneId zone) {
			return this;
		}

		@Override
		public Instant instant() {
			Instant now = fixed;
			return now != null ? now : Instant.now();
		}
	}
}
