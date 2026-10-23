package com.sitemonitor.config;

import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.Trees;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.type.WildcardType;
import javax.lang.model.util.Types;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Oturum özniteliği TÜR kapısı (2026-10-23, çok pod sürüm 2b · MP-50 / INFRA-14c).
 *
 * <p>Prod'da oturumlar Spring Session JDBC'de ({@code spring_session_attributes}) JAVA SERİLEŞTİRMESİYLE saklanır ve
 * yuvarlanan dağıtımda ESKİ ve YENİ pod aynı oturumu okur. Öznitelik değeri bir uygulama sınıfıysa (kayıt, enum, DTO)
 * sınıfı değiştiren bir sürüm geldiğinde öbür pod onu çözemez ({@code InvalidClassException} / {@code
 * ClassNotFoundException}) — oturum o istekte okunamaz, kullanıcı rastgele düşer, dağıtım geri alınsa bile kayıtlar
 * kalır. JDK türleri ({@code java.lang} / {@code java.util} / {@code java.time} / {@code java.math}; tür
 * argümanları da) her sürümde aynıdır. Bu kapı {@code src/main/java}'daki HER oturum yazısını derleyici ağacıyla
 * (javac, tür çözümlü) bulur ve değer ifadesinin STATİK türünü izin listesine karşı denetler: {@code
 * HttpSession.setAttribute / putValue}, Spring Session {@code Session.setAttribute}, {@code
 * WebUtils.setSessionAttribute} ve kapsamı {@code SCOPE_REQUEST} olmayan {@code RequestAttributes.setAttribute}.
 * {@code Object}, ham koleksiyon ({@code List}), tür değişkeni ve çözülemeyen tür de RED — içeriği bilinemez.
 *
 * <p>{@link SessionAttributeContractTest} yazılan / okunan ADLARI denetler; bu kapı DEĞERLERİN türünü.
 * Bir uygulama nesnesini oturumda tutmak gerekirse alanlarını JDK türlerine açın (ör. kimlik + ad Map'i).
 */
class SessionAttributeTypesGateTest {

    private static final Path MAIN = Path.of("src/main/java");
    /** Oturuma yazabilecek dosyalar (kaba ön süzgeç; asıl karar tür çözümlü ağaçta). */
    private static final Pattern CANDIDATE = Pattern.compile("\\.setAttribute\\(|\\.putValue\\(|setSessionAttribute\\(");
    private static final List<String> JDK_PACKAGES = List.of("java.lang.", "java.util.", "java.time.", "java.math.");

    /** Bir oturum yazısı: yer, öznitelik ifadesi, değer türü, izinli mi. */
    record Write(String where, String attribute, String type, boolean allowed) {
        @Override public String toString() { return where + "  " + attribute + " = <" + type + ">"; }
    }

    record Result(List<Write> writes, List<String> errors) {
        List<Write> violations() { return writes.stream().filter(w -> !w.allowed()).toList(); }
    }

    @Test
    @DisplayName("src/main/java'daki her oturum özniteliği değeri bir JDK türü (eski + yeni pod aynı JDBC oturumunu okur)")
    void everySessionAttributeValueIsAJdkType() throws IOException {
        List<Path> files;
        try (Stream<Path> s = Files.walk(MAIN)) {
            files = s.filter(p -> p.toString().endsWith(".java")).filter(SessionAttributeTypesGateTest::isCandidate).sorted().toList();
        }
        assertThat(files).as("oturum yazan dosyalar bulunamadı (kaynak yolu değişti mi?)").isNotEmpty();
        Result r = analyze(fileObjects(files));
        // Bugün ~22 yazı var (AuthController, AuthInterceptor, NocOperatorService, TeamAccessGrantService,
        // MonitoringController). Sıfır = çözümleyici bozuldu (ör. classpath okunamadı) — sessizce yeşil olmasın.
        assertThat(r.writes()).as("çözümlenen oturum yazısı yok — derleyici hataları: %s", head(r.errors()))
                .hasSizeGreaterThanOrEqualTo(15);
        assertThat(r.violations())
                .as("oturuma JDK dışı (ya da içeriği bilinemeyen) değer yazılıyor — yuvarlanan dağıtımda eski / yeni pod onu"
                        + " çözemez. Değeri java.lang / java.util / java.time / java.math türlerine çevirin. Yazılar:\n%s",
                        String.join("\n", r.violations().stream().map(Write::toString).toList()))
                .isEmpty();
    }

    @Test
    @DisplayName("Çözümleyici kendini sınar: uygulama sınıfı, Object, ham liste, uygulama türlü liste, kayıt → RED; JDK türleri geçer")
    void analyzerFlagsAppTypes() throws IOException {
        String src = """
                package gate.sample;
                import jakarta.servlet.http.HttpSession;
                import org.springframework.web.context.request.RequestAttributes;
                import java.util.*;
                class Sample {
                    record Rec(long id) implements java.io.Serializable {}
                    void ok(HttpSession s, RequestAttributes ra, long now, List<Long> ids) {
                        s.setAttribute("a", "x");
                        s.setAttribute("b", now);
                        s.setAttribute("c", new ArrayList<>(ids));
                        s.setAttribute("d", Boolean.TRUE);
                        s.setAttribute("e", Map.of("k", List.of(1L)));
                        s.setAttribute("f", java.time.DayOfWeek.MONDAY);
                        s.setAttribute("g", null);
                        s.setAttribute("h", true);
                        ra.setAttribute("req", new Rec(1), RequestAttributes.SCOPE_REQUEST);
                    }
                    void bad(HttpSession s, RequestAttributes ra, com.sitemonitor.model.AppUser u, Object o, List raw) {
                        s.setAttribute("u", u);
                        s.setAttribute("o", o);
                        s.setAttribute("l", new ArrayList<com.sitemonitor.model.AppUser>());
                        s.setAttribute("r", raw);
                        s.setAttribute("rec", new Rec(2));
                        s.setAttribute("arr", new Rec[0]);
                        ra.setAttribute("sess", new Rec(3), RequestAttributes.SCOPE_SESSION);
                        org.springframework.web.util.WebUtils.setSessionAttribute(null, "w", new Rec(4));
                    }
                }
                """;
        Result r = analyze(List.of(new Source("gate/sample/Sample.java", src)));
        assertThat(r.errors()).as("örnek kaynak derlenmeli").isEmpty();
        assertThat(r.writes().stream().filter(Write::allowed).map(Write::attribute))
                .containsExactlyInAnyOrder("\"a\"", "\"b\"", "\"c\"", "\"d\"", "\"e\"", "\"f\"", "\"g\"", "\"h\"");
        assertThat(r.violations().stream().map(Write::attribute))
                .containsExactlyInAnyOrder("\"u\"", "\"o\"", "\"l\"", "\"r\"", "\"rec\"", "\"arr\"", "\"sess\"", "\"w\"");
    }

    // ── çözümleyici ───────────────────────────────────────────────────────────────────────

    private static boolean isCandidate(Path p) {
        try {
            return CANDIDATE.matcher(Files.readString(p, StandardCharsets.UTF_8)).find();
        } catch (IOException e) {
            throw new IllegalStateException(p.toString(), e);
        }
    }

    private static String head(List<String> errors) {
        return errors.isEmpty() ? "(yok)" : String.join(" | ", errors.subList(0, Math.min(5, errors.size())));
    }

    private static final class Source extends SimpleJavaFileObject {
        private final String code;
        Source(String path, String code) {
            super(URI.create("string:///" + path), Kind.SOURCE);
            this.code = code;
        }
        @Override public CharSequence getCharContent(boolean ignoreEncodingErrors) { return code; }
    }

    private static List<JavaFileObject> fileObjects(List<Path> files) throws IOException {
        List<JavaFileObject> out = new ArrayList<>();
        for (Path p : files) out.add(new Source(MAIN.relativize(p).toString().replace('\\', '/'), Files.readString(p, StandardCharsets.UTF_8)));
        return out;
    }

    /** Surefire gerçek test sınıf yolunu bu özellikte verir; manifest-only jar'da java.class.path yalnız önyükleyicidir. */
    private static String classpath() {
        String cp = System.getProperty("surefire.test.class.path");
        return cp != null && !cp.isBlank() ? cp : System.getProperty("java.class.path");
    }

    /**
     * Kaynakları ÜRETİM sınıf yoluyla (target/classes + bağımlılıklar) tür çözümlü derler (sınıf dosyası yazılmaz). Ek
     * açıklama işleyicisi çalışmaz (Lombok'un ürettiği üyeler yok — bunlara dayanan ifadeler hata verir ama analiz
     * sürer: {@code should-stop.ifError=FLOW}); oturum yazılarının değerleri JDK / sınıf dosyası türlerine çözülür.
     */
    static Result analyze(List<JavaFileObject> sources) throws IOException {
        JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
        assertThat(javac).as("testler bir JDK ile koşmalı (javax.tools derleyicisi yok)").isNotNull();
        DiagnosticCollector<JavaFileObject> diags = new DiagnosticCollector<>();
        List<String> opts = List.of("-proc:none", "-implicit:none", "-XDshould-stop.ifError=FLOW", "-Xmaxerrs", "100000",
                "-nowarn", "-encoding", "UTF-8", "-classpath", classpath());
        try (StandardJavaFileManager fm = javac.getStandardFileManager(diags, Locale.ROOT, StandardCharsets.UTF_8)) {
            JavacTask task = (JavacTask) javac.getTask(null, fm, diags, opts, null, sources);
            Iterable<? extends CompilationUnitTree> units = task.parse();
            task.analyze();
            Trees trees = Trees.instance(task);
            Types types = task.getTypes();
            TypeElement httpSession = task.getElements().getTypeElement("jakarta.servlet.http.HttpSession");
            TypeElement springSession = task.getElements().getTypeElement("org.springframework.session.Session");
            TypeElement requestAttributes = task.getElements().getTypeElement("org.springframework.web.context.request.RequestAttributes");
            assertThat(httpSession).as("jakarta.servlet.http.HttpSession sınıf yolunda yok — classpath okunamadı").isNotNull();
            List<Write> writes = new ArrayList<>();
            for (CompilationUnitTree unit : units) {
                new TreePathScanner<Void, Void>() {
                    @Override
                    public Void visitMethodInvocation(MethodInvocationTree node, Void v) {
                        Element el = trees.getElement(getCurrentPath());
                        if (el instanceof ExecutableElement m && m.getEnclosingElement() instanceof TypeElement owner) {
                            String name = m.getSimpleName().toString();
                            int valueArg = -1;
                            if ((name.equals("setAttribute") || name.equals("putValue")) && node.getArguments().size() == 2
                                    && (isSubtype(types, owner, httpSession) || isSubtype(types, owner, springSession))) {
                                valueArg = 1;
                            } else if (name.equals("setSessionAttribute") && owner.getQualifiedName().contentEquals("org.springframework.web.util.WebUtils")) {
                                valueArg = 2;
                            } else if (name.equals("setAttribute") && node.getArguments().size() == 3 && isSubtype(types, owner, requestAttributes)
                                    && !isRequestScope(trees.getElement(new TreePath(getCurrentPath(), node.getArguments().get(2))))) {
                                valueArg = 1;
                            }
                            if (valueArg >= 0) {
                                ExpressionTree arg = node.getArguments().get(valueArg);
                                TypeMirror t = trees.getTypeMirror(new TreePath(getCurrentPath(), arg));
                                long line = unit.getLineMap().getLineNumber(trees.getSourcePositions().getStartPosition(unit, node));
                                String where = unit.getSourceFile().getName() + ":" + line;
                                String attr = valueArg == 2 ? node.getArguments().get(1).toString() : node.getArguments().get(0).toString();
                                writes.add(new Write(where, attr, String.valueOf(t), allowed(types, t, 0)));
                            }
                        }
                        return super.visitMethodInvocation(node, v);
                    }
                }.scan(unit, null);
            }
            List<String> errors = diags.getDiagnostics().stream()
                    .filter(d -> d.getKind() == javax.tools.Diagnostic.Kind.ERROR)
                    .map(d -> (d.getSource() == null ? "?" : d.getSource().getName()) + ":" + d.getLineNumber() + " " + d.getMessage(Locale.ROOT))
                    .toList();
            return new Result(writes, errors);
        }
    }

    private static boolean isSubtype(Types types, TypeElement owner, TypeElement target) {
        return target != null && types.isSubtype(types.erasure(owner.asType()), types.erasure(target.asType()));
    }

    /** Üçüncü argüman RequestAttributes.SCOPE_REQUEST sabiti mi — istek kapsamı oturuma yazmaz; başka her şey (ya da bilinmeyen) yazar. */
    private static boolean isRequestScope(Element scope) {
        return scope instanceof VariableElement v && v.getSimpleName().contentEquals("SCOPE_REQUEST")
                && Integer.valueOf(0).equals(v.getConstantValue());
    }

    /** İzinli tür: ilkel, null, JDK paketindeki (Object hariç) tür ve TÜM tür argümanları; dizi bileşeni de. */
    static boolean allowed(Types types, TypeMirror t, int depth) {
        if (t == null || depth > 8) return false;
        TypeKind k = t.getKind();
        if (k.isPrimitive() || k == TypeKind.NULL) return true;
        if (k == TypeKind.ARRAY) return allowed(types, ((ArrayType) t).getComponentType(), depth + 1);
        if (k == TypeKind.WILDCARD) {
            TypeMirror bound = ((WildcardType) t).getExtendsBound();
            return bound != null && allowed(types, bound, depth + 1);
        }
        if (k != TypeKind.DECLARED) return false;                       // ERROR, TYPEVAR, INTERSECTION …
        DeclaredType dt = (DeclaredType) t;
        TypeElement te = (TypeElement) dt.asElement();
        String qn = te.getQualifiedName().toString();
        if (qn.equals("java.lang.Object")) return false;
        if (JDK_PACKAGES.stream().noneMatch(qn::startsWith)) return false;
        if (!te.getTypeParameters().isEmpty() && dt.getTypeArguments().isEmpty()) return false;   // ham tür
        for (TypeMirror a : dt.getTypeArguments()) if (!allowed(types, a, depth + 1)) return false;
        return true;
    }
}
