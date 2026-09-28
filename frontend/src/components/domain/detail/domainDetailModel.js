/**
 * Alan Adı DETAY penceresi modeli (2026-09-28 yeniden tasarım) — saf yardımcılar (React yok; başlık, Kayıt bilgileri
 * sekmesi ve testler paylaşır). Kart modeli ({@link ../domainCardModel.js}) ile AYNI sözlük: kalan gün tonu, kayıt
 * dönemi, kilit, EPP sınıflaması oradan gelir; burada yalnız detayın fazladan ihtiyaçları durur.
 *
 * <p><b>Veri uydurulmaz.</b> Tüm alanlar GET /monitoring/domain satırından ya da aynı biçimi döndüren
 * GET /monitoring/domain/{id}/registration yanıtından (MonitoringController.enrichDomain) okunur. Olmayan bilgi
 * (ad sunucusu başına çözümleme, kayıt sahibi, RDAP/WHOIS ham yanıtı) gösterilmez; "bilinmiyor" ile "yok" ayrı tutulur.
 *
 * <p><b>Gün kuralı.</b> Bitiş `expiry_date` registry'nin takvim günüdür (WHOIS'te yalnız tarih, RDAP'ta UTC zaman
 * damgası). Gün anahtarı kart modelinin {@link expiryKey}'i (yerel gün), gösterim {@link fmtExpiry} — kartla aynı gün.
 * Zaman damgası `.slice(0, 10)` ile KESİLMEZ (RDAP 23:59:59Z İstanbul'da ertesi gündür).
 */
import { eppKey } from '../../../utils/domainEpp.js'
import { localDayKey } from '../../../utils/localDay.js'
import { dateLocale } from '../../../i18n/dateLocale.js'
import { eppChips, expiryKey, expiryTone, lockOf, planChipOf, sourceFamily } from '../domainCardModel.js'

const DAY_MS = 86_400_000

const num = (v) => {
  if (v === null || v === undefined || v === '') return null
  const n = Number(v)
  return Number.isFinite(n) ? n : null
}

/** 'yyyy-MM-dd' → UTC gece yarısı ms (yalnız gün aritmetiği içindir; saat dilimi kayması olmaz). */
function keyMs(key) {
  const m = /^(\d{4})-(\d{2})-(\d{2})$/.exec(String(key || ''))
  return m ? Date.UTC(Number(m[1]), Number(m[2]) - 1, Number(m[3])) : null
}

/** Bugünün YEREL gün anahtarı. */
export function todayKey(now = Date.now()) {
  const d = new Date(now)
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
}

/** İki gün anahtarı arasındaki gün farkı (a − b); anahtarlardan biri bozuksa null. */
export function dayDiff(a, b) {
  const x = keyMs(a), y = keyMs(b)
  return x == null || y == null ? null : Math.round((x - y) / DAY_MS)
}

/** Gün anahtarına n gün ekler (negatif = geri). */
export function addDays(key, n) {
  const x = keyMs(key)
  if (x == null) return null
  const d = new Date(x + n * DAY_MS)
  return `${d.getUTCFullYear()}-${String(d.getUTCMonth() + 1).padStart(2, '0')}-${String(d.getUTCDate()).padStart(2, '0')}`
}

/**
 * "212 gün sonra" / "3 yıl önce" / "bugün" — `Intl.RelativeTimeFormat` (TR/EN çoğul ve "dün/yarın" biçimleri yerelin
 * kendisinden). 45 günden kısa fark gün, iki yıldan kısa ay, ötesi yıl olarak söylenir.
 */
export function relativeDays(diff, locale = dateLocale()) {
  if (diff == null) return ''
  let fmt
  try { fmt = new Intl.RelativeTimeFormat(locale, { numeric: 'auto' }) } catch { return '' }
  const a = Math.abs(diff)
  if (a < 45) return fmt.format(diff, 'day')
  if (a < 730) return fmt.format(Math.round(diff / 30.44), 'month')
  return fmt.format(Math.round(diff / 365.25), 'year')
}

/** Değerin saat bilgisi var mı (RDAP zaman damgası) — yalnız tarih ("2029-10-26") değilse true. */
export function hasTime(iso) {
  return !!iso && String(iso).trim().length > 10
}

function parse(iso) {
  if (!iso) return null
  const s = String(iso).trim()
  const d = new Date(s.length <= 10 ? s + 'T00:00:00Z' : (/[zZ]$|[+-]\d{2}:?\d{2}$/.test(s) ? s : s + 'Z'))
  return Number.isNaN(d.getTime()) ? null : d
}

/** Uzun gün ("13 Ağustos 2027 Cuma") — {@link fmtExpiry} ile AYNI ayrıştırma, dolayısıyla aynı gün. */
export function fmtLongDay(iso) {
  const d = parse(iso)
  return d ? d.toLocaleDateString(dateLocale(), { weekday: 'long', day: 'numeric', month: 'long', year: 'numeric' }) : '—'
}

