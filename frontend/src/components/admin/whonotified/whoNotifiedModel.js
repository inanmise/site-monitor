/**
 * "Kim bilgilendirilir?" — saf görünüm modeli (2026-09-27, ayrı sekme + yeniden tasarım).
 *
 * <p>Sunucu yanıtı ({@code GET /api/admin/recipients/simulate}, {@code EscalationService.simulateRecipients} +
 * AdminController push ayağı) DEĞİŞMEDİ; burada yalnız ekranın ihtiyacı olan biçime çevrilir:
 * <ul>
 *   <li>e-posta alıcıları = TEKİL adresler (sunucunun {@code email_total} sayımıyla aynı): takım/grup adresleri +
 *       e-postası olan ve tekrarlanmayan eskalasyon kişileri. {@code email_duplicate} kişi ayrı satır DEĞİL — aynı
 *       adresi taşıyan satıra "ayrıca eskalasyon kişisi" notu olarak eklenir (tek e-posta gider);</li>
 *   <li>push alıcıları = {@code decision === 'RECIPIENT'}; geri kalanı gerekçesiyle "bilgilendirilmeyenler";</li>
 *   <li>e-postası olmayan eskalasyon kişisi de "bilgilendirilmeyenler"e düşer (webhook'u yine çalışabilir).</li>
 * </ul>
 * Karar mantığı KOPYALANMAZ: kim alır / kim almaz sunucunun verdiği karardır (gerçek gönderimle aynı kod yolu).
 */

export const LEVELS = ['WARNING', 'HIGH', 'CRITICAL']
export const KINDS = ['CERT', 'MONITOR']
export const DEFAULT_LEVEL = 'HIGH'
export const DEFAULT_KIND = 'CERT'

/** Seviye rengi (nokta/rozet) — Eskalasyon Kişileri tablosundaki seviye rozetleriyle aynı ton. */
export const LEVEL_DOT = { WARNING: 'bg-amber-500', HIGH: 'bg-orange-600', CRITICAL: 'bg-red-700' }

/** URL'den gelen değerler güvenilmez (elle yazılmış bağlantı): bilinmeyen seviye/tür varsayılana, bozuk id boşa iner. */
export const normLevel = (v) => (LEVELS.includes(v) ? v : DEFAULT_LEVEL)
export const normKind = (v) => (KINDS.includes(v) ? v : DEFAULT_KIND)
export const normId = (v) => (/^[1-9][0-9]*$/.test(String(v ?? '')) ? String(v) : '')

const lower = (s) => String(s ?? '').trim().toLowerCase()
/** Sunucu yanıtları snake_case; eski/karışık yükler için camelCase yedeği. */
const pick = (o, snake, camel) => (o?.[snake] ?? o?.[camel] ?? null)

/**
 * Sunucunun takım satırı kaynak etiketi — {@code NotificationGroupService.Override.label()} "Grup: X", aksi hâlde
 * "Takım maili" (yalnız Türkçe gelir). Ekran kendi i18n metnini kurar; bilinmeyen etiket takım adresi sayılır.
 */
export function parseTeamSource(source) {
  const s = String(source ?? '').trim()
  const m = /^Grup:\s*(.+)$/.exec(s)
  return m ? { kind: 'group', groupName: m[1].trim() } : { kind: 'team', groupName: null }
}

/**
 * @param {object} data simülasyon yanıtının `data` alanı
 * @returns {{
 *   emails: Array<{key:string,email:string,name:string,source:'group'|'team'|'contact'|'globalContact',groupName?:string,role?:string,minLevel?:string,also:Array<{id:any,name:string}>}>,
 *   webhooks: Array<{key:string,name:string,type:string,target:string}>,
 *   push: {available:boolean, error:string|null, recipients:Array<object>, total:number},
 *   excluded: Array<{key:string,channel:'email'|'push',name:string,sub:string,reason:string}>,
 *   counts: {email:number, push:number, webhook:number, excluded:number},
 *   nobody: boolean,
 *   managersIncluded: boolean,
 *   fallbackGlobal: boolean,
 * }}
 */
export function buildView(data) {
  const teamEmails = Array.isArray(data?.team_emails) ? data.team_emails : []
  const contacts = Array.isArray(data?.contacts) ? data.contacts : []
  const rawWebhooks = Array.isArray(data?.webhooks) ? data.webhooks : []
  const push = Array.isArray(data?.push) ? data.push : null
  const fallbackGlobal = !!data?.contacts_fallback_global

  const emails = []
  const byAddress = new Map()
  const excluded = []

  for (const e of teamEmails) {
    if (!lower(e?.email)) continue
    const src = parseTeamSource(e.source)
    const row = {
      key: `team:${lower(e.email)}`, email: String(e.email).trim(),
      name: src.kind === 'group' ? src.groupName : (e.team || data?.team_name || ''),
      source: src.kind, groupName: src.groupName, also: [],
    }
    emails.push(row)
    byAddress.set(lower(e.email), row)
  }

  for (const c of contacts) {
    const address = lower(c?.email)
    if (!address) {
      excluded.push({ key: `contact:${c?.id}`, channel: 'email', name: c?.name || '—', sub: c?.role || '', reason: 'NO_EMAIL' })
      continue
    }
    const host = byAddress.get(address)
    if (host) {   // sunucu "email_duplicate" der: aynı adrese tek e-posta gider
      host.also.push({ id: c.id, name: c.name || address })
      continue
    }
    const row = {
      key: `contact:${c.id}`, email: String(c.email).trim(), name: c.name || String(c.email).trim(),
      source: fallbackGlobal ? 'globalContact' : 'contact', role: c.role || null, minLevel: c.min_level || null, also: [],
    }
    emails.push(row)
    byAddress.set(address, row)
  }

  const webhooks = rawWebhooks.map((w, i) => ({
    key: `webhook:${w?.id ?? i}`, name: w?.name || '—', type: String(w?.type || '').toUpperCase(), target: w?.target || '',
  }))

  const pushRows = (push || []).map((p, i) => ({
    key: `push:${p?.username || '-'}:${i}`,
    username: p?.username && p.username !== '-' ? p.username : '',
    name: pick(p, 'display_name', 'displayName') || p?.username || '—',
    orgRole: pick(p, 'org_role', 'orgRole'),
    group: p?.group || null,
    minLevel: pick(p, 'min_level', 'minLevel'),
    decision: p?.decision || 'UNKNOWN',
  }))
  const pushRecipients = pushRows.filter((p) => p.decision === 'RECIPIENT')
  for (const p of pushRows) {
    if (p.decision !== 'RECIPIENT') {
      excluded.push({ key: p.key, channel: 'push', name: p.name, sub: p.username, reason: p.decision })
    }
  }

  const emailCount = Number.isFinite(Number(data?.email_total)) && data?.email_total != null ? Number(data.email_total) : emails.length
  const counts = { email: emailCount, push: pushRecipients.length, webhook: webhooks.length, excluded: excluded.length }
  return {
    emails, webhooks, excluded, counts, fallbackGlobal,
    push: { available: push != null, error: data?.push_error || null, recipients: pushRecipients, total: pushRows.length },
    nobody: counts.email === 0 && counts.webhook === 0 && counts.push === 0,
    managersIncluded: data?.managers_included !== false,
  }
}
