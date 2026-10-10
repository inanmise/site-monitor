package com.sitemonitor.repository;

import com.sitemonitor.model.TlsProfile;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

/** TLS profilleri (2026-10-10) — alan adı başına tek satır. Yazma yalnız {@code save} (tx'li JpaRepository yolu). */
public interface TlsProfileRepository extends JpaRepository<TlsProfile, String> {

    List<TlsProfile> findByDomainIn(Collection<String> domains);
}
