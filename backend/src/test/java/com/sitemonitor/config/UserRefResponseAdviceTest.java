package com.sitemonitor.config;

import com.sitemonitor.model.AppUser;
import com.sitemonitor.service.userref.UserPublicIds;
import com.sitemonitor.service.userref.UserRef;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.http.converter.StringHttpMessageConverter;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.json.JsonMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Opak kullanıcı kimliğinin tek çıkış kapısı (2026-10-08): global admin'in yanıtı DOKUNULMADAN (sayısal), AUDIT /
 * kapsamlı müdür / kullanıcı / oturumsuz istekte opak kimlik. JSON dışı yanıtlar (düz metin) etkilenmez; servis
 * yoksa (dilim testi) kapı no-op.
 */
class UserRefResponseAdviceTest {

    private static final JsonMapper MAPPER = JsonMapper.builder()
            .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE).build();
    private static final String P5 = "00000000-0000-4000-8000-000000000005";
    private static final String P7 = "00000000-0000-4000-8000-000000000007";

    @RestController
    static class Probe {
        @GetMapping("/api/probe")
        Map<String, Object> probe() {
            AppUser u = new AppUser();
            u.setId(5L);
            u.setUsername("ali");
            u.setManagerId(7L);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("data", List.of(u));
            body.put("user_id", 5L);
            body.put("team_id", 3L);
            body.put("dir", List.of(Map.of("id", UserRef.of(7L))));
            return body;
        }

        @GetMapping("/api/plain")
        Map<String, Object> plain() {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("id", UserRef.of(5L));
            body.put("team_id", 3L);
            body.put("ratio", new java.math.BigDecimal("12.50"));
            return body;
        }

        @GetMapping(value = "/api/text", produces = "text/plain")
        String text() {
            return "{\"user_id\":5}";
        }
    }

    /** Veritabanısız sahte servis — yalnız 5 ve 7 bilinir. */
    static final class StubIds extends UserPublicIds {
        StubIds() { super(null); }
        @Override public String publicIdOf(Long id) { return id == null ? null : id == 5L ? P5 : id == 7L ? P7 : null; }
    }

    private MockMvc mvc;

    private MockMvc build(boolean withService) {
        StaticListableBeanFactory bf = new StaticListableBeanFactory();
        bf.addBean("json", MAPPER);
        if (withService) bf.addBean("ids", new StubIds());
        UserRefResponseAdvice advice = new UserRefResponseAdvice(
                bf.getBeanProvider(ObjectMapper.class), bf.getBeanProvider(UserPublicIds.class));
        return MockMvcBuilders.standaloneSetup(new Probe())
                .setControllerAdvice(advice)
                .setMessageConverters(new StringHttpMessageConverter(), new JacksonJsonHttpMessageConverter(MAPPER))
                .build();
    }

    @BeforeEach
    void setUp() {
        mvc = build(true);
    }

    private static MockHttpSession session(String role, List<Long> viewTeamIds) {
        MockHttpSession s = new MockHttpSession();
        s.setAttribute("systemRole", role);
        if (viewTeamIds != null) s.setAttribute("viewTeamIds", viewTeamIds);
        return s;
    }

    @Test
    @DisplayName("global admin: yanıt bugünkü gibi sayısal")
    void globalAdminSeesNumbers() throws Exception {
        mvc.perform(get("/api/probe").session(session("ADMIN", null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value(5))
                .andExpect(jsonPath("$.data[0].manager_id").value(7))
                .andExpect(jsonPath("$.user_id").value(5))
                .andExpect(jsonPath("$.dir[0].id").value(7))
                .andExpect(jsonPath("$.team_id").value(3));
    }

    @Test
    @DisplayName("AUDIT, kapsamlı müdür, kullanıcı ve oturumsuz istek: kullanıcı kimlikleri opak; takım kimliği aynen")
    void othersSeeOpaque() throws Exception {
        for (MockHttpSession s : List.of(session("AUDIT", null), session("ADMIN", List.of(1L)), session("USER", List.of(1L)))) {
            mvc.perform(get("/api/probe").session(s))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data[0].id").value(P5))
                    .andExpect(jsonPath("$.data[0].manager_id").value(P7))
                    .andExpect(jsonPath("$.user_id").value(P5))
                    .andExpect(jsonPath("$.dir[0].id").value(P7))
                    .andExpect(jsonPath("$.team_id").value(3));
        }
        mvc.perform(get("/api/probe"))
                .andExpect(jsonPath("$.user_id").value(P5));
    }

    @Test
    @DisplayName("hızlı yol (çevrilecek alan adı yok): gerçek dönüştürücü ham JSON'u yazar; kullanıcı kimliği yine opak, sayılar aynen")
    void fastPathThroughRealConverter() throws Exception {
        mvc.perform(get("/api/plain").session(session("USER", List.of(1L))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(P5))
                .andExpect(jsonPath("$.team_id").value(3))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("\"ratio\":12.50")));
        mvc.perform(get("/api/plain").session(session("ADMIN", null)))
                .andExpect(jsonPath("$.id").value(5));
    }

    @Test
    @DisplayName("JSON dışı yanıt (düz metin) kapıdan geçmez")
    void nonJsonUntouched() throws Exception {
        mvc.perform(get("/api/text").session(session("USER", List.of(1L))))
                .andExpect(content().string("{\"user_id\":5}"));
    }

    @Test
    @DisplayName("servis yoksa (dilim testi) kapı no-op — sayısal")
    void noServiceNoOp() throws Exception {
        build(false).perform(get("/api/probe").session(session("USER", List.of(1L))))
                .andExpect(jsonPath("$.user_id").value(5));
    }
}
