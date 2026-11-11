package com.squirrel.tickting.global.jwt;

import com.squirrel.tickting.global.exception.BusinessException;
import com.squirrel.tickting.global.exception.code.AuthExceptionCode;
import com.squirrel.tickting.global.security.AccountType;
import com.squirrel.tickting.global.security.CustomUserDetails;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import javax.crypto.SecretKey;
import org.springframework.stereotype.Component;

/**
 * JWT 발급과 검증.
 *
 * 토큰에 담는 값
 *  - sub: 계정 번호
 *  - type: 계정 종류(USER, SELLER, ADMIN)
 *  - role, businessId: 판매자일 때만 (대표/담당자, 소속 사업체)
 *  - kind: access 또는 refresh. refresh 토큰으로 API 를 호출하거나 그 반대가 되지 않게 구분한다.
 *
 * 검증에 실패하면 BusinessException(AuthExceptionCode)을 던진다.
 */
@Component
public class JwtProvider {

	private static final String CLAIM_TYPE = "type";
	private static final String CLAIM_ROLE = "role";
	private static final String CLAIM_BUSINESS_ID = "businessId";
	private static final String CLAIM_KIND = "kind";
	private static final String KIND_ACCESS = "access";
	private static final String KIND_REFRESH = "refresh";

	private final JwtProperties properties;
	private final SecretKey key;

	public JwtProvider(JwtProperties properties) {
		this.properties = properties;
		this.key = Keys.hmacShaKeyFor(properties.secret().getBytes(StandardCharsets.UTF_8));
	}

	public String createAccessToken(CustomUserDetails account) {
		var builder = baseBuilder(account.getId(), account.getAccountType(), KIND_ACCESS,
			properties.accessTokenValiditySeconds());
		if (account.getRole() != null) {
			builder.claim(CLAIM_ROLE, account.getRole());
		}
		if (account.getBusinessId() != null) {
			builder.claim(CLAIM_BUSINESS_ID, account.getBusinessId());
		}
		return builder.signWith(key).compact();
	}

	/** refresh 토큰에는 누구인지(번호, 종류)만 담는다. 권한은 재발급할 때 DB 에서 다시 읽는다. */
	public String createRefreshToken(Long accountId, AccountType accountType) {
		return baseBuilder(accountId, accountType, KIND_REFRESH, properties.refreshTokenValiditySeconds())
			.signWith(key).compact();
	}

	public CustomUserDetails parseAccessToken(String token) {
		return toAccount(parse(token, KIND_ACCESS));
	}

	public CustomUserDetails parseRefreshToken(String token) {
		return toAccount(parse(token, KIND_REFRESH));
	}

	private io.jsonwebtoken.JwtBuilder baseBuilder(Long accountId, AccountType accountType, String kind,
		long validitySeconds) {
		Instant now = Instant.now();
		return Jwts.builder()
			.issuer(properties.issuer())
			.subject(String.valueOf(accountId))
			.claim(CLAIM_TYPE, accountType.name())
			.claim(CLAIM_KIND, kind)
			.issuedAt(Date.from(now))
			.expiration(Date.from(now.plusSeconds(validitySeconds)));
	}

	private Claims parse(String token, String expectedKind) {
		Claims claims;
		try {
			claims = Jwts.parser()
				.verifyWith(key)
				.requireIssuer(properties.issuer())
				.build()
				.parseSignedClaims(token)
				.getPayload();
		} catch (ExpiredJwtException e) {
			throw new BusinessException(AuthExceptionCode.TOKEN_EXPIRED);
		} catch (JwtException | IllegalArgumentException e) {
			throw new BusinessException(AuthExceptionCode.TOKEN_INVALID);
		}
		if (!expectedKind.equals(claims.get(CLAIM_KIND, String.class))) {
			throw new BusinessException(AuthExceptionCode.TOKEN_INVALID);
		}
		return claims;
	}

	private CustomUserDetails toAccount(Claims claims) {
		try {
			Long id = Long.valueOf(claims.getSubject());
			AccountType type = AccountType.valueOf(claims.get(CLAIM_TYPE, String.class));
			String role = claims.get(CLAIM_ROLE, String.class);
			Number businessId = claims.get(CLAIM_BUSINESS_ID, Number.class);
			return new CustomUserDetails(id, type, role, businessId == null ? null : businessId.longValue());
		} catch (RuntimeException e) {
			// 서명은 맞는데 값이 이상한 경우 (번호가 숫자가 아님, 모르는 계정 종류 등)
			throw new BusinessException(AuthExceptionCode.TOKEN_INVALID);
		}
	}
}
