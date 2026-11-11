package com.squirrel.tickting.domain.user.infra.repository;

import com.squirrel.tickting.domain.user.infra.entity.UserMember;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserMemberRepository extends JpaRepository<UserMember, Long> {

	Optional<UserMember> findByEmailAndDeletedAtIsNull(String email);

	Optional<UserMember> findByIdAndDeletedAtIsNull(Long id);

	boolean existsByEmailAndDeletedAtIsNull(String email);

	boolean existsByCiHashAndDeletedAtIsNull(String ciHash);
}
