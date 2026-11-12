package com.squirrel.tickting.domain.admin.infra.repository;

import com.squirrel.tickting.domain.admin.infra.entity.AdminMember;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AdminMemberRepository extends JpaRepository<AdminMember, Long> {

	Optional<AdminMember> findByEmailAndDeletedAtIsNull(String email);

	Optional<AdminMember> findByIdAndDeletedAtIsNull(Long id);
}
