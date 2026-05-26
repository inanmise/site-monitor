const BASE = import.meta.env.VITE_API_BASE ?? '/api'

function nonJsonErrorPayload(status) {
  return {
    success: false,
    status,
    error:
      status === 504 ? 'Gateway timeout — sunucu yanıt vermedi'
    : status === 502 ? 'Bad gateway — sunucu erişilemiyor'
    : status === 503 ? 'Servis kullanılamıyor'
    : status >= 500  ? `Sunucu hatası (HTTP ${status})`
    : status >= 400  ? `Hata (HTTP ${status})`
    :                  'Geçersiz yanıt',
  }
}

async function request(path, options = {}) {
  const res = await fetch(`${BASE}${path}`, {
    credentials: 'include',
    headers: { 'Content-Type': 'application/json', ...options.headers },
    ...options,
  })
  if (res.status === 401) {
    return null
  }
  try {
    return await res.json()
  } catch {
    // Non-JSON response (HTML error page from proxy / empty body) — graceful fallback
    return nonJsonErrorPayload(res.status)
  }
}

export const api = {
  login: async (username, password, rememberMe = false) => {
    const r = await fetch(`${BASE}/login`, {
      method: 'POST',
      credentials: 'include',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ username, password, remember_me: String(rememberMe) }),
    })
    try { return await r.json() } catch { return nonJsonErrorPayload(r.status) }
  },

  logout: () => request('/logout', { method: 'POST' }),

  getMe: () => request('/me'),

  getCertificates: () => request('/certificates'),

  getCertificatesPaginated: (params) => {
    const q = new URLSearchParams(params).toString()
    return request(`/certificates/list?${q}`)
  },

  getWarnings: () => request('/warnings'),

  getHistory: (domain) => request(`/history/${encodeURIComponent(domain)}`),

  getDomainAlerts: (domain) => request(`/history/${encodeURIComponent(domain)}/alerts`),

  checkDomain: (domain) => request(`/check/${encodeURIComponent(domain)}`),
  checkDomainPreview: (domain) => request(`/check-preview/${encodeURIComponent(domain)}`),

  getStats: () => request('/stats'),

  getTeamStats: () => request('/stats/teams'),

  runScheduler: () => request('/scheduler/run', { method: 'POST' }),

  getSchedulerStatus: () => request('/scheduler/status'),

  getRenewalAdvice: () => request('/renewal-advice'),

  getActivityLog: (hours = 24) => request(`/activity?hours=${hours}`),

  getSilentAlertDomains: () => request('/alerts/silent-domains'),

  // ── Admin ────────────────────────────────────────────────────────────────

  admin: {
    // Inventory
    getInventory: (showDeleted = false) => request(`/admin/inventory?showDeleted=${showDeleted}`),
    addInventory: (item) => request('/admin/inventory', { method: 'POST', body: JSON.stringify(item) }),
    updateInventory: (id, item) => request(`/admin/inventory/${id}`, { method: 'PUT', body: JSON.stringify(item) }),
    deleteInventory: (id) => request(`/admin/inventory/${id}`, { method: 'DELETE' }),
    restoreInventory: (id) => request(`/admin/inventory/${id}/restore`, { method: 'POST' }),
    transferCertSy: (id, teamId) => request(`/admin/inventory/${id}/transfer`, {
      method: 'POST', body: JSON.stringify({ team_id: teamId }),
    }),
    transferCertUg: (id, ugTeamId) => request(`/admin/inventory/${id}/transfer-ug`, {
      method: 'POST', body: JSON.stringify({ ug_team_id: ugTeamId }),
    }),

    // Thresholds
    getThresholds: () => request('/admin/thresholds'),
    updateThreshold: (id, data) => request(`/admin/thresholds/${id}`, { method: 'PUT', body: JSON.stringify(data) }),

    // Contacts
    getContacts: () => request('/admin/contacts/all'),
    addContact: (contact) => request('/admin/contacts', { method: 'POST', body: JSON.stringify(contact) }),
    updateContact: (id, contact) => request(`/admin/contacts/${id}`, { method: 'PUT', body: JSON.stringify(contact) }),
    deleteContact: (id) => request(`/admin/contacts/${id}`, { method: 'DELETE' }),

    // Alert Events
    getAlerts: (onlyOpen = false) => request(`/admin/alerts?onlyOpen=${onlyOpen}`),
    acknowledgeAlert: (id, acknowledgedBy) => request(`/admin/alerts/${id}/acknowledge`, {
      method: 'POST',
      body: JSON.stringify({ acknowledged_by: acknowledgedBy }),
    }),
    resolveAlert:     (id) => request(`/admin/alerts/${id}/resolve`,     { method: 'POST' }),
    reNotifyAlert: (id) => request(`/admin/alerts/${id}/re-notify`, { method: 'POST' }),
    getAlertNotifications: (id) => request(`/admin/alerts/${id}/notifications`),

    // Teams
    getTeams: () => request('/admin/teams'),
    createTeam: (data) => request('/admin/teams', { method: 'POST', body: JSON.stringify(data) }),
    updateTeam: (id, data) => request(`/admin/teams/${id}`, { method: 'PUT', body: JSON.stringify(data) }),
    deleteTeam: (id) => request(`/admin/teams/${id}`, { method: 'DELETE' }),
    getTeamUsers: (id) => request(`/admin/teams/${id}/users`),

    // Users
    getUsers: () => request('/admin/users'),
    createUser: (data) => request('/admin/users', { method: 'POST', body: JSON.stringify(data) }),
    updateUser: (id, data) => request(`/admin/users/${id}`, { method: 'PUT', body: JSON.stringify(data) }),
    deleteUser: (id) => request(`/admin/users/${id}`, { method: 'DELETE' }),
    resetPassword: (id, password) => request(`/admin/users/${id}/reset-password`, {
      method: 'POST', body: JSON.stringify({ password }),
    }),
    unlockUser: (id) => request(`/admin/users/${id}/unlock`, { method: 'POST' }),

    // Cert transfer
    transferCert: (id, teamId) => request(`/admin/inventory/${id}/transfer`, {
      method: 'POST', body: JSON.stringify({ team_id: teamId }),
    }),

    // Certificate notes
    getNotes: (domain) => request(`/admin/notes/${encodeURIComponent(domain)}`),
    addNote: (domain, note) => request(`/admin/notes/${encodeURIComponent(domain)}`, {
      method: 'POST', body: JSON.stringify({ note }),
    }),
    deleteNote: (domain, noteId) => request(`/admin/notes/${encodeURIComponent(domain)}/${noteId}`, {
      method: 'DELETE',
    }),

    // Weak algorithm report
    getWeakAlgorithms: () => request('/admin/audit/weak-algorithms'),

    // Audit log
    getAuditLogs: (params) => {
      const q = new URLSearchParams(
        Object.fromEntries(
          Object.entries(params).filter(([, v]) => v !== '' && v != null && v !== false)
        )
      ).toString()
      return request(`/admin/audit?${q}`)
    },
    getAuditStats: () => request('/admin/audit/stats'),

    // System health
    getSystemHealth: () => request('/admin/system'),
    forceReleaseLock: () => request('/admin/system/scheduler-lock', { method: 'DELETE' }),
    getMetrics: () => request('/admin/system/metrics'),
    getHttpMetrics: () => request('/admin/system/http-metrics'),
    getDbStats: () => request('/admin/system/db-stats'),
    getSmtpLogs: (days) =>
      request(`/admin/system/smtp-logs${days ? `?days=${days}` : ''}`),
    triggerHeartbeat: () => request('/admin/system/heartbeat', { method: 'POST' }),
  },

  // ── Monitoring ───────────────────────────────────────────────────────────

  monitoring: {
    // Uptime
    getUptimeOverview:    () => request('/monitoring/uptime/overview'),
    getUptimeHistory:     (domain, hours = 24) => request(`/monitoring/uptime/${encodeURIComponent(domain)}/history?hours=${hours}`),
    getUptimeHttpHistory: (domain, port, from, to, limit = 500) =>
      request(`/monitoring/uptime/${encodeURIComponent(domain)}/http-history?port=${port}&from=${from}&to=${to}&limit=${limit}`),
    getUptimeSslHistory:  (domain, from, to, limit = 500) =>
      request(`/monitoring/uptime/${encodeURIComponent(domain)}/ssl-history?from=${from}&to=${to}&limit=${limit}`),

    // Port
    getPortMonitors:   () => request('/monitoring/port'),
    createPortMonitor: (data) => request('/monitoring/port', { method: 'POST', body: JSON.stringify(data) }),
    updatePortMonitor: (id, data) => request(`/monitoring/port/${id}`, { method: 'PUT', body: JSON.stringify(data) }),
    deletePortMonitor: (id) => request(`/monitoring/port/${id}`, { method: 'DELETE' }),
    triggerPortCheck:  (id) => request(`/monitoring/port/${id}/check`, { method: 'POST' }),
    getPortHistory:    (id, limit = 100) => request(`/monitoring/port/${id}/history?limit=${limit}`),

    // DNS
    getDnsMonitors:    () => request('/monitoring/dns'),
    createDnsMonitor:  (data) => request('/monitoring/dns', { method: 'POST', body: JSON.stringify(data) }),
    updateDnsMonitor:  (id, data) => request(`/monitoring/dns/${id}`, { method: 'PUT', body: JSON.stringify(data) }),
    deleteDnsMonitor:  (id) => request(`/monitoring/dns/${id}`, { method: 'DELETE' }),
    triggerDnsCheck:   (id) => request(`/monitoring/dns/${id}/check`, { method: 'POST' }),
    getDnsHistory:     (id, limit = 100) => request(`/monitoring/dns/${id}/history?limit=${limit}`),
  },
}

function toUtc(iso) {
  return iso.endsWith('Z') || iso.includes('+') ? iso : iso + 'Z'
}

export function formatDate(iso) {
  if (!iso) return 'N/A'
  try {
    return new Date(toUtc(iso)).toLocaleString('tr-TR', {
      year: 'numeric', month: '2-digit', day: '2-digit',
      hour: '2-digit', minute: '2-digit',
    })
  } catch {
    return iso
  }
}

export function formatTime(iso) {
  if (!iso) return '—'
  try {
    return new Date(toUtc(iso)).toLocaleTimeString('tr-TR', {
      hour: '2-digit', minute: '2-digit', second: '2-digit',
    })
  } catch {
    return iso.substring(11, 19)
  }
}

export function formatDateOnly(iso) {
  if (!iso) return '—'
  try {
    return new Date(toUtc(iso)).toLocaleDateString('tr-TR', {
      year: 'numeric', month: '2-digit', day: '2-digit',
    })
  } catch {
    return iso.substring(0, 10)
  }
}
