package com.sitemonitor.service.userref;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.sitemonitor.model.AppUser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.annotation.JsonSerialize;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Kapı (2026-10-08): bir entity'ye kullanıcı kimliği taşıyan yeni bir alan eklenirse ({@code fooUserId},
 * {@code approverId}… adları), yanıtta opak kimliğe dönmesi için snake_case adı {@link UserRefWire#KEYS}'te olmalı
 * ya da alan {@code @JsonIgnore} ile hiç yazılmamalı. Aksi hâlde global olmayan görüntüleyiciye sayısal kullanıcı
 * kimliği sızar ("bir kullanıcı başka bir kullanıcının id'sini okuyamasın").
 */
class UserRefCoverageGateTest {

    /** Kullanıcı kimliğini belli eden alan adları (model paketinde). */
    private static final Pattern USER_REF_NAME = Pattern.compile(
            "(?i).*(userid|userids|managerid|leaderid|actorid|authorid|ownerid|approverid|assigneeid|byid)$");

    private static String snake(String camel) {
        return camel.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase();
    }

    private static boolean idLike(Field f) {
        Class<?> t = f.getType();
        return t == Long.class || t == long.class || t == Integer.class || t == int.class
                || Collection.class.isAssignableFrom(t);
    }

    @Test
    @DisplayName("model paketindeki her kullanıcı-kimliği alanı ya KEYS'te ya da @JsonIgnore")
    void everyUserRefFieldIsCovered() throws IOException, ClassNotFoundException {
        Path dir = Path.of("src/main/java/com/sitemonitor/model");
        assertThat(dir).isDirectory();
        List<String> uncovered = new ArrayList<>();
        List<String> seen = new ArrayList<>();
        try (Stream<Path> files = Files.list(dir)) {
            for (Path p : files.filter(x -> x.toString().endsWith(".java")).toList()) {
                String name = p.getFileName().toString().replace(".java", "");
                Class<?> c = Class.forName("com.sitemonitor.model." + name);
                for (Class<?> k : withNested(c)) {
                    for (Field f : k.getDeclaredFields()) {
                        if (Modifier.isStatic(f.getModifiers()) || !idLike(f)) continue;
                        if (!USER_REF_NAME.matcher(f.getName()).matches()) continue;
                        seen.add(k.getSimpleName() + "." + f.getName());
                        boolean ignored = f.isAnnotationPresent(JsonIgnore.class);
                        boolean keyed = UserRefWire.KEYS.contains(snake(f.getName()));
                        if (!ignored && !keyed) uncovered.add(k.getSimpleName() + "." + f.getName() + " → " + snake(f.getName()));
                    }
                }
            }
        }
        assertThat(seen).as("tarayıcı gerçekten alan buldu (kör kapı değil)")
                .contains("AppUser.managerId", "Team.leaderId", "AuditLog.actorId", "EscalationContact.userId");
        assertThat(uncovered).as("UserRefWire.KEYS'e ekleyin ya da alanı @JsonIgnore yapın").isEmpty();
    }

    @Test
    @DisplayName("AppUser.id opak serileştiriciyle yazılır; public_id ayrı alan olarak hiç yazılmaz")
    void appUserIdUsesOpaqueSerializer() throws NoSuchFieldException {
        JsonSerialize js = AppUser.class.getDeclaredField("id").getAnnotation(JsonSerialize.class);
        assertThat(js).isNotNull();
        assertThat(js.using()).isEqualTo(UserRef.LongSerializer.class);
        assertThat(AppUser.class.getDeclaredField("publicId").isAnnotationPresent(JsonIgnore.class)).isTrue();
    }

    private static List<Class<?>> withNested(Class<?> c) {
        List<Class<?>> out = new ArrayList<>();
        out.add(c);
        for (Class<?> n : c.getDeclaredClasses()) out.addAll(withNested(n));
        return out;
    }
}
