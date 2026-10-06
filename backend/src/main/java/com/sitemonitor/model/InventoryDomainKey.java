package com.sitemonitor.model;

import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.regex.Pattern;

/**
 * Envanter anahtarının ({@code certificate_inventory.domain}) biçim kuralı — kaynağa göre (2026-10-06).
 *
 * <p>Ağdan kontrol edilen kayıt (kaynak {@code null}) için kural, eskiden alana yazılı olan {@code @Pattern}'in
 * AYNISIDIR ({@link #NETWORK_PATTERN}, ileti ve ihlal yolu {@code domain}). Elle yüklenen sertifika kaydı
 * ({@code cert_source = MANUAL}) bir host adı değil, kullanıcının seçtiği "takip adı"dır; onun kuralı
 * {@link #MANUAL_PATTERN}'dir (küçük harf, {@code . _ -}; boşluk, {@code : / @} yok, en çok 253 karakter).
 *
 * <p>Neden sınıf düzeyinde: alan düzeyindeki {@code @Pattern} kaydın kaynağını göremez; ağ kaydının kuralı
 * gevşetilmeden manuel anahtar kabul edilemezdi. Hem istek gövdesi ({@code @Valid}) hem JPA kalıcılaştırma
 * doğrulaması bu kısıtı uygular.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = InventoryDomainKey.KeyValidator.class)
public @interface InventoryDomainKey {

    /** Ağ kaydının (eski alan düzeyi {@code @Pattern}) biçimi — DEĞİŞTİRİLMEDİ. */
    Pattern NETWORK_PATTERN = Pattern.compile(
            "^(?!-)(?!.*--)(?:\\*\\.)?[A-Za-z0-9]([A-Za-z0-9-]{0,61}[A-Za-z0-9])?(?:\\.[A-Za-z0-9]([A-Za-z0-9-]{0,61}[A-Za-z0-9])?)*$");

    /** Manuel sertifika takip adının biçimi (sözleşme). Uzunluk ayrıca ≤ 253 ile sınırlıdır. */
    Pattern MANUAL_PATTERN = Pattern.compile("^(\\*\\.)?[a-z0-9]([a-z0-9._-]{0,251}[a-z0-9])?$");

    /** Manuel anahtarın azami uzunluğu — kolonun {@code @Size(max = 253)} sınırıyla aynı. */
    int MANUAL_MAX_LENGTH = 253;

    String message() default "Geçersiz domain formatı";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};

    class KeyValidator implements ConstraintValidator<InventoryDomainKey, CertificateInventory> {

        /** Manuel takip adı biçim kontrolü — servis doğrulayıcısı ve kısıt aynı kuralı kullanır. */
        public static boolean isValidManualKey(String key) {
            return key != null && key.length() <= MANUAL_MAX_LENGTH && MANUAL_PATTERN.matcher(key).matches();
        }

        @Override
        public boolean isValid(CertificateInventory inv, ConstraintValidatorContext ctx) {
            if (inv == null || inv.getDomain() == null) return true;   // @Pattern gibi: null'ı @NotBlank yakalar
            String d = inv.getDomain();
            boolean ok;
            String message;
            if (inv.isManual()) {
                ok = isValidManualKey(d);
                message = "Geçersiz takip adı";
            } else {
                ok = NETWORK_PATTERN.matcher(d).matches();
                message = "Geçersiz domain formatı";
            }
            if (ok) return true;
            ctx.disableDefaultConstraintViolation();
            ctx.buildConstraintViolationWithTemplate(message).addPropertyNode("domain").addConstraintViolation();
            return false;
        }
    }
}
