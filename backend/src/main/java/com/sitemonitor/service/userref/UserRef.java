package com.sitemonitor.service.userref;

import tools.jackson.core.JsonGenerator;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.ValueSerializer;
import tools.jackson.databind.annotation.JsonSerialize;

/**
 * Yanıt haritasında anahtarı {@code id} olan (ya da adı kullanıcıyı belli etmeyen) bir KULLANICI kimliği. Değer
 * görüntüleyiciden bağımsız saklanır (önbelleğe alınmış haritalar her iki görüntüleyiciye de güvenle döner); yazılırken
 * {@link UserRefWire} kuralına göre global admin'e sayı, diğerlerine opak kimlik olur.
 *
 * <p>Anahtarı {@code user_id / manager_id / actor_id …} olan değerler için GEREK YOK — onları
 * {@link UserRefWire#KEYS} adından tanır. Bu sarmalayıcı yalnız belirsiz anahtarlar içindir (kullanıcı dizini
 * satırındaki {@code id}, toplu işlem sonuç satırı {@code id} gibi).
 */
@JsonSerialize(using = UserRef.Serializer.class)
public record UserRef(Long id) {

    public static UserRef of(Long id) {
        return id == null ? null : new UserRef(id);
    }

    @Override
    public String toString() {
        return String.valueOf(id);
    }

    static final class Serializer extends ValueSerializer<UserRef> {
        @Override
        public void serialize(UserRef value, JsonGenerator gen, SerializationContext ctxt) {
            UserRefWire.write(value == null ? null : value.id(), gen);
        }
    }

    /** Entity'de kullanıcı kimliği taşıyan ve adı belirsiz {@code Long} alanlar için ({@code AppUser.id}). */
    public static final class LongSerializer extends ValueSerializer<Long> {
        @Override
        public void serialize(Long value, JsonGenerator gen, SerializationContext ctxt) {
            UserRefWire.write(value, gen);
        }
    }
}
