package com.onsemi.cim.apps.exensio.resender.repository;

import com.onsemi.cim.apps.exensio.resender.entity.VerificationToken;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface VerificationTokenRepository extends JpaRepository<VerificationToken, Long> {
    Optional<VerificationToken> findByToken(String token);
}
