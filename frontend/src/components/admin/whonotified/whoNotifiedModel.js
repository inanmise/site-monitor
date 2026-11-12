/**
 * "Kim bilgilendirilir?" — saf görünüm modeli (2026-09-27, ayrı sekme + yeniden tasarım).
 *
 * <p>Sunucu yanıtı ({@code GET /api/admin/recipients/simulate}, {@code EscalationService.simulateRecipients} +
 * AdminController push ayağı) DEĞİŞMEDİ; burada yalnız ekranın ihtiyacı olan biçime çevrilir:
 * <ul>
 *   <li>e-posta alıcıları = TEKİL adresler (sunucunun {@code email_total} sayımıyla aynı): takım/grup adresleri +
 *       e-postası olan ve tekrarlanmayan eskalasyon kişileri. {@code email_duplicate} kişi ayrı satır DEĞİL — aynı
 *       adresi taşıyan satıra "ayrıca eskalasyon kişisi" notu olarak eklenir (tek e-posta gider);</li>
 *   <li>push alıcıları = {@code decision === 'RECIPIENT'} VE kanal engeli yok ({@code push_channel.block_reason}); geri
 *       kalanı gerekçesiyle push kartında "Almaz" ve "bilgilendirilmeyenler"de. Kimin satırları görebileceğini sunucu
 *       söyler ({@code push_access}: FULL tüm üyeler / SELF yalnız kendi satırı / NONE hiç — {@code PushDecisionAccess});</li>
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
 *   emails: Array<{key:string,email:string,name:string,source:'group'|'team'|'contact',groupName?:string,role?:string,minLevel?:string,also:Array<{id:any,name:string}>}>,
 *   webhooks: Array<{key:string,name:string,type:string,target:string}>,
 *   push: {available:boolean, access:'FULL'|'SELF'|'NONE', accessReason:string|null, settings:'FULL'|'LIMITED'|'NONE',
 *          error:string|null, channel:object|null, rows:Array<object>, recipients:Array<object>,
 *          nonRecipients:Array<object>, self:object|null, total:number},
 *   excluded: Array<{key:string,channel:'email'|'push',name:string,sub:string,reason:string}>,
 *   counts: {email:number, push:number, pushNot:number, webhook:number, excluded:number},
 *   nobody: boolean,
 *   managersIncluded: boolean,
 *   teamContacts: 'ok'|'none'|'noneAtLevel',
 *   owners: Array<{teamId:any, role:'SY'|'UG', teamName:string, state:'ok'|'none'|'noneAtLevel'}>,
 * }}
 */
