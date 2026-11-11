package com.squirrel.tickting.global.jwt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.squirrel.tickting.global.exception.BusinessException;
import com.squirrel.tickting.global.exception.code.AuthExceptionCode;
import com.squirrel.tickting.global.security.AccountType;
import com.squirrel.tickting.global.security.CustomUserDetails;
import com.squirrel.tickting.support.TestSecrets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class JwtProviderTest {

	// 키는 코드에 적지 않고 실행할 때 만든다. 서로 다른 두 키가 필요하다. (위조 토큰 검증용)
	private static final String SECRET = TestSecrets.randomJwtSecret();
	private static final String OTHER_SECRET = TestSecrets.randomJwtSecret();

	private final JwtProvider provider = new JwtProvider(new JwtProperties(SECRET, "tickting", 900, 1209600));

	@Test
	@DisplayName("판매자 액세스 토큰을 발급하고 파싱하면 번호, 계정 종류, 역할, 사업체 번호가 그대로 나온다")
	void sellerAccessTokenRoundTrip() {
		var seller = new CustomUserDetails(7L, AccountType.SELLER, "OWNER", 3L);

		CustomUserDetails parsed = provider.parseAccessToken(provider.createAccessToken(seller));

		assertThat(parsed.getId()).isEqualTo(7L);
		assertThat(parsed.getAccountType()).isEqualTo(AccountType.SELLER);
		assertThat(parsed.getRole()).isEqualTo("OWNER");
		assertThat(parsed.getBusinessId()).isEqualTo(3L);
	}

	@Test
	@DisplayName("예매자 토큰에는 역할과 사업체 번호가 없다")
	void userTokenHasNoRoleAndBusiness() {
		var user = new CustomUserDetails(5L, AccountType.USER, null, null);

		CustomUserDetails parsed = provider.parseAccessToken(provider.createAccessToken(user));

		assertThat(parsed.getAccountType()).isEqualTo(AccountType.USER);
		assertThat(parsed.getRole()).isNull();
		assertThat(parsed.getBusinessId()).isNull();
	}

	@Test
	@DisplayName("판매자 대표는 ROLE_SELLER 와 ROLE_SELLER_OWNER 권한을 가지고, 담당자는 OWNER 권한이 없다")
	void sellerAuthorities() {
		var owner = new CustomUserDetails(1L, AccountType.SELLER, "OWNER", 1L);
		var staff = new CustomUserDetails(2L, AccountType.SELLER, "STAFF", 1L);

		assertThat(owner.getAuthorities()).extracting(Object::toString)
			.containsExactlyInAnyOrder("ROLE_SELLER", "ROLE_SELLER_OWNER");
		assertThat(staff.getAuthorities()).extracting(Object::toString)
			.containsExactlyInAnyOrder("ROLE_SELLER", "ROLE_SELLER_STAFF");
	}

	@Test
	@DisplayName("예매자와 판매자의 번호가 같아도 계정 종류로 구분된다")
	void sameIdDifferentType() {
		var user = provider.parseAccessToken(
			provider.createAccessToken(new CustomUserDetails(5L, AccountType.USER, null, null)));
		var admin = provider.parseAccessToken(
			provider.createAccessToken(new CustomUserDetails(5L, AccountType.ADMIN, null, null)));

		assertThat(user.getUsername()).isEqualTo("USER:5");
		assertThat(admin.getUsername()).isEqualTo("ADMIN:5");
		assertThat(admin.isAdmin()).isTrue();
	}

	@Test
	@DisplayName("만료된 토큰은 TOKEN_EXPIRED 로 거부한다")
	void expiredToken() {
		var expiredProvider = new JwtProvider(new JwtProperties(SECRET, "tickting", -10, -10));
		String token = expiredProvider.createAccessToken(new CustomUserDetails(1L, AccountType.USER, null, null));

		assertCode(() -> provider.parseAccessToken(token), AuthExceptionCode.TOKEN_EXPIRED);
	}

	@Test
	@DisplayName("다른 키로 서명한 토큰은 TOKEN_INVALID 로 거부한다")
	void forgedToken() {
		var forger = new JwtProvider(new JwtProperties(OTHER_SECRET, "tickting", 900, 900));
		String token = forger.createAccessToken(new CustomUserDetails(1L, AccountType.ADMIN, null, null));

		assertCode(() -> provider.parseAccessToken(token), AuthExceptionCode.TOKEN_INVALID);
	}

	@Test
	@DisplayName("발급자가 다른 토큰은 TOKEN_INVALID 로 거부한다")
	void wrongIssuer() {
		var other = new JwtProvider(new JwtProperties(SECRET, "someone-else", 900, 900));
		String token = other.createAccessToken(new CustomUserDetails(1L, AccountType.USER, null, null));

		assertCode(() -> provider.parseAccessToken(token), AuthExceptionCode.TOKEN_INVALID);
	}

	@Test
	@DisplayName("형식이 깨진 문자열은 TOKEN_INVALID 로 거부한다")
	void garbageToken() {
		assertCode(() -> provider.parseAccessToken("not-a-jwt"), AuthExceptionCode.TOKEN_INVALID);
		assertCode(() -> provider.parseAccessToken(""), AuthExceptionCode.TOKEN_INVALID);
	}

	@Test
	@DisplayName("리프레시 토큰으로 액세스 토큰 자리를 통과할 수 없고, 반대도 마찬가지다")
	void accessAndRefreshAreNotInterchangeable() {
		String refresh = provider.createRefreshToken(1L, AccountType.USER);
		String access = provider.createAccessToken(new CustomUserDetails(1L, AccountType.USER, null, null));

		assertCode(() -> provider.parseAccessToken(refresh), AuthExceptionCode.TOKEN_INVALID);
		assertCode(() -> provider.parseRefreshToken(access), AuthExceptionCode.TOKEN_INVALID);
	}

	@Test
	@DisplayName("리프레시 토큰에는 계정 번호와 종류만 담긴다")
	void refreshTokenCarriesOnlyIdentity() {
		CustomUserDetails parsed = provider.parseRefreshToken(provider.createRefreshToken(9L, AccountType.SELLER));

		assertThat(parsed.getId()).isEqualTo(9L);
		assertThat(parsed.getAccountType()).isEqualTo(AccountType.SELLER);
		assertThat(parsed.getRole()).isNull();
		assertThat(parsed.getBusinessId()).isNull();
	}

	private void assertCode(Runnable action, AuthExceptionCode expected) {
		assertThatThrownBy(action::run)
			.isInstanceOfSatisfying(BusinessException.class,
				e -> assertThat(e.getExceptionCode()).isEqualTo(expected));
	}
}
