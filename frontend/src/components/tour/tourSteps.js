/**
 * Ürün turu içeriği (2026-09-13). Hedefler `data-tour="…"` öznitelikleriyle işaretlenir (sınıf adı
 * değil — stil değişse de kırılmaz); `tour-targets.test.js` her hedefin bir JSX'te var olduğunu pinler.
 *
 * Adım alanları: id · target (data-tour) · tab (o sekmeye geç) · placement · when(ctx) (rol süzgeci) ·
 * since (TOUR_VERSION; yenilikler turu) · help (whitepaper §14.x) · advanceOn (etkileşimli adım:
 * { selector } DOM'da belirince ya da { event } yayınlanınca ilerler) · doIt (Benim yerime yap) ·
 * before (adıma girerken çalışır: { reveal: sekme | '*' } menüyü/bölümü aç, { conceal: true } telefonda çekmeceyi
 * kapat) · mobile:false (dar ekranda atla) · center (hedefsiz, ortada).
 * Metinler i18n: tour.s.<id>.title / tour.s.<id>.body
 */
export const isAdminCtx = (c) => c?.role === 'ADMIN' || c?.role === 'TEAM_ADMIN'

export const MAIN_STEPS = [
  { id: 'welcome',   center: true, since: 1, help: '14.30' },
  { id: 'sidebar',   target: 'nav-groups', placement: 'right', since: 1, help: '14.3', before: { reveal: 'dashboard' } },
  { id: 'dashboard', target: 'dash-stats', tab: 'dashboard', placement: 'bottom', since: 1, help: '14.4' },
  { id: 'filters',   target: 'dash-filters', tab: 'dashboard', placement: 'bottom', since: 1, help: '14.4' },
  { id: 'check-now', target: 'check-now', tab: 'dashboard', placement: 'bottom', since: 1, help: '14.4' },
  { id: 'add-domain', target: 'add-domain', tab: 'dashboard', placement: 'bottom', since: 1, help: '14.12', when: isAdminCtx },
  // Sertifika penceresi shadcn Dialog (ModalShell): eski `.modal.show` sınıfı YOK → açık pencere, içindeki sekme
  // şeridinin `data-tour` hedefiyle tanınır. Kart kökü tıklamayla açılmaz; gerçek düğme alan adı `[data-cert-open]`
  // (2026-09-27). Kapı: tourEngine.test.js "seçiciler sınıf adına değil özniteliğe bağlı".
  { id: 'card',      target: 'first-card', tab: 'dashboard', placement: 'right', since: 1, help: '14.5',
    advanceOn: { selector: '[data-tour="cert-modal-tabs"]' }, doIt: { click: 'first-card', inner: '[data-cert-open]' } },
  { id: 'modal',     target: 'cert-modal-tabs', placement: 'bottom', since: 1, help: '14.5', requires: '[data-tour="cert-modal-tabs"]',
    after: { closeModal: true } },
  { id: 'all',       target: 'nav-tab-all', placement: 'right', since: 1, help: '14.8', before: { reveal: 'all' } },
  // Güncel ana ekranlar (2026-10-02, öneri 24): `since: 2` adımlar turu v1'de tamamlamış kullanıcıya "Yenilikler" olarak
  // sunulur (TOUR_VERSION 2; tamamı değil yalnız bu adımlar). `monitoring` kimliği korunur — hedefi HTTP sekmesi yerine
  // bölümün ilk sırasındaki İzleme Panosu. Favoriler / Görünümler adımları İzleme Panosu sayfasında gösterilir; telefonda
  // açık çekmece kapatılır (conceal), masaüstünde zararsız. Hedef yoksa (izleme/favori/tercih yok) adım atlanır.
  { id: 'monitoring', target: 'nav-tab-monitoring', placement: 'right', since: 2, help: '14.14', before: { reveal: 'monitoring' } },
  { id: 'favorites', target: 'mo-views', tab: 'monitoring', placement: 'bottom', since: 2, help: '14.14', before: { conceal: true } },
  { id: 'saved-views', target: 'saved-views', tab: 'monitoring', placement: 'bottom', since: 2, help: '14.14', before: { conceal: true } },
  { id: 'status-page', target: 'nav-tab-status', placement: 'right', since: 2, before: { reveal: 'status' } },
  { id: 'alerts',    target: 'nav-tab-incidents', placement: 'right', since: 1, help: '14.15', before: { reveal: 'incidents' } },
  { id: 'alert-history', target: 'nav-tab-alerthistory', placement: 'right', since: 2, help: '14.17', before: { reveal: 'alerthistory' } },
  { id: 'reports',   target: 'nav-tab-weeklyreports', placement: 'right', since: 1, help: '14.18', before: { reveal: 'weeklyreports' } },
  { id: 'admin',     target: 'nav-tab-admin', placement: 'right', since: 1, help: '14.24', when: isAdminCtx, before: { reveal: 'admin' } },
  { id: 'health',    target: 'nav-tab-health', placement: 'right', since: 1, help: '14.27', when: isAdminCtx, before: { reveal: 'health' } },
  { id: 'audit',     target: 'nav-tab-activity', placement: 'right', since: 1, help: '14.20', when: (c) => c?.role === 'AUDIT', before: { reveal: 'activity' } },
  { id: 'palette',   target: 'nav-search', placement: 'right', since: 1, mobile: false,
    // Palet shadcn Dialog (2026-09-26): açık palet `[data-command-palette]` taşır (CommandPalette.jsx), sınıf adı yok.
    advanceOn: { selector: '[data-command-palette]' }, doIt: { click: 'nav-search' }, after: { closePalette: true } },
  // Klavye kısayolları (öneri 24): hedefsiz bilgi adımı; telefonda klavye yok → atlanır.
  { id: 'shortcuts', center: true, since: 2, mobile: false },
  // Telefonda zil üst çubukta, yardım düğmesi sayfada → açık çekmece kapatılır (conceal); kullanıcı kartı
  // çekmecede → yalnız menü açılır (reveal '*'). Masaüstünde ikisi de zararsız. (2026-09-26, yeni kenar çubuğu)
  { id: 'inbox',     target: 'nav-inbox', placement: 'right', since: 1, help: '14.2', before: { conceal: true } },
  { id: 'user',      target: 'nav-user', placement: 'right', since: 1, help: '14.21', before: { reveal: '*' } },
  { id: 'help',      target: 'help-fab', placement: 'left', since: 1, help: '14.29', before: { conceal: true } },
  { id: 'done',      center: true, since: 1, help: '14.30' },
]

