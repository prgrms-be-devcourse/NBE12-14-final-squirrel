package com.squirrel.tickting.domain.audit.service;

import com.squirrel.tickting.domain.audit.infra.repository.AuditLogRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AuditLogService {

	private final AuditLogRepository auditLogRepository;
}
