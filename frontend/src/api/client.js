const BASE = '/api'

async function request(path, options = {}) {
  const res = await fetch(`${BASE}${path}`, {
    credentials: 'include',
    headers: { 'Content-Type': 'application/json', ...options.headers },
    ...options,
  })
  if (res.status === 401) {
    return null
  }
  return res.json()
}

export const api = {
  login: (username, password, rememberMe = false) =>
    fetch(`${BASE}/login`, {
      method: 'POST',
      credentials: 'include',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ username, password, remember_me: String(rememberMe) }),
    }).then((r) => r.json()),

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

  getStats: () => request('/stats'),

  runScheduler: () => request('/scheduler/run', { method: 'POST' }),

  getSchedulerStatus: () => request('/scheduler/status'),

  getRenewalAdvice: () => request('/renewal-advice'),

  getActivityLog: (hours = 24) => request(`/activity?hours=${hours}`),

  // ── Admin ────────────────────────────────────────────────────────────────

  admin: {
    // Inventory
    getInventory: () => request('/admin/inventory'),
    addInventory: (item) => request('/admin/inventory', { method: 'POST', body: JSON.stringify(item) }),
    updateInventory: (id, item) => request(`/admin/inventory/${id}`, { method: 'PUT', body: JSON.stringify(item) }),
    deleteInventory: (id) => request(`/admin/inventory/${id}`, { method: 'DELETE' }),

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
    acknowledgeAlert: (id, by) => request(`/admin/alerts/${id}/acknowledge`, {
      method: 'POST', body: JSON.stringify({ acknowledged_by: by }),
    }),
    resolveAlert: (id, resolvedBy) => request(`/admin/alerts/${id}/resolve`, {
      method: 'POST',
      body: JSON.stringify({ resolved_by: resolvedBy ?? 'admin' }),
    }),
    reNotifyAlert: (id) => request(`/admin/alerts/${id}/re-notify`, { method: 'POST' }),
    getAlertNotifications: (id) => request(`/admin/alerts/${id}/notifications`),
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
