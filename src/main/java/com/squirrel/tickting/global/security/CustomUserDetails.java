package com.squirrel.tickting.global.security;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import lombok.Getter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

/**
 * 요청마다 JWT 에서 꺼내는 로그인 사용자 정보.
 * 컨트롤러에서는 @AuthenticationPrincipal CustomUserDetails 로 받는다.
 *
 * 권한(authorities)은 아래처럼 만든다.
 *  - 예매자: ROLE_USER
 *  - 관리자: ROLE_ADMIN
 *  - 판매자: ROLE_SELLER, 그리고 역할에 따라 ROLE_SELLER_OWNER / ROLE_SELLER_STAFF
 * 그래서 @PreAuthorize("hasRole('SELLER')") 는 대표·담당자 모두 통과하고,
 * hasRole('SELLER_OWNER') 는 대표만 통과한다.
 */
@Getter
public class CustomUserDetails implements UserDetails {

	private final Long id;
	private final AccountType accountType;
	/** 판매자의 역할(OWNER, STAFF). 판매자가 아니면 null */
	private final String role;
	/** 판매자의 소속 사업체 번호. 판매자가 아니면 null */
	private final Long businessId;

	public CustomUserDetails(Long id, AccountType accountType, String role, Long businessId) {
		this.id = id;
		this.accountType = accountType;
		this.role = role;
		this.businessId = businessId;
	}

	@Override
	public Collection<? extends GrantedAuthority> getAuthorities() {
		List<GrantedAuthority> authorities = new ArrayList<>();
		authorities.add(new SimpleGrantedAuthority("ROLE_" + accountType.name()));
		if (role != null) {
			authorities.add(new SimpleGrantedAuthority("ROLE_" + accountType.name() + "_" + role));
		}
		return authorities;
	}

	public boolean isAdmin() {
		return accountType == AccountType.ADMIN;
	}

	/** 토큰 인증이라 비밀번호는 들고 있지 않는다. */
	@Override
	public String getPassword() {
		return null;
	}

	@Override
	public String getUsername() {
		return accountType.name() + ":" + id;
	}
}
