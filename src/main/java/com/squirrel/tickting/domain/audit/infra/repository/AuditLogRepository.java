package com.squirrel.tickting.domain.audit.infra.repository;

import com.squirrel.tickting.domain.audit.infra.entity.AuditLog;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AuditLogRepository extends JpaRepository<AuditLog, Long> {
}
