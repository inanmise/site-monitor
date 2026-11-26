package com.sitemonitor.repository;

import com.sitemonitor.model.IncidentRecord;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;
import jakarta.persistence.TypedQuery;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.support.PageableExecutionUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@link IncidentRecordQueries} uygulaması (2026-11-26, Paket 3). Sorgu yalnız VERİLEN koşullarla kurulur; her kullanıcı
 * değeri adlı parametredir. LIKE kalıpları serviste {@code !} ile kaçırılmıştır ({@code ESCAPE '!'}).
 */
public class IncidentRecordQueriesImpl implements IncidentRecordQueries {

    /** Seçenek yönetiminin dokunabileceği alanlar — başka öznitelik adı sorguya giremez. */
    static final Set<String> OPTION_ATTRIBUTES = Set.of("errorCode", "functionCode", "channelCode", "service", "channel");

    @PersistenceContext
    private EntityManager em;

    /** Kurulmuş WHERE: metin + parametreler; {@code none} = kapsam boş, sorgu gerekmez. */
    record Where(String sql, Map<String, Object> params, boolean none) {
        void bind(Query q) {
            params.forEach(q::setParameter);
        }
    }

    /** CSV alanında tam öğe: virgül çevresindeki tek boşluk silinip başa / sona virgül eklenir, iki taraf LOWER. */
    static String tokenCondition(String attribute, String param) {
        return "CONCAT(',', REPLACE(REPLACE(LOWER(i." + attribute + "), ', ', ','), ' ,', ','), ',') LIKE LOWER(:"
                + param + ") ESCAPE '!'";
    }

    static Where where(Filter f, List<String> extra) {
        List<String> c = new ArrayList<>();
        Map<String, Object> p = new LinkedHashMap<>();
        if (f.q() != null) {
            StringBuilder sb = new StringBuilder("(");
            for (int k = 0; k < SEARCH_ATTRIBUTES.size(); k++) {
                if (k > 0) sb.append(" OR ");
                sb.append("LOWER(i.").append(SEARCH_ATTRIBUTES.get(k)).append(") LIKE :q ESCAPE '!'");
            }
            if (f.qid() != null) {
                sb.append(" OR i.id = :qid");
                p.put("qid", f.qid());
            }
            sb.append(")");
            c.add(sb.toString());
            p.put("q", f.q());
        }
        if (f.severity() != null) { c.add("i.severity = :severity"); p.put("severity", f.severity()); }
        if (f.category() != null) { c.add("i.category = :category"); p.put("category", f.category()); }
        if (f.status() != null)   { c.add("i.status = :status"); p.put("status", f.status()); }
        if (f.service() != null)  { c.add("LOWER(i.service) LIKE :service ESCAPE '!'"); p.put("service", f.service()); }
        if (f.channel() != null)  { c.add("LOWER(i.channel) LIKE :channel ESCAPE '!'"); p.put("channel", f.channel()); }
        if (f.since() != null)    { c.add("i.occurredAt >= :since"); p.put("since", f.since()); }
        if (f.until() != null)    { c.add("i.occurredAt <= :until"); p.put("until", f.until()); }
        if (f.teamId() != null)   { c.add("i.teamId = :teamId"); p.put("teamId", f.teamId()); }
        if (f.slaBreached() != null) { c.add("i.slaBreached = :slaBreached"); p.put("slaBreached", f.slaBreached()); }
        if (f.open() != null)     c.add(Boolean.TRUE.equals(f.open()) ? "i.status <> 'RESOLVED'" : "i.status = 'RESOLVED'");
        if (f.serviceToken() != null) { c.add(tokenCondition("service", "svcTok")); p.put("svcTok", f.serviceToken()); }
        if (f.channelToken() != null) { c.add(tokenCondition("channel", "chnTok")); p.put("chnTok", f.channelToken()); }
        if (f.problemTypeToken() != null) { c.add(tokenCondition("problemTypes", "ptTok")); p.put("ptTok", f.problemTypeToken()); }
        if (f.tagToken() != null) { c.add(tokenCondition("tags", "tagTok")); p.put("tagTok", f.tagToken()); }
        if (f.errorCode() != null) { c.add("LOWER(i.errorCode) = LOWER(:errorCode)"); p.put("errorCode", f.errorCode()); }
        if (f.createdBy() != null) { c.add("LOWER(i.createdBy) = LOWER(:createdBy)"); p.put("createdBy", f.createdBy()); }
        if (f.postmortem() != null) {
            if ("NONE".equals(f.postmortem())) {
                c.add("NOT EXISTS (SELECT 1 FROM IncidentPostmortem pm WHERE pm.incidentId = i.id)");
            } else {
                c.add("EXISTS (SELECT 1 FROM IncidentPostmortem pm WHERE pm.incidentId = i.id AND pm.status = :pm)");
                p.put("pm", f.postmortem());
            }
        }
        boolean none = false;
        if (f.scope() != null) {
            if (f.scope().isEmpty()) none = true;
            c.add("(i.teamId IN :scope OR i.createdByTeamId IN :scope)");
            p.put("scope", f.scope().isEmpty() ? List.of(-1L) : f.scope());
        }
        if (extra != null) c.addAll(extra);
        String sql = c.isEmpty() ? "" : " WHERE " + String.join(" AND ", c);
        return new Where(sql, p, none);
    }

