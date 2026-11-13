package com.sitemonitor.service.sqlplayground;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Test yardımcısı: uygulamanın kalıcı şeması — {@code @Entity} tablo / kolonları (Hibernate'in adlandırma kuralıyla) +
 * kaynaktaki ham DDL ({@code CREATE TABLE …} dize sabitleri). Hassas kolon kapısı ve veri sözlüğü kapısı kullanır.
 */
final class SchemaSources {

    private SchemaSources() {}

    /** tablo → kolonlar (küçük harf). */
    static Map<String, Set<String>> entityColumns() {
        Map<String, Set<String>> out = new LinkedHashMap<>();
        var scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(Entity.class));
        for (BeanDefinition bd : scanner.findCandidateComponents("com.sitemonitor.model")) {
            Class<?> c;
            try {
                c = Class.forName(bd.getBeanClassName());
            } catch (ClassNotFoundException e) {
                continue;
            }
            Table t = c.getAnnotation(Table.class);
            String table = (t != null && !t.name().isBlank() ? t.name() : snake(c.getSimpleName())).toLowerCase(Locale.ROOT);
            Set<String> cols = out.computeIfAbsent(table, k -> new LinkedHashSet<>());
            for (Class<?> k = c; k != null && k != Object.class; k = k.getSuperclass()) {
                for (Field f : k.getDeclaredFields()) {
                    int m = f.getModifiers();
                    if (Modifier.isStatic(m) || Modifier.isTransient(m) || f.isAnnotationPresent(Transient.class)) continue;
                    Column col = f.getAnnotation(Column.class);
                    JoinColumn jc = f.getAnnotation(JoinColumn.class);
                    String name = col != null && !col.name().isBlank() ? col.name()
                            : jc != null && !jc.name().isBlank() ? jc.name() : snake(f.getName());
                    cols.add(name.toLowerCase(Locale.ROOT));
                }
            }
        }
        return out;
    }

    private static final Pattern LITERAL = Pattern.compile("\"((?:[^\"\\\\\\n]|\\\\.)*)\"");
    private static final Pattern CREATE = Pattern.compile("(?i)CREATE TABLE (?:IF NOT EXISTS )?(\\w+)\\s*\\(");
    private static final Set<String> NOT_COLUMN = Set.of("primary", "unique", "constraint", "check", "foreign", "exclude");

    /** {@code String AD = "değer";} — tek dize sabitleri (tablo adı sabitlerini DDL'e yerleştirmek için). */
    private static final Pattern CONSTANT = Pattern.compile("String\\s+([A-Z][A-Z0-9_]*)\\s*=\\s*\"([A-Za-z0-9_]+)\"\\s*;");
    /** {@code " + AD + "} ya da {@code " + Sinif.AD + "} — dize içine gömülü sabit. */
    private static final Pattern SPLICE = Pattern.compile("\"\\s*\\+\\s*(?:\\w+\\.)?([A-Z][A-Z0-9_]*)\\s*\\+\\s*\"");

    /** Kaynaktaki ham DDL: tablo → kolonlar (küçük harf). Tablo adı sabitle verilmişse ({@code " + JOBS + "}) yerine konur. */
    static Map<String, Set<String>> rawDdlColumns() throws IOException {
        Map<String, Set<String>> out = new LinkedHashMap<>();
        Path root = Path.of("src", "main", "java");
        Map<Path, String> sources = new LinkedHashMap<>();
        Map<String, String> globalConstants = new LinkedHashMap<>();
        try (Stream<Path> files = Files.walk(root)) {
            for (Path p : (Iterable<Path>) files.filter(x -> x.toString().endsWith(".java"))::iterator) {
                String src = Files.readString(p, StandardCharsets.UTF_8);
                Matcher c = CONSTANT.matcher(src);
                while (c.find()) globalConstants.putIfAbsent(c.group(1), c.group(2));
                if (src.contains("CREATE TABLE")) sources.put(p, src);
            }
        }
        for (String raw : sources.values()) {
            {   // dosya başına: önce dosyanın kendi sabitleri, yoksa başka dosyadaki aynı adlı sabit
                Map<String, String> local = new LinkedHashMap<>();
                Matcher c = CONSTANT.matcher(raw);
                while (c.find()) local.putIfAbsent(c.group(1), c.group(2));
                Matcher sp = SPLICE.matcher(raw);
                StringBuilder spliced = new StringBuilder();
                while (sp.find()) {
                    String v = local.getOrDefault(sp.group(1), globalConstants.get(sp.group(1)));
                    sp.appendReplacement(spliced, Matcher.quoteReplacement(v == null ? sp.group() : v));
                }
                sp.appendTail(spliced);
                String src = spliced.toString();
                StringBuilder lits = new StringBuilder();
                Matcher lm = LITERAL.matcher(src);
                while (lm.find()) lits.append(lm.group(1)).append(' ');
                String all = lits.toString();
                Matcher cm = CREATE.matcher(all);
                while (cm.find()) {
                    String table = cm.group(1).toLowerCase(Locale.ROOT);
                    Set<String> cols = out.computeIfAbsent(table, k -> new LinkedHashSet<>());
                    int depth = 1;
                    boolean expectName = true;
                    for (int i = cm.end(); i < all.length() && depth > 0; i++) {
                        char ch = all.charAt(i);
                        if (ch == '(') { depth++; continue; }
                        if (ch == ')') { depth--; continue; }
                        if (depth == 1 && ch == ',') { expectName = true; continue; }
                        if (depth == 1 && expectName && (Character.isLetter(ch) || ch == '_')) {
                            int j = i;
                            while (j < all.length() && (Character.isLetterOrDigit(all.charAt(j)) || all.charAt(j) == '_')) j++;
                            String w = all.substring(i, j).toLowerCase(Locale.ROOT);
                            if (!NOT_COLUMN.contains(w)) cols.add(w);
                            expectName = false;
                            i = j - 1;
                        }
                    }
                }
            }
        }
        return out;
    }

    static Map<String, Set<String>> allColumns() throws IOException {
        Map<String, Set<String>> out = new LinkedHashMap<>(entityColumns());
        rawDdlColumns().forEach((t, cols) -> out.computeIfAbsent(t, k -> new LinkedHashSet<>()).addAll(cols));
        return out;
    }

    static String snake(String s) {
        return s.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase(Locale.ROOT);
    }
}