/** Sayfa turları — sekme kimliği → adımlar (aynı motor; "Bu sayfayı tanıt"). */
export const PAGE_TOURS = {
  all: [
    { id: 'all-filters', target: 'ct-filters', placement: 'bottom', help: '14.8' },
    { id: 'all-status',  target: 'ct-status', placement: 'bottom', help: '14.8' },
    { id: 'all-columns', target: 'ct-columns', placement: 'bottom', help: '14.8' },
    { id: 'all-presets', target: 'ct-presets', placement: 'bottom', help: '14.8' },
    { id: 'all-csv',     target: 'ct-csv', placement: 'bottom', help: '14.8' },
    { id: 'all-select',  target: 'ct-select', placement: 'right', help: '14.8', mobile: false },
    { id: 'all-menu',    target: 'ct-row-menu', placement: 'left', help: '14.8' },
  ],
  weeklyreports: [
    { id: 'wr-thisweek', target: 'wr-thisweek', placement: 'bottom', help: '14.18' },
    { id: 'wr-chips',    target: 'wr-chips', placement: 'bottom', help: '14.18' },
    { id: 'wr-table',    target: 'wr-table', placement: 'top', help: '14.18' },
    { id: 'wr-new',      target: 'wr-new', placement: 'bottom', help: '14.18', when: (c) => c?.role !== 'AUDIT' },
    { id: 'wr-help',     target: 'wr-help', placement: 'bottom', help: '14.18' },
  ],
  http: [
    { id: 'mon-how',  target: 'mon-how', placement: 'bottom', help: '14.14' },
    { id: 'mon-new',  target: 'mon-new', placement: 'bottom', help: '14.14', when: (c) => c?.canWrite !== false },
    { id: 'mon-guide', target: 'mon-guide', placement: 'bottom', help: '14.14' },
    { id: 'mon-cards', target: 'mon-cards', placement: 'top', help: '14.14' },
  ],
}

/** Başlangıç listesi (yeni kullanıcı): anahtar → tamamlanma sunucuda `checklist`. */
export const CHECKLIST_ITEMS = [
  { key: 'tour',    tab: null },            // tur tamamlandı
  { key: 'card',    tab: 'dashboard', visit: false },   // ilk sertifika kartı AÇILINCA (App.openCertModal), ziyaretle değil
  { key: 'all',     tab: 'all' },           // Tüm Sertifikalar ziyaret edildi
  { key: 'monitor', tab: 'http' },          // bir izleme sayfası ziyaret edildi
  { key: 'report',  tab: 'weeklyreports' }, // haftalık rapor sayfası görüldü
  { key: 'help',    tab: 'help' },          // yardım açıldı
]

/** Sekme ziyaretinden türeyen liste maddeleri (tab → key). */
export const CHECKLIST_BY_TAB = Object.fromEntries(CHECKLIST_ITEMS.filter((i) => i.tab && i.visit !== false).map((i) => [i.tab, i.key]))
// İzleme sekmelerinin hepsi "monitor" maddesini tamamlar
for (const tb of ['domain', 'port', 'dns', 'keyword', 'ping', 'page', 'pagespeed', 'scripted', 'uptime']) CHECKLIST_BY_TAB[tb] = 'monitor'
