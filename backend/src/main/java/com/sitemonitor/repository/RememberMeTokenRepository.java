package com.sitemonitor.repository;

import com.sitemonitor.model.RememberMeToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

public interface RememberMeTokenRepository extends JpaRepository<RememberMeToken, Long> {

    Optional<RememberMeToken> findByToken(String token);

    /** Cihaz Geçmişi ekranı: kullanıcının hatırlanan cihaz(lar)ı. CANONICAL username ile aranır. */
    java.util.List<RememberMeToken> findByUsername(String username);

    @Transactional
    void deleteByToken(String token);

    /** Tek aktif oturum: yeni login'de kullanıcının TÜM remember-me token'larını iptal et
     *  (eski tarayıcı cookie ile sessizce geri dönemesin). */
    @Transactional
    void deleteByUsername(String username);

    /**
     * Cihaz Geçmişi: TEK hatırlanan cihazı iptal eder — sahiplik kontrolü SORGUNUN İÇİNDE.
     *
     * <p>Önce "oku, sahibi mi bak, sonra sil" yapmıyoruz: iki adım arasında satır değişebilir
     * (TOCTOU) ve kod "başkasının satırını sildim mi" sorusunu ancak dolaylı cevaplayabilirdi.
     * Dönen sayı 0 ise satır ya yok ya da başkasının — çağıran İKİSİNİ DE 404 sayar, böylece
     * "bu id var ama senin değil" bilgisi sızmaz.
     *
     * <p>{@code @Transactional} + {@code int} ŞART (RepositoryWriteTransactionGuardTest):
     * türetilmiş silme sorgularına Spring Data kendiliğinden transaction sarmaz ve
     * {@code open-in-view=false} olduğu için çağrı tx'siz düşerdi.
     */
    @Transactional
    int deleteByIdAndUsername(Long id, String username);

    @Modifying
    @Transactional
    @Query("DELETE FROM RememberMeToken t WHERE t.expiresAt < :now")
    void deleteExpired(long now);
}
