// Olay & Hata Geçmişi — "Bul ve Raporla" paket 3 (2026-11-26). Gerçek tarayıcıda 390 (dokunmatik) / 768 (dokunmatik) / 1280:
//  1) arama tüm alanlarda: çözüm adımlarındaki bir sözcük ve "#9" kayıt numarası satırı bulur, satır "Eşleşme: <alan>" der,
//  2) gelişmiş süzgeçler (postmortem, etiket) + sıralama listeye gider; çipler çıkar; eğilim süzgeçli istenir ve KPI'lar
//     "süzgece göre" notunu taşır; ayrıntılı döküm (MTTD / ortanca MTTR / müşteri / kırılımlar) sığar,
//  3) dışa aktarım: CSV sunucudan indirilir (BOM + başlık), Excel sunucunun JSON'undan tarayıcıda kurulur (gerçek .xlsx açılır),
//  4) ayrıntıdan "Özeti indir" (Markdown, kaydın TAZE hâli) ve "Yazdır" (yalnız yazdırma görünümü basılır),
//  5) seçenekleri yönet: iki kanal değeri tek ada birleştirilir (onay → PUT gövdesi),
//  6) durumlar: liste okunamadı + yeniden dene, eşleşmeyen arama, yetkisiz sayfa / seçenek düğmesi yok, eski sunucu (gelişmiş
//     süzgeç bandı, dışa aktarım "bu sürümde yok", döküm "—", seçenek penceresi "bu sürümde yok"), Excel 429,
//  7) seçenek adında sunucunun alan hatası alanın altında, yanıtsız istek (503) "sonuç bilinmiyor" + yeniden okuma, sonra başarı;
//     komut paletinden olay kaydı → ayrıntı açılır,
//  8) bağımsız inceleme (2026-11-26): başlık eylemleri dokunmatikte ≥ 40 px; KPI kartı aramayı korur; döküm satırları (takım /
//     kanal / kategori / önem) listeyle AYNI kümeyi süzer (liste sayısı = satır sayısı; kanal tam öğe, listede olmayan takım
//     adıyla); gelişmiş süzgeçler (alan adı, problem tipi, hata kodu, yalnız benimkiler) + bütün sıralamalar + yeniden yüklemede
//     sıralama; dışa aktarım kesik bildirimi, 429 (kişi / dakika / pod), eski sunucu Excel 404 / 405, indirme başlatılamadı;
//     özet okunamadı; görselli yazdırma görseli bekler (en çok 5 sn); seçenek yönetimi 403 / okuma hatası / takım-tür değişimi /
//     kesik liste / genel seçenek kaldı; süzgeçli günlük grafik + kayıt sınırı notu; eğilim yenileme hatası şeridi; eski sunucu
//     eğilim notu; komut paletinde "#9" ve başka sekmeden olay kaydı.
// Bant / açılır pencere / ayrıntı ekrana sığar, metin kırpılmaz, dokunmatik hedefler ≥ 40 px, sayfa yatay kaymaz.
import { test, expect } from '@playwright/test'
import { readFileSync } from 'node:fs'
import { unzipSync, strFromU8 } from 'fflate'

const NOW = new Date('2026-11-26T09:00:00Z')
const TEAM = 'Dijital Kanallar Mobil Bankacılık ve Uzun Adlı Uygulama Geliştirme Ekibi'
const FULL = Object.freeze({
  id: 9, version: 2, title: 'Ödeme servisinde gecikme — kart işlemleri yavaş yanıt veriyor',
  occurred_at: '2026-11-19T07:00:00', detected_at: '2026-11-19T07:10:00', resolved_at: '2026-11-19T09:00:00',
  severity: 'HIGH', status: 'RESOLVED', category: 'APPLICATION', team_id: 5, team_name: TEAM,
  error_code: 'E-500', function_code: '', channel_code: '', service: 'odeme.example.com', channel: 'Mobil Şube',
  affected_services: 'Kart ödemeleri', problem_types: 'Konfigürasyon', affected_app: '', affected_systems: '',
  affected_customers: 1200, affected_transactions: null, sla_breached: true, error_budget_burn_pct: null, duration_minutes: 120,
  runbook_url: null, tags: 'prod, odeme', tech_error_count: null, outage_related: null, outage_id: null,
  created_by: 'demo', created_at: '2026-11-19T09:20:00', updated_by: 'demo', updated_at: '2026-11-19T09:20:00', can_edit: true,
  rca_summary: 'Bağlantı havuzu tükendi.', description: 'Kart işlemleri yavaşladı.',
  resolution_steps: 'Bağlantı havuzu büyütüldü, uygulama yeniden başlatıldı.', business_impact: 'Ödemeler gecikti.',
})
const MD_KEYS = ['rca_summary', 'description', 'resolution_steps', 'business_impact']
const CHANNEL_A = 'İnternet Şubesi Kurumsal Ödemeler ve Uzun Adlı Alt Kanal'
const CHANNEL_B = 'Internet Subesi (eski yazım)'
const SHARED_TEAM = 'Paylaşımla Görünen Kurumsal Ödeme Sistemleri ve Uzun Adlı Destek Ekibi'
const SECOND_TEAM = 'İkinci Takım — Kurumsal Kanallar ve Uzun Adlı Operasyon Birimi'
const LONG_DOMAIN = 'kurumsal-odeme-servisleri.uzun-alt-alan-adi.example.com.tr'
/** 1×1 PNG (yazdırmanın görsel beklemesi için). */
const PNG = Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==', 'base64')
/** Günlük trend (view=daily) yanıtı: 30 günde 3 olay, 0 kritik. */
const DAILY = [
  { day: '2026-11-25', count: 2, critical: 0, high: 2, medium: 0, low: 0 },
  { day: '2026-11-20', count: 1, critical: 0, high: 0, medium: 1, low: 0 },
]

let listQueries = []
let trendQueries = []
let exportQueries = []
let optionPuts = []
let detailGets = 0
let usageGets = 0
let usageQueries = []
let searchQueries = []
/** Senaryo anahtarları (test sırasında değiştirilebilir — yol işleyicisi istek anında okur). */
let MODE = {}

function summaryRow(extra = {}) {
  const out = { ...FULL, markdown_omitted: true, ...extra }
  for (const k of MD_KEYS) delete out[k]
  return out
}

const EXTRA_FILTER_KEYS = ['q', 'service', 'service_exact', 'channel', 'channel_exact', 'category', 'team_id', 'problem_type',
  'error_code', 'tag', 'created_by', 'postmortem']

/**
 * Veri kümesi kipi (inceleme 2026-11-26): liste ve eğilim AYNI üç kayıttan süzülür — döküm satırına tıklayınca liste sayısı
 * satırın sayısına eşit olmalı. Kanal TAM öğe (`channel_exact`) ile alt dize (`channel`) farklı sayar: "Mobil Şube" iki
 * kayıtta tam öğe, üç kayıtta alt dize. Takım 7 süzgeç seçicisinde yok (paylaşımla görünen takım).
 */
function dataset() {
  return [
    summaryRow(),
    summaryRow({ id: 11, title: 'Kurumsal kanalda giriş hatası', channel: 'Mobil Şube Kurumsal', team_id: 7, team_name: SHARED_TEAM,
      category: 'NETWORK', severity: 'MEDIUM' }),
    summaryRow({ id: 12, title: 'ATM nakit çekim hatası', channel: 'Mobil Şube, ATM', team_id: 7, team_name: SHARED_TEAM,
      category: 'NETWORK', severity: 'HIGH' }),
  ]
}
const csvItems = (v) => String(v || '').split(',').map((s) => s.trim()).filter(Boolean)
const lc = (s) => String(s ?? '').toLocaleLowerCase('tr')
function applyFilters(rows, sp) {
  return rows.filter((r) => {
    const q = (sp.get('q') || '').trim()
    if (q && !lc(r.title).includes(lc(q)) && q !== `#${r.id}`) return false
    if (sp.get('team_id') && String(r.team_id) !== sp.get('team_id')) return false
    if (sp.get('category') && r.category !== sp.get('category')) return false
    if (sp.get('severity') && r.severity !== sp.get('severity')) return false
    if (sp.get('channel') && !lc(r.channel).includes(lc(sp.get('channel')))) return false
    if (sp.get('channel_exact') && !csvItems(r.channel).some((c) => lc(c) === lc(sp.get('channel_exact')))) return false
    return true
  })
}
function trendsOf(rows) {
  const count = (key) => rows.reduce((m, r) => ({ ...m, [r[key]]: (m[r[key]] || 0) + 1 }), {})
  const byChannel = {}
  for (const r of rows) for (const c of csvItems(r.channel)) byChannel[c] = (byChannel[c] || 0) + 1
  const teams = new Map()
  for (const r of rows) {
    const tm = teams.get(r.team_id) || { team_id: r.team_id, team_name: r.team_name, count: 0 }
    tm.count += 1
    teams.set(r.team_id, tm)
  }
  return {
    summary: { total: rows.length, open: 0, critical: 0, sla_breached: rows.length, mttr_minutes: 120, mttr_median_minutes: 120,
      mttr_sample: rows.length, mttd_minutes: 10, mttd_median_minutes: 10, mttd_sample: rows.length,
      customers_sum: 1200 * rows.length, customers_sample: rows.length },
    by_severity: count('severity'), by_status: count('status'), by_category: count('category'), by_channel: byChannel,
    by_team: [...teams.values()], daily: [],
  }
}