/** Saat ("15:37") — yalnız zaman damgası taşıyan değerde; tarih-yalnız değerde null (uydurma 03:00 yazılmaz). */
export function fmtClock(iso) {
  if (!hasTime(iso)) return null
  const d = parse(iso)
  return d ? d.toLocaleTimeString(dateLocale(), { hour: '2-digit', minute: '2-digit' }) : null
}

/**
 * Kayıt zaman çizelgesi — tarih sırasıyla olaylar: kayıt → son güncelleme → bugün → uyarı eşiği → planlanan yenileme
 * → bitiş (yalnız VERİSİ olanlar). Uyarı eşiği izlemenin kendi `warning_days`'inden türer (bitiş − N gün; dolmuş
 * kayıtta çizilmez). Öğe: `{ kind, key, iso, diff, state: past|today|future, tone }`; `diff` bugüne göre gün farkı.
 * Tonlar: muted (geçmiş kayıt olayları) · info (bugün) · warn (uyarı eşiği) · plan / bad (plan, gecikmiş plan) ·
 * bitiş için kart tonu (ok | warning | critical | expired).
 */
const KIND_RANK = { registered: 0, updated: 1, today: 2, warning: 3, plan: 4, expiry: 5 }

export function timelineOf(m, now = Date.now()) {
  const today = todayKey(now)
  const out = []
  // `diff` verilirse (sunucunun kalan günü) gün anahtarından hesaplanan yerine o kullanılır.
  const push = (kind, key, iso, tone, extra = {}, diffOverride = null) => {
    if (!key || keyMs(key) == null) return
    const diff = diffOverride ?? dayDiff(key, today)
    out.push({ kind, key, iso: iso ?? key, diff, state: diff < 0 ? 'past' : diff === 0 ? 'today' : 'future', tone, ...extra })
  }
  push('registered', localDayKey(m?.registration_date), m?.registration_date, 'muted')
  const updated = localDayKey(m?.last_changed)
  if (updated && updated !== localDayKey(m?.registration_date)) push('updated', updated, m?.last_changed, 'muted')
  push('today', today, today, 'info')
  const exp = expiryKey(m)
  // Bitiş ve eşik "kaç gün" bilgisi SUNUCUNUN kalan gününden: kahraman panelin büyük sayısıyla aynı günü söyler
  // (RDAP 23:59:59Z gibi damgalarda yerel gün farkı bir gün kaydırabilirdi).
  const days = num(m?.days_remaining)
  const expTone = expiryTone(days)
  const warn = num(m?.warning_days)
  if (exp && warn > 0 && expTone !== 'expired') {
    push('warning', addDays(exp, -warn), null, 'warn', { days: warn }, days == null ? null : days - warn)
  }
  if (m?.renewal_planned_at) {
    const at = localDayKey(m.renewal_planned_at)
    push('plan', at, m.renewal_planned_at, m.renewal_overdue ? 'bad' : 'plan',
      { overdue: !!m.renewal_overdue, before: exp && at ? dayDiff(exp, at) : null })
  }
  if (exp) push('expiry', exp, m.expiry_date, expTone === 'unknown' ? 'ok' : expTone, {}, days)
  return out.sort((a, b) => (keyMs(a.key) - keyMs(b.key)) || (KIND_RANK[a.kind] - KIND_RANK[b.kind]))
}

/**
 * Kilit matrisi — transfer · güncelleme · silme · yenileme. Transfer, sunucunun kendi hükmüdür (`transfer_lock`,
 * kartla aynı: {@link lockOf}); diğer üçü EPP kodlarından türer (`client<işlem>prohibited` = registrar kilidi,
 * `server<işlem>prohibited` = registry kilidi). Sunucu kilidi yalnız RDAP'ta doğrular (DomainCheckerService.lockKnown);
 * transfer "doğrulanamadı" ise türetilenler de doğrulanamadı sayılır — biri "Kilit yok", öteki "Doğrulanamadı"
 * diyen çelişkili bir tablo çıkmaz.
 * Öğe: `{ op, state: BOTH|SERVER|CLIENT|NONE|UNKNOWN, tone: ok|bad|warn|muted }`. Ton: transfer kilidi yoksa bad
 * (alarm konusu); güncelleme/silme kilidi yoksa muted (daha az koruma, alarm değil); YENİLEME kilidi varsa warn
 * (yenilemeyi engeller — beklenmedik), yoksa ok.
 */
export const LOCK_OPS = ['transfer', 'update', 'delete', 'renew']

export function locksOf(m) {
  const transfer = lockOf(m)
  const known = transfer.state !== 'UNKNOWN'
  const codes = new Set((Array.isArray(m?.status_codes) ? m.status_codes : []).map(eppKey))
  return LOCK_OPS.map((op) => {
    if (op === 'transfer') return { op, state: transfer.state, tone: transfer.tone }
    if (!known) return { op, state: 'UNKNOWN', tone: 'muted' }
    const client = codes.has(`client${op}prohibited`)
    const server = codes.has(`server${op}prohibited`)
    const state = client && server ? 'BOTH' : server ? 'SERVER' : client ? 'CLIENT' : 'NONE'
    const locked = state !== 'NONE'
    const tone = op === 'renew' ? (locked ? 'warn' : 'ok') : (locked ? 'ok' : 'muted')
    return { op, state, tone }
  })
}

