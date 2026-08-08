package com.sitemonitor.service.retention;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code docs/RETENTION_POLITIKASI.md} içeriğini KATALOGDAN üretir — doküman elle yazılmaz.
 *
 * <p>Neden: bu projede dokümanın koddan sapması somut zarar verdi. {@code docs/db-scaling.md} §2
 * yeniden adlandırma öncesindeki ESKİ anahtar önekini listelemeye devam ediyordu (kod
 * {@code site.monitor.*} okur); dokümanı izleyen bir ops mühendisi ayarı set eder, hiçbir şey
 * değişmez, sessizce varsayılana düşerdi.
 * {@code RetentionDocTest} üretilen metni dosyayla karşılaştırır — sapma derlemeyi kırar.
 */
public final class RetentionDocGenerator {

    private RetentionDocGenerator() { }

    private static final Map<RetentionPolicy.DataClass, String> CLASS_TR = new LinkedHashMap<>(Map.of(
            RetentionPolicy.DataClass.PERSONAL, "Kişisel Veri",
            RetentionPolicy.DataClass.SECURITY_AUDIT, "Denetim ve Güvenlik",
            RetentionPolicy.DataClass.CONTENT, "Kullanıcı İçeriği",
            RetentionPolicy.DataClass.OPERATIONAL, "İşletimsel Telemetri"));

    private static final List<RetentionPolicy.DataClass> ORDER = List.of(
            RetentionPolicy.DataClass.PERSONAL,
            RetentionPolicy.DataClass.SECURITY_AUDIT,
            RetentionPolicy.DataClass.CONTENT,
            RetentionPolicy.DataClass.OPERATIONAL);

    public static String generate() {
        StringBuilder sb = new StringBuilder();
        sb.append("# Veri Saklama Politikası\n\n");
        sb.append("> **Bu dosya ÜRETİLİR — elle düzenlemeyin.** Kaynak: `RetentionCatalog.ALL`.\n");
        sb.append("> Değişiklik için kataloğu güncelleyin; `RetentionDocTest` sapmayı yakalar.\n\n");

        sb.append("## Nasıl çalışır\n\n");
        sb.append("- Gece temizliği `0 30 3` cron'unda tek pod'da (`nightly-cleanup` dağıtık kilidi) koşar.\n");
        sb.append("- Ham seriler ÖNCE günlük özete (`monitor_check_daily`) alınır, SONRA silinir —\n");
        sb.append("  ham veri kısalsa da uzun dönem trend korunur.\n");
        sb.append("- Yüksek hacimli tablolar 10.000'lik dilimlerle silinir (`ANALYZE` ile biter):\n");
        sb.append("  tek dev DELETE yerine kısa transaction'lar, bloat ve uzun kilit yok.\n");
        sb.append("- Süreler **canlı** ayardır: Ayarlar → Veri Saklama'dan değiştirilir, ANINDA geçerli olur.\n");
        sb.append("  Her politikanın kodda tanımlı bir **taban (minDays)** değeri vardır; altına inilemez.\n");
        sb.append("- **Legal hold** (`").append(RetentionCatalog.HOLD_KEY).append("`): açıkken hiçbir satır\n");
        sb.append("  silinmez; her gece uyarı logu ve denetim kaydı yazılır (soruşturma/denetim valfi).\n\n");

        sb.append("## Uyum onayı\n\n");
        sb.append("Kişisel veri ve denetim kaydı içeren tablolarda süreyi **kodun varsayılanı değil,\n");
        sb.append("uyum/hukuk biriminin onayı** belirler. Onay bilgisi (kim, ne zaman, hangi dayanak)\n");
        sb.append("Ayarlar → Veri Saklama ekranından politika bazında kaydedilir. KVKK tarafında\n");
        sb.append("saklama-imha politikası ve 6 aylık periyodik imha ritmi, bankacılık tarafında\n");
        sb.append("BDDK/iç denetim süreleri esas alınmalıdır.\n\n");

        for (RetentionPolicy.DataClass dc : ORDER) {
            List<RetentionPolicy> list = RetentionCatalog.ALL.stream()
                    .filter(p -> p.dataClass() == dc).toList();
            if (list.isEmpty()) continue;
            sb.append("## ").append(CLASS_TR.get(dc)).append("\n\n");
            sb.append("| Tablo | Süre | Taban | Ayar anahtarı | Kural | Gerekçe |\n");
            sb.append("|---|---|---|---|---|---|\n");
            for (RetentionPolicy p : list) {
                sb.append("| `").append(p.table()).append("` | ")
                  .append(duration(p)).append(" | ")
                  .append(p.deletes() && p.configurable() ? p.minDays() + " g" : "—").append(" | ")
                  .append(p.settingKey() == null ? "—" : "`" + p.settingKey() + "`").append(" | ")
                  .append(rule(p)).append(" | ")
                  .append(p.rationale().replace("|", "\\|")).append(" |\n");
            }
            sb.append("\n");
        }

        sb.append("## Kapsam güvencesi\n\n");
        sb.append("`RetentionCoverageTest` her kalıcı tabloyu tarar: politikası olmayan ve gerekçeli\n");
        sb.append("muafiyet listesinde bulunmayan bir tablo varsa **derleme kırmızıya döner**.\n");
        sb.append("Bu, \"yeni izleme türü eklendi, temizliği unutuldu\" hata sınıfını kalıcı olarak kapatır.\n");
        return sb.toString();
    }

    private static String duration(RetentionPolicy p) {
        return switch (p.mode()) {
            case ORPHAN_ONLY -> "öksüz temizliği";
            case EXTERNAL -> "harici yönetilir";
            case BOUNDED -> "sınırlı büyür";
            default -> p.zeroMeansNever() && p.defaultDays() == 0
                    ? "kapalı (opt-in)"
                    : p.defaultDays() + " gün";
        };
    }

    private static String rule(RetentionPolicy p) {
        if (!p.deletes()) return "—";
        String w = p.resolvedWhere().replace("|", "\\|");
        return "`" + (w.length() > 110 ? w.substring(0, 107) + "…" : w) + "`";
    }
}
