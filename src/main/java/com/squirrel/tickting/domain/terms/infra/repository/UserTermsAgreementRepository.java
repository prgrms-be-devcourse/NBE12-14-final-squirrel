package com.squirrel.tickting.domain.terms.infra.repository;

import com.squirrel.tickting.domain.terms.infra.entity.UserTermsAgreement;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserTermsAgreementRepository extends JpaRepository<UserTermsAgreement, Long> {
}
