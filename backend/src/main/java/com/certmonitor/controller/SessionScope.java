package com.certmonitor.controller;

import jakarta.servlet.http.HttpSession;

import java.util.List;

/**
 * Team-scoping helpers backed by session attributes set in
 * {@code AuthController.populateSession} (Faz 3b).
 *
 * <ul>
 *   <li>{@code viewTeamIds} — read scope. {@code null} = unrestricted (global admin / AUDIT);
 *       a list (possibly empty) = visible teams only.</li>
 *   <li>{@code manageTeamIds} — write scope. {@code null} = unrestricted (global admin);
 *       a list (possibly empty) = manageable teams only (empty = none, e.g. müdür/USER).</li>
 * </ul>
 *
 * A "müdür" is an AD-authenticated ADMIN: it has a non-null {@code viewTeamIds}, so it is
 * NOT a global admin even though its systemRole is ADMIN.
 */
public final class SessionScope {

    private SessionScope() {}

    @SuppressWarnings("unchecked")
    public static List<Long> viewTeamIds(HttpSession session) {
        Object o = session != null ? session.getAttribute("viewTeamIds") : null;
        return (o instanceof List) ? (List<Long>) o : null;
    }

    @SuppressWarnings("unchecked")
    public static List<Long> manageTeamIds(HttpSession session) {
        Object o = session != null ? session.getAttribute("manageTeamIds") : null;
        return (o instanceof List) ? (List<Long>) o : null;
    }

    /** True only for the unrestricted (local/bootstrap) ADMIN — never for a scoped müdür. */
    public static boolean isGlobalAdmin(HttpSession session) {
        return session != null
                && "ADMIN".equals(session.getAttribute("systemRole"))
                && session.getAttribute("viewTeamIds") == null;
    }

    /** Global read (sees all teams): global admin or AUDIT (both have null view scope).
     *  Role is checked too, so a non-privileged session without a view scope is never
     *  treated as a global viewer (defence in depth — populateSession always sets it). */
    public static boolean isGlobalViewer(HttpSession session) {
        if (session == null) return false;
        Object role = session.getAttribute("systemRole");
        return ("ADMIN".equals(role) || "AUDIT".equals(role))
                && session.getAttribute("viewTeamIds") == null;
    }

    /** Whether the caller may manage (write) a resource owned by {@code teamId}.
     *  Only the global admin manages with a null scope; any other role must have the
     *  team explicitly in its manage scope. */
    public static boolean canManage(HttpSession session, Long teamId) {
        if (isGlobalAdmin(session)) return true;          // global manage
        List<Long> m = manageTeamIds(session);
        return m != null && teamId != null && m.contains(teamId);
    }

    /** Whether the caller may VIEW a resource owned by {@code teamId}. Global viewer (admin/AUDIT)
     *  sees all; otherwise the team must be in the read scope. */
    public static boolean canView(HttpSession session, Long teamId) {
        if (isGlobalViewer(session)) return true;
        List<Long> v = viewTeamIds(session);
        return v != null && teamId != null && v.contains(teamId);
    }
}
