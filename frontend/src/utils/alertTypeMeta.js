import {
  Globe, ShieldAlert, Network, Plug, Server, Clock, Shuffle, AlertCircle,
  Target, Radio, ScanSearch, FlaskConical, CalendarDays, Link2, Ban, Zap, Gauge, Bell,
  ListX,
} from 'lucide-react'

/**
 * ALARM TİPİ SÖZLÜĞÜ — ikon + renk, tek kaynak.
 *
 * <p>Neden var (2026-08-16 bulgusu): {@code AlertHistory.jsx} kendi tip haritasını tutuyordu ve
 * yalnız <b>11</b> tip tanıyordu. Backend'de 24, sertifika tipleriyle birlikte <b>28</b> tip var
 * ({@code EscalationService.TYPE_*}). Sonuç: keyword / ping / HTTP / sayfa bütünlüğü / sentetik /
 * alan adı izleme alarmları ekranda HAM ENUM adıyla ({@code SCRIPTED_FAIL}, {@code KEYWORD_SLOW})
 * ikonsuz ve renksiz görünüyordu. Daha kötüsü: tip filtresi pill'leri de aynı haritadan
 * üretildiği için <b>o alarmlar hiç filtrelenemiyordu</b>.
 *
 * <p>Etiketler burada TEKRARLANMAZ: i18n'de zaten tam liste var ({@code incov.type.*}, 28 anahtar)
 * ve Olaylar sayfası onu kullanıyor. Bu modül yalnız görsel kimliği (ikon/renk) taşır; etiket için
 * {@link alertTypeLabel} i18n'e sorar. Böylece iki ayrı sözlüğün ayrışması mümkün olmaz.
 *
 * <p>Renkler izleme türüyle uyumlu seçildi (Nav'daki ikon dili) ve <b>mevcut 11 tipin renkleri
 * aynen korundu</b> — kullanıcı alışkanlığı bozulmasın.
 */
export const ALERT_TYPE_META = {
  // ── Sertifika (mevcut renkler korundu) ────────────────────────────────────
  EXPIRY:            { icon: Clock,        color: '#d97706' },
  CHAIN_BROKEN:      { icon: Link2,        color: '#7c3aed' },
  REVOKED:           { icon: Ban,          color: '#be123c' },
  MISMATCH:          { icon: Zap,          color: '#0891b2' },
  // Güvenlik kusurları: tarayıcının reddettiği sertifika — süre alarmlarından ayrı renk.
  HOSTNAME_MISMATCH: { icon: ShieldAlert,  color: '#b91c1c' },
  UNTRUSTED_CA:      { icon: ShieldAlert,  color: '#9f1239' },
  ACCESSIBILITY:     { icon: Globe,        color: '#dc2626' },

  // ── DNS (mevcut renkler korundu) ──────────────────────────────────────────
  DNS_FAILURE:       { icon: Server,       color: '#2563eb' },
  DNS_SLOW:          { icon: Clock,        color: '#0d9488' },
  DNS_UNEXPECTED:    { icon: AlertCircle,  color: '#ea580c' },
  DNS_INCONSISTENT:  { icon: Network,      color: '#0284c7' },
  DNS_CHANGED:       { icon: Shuffle,      color: '#9333ea' },

  // ── Port (PORT_DOWN rengi korundu) ────────────────────────────────────────
  PORT_DOWN:         { icon: Plug,         color: '#db2777' },
  PORT_SLOW:         { icon: Gauge,        color: '#c2410c' },
  PING_SLOW:         { icon: Gauge,        color: '#c2410c' },

  // ── HTTP / Website ────────────────────────────────────────────────────────
  HTTP_DOWN:         { icon: Globe,        color: '#b91c1c' },
  HTTP_SSL:          { icon: ShieldAlert,  color: '#a16207' },

  // ── Kelime (içerik doğrulama) ─────────────────────────────────────────────
  KEYWORD:               { icon: Target,     color: '#7e22ce' },
  KEYWORD_SLOW:          { icon: Gauge,      color: '#a21caf' },
  KEYWORD_SSL:           { icon: ShieldAlert, color: '#9333ea' },
  KEYWORD_DOMAIN_EXPIRY: { icon: CalendarDays, color: '#6d28d9' },

  // ── Ping ──────────────────────────────────────────────────────────────────
  PING_DOWN:         { icon: Radio,        color: '#e11d48' },

  // ── Alan adı (domain) izleme ──────────────────────────────────────────────
  DOMAIN_EXPIRY:      { icon: CalendarDays, color: '#ca8a04' },
  DOMAINMON_EXPIRY:   { icon: CalendarDays, color: '#a16207' },
  DOMAINMON_UNKNOWN:  { icon: AlertCircle,  color: '#78716c' },
  DOMAINMON_STATUS:   { icon: ShieldAlert,  color: '#9a3412' },
  DOMAINMON_CHANGED:  { icon: Shuffle,      color: '#7c2d12' },
  DOMAINMON_TRANSFER_LOCK: { icon: ShieldAlert, color: '#b45309' },
  DOMAINMON_BLACKLIST:     { icon: ListX,       color: '#b42318' },

  // ── Sayfa bütünlüğü ───────────────────────────────────────────────────────
  PAGE_DOWN:         { icon: ScanSearch,   color: '#dc2626' },
  PAGE_INTEGRITY:    { icon: ScanSearch,   color: '#0369a1' },

  // ── Sayfa hızı ────────────────────────────────────────────────────────────
  // DOWN kırmızı (kesinti), SLOW turuncu: yavaşlık kesinti DEĞİL, renk de bunu söylemeli.
  PAGESPEED_DOWN:    { icon: Gauge,        color: '#dc2626' },
  PAGESPEED_SLOW:    { icon: Gauge,        color: '#c2410c' },

  // ── Sentetik izleme ───────────────────────────────────────────────────────
  SCRIPTED_FAIL:     { icon: FlaskConical, color: '#be123c' },
  SCRIPTED_SLOW:     { icon: FlaskConical, color: '#0f766e' },
}

/** Sözlükte olmayan (yeni eklenmiş) bir tip için nötr görsel kimlik — asla boş dönmez. */
const FALLBACK = { icon: Bell, color: '#64748b' }

/**
 * Tipin görsel kimliği. Bilinmeyen/boş tipte de DAİMA kullanılabilir bir nesne döner:
 * yeni bir alarm tipi eklendiğinde ekran ikonsuz/renksiz kalmaz, yalnız nötr görünür.
 */
export function alertTypeMeta(type) {
  return ALERT_TYPE_META[type] ?? FALLBACK
}

/**
 * Tipin okunur adı — i18n'deki {@code incov.type.*} listesinden.
 *
 * <p>Çeviri yoksa HAM TİP döner (Olaylar sayfasındaki kanonik desen): yeni bir tip eklenip
 * çevirisi unutulduğunda ekranda "incov.type.YENI_TIP" gibi bir anahtar değil, en azından
 * tanınabilir bir enum adı görünür.
 *
 * @param {(k: string) => string} t  i18n çeviri fonksiyonu
 */
export function alertTypeLabel(t, type) {
  if (!type) return ''
  const key = `incov.type.${type}`
  const label = t(key)
  return label === key ? type : label
}

/** Sözlükte tanımlı tüm tipler — filtre pill'lerinin kaynağı. */
export const ALERT_TYPES = Object.keys(ALERT_TYPE_META)