async function mock(page, opts = {}) {
  listQueries = []; trendQueries = []; exportQueries = []; optionPuts = []; detailGets = 0; usageGets = 0; usageQueries = []; searchQueries = []
  MODE = { ...opts }
  await page.addInitScript(() => {
    try {
      localStorage.setItem('sm.tour', JSON.stringify({ status: 'dismissed', version: 99 }))
      localStorage.setItem('site-monitor-lang', 'tr')
      sessionStorage.setItem('sm.gaps.prompted', 'demo')
    } catch { /* yoksay */ }
    window.__printed = 0
    window.print = () => { window.__printed += 1 }
  })
  await page.route((u) => new URL(u).pathname.startsWith('/api/'), async (route) => {
    const req = route.request()
    const url = new URL(req.url())
    const p = url.pathname
    const sp = url.searchParams
    let body = { success: true, data: [] }
    if (p === '/api/me') body = { success: true, username: 'demo', display_name: 'Demo Kullanıcı', system_role: 'ADMIN', global_admin: true, team_id: 5, team_ids: [5], tour: { status: 'dismissed', version: 99 } }
    else if (p === '/api/me/permissions') {
      body = { success: true, data: MODE.noView ? { 'incidents.view': { view: false } }
        : MODE.noManage ? { 'incidents.view': { view: true }, 'incidents.manage': { edit: false } }
          : { 'incidents.view': { view: true }, 'incidents.manage': { edit: true } } }
    } else if (p === '/api/admin/teams') body = { success: true, data: [{ id: 5, name: TEAM }] }
    else if (p === '/api/incidents/teams') {
      if (MODE.old) {
        await route.fulfill({ status: 404, contentType: 'application/json', body: JSON.stringify({ success: false, code: 'NOT_FOUND', error: 'İstenen API adresi bulunamadı.' }) })
        return
      }
      body = { success: true, data: [{ id: 5, name: TEAM, active: true, can_manage_options: !MODE.noManage }] }
      if (MODE.twoTeams) body.data.push({ id: 6, name: SECOND_TEAM, active: true, can_manage_options: true })
    } else if (p.startsWith('/api/incidents/images/')) {
      // Görsel: test istenene dek bekletebilir (yazdırma görselin inmesini bekler, en çok 5 sn)
      const hold = MODE.imageHolds?.[p.split('/').pop()]
      if (hold) await hold
      try { await route.fulfill({ status: 200, contentType: 'image/png', body: PNG }) } catch { /* sayfa kapandı */ }
      return
    } else if (p === '/api/incidents/options' && req.method() === 'GET') {
      const type = sp.get('type')
      body = { success: true, data: type === 'DOMAIN' ? ['odeme.example.com', LONG_DOMAIN]
        : type === 'ERROR_CODE' ? ['E-500', 'E-503'] : type === 'CHANNEL' ? ['Mobil Şube'] : [] }
    } else if (p === '/api/search') {
      searchQueries.push(sp.get('q'))
      body = { success: true, data: [{ kind: 'incident', id: '9', label: FULL.title, sub: '#9 · E-500 · odeme.example.com', team_id: 5,
        team_name: TEAM, tab: 'incident-history', params: { incident: '9' } }] }
    } else if (p === '/api/incidents' && req.method() === 'GET') {
      listQueries.push(Object.fromEntries(sp))
      if (MODE.listFail > 0) {
        MODE.listFail -= 1
        await route.fulfill({ status: 500, contentType: 'application/json', body: JSON.stringify({ success: false, code: 'INTERNAL_ERROR', error: 'Sunucuda beklenmeyen bir hata oluştu; birkaç saniye sonra yeniden deneyin.' }) })
        return
      }
      let data
      if (MODE.dataset) data = applyFilters(dataset(), sp)
      else {
        const q = (sp.get('q') || '').trim()
        let row = summaryRow()
        if (q === '#9' || q === '9') row = summaryRow({ match_field: 'id' })
        else if (q.toLocaleLowerCase('tr').includes('havuz')) row = summaryRow({ match_field: 'resolution_steps' })
        else if (q) row = null
        data = row ? [row] : []
      }
      body = { success: true, data, total: data.length, page: 0, size: 20 }
      if (!MODE.old) body.ext_filters = true
    } else if (p === '/api/incidents/trends') {
      trendQueries.push(Object.fromEntries(sp))
      const filtered = EXTRA_FILTER_KEYS.some((k) => sp.get(k))
      if (MODE.trendFail) {
        await route.fulfill({ status: 503, contentType: 'application/json', body: JSON.stringify({ success: false, code: 'DB_UNAVAILABLE', error: 'Veritabanına şu anda ulaşılamıyor; birkaç saniye sonra yeniden deneyin.' }) })
        return
      }
      if (sp.get('view') === 'daily' && !MODE.old) {
        // Yalın günlük seri (view=daily) — özet / kırılım yok
        await route.fulfill({ status: 200, contentType: 'application/json',
          body: JSON.stringify({ success: true, data: { daily: DAILY, filtered, daily_only: true }, ext_filters: true }) })
        return
      }
      if (MODE.dataset) {
        await route.fulfill({ status: 200, contentType: 'application/json',
          body: JSON.stringify({ success: true, data: { ...trendsOf(applyFilters(dataset(), sp)), filtered }, ext_filters: true }) })
        return
      }
      if (MODE.old) {
        body = { success: true, data: { summary: { total: 1, open: 0, critical: 0, sla_breached: 1, mttr_minutes: 120, mttr_sample: 1 },
          by_severity: { HIGH: 1 }, by_status: { RESOLVED: 1 }, by_category: { APPLICATION: 1 }, by_channel: { 'Mobil Şube': 1 }, daily: [] } }
        await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(body) })
        return
      }
      body = { success: true, data: {
        summary: { total: 1, open: 0, critical: 0, sla_breached: 1, mttr_minutes: 120, mttr_median_minutes: 120, mttr_sample: 1,
          mttd_minutes: 10, mttd_median_minutes: 10, mttd_sample: 1, customers_sum: 1200, customers_sample: 1 },
        by_severity: { HIGH: 1 }, by_status: { RESOLVED: 1 }, by_category: { APPLICATION: 1 }, by_channel: { 'Mobil Şube': 1 },
        by_team: [{ team_id: 5, team_name: TEAM, count: 1 }], daily: [], filtered, capped: MODE.capped === true,
        ...(MODE.capped ? { rows_read: 20000 } : {}),
      } }
      // Yeni sunucu eğilimde süzgeçleri uyguladığını söyler; `oldTrend` = eski pod (işaret yok → "süzülmüyor" notu)
      if (!MODE.oldTrend) body.ext_filters = true
    } else if (p === '/api/incidents/export') {
      exportQueries.push(Object.fromEntries(sp))
      const oldStatus = MODE.old ? 404 : MODE.oldExport
      if (oldStatus) {
        await route.fulfill({ status: oldStatus, contentType: 'application/json', body: JSON.stringify(oldStatus === 405
          ? { success: false, code: 'METHOD_NOT_ALLOWED', error: 'Yöntem desteklenmiyor.' }
          : { success: false, code: 'NOT_FOUND', error: 'İstenen API adresi bulunamadı.' }) })
        return
      }
      // 429: kişi başına aynı anda tek / dakikalık sınır / pod dolu (sunucunun iletileri)
      const busy = MODE.exportBusy === true ? (sp.get('format') === 'json' ? 'person' : null) : MODE.exportBusy
      if (busy) {
        const b = {
          person: { code: 'EXPORT_BUSY', retry: 5, error: 'Önceki dışa aktarımınız hâlâ hazırlanıyor; kişi başına aynı anda tek dışa aktarım yapılabilir. Bitince yeniden deneyin.' },
          minute: { code: 'RATE_LIMITED', retry: 42, error: 'Dışa aktarım sınırına ulaşıldı: kişi başına dakikada en çok 6 dışa aktarım. 42 saniye sonra yeniden deneyin.' },
          pod: { code: 'EXPORT_BUSY', retry: 10, error: 'Sunucuda şu anda başka dışa aktarımlar hazırlanıyor; 10 saniye sonra yeniden deneyin.' },
        }[busy]
        await route.fulfill({ status: 429, contentType: 'application/json', headers: { 'Retry-After': String(b.retry) },
          body: JSON.stringify({ success: false, code: b.code, retry_after_seconds: b.retry, error: b.error }) })
        return
      }
      if (sp.get('format') === 'csv') {
        const csv = '﻿id,title,severity,resolution_steps\r\n9,"' + FULL.title + '",HIGH,"' + FULL.resolution_steps + '"\r\n'
        await route.fulfill({ status: 200, contentType: 'text/csv; charset=utf-8', body: csv,
          headers: { 'Content-Disposition': 'attachment; filename="olay-gecmisi-20261126-1200.csv"',
            'X-Export-Truncated': MODE.exportTruncated ? 'true' : 'false', 'X-Export-Max-Rows': '10000' } })
        return
      }
      body = { success: true, data: {
        columns: ['id', 'title', 'severity', 'team', 'resolution_steps'],
        rows: [{ id: 9, title: FULL.title, severity: 'HIGH', team: TEAM, resolution_steps: FULL.resolution_steps }],
        truncated: MODE.exportTruncated === true, max_rows: MODE.exportTruncated ? 5000 : 10000, text_truncated: false,
      } }
    } else if (p === '/api/incidents/9' && req.method() === 'GET') {
      detailGets += 1
      if (MODE.detailFail) {
        await route.fulfill({ status: 503, contentType: 'application/json', body: JSON.stringify({ success: false, code: 'DB_UNAVAILABLE', error: 'Veritabanına şu anda ulaşılamıyor; birkaç saniye sonra yeniden deneyin.' }) })
        return
      }
      body = { success: true, data: { ...FULL,
        ...(MODE.withImage ? { description: `Kart işlemleri yavaşladı.\n\n![ekran görüntüsü](/api/incidents/images/${MODE.withImage})` } : {}) } }
    } else if (p === '/api/incidents/options/usage') {
      usageGets += 1
      usageQueries.push(Object.fromEntries(sp))
      if (MODE.old) {
        await route.fulfill({ status: 405, contentType: 'application/json', body: JSON.stringify({ success: false, code: 'METHOD_NOT_ALLOWED', error: 'Yöntem desteklenmiyor.' }) })
        return
      }
      if (MODE.forbiddenTeam && sp.get('team_id') === MODE.forbiddenTeam) {
        await route.fulfill({ status: 403, contentType: 'application/json', body: JSON.stringify({ success: false, code: 'FORBIDDEN', error: 'Bu takımın seçeneklerini yönetme yetkiniz yok.' }) })
        return
      }
      if (MODE.usageFail > 0) {
        MODE.usageFail -= 1
        await route.fulfill({ status: 500, contentType: 'application/json', body: JSON.stringify({ success: false, code: 'INTERNAL_ERROR', error: 'Sunucuda beklenmeyen bir hata oluştu; birkaç saniye sonra yeniden deneyin.' }) })
        return
      }
      const values = sp.get('type') === 'ERROR_CODE'
        ? [{ value: 'E-500', records: 3, team_option: true, global_option: false }, { value: 'E-503', records: 1, team_option: false, global_option: false }]
        : [
          { value: CHANNEL_A, records: 4, team_option: true, global_option: false },
          { value: CHANNEL_B, records: 2, team_option: false, global_option: true },
          { value: 'Mobil Şube', records: 1, team_option: true, global_option: false },
        ]
      body = { success: true, data: { type: sp.get('type'), team_id: Number(sp.get('team_id')), values,
        ...(MODE.usageTruncated ? { truncated: true, rows_read: 20000, max_rows: 20000 } : { truncated: false, rows_read: 7, max_rows: 20000 }) } }
    } else if (p === '/api/incidents/options' && req.method() === 'PUT') {
      const sent = JSON.parse(req.postData() || '{}')
      optionPuts.push(sent)
      if (MODE.renameFlow && optionPuts.length === 1) {
        const msg = 'Kayıt #7 için yeni değer alanın sınırını (500 karakter) aşıyor. Daha kısa bir ad yazın; hiçbir kayıt değiştirilmedi.'
        await route.fulfill({ status: 400, contentType: 'application/json', body: JSON.stringify({ success: false, code: 'VALIDATION_FAILED', field: 'to', fields: { to: msg }, error: msg }) })
        return
      }
      if (MODE.renameFlow && optionPuts.length === 2) {
        await route.fulfill({ status: 503, contentType: 'text/html', body: '<html><body>Service Unavailable</body></html>' })
        return
      }
      body = MODE.renameFlow
        ? { success: true, data: { mode: 'rename', records: 1, to: sent.to, global_kept: [] } }
        : { success: true, data: { mode: 'merge', records: 6, to: sent.to, global_kept: [CHANNEL_B] } }
    }
    await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(body) })
  })
}

