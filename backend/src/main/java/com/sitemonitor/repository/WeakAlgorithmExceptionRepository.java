package com.sitemonitor.repository;

import com.sitemonitor.model.WeakAlgorithmException;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/** Zayıf Algoritma Raporu istisnaları (2026-09-12). Silme, id ile {@code deleteById} (tx'li JpaRepository yolu). */
public interface WeakAlgorithmExceptionRepository extends JpaRepository<WeakAlgorithmException, Long> {
    Optional<WeakAlgorithmException> findByDomain(String domain);
}
