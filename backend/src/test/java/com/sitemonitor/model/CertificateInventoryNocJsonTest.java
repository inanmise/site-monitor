package com.sitemonitor.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Envanter varlığı istek gövdesine DOĞRUDAN bağlanıyor (SNAKE_CASE). 7/24 alanlarının iki sözleşmesi:
 * <ul>
 *   <li>Yanıtta {@code noc_notify} boolean (null → false), {@code noc_group_ids} LİSTE — ham virgüllü değer ve
 *       "gövdede geldi mi" işaretleri JSON'a ÇIKMAZ.</li>
 *   <li>Gövdede alan YOKSA "geldi" işareti false kalır → {@code updateInventory} mevcut değeri korur (alanı bilmeyen
 *       eski form 7/24'ü sessizce kapatmaz).</li>
 * </ul>
 */
class CertificateInventoryNocJsonTest {

    /** Üretimdeki ayar: Spring global SNAKE_CASE (spring.jackson.property-naming-strategy). */
    private static final JsonMapper MAPPER = JsonMapper.builder()
            .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE).build();

    @Test
    @DisplayName("yanıt: noc_notify boolean, noc_group_ids liste; ham kolon ve işaretler çıkmaz")
    void serialises() throws Exception {
        CertificateInventory inv = new CertificateInventory();
        inv.setDomain("www.example.com");
        inv.setNocGroupIds("3,7");
        String json = MAPPER.writeValueAsString(inv);
        assertThat(json).contains("\"noc_notify\":false").contains("\"noc_group_ids\":[3,7]")
                .doesNotContain("supplied").doesNotContain("\"3,7\"");
        inv.setNocNotify(true);
        assertThat(MAPPER.writeValueAsString(inv)).contains("\"noc_notify\":true");
    }

    @Test
    @DisplayName("istek: gelen alan işaretlenir; gelmeyen alan işaretlenmez (updateInventory korur)")
    void deserialisesWithPresence() throws Exception {
        CertificateInventory with = MAPPER.readValue(
                "{\"domain\":\"www.example.com\",\"noc_notify\":true,\"noc_group_ids\":[5,5,9]}", CertificateInventory.class);
        assertThat(with.getNocNotify()).isTrue();
        assertThat(with.getNocGroupIds()).isEqualTo("5,9");
        assertThat(with.isNocNotifySupplied()).isTrue();
        assertThat(with.isNocGroupIdsSupplied()).isTrue();

        CertificateInventory without = MAPPER.readValue("{\"domain\":\"www.example.com\"}", CertificateInventory.class);
        assertThat(without.isNocNotifySupplied()).isFalse();
        assertThat(without.isNocGroupIdsSupplied()).isFalse();

        CertificateInventory cleared = MAPPER.readValue(
                "{\"domain\":\"www.example.com\",\"noc_group_ids\":null}", CertificateInventory.class);
        assertThat(cleared.isNocGroupIdsSupplied()).isTrue();
        assertThat(cleared.getNocGroupIds()).isNull();
        assertThat(List.of(cleared.nocGroupIdsForJson())).containsExactly(List.of());
    }
}