/** Görünür etkileşimli öğelerden 40 px'in altında kalanlar (dokunmatik): yükseklik, ya da ::after vuruş alanı. */
function smallTargets(root) {
  const out = []
  for (const b of root.querySelectorAll('button, select, input, a[href]')) {
    const r = b.getBoundingClientRect()
    if (r.width === 0 || r.height === 0 || b.disabled || b.type === 'hidden') continue
    let h = r.height
    const after = getComputedStyle(b, '::after')
    if (after.position === 'absolute' && after.height && after.height !== 'auto') h = Math.max(h, parseFloat(after.height) || 0)
    if (h < 39.5) out.push(`"${(b.getAttribute('aria-label') || b.textContent || '').trim().slice(0, 30)}" ${Math.round(h)}`)
  }
  return out
}

/** Gerçekten kırpılan / taşan metin (tek düğümlü yaprak öğeler: içerik kutusundan geniş); görünmez öğeler (≤ 2 px) sayılmaz.
 *  Yalnız `text-overflow: ellipsis` biçemi kırpma sayılmaz — içerik sığıyorsa üç nokta görünmez. */
function clipped(root) {
  return [...root.querySelectorAll('span, p, label, button, div, a, li, td, th')]
    .filter((n) => { const r = n.getBoundingClientRect(); return r.width > 2 && r.height > 2 })
    .filter((n) => n.childElementCount === 0 && n.textContent.trim().length > 3 && n.scrollWidth > n.clientWidth + 1)
    .map((n) => n.textContent.slice(0, 40))
}

/** Öğenin içinden sağa taşan alt öğeler. */
function overflow(el) {
  const lim = el.getBoundingClientRect().right + 1
  if (el.scrollWidth <= el.clientWidth + 1) return []
  return [...el.querySelectorAll('*')].filter((n) => n.getBoundingClientRect().right > lim)
    .map((n) => `${n.tagName}.${(n.getAttribute('data-slot') || n.className || '').toString().slice(0, 60)} ${Math.round(n.getBoundingClientRect().right)}>${Math.round(lim)}`).slice(0, 8)
}

/** Açılış / kayma animasyonları bitene dek bekler; sonsuz animasyonlar hariç, ≤ 3 sn. */
async function settle(page) {
  await page.evaluate(() => Promise.race([
    Promise.all(document.getAnimations()
      .filter((a) => a.effect?.getComputedTiming?.().iterations !== Infinity)
      .map((a) => a.finished.catch(() => {}))),
    new Promise((r) => setTimeout(r, 3000)),
  ]))
}

/**
 * "Dışa aktar" menüsünü açar ve `fmt` öğesinin AÇIK menüde göründüğünü bekler. Önceki menünün kapanış canlandırması (~150 ms)
 * sürerken tetikleyiciye basmak Radix'te "dışarı tıklama" sayılır ve menü açılmaz (CI Linux'ta görüldü, 2026-11-26): eski menü
 * tümüyle kapanana ve düğme etkinleşene dek beklenir, menü açılmadıysa yeniden basılır (≤ 30 sn).
 */
async function openExportMenu(page, fmt) {
  const trigger = page.locator('[data-action="export"]')
  const item = page.locator(`[data-slot="dropdown-menu-content"][data-state="open"] [data-slot="ih-export-${fmt}"]`)
  await expect(async () => {
    if (!(await item.isVisible())) {
      await expect(page.locator('[data-slot="dropdown-menu-content"]')).toHaveCount(0, { timeout: 3_000 })
      await expect(trigger).toBeEnabled({ timeout: 3_000 })
      await trigger.click()
    }
    await expect(item).toBeVisible({ timeout: 3_000 })
  }).toPass({ timeout: 30_000 })
  return item
}

/** Menüden `fmt` indirmesini başlatır; indirme gelmezse ekrandaki iletiler hataya yazılır (neden düştüğü görünsün). */
async function exportDownload(page, fmt) {
  const item = await openExportMenu(page, fmt)
  try {
    const [dl] = await Promise.all([page.waitForEvent('download', { timeout: 45_000 }), item.click()])
    return dl
  } catch (e) {
    const notes = await page.locator('[role="status"], [role="alert"]').allInnerTexts().catch(() => [])
    throw new Error(`${fmt} indirmesi başlamadı; ekrandaki iletiler: ${JSON.stringify(notes.filter(Boolean))}\n${e.message}`)
  }
}

const VIEWPORTS = [
  { name: 'phone', width: 390, height: 844, touch: true, compact: true },
  { name: 'tablet', width: 768, height: 1024, touch: true, compact: true },
  { name: 'laptop', width: 1280, height: 800, touch: false, compact: false },
]