    @Override
    public Page<IncidentRecord> findFiltered(Filter f, Pageable pageable, String orderBy) {
        Where w = where(f, null);
        if (w.none()) return Page.empty(pageable);
        TypedQuery<IncidentRecord> q = em.createQuery("SELECT i FROM IncidentRecord i" + w.sql() + orderBy, IncidentRecord.class);
        w.bind(q);
        q.setHint(QUERY_TIMEOUT_HINT, FILTER_QUERY_TIMEOUT_MS);
        if (pageable.isPaged()) {
            q.setFirstResult((int) Math.min(Integer.MAX_VALUE, pageable.getOffset()));
            q.setMaxResults(pageable.getPageSize());
        }
        List<IncidentRecord> rows = q.getResultList();
        return PageableExecutionUtils.getPage(rows, pageable, () -> count(w));
    }

    @Override
    public long countFiltered(Filter f) {
        Where w = where(f, null);
        return w.none() ? 0L : count(w);
    }

    private long count(Where w) {
        TypedQuery<Long> q = em.createQuery("SELECT COUNT(i) FROM IncidentRecord i" + w.sql(), Long.class);
        w.bind(q);
        q.setHint(QUERY_TIMEOUT_HINT, FILTER_QUERY_TIMEOUT_MS);
        Long n = q.getSingleResult();
        return n == null ? 0L : n;
    }

    @Override
    public List<Object[]> selectFiltered(String select, Filter f, List<String> extra, String tail, int limit) {
        Where w = where(f, extra);
        if (w.none()) return List.of();
        Query q = em.createQuery("SELECT " + select + " FROM IncidentRecord i" + w.sql() + (tail == null ? "" : tail));
        w.bind(q);
        q.setHint(QUERY_TIMEOUT_HINT, FILTER_QUERY_TIMEOUT_MS);
        if (limit > 0) q.setMaxResults(limit);
        List<?> raw = q.getResultList();
        List<Object[]> out = new ArrayList<>(raw.size());
        for (Object o : raw) out.add(o instanceof Object[] a ? a : new Object[]{o});
        return out;
    }

    @Override
    public List<Object[]> teamFieldValues(String attribute, Long teamId, int limit) {
        if (!OPTION_ATTRIBUTES.contains(attribute)) throw new IllegalArgumentException("Desteklenmeyen alan: " + attribute);
        if (teamId == null || limit <= 0) return List.of();
        Query q = em.createQuery("SELECT i.id, i." + attribute + " FROM IncidentRecord i"
                + " WHERE (i.teamId = :team OR (i.teamId IS NULL AND i.createdByTeamId = :team))"
                + " AND i." + attribute + " IS NOT NULL AND i." + attribute + " <> ''"
                + " ORDER BY i.id");
        q.setParameter("team", teamId);
        q.setMaxResults(limit);
        q.setHint(QUERY_TIMEOUT_HINT, OPTION_QUERY_TIMEOUT_MS);
        List<?> raw = q.getResultList();
        List<Object[]> out = new ArrayList<>(raw.size());
        for (Object o : raw) out.add((Object[]) o);
        return out;
    }

    @Override
    public List<Long> lockRows(java.util.Collection<Long> ids, long lockTimeoutMs) {
        if (ids == null || ids.isEmpty()) return List.of();
        long wait = Math.max(1L, lockTimeoutMs);
        // PostgreSQL: kilit beklemesi işlem içinde sınırlanır (SET LOCAL işlem bitince kendiliğinden kalkar). Başka yerde
        // (H2 — testler) yalnız sorgu zaman aşımı.
        if (isPostgres()) em.createNativeQuery("SET LOCAL lock_timeout = '" + wait + "ms'").executeUpdate();
        Query q = em.createNativeQuery("SELECT id FROM incident_records WHERE id IN (:ids) ORDER BY id FOR UPDATE");
        q.setParameter("ids", ids);
        q.setHint(QUERY_TIMEOUT_HINT, (int) Math.min(Integer.MAX_VALUE, wait + 5_000L));
        List<?> raw = q.getResultList();
        List<Long> out = new ArrayList<>(raw.size());
        for (Object o : raw) out.add(((Number) o).longValue());
        return out;
    }

    /** JPA sorgu zaman aşımı ipucu (ms; Hibernate JDBC {@code setQueryTimeout}'a çevirir). */
    static final String QUERY_TIMEOUT_HINT = "jakarta.persistence.query.timeout";

    /** Veritabanı PostgreSQL mi — bilinen yanıt saklanır; okunamazsa "hayır" (yalnız sorgu zaman aşımı uygulanır). */
    private volatile Boolean postgres;

    private boolean isPostgres() {
        Boolean p = postgres;
        if (p != null) return p;
        try {
            String product = em.unwrap(org.hibernate.Session.class)
                    .doReturningWork(c -> c.getMetaData().getDatabaseProductName());
            p = product != null && product.toLowerCase(java.util.Locale.ROOT).contains("postgres");
            postgres = p;
            return p;
        } catch (RuntimeException e) {
            return false;
        }
    }
}
