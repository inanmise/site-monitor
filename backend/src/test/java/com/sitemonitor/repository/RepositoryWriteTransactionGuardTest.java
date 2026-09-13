package com.sitemonitor.repository;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.AnnotatedBeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AssignableTypeFilter;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.repository.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * YAPI KAPISI: yazan her repository metodu transaction sahibini AÇIKÇA beyan etmek zorunda.
 *
 * <p><b>Neden var (2026-08-21, gerçek üretim hatası).</b> {@code ScriptedDraftRepository}'de
 * {@code void deleteByOwnerAndMonitorKey(...)} {@code @Transactional} taşımıyordu. Spring Data
 * yalnız {@code SimpleJpaRepository}'nin hazır metotlarına (save/delete/deleteAll…) kendiliğinden
 * transaction sarar; <b>türetilmiş</b> (metot adından üretilen) silme sorgularına SARMAZ. Uygulamada
 * {@code spring.jpa.open-in-view=false} ve çağıran {@code MonitoringController} transactional değil
 * → çağrı her seferinde {@code TransactionRequiredException} ile düşüyordu. Üstüne çağıran istisnayı
 * {@code log.debug} ile yutuyor, arayüz de yanıtı okumadan "taslak silindi" diyordu. Sonuç: kullanıcı
 * taslağı sildiğini sanıyor, uyarı her açılışta geri geliyor ve hiçbir yerde tek bir hata satırı yok.
 *
 * <p><b>Neden testler yakalayamadı.</b> Servis testleri repository'yi mock'luyor (mock'ta transaction
 * diye bir şey yok), {@code @DataJpaTest} ise her testi transaction'a SARAR — yani ambiyans tx'i
 * kendisi sağlayıp eksikliği görünmez kılar. Bu yüzden {@code ScriptedRepositoriesTest}'teki pinleyen
 * test bilinçli olarak {@code Propagation.NOT_SUPPORTED} ile koşuyor.
 *
 * <p><b>Kural.</b> Adı {@code delete}/{@code remove} ile başlayan ya da {@code @Modifying} taşıyan her
 * metot ya {@code @Transactional} taşır, ya da aşağıdaki listeye <b>gerekçesiyle</b> eklenir. Liste
 * kısayol değil, bilinçli karar noktasıdır: oraya bir satır eklemek "bu metot DAİMA transactional bir
 * çağıranın içinde koşar" taahhüdü vermektir.
 */
class RepositoryWriteTransactionGuardTest {

    /**
     * Transaction'ı ÇAĞIRAN yönetir — her satır, o metodu çağıran {@code @Transactional} sınıfı
     * göstermek zorunda. Yeni satır eklerken çağıranı gerçekten aç ve doğrula; "derlensin diye"
     * eklenen her satır yukarıdaki hatanın kapısını yeniden açar.
     */
    private static final Set<String> CALLER_MANAGED_TX = Set.of(
            // Grup yeniden adlandırma — MonitoringGroupService#rename (@Transactional, satır ~220).
            // Grubun monitör tablosu ve alarm geçmişi TEK transaction'da döner; metot başına ayrı
            // transaction, yarısı dönmüş bir grup adı bırakabilirdi.
            "CertificateInventoryRepository#renameGroupForTeam",
            "DnsMonitorRepository#renameGroupForTeam",
            "DomainMonitorRepository#renameGroupForTeam",
            "HttpMonitorRepository#renameGroupForTeam",
            "KeywordMonitorRepository#renameGroupForTeam",
            "PageMonitorRepository#renameGroupForTeam",
            "PageSpeedMonitorRepository#renameGroupForTeam",
            "PingMonitorRepository#renameGroupForTeam",
            "PortMonitorRepository#renameGroupForTeam",
            "ScriptedMonitorRepository#renameGroupForTeam",
            "AlertEventRepository#renameGroupForTeamAndTypes",
            // Domain yeniden adlandırma — AdminController#updateInventory (@Transactional, satır ~219).
            // Dört tablo aynı anda dönmezse geçmiş eski, envanter yeni domain'i gösterir.
            "AlertEventRepository#renameDomain",
            "CertificateCheckRepository#renameDomain",
            "CertificateNoteRepository#renameDomain",
            "LatestCheckRepository#renameDomain",
            // Kalıcı purge — AdminController#purgeInventory / #purgeAllDeleted (@Transactional):
            // kontrol geçmişi + latest_checks + notlar + envanter satırı tek adım.
            "CertificateCheckRepository#deleteByDomain",
            // Haftalık rapor — WeeklyReportService (@Transactional): rapor + görsel + mail kaydı + kilitler birlikte.
            "WeeklyReportImageRepository#deleteByReportId",
            "WeeklyReportMailRepository#deleteByReportId",
            "WeeklyReportCommentRepository#deleteByReportId",   // WeeklyReportService.delete (@Transactional), 2026-09-13
            "WeeklyReportRepository#clearLocksByUser",
            // Giriş sorunu bildirimi — LoginIssueService#delete (@Transactional): bildirim + görseller birlikte.
            "LoginIssueReportImageRepository#deleteByReportId",
            // Kullanıcı satırı damgaları — UserService (@Transactional): recordFailedLogin (satır ~249),
            // touchActiveSession (~257), clearAllActiveSessions (~318).
            "AppUserRepository#bumpFailedLogin",
            "AppUserRepository#touchLastSeen",
            "AppUserRepository#adoptSessionIfNone",   // touchActiveSession (@Transactional) — restart sonrası yeniden sahiplenme (QA ISSUE-002, 2026-09-13)
            "AppUserRepository#clearAllActiveSessions"
    );