for (const vp of VIEWPORTS) {
  test.describe(`Olay kaydı bul ve raporla — ${vp.name} ${vp.width}`, () => {
    test.use({ viewport: { width: vp.width, height: vp.height }, hasTouch: vp.touch, isMobile: vp.touch, timezoneId: 'Europe/Istanbul' })

    async function checkLayout(page, loc, label, { targets = true } = {}) {
      await expect(loc, label).toBeVisible()
      await settle(page)
      expect(await loc.evaluate(overflow), label).toEqual([])
      expect(await loc.evaluate(clipped), label).toEqual([])
      if (vp.touch && targets) expect(await loc.evaluate(smallTargets), label).toEqual([])
      const b = await loc.boundingBox()
      expect(b.x, label).toBeGreaterThanOrEqual(-0.5)
      expect(b.x + b.width, label).toBeLessThanOrEqual(vp.width + 0.5)
      expect(await page.evaluate(() => document.documentElement.scrollWidth <= document.documentElement.clientWidth + 1), label).toBe(true)
    }

    async function search(page, text) {
      const box = page.getByRole('searchbox', { name: /Başlık, kod, metin ya da #no ara/ })
      await box.fill(text)
      await box.press('Enter')
      await expect.poll(() => listQueries.at(-1)?.q ?? '').toBe(text)
    }

    /** Gelişmiş süzgeç kabı: dar ekranda süzgeç çekmecesi (açılır), genişte "Daha fazla süzgeç" bölümü. */
    async function openAdvanced(page) {
      if (vp.compact) {
        await page.locator('[data-slot="ih-filters-button"]').click()
        const sheet = page.locator('[data-slot="ih-filters-sheet"]')
        await expect(sheet).toBeVisible()
        return sheet
      }
      const more = page.locator('[data-slot="ih-advanced-filters"]')
      if (!(await more.isVisible())) await page.locator('[data-slot="ih-more-filters"]').click()
      await expect(more).toBeVisible()
      return more
    }
    async function closeAdvanced(page) {
      if (!vp.compact) return
      const sheet = page.locator('[data-slot="ih-filters-sheet"]')
      await sheet.getByRole('button', { name: /sonucu göster|Uygula/ }).click()
      await expect(sheet).toHaveCount(0)
    }
    async function applyTag(page, tag) {
      const scope = await openAdvanced(page)
      const box = scope.getByRole('textbox', { name: 'Etiket' })
      await box.fill(tag)
      await box.press('Enter')
      await expect.poll(() => listQueries.at(-1)?.tag).toBe(tag)
      await closeAdvanced(page)
    }
    const sortSelect = (page) => (vp.compact ? page.locator('[data-slot="ih-filters-sheet"]') : page).getByRole('combobox', { name: 'Sıralama', exact: true })

    test('arama, gelişmiş süzgeç + sıralama, süzgeçli KPI ve döküm, dışa aktarım', async ({ page }) => {
      test.setTimeout(150_000)
      await page.clock.setFixedTime(NOW)
      await mock(page)
      await page.goto('/?tab=incident-history')
      await expect(page.getByText(FULL.title).first()).toBeVisible()
      // süzgeçsiz eğilim: yalnız tarih parametreleri (bugünkü istek)
      expect(trendQueries.length).toBeGreaterThan(0)
      for (const q of trendQueries) expect(Object.keys(q).every((k) => k === 'since' || k === 'until')).toBe(true)

      // 1) arama: çözüm adımlarındaki sözcük ve kayıt numarası
      await search(page, 'havuz')
      const hint = page.locator('[data-slot="ih-match"]:visible').first()
      await expect(hint).toHaveAttribute('data-field', 'resolution_steps')
      await expect(hint).toHaveText('Eşleşme: çözüm adımları')
      await checkLayout(page, hint, 'eşleşme ipucu', { targets: false })
      await search(page, '#9')
      await expect(page.locator('[data-slot="ih-match"]:visible').first()).toHaveText('Eşleşme: kayıt no')
      await search(page, '')

      // 2) gelişmiş süzgeçler + sıralama
      if (vp.compact) {
        await page.locator('[data-slot="ih-filters-button"]').click()
        const sheet = page.locator('[data-slot="ih-filters-sheet"]')
        await expect(sheet).toBeVisible()
        await sheet.getByRole('combobox', { name: 'Postmortem' }).selectOption('none')
        const tag = sheet.getByRole('textbox', { name: 'Etiket' })
        await tag.fill('prod')
        await tag.press('Enter')
        await sheet.getByRole('combobox', { name: 'Sıralama' }).selectOption('severity')
        await checkLayout(page, sheet, 'süzgeç çekmecesi')
        await sheet.getByRole('button', { name: /sonucu göster|Uygula/ }).click()
        await expect(sheet).toHaveCount(0)
      } else {
        await page.locator('[data-slot="ih-more-filters"]').click()
        const adv = page.locator('[data-slot="ih-advanced-filters"]')
        await expect(adv).toBeVisible()
        await adv.getByRole('combobox', { name: 'Postmortem' }).selectOption('none')
        const tag = adv.getByRole('textbox', { name: 'Etiket' })
        await tag.fill('prod')
        await tag.press('Enter')
        await page.getByRole('combobox', { name: 'Sıralama' }).selectOption('severity')
        await checkLayout(page, page.locator('[data-slot="ih-toolbar"]'), 'süzgeç çubuğu')
      }
      await expect.poll(() => listQueries.at(-1)).toMatchObject({ postmortem: 'none', tag: 'prod', sort: 'severity' })
      // adres eşitlemesi gecikmeli (~300 ms)
      await expect.poll(() => page.url()).toContain('ih_pm=none')
      await expect.poll(() => page.url()).toContain('ih_tag=prod')
      await expect.poll(() => page.url()).toContain('ih_sort=severity')
      const chips = page.locator('[data-slot="ih-active-filters"]')
      await expect(chips.locator('[data-filter="tag"]')).toContainText('Etiket: prod')
      await expect(chips.locator('[data-filter="postmortem"]')).toContainText('Postmortem: Postmortemi yok')
      await checkLayout(page, chips, 'süzgeç çipleri')

      // eğilim süzgeçli: etiket gider, sıralama gitmez; KPI notu
      await expect.poll(() => trendQueries.some((q) => q.tag === 'prod' && q.postmortem === 'none')).toBe(true)
      expect(trendQueries.filter((q) => q.tag).every((q) => !('sort' in q))).toBe(true)
      const note = page.locator('[data-slot="ih-kpi-filtered"]')
      await expect(note).toBeVisible()
      await checkLayout(page, page.locator('[data-slot="ih-kpis"]'), 'KPI kartları', { targets: false })
      await checkLayout(page, note, 'süzgece göre notu', { targets: false })

      // ayrıntılı döküm
      await page.locator('[data-slot="stats-toggle"]').click()
      const bd = page.locator('[data-slot="ih-breakdown"]')
      await expect(bd).toBeVisible()
      await expect(bd.locator('[data-slot="ih-metric-mttd"] [data-slot="ih-metric-value"]')).not.toHaveText('—')
      await expect(bd.locator('[data-slot="ih-metric-customers"] [data-slot="ih-metric-value"]')).toHaveText('1.200')
      await expect(bd.locator('[data-slot="ih-bd-team"]')).toContainText(TEAM)
      await checkLayout(page, bd, 'ayrıntılı döküm')

      // 3) dışa aktarım — CSV (sunucunun dosyası)
      const csv = await exportDownload(page, 'csv')
      expect(csv.suggestedFilename()).toBe('olay-gecmisi-20261126-1200.csv')
      const csvText = readFileSync(await csv.path(), 'utf8')
      expect(csvText.charCodeAt(0)).toBe(0xFEFF)
      expect(csvText).toContain('id,title,severity,resolution_steps')
      expect(csvText).toContain(FULL.resolution_steps)
      const csvQ = exportQueries.find((q) => q.format === 'csv')
      expect(csvQ).toMatchObject({ tag: 'prod', postmortem: 'none', sort: 'severity' })
      expect(csvQ.page).toBeUndefined()
      expect(csvQ.size).toBeUndefined()
      await expect(page.getByText('olay-gecmisi-20261126-1200.csv indirildi.')).toBeVisible()

      // Excel — sunucunun JSON'undan tarayıcıda; gerçek .xlsx açılır, başlıklar çevrilmiş, ham kod yok
      const xl = await exportDownload(page, 'xlsx')
      expect(xl.suggestedFilename()).toMatch(/^olay-gecmisi-\d{8}-\d{4}\.xlsx$/)
      const files = unzipSync(new Uint8Array(readFileSync(await xl.path())))
      const sheet1 = strFromU8(files['xl/worksheets/sheet1.xml'])
      expect(sheet1).toContain('Başlık')
      expect(sheet1).toContain('YÜKSEK')
      expect(sheet1).not.toContain('>HIGH<')
      expect(sheet1).toContain(TEAM)
      expect(exportQueries.find((q) => q.format === 'json')).toMatchObject({ tag: 'prod', sort: 'severity' })
      await checkLayout(page, page.locator('[data-slot="incident-history"]'), 'sayfa', { targets: false })
    })

    test('ayrıntıdan özet indir + yazdır, seçenek birleştirme', async ({ page }) => {
      test.setTimeout(120_000)
      await page.clock.setFixedTime(NOW)
      await mock(page)
      await page.goto('/?tab=incident-history')
      await expect(page.getByText(FULL.title).first()).toBeVisible()

      // 4) ayrıntı: özet satırdan açılır, özet kaydın TAZE hâlinden üretilir
      await page.getByText(FULL.title).first().click()
      const sheet = page.locator('[data-slot="ih-detail"]')
      await expect(sheet.getByText('Bağlantı havuzu tükendi.')).toBeVisible()
      await checkLayout(page, sheet.locator('[data-slot="ih-detail-actions"]'), 'ayrıntı eylemleri')
      const before = detailGets
      const [md] = await Promise.all([page.waitForEvent('download'), sheet.locator('[data-slot="ih-summary-download"]').click()])
      expect(md.suggestedFilename()).toBe('olay-9-odeme-servisinde-gecikme-kart-islemleri-yavas-yanit-veriyor.md')
      const mdText = readFileSync(await md.path(), 'utf8')
      expect(mdText.startsWith(`# ${FULL.title}`)).toBe(true)
      expect(mdText).toContain(FULL.resolution_steps)
      expect(mdText).toContain(TEAM)
      expect(detailGets).toBeGreaterThan(before)

      // Yazdır: yazdırma görünümü body'nin çocuğu, print bir kez; yazdırma ortamında yalnız o görünür (masaüstü — emulateMedia
      // dokunmatik öykünmeyi sıfırlar, telefonda / tablette yapılmaz)
      await sheet.locator('[data-slot="ih-print"]').click()
      const root = page.locator('body > [data-slot="ih-print-root"]')
      await expect(root).toHaveCount(1)
      await expect.poll(() => page.evaluate(() => window.__printed)).toBe(1)
      await expect(page.locator('body')).toHaveAttribute('data-ih-print', '1')
      await expect(root).toContainText(FULL.title)
      await expect(root).toContainText(FULL.resolution_steps)
      if (!vp.touch) {
        await page.emulateMedia({ media: 'print' })
        await expect(root).toBeVisible()
        await expect(page.locator('[data-slot="incident-history"]')).toBeHidden()
        await page.emulateMedia({ media: 'screen' })
      }
      await page.evaluate(() => window.dispatchEvent(new Event('afterprint')))
      await expect(root).toHaveCount(0)
      await expect(page.locator('body')).not.toHaveAttribute('data-ih-print')
      await sheet.getByRole('button', { name: /^Kapat$/ }).click()
      await expect(sheet).toHaveCount(0)

      // 5) seçenekleri yönet: iki kanal değeri tek ada birleşir
      await page.locator('[data-action="manage-options"]').click()
      const mgr = page.locator('[data-slot="ih-options-manager"]')
      await expect(mgr.locator('[data-slot="ih-opt-value"]')).toHaveCount(3)
      await checkLayout(page, mgr, 'seçenek yönetimi')
      await mgr.locator(`[data-slot="ih-opt-value"][data-value="${CHANNEL_A}"]`).getByRole('checkbox').click()
      await mgr.locator(`[data-slot="ih-opt-value"][data-value="${CHANNEL_B}"]`).getByRole('checkbox').click()
      await mgr.locator('[data-slot="ih-opt-to"]').fill(CHANNEL_A)
      await expect(page.locator('[data-slot="ih-opt-selected"]')).toHaveText('2 değer seçili · en çok 6 kayıt etkilenecek')
      await page.locator('[data-slot="ih-opt-submit"]').click()
      const confirm = page.getByRole('dialog', { name: 'Değerler birleştirilsin mi?' })
      await expect(confirm).toBeVisible()
      await confirm.getByRole('button', { name: 'Birleştir' }).click()
      await expect.poll(() => optionPuts.length).toBe(1)
      expect(optionPuts[0]).toEqual({ type: 'CHANNEL', team_id: 5, from: [CHANNEL_A, CHANNEL_B], to: CHANNEL_A })
      await expect(page.getByText(`6 kayıt güncellendi: değerler "${CHANNEL_A}" altında birleşti.`, { exact: false })).toBeVisible()
      // genel seçenek satırı olan değer listede kalır — sonuç bunu söyler (global_kept)
      await expect(page.getByText(`Genel seçenek olarak tanımlı değerler (${CHANNEL_B}) listede kaldı`, { exact: false }).first()).toBeVisible()
    })

    test('durumlar: liste okunamadı, eşleşmeyen arama, eski sunucu, Excel 429, yetkisiz', async ({ page }) => {
      test.setTimeout(150_000)
      await page.clock.setFixedTime(NOW)
      // geliştirme sunucusu StrictMode'da ilk yüklemeyi iki kez ister — "yeniden dene"ye dek her liste isteği düşer
      await mock(page, { listFail: 99 })
      await page.goto('/?tab=incident-history')
      // liste okunamadı → hata bloğu + yeniden dene (asla "kayıt yok")
      const err = page.getByRole('alert').filter({ hasText: 'Kayıtlar yüklenemedi.' })
      await expect(err).toBeVisible({ timeout: 30_000 })
      await expect(page.getByText('Henüz olay kaydı yok')).toHaveCount(0)
      await checkLayout(page, err, 'liste hatası')
      MODE.listFail = 0
      await err.getByRole('button', { name: 'Yeniden dene' }).click()
      await expect(page.getByText(FULL.title).first()).toBeVisible()

      // eşleşmeyen arama → "eşleşen olay yok" + süzgeçleri temizle
      await search(page, 'yokboyle')
      const none = page.getByText('Bu süzgeçlerle eşleşen olay yok')
      await expect(none).toBeVisible()
      await checkLayout(page, page.locator('[data-slot="ih-results"]'), 'eşleşmeyen arama')
      await search(page, '')
      await expect(page.getByText(FULL.title).first()).toBeVisible()

      // Excel: sunucu 429 EXPORT_BUSY → sunucunun iletisi, indirme yok
      MODE.exportBusy = true
      await (await openExportMenu(page, 'xlsx')).click()
      await expect(page.getByText(/Önceki dışa aktarımınız hâlâ hazırlanıyor/).first()).toBeVisible()
      MODE.exportBusy = false

      // eski sunucu: gelişmiş süzgeç uygulanınca bant; dışa aktarım "bu sürümde yok"; döküm "—"
      MODE.old = true
      if (vp.compact) {
        await page.locator('[data-slot="ih-filters-button"]').click()
        const sheet = page.locator('[data-slot="ih-filters-sheet"]')
        await sheet.getByRole('combobox', { name: 'Postmortem' }).selectOption('published')
        await sheet.getByRole('button', { name: /sonucu göster|Uygula/ }).click()
        await expect(sheet).toHaveCount(0)
      } else {
        await page.locator('[data-slot="ih-more-filters"]').click()
        await page.locator('[data-slot="ih-advanced-filters"]').getByRole('combobox', { name: 'Postmortem' }).selectOption('published')
      }
      await expect.poll(() => listQueries.at(-1)?.postmortem).toBe('published')
      const band = page.locator('[data-slot="ih-ext-unsupported"]')
      await expect(band).toBeVisible()
      await checkLayout(page, band, 'eski sunucu bandı', { targets: false })
      await (await openExportMenu(page, 'csv')).click()
      await expect(page.getByText('Bu sunucu sürümü olay geçmişi dışa aktarımını henüz bilmiyor; dağıtım bitince yeniden deneyin.').first()).toBeVisible()
      await page.locator('[data-slot="stats-toggle"]').click()
      const bd = page.locator('[data-slot="ih-breakdown"]')
      await expect(bd).toBeVisible()
      await expect(bd.locator('[data-slot="ih-metric-mttd"] [data-slot="ih-metric-value"]')).toHaveText('—')
      await expect(bd.locator('[data-slot="ih-metric-customers"] [data-slot="ih-metric-value"]')).toHaveText('—')
      await checkLayout(page, bd, 'eski sunucu dökümü')
      // eski sunucu /teams bilmez → yeniden yüklemede seçenek yönetimi düğmesi yok (yetki bilinmiyor)
      await page.reload()
      await expect(page.getByText(FULL.title).first()).toBeVisible({ timeout: 30_000 })
      await expect(page.locator('[data-action="manage-options"]')).toHaveCount(0)

      // yetkisiz: seçenek yönetimi yetkisi yok → düğme yok; görüntüleme yetkisi yok → sayfa "yetkiniz yok"
      MODE = { noManage: true }
      await page.goto('/?tab=incident-history')
      await expect(page.getByText(FULL.title).first()).toBeVisible()
      await expect(page.locator('[data-action="manage-options"]')).toHaveCount(0)
      await expect(page.locator('[data-action="export"]')).toBeVisible()
      MODE = { noView: true }
      await page.goto('/?tab=incident-history')
      await expect(page.getByText('Bu sayfa için yetkiniz yok.').first()).toBeVisible()
    })

    test('seçenek adında alan hatası, yanıtsız istek "sonuç bilinmiyor", komut paletinden olay kaydı', async ({ page }) => {
      test.setTimeout(150_000)
      await page.clock.setFixedTime(NOW)
      await mock(page, { renameFlow: true })
      await page.goto('/?tab=incident-history')
      await expect(page.getByText(FULL.title).first()).toBeVisible({ timeout: 30_000 })

      await page.locator('[data-action="manage-options"]').click()
      const mgr = page.locator('[data-slot="ih-options-manager"]')
      await expect(mgr.locator('[data-slot="ih-opt-value"]')).toHaveCount(3)
      const pick = async () => {
        await mgr.locator('[data-slot="ih-opt-value"][data-value="Mobil Şube"]').getByRole('checkbox').click()
      }
      const submit = async (title = 'Değer yeniden adlandırılsın mı?', btn = 'Yeniden adlandır') => {
        await page.locator('[data-slot="ih-opt-submit"]').click()
        const confirm = page.getByRole('dialog', { name: title })
        await expect(confirm).toBeVisible()
        await confirm.getByRole('button', { name: btn }).click()
      }
      // 1) sunucunun alan hatası alanın altında
      await pick()
      await mgr.locator('[data-slot="ih-opt-to"]').fill('Mobil Bankacılık')
      await submit()
      const fieldErr = mgr.locator('[data-field="to"]')
      await expect(fieldErr).toContainText('Kayıt #7 için yeni değer alanın sınırını')
      await expect(mgr.locator('[data-slot="ih-opt-to"]')).toHaveAttribute('aria-invalid', 'true')
      await checkLayout(page, mgr, 'seçenek alan hatası')
      // 2) yanıtsız istek (503): "sonuç bilinmiyor", liste yeniden okunur, seçim sıfırlanır — körlemesine yeniden denenmez
      const before = usageGets
      await mgr.locator('[data-slot="ih-opt-to"]').fill('Mobil')
      await submit()
      const failure = mgr.locator('[data-slot="ih-opt-failure"]')
      await expect(failure).toContainText('Sunucudan yanıt alınamadı; değişiklik uygulanmış olabilir.')
      await expect.poll(() => usageGets).toBeGreaterThan(before)
      await expect(page.locator('[data-slot="ih-opt-submit"]')).toBeDisabled()
      expect(optionPuts).toHaveLength(2)
      await checkLayout(page, mgr, 'sonuç bilinmiyor')
      // 3) yeniden seçip gönderince başarı
      await pick()
      await mgr.locator('[data-slot="ih-opt-to"]').fill('Mobil')
      await submit()
      await expect.poll(() => optionPuts.length).toBe(3)
      await expect(page.getByText('1 kayıt güncellendi', { exact: false }).first()).toBeVisible()
      await page.keyboard.press('Escape')
      await expect(mgr).toHaveCount(0)

      // komut paleti: olay kaydı araması → ayrıntı açılır
      await page.keyboard.press('Control+k')
      const input = page.getByPlaceholder(/Sayfa, alan adı/)
      await expect(input).toBeVisible()
      await input.fill('gecikme')
      await expect.poll(() => searchQueries.at(-1)).toBe('gecikme')
      const hit = page.getByRole('option', { name: new RegExp(FULL.title.slice(0, 30)) })
      await expect(hit).toBeVisible()
      await expect(hit).toContainText('Olay kaydı')
      const palette = page.locator('[role="dialog"]').filter({ has: input })
      await checkLayout(page, palette, 'komut paleti', { targets: false })
      const before2 = detailGets
      await hit.click()
      const sheet = page.locator('[data-slot="ih-detail"]')
      await expect(sheet).toBeVisible()
      await expect(sheet.getByText('Bağlantı havuzu tükendi.')).toBeVisible()
      await expect.poll(() => detailGets).toBeGreaterThan(before2)
      expect(new URL(page.url()).searchParams.has('incident')).toBe(false)
    })

    // ── Bağımsız inceleme düzeltmeleri (2026-11-26) ────────────────────────────────────────────────────────────────────

    test('başlık dokunmatikte ≥ 40 px; KPI kartı aramayı korur; döküm satırları listeyle aynı kümeyi süzer', async ({ page }) => {
      test.setTimeout(150_000)
      await page.clock.setFixedTime(NOW)
      await mock(page, { dataset: true })
      await page.goto('/?tab=incident-history')
      await expect(page.getByText(FULL.title).first()).toBeVisible()
      await expect(page.locator('[data-action="manage-options"]')).toBeVisible()
      // #2 başlık eylemleri (Kayıtlı görünümler · Dışa aktar · Seçenekler · Yenile · Hızlı kayıt · Yeni olay): data-slot "button"
      // kalır → PageHeader dokunmatik büyütmesi uygulanır
      const header = page.locator('.app-main [data-slot="page-header"]').first()
      for (const a of ['export', 'manage-options', 'refresh', 'quick', 'new']) {
        await expect(header.locator(`[data-action="${a}"]`), a).toHaveAttribute('data-slot', 'button')
      }
      await checkLayout(page, header, 'sayfa başlığı')

      // #1 KPI kartı aramayı korur: kutu, çip ve istek aynı kalır
      const total = page.locator('[data-slot="ih-total"]')
      await search(page, 'hatası')
      await expect(total).toHaveText('2 kayıt')
      await page.locator('[data-slot="ih-kpi"][data-key="critical"]').click()
      await expect.poll(() => listQueries.at(-1)).toMatchObject({ q: 'hatası', severity: 'CRITICAL' })
      await expect(page.getByText('Bu süzgeçlerle eşleşen olay yok')).toBeVisible()
      await expect(page.getByRole('searchbox', { name: /Başlık, kod, metin ya da #no ara/ })).toHaveValue('hatası')
      await expect(page.locator('[data-slot="ih-active-filters"] [data-filter="q"]')).toContainText('hatası')
      await expect.poll(() => trendQueries.at(-1)?.q).toBe('hatası')
      expect(trendQueries.at(-1).severity).toBeUndefined()
      await page.waitForTimeout(500)   // gecikmeli arama penceresi geçti — arama hâlâ yerinde
      expect(listQueries.at(-1)).toMatchObject({ q: 'hatası', severity: 'CRITICAL' })
      await page.locator('[data-slot="ih-kpi"][data-key="critical"]').click()
      await expect(total).toHaveText('2 kayıt')
      await search(page, '')
      await expect(total).toHaveText('3 kayıt')

      // #5 / #6 döküm satırı → liste sayısı satırın sayısına eşit
      await page.locator('[data-slot="stats-toggle"]').click()
      const bd = page.locator('[data-slot="ih-breakdown"]')
      await expect(bd).toBeVisible()
      const bdTotal = bd.locator('[data-slot="ih-metric-total"] [data-slot="ih-metric-value"]')
      await expect(bdTotal).toHaveText('3')
      async function pickRow(slot, key) {
        const row = bd.locator(`[data-slot="${slot}"] [data-slot="ih-bd-row"][data-key="${key}"]`)
        await expect(row).toBeVisible()
        const n = Number(/(\d+) · %/.exec(await row.innerText())?.[1])
        await row.getByRole('button').click()
        return n
      }
      // kanal: TAM öğe ("Mobil Şube" iki kayıtta öğe; alt dize "Mobil Şube Kurumsal"ı da sayardı → 3)
      let n = await pickRow('ih-bd-channel', 'Mobil Şube')
      expect(n).toBe(2)
      await expect(total).toHaveText(`${n} kayıt`)
      expect(listQueries.at(-1)).toMatchObject({ channel_exact: 'Mobil Şube', channel: 'Mobil Şube' })
      const chips = page.locator('[data-slot="ih-active-filters"]')
      await expect(chips.locator('[data-filter="channel_exact"]')).toContainText('Kanal (tam): Mobil Şube')
      await expect.poll(() => new URL(page.url()).searchParams.get('ih_chx')).toBe('Mobil Şube')
      await expect(bdTotal).toHaveText('2')
      // takım: süzgeç seçicisinde olmayan (paylaşımla görünen) takım ADIYLA eklenir
      n = await pickRow('ih-bd-team', '7')
      expect(n).toBe(1)
      await expect(total).toHaveText(`${n} kayıt`)
      const teamChip = chips.locator('[data-filter="team_id"]')
      await expect(teamChip).toContainText(`Takım: ${SHARED_TEAM}`)
      await expect(teamChip).not.toContainText('Adı okunamayan takım')
      if (!vp.compact) {
        const teamSel = page.locator('[data-slot="incident-filters"]').getByRole('combobox', { name: 'Takım', exact: true })
        await expect(teamSel).toHaveValue('7')
        await expect(teamSel.locator('option:checked')).toHaveText(SHARED_TEAM)
      }
      await checkLayout(page, chips, 'döküm süzgeç çipleri')
      await chips.getByRole('button', { name: 'Temizle', exact: true }).click()
      await expect(total).toHaveText('3 kayıt')
      await expect(bdTotal).toHaveText('3')
      // kategori ve önem satırları
      n = await pickRow('ih-bd-category', 'NETWORK')
      expect(n).toBe(2)
      await expect(total).toHaveText(`${n} kayıt`)
      await expect(bdTotal).toHaveText('2')
      n = await pickRow('ih-bd-severity', 'HIGH')
      expect(n).toBe(1)
      await expect(total).toHaveText(`${n} kayıt`)
      expect(listQueries.at(-1)).toMatchObject({ category: 'NETWORK', severity: 'HIGH' })
      await checkLayout(page, bd, 'döküm satırları')
    })

    test('gelişmiş süzgeçler (alan adı, problem tipi, hata kodu, yalnız benimkiler), sıralamalar, yeniden yüklemede sıralama', async ({ page }) => {
      test.setTimeout(150_000)
      await page.clock.setFixedTime(NOW)
      await mock(page)
      await page.goto('/?tab=incident-history')
      await expect(page.getByText(FULL.title).first()).toBeVisible()
      const scope = await openAdvanced(page)
      await scope.getByRole('combobox', { name: 'Alan adı / servis', exact: true }).selectOption(LONG_DOMAIN)
      await expect.poll(() => listQueries.at(-1)?.service_exact).toBe(LONG_DOMAIN)
      await scope.getByRole('combobox', { name: 'Problem tipi', exact: true }).selectOption('Konfigürasyon')
      await expect.poll(() => listQueries.at(-1)?.problem_type).toBe('Konfigürasyon')
      await scope.getByRole('combobox', { name: 'Hata kodu', exact: true }).selectOption('E-503')
      await expect.poll(() => listQueries.at(-1)?.error_code).toBe('E-503')
      await scope.getByRole('checkbox', { name: 'Yalnız benim açtıklarım' }).click()
      await expect.poll(() => listQueries.at(-1)?.created_by).toBe('@me')
      await checkLayout(page, scope, 'gelişmiş süzgeçler')
      // sıralamaların hepsi listeye gider (varsayılan gönderilmez)
      for (const s of ['occurred_asc', 'status', 'duration', 'severity', 'updated']) {
        await sortSelect(page).selectOption(s)
        await expect.poll(() => listQueries.at(-1)?.sort).toBe(s)
      }
      await closeAdvanced(page)
      const chips = page.locator('[data-slot="ih-active-filters"]')
      await expect(chips.locator('[data-filter="service"]')).toContainText(`Alan adı: ${LONG_DOMAIN}`)
      await expect(chips.locator('[data-filter="problem_type"]')).toContainText('Problem tipi: Konfigürasyon')
      await expect(chips.locator('[data-filter="error_code"]')).toContainText('Hata kodu: E-503')
      await expect(chips.locator('[data-filter="mine"]')).toContainText('Benim açtıklarım')
      await checkLayout(page, chips, 'gelişmiş süzgeç çipleri')
      // eğilim süzgeçleri taşır, sıralamayı taşımaz
      await expect.poll(() => trendQueries.some((q) => q.service_exact === LONG_DOMAIN && q.error_code === 'E-503' && q.created_by === '@me')).toBe(true)
      expect(trendQueries.every((q) => !('sort' in q))).toBe(true)
      await expect.poll(() => page.url()).toContain('ih_sort=updated')

      // yeniden yükleme: süzgeçler + sıralama adresten okunur, ilk istekte gider; seçici aynı değeri gösterir
      const before = listQueries.length
      await page.reload()
      await expect(page.getByText(FULL.title).first()).toBeVisible({ timeout: 30_000 })
      await expect.poll(() => listQueries.length).toBeGreaterThan(before)
      expect(listQueries[before]).toMatchObject({ sort: 'updated', service_exact: LONG_DOMAIN, problem_type: 'Konfigürasyon',
        error_code: 'E-503', created_by: '@me' })
      if (vp.compact) await openAdvanced(page)
      await expect(sortSelect(page)).toHaveValue('updated')
      await closeAdvanced(page)
    })

    test('dışa aktarım: kesik bildirimi, 429 (kişi / dakika / pod), eski sunucu Excel 404 / 405, indirme başlatılamadı', async ({ page }) => {
      test.setTimeout(150_000)
      await page.clock.setFixedTime(NOW)
      await mock(page, { exportTruncated: true })
      let downloads = 0
      page.on('download', () => { downloads += 1 })
      await page.goto('/?tab=incident-history')
      await expect(page.getByText(FULL.title).first()).toBeVisible()
      const exportVia = async (fmt) => {
        // Önceki menünün kapanış canlandırması sürerken (~150 ms) tetikleyiciye basılırsa Radix bunu dışarı tıklama sayar ve
        // menü açılmaz (bilinen sınır, Aktivite dışa aktarımıyla aynı) — önce eski menü tümüyle kapanır
        await (await openExportMenu(page, fmt)).click()
      }
      // kesik: dosya iner, bildirim sınırı söyler (CSV 10000 başlıktan, Excel 5000 yanıttan)
      const [csv] = await Promise.all([page.waitForEvent('download'), exportVia('csv')])
      expect(csv.suggestedFilename()).toBe('olay-gecmisi-20261126-1200.csv')
      await expect(page.getByText('Dışa aktarım ilk 10000 kayıtla sınırlandı', { exact: false }).first()).toBeVisible()
      const [xl] = await Promise.all([page.waitForEvent('download'), exportVia('xlsx')])
      expect(xl.suggestedFilename()).toMatch(/^olay-gecmisi-\d{8}-\d{4}\.xlsx$/)
      await expect(page.getByText('Dışa aktarım ilk 5000 kayıtla sınırlandı', { exact: false }).first()).toBeVisible()
      MODE.exportTruncated = false
      const got = downloads

      // 429: sunucunun iletisi, indirme yok
      MODE.exportBusy = 'person'
      await exportVia('csv')
      await expect(page.getByText(/Önceki dışa aktarımınız hâlâ hazırlanıyor/).first()).toBeVisible()
      MODE.exportBusy = 'minute'
      await exportVia('csv')
      await expect(page.getByText(/kişi başına dakikada en çok 6 dışa aktarım\. 42 saniye sonra/).first()).toBeVisible()
      MODE.exportBusy = 'pod'
      await exportVia('xlsx')
      await expect(page.getByText(/başka dışa aktarımlar hazırlanıyor; 10 saniye sonra/).first()).toBeVisible()
      MODE.exportBusy = null

      // eski sunucu: Excel (JSON) 404 ve 405 → "bu sürümde yok"
      for (const status of [404, 405]) {
        MODE.oldExport = status
        const n = exportQueries.length
        await exportVia('xlsx')
        await expect.poll(() => exportQueries.length).toBeGreaterThan(n)
        await expect(page.getByText('Bu sunucu sürümü olay geçmişi dışa aktarımını henüz bilmiyor', { exact: false }).first()).toBeVisible()
      }
      MODE.oldExport = null

      // tarayıcı indirmeyi başlatamadı: başarı yazılmaz, hata bildirilir (CSV ve Excel)
      await page.evaluate(() => {
        window.__origCreate = URL.createObjectURL
        URL.createObjectURL = () => { throw new Error('indirme engellendi') }
      })
      await exportVia('csv')
      await expect(page.getByText('Dışa aktarım tamamlanamadı.', { exact: false }).first()).toBeVisible()
      const n2 = exportQueries.length
      await exportVia('xlsx')
      await expect.poll(() => exportQueries.length).toBeGreaterThan(n2)
      await expect(page.locator('[data-action="export"]')).toBeEnabled()
      await page.evaluate(() => { URL.createObjectURL = window.__origCreate })
      expect(downloads).toBe(got)
      await expect(page.getByText(/indirildi\.$/)).toHaveCount(0)
      await checkLayout(page, page.locator('.app-main [data-slot="page-header"]').first(), 'başlık (dışa aktarım sonrası)')
    })

    test('özet okunamadı; görselli yazdırma görseli bekler (en çok 5 sn)', async ({ page }) => {
      test.setTimeout(150_000)
      await page.clock.setFixedTime(NOW)
      let release3
      let release4
      await mock(page, { withImage: 3, imageHolds: {
        3: new Promise((r) => { release3 = r }),
        4: new Promise((r) => { release4 = r }),
      } })
      try {
        await page.goto('/?tab=incident-history')
        await expect(page.getByText(FULL.title).first()).toBeVisible()
        await page.getByText(FULL.title).first().click()
        const sheet = page.locator('[data-slot="ih-detail"]')
        await expect(sheet.getByText('Bağlantı havuzu tükendi.')).toBeVisible()

        // özet: kaydın taze hâli okunamaz → hata, indirme yok; yazdırma görünümü de açılmaz. Sunucunun iletisi varsa o yazılır
        // (hata sözleşmesi: belirli sunucu metni korunur); `inc.sum.readFailed` yalnız metinsiz yanıtın yedeğidir
        MODE.detailFail = true
        let downloads = 0
        page.on('download', () => { downloads += 1 })
        await sheet.locator('[data-slot="ih-summary-download"]').click()
        await expect(page.getByText('Veritabanına şu anda ulaşılamıyor; birkaç saniye sonra yeniden deneyin.', { exact: false }).first()).toBeVisible()
        const d0 = detailGets
        await sheet.locator('[data-slot="ih-print"]').click()
        await expect.poll(() => detailGets).toBeGreaterThan(d0)
        await expect(sheet.locator('[data-slot="ih-print"]')).toBeEnabled()
        await expect(page.locator('body > [data-slot="ih-print-root"]')).toHaveCount(0)
        expect(downloads).toBe(0)
        expect(await page.evaluate(() => window.__printed)).toBe(0)
        await checkLayout(page, sheet.locator('[data-slot="ih-detail-actions"]'), 'özet okunamadı')
        MODE.detailFail = false

        // görsel inene dek yazdırılmaz, düğme meşgul; inince bir kez yazdırılır
        const printBtn = sheet.locator('[data-slot="ih-print"]')
        await printBtn.click()
        const root = page.locator('body > [data-slot="ih-print-root"]')
        await expect(root).toHaveCount(1)
        await expect(root.locator('img[src="/api/incidents/images/3"]')).toHaveCount(1)
        await page.waitForTimeout(1500)
        expect(await page.evaluate(() => window.__printed)).toBe(0)
        await expect(printBtn).toBeDisabled()
        await expect(printBtn).toHaveAttribute('aria-busy', 'true')
        release3()
        await expect.poll(() => page.evaluate(() => window.__printed)).toBe(1)
        await expect(printBtn).toBeEnabled()
        await page.evaluate(() => window.dispatchEvent(new Event('afterprint')))
        await expect(root).toHaveCount(0)

        // hiç inmeyen görsel: bekleme SINIRLI — en çok 5 sn sonra yine yazdırılır
        MODE.withImage = 4
        const t0 = Date.now()
        await printBtn.click()
        await expect(root.locator('img[src="/api/incidents/images/4"]')).toHaveCount(1)
        await expect.poll(() => page.evaluate(() => window.__printed), { timeout: 12_000 }).toBe(2)
        const waited = Date.now() - t0
        expect(waited).toBeGreaterThanOrEqual(4500)
        expect(waited).toBeLessThan(11_000)
        await page.evaluate(() => window.dispatchEvent(new Event('afterprint')))
        await expect(root).toHaveCount(0)
      } finally {
        release3?.()
        release4?.()
      }
    })

    test('seçenek yönetimi: 403, okuma hatası + yeniden dene, takım / tür değişimi, kesik kullanım listesi', async ({ page }) => {
      test.setTimeout(150_000)
      await page.clock.setFixedTime(NOW)
      await mock(page, { twoTeams: true, forbiddenTeam: '6' })
      await page.goto('/?tab=incident-history')
      await expect(page.getByText(FULL.title).first()).toBeVisible()
      await page.locator('[data-action="manage-options"]').click()
      const mgr = page.locator('[data-slot="ih-options-manager"]')
      await expect(mgr).toBeVisible()
      const teamSel = mgr.locator('[data-slot="ih-opt-team"]')
      const typeSel = mgr.locator('[data-slot="ih-opt-type"]')
      // iki takım: önce takım seçilir
      await expect(mgr.locator('[data-slot="ih-opt-pick-team"]')).toBeVisible()
      // takım 6: yönetme yetkisi yok (403) — "yetkiniz yok", asla "değer yok"
      await teamSel.selectOption('6')
      await expect(mgr.getByText('Bu takımın seçeneklerini yönetme yetkiniz yok.')).toBeVisible()
      await expect(mgr.locator('[data-slot="ih-opt-empty"]')).toHaveCount(0)
      await expect(page.locator('[data-slot="ih-opt-submit"]')).toBeDisabled()
      expect(usageQueries.at(-1)).toMatchObject({ type: 'CHANNEL', team_id: '6' })
      await checkLayout(page, mgr, 'seçenek 403')
      // takım 5: ilk okuma düşer → "okunamadı" + yeniden dene → değerler
      MODE.usageFail = 1
      await teamSel.selectOption('5')
      const failed = mgr.getByRole('alert').filter({ hasText: 'Değerler okunamadı.' })
      await expect(failed).toBeVisible()
      await expect(mgr.locator('[data-slot="ih-opt-empty"]')).toHaveCount(0)
      await checkLayout(page, mgr, 'seçenek okuma hatası')
      await failed.getByRole('button', { name: 'Yeniden dene' }).click()
      await expect(mgr.locator('[data-slot="ih-opt-value"]')).toHaveCount(3)
      expect(usageQueries.at(-1)).toMatchObject({ type: 'CHANNEL', team_id: '5' })
      // tür değişimi: o türün değerleri; kesik okuma → not + "en az N kayıt"
      MODE.usageTruncated = true
      await typeSel.selectOption('ERROR_CODE')
      await expect.poll(() => usageQueries.at(-1)).toMatchObject({ type: 'ERROR_CODE', team_id: '5' })
      await expect(mgr.locator('[data-slot="ih-opt-value"]')).toHaveCount(2)
      await expect(mgr.locator('[data-slot="ih-opt-truncated"]')).toContainText('yalnız ilk 20000 kayıt okundu')
      await expect(mgr.locator('[data-slot="ih-opt-value"][data-value="E-500"]')).toContainText('en az 3 kayıt')
      await checkLayout(page, mgr, 'kesik kullanım listesi')
      // takım değişimi: aynı tür, öteki takımla istenir; eski takımın değerleri görünmez
      await teamSel.selectOption('6')
      await expect.poll(() => usageQueries.at(-1)).toMatchObject({ type: 'ERROR_CODE', team_id: '6' })
      await expect(mgr.getByText('Bu takımın seçeneklerini yönetme yetkiniz yok.')).toBeVisible()
      await expect(mgr.locator('[data-slot="ih-opt-value"]')).toHaveCount(0)
    })

    test('eğilim: yenileme hatası şeridi, süzgeçli günlük grafik + kayıt sınırı notu, eski sunucu notu', async ({ page }) => {
      test.setTimeout(150_000)
      await page.clock.setFixedTime(NOW)
      await mock(page, { capped: true })
      await page.goto('/?tab=incident-history')
      await expect(page.getByText(FULL.title).first()).toBeVisible()
      const kpiTotal = page.locator('[data-slot="ih-kpi"][data-key="total"] [data-slot="ih-kpi-value"]')
      await expect(kpiTotal).toHaveText('1')
      // #14 aynı süzgecin yenilemesi düştü: eski sayı + "yenilenemedi" şeridi; yeniden dene başarılı → şerit kalkar
      MODE.trendFail = true
      await page.locator('[data-action="refresh"]').click()
      const stale = page.locator('[data-slot="ih-kpi-stale"]')
      await expect(stale).toBeVisible()
      await expect(stale).toContainText('Özet yenilenemedi')
      await expect(kpiTotal).toHaveText('1')
      await checkLayout(page, stale, 'yenilenemedi şeridi')
      MODE.trendFail = false
      await stale.getByRole('button', { name: 'Yeniden dene' }).click()
      await expect(stale).toHaveCount(0)
      await expect(kpiTotal).toHaveText('1')

      // #7 süzgeçli günlük grafik: view=daily + süzgeç + tarih penceresi; döküm kayıt sınırı notu
      await applyTag(page, 'prod')
      await page.locator('[data-slot="stats-toggle"]').click()
      await expect.poll(() => trendQueries.some((q) => q.view === 'daily' && q.tag === 'prod' && !!q.since && !!q.until)).toBe(true)
      // özet / döküm isteği hiçbir zaman yalın günlük kipte değil (view yalnız günlük grafikte)
      expect(trendQueries.filter((q) => !q.view).length).toBeGreaterThan(0)
      expect(trendQueries.every((q) => q.view == null || q.view === 'daily')).toBe(true)
      const chart = page.locator('[data-slot="incident-trend"]')
      await expect(chart).toContainText('Son 30 günde 3 olay · 0 kritik')
      await expect.poll(() => chart.locator('.recharts-bar-rectangle, .recharts-rectangle').count()).toBeGreaterThan(0)
      const capped = page.locator('[data-slot="ih-bd-capped"]')
      await expect(capped).toContainText('en yeni 20000 kayıttan')
      await checkLayout(page, chart, 'süzgeçli günlük grafik')
      await checkLayout(page, capped, 'kayıt sınırı notu', { targets: false })
      await expect(page.locator('[data-slot="ih-kpi-filtered"]')).toBeVisible()
      await expect(page.locator('[data-slot="ih-kpi-unfiltered"]')).toHaveCount(0)

      // #13 eski sunucu: eğilim yanıtında ext_filters yok → "sayılar süzülmüyor" notu, "süzgece göre" notu yok
      MODE.oldTrend = true
      await page.locator('[data-action="refresh"]').click()
      const oldNote = page.locator('[data-slot="ih-kpi-unfiltered"]')
      await expect(oldNote).toBeVisible()
      await expect(oldNote).toContainText('özet sayılarını süzgeçlere göre hesaplamıyor')
      await expect(page.locator('[data-slot="ih-kpi-filtered"]')).toHaveCount(0)
      await checkLayout(page, oldNote, 'eski sunucu eğilim notu', { targets: false })
    })

    test('komut paleti: "#9" kayıt numarası, başka sekmeden olay kaydı açılır', async ({ page }) => {
      test.setTimeout(120_000)
      await page.clock.setFixedTime(NOW)
      await mock(page)
      await page.goto('/?tab=help')
      await expect(page.locator('.app-main')).toBeVisible()
      await page.keyboard.press('Control+k')
      const input = page.getByPlaceholder(/Sayfa, alan adı/)
      await expect(input).toBeVisible()
      await input.fill('#9')
      await expect.poll(() => searchQueries.at(-1)).toBe('#9')
      const hit = page.getByRole('option', { name: new RegExp(FULL.title.slice(0, 30)) })
      await expect(hit).toBeVisible()
      await expect(hit).toContainText('#9')
      const palette = page.locator('[role="dialog"]').filter({ has: input })
      await checkLayout(page, palette, 'komut paleti (#9)', { targets: false })
      const before = detailGets
      await hit.click()
      await expect.poll(() => new URL(page.url()).searchParams.get('tab')).toBe('incident-history')
      const sheet = page.locator('[data-slot="ih-detail"]')
      await expect(sheet).toBeVisible({ timeout: 30_000 })
      await expect(sheet.getByText('Bağlantı havuzu tükendi.')).toBeVisible()
      await expect.poll(() => detailGets).toBeGreaterThan(before)
      await expect.poll(() => new URL(page.url()).searchParams.has('incident')).toBe(false)
      await checkLayout(page, sheet.locator('[data-slot="ih-detail-actions"]'), 'paletten açılan ayrıntı')
    })
  })
}
