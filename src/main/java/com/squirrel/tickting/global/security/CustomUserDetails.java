package com.squirrel.tickting.global.security;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import lombok.Getter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

/**
 * 로그인한 사용자 정보. v1 은 요청마다 JWT 에서 꺼내고, v2 는 Redis 세션에 저장해 둔 값을 꺼낸다.
 * 컨트롤러에서는 어느 방식이든 @AuthenticationPrincipal CustomUserDetails 로 받는다.
 * Redis 세션에 저장되므로 Serializable 이다. (필드를 바꾸면 serialVersionUID 도 올린다. 저장된 세션은 읽지 못하게 되어 다시 로그인한다)
 *
 * 권한(authorities)은 아래처럼 만든다.
 *  - 예매자: ROLE_USER
 *  - 관리자: ROLE_ADMIN
 *  - 판매자: ROLE_SELLER, 그리고 역할에 따라 ROLE_SELLER_OWNER / ROLE_SELLER_STAFF
 * 그래서 @PreAuthorize("hasRole('SELLER')") 는 대표·담당자 모두 통과하고,
 * hasRole('SELLER_OWNER') 는 대표만 통과한다.
 */
@Getter
public class CustomUserDetails implements UserDetails, Serializable {

	private static final long serialVersionUID = 1L;

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

	/** 로그인할 때만 비밀번호를 확인하고, 토큰이나 세션에는 비밀번호를 담지 않는다. */
	@Override
	public String getPassword() {
		return null;
	}

	@Override
	public String getUsername() {
		return accountType.name() + ":" + id;
	}
}