    @Test
    @DisplayName("Türetilmiş silme / @Modifying metotları transaction sahibini beyan ediyor")
    void everyWritingRepositoryMethodDeclaresItsTransaction() {
        List<String> offenders = new ArrayList<>();

        for (Class<?> repo : repositoryInterfaces()) {
            if (repo.isAnnotationPresent(Transactional.class)) continue;   // arayüz düzeyinde beyan
            for (Method m : repo.getDeclaredMethods()) {
                if (!writes(m)) continue;
                if (m.isAnnotationPresent(Transactional.class)) continue;
                String id = repo.getSimpleName() + "#" + m.getName();
                if (CALLER_MANAGED_TX.contains(id)) continue;
                offenders.add(id);
            }
        }

        assertThat(offenders)
                .as("""
                    Bu metotlar yazıyor ama transaction sahibi belirsiz. Spring Data türetilmiş silme \
                    sorgularına ve @Modifying sorgularına KENDİLİĞİNDEN transaction sarmaz; \
                    open-in-view=false olduğu için tx'siz çağrı TransactionRequiredException ile düşer \
                    ve genelde bir catch bloğunda kaybolur (kullanıcı "silindi" görür, kayıt durur).
                    Yapılacak: metoda @Transactional ekle — YA DA daima transactional bir çağıranın \
                    içinde koştuğunu doğrulayıp CALLER_MANAGED_TX listesine çağıranı gösteren \
                    gerekçesiyle ekle.""")
                .isEmpty();
    }

    /** Ayrıca: liste bayatlamasın — silinmiş/yeniden adlandırılmış metotlar burada asılı kalmasın. */
    @Test
    @DisplayName("CALLER_MANAGED_TX listesinde artık var olmayan metot yok")
    void allowListHasNoStaleEntries() {
        Set<String> existing = new TreeSet<>();
        for (Class<?> repo : repositoryInterfaces()) {
            for (Method m : repo.getDeclaredMethods()) {
                if (writes(m)) existing.add(repo.getSimpleName() + "#" + m.getName());
            }
        }
        assertThat(existing)
                .as("Listedeki bu girdiler artık hiçbir repository'de yok — sil (yoksa liste "
                        + "gerçek bir eksikliği sessizce affeder).")
                .containsAll(CALLER_MANAGED_TX);
    }

    private static boolean writes(Method m) {
        return m.isAnnotationPresent(Modifying.class)
                || m.getName().startsWith("delete")
                || m.getName().startsWith("remove");
    }

    /**
     * {@code com.sitemonitor.repository} altındaki tüm repository ARAYÜZLERİ.
     * Varsayılan tarayıcı yalnız somut sınıfları aday sayar; burada aday ölçütü arayüz olacak
     * şekilde değiştirildi (Spring Data'nın kendi tarayıcısıyla aynı numara).
     */
    private static List<Class<?>> repositoryInterfaces() {
        var scanner = new ClassPathScanningCandidateComponentProvider(false) {
            @Override
            protected boolean isCandidateComponent(AnnotatedBeanDefinition beanDefinition) {
                return beanDefinition.getMetadata().isInterface() && beanDefinition.getMetadata().isIndependent();
            }
        };
        scanner.addIncludeFilter(new AssignableTypeFilter(Repository.class));

        List<Class<?>> found = new ArrayList<>();
        for (var bd : scanner.findCandidateComponents("com.sitemonitor.repository")) {
            try {
                found.add(Class.forName(bd.getBeanClassName()));
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException("Repository arayüzü yüklenemedi: " + bd.getBeanClassName(), e);
            }
        }
        assertThat(found).as("Repository taraması hiçbir şey bulamadı — tarayıcı bozulmuşsa bu "
                + "test sessizce YEŞİL kalır ve hiçbir şeyi korumaz.").hasSizeGreaterThan(20);
        return found;
    }
}