/** EPP kodlarının TAMAMI, önem sırasıyla (kritik → uyarı → bilgi; aynı tonda kaynağın sırası) — kart modelinden. */
export function eppListOf(codes) {
  return eppChips(codes, Number.POSITIVE_INFINITY).shown
}

/**
 * Kara liste kanıtı: `blacklist_detail` = "zen.spamhaus.org=192.0.2.1 → 127.0.0.2; dbl.spamhaus.org=…"
 * (DnsblCheckerService — `;` ayraçlı `liste=hedef`). Öğe `{ list, target, delist }`; `delist` yalnız bilinen iki listenin
 * sabit kaldırma sayfası (sunucudaki delistUrl ile aynı; alan adı adrese eklenmez).
 */
export function blacklistEntriesOf(detail) {
  return String(detail || '').split(';').map((part) => {
    const s = part.trim()
    if (!s) return null
    const at = s.indexOf('=')
    const list = (at >= 0 ? s.slice(0, at) : s).trim()
    const target = at >= 0 ? s.slice(at + 1).trim() : ''
    const z = list.toLowerCase()
    const delist = z.includes('spamhaus') ? 'https://check.spamhaus.org/' : z.includes('spamcop') ? 'https://www.spamcop.net/bl.shtml' : null
    return list ? { list, target, delist } : null
  }).filter(Boolean)
}

/**
 * Yenileme planı bloğu: `{ kind: 'plan', state: planned|overdue, at, by, note, before }` (plan var; `before` = bitişten
 * kaç gün önce), `{ kind: 'cta' }` (plan yok ama kalan gün uyarı eşiğinde / dolmuş — kart kısayoluyla AYNI kural,
 * {@link planChipOf}), `{ kind: 'offer' }` (plan yok, eşik uzak, planlama yetkisi var — sakin bir düğme) ya da null.
 */
export function planBlockOf(m, canPlan) {
  const chip = planChipOf(m, canPlan)
  if (chip?.kind === 'plan') {
    const exp = expiryKey(m)
    const at = localDayKey(chip.at)
    return { ...chip, before: exp && at ? dayDiff(exp, at) : null }
  }
  if (chip?.kind === 'cta') return chip
  return canPlan && expiryKey(m) ? { kind: 'offer' } : null
}

/**
 * Bitişi bilinmeyen kayıt için sonraki adımlar (anahtar listesi, sırayla): hiç kontrol yoksa ilk sorgu; hata varsa
 * yazım + geçici hata; kaynak tarih döndürmediyse registry notu; .tr alan adında web WHOIS notu; en sonda Tanıla
 * (yetki varsa ne yaptığı, yoksa kime sorulacağı).
 */
export function nextStepsOf(m, { canDiagnose = false } = {}) {
  const steps = []
  if (!m?.checked_at) steps.push('never')
  else if (String(m.error || '').trim()) steps.push('spelling', 'retry')
  else steps.push('nodata')
  if (/\.tr$/i.test(String(m?.domain || ''))) steps.push('tr')
  if (m?.checked_at) steps.push(canDiagnose ? 'diagnose' : 'diagnoseAdmin')
  return steps
}

/** Kaynak ailesi açıklaması için anahtar (rdap | whois) — kart modelinden; "NONE" null. */
export { sourceFamily }

/** Kontrol sıklığı (saat) — `interval_seconds`; yoksa null. */
export function intervalHours(m) {
  const s = num(m?.interval_seconds)
  return s && s > 0 ? Math.max(1, Math.round(s / 3600)) : null
}

/**
 * "Kayıt verisi (JSON)" görünümü — SiteMonitor'ün bu alan adı için SAKLADIĞI normalleştirilmiş kayıt (son kontrol).
 * RDAP/WHOIS'in ham yanıtı sunucuda tutulmaz; burada gösterilen, kayıt sekmesinin okuduğu yanıtın kayıtla ilgili
 * alanlarıdır (takım / bildirim ayarları gibi izleme yapılandırması dışarıda kalır). Sıra sabittir.
 */
export const RAW_KEYS = [
  'domain', 'source', 'whois_provider', 'checked_at', 'status', 'days_remaining', 'expiry_date', 'registration_date',
  'last_changed', 'registrar', 'registrar_iana_id', 'status_codes', 'nameservers', 'ns_resolves', 'dnssec',
  'resolved_ips', 'hostnames', 'transfer_lock', 'blacklist_status', 'blacklist_detail', 'changed', 'change_detail', 'error',
]

export function rawRecordOf(reg) {
  const out = {}
  for (const k of RAW_KEYS) if (reg && k in reg) out[k] = reg[k]
  return out
}

export function rawJsonOf(reg) {
  return JSON.stringify(rawRecordOf(reg), null, 2)
}
