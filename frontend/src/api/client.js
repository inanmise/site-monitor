// Tarih yereli i18n'den CANLI okunur: bu dosyadaki formatlayicilar duz fonksiyon,
// hook degil — sabit 'tr-TR' yazdiklari icin Ingilizce arayuzde ayni ekranda iki
// farkli tarih bicimi goruluyordu (bkz. i18n/dateLocale.js).
import { dateLocale, LANG_STORAGE_KEY } from '../i18n/dateLocale.js'
import { toUtc, localDayKey } from '../utils/localDay.js'

/** Arayüz dili (tr|en) — i18n/index.jsx'teki storedLang ile aynı anahtar; i18n modülünü
 *  import etmemek için (React bağımlılığı, dairesel import riski) burada yalın okunur. */
function uiLang() {
  try { return localStorage.getItem(LANG_STORAGE_KEY) || 'en' } catch { return 'en' }
}

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

// Son başarısız API çağrıları (Sorun Bildir otomatik bağlamı) — yalnız yol + durum kodu + zaman.
// Gövde/başlık ASLA saklanmaz; query string de atılır (gizlilik). Halka tampon: en yeni 5 kayıt.
const FAILED_RING_MAX = 5
const failedRequests = []
function recordFailure(path, status) {
  try {
    failedRequests.push({ path: String(path).split('?')[0], status, at: new Date().toISOString().slice(0, 19) })
    if (failedRequests.length > FAILED_RING_MAX) failedRequests.shift()
  } catch { /* yoksay */ }
}
export function getRecentFailures() { return [...failedRequests] }

/** Kontrol Geçmişi v2 yol eşlemesi — uptime türleri domain-anahtarlıdır. */
function historyPath(kind, id) {
  if (kind === 'uptime-http') return `/monitoring/uptime/${encodeURIComponent(id)}/http-history`
  if (kind === 'uptime-ssl')  return `/monitoring/uptime/${encodeURIComponent(id)}/ssl-history`
  return `/monitoring/${kind}/${id}/history`
}

