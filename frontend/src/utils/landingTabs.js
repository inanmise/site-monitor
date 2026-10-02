/**
 * Açılış sekmesi seçimi (2026-10-02, öneri 23) — kullanıcının girişte açılacak sekmesi. Sıra kenar çubuğu sırası
 * (components/Nav.jsx SECTIONS + kullanıcı menüsündeki Ayarlar), etiketler kenar çubuğunun anahtarları. Pano listede YOK:
 * seçicinin varsayılan seçeneği "Pano (varsayılan)" — saklanan değer boştur.
 *
 * <p>Görünürlük App.jsx / Nav ile AYNI kurallar: Ayarlar yalnız rol ADMIN, SQL Playground yalnız global yönetici,
 * Haftalık Raporlar yalnız modül açıksa. Kimlikler App.jsx VALID_TABS'ta olmalı (kapı: landingTabs.test.js).
 */
export const LANDING_TAB_ORDER = Object.freeze([
  { id: 'all', labelKey: 'nav.all' },
  { id: 'domains', labelKey: 'nav.domains' },
  { id: 'uptime', labelKey: 'nav.uptime' },
  { id: 'forecast', labelKey: 'nav.forecast' },
  { id: 'renewal', labelKey: 'nav.renewal' },
  { id: 'renewal-guide', labelKey: 'nav.renewalGuide' },
  { id: 'monitoring', labelKey: 'nav.monitoringOverview' },
  { id: 'status', labelKey: 'nav.statusPage' },
  { id: 'http', labelKey: 'nav.http' },
  { id: 'ping', labelKey: 'nav.ping' },
  { id: 'port', labelKey: 'nav.port' },
  { id: 'dns', labelKey: 'nav.dns' },
  { id: 'domain', labelKey: 'nav.domainmon' },
  { id: 'keyword', labelKey: 'nav.keyword' },
  { id: 'page', labelKey: 'nav.page' },
  { id: 'pagespeed', labelKey: 'nav.pagespeed' },
  { id: 'scripted', labelKey: 'nav.scripted' },
  { id: 'warnings', labelKey: 'nav.warnings' },
  { id: 'incidents', labelKey: 'nav.incidents' },
  { id: 'maintenance', labelKey: 'nav.maintenance' },
  { id: 'alerthistory', labelKey: 'nav.alertHistory' },
  { id: 'storms', labelKey: 'nav.storms' },
  { id: 'noc', labelKey: 'nav.noc' },
  { id: 'stats', labelKey: 'nav.stats' },
  { id: 'weakalgo', labelKey: 'nav.weakAlgo' },
  { id: 'weeklyreports', labelKey: 'nav.weeklyReports' },
  { id: 'incident-history', labelKey: 'nav.incidentHistory' },
  { id: 'activity', labelKey: 'nav.activity' },
  { id: 'myactivity', labelKey: 'nav.myActivity' },
  { id: 'system', labelKey: 'nav.system' },
  { id: 'monitorchanges', labelKey: 'nav.monitorChanges' },
  { id: 'admin', labelKey: 'nav.admin' },
  { id: 'health', labelKey: 'nav.health' },
  { id: 'permissions', labelKey: 'nav.permissions' },
  { id: 'sqlplayground', labelKey: 'nav.sqlPlayground' },
  { id: 'login-issues', labelKey: 'nav.loginIssues' },
  { id: 'help', labelKey: 'nav.help' },
  { id: 'settings', labelKey: 'nav.settings' },
])

/** Kullanıcının GERÇEKTEN açabildiği sekmeler (Pano hariç), kenar çubuğu sırasıyla. */
export function landingTabOptions({ systemRole, globalAdmin, weeklyReportsVisible } = {}) {
  return LANDING_TAB_ORDER.filter(({ id }) => {
    if (id === 'settings') return systemRole === 'ADMIN'
    if (id === 'sqlplayground') return !!globalAdmin
    if (id === 'weeklyreports') return !!weeklyReportsVisible
    return true
  })
}
