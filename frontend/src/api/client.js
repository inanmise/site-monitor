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

/**
 * Bağlantı açılıp hiç yanıt vermezse (OpenShift pod restart / HAProxy bağlantıyı
 * RST'siz düşürürse oluşan yarı-açık socket) `fetch` süresiz asılı kalır. AbortController
 * + timeout ile bunu sınırlandırırız; özellikle açılış akışında (getMe/login) sonsuz
 * "Yükleniyor…" ekranını (pratikte beyaz ekran) önler. timeoutMs <= 0 → timeout uygulanmaz.
 */
const DEFAULT_TIMEOUT_MS = 15000

async function fetchWithTimeout(url, options = {}, timeoutMs = DEFAULT_TIMEOUT_MS) {
  if (!(timeoutMs > 0) || typeof AbortController === 'undefined') {
    return fetch(url, options)
  }
  const controller = new AbortController()
  const timer = setTimeout(() => controller.abort(), timeoutMs)
  try {
    return await fetch(url, { ...options, signal: controller.signal })
  } finally {
    clearTimeout(timer)
  }
}

async function request(path, options = {}) {
  // FormData gönderiminde Content-Type'ı tarayıcı belirler (multipart boundary)
  const isForm = options.body instanceof FormData
  // timeoutMs opsiyoneldir: varsayılan 0 (timeout yok) → uzun-süren çağrılar
  // (scheduler/diagnostics/checkDomain/upload/sql) ETKİLENMEZ. Açılış çağrıları
  // (getMe) açıkça bir timeout geçirir.
  const { timeoutMs = 0, ...opts } = options
  let res
  try {
    res = await fetchWithTimeout(`${BASE}${path}`, {
      credentials: 'include',
      headers: { ...(isForm ? {} : { 'Content-Type': 'application/json' }), ...opts.headers },
      ...opts,
    }, timeoutMs)
  } catch (e) {
    // Timeout (abort) → asılı kalmak yerine yumuşak hata payload'ı döndür; böylece
    // çağıran (örn. App.jsx getMe.then) authChecked'i true yapıp login'i gösterir.
    // Diğer ağ hataları mevcut davranışı korur (reject → çağıranın .catch'i).
    if (e?.name === 'AbortError') {
      return { success: false, status: 0, error: 'İstek zaman aşımına uğradı — sunucu yanıt vermedi' }
    }
    throw e
  }
  if (res.status === 401) {
    // Session expired or invalidated (typically: pod restart wiped in-memory
    // sessions). Don't redirect during the initial auth bootstrap or from the
    // login endpoint itself — App.jsx already handles user=null by rendering
    // the login form. Only force a hard reload when we previously had an
    // authenticated session (flag set by api.login on success).
    if (typeof window !== 'undefined' &&
        sessionStorage.getItem('cm.session.active') === '1') {
      try { sessionStorage.removeItem('cm.session.active') } catch {}
      window.location.assign('/?session=expired')
    }
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
  // Hafif kullanıcı dizini (her authenticated kullanıcı) — UserDirectory bağlamı bununla beslenir.
  users: {
    directory: () => request('/users/directory'),
  },
  me: {
    changePassword: (currentPwd, newPwd) => request('/me/change-password', {
      method: 'POST',
      body: JSON.stringify({ current_password: currentPwd, new_password: newPwd }),
    }),
    getMyAudit: (params = {}) => {
      const qs = new URLSearchParams()
      Object.entries(params).forEach(([k, v]) => {
        if (v !== undefined && v !== null && v !== '') qs.append(k, v)
      })
      const s = qs.toString()
      return request(`/me/audit${s ? `?${s}` : ''}`)
    },
    getPermissions: () => request('/me/permissions'),
  },

  login: async (username, password, rememberMe = false, forceLogin = false) => {
    let r
    try {
      r = await fetchWithTimeout(`${BASE}/login`, {
        method: 'POST',
        credentials: 'include',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          username, password,
          remember_me: String(rememberMe),
          force_login: String(forceLogin),
        }),
      })
    } catch (e) {
      // Login isteği asılır/başarısız olursa buton sonsuz "bekliyor"da kalmasın
      return {
        success: false,
        status: 0,
        error: e?.name === 'AbortError'
          ? 'Giriş zaman aşımına uğradı — sunucu yanıt vermedi'
          : 'Sunucuya ulaşılamadı (ağ hatası)',
      }
    }
    let body
    try { body = await r.json() } catch { body = nonJsonErrorPayload(r.status) }
    // Flag a successful login so the 401 handler in request() knows that any
    // subsequent 401 is a *lost* session (worth a hard reload to /?session=
    // expired), not the initial unauthenticated bootstrap.
    if (r.ok && body?.success && typeof window !== 'undefined') {
      try { sessionStorage.setItem('cm.session.active', '1') } catch {}
    }
    return body
  },

  logout: async () => {
    if (typeof window !== 'undefined') {
      try { sessionStorage.removeItem('cm.session.active') } catch {}
    }
    return request('/logout', { method: 'POST' })
  },

  getMe: () => request('/me', { timeoutMs: DEFAULT_TIMEOUT_MS }),

  // Hafif oturum geçerlilik yoklaması — süpersede ise 401 → request() otomatik /?session=expired.
  sessionPing: () => request('/session/ping', { timeoutMs: DEFAULT_TIMEOUT_MS }),

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

  getMailFailureDomains: () => request('/notifications/failure-domains'),

  getNetworkStatus: () => request('/system/network-status'),

  getNetworkOutageHistory: (limit = 50) => request(`/system/network-outage-history?limit=${limit}`),

  // ── Guide links (Sertifika Değişim Rehberi) ──────────────────────────────

  guideLinks: {
    list:   () => request('/guide-links'),
    create: (data) => request('/guide-links', { method: 'POST', body: JSON.stringify(data) }),
    update: (id, data) => request(`/guide-links/${id}`, { method: 'PUT', body: JSON.stringify(data) }),
    delete: (id) => request(`/guide-links/${id}`, { method: 'DELETE' }),
  },

  // ── Haftalık Raporlar ────────────────────────────────────────────────────

  incidents: {
    list: (params = {}) => {
      const q = new URLSearchParams()
      for (const k of ['q', 'severity', 'category', 'status', 'service', 'channel', 'since', 'until', 'team_id', 'page', 'size']) {
        if (params[k] != null && params[k] !== '') q.set(k, params[k])
      }
      if (params.slaBreached != null) q.set('sla_breached', params.slaBreached)
      if (params.open != null) q.set('open', params.open)
      const qs = q.toString()
      return request(`/incidents${qs ? '?' + qs : ''}`)
    },
    get: (id) => request(`/incidents/${id}`),
    options: (type) => request(`/incidents/options?type=${encodeURIComponent(type)}`),
    addOption: (type, value) =>
      request('/incidents/options', { method: 'POST', body: JSON.stringify({ type, value }) }),
    deleteOption: (type, value) =>
      request(`/incidents/options?type=${encodeURIComponent(type)}&value=${encodeURIComponent(value)}`, { method: 'DELETE' }),
    trends: (since, until) => {
      const q = new URLSearchParams()
      if (since) q.set('since', since)
      if (until) q.set('until', until)
      const qs = q.toString()
      return request(`/incidents/trends${qs ? '?' + qs : ''}`)
    },
    create: (payload) => request('/incidents', { method: 'POST', body: JSON.stringify(payload) }),
    update: (id, payload) => request(`/incidents/${id}`, { method: 'PUT', body: JSON.stringify(payload) }),
    previewNotification: (payload) => request('/incidents/preview-notification', { method: 'POST', body: JSON.stringify(payload) }),
    remove: (id) => request(`/incidents/${id}`, { method: 'DELETE' }),
    transfer: (ids, teamId, teamName) => request('/incidents/transfer', {
      method: 'POST', body: JSON.stringify({ ids, team_id: teamId, team_name: teamName }),
    }),
    uploadImage: (id, file, caption) => {
      const form = new FormData()
      form.append('file', file)
      if (caption) form.append('caption', caption)
      // id yoksa (create modu) taslak yükleme; kaydedince backend markdown'daki görseli olaya bağlar
      const path = id ? `/incidents/${id}/images` : '/incidents/images'
      return request(path, { method: 'POST', body: form })
    },
  },

  weeklyReports: {
    list: (params = {}) => {
      const q = new URLSearchParams()
      if (params.teamId) q.set('teamId', params.teamId)
      if (params.year) q.set('year', params.year)
      const qs = q.toString()
      return request(`/weekly-reports${qs ? '?' + qs : ''}`)
    },
    get: (id) => request(`/weekly-reports/${id}`),
    years: (teamId) => request(`/weekly-reports/years${teamId ? '?teamId=' + teamId : ''}`),
    mails: (id) => request(`/weekly-reports/${id}/mails`),
    create: (payload) => request('/weekly-reports', {
      method: 'POST', body: JSON.stringify(payload),
    }),
    save: (id, contentJson, version) => request(`/weekly-reports/${id}`, {
      method: 'PUT', body: JSON.stringify({ content_json: contentJson, version }),
    }),
    lock: (id, force = false) => request(`/weekly-reports/${id}/lock${force ? '?force=true' : ''}`, { method: 'POST' }),
    unlock: (id) => request(`/weekly-reports/${id}/unlock`, { method: 'POST' }),
    remove: (id) => request(`/weekly-reports/${id}`, { method: 'DELETE' }),
    submit: (id) => request(`/weekly-reports/${id}/submit`, { method: 'POST' }),
    triggerReminder: () => request('/weekly-reports/reminders/trigger', { method: 'POST' }),
    reopen: (id) => request(`/weekly-reports/${id}/reopen`, { method: 'POST' }),
    resend: (id) => request(`/weekly-reports/${id}/resend`, { method: 'POST' }),
    approve: (id) => request(`/weekly-reports/${id}/approve`, { method: 'POST' }),
    reject: (id, note) => request(`/weekly-reports/${id}/reject`, {
      method: 'POST', body: JSON.stringify({ note }),
    }),
    preview: (id) => request(`/weekly-reports/${id}/preview`),
    transfer: (ids, targetTeamId) => request('/weekly-reports/transfer', {
      method: 'POST', body: JSON.stringify({ ids, target_team_id: targetTeamId }),
    }),
    uploadImage: (id, file, caption) => {
      const form = new FormData()
      form.append('file', file)
      if (caption) form.append('caption', caption)
      return request(`/weekly-reports/${id}/images`, { method: 'POST', body: form })
    },
    deleteImage: (imageId) => request(`/weekly-reports/images/${imageId}`, { method: 'DELETE' }),
  },

  // ── Admin ────────────────────────────────────────────────────────────────

  admin: {
    // Inventory
    getInventory: (showDeleted = false) => request(`/admin/inventory?showDeleted=${showDeleted}`),
    addInventory: (item) => request('/admin/inventory', { method: 'POST', body: JSON.stringify(item) }),
    updateInventory: (id, item) => request(`/admin/inventory/${id}`, { method: 'PUT', body: JSON.stringify(item) }),
    deleteInventory: (id) => request(`/admin/inventory/${id}`, { method: 'DELETE' }),
    restoreInventory: (id) => request(`/admin/inventory/${id}/restore`, { method: 'POST' }),
    // Kalıcı sil (geri alınamaz) — yalnız admin. Envanter + o domain'in kontrol geçmişi.
    purgeInventory: (id) => request(`/admin/inventory/${id}/permanent`, { method: 'DELETE' }),
    purgeDeletedInventory: () => request('/admin/inventory/purge-deleted', { method: 'POST' }),
    bulkInventory: (ids, action) => request('/admin/inventory/bulk', {
      method: 'POST', body: JSON.stringify({ ids, action }),
    }),
    runDiagnostics: (domain, port = 443) => request('/admin/diagnostics', {
      method: 'POST', body: JSON.stringify({ domain, port }),
    }),
    runOpensslDiagnostics: (domain, port = 443) => request('/admin/diagnostics/openssl', {
      method: 'POST', body: JSON.stringify({ domain, port }),
    }),
    runNetworkDiagnostics: (domain, port = 443) => request('/admin/diagnostics/network', {
      method: 'POST', body: JSON.stringify({ domain, port }),
    }),
    runHstsDiagnostics: (domain, port = 443) => request('/admin/diagnostics/hsts', {
      method: 'POST', body: JSON.stringify({ domain, port }),
    }),
    diagHistory: (domain) => request(`/admin/diagnostics/history?domain=${encodeURIComponent(domain)}`),
    diagHistoryDetail: (id) => request(`/admin/diagnostics/history/${id}`),
    clientIpDebug: () => request('/admin/client-ip-debug'),

    // Genel Ayarlar — küratörlü runtime config (admin-only Settings page)
    getGeneralSettings: () => request('/admin/general/settings'),
    saveGeneralSettings: (dto) => request('/admin/general/settings', { method: 'PUT', body: JSON.stringify(dto) }),

    // Anahtar çözümleme aracı — verilen CERT_MONITOR_SECRET_KEY ile şifreli alanları çöz
    secretToolsInfo: () => request('/admin/secret-tools/info'),
    decryptSecrets: (key) => request('/admin/secret-tools/decrypt', { method: 'POST', body: JSON.stringify({ key }) }),

    // Veritabanı bilgileri (admin-only Settings sayfası) — bağlı PostgreSQL meta verisi
    getDatabaseInfo: () => request('/admin/database/info'),

    // LDAP / Active Directory settings (admin-only Settings page)
    getLdapSettings: () => request('/admin/ldap/settings'),
    saveLdapSettings: (dto) => request('/admin/ldap/settings', { method: 'PUT', body: JSON.stringify(dto) }),
    testLdap: () => request('/admin/ldap/test', { method: 'POST' }),
    queryLdapUser: (value, attr) => request('/admin/ldap/query-user', {
      method: 'POST', body: JSON.stringify({ value, attr }),
    }),

    // SMTP / outbound mail settings (admin-only Settings page)
    getSmtpSettings: () => request('/admin/smtp/settings'),
    saveSmtpSettings: (dto) => request('/admin/smtp/settings', { method: 'PUT', body: JSON.stringify(dto) }),
    testSmtp: () => request('/admin/smtp/test', { method: 'POST' }),
    sendSmtpTest: (recipient) => request('/admin/smtp/test-email', {
      method: 'POST', body: JSON.stringify({ recipient }),
    }),

    // Haftalık erişilebilirlik e-postası (Ayarlar → Haftalık E-posta sayfası)
    getWeeklyAvailStatus: () => request('/admin/system/weekly-availability/status'),
    getWeeklyAvailPreview: (teamId) =>
      request(`/admin/system/weekly-availability/preview?teamId=${encodeURIComponent(teamId)}`),
    sendWeeklyAvailTest: (teamId, email) => request('/admin/system/weekly-availability/send-test', {
      method: 'POST', body: JSON.stringify({ teamId, email }),
    }),
    setWeeklyAvailEnabled: (enabled) => request('/admin/system/weekly-availability/enabled', {
      method: 'PUT', body: JSON.stringify({ enabled }),
    }),
    getWeeklyAvailHistory: (limit = 50, includeTest = false) =>
      request(`/admin/system/weekly-availability/history?limit=${limit}&includeTest=${includeTest}`),
    getWeeklyAvailHistoryItem: (id) =>
      request(`/admin/system/weekly-availability/history/${encodeURIComponent(id)}`),
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
    getAlerts: (params = {}) => {
      const opts = typeof params === 'object' && params !== null ? params : { onlyOpen: params }
      const qs = new URLSearchParams()
      Object.entries(opts).forEach(([k, v]) => {
        if (v !== undefined && v !== null && v !== '') qs.append(k, v)
      })
      const s = qs.toString()
      return request(`/admin/alerts${s ? `?${s}` : ''}`)
    },
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
    // Filtreli + sayfalı liste (Admin Users ekranı). Boş/null filtreler atlanır.
    searchUsers: (params) => {
      const q = new URLSearchParams(
        Object.fromEntries(
          Object.entries(params || {}).filter(([, v]) => v !== '' && v != null && v !== false),
        ),
      ).toString()
      return request(`/admin/users/search?${q}`)
    },
    createUser: (data) => request('/admin/users', { method: 'POST', body: JSON.stringify(data) }),
    updateUser: (id, data) => request(`/admin/users/${id}`, { method: 'PUT', body: JSON.stringify(data) }),
    deleteUser: (id) => request(`/admin/users/${id}`, { method: 'DELETE' }),
    autoResetPassword: (id, adminPassword) => request(`/admin/users/${id}/auto-reset-password`, {
      method: 'POST', body: JSON.stringify({ admin_password: adminPassword }),
    }),
    unlockUser: (id) => request(`/admin/users/${id}/unlock`, { method: 'POST' }),

    // Cert transfer
    transferCert: (id, teamId) => request(`/admin/inventory/${id}/transfer`, {
      method: 'POST', body: JSON.stringify({ team_id: teamId }),
    }),

    // Certificate notes
    getNotes: (domain) => request(`/admin/notes/${encodeURIComponent(domain)}`),
    addNote: (domain, note, category = 'NOTE') => request(`/admin/notes/${encodeURIComponent(domain)}`, {
      method: 'POST', body: JSON.stringify({ note, category }),
    }),
    updateNote: (domain, noteId, note) => request(`/admin/notes/${encodeURIComponent(domain)}/${noteId}`, {
      method: 'PUT', body: JSON.stringify({ note }),
    }),
    deleteNote: (domain, noteId) => request(`/admin/notes/${encodeURIComponent(domain)}/${noteId}`, {
      method: 'DELETE',
    }),
    getNoteRevisions: (domain, noteId) =>
      request(`/admin/notes/${encodeURIComponent(domain)}/${noteId}/revisions`),
    restoreNote: (domain, noteId) =>
      request(`/admin/notes/${encodeURIComponent(domain)}/${noteId}/restore`, { method: 'POST' }),

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

    // Permission matrix
    getPermissionMatrix: () => request('/admin/permissions'),
    updatePermissionGrant: (body) => request('/admin/permissions', {
      method: 'PUT', body: JSON.stringify(body),
    }),
    resetPermissionsToDefaults: () => request('/admin/permissions/reset-to-defaults', {
      method: 'POST',
    }),

    // SQL Playground
    sqlListTables:  () => request('/admin/sql/tables'),
    sqlListColumns: (table) => request(`/admin/sql/tables/${encodeURIComponent(table)}/columns`),
    sqlTableDetails: (table) => request(`/admin/sql/tables/${encodeURIComponent(table)}/details`),
    sqlRelations:   () => request('/admin/sql/relationships'),
    sqlExecute:     (sql) => request('/admin/sql/execute', { method: 'POST', body: JSON.stringify({ sql }) }),
    sqlHistory:     () => request('/admin/sql/history'),
    sqlSamples:     () => request('/admin/sql/samples'),

    // System health
    getSystemHealth: () => request('/admin/system'),
    forceReleaseLock: () => request('/admin/system/scheduler-lock', { method: 'DELETE' }),
    getMetrics: () => request('/admin/system/metrics'),
    getHttpMetrics: () => request('/admin/system/http-metrics'),
    getHttpMetricsEndpoints: (from, to) =>
      request(`/admin/system/http-metrics/endpoints?from=${encodeURIComponent(from)}&to=${encodeURIComponent(to)}`),
    getHttpMetricsSeries: (from, to, endpoint, granularity) => {
      const q = new URLSearchParams({ from, to })
      if (endpoint) q.set('endpoint', endpoint)
      if (granularity) q.set('granularity', granularity)
      return request(`/admin/system/http-metrics/series?${q.toString()}`)
    },
    getDbStats: () => request('/admin/system/db-stats'),
    getDbAnalytics: (days = 7) => request(`/admin/system/db-analytics?days=${days}`),
    getSmtpLogs: (days) =>
      request(`/admin/system/smtp-logs${days ? `?days=${days}` : ''}`),
    triggerHeartbeat: () => request('/admin/system/heartbeat', { method: 'POST' }),
    getHeartbeatTimeline: (days = 1) => request(`/admin/system/heartbeat-timeline?days=${days}`),
    // Kullanıcı / oturum izleme
    getUserActivity: () => request('/admin/system/user-activity'),
    // Esnek login serisi — aralık seçimi (1g/7g/30g), gün-navigasyonu, zoom
    getLoginSeries: (from, to, granularity = 'day') =>
      request(`/admin/system/user-activity/series?from=${encodeURIComponent(from)}&to=${encodeURIComponent(to)}&granularity=${granularity}`),
    terminateUserSession: (username) => request('/admin/system/terminate-session', {
      method: 'POST', body: JSON.stringify({ username }),
    }),
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
    testPortMonitor:   (data) => request('/monitoring/port/test', { method: 'POST', body: JSON.stringify(data) }),
    getPortHistory:    (id, { days, limit } = {}) => {
      const q = new URLSearchParams(
        Object.fromEntries(Object.entries({ days, limit }).filter(([, v]) => v != null && v !== '')),
      ).toString()
      return request(`/monitoring/port/${id}/history${q ? `?${q}` : ''}`)
    },

    // DNS
    getDnsMonitors:    () => request('/monitoring/dns'),
    createDnsMonitor:  (data) => request('/monitoring/dns', { method: 'POST', body: JSON.stringify(data) }),
    updateDnsMonitor:  (id, data) => request(`/monitoring/dns/${id}`, { method: 'PUT', body: JSON.stringify(data) }),
    deleteDnsMonitor:  (id) => request(`/monitoring/dns/${id}`, { method: 'DELETE' }),
    triggerDnsCheck:   (id) => request(`/monitoring/dns/${id}/check`, { method: 'POST' }),
    getDnsHistory:     (id, days = 7) => request(`/monitoring/dns/${id}/history?days=${days}`),
    getDnsDetails:     (id) => request(`/monitoring/dns/${id}/details`),

    // Keyword
    getKeywordMonitors:   () => request('/monitoring/keyword'),
    createKeywordMonitor: (data) => request('/monitoring/keyword', { method: 'POST', body: JSON.stringify(data) }),
    updateKeywordMonitor: (id, data) => request(`/monitoring/keyword/${id}`, { method: 'PUT', body: JSON.stringify(data) }),
    deleteKeywordMonitor: (id) => request(`/monitoring/keyword/${id}`, { method: 'DELETE' }),
    triggerKeywordCheck:  (id) => request(`/monitoring/keyword/${id}/check`, { method: 'POST' }),
    testKeyword:          (data) => request('/monitoring/keyword/test', { method: 'POST', body: JSON.stringify(data) }),
    getKeywordResponseSeries: (id, { from, to, days } = {}) => {
      const q = new URLSearchParams(
        Object.fromEntries(Object.entries({ from, to, days }).filter(([, v]) => v != null && v !== '')),
      ).toString()
      return request(`/monitoring/keyword/${id}/response-series${q ? `?${q}` : ''}`)
    },
    getKeywordHistory:    (id, { days, limit } = {}) => {
      const q = new URLSearchParams(
        Object.fromEntries(Object.entries({ days, limit }).filter(([, v]) => v != null && v !== '')),
      ).toString()
      return request(`/monitoring/keyword/${id}/history${q ? `?${q}` : ''}`)
    },

    // Ping
    getPingMonitors:   () => request('/monitoring/ping'),
    monitorDefaults: () => request('/monitoring/defaults'),
    createPingMonitor: (data) => request('/monitoring/ping', { method: 'POST', body: JSON.stringify(data) }),
    updatePingMonitor: (id, data) => request(`/monitoring/ping/${id}`, { method: 'PUT', body: JSON.stringify(data) }),
    deletePingMonitor: (id) => request(`/monitoring/ping/${id}`, { method: 'DELETE' }),
    triggerPingCheck:  (id) => request(`/monitoring/ping/${id}/check`, { method: 'POST' }),
    testPingMonitor:   (data) => request('/monitoring/ping/test', { method: 'POST', body: JSON.stringify(data) }),
    getPingHistory:    (id, { days, limit } = {}) => {
      const q = new URLSearchParams(
        Object.fromEntries(Object.entries({ days, limit }).filter(([, v]) => v != null && v !== '')),
      ).toString()
      return request(`/monitoring/ping/${id}/history${q ? `?${q}` : ''}`)
    },
    getPingResponseSeries: (id, { from, to, days } = {}) => {
      const q = new URLSearchParams(
        Object.fromEntries(Object.entries({ from, to, days }).filter(([, v]) => v != null && v !== '')),
      ).toString()
      return request(`/monitoring/ping/${id}/response-series${q ? `?${q}` : ''}`)
    },
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

// formatDate gibi ama saniye dahil (login/oturum zamanları için — saniye hassasiyeti gerekir).
export function formatDateSec(iso) {
  if (!iso) return 'N/A'
  try {
    return new Date(toUtc(iso)).toLocaleString('tr-TR', {
      year: 'numeric', month: '2-digit', day: '2-digit',
      hour: '2-digit', minute: '2-digit', second: '2-digit',
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