/** Boş/null paramları atarak query string üretir (mevcut get*ResponseSeries deseniyle aynı). */
function historyQuery(params) {
  return new URLSearchParams(
    Object.fromEntries(Object.entries(params).filter(([, v]) => v != null && v !== '')),
  ).toString()
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
      // X-Lang: sunucu tost/hata metinlerini arayüz dilinde döner (backend Msg.t). Eskiden her
      // ayar sayfası İngilizce arayüzde Türkçe "Ayarlar kaydedildi…" basıyordu (QA ISSUE-001).
      headers: { ...(isForm ? {} : { 'Content-Type': 'application/json' }), 'X-Lang': uiLang(), ...opts.headers },
      ...opts,
    }, timeoutMs)
  } catch (e) {
    // Timeout (abort) → asılı kalmak yerine yumuşak hata payload'ı döndür; böylece
    // çağıran (örn. App.jsx getMe.then) authChecked'i true yapıp login'i gösterir.
    // Diğer ağ hataları mevcut davranışı korur (reject → çağıranın .catch'i).
    if (e?.name === 'AbortError') {
      recordFailure(path, 0)
      return { success: false, status: 0, error: 'İstek zaman aşımına uğradı — sunucu yanıt vermedi' }
    }
    recordFailure(path, 0)
    throw e
  }
  if (!res.ok) recordFailure(path, res.status)
  if (res.status === 401) {
    // Session expired or invalidated (typically: pod restart wiped in-memory
    // sessions). Don't redirect during the initial auth bootstrap or from the
    // login endpoint itself — App.jsx already handles user=null by rendering
    // the login form. Only force a hard reload when we previously had an
    // authenticated session (flag set by api.login on success).
    if (typeof window !== 'undefined' &&
        sessionStorage.getItem('sm.session.active') === '1') {
      try { sessionStorage.removeItem('sm.session.active') } catch {}
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
  /** Komut paleti (2026-09-12, #1): alan / izleme / takım — takım kapsamlı. */
  search: (q) => request(`/search?q=${encodeURIComponent(q)}`),
  // Hafif kullanıcı dizini (her authenticated kullanıcı) — UserDirectory bağlamı bununla beslenir.
  /** Kurum-geneli takım rehberi (oturum açmış herkes) — ad→id ve üye listesi (beyaz-listeli). */
  teams: {
    directory: () => request('/teams/directory'),
    members: (id) => request(`/teams/${id}/members`),
  },
  users: {
    directory: () => request('/users/directory'),
  },
  /** Sürüm & yayın yüzeyi — kimlikli HERKES (K9). Nav çipi popover'ı + Yardım → Yenilikler. */
  system: {
    getVersion: () => request('/system/version'),
    getReleases: (params = {}) => {
      const qs = new URLSearchParams()
      for (const [k, v] of Object.entries(params)) if (v != null && v !== '' && v !== false) qs.set(k, String(v))
      const s = qs.toString()
      return request(`/system/releases${s ? `?${s}` : ''}`)
    },
    getReleaseNotes: (since) => request(`/system/releases/notes${since ? `?since=${encodeURIComponent(since)}` : ''}`),
  },
  /** Sürüm & yayın yüzeyi — kimlikli HERKES (K9). Nav çipi popover'ı + Yardım → Yenilikler. */
  system: {
    getVersion: () => request('/system/version'),
    getReleases: (params = {}) => {
      const qs = new URLSearchParams()
      for (const [k, v] of Object.entries(params)) if (v != null && v !== '' && v !== false) qs.set(k, String(v))
      const s = qs.toString()
      return request(`/system/releases${s ? `?${s}` : ''}`)
    },
    getReleaseNotes: (since) => request(`/system/releases/notes${since ? `?since=${encodeURIComponent(since)}` : ''}`),
  },
  me: {
    /** "Sizin için — bugün" paneli (2026-09-12, #3) */
    today: () => request('/me/today'),
    /** Bildirim kutusu (2026-09-12, #2) */
    inbox: () => request('/me/inbox'),
    // 2026-09-10: yol '/auth/me/push-opt-out' idi — AuthController '/api' tabanlı, uç '/api/me/push-opt-out'
    // → 404; sunucu onayı gelmediği için "Webhook push istemiyorum" kutusu HİÇ işaretlenmiyordu.
    setPushOptOut: (optOut) => request('/me/push-opt-out', { method: 'POST', body: JSON.stringify({ opt_out: optOut }) }),
    changePassword: (currentPwd, newPwd) => request('/me/change-password', {
      method: 'POST',
      body: JSON.stringify({ current_password: currentPwd, new_password: newPwd }),
    }),
    // ── Cihaz Gecmisi / Oturum Guvenligi (self-scope) ────────────────────────
    // Hicbirinde KULLANICI parametresi YOKTUR — kimlik sunucuda oturumdan okunur.
    getMyDevices: () => request('/me/devices'),
    getMyDeviceLogins: (params = {}) => {
      const qs = new URLSearchParams()
      Object.entries(params).forEach(([k, v]) => {
        if (v !== undefined && v !== null && v !== '') qs.append(k, v)
      })
      const s = qs.toString()
      return request(`/me/devices/logins${s ? `?${s}` : ''}`)
    },
    revokeRememberedDevice: (id) => request(`/me/devices/remembered/${id}`, { method: 'DELETE' }),
    logoutOtherDevices: () => request('/me/devices/logout-others', { method: 'POST' }),
    reportSuspiciousLogin: (auditId) => request('/me/devices/report-login', {
      method: 'POST',
      body: JSON.stringify({ auditId }),
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
      try { sessionStorage.setItem('sm.session.active', '1') } catch {}
    }
    return body
  },

  logout: async () => {
    if (typeof window !== 'undefined') {
      try { sessionStorage.removeItem('sm.session.active') } catch {}
    }
    return request('/logout', { method: 'POST' })
  },

  getMe: () => request('/me', { timeoutMs: DEFAULT_TIMEOUT_MS }),

  // Branding (beyaz etiket) — PUBLIC, auth gerekmez (login sayfası açılışta çeker).
  // fresh=true: kaydet sonrası çağrı — 60 sn'lik Cache-Control'ü query ile bust'la (anında yansıma).
  getBranding: (fresh = false) => request('/branding' + (fresh ? `?_=${Date.now()}` : '')),

  // Login hero istatistikleri — PUBLIC (izlenen hedef adedi + 7g erişilebilirlik %).
  getPublicStats: () => request('/public-stats'),

  // Login "sorun bildir" — PUBLIC; sistem yöneticisi e-postasına iletilir (IP rate-limit'li).
  // Zengin sonuç döner: {success, status, reference?, error?, networkError?} — modal, sebebi +
  // "Detay gör" ile teknik hatayı (HTTP kodu/sunucu mesajı/ağ istisnası) gösterebilsin.
  sendLoginHelp: async (dto) => {
    try {
      const res = await fetch(`${BASE}/login-help`, {
        method: 'POST', credentials: 'include',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(dto),
      })
      let data = null
      try { data = await res.json() } catch { /* gövde JSON değil (proxy HTML hata sayfası vb.) */ }
      return {
        success: !!(data && data.success),
        status: res.status,
        reference: data?.reference,
        error: data?.error,
      }
    } catch (e) {
      // Ağ hatası (sunucuya ulaşılamadı / yeniden başlatma / CORS) — status yok.
      return { success: false, status: 0, networkError: true, error: String(e?.message || e?.name || e) }
    }
  },

  // Oturum içi "Sorun Bildir" (USER_REPORT) — kayıt sorun-bildirimleri ekranına düşer + admin maili.
  // Kimlik sunucuda OTURUMDAN okunur; dto yalnız kullanıcının bilebileceklerini + otomatik bağlamı taşır.
  sendIssueReport: (dto) => request('/issue-reports', { method: 'POST', body: JSON.stringify(dto) }),

  // Hafif oturum geçerlilik yoklaması — süpersede ise 401 → request() otomatik /?session=expired.
  // Sayfa kullanımı (System Health #1): görünür sekme anahtarı ping'e eklenir — yalnız `tab`, URL parametreleri değil
  sessionPing: (tab) => request(`/session/ping${tab ? '?tab=' + encodeURIComponent(tab) : ''}`, { timeoutMs: DEFAULT_TIMEOUT_MS }),

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
  // Envanter formundaki "Test et": YAZILAN degerlerle canli el sikismasi, KAYIT YOK.
  // check-preview'dan farki portu/TLS modunu/proxy'yi envanterden degil GOVDEDEN almasi —
  // henuz kaydedilmemis bir kayitta formdaki 8443 ancak boyle test edilebiliyor.
  testCertificate: (body) => request('/certificates/test', { method: 'POST', body: JSON.stringify(body) }),
  // Sertifika sağlık kontrol listesi: KALICI son kontrolden anında gelir (ağ beklemez).
  getCertificateHealth: (domain) => request(`/certificates/${encodeURIComponent(domain)}/health`),
  // "Şimdi kontrol et" — canlı el sıkışması koşar, sonucu kalıcılaştırır, listeyi tazeler.
  refreshCertificateHealth: (domain) =>
    request(`/certificates/${encodeURIComponent(domain)}/health/refresh`, { method: 'POST' }),
  // "Planlı yenilemeydi" onayı: sabitlenen parmak izi için kalıcı onay yazar, satır yeşile döner.
  confirmCertificateRenewal: (domain) =>
    request(`/certificates/${encodeURIComponent(domain)}/health/confirm-renewal`, { method: 'POST' }),

  getStats: () => request('/stats'),
  getExecutiveStats: () => request('/stats/executive'),   // yönetici özeti (2026-09-12, #20)
  getRecentChanges: (days = 7) => request(`/stats/changes?days=${days}`),   // "ne değişti" satırı (2026-09-12, #7)

  getTeamStats: () => request('/stats/teams'),

  runScheduler: () => request('/scheduler/run', { method: 'POST' }),

  getSchedulerStatus: () => request('/scheduler/status'),

  getRenewalAdvice: () => request('/renewal-advice'),
  // Vade takvimi (2026-09-12): tek gövde + planlanan yenileme
  getForecast: () => request('/forecast'),
  forecastPlan: (domain, date, note) => request(`/forecast/${encodeURIComponent(domain)}/plan`, { method: 'POST', body: JSON.stringify({ date, note }) }),
  forecastUnplan: (domain) => request(`/forecast/${encodeURIComponent(domain)}/plan`, { method: 'DELETE' }),

  // Birleşik aktivite akışı (Kayıtlar → Aktivite) — sayfalı/filtreli/takım-izole. Boş filtreler düşürülür.
  getActivity: (params = {}) => {
    const qs = new URLSearchParams()
    Object.entries(params).forEach(([k, v]) => { if (v !== undefined && v !== null && v !== '') qs.append(k, v) })
    const s = qs.toString()
    return request(`/activity${s ? `?${s}` : ''}`)
  },
  getActivityDetail: (id) => request(`/activity/${id}`),
  getActivitySummary: (params = {}) => {
    const qs = new URLSearchParams()
    Object.entries(params).forEach(([k, v]) => { if (v !== undefined && v !== null && v !== '') qs.append(k, v) })
    const s = qs.toString()
    return request(`/activity/summary${s ? `?${s}` : ''}`)
  },

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
    kpis: (id) => request(`/weekly-reports/${id}/kpis`),
    monitoringStats: (id) => request(`/weekly-reports/${id}/monitoring-stats`),
    years: (teamId) => request(`/weekly-reports/years${teamId ? '?teamId=' + teamId : ''}`),
    deadline: () => request('/weekly-reports/deadline'),   // son giriş günü/saati — canlı ayar (2026-09-12)
    completion: (year) => request(`/weekly-reports/completion${year ? '?year=' + year : ''}`),   // takım × hafta panosu (2026-09-12, #21)
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

  /**
   * Takim Bildirim Gruplari (/api/notification-groups).
   *
   * admin blogunun DISINDA: uc admin-only degil -- takimin her uyesi kendi takiminin
   * alici listesini yonetir (K2). api.admin altina konsaydi cagri yerlerinde yanlis
   * bir yetki cagrisimi yaratirdi.
   */
  notificationGroups: {
    /** teamId verilirse yalniz o takim; includeInactive silinmisleri de getirir (rozet icin). */
    list: (teamId, includeInactive = false) => {
      const qs = new URLSearchParams()
      if (teamId != null && teamId !== '') qs.set('teamId', String(teamId))
      if (includeInactive) qs.set('includeInactive', 'true')
      const q = qs.toString()
      return request(`/notification-groups${q ? `?${q}` : ''}`)
    },
    create: (body) => request('/notification-groups', { method: 'POST', body: JSON.stringify(body) }),
    update: (id, body) => request(`/notification-groups/${id}`, { method: 'PUT', body: JSON.stringify(body) }),
    remove: (id) => request(`/notification-groups/${id}`, { method: 'DELETE' }),
    makeDefault: (id) => request(`/notification-groups/${id}/make-default`, { method: 'POST' }),
    /** Grup nerede kullaniliyor — silme onayindan ONCE gosterilen ozet. */
    usage: (id) => request(`/notification-groups/${id}/usage`),
    /** Degisiklik gecmisi (kim/ne zaman/ne degisti) — SILINMIS gruplar dahil. Kaynak audit_log;
     *  denetim uclarindan ayri, cunku bu ekran notification.groups yetkisiyle acilir. */
    history: (groupId, limit = 50) => {
      const qs = new URLSearchParams({ limit: String(limit) })
      if (groupId != null) qs.set('groupId', String(groupId))
      return request(`/notification-groups/history?${qs.toString()}`)
    },
    /** Tum referanslari baska gruba tasi; targetGroupId null => takim varsayilani. */
    reassign: (id, targetGroupId) => request(`/notification-groups/${id}/reassign`, {
      method: 'POST', body: JSON.stringify({ target_group_id: targetGroupId ?? null }),
    }),
  },

  admin: {
    // Kişi-webhook (push) bildirim kanalı — yalnız admin
    userPush: {
      getSettings:  () => request('/admin/user-push/settings'),
      saveSettings: (data) => request('/admin/user-push/settings', { method: 'POST', body: JSON.stringify(data) }),
      saveScopes:   (rows) => request('/admin/user-push/scopes', { method: 'POST', body: JSON.stringify(rows) }),
      sendTest:     (data) => request('/admin/user-push/test', { method: 'POST', body: JSON.stringify(data) }),
      getDeliveries: (params) => request(`/admin/user-push/deliveries?${new URLSearchParams(params)}`),
      getStats:     () => request('/admin/user-push/stats'),
      explain:      (teamId, level) => request(`/admin/user-push/explain?teamId=${encodeURIComponent(teamId)}&level=${encodeURIComponent(level || 'HIGH')}`),
      exportUrl:    (params) => `/api/admin/user-push/deliveries/export?${new URLSearchParams(params)}`,
    },
    // Inventory
    getInventory: (showDeleted = false) => request(`/admin/inventory?showDeleted=${showDeleted}`),
    // Envanter zenginleştirme (2026-09-12): hijyen bandı + CSV içe aktarma (dry_run varsayılan true)
    getInventoryHygiene: () => request('/admin/inventory/hygiene'),
    importInventory: (rows, dryRun = true) =>
      request('/admin/inventory/import', { method: 'POST', body: JSON.stringify({ rows, dry_run: dryRun }) }),
    getInventoryByDomain: (domain) => request(`/admin/inventory/by-domain?domain=${encodeURIComponent(domain)}`),
    addInventory: (item) => request('/admin/inventory', { method: 'POST', body: JSON.stringify(item) }),
    updateInventory: (id, item) => request(`/admin/inventory/${id}`, { method: 'PUT', body: JSON.stringify(item) }),
    deleteInventory: (id) => request(`/admin/inventory/${id}`, { method: 'DELETE' }),
    restoreInventory: (id) => request(`/admin/inventory/${id}/restore`, { method: 'POST' }),
    // Kalıcı sil (geri alınamaz) — yalnız admin. Envanter + o domain'in kontrol geçmişi.
    purgeInventory: (id) => request(`/admin/inventory/${id}/permanent`, { method: 'DELETE' }),
    purgeDeletedInventory: () => request('/admin/inventory/purge-deleted', { method: 'POST' }),
    /** extra: yalniz set-contacts icin — GONDERILEN alanlar yazilir, otekilere dokunulmaz. */
    bulkInventory: (ids, action, extra) => request('/admin/inventory/bulk', {
      method: 'POST', body: JSON.stringify({ ids, action, ...(extra ?? {}) }),
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
    // Alan adı (registrar) süre bitişi tanılaması — adım adım RDAP/WHOIS trace.
    runDomainExpiryDiagnostics: (domain) => request('/admin/diagnostics/domain-expiry', {
      method: 'POST', body: JSON.stringify({ domain }),
    }),
    // Proxy'nin sunduğu CA zincirini yakala → yapıştırılmaya hazır PEM (varsayılan host: data.iana.org).
    captureProxyCaChain: (host) => request('/admin/diagnostics/proxy-ca-chain', {
      method: 'POST', body: JSON.stringify(host ? { host } : {}),
    }),
    diagHistory: (domain) => request(`/admin/diagnostics/history?domain=${encodeURIComponent(domain)}`),
    diagHistoryDetail: (id) => request(`/admin/diagnostics/history/${id}`),
    clientIpDebug: () => request('/admin/client-ip-debug'),

    // Genel Ayarlar — küratörlü runtime config (admin-only Settings page)
    getGeneralSettings: () => request('/admin/general/settings'),
    saveGeneralSettings: (dto) => request('/admin/general/settings', { method: 'PUT', body: JSON.stringify(dto) }),

    // Branding (beyaz etiket) — login/uygulama kimliği + duyuru şeridi
    // Veri Saklama (retention) — politika matrisi, dry-run, elle temizlik, çalışma geçmişi.
    getRetentionOverview: (estimate = false) =>
      request(`/admin/retention/overview${estimate ? '?estimate=true' : ''}`),
    saveRetentionSettings: (values) =>
      request('/admin/retention/settings', { method: 'PUT', body: JSON.stringify({ values }) }),
    retentionDryRun: () => request('/admin/retention/dry-run', { method: 'POST' }),
    retentionRunNow: () => request('/admin/retention/run', { method: 'POST' }),
    /** Koşum listesi — sayı verilirse eski `limit` biçimi; nesne verilirse sunucu-taraflı süzgeç/sayfa param'ları. */
    getRetentionRuns: (params = 10) => {
      if (typeof params === 'number') return request(`/admin/retention/runs?limit=${params}`)
      const qs = new URLSearchParams()
      for (const [k, v] of Object.entries(params)) if (v != null && v !== '' && v !== false) qs.set(k, String(v))
      const s = qs.toString()
      return request(`/admin/retention/runs${s ? `?${s}` : ''}`)
    },
    getRetentionRunsCsvUrl: (params = {}) => {
      const qs = new URLSearchParams()
      for (const [k, v] of Object.entries(params)) if (v != null && v !== '' && v !== false) qs.set(k, String(v))
      const s = qs.toString()
      return `/api/admin/retention/runs/export${s ? `?${s}` : ''}`
    },
    // ── Sürüm & Dağıtım geçmişi (release_history.read / .edit) ──
    getDeployments: (params = {}) => {
      const qs = new URLSearchParams()
      for (const [k, v] of Object.entries(params)) if (v != null && v !== '' && v !== false) qs.set(k, String(v))
      const s = qs.toString()
      return request(`/admin/deployments${s ? `?${s}` : ''}`)
    },
    getDeploymentsCsvUrl: (params = {}) => {
      const qs = new URLSearchParams()
      for (const [k, v] of Object.entries(params)) if (v != null && v !== '' && v !== false) qs.set(k, String(v))
      const s = qs.toString()
      return `/api/admin/deployments/export${s ? `?${s}` : ''}`
    },
    getDeploymentTimeline: (env) => request(`/admin/deployments/timeline${env ? `?env=${encodeURIComponent(env)}` : ''}`),
    getDeploymentMatrix: (all = false) => request(`/admin/deployments/matrix${all ? '?all=true' : ''}`),
    createDeployment: (body) => request('/admin/deployments', { method: 'POST', body: JSON.stringify(body) }),
    backfillDeployments: (environment) => request('/admin/deployments/backfill', { method: 'POST', body: JSON.stringify({ environment }) }),
    deleteDeployment: (id) => request(`/admin/deployments/${id}`, { method: 'DELETE' }),
    // ── Sürüm & Dağıtım geçmişi (release_history.read / .edit) ──
    getDeployments: (params = {}) => {
      const qs = new URLSearchParams()
      for (const [k, v] of Object.entries(params)) if (v != null && v !== '' && v !== false) qs.set(k, String(v))
      const s = qs.toString()
      return request(`/admin/deployments${s ? `?${s}` : ''}`)
    },
    getDeploymentsCsvUrl: (params = {}) => {
      const qs = new URLSearchParams()
      for (const [k, v] of Object.entries(params)) if (v != null && v !== '' && v !== false) qs.set(k, String(v))
      const s = qs.toString()
      return `/api/admin/deployments/export${s ? `?${s}` : ''}`
    },
    getDeploymentTimeline: (env) => request(`/admin/deployments/timeline${env ? `?env=${encodeURIComponent(env)}` : ''}`),
    getDeploymentMatrix: (all = false) => request(`/admin/deployments/matrix${all ? '?all=true' : ''}`),
    createDeployment: (body) => request('/admin/deployments', { method: 'POST', body: JSON.stringify(body) }),
    backfillDeployments: (environment) => request('/admin/deployments/backfill', { method: 'POST', body: JSON.stringify({ environment }) }),
    deleteDeployment: (id) => request(`/admin/deployments/${id}`, { method: 'DELETE' }),
    /** Saklama süresi değişiklik geçmişi (kim/ne zaman/eski→yeni) — audit_log kaynaklı. */
    getRetentionChanges: (limit = 25, policyId) =>
      request(`/admin/retention/changes?limit=${limit}${policyId ? `&policyId=${encodeURIComponent(policyId)}` : ''}`),
    // Saatlik özeti geriye doldur — ham seri kısaltılmadan ÖNCE çalıştırılır (yalnız yazar, silmez).
    retentionBackfillHourly: (days = 0) =>
      request(`/admin/retention/backfill-hourly${days > 0 ? `?days=${days}` : ''}`, { method: 'POST' }),
    saveRetentionApproval: (policyId, note) =>
      request('/admin/retention/approval', { method: 'PUT', body: JSON.stringify({ policy_id: policyId, note }) }),

    // Cihaz Gecmisi — ADMIN salt-okunur gorunumu (K8). Eylem ucu YOKTUR ve olmamalidir:
    // iptal/cikis yalniz kullanicinin KENDI self-scope ucundadir (sunucu tarafinda da oyle).
    getUserDevices: (userId) => request(`/admin/users/${userId}/devices`),
    getUserDeviceLogins: (userId, params = {}) => {
      const qs = new URLSearchParams()
      Object.entries(params).forEach(([k, v]) => {
        if (v !== undefined && v !== null && v !== '') qs.append(k, v)
      })
      const s = qs.toString()
      return request(`/admin/users/${userId}/devices/logins${s ? `?${s}` : ''}`)
    },

    getBrandingSettings: () => request('/admin/branding/settings'),
    saveBrandingSettings: (dto) => request('/admin/branding/settings', { method: 'PUT', body: JSON.stringify(dto) }),

    // Login Sorun Bildirimleri — admin triyaj (listele/detay/durum)
    getLoginIssues: (params = {}) => {
      const qs = new URLSearchParams()
      if (params.status) qs.set('status', params.status)
      if (params.source) qs.set('source', params.source)
      if (params.category) qs.set('category', params.category)
      if (params.q) qs.set('q', params.q)
      if (params.since) qs.set('since', params.since)
      if (params.until) qs.set('until', params.until)
      if (params.page != null) qs.set('page', params.page)
      if (params.size != null) qs.set('size', params.size)
      const q = qs.toString()
      return request(`/admin/login-issues${q ? '?' + q : ''}`)
    },
    getLoginIssue: (id) => request(`/admin/login-issues/${id}`),
    updateLoginIssueStatus: (id, dto) =>
      request(`/admin/login-issues/${id}/status`, { method: 'PUT', body: JSON.stringify(dto) }),
    // KALICI silme: kayit + gorselleri + giden maillerin saklanan kopyalari. Ayri ve
    // "hassas" bir yetki ister (issues.login-reports.purge).
    purgeLoginIssue: (id) => request(`/admin/login-issues/${id}`, { method: 'DELETE' }),


    // Anahtar çözümleme aracı — verilen SITE_MONITOR_SECRET_KEY ile şifreli alanları çöz
    secretToolsInfo: () => request('/admin/secret-tools/info'),
    decryptSecrets: (key) => request('/admin/secret-tools/decrypt', { method: 'POST', body: JSON.stringify({ key }) }),

    // Veritabanı bilgileri (admin-only Settings sayfası) — bağlı PostgreSQL meta verisi
    getDatabaseInfo: () => request('/admin/database/info'),

    // LDAP / Active Directory settings (admin-only Settings page)
    getLdapSettings: () => request('/admin/ldap/settings'),
    saveLdapSettings: (dto) => request('/admin/ldap/settings', { method: 'PUT', body: JSON.stringify(dto) }),
    // verify=true: kayıtlı "doğrulamayı atla" ayarı DEĞİŞMEDEN sertifika doğrulaması açık denenir
    testLdap: (verify = false) => request(`/admin/ldap/test${verify ? '?verify=true' : ''}`, { method: 'POST' }),
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

    // Başarısız-login anomali uyarısı (Ayarlar → Login Anomali sayfası)
    getLoginAnomalySettings: () => request('/admin/login-anomaly/settings'),
    saveLoginAnomalySettings: (dto) => request('/admin/login-anomaly/settings', {
      method: 'PUT', body: JSON.stringify(dto),
    }),
    testLoginAnomalyEmail: (recipient) => request('/admin/login-anomaly/test-email', {
      method: 'POST', body: JSON.stringify({ recipient }),
    }),
    getLoginAnomalyIncidents: (page = 0, size = 20) =>
      request(`/admin/login-anomaly/incidents?page=${page}&size=${size}`),

    // Aylık sertifika envanteri raporu (Ayarlar → Envanter Raporu) — ayın son cuması 10:00
    getCertInvReportStatus:  () => request('/admin/system/cert-inventory-report/status'),
    getCertInvReportPreview: () => request('/admin/system/cert-inventory-report/preview'),
    runCertInvReport:        () => request('/admin/system/cert-inventory-report/run', { method: 'POST' }),
    sendCertInvReportTest:   (email) => request('/admin/system/cert-inventory-report/send-test', {
      method: 'POST', body: JSON.stringify({ email }),
    }),
    getCertInvReportHistory: (limit = 24) => request(`/admin/system/cert-inventory-report/history?limit=${limit}`),
    saveCertInvReportSettings: (values) => request('/admin/system/cert-inventory-report/settings', {
      method: 'PUT', body: JSON.stringify(values),
    }),

    // Haftalık erişilebilirlik e-postası (Ayarlar → Haftalık E-posta sayfası)
    getWeeklyAvailStatus: () => request('/admin/system/weekly-availability/status'),
    getWeeklyAvailPreview: (teamId, weekOffset) =>
      request(`/admin/system/weekly-availability/preview?teamId=${encodeURIComponent(teamId)}`
        + (weekOffset != null ? `&weekOffset=${encodeURIComponent(weekOffset)}` : '')),
    /**
     * Haftalık kesinti PDF'ini indirir — mail ekinin BİREBİR aynısı.
     *
     * <p>Düz `<a href>` yerine blob: uç PDF üretilemediğinde 503 döner ve bağlantı olsaydı
     * tarayıcı boş/bozuk bir sayfaya giderdi. Burada hata yakalanıp kullanıcıya söylenebiliyor.
     * Dosya küçük (tipik 30 KB – birkaç MB), belleğe almak sorun değil.
     *
     * @returns {Promise<{success: boolean, error?: string}>}
     */
    downloadWeeklyOutagePdf: async (teamId, weekOffset) => {
      const url = `${BASE}/admin/system/weekly-availability/outage-pdf`
        + `?teamId=${encodeURIComponent(teamId)}`
        + (weekOffset != null ? `&weekOffset=${encodeURIComponent(weekOffset)}` : '')
      try {
        const res = await fetch(url, { credentials: 'include' })
        if (!res.ok) {
          return { success: false, status: res.status,
            error: res.status === 503 ? 'PDF_GENERATION_FAILED' : `HTTP_${res.status}` }
        }
        const blob = await res.blob()
        // Dosya adını sunucunun Content-Disposition'ından al — mailde giden adla aynı olsun.
        const disp = res.headers.get('Content-Disposition') || ''
        const match = /filename="?([^";]+)"?/.exec(disp)
        const objectUrl = URL.createObjectURL(blob)
        const a = document.createElement('a')
        a.href = objectUrl
        a.download = match ? match[1] : 'haftalik-kesinti-raporu.pdf'
        a.click()
        // Serbest bırakmayı ERTELE: click() indirmeyi eşzamanlı başlatmıyor ve URL hemen
        // geçersiz kılınırsa bazı tarayıcılar dosyayı boş indiriyor ya da hiç indirmiyor.
        setTimeout(() => URL.revokeObjectURL(objectUrl), 0)
        return { success: true }
      } catch (e) {
        return { success: false, error: e?.message || 'NETWORK' }
      }
    },
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
    /**
     * Alarm Geçmişi CSV bağlantısı — indirme <a href> ile yapılır, fetch ile DEĞİL.
     *
     * <p>Sunucu dosyayı Content-Disposition ile akıtıyor; tarayıcının indirme akışını kullanmak
     * hem büyük dosyayı belleğe almamayı hem de oturum çerezinin kendiliğinden gitmesini sağlar
     * (Kontrol Geçmişi CSV'siyle aynı desen).
     */
    getAlertsCsvUrl: (params = {}) => {
      const qs = new URLSearchParams()
      Object.entries(params).forEach(([k, v]) => {
        if (v !== undefined && v !== null && v !== '') qs.append(k, v)
      })
      const s = qs.toString()
      return `${BASE}/admin/alerts/export${s ? `?${s}` : ''}`
    },

    getAlerts: (params = {}) => {
      const opts = typeof params === 'object' && params !== null ? params : { onlyOpen: params }
      const qs = new URLSearchParams()
      Object.entries(opts).forEach(([k, v]) => {
        if (v !== undefined && v !== null && v !== '') qs.append(k, v)
      })
      const s = qs.toString()
      return request(`/admin/alerts${s ? `?${s}` : ''}`)
    },
    // note = zorunlu gerekçe (en az 3 kelime). Sunucu da doğruluyor; geçersizse 400 döner.
    acknowledgeAlert: (id, note) => request(`/admin/alerts/${id}/acknowledge`, {
      method: 'POST',
      body: JSON.stringify({ note }),
    }),
    resolveAlert: (id, note) => request(`/admin/alerts/${id}/resolve`, {
      method: 'POST',
      body: JSON.stringify({ note }),
    }),
    reNotifyAlert: (id, body) => request(`/admin/alerts/${id}/re-notify`, {
      method: 'POST', ...(body ? { body: JSON.stringify(body) } : {}),
    }),
    // "Tekrar Bildir" onay pop-up'ı: gönderim yapmadan alıcı listesini döner
    previewReNotify: (id) => request(`/admin/alerts/${id}/re-notify/preview`),
    // Toplu işlem: action ∈ {acknowledge, resolve, re-notify}, ids = alarm id listesi
    // note yalnız acknowledge/resolve için gerekli; re-notify'da null geçilir (sunucu da aramaz).
    bulkAlertAction: (action, ids, note) => request('/admin/alerts/bulk', {
      method: 'POST', body: JSON.stringify({ action, ids, ...(note ? { note } : {}) }),
    }),
    getAlertNotifications: (id) => request(`/admin/alerts/${id}/notifications`),
    getAlertNoise: (days = 7) => request(`/admin/alerts/noise?days=${days}`),   // gürültü analizi (2026-09-12, #18)
    getAlertPushDeliveries: (id) => request(`/admin/alerts/${id}/push-deliveries`),

    // Teams
    getTeams: () => request('/admin/teams'),
    createTeam: (data) => request('/admin/teams', { method: 'POST', body: JSON.stringify(data) }),
    updateTeam: (id, data) => request(`/admin/teams/${id}`, { method: 'PUT', body: JSON.stringify(data) }),
    deleteTeam: (id) => request(`/admin/teams/${id}`, { method: 'DELETE' }),
    // Haftalık e-posta anahtarları — takım ÜYELERİNE açık dar uç (ad/e-posta/aktifliğe dokunmaz).
    updateTeamWeeklyNotifications: (id, data) =>
      request(`/admin/teams/${id}/weekly-notifications`, { method: 'PUT', body: JSON.stringify(data) }),
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
    unlockUserRole: (id) => request(`/admin/users/${id}/role-unlock`, { method: 'POST' }),
    unlockUserOrgRole: (id) => request(`/admin/users/${id}/org-role-unlock`, { method: 'POST' }),

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
    // Yapılandırma sağlığı kartı (2026-09-12, #25)
    getConfigHealth: () => request('/admin/config-health'),
    getWeakAlgorithms: () => request('/admin/audit/weak-algorithms'),
    // 2026-09-12 zenginleştirme: CSV indirme <a href> ile (same-origin cookie), istisna ve takıma bildir uçları
    weakAlgorithmsExportUrl: () => `${BASE}/admin/audit/weak-algorithms/export`,
    setWeakAlgorithmException: (domain, body) =>
      request(`/admin/audit/weak-algorithms/${encodeURIComponent(domain)}/exception`, { method: 'POST', body: JSON.stringify(body) }),
    clearWeakAlgorithmException: (domain) =>
      request(`/admin/audit/weak-algorithms/${encodeURIComponent(domain)}/exception`, { method: 'DELETE' }),
    notifyWeakAlgorithm: (domain) =>
      request(`/admin/audit/weak-algorithms/${encodeURIComponent(domain)}/notify`, { method: 'POST' }),

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
    getAuditIntegrity: () => request('/admin/audit/integrity'),
    // Olay türü kataloğu (tür + kategori + son 90 günün sayısı). Filtre listesi buradan gelir;
    // elle tutulan liste 162 türün yalnız 32'sini tanıyacak kadar sürüklenmişti.
    getAuditEventTypes: () => request('/admin/audit/event-types'),
    getAuditEntry: (id) => request(`/admin/audit/${id}`),
    getAuditResourceHistory: (type, id, limit = 100) =>
      request(`/admin/audit/resource/${encodeURIComponent(type)}/${encodeURIComponent(id)}?limit=${limit}`),
    getAuditActorHistory: (actorId, limit = 100) => request(`/admin/audit/actor/${actorId}?limit=${limit}`),
    getAuditCorrelated: (cid) => request(`/admin/audit/correlation/${encodeURIComponent(cid)}`),
    // Filtreli dışa aktarma URL'i (tarayıcı indirir; işlem sunucuda AUDIT_EXPORT olarak denetlenir).
    auditExportUrl: (format, params) => {
      const q = new URLSearchParams(
        Object.fromEntries(
          Object.entries({ ...params, format }).filter(([, v]) => v !== '' && v != null && v !== false)
        )
      ).toString()
      return `${BASE}/admin/audit/export?${q}`
    },

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
    terminateUserSession: (username, reason) => request('/admin/system/terminate-session', {
      method: 'POST', body: JSON.stringify(reason ? { username, reason } : { username }),
    }),
    // Kullanıcı etkinliği zenginleştirmesi (2026-09-13): kullanıcı zaman çizelgesi + anomali onayı
    getUserTimeline: (username, limit = 20) => request(`/admin/system/user-activity/user/${encodeURIComponent(username)}?limit=${limit}`),
    ackAnomaly: (auditId, acknowledge = true, note) => request(`/admin/system/user-activity/anomalies/${auditId}/ack`, {
      method: 'POST', body: JSON.stringify({ acknowledge, note: note || null }),
    }),
  },

  // ── Monitoring ───────────────────────────────────────────────────────────

  monitoring: {
    // İzleme Grupları (TAKIM + izleme TÜRÜ bazlı) — autocomplete + yeniden adlandırma; server-side takım filtresi
    listGroups: (teamId, type) => {
      const p = new URLSearchParams()
      if (teamId != null && teamId !== '') p.set('teamId', teamId)
      if (type) p.set('type', type)
      const q = p.toString()
      return request('/monitoring/groups' + (q ? '?' + q : ''))
    },
    renameGroup: (id, newName) => request('/monitoring/groups/' + id, { method: 'PUT', body: JSON.stringify({ new_name: newName }) }),
    // Monitor guide + notes (hedef-bazlı: type = KEYWORD|PING, target = url/host)
    getMonitorNotes: (type, target) =>
      request(`/monitoring/notes?type=${encodeURIComponent(type)}&target=${encodeURIComponent(target)}`),
    saveMonitorGuide: (type, target, guide) =>
      request('/monitoring/notes/guide', { method: 'PUT', body: JSON.stringify({ type, target, guide }) }),
    addMonitorNote: (data) => request('/monitoring/notes', { method: 'POST', body: JSON.stringify(data) }),
    updateMonitorNote: (id, data) => request(`/monitoring/notes/${id}`, { method: 'PUT', body: JSON.stringify(data) }),
    deleteMonitorNote: (id) => request(`/monitoring/notes/${id}`, { method: 'DELETE' }),

    // Incidents Overview (AlertEvent tabanlı — tüm monitörlerin olayları)
    incidents: {
      list: (params = {}) => {
        const q = new URLSearchParams()
        for (const k of ['status', 'rootCause', 'q', 'since', 'until', 'sort', 'dir', 'page', 'size']) {
          if (params[k] != null && params[k] !== '') q.set(k, params[k])
        }
        const qs = q.toString()
        return request(`/monitoring/incidents${qs ? '?' + qs : ''}`)
      },
      // Tekil olay — e-posta derin linki (?incident=<id>) sayfalı listede olmayan olayı da açabilsin.
      get:           (id) => request(`/monitoring/incidents/${id}`),
      comments:      (id) => request(`/monitoring/incidents/${id}/comments`),
      addComment:    (id, body) => request(`/monitoring/incidents/${id}/comments`, { method: 'POST', body: JSON.stringify({ body }) }),
      deleteComment: (commentId) => request(`/monitoring/incidents/comments/${commentId}`, { method: 'DELETE' }),
      remove:        (id) => request(`/monitoring/incidents/${id}`, { method: 'DELETE' }),
    },

    // Maintenance Windows (bakım penceresi)
    maintenance: {
      list:   () => request('/monitoring/maintenance'),
      active: () => request('/monitoring/maintenance/active'),
      create: (data) => request('/monitoring/maintenance', { method: 'POST', body: JSON.stringify(data) }),
      update: (id, data) => request(`/monitoring/maintenance/${id}`, { method: 'PUT', body: JSON.stringify(data) }),
      remove: (id) => request(`/monitoring/maintenance/${id}`, { method: 'DELETE' }),
      pause:  (id) => request(`/monitoring/maintenance/${id}/pause`, { method: 'POST' }),
      resume: (id) => request(`/monitoring/maintenance/${id}/resume`, { method: 'POST' }),
      quick:  (data) => request('/monitoring/maintenance/quick', { method: 'POST', body: JSON.stringify(data) }),
    },

    // Alarm fırtınası (alert storm) ayarları — "Alert Settings" bölümü
    storm: {
      getSettings:  () => request('/monitoring/storm/settings'),
      saveSettings: (data) => request('/monitoring/storm/settings', { method: 'PUT', body: JSON.stringify(data) }),
    },

    // Uptime
    getUptimeOverview:    () => request('/monitoring/uptime/overview'),
    getUptimeHistory:     (domain, hours = 24) => request(`/monitoring/uptime/${encodeURIComponent(domain)}/history?hours=${hours}`),

    // ── Kontrol Geçmişi v2 — TÜM türlerin tek history istemcisi (CheckHistoryTab kullanır) ──
    // kind: keyword|ping|port|http|domain|page|scripted|dns|uptime-http|uptime-ssl
    // (uptime türlerinde id = domain, params.port yalnız uptime-http'de anlamlı)
    // params: { from, to, days, status, changedOnly, page (0-tabanlı), size, port }
    getCheckHistory: (kind, id, params = {}) => {
      const q = historyQuery(params)
      return request(`${historyPath(kind, id)}${q ? `?${q}` : ''}`)
    },
    // CSV indirme <a href download> ile yapılır (session cookie same-origin) — fetch değil.
    getCheckHistoryCsvUrl: (kind, id, params = {}) => {
      const q = historyQuery({ ...params, format: 'csv' })
      return `${BASE}${historyPath(kind, id)}${q ? `?${q}` : ''}`
    },

    // Port
    getPortMonitors:   () => request('/monitoring/port'),
    createPortMonitor: (data) => request('/monitoring/port', { method: 'POST', body: JSON.stringify(data) }),
    updatePortMonitor: (id, data) => request(`/monitoring/port/${id}`, { method: 'PUT', body: JSON.stringify(data) }),
    deletePortMonitor: (id) => request(`/monitoring/port/${id}`, { method: 'DELETE' }),
    triggerPortCheck:  (id) => request(`/monitoring/port/${id}/check`, { method: 'POST' }),
    testPortMonitor:   (data) => request('/monitoring/port/test', { method: 'POST', body: JSON.stringify(data) }),
    getPortResponseSeries: (id, { from, to, days } = {}) => {
      const q = new URLSearchParams(
        Object.fromEntries(Object.entries({ from, to, days }).filter(([, v]) => v != null && v !== '')),
      ).toString()
      return request(`/monitoring/port/${id}/response-series${q ? `?${q}` : ''}`)
    },

    // DNS
    // Canlı teyit zincirleri ("Teyit denemesi X/N") — detay modalları 30sn'de bir poll eder
    getConfirmations:  (domain) => request(`/monitoring/confirmations${domain ? `?domain=${encodeURIComponent(domain)}` : ''}`),
    getDnsMonitors:    () => request('/monitoring/dns'),
    createDnsMonitor:  (data) => request('/monitoring/dns', { method: 'POST', body: JSON.stringify(data) }),
    updateDnsMonitor:  (id, data) => request(`/monitoring/dns/${id}`, { method: 'PUT', body: JSON.stringify(data) }),
    deleteDnsMonitor:  (id) => request(`/monitoring/dns/${id}`, { method: 'DELETE' }),
    triggerDnsCheck:   (id) => request(`/monitoring/dns/${id}/check`, { method: 'POST' }),
    getDnsResponseSeries: (id, { from, to, days } = {}) => {
      const q = new URLSearchParams(
        Object.fromEntries(Object.entries({ from, to, days }).filter(([, v]) => v != null && v !== '')),
      ).toString()
      return request(`/monitoring/dns/${id}/response-series${q ? `?${q}` : ''}`)
    },
    getDnsDetails:     (id) => request(`/monitoring/dns/${id}/details`),
    testDnsMonitor:    (data) => request('/monitoring/dns/test', { method: 'POST', body: JSON.stringify(data) }),

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

    // Page Integrity (Sayfa Bütünlüğü) — 9. tür
    getPageMonitors:   () => request('/monitoring/page'),
    createPageMonitor: (data) => request('/monitoring/page', { method: 'POST', body: JSON.stringify(data) }),
    updatePageMonitor: (id, data) => request(`/monitoring/page/${id}`, { method: 'PUT', body: JSON.stringify(data) }),
    deletePageMonitor: (id) => request(`/monitoring/page/${id}`, { method: 'DELETE' }),
    triggerPageCheck:  (id) => request(`/monitoring/page/${id}/check`, { method: 'POST' }),
    testPage:          (data) => request('/monitoring/page/test', { method: 'POST', body: JSON.stringify(data) }),
    getPageResponseSeries: (id, { from, to, days } = {}) => {
      const q = new URLSearchParams(
        Object.fromEntries(Object.entries({ from, to, days }).filter(([, v]) => v != null && v !== '')),
      ).toString()
      return request(`/monitoring/page/${id}/response-series${q ? `?${q}` : ''}`)
    },
    getPageIssues:     (id, { issueType, days, limit } = {}) => {
      const q = new URLSearchParams(
        Object.fromEntries(Object.entries({ issueType, days, limit }).filter(([, v]) => v != null && v !== '')),
      ).toString()
      return request(`/monitoring/page/${id}/issues${q ? `?${q}` : ''}`)
    },

    // Sayfa Hızı (Page Speed)
    getPageSpeedMonitors:   () => request('/monitoring/pagespeed'),
    createPageSpeedMonitor: (data) => request('/monitoring/pagespeed', { method: 'POST', body: JSON.stringify(data) }),
    updatePageSpeedMonitor: (id, data) => request(`/monitoring/pagespeed/${id}`, { method: 'PUT', body: JSON.stringify(data) }),
    deletePageSpeedMonitor: (id) => request(`/monitoring/pagespeed/${id}`, { method: 'DELETE' }),
    triggerPageSpeedCheck:  (id) => request(`/monitoring/pagespeed/${id}/check`, { method: 'POST' }),
    testPageSpeed:          (data) => request('/monitoring/pagespeed/test', { method: 'POST', body: JSON.stringify(data) }),
    getPageSpeedSeries: (id, { from, to, days, metric } = {}) => {
      const q = new URLSearchParams(
        Object.fromEntries(Object.entries({ from, to, days, metric }).filter(([, v]) => v != null && v !== '')),
      ).toString()
      return request(`/monitoring/pagespeed/${id}/response-series${q ? `?${q}` : ''}`)
    },
    getPageSpeedResources: (id, { checkId, limit } = {}) => {
      const q = new URLSearchParams(
        Object.fromEntries(Object.entries({ checkId, limit }).filter(([, v]) => v != null && v !== '')),
      ).toString()
      return request(`/monitoring/pagespeed/${id}/resources${q ? `?${q}` : ''}`)
    },

    // Senaryo İzleme (Scripted Check / k6) — 10. tür
    getScriptedMonitors:   () => request('/monitoring/scripted'),
    createScriptedMonitor: (data) => request('/monitoring/scripted', { method: 'POST', body: JSON.stringify(data) }),
    updateScriptedMonitor: (id, data) => request(`/monitoring/scripted/${id}`, { method: 'PUT', body: JSON.stringify(data) }),
    deleteScriptedMonitor: (id) => request(`/monitoring/scripted/${id}`, { method: 'DELETE' }),
    triggerScriptedCheck:  (id) => request(`/monitoring/scripted/${id}/check`, { method: 'POST' }),
    testScripted:          (data) => request('/monitoring/scripted/test', { method: 'POST', body: JSON.stringify(data) }),
    // Bağlantı teşhisi: aynı hedefe vekil/CA kombinasyonlarıyla k6 sondası — "Java çekiyor,
    // k6 çekmiyor" ayrımını ÖLÇER. İzleme havuzunu tüketmez (ayrı semafor), bacaklar sırayla koşar.
    diagnoseScripted:      (id, url) => request(`/monitoring/scripted/${id}/diagnose`,
                             { method: 'POST', body: JSON.stringify(url ? { url } : {}) }),
    // Sürüm geçmişi: liste gövde taşımaz (yüzlerce sürümde yanıt şişmesin), önizleme ayrı çağrı.
    getScriptedVersions:   (id) => request(`/monitoring/scripted/${id}/versions`),
    getScriptedVersion:    (id, versionId) => request(`/monitoring/scripted/${id}/versions/${versionId}`),
    // ── Yapılandırma değişiklik geçmişi (kim/ne zaman/hangi IP/neyi değiştirdi) ──
    // Script SÜRÜMLERİYLE karıştırmayın: orası script gövdesinin sürümleri, burası ayarların geçmişi.
    getChanges: (kind, id, params = {}) => {
      const q = new URLSearchParams()
      Object.keys(params).forEach(k => { if (params[k] != null && params[k] !== '') q.set(k, params[k]) })
      const qs = q.toString()
      return request(`/monitoring/changes/${kind}/${id}${qs ? '?' + qs : ''}`)
    },
    getChangeDetail: (kind, id, seq) => request(`/monitoring/changes/${kind}/${id}/${seq}`),
    getRecentChanges: (params = {}) => {
      const q = new URLSearchParams()
      Object.keys(params).forEach(k => { if (params[k] != null && params[k] !== '') q.set(k, params[k]) })
      const qs = q.toString()
      return request(`/monitoring/changes/recent${qs ? '?' + qs : ''}`)
    },
    // Geri döndürme: geçmişi EZMEZ, RESTORE olaylı yeni bir satır üretir (sunucu tarafında).
    restoreChange: (kind, id, seq, note) => request(`/monitoring/changes/${kind}/${id}/${seq}/restore`,
      { method: 'POST', body: JSON.stringify(note ? { changeNote: note } : {}) }),
    // Otomatik taslak — DOĞRULAMA YAPMAYAN ayrı uç (PUT /scripted/{id} her çağrıda k6 çalıştırıyor).
    saveScriptedDraft:     (data) => request('/monitoring/scripted/draft', { method: 'PUT', body: JSON.stringify(data) }),
    getScriptedDrafts:     () => request('/monitoring/scripted/drafts'),
    deleteScriptedDraft:   (monitorKey) => request(`/monitoring/scripted/draft/${monitorKey}`, { method: 'DELETE' }),
    getScriptedResponseSeries: (id, { from, to, days } = {}) => {
      const q = new URLSearchParams(
        Object.fromEntries(Object.entries({ from, to, days }).filter(([, v]) => v != null && v !== '')),
      ).toString()
      return request(`/monitoring/scripted/${id}/response-series${q ? `?${q}` : ''}`)
    },

    // Şablon kütüphanesi — Genel (teamId null) + Takım katmanları.
    // Yanıt satır başına can_edit/can_delete/can_promote/... taşır; UI yetkiyi YENİDEN HESAPLAMAZ.
    getScriptedTemplates:   (scope) => request(`/monitoring/scripted/templates${scope ? `?scope=${scope}` : ''}`),
    getScriptedTemplate:    (id) => request(`/monitoring/scripted/templates/${id}`),
    createScriptedTemplate: (data) => request('/monitoring/scripted/templates', { method: 'POST', body: JSON.stringify(data) }),
    updateScriptedTemplate: (id, data) => request(`/monitoring/scripted/templates/${id}`, { method: 'PUT', body: JSON.stringify(data) }),
    // permanent=true yalnız admin'e ve yerleşik OLMAYAN şablona açık (yerleşiği seeder diriltir).
    deleteScriptedTemplate: (id, permanent) => request(`/monitoring/scripted/templates/${id}${permanent ? '?permanent=true' : ''}`, { method: 'DELETE' }),
    // Çöpten geri alma. Adı bilinçli `undelete`: bu ailede "restore" SÜRÜM geri yüklemedir.
    undeleteScriptedTemplate: (id) => request(`/monitoring/scripted/templates/${id}/undelete`, { method: 'POST' }),
    promoteScriptedTemplate:  (id) => request(`/monitoring/scripted/templates/${id}/promote`, { method: 'POST' }),
    demoteScriptedTemplate:   (id, teamId) => request(`/monitoring/scripted/templates/${id}/demote`, { method: 'POST', body: JSON.stringify({ teamId }) }),
    getScriptedTemplateVersions: (id) => request(`/monitoring/scripted/templates/${id}/versions`),
    getScriptedTemplateVersion:  (id, versionId) => request(`/monitoring/scripted/templates/${id}/versions/${versionId}`),

    // HTTP / Website
    getHttpMonitors:   () => request('/monitoring/http'),
    // Kart mini trendi (2026-09-12): tür başına tek toplu istek — saatlik kovalar + son 5 kontrol
    getSparklines: (type, hours = 24) => request(`/monitoring/sparklines?type=${encodeURIComponent(type)}&hours=${hours}`),
    // Kullanılabilirlik / SLA (2026-09-12, #11): 30 günlük oran + hedef
    getSla: (type, days = 30) => request(`/monitoring/sla?type=${encodeURIComponent(type)}&days=${days}`),
    createHttpMonitor: (data) => request('/monitoring/http', { method: 'POST', body: JSON.stringify(data) }),
    updateHttpMonitor: (id, data) => request(`/monitoring/http/${id}`, { method: 'PUT', body: JSON.stringify(data) }),
    deleteHttpMonitor: (id) => request(`/monitoring/http/${id}`, { method: 'DELETE' }),
    triggerHttpCheck:  (id) => request(`/monitoring/http/${id}/check`, { method: 'POST' }),
    testHttp:          (data) => request('/monitoring/http/test', { method: 'POST', body: JSON.stringify(data) }),
    getHttpResponseSeries: (id, { from, to, days } = {}) => {
      const q = new URLSearchParams(
        Object.fromEntries(Object.entries({ from, to, days }).filter(([, v]) => v != null && v !== '')),
      ).toString()
      return request(`/monitoring/http/${id}/response-series${q ? `?${q}` : ''}`)
    },

    // Domain (alan adı süre bitişi)
    getDomainMonitors:   () => request('/monitoring/domain'),
    createDomainMonitor: (data) => request('/monitoring/domain', { method: 'POST', body: JSON.stringify(data) }),
    updateDomainMonitor: (id, data) => request(`/monitoring/domain/${id}`, { method: 'PUT', body: JSON.stringify(data) }),
    deleteDomainMonitor: (id) => request(`/monitoring/domain/${id}`, { method: 'DELETE' }),
    triggerDomainCheck:  (id) => request(`/monitoring/domain/${id}/check`, { method: 'POST' }),
    testDomain:          (data) => request('/monitoring/domain/test', { method: 'POST', body: JSON.stringify(data) }),
    // Domain Kaydı (registration) — DB'deki son bilgi; live=true → anlık RDAP sorgusu.
    getDomainRegistration: (id, { live } = {}) =>
      request(`/monitoring/domain/${id}/registration${live ? '?live=true' : ''}`),

    // Ping
    getPingMonitors:   () => request('/monitoring/ping'),
    monitorDefaults: () => request('/monitoring/defaults'),
    createPingMonitor: (data) => request('/monitoring/ping', { method: 'POST', body: JSON.stringify(data) }),
    updatePingMonitor: (id, data) => request(`/monitoring/ping/${id}`, { method: 'PUT', body: JSON.stringify(data) }),
    deletePingMonitor: (id) => request(`/monitoring/ping/${id}`, { method: 'DELETE' }),
    triggerPingCheck:  (id) => request(`/monitoring/ping/${id}/check`, { method: 'POST' }),
    testPingMonitor:   (data) => request('/monitoring/ping/test', { method: 'POST', body: JSON.stringify(data) }),
    getPingResponseSeries: (id, { from, to, days } = {}) => {
      const q = new URLSearchParams(
        Object.fromEntries(Object.entries({ from, to, days }).filter(([, v]) => v != null && v !== '')),
      ).toString()
      return request(`/monitoring/ping/${id}/response-series${q ? `?${q}` : ''}`)
    },

    // Sertifika serisi: id yerine DOMAIN (cert domain-anahtarlı) → nokta içerdiği için encodeURIComponent
    // şart (historyPath'teki uptime-ssl ile aynı kural). İki seri döner: avg/p95 = ms, days = kalan gün.
    getSslResponseSeries: (domain, { from, to, days } = {}) => {
      const q = new URLSearchParams(
        Object.fromEntries(Object.entries({ from, to, days }).filter(([, v]) => v != null && v !== '')),
      ).toString()
      return request(`/monitoring/uptime/${encodeURIComponent(domain)}/ssl/response-series${q ? `?${q}` : ''}`)
    },
  },
}

/**
 * Sunucu damgasını `new Date(...)`'ın UTC olarak okuyacağı biçime getirir.
 *
 * Backend damgaları saat dilimi eki OLMADAN gelir ("2026-09-07T10:00:00"); JS bunları YEREL
 * saat sayar, o yüzden sonuna `Z` eklenir. Eski hâli iki girdide bozuluyordu:
 *
 * 1. NEGATİF ofset — yalnız `'+'` aranıyordu, "2026-09-07T10:00:00-03:00" onu içermediği için
 *    sonuna `Z` ekleniyor ve "…-03:00Z" çıkıyordu → `Invalid Date`. `toLocaleString`
 *    FIRLATMADIĞI için catch dalı da çalışmıyor, ekrana düpedüz "Invalid Date" yazılıyordu.
 * 2. YALNIZ TARİH — "2026-09-07" + "Z" = "2026-09-07Z", yine `Invalid Date`
 *    (formatDateOnly tam da bu biçimi alıyor).
 *
 * Ayrıca dize olmayan girdide `.endsWith` FIRLATIYORDU; artık olduğu gibi geçiriliyor
 * (`new Date` zaten Date/number kabul eder).
 */
// toUtc / localDayKey: utils/localDay.js (yeniden dışa aktarılır — eski import yolları geçerli)
export { toUtc, localDayKey }

export function formatDate(iso) {
  if (!iso) return 'N/A'
  try {
    return new Date(toUtc(iso)).toLocaleString(dateLocale(), {
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
    return new Date(toUtc(iso)).toLocaleString(dateLocale(), {
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
    return new Date(toUtc(iso)).toLocaleTimeString(dateLocale(), {
      hour: '2-digit', minute: '2-digit', second: '2-digit',
    })
  } catch {
    return iso.substring(11, 19)
  }
}


export function formatDateOnly(iso) {
  if (!iso) return '—'
  try {
    return new Date(toUtc(iso)).toLocaleDateString(dateLocale(), {
      year: 'numeric', month: '2-digit', day: '2-digit',
    })
  } catch {
    return iso.substring(0, 10)
  }
}