export function buildView(data) {
  const teamEmails = Array.isArray(data?.team_emails) ? data.team_emails : []
  const contacts = Array.isArray(data?.contacts) ? data.contacts : []
  const rawWebhooks = Array.isArray(data?.webhooks) ? data.webhooks : []
  const push = Array.isArray(data?.push) ? data.push : null
  // Eskalasyon kişileri YALNIZ takımın kendi kişileridir (sunucu kuralı 2026-09-28, EscalationContactScope): takımda
  // seviyeye uyan kişi yoksa kimse eklenmez — başka takımın ya da takımsız kişiye düşülmez. 'none' = takımda hiç etkin
  // kişi yok; 'noneAtLevel' = var ama hiçbiri bu seviyeyi almıyor. Eski "contacts_fallback_global" bayrağı kalktı.
  const teamContacts = data?.team_contacts_missing === true
    ? (data?.team_contacts_defined === true ? 'noneAtLevel' : 'none')
    : 'ok'
  // "Her sahip takım kendi kişisi" (2026-09-28): sertifika senaryosunda SY + UG — her takımın KENDİ kişi durumu
  // (sunucunun `owners` listesi). Eski sunucu (alan yok) → yalnız SY, yukarıdaki bayraklardan.
  const ownerState = (o) => (o?.contacts_missing === true ? (o?.contacts_defined === true ? 'noneAtLevel' : 'none') : 'ok')
  const owners = Array.isArray(data?.owners) && data.owners.length > 0
    ? data.owners.map((o) => ({
      teamId: o?.team_id ?? null, role: o?.role === 'UG' ? 'UG' : 'SY', teamName: o?.team_name || '', state: ownerState(o),
    }))
    : [{ teamId: data?.team_id ?? null, role: 'SY', teamName: data?.team_name || '', state: teamContacts }]

  const emails = []
  const byAddress = new Map()
  const excluded = []

  for (const e of teamEmails) {
    if (!lower(e?.email)) continue
    const src = parseTeamSource(e.source)
    const name = src.kind === 'group' ? src.groupName : (e.team || data?.team_name || '')
    // Zincir izi (2026-11-13): yalnız pasif hesaplara ait adres gönderimde süzülür → alıcı değil, gerekçesiyle hariç.
    if (e.inactive_only === true) {
      excluded.push({ key: `team:${lower(e.email)}`, channel: 'email', name: name || String(e.email).trim(), sub: '',
        address: String(e.email).trim(), reason: 'INACTIVE_ADDRESS' })
      continue
    }
    const row = {
      key: `team:${lower(e.email)}`, email: String(e.email).trim(),
      name, source: src.kind, groupName: src.groupName, also: [],
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
    if (c?.inactive_only === true) {
      excluded.push({ key: `contact:${c?.id}`, channel: 'email', name: c?.name || '—', sub: c?.role || '',
        address: String(c.email).trim(), reason: 'INACTIVE_ADDRESS' })
      continue
    }
    // Zamana bağlı eskalasyon adımı (2026-10-01): gecikmeli kişi ilk e-postaya GİRMEZ — kendi satırında "N dk onaysız
    // kalırsa" rozetiyle görünür, başka satıra katlanmaz ve e-posta toplamına (sunucunun email_total'ı) sayılmaz.
    const delayMinutes = Number(c?.delay_minutes) > 0 ? Number(c.delay_minutes) : null
    const host = delayMinutes == null ? byAddress.get(address) : null
    if (host) {   // sunucu "email_duplicate" der: aynı adrese tek e-posta gider
      host.also.push({ id: c.id, name: c.name || address })
      continue
    }
    const row = {
      key: `contact:${c.id}`, email: String(c.email).trim(), name: c.name || String(c.email).trim(),
      source: 'contact', role: c.role || null, minLevel: c.min_level || null, also: [], delayMinutes,
    }
    emails.push(row)
    if (delayMinutes == null) byAddress.set(address, row)
  }

  // Nöbetçi (2026-11-13 zincir izi): YÜKSEK / KRİTİK'te "nöbetçiye de bildir" açık takımın o anki nöbetçisi. Adres zincirde
  // zaten varsa aynı satıra not düşer (tek e-posta); pasif hesaba aitse hariç.
  const oncall = Array.isArray(data?.oncall) ? data.oncall : []
  for (const [i, p] of oncall.entries()) {
    const address = lower(p?.email)
    const name = p?.name || String(p?.email || '').trim() || '—'
    if (!address) continue
    if (p?.inactive_only === true) {
      excluded.push({ key: `oncall:${i}`, channel: 'email', name, sub: '', address: String(p.email).trim(), reason: 'INACTIVE_ADDRESS' })
      continue
    }
    const host = byAddress.get(address)
    if (host) { host.also.push({ id: `oncall:${i}`, name, oncall: true }); continue }
    const row = { key: `oncall:${i}`, email: String(p.email).trim(), name, source: 'oncall', also: [] }
    emails.push(row)
    byAddress.set(address, row)
  }

  // Sunucunun hariç listesi (2026-11-13): sahip takımların bu senaryoda alıcı OLMAYAN kişileri + gerekçesi
  // (TEAM_ONLY / INACTIVE_ACCOUNT / SAME_ADDRESS / LEVEL_THRESHOLD). Eski sunucu alanı göndermez → liste bugünkü gibi.
  const excludedContacts = Array.isArray(data?.excluded_contacts) ? data.excluded_contacts : []
  for (const c of excludedContacts) {
    excluded.push({
      key: `xcontact:${c?.id}`, channel: 'email', name: c?.name || '—', sub: c?.role || '',
      address: c?.email ? String(c.email).trim() : '', reason: String(c?.reason || 'UNKNOWN'),
      minLevel: c?.min_level || null,
    })
  }
  const traced = teamEmails.some((e) => e?.inactive_only === true) || contacts.some((c) => c?.inactive_only === true)
    || oncall.length > 0

  const webhooks = rawWebhooks.map((w, i) => ({
    key: `webhook:${w?.id ?? i}`, name: w?.name || '—', type: String(w?.type || '').toUpperCase(), target: w?.target || '',
    delayMinutes: Number(w?.delay_minutes) > 0 ? Number(w.delay_minutes) : null,
  }))

  const channel = buildPushChannel(data?.push_channel)
  const viewer = lower(data?.push_viewer)
  const pushRows = (push || []).map((p, i) => {
    const decision = p?.decision || 'UNKNOWN'
    // Kişi kararı sunucunun (explain); kanal engeli HERKESİ aynı anda etkiler — "alır" diyen kişi bile o an almaz.
    const effective = decision === 'RECIPIENT' && channel?.block ? channel.block : decision
    const username = p?.username && p.username !== '-' ? p.username : ''
    return {
      key: `push:${username || '-'}:${i}`,
      username,
      name: pick(p, 'display_name', 'displayName') || p?.username || '—',
      orgRole: pick(p, 'org_role', 'orgRole'),
      group: p?.group || null,
      minLevel: pick(p, 'min_level', 'minLevel'),
      // Aynı kişi (2026-10-21): karar SKIPPED_SAME_PERSON ise kararın verildiği (aynı kişinin) hesap
      samePersonOf: pick(p, 'same_person_of', 'samePersonOf') || null,
      decision,
      effective,
      receives: effective === 'RECIPIENT',
      blockedByChannel: decision === 'RECIPIENT' && effective !== 'RECIPIENT',
      isSelf: !!viewer && lower(username) === viewer,
    }
  })
  const pushRecipients = pushRows.filter((p) => p.receives)
  const pushNonRecipients = pushRows.filter((p) => !p.receives)
  for (const p of pushNonRecipients) {
    excluded.push({ key: p.key, channel: 'push', name: p.name, sub: p.username, reason: p.effective })
  }

  const rawAccess = String(data?.push_access || '').toUpperCase()
  // Eski sunucu (alan yok): satırlar geldiyse tam görünüm, gelmediyse gizli — neden bilinmiyor.
  const access = PUSH_ACCESS.includes(rawAccess) ? rawAccess : (push != null ? 'FULL' : 'NONE')

  // Sunucunun email_total'ı düşecek pasif adresleri ve nöbetçiyi bilmez: zincir izi alanları varsa sayı satırlardan (gecikmeli
  // kişi hariç — o ilk e-postaya girmez); yoksa sunucunun sayısı (bugünkü davranış).
  const emailCount = traced
    ? emails.filter((e) => e.delayMinutes == null).length
    : (Number.isFinite(Number(data?.email_total)) && data?.email_total != null ? Number(data.email_total) : emails.length)
  const counts = {
    email: emailCount, push: pushRecipients.length, pushNot: pushNonRecipients.length,
    // Gecikmeli kişinin webhook'u ilk bildirime girmez (yalnız eskalasyon adımında) — sayı anlık kanalları gösterir.
    webhook: webhooks.filter((w) => w.delayMinutes == null).length, excluded: excluded.length,
  }
  return {
    emails, webhooks, excluded, counts, teamContacts, owners,
    push: {
      available: push != null && access !== 'NONE',
      access,
      accessReason: data?.push_access_reason || null,
      settings: PUSH_SETTINGS.includes(data?.push_settings) ? data.push_settings : 'NONE',
      error: data?.push_error || null,
      channel,
      rows: pushRows,
      recipients: pushRecipients,
      nonRecipients: pushNonRecipients,
      self: access === 'SELF' ? (pushRows.find((p) => p.isSelf) || pushRows[0] || null) : null,
      total: pushRows.length,
    },
    nobody: counts.email === 0 && counts.webhook === 0 && counts.push === 0,
    managersIncluded: data?.managers_included !== false,
  }
}

/** Sunucunun görünürlük düzeyleri ({@code PushDecisionAccess}): tüm üyeler / yalnız kendi satırı / hiç. */
export const PUSH_ACCESS = ['FULL', 'SELF', 'NONE']
/** Ayarlar → Webhook Bildirimleri erişimi: FULL (global yönetici), LIMITED (kapsamlı müdür — adres hariç), NONE. */
export const PUSH_SETTINGS = ['FULL', 'LIMITED', 'NONE']
/** Kanal düzeyi engel kodları ({@code UserPushService.scenarioChannel}) — kişi kararlarından ayrı sözlük (`wn.pushReason.*`). */
export const PUSH_CHANNEL_BLOCKS = ['CHANNEL_DISABLED', 'NOT_CONFIGURED', 'SKIPPED_TYPE_OFF', 'SKIPPED_TEAM_OFF', 'SKIPPED_QUIET_HOURS']

/**
 * Push karar kodunun sade metni — kişi kodları Ayarlar → Webhook → "Kim alır?" ile AYNI anahtarlar
 * (`userpush.decision.*`), kanal kodları ayrı sözlük (`wn.pushReason.*`); bilinmeyen kod ham görünür.
 */
export function pushReasonText(t, code) {
  const key = PUSH_CHANNEL_BLOCKS.includes(code) ? `wn.pushReason.${code}` : `userpush.decision.${code}`
  const v = t(key)
  return v === key ? code : v
}

/** Sunucunun `push_channel` nesnesi → ekran biçimi; yoksa (eski sunucu / okunamadı) null. */
export function buildPushChannel(c) {
  if (!c || typeof c !== 'object') return null
  const block = PUSH_CHANNEL_BLOCKS.includes(c.block_reason) ? c.block_reason : null
  return {
    enabled: c.enabled !== false,
    configured: c.configured !== false,
    teamEnabled: c.team_enabled !== false,
    types: Array.isArray(c.types) ? c.types : [],
    disabledTypes: Array.isArray(c.disabled_types) ? c.disabled_types : [],
    quietStart: c.quiet_start || null,
    quietEnd: c.quiet_end || null,
    quietMinLevel: c.quiet_min_level || null,
    quietActive: !!c.quiet_active,
    quietBlocksLevel: !!c.quiet_blocks_level,
    block,
  }
}

/** Kişi satırı süzgeci — 'all' | 'yes' (alacaklar) | 'no' (almayacaklar) + ad/kullanıcı adı araması. */
export function filterPushRows(rows, filter, query) {
  const q = lower(query)
  return (rows || []).filter((r) => {
    if (filter === 'yes' && !r.receives) return false
    if (filter === 'no' && r.receives) return false
    if (!q) return true
    return lower(r.name).includes(q) || lower(r.username).includes(q)
  })
}

// ── Dışa aktarım + seviye karşılaştırması (2026-11-13, "Kim bilgilendirilir?" zincir izi) ─────────────────────────────

/**
 * Senaryonun alıcı listesi → CSV satırları (başlık dahil). Dahil olanlar (e-posta / webhook / push) ve hariç kalanlar
 * gerekçesiyle; metinler çağıranın dilinde (`labels`): kanal adları, kaynak etiketleri, durum ve gerekçe metni.
 * @param {ReturnType<typeof buildView>} view
 * @param {{header:string[], channel:(c:string)=>string, source:(s:string)=>string, yes:string, no:string,
 *          later:(m:number)=>string, reason:(code:string, channel:string)=>string}} labels
 */
export function recipientCsvRows(view, labels) {
  const rows = [labels.header]
  for (const e of view.emails) {
    const notes = (e.also || []).map((a) => a.name).join(', ')
    rows.push([labels.channel('email'), e.name || '', e.email, labels.source(e.source),
      e.delayMinutes != null ? labels.later(e.delayMinutes) : labels.yes, notes])
  }
  for (const w of view.webhooks) {
    rows.push([labels.channel('webhook'), w.name, w.target, labels.source('contact'),
      w.delayMinutes != null ? labels.later(w.delayMinutes) : labels.yes, ''])
  }
  if (view.push.available) {
    for (const p of view.push.recipients) rows.push([labels.channel('push'), p.name, p.username, p.group || '', labels.yes, ''])
  }
  for (const x of view.excluded) {
    rows.push([labels.channel(x.channel), x.name, x.address || x.sub || '', '', labels.no, labels.reason(x.reason, x.channel)])
  }
  return rows
}

/**
 * Seviyelere göre karşılaştırma: her seviyenin görünümünden (null = okunamadı) alıcı birleşimi. Hücre: 'yes' (alır),
 * 'later' (gecikmeli adım), 'no' (değerlendirildi, almaz — gerekçe `reason`), 'unknown' (o seviye okunamadı).
 * Seviyeler arasında farklılaşan satırlar önce; sonra kanal, ad.
 * @param {Record<string, ReturnType<typeof buildView>|null>} byLevel
 */
export function compareLevels(byLevel, levels = LEVELS) {
  const rows = new Map()
  const ensure = (key, channel, name, address) => {
    if (!rows.has(key)) rows.set(key, { key, channel, name, address, cells: {} })
    return rows.get(key)
  }
  for (const lv of levels) {
    const v = byLevel[lv]
    if (!v) continue
    for (const e of v.emails) {
      ensure(`email:${lower(e.email)}`, 'email', e.name || e.email, e.email).cells[lv] = { state: e.delayMinutes != null ? 'later' : 'yes' }
    }
    for (const w of v.webhooks) {
      ensure(`webhook:${lower(w.name)}|${w.target}`, 'webhook', w.name, w.target).cells[lv] = { state: w.delayMinutes != null ? 'later' : 'yes' }
    }
    if (v.push.available) {
      for (const p of v.push.recipients) ensure(`push:${lower(p.username)}`, 'push', p.name, p.username).cells[lv] = { state: 'yes' }
    }
    for (const x of v.excluded) {
      const key = x.channel === 'push' ? `push:${lower(x.sub)}` : `email:${lower(x.address || x.name)}`
      const r = ensure(key, x.channel, x.name, x.address || x.sub || '')
      if (!r.cells[lv]) r.cells[lv] = { state: 'no', reason: x.reason }
    }
  }
  const out = [...rows.values()].map((r) => {
    for (const lv of levels) if (!r.cells[lv]) r.cells[lv] = { state: byLevel[lv] ? 'no' : 'unknown' }
    const states = new Set(levels.map((lv) => r.cells[lv].state).filter((s) => s !== 'unknown'))
    return { ...r, varies: states.size > 1 }
  })
  const CH = { email: 0, webhook: 1, push: 2 }
  out.sort((a, b) => (Number(b.varies) - Number(a.varies)) || ((CH[a.channel] ?? 9) - (CH[b.channel] ?? 9))
    || String(a.name).localeCompare(String(b.name), 'tr'))
  return out
}
