import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, waitFor, fireEvent, cleanup } from './test-utils.jsx'
import { EN } from '../i18n/en.js'
import { TR } from '../i18n/tr.js'
import listJson from './helpers/activityReal/list.json'
import summaryJson from './helpers/activityReal/summary.json'
import exportJson from './helpers/activityReal/export.json'
import d503 from './helpers/activityReal/detail-http503sched.json'
import dLimited from './helpers/activityReal/detail-httpFail-limited.json'
import dHttpFail from './helpers/activityReal/detail-httpFail.json'
import dCert from './helpers/activityReal/detail-certFail.json'
import dDbq from './helpers/activityReal/detail-dbqFail.json'
import dScr from './helpers/activityReal/detail-scrFail.json'
import dCfg from './helpers/activityReal/detail-configChanged.json'
import dAlert from './helpers/activityReal/detail-alertTriggered.json'
import tlHttp from './helpers/activityReal/timeline-http2.json'
import tlDbq from './helpers/activityReal/timeline-dbq.json'
import relDns from './helpers/activityReal/related-dnsFail0.json'
import relHttp from './helpers/activityReal/related-httpFail.json'

const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))
vi.mock('../api/client', async (importOriginal) => {
  const mod = await importOriginal()
  return {
    ...mod,
    api: withApiFallback({
      getActivity: vi.fn(), getActivitySummary: vi.fn(), getActivityFullDetail: vi.fn(), getActivityDetail: vi.fn(),
      getActivityTimeline: vi.fn(), getActivityRelated: vi.fn(), search: vi.fn(), teams: { directory: vi.fn() },
      admin: { getAlert: vi.fn(), getAlertNotifications: vi.fn(), getAlertPushDeliveries: vi.fn(), getAlertStormPush: vi.fn() },
      monitoring: { getChangeDetail: vi.fn() },
    }),
  }
})
import { api } from '../api/client'
import ActivityLog from '../components/ActivityLog.jsx'
import ActivityDetailPage from '../components/activity/detail/ActivityDetailPage.jsx'
import MonitorTimelineView from '../components/activity/timeline/MonitorTimelineView.jsx'
import RelatedActivityPanel from '../components/activity/related/RelatedActivityPanel.jsx'
import { excelSheet, unwrapExportJson } from '../components/activity/activityExport.js'
import { normalizeDetail } from '../components/activity/detail/activityDetailModel.js'
import { unwrapRelated } from '../components/activity/related/relatedModel.js'

/**
 * GERÇEK arka uç dökümleriyle aktivite ekranları (2026-11-05, inceleme düzeltme turu — act-review-contract): dökümler
 * gerçek yazıcılar + gerçek denetleyici ile üretildi (`helpers/activityReal/*`, sahte kurum / kişi). Liste, ayrıntı sayfası
 * (her döküm), izleme zaman çizelgesi, kök neden paneli ve Excel modeli TR + EN çizilir; görünür metinde `undefined`,
 * `NaN`, `[object Object]`, ham `act.*` anahtarı, ham kod (neden / eylem / şüphe kodu) ve ham ISO zamanı OLMAMALI.
 * `related-*.json` be2 d1097f7a anlamına çevrildi (total / monitors / by_* bu kayıt HARİÇ; `window_total`, `anchor_in_window`).
 */
const clone = (x) => JSON.parse(JSON.stringify(x))
const q = (sel, root = document) => root.querySelector(sel)

// Görünür metinde ham kalmaması gereken kodlar: dökümlerdeki tüm neden / eylem / şüphe / olgu kodları
const RAW_CODES = new Set([
  ...listJson.data.map((r) => r.failure_reason).filter(Boolean),
  ...listJson.data.map((r) => r.action),
  ...relDns.data.suspects.map((s) => s.code), ...relDns.data.facts.map((f) => f.code),
  ...relHttp.data.suspects.map((s) => s.code), ...relHttp.data.facts.map((f) => f.code),
  'DB_SQL_ERROR', 'HTTP_STATUS', 'SCRIPT_CHECK_FAILED',
].filter((c) => /_/.test(c) || c === 'SINGLE'))

/** Görünür metindeki sorunlar (boş dizi = temiz). */
function problems(root) {
  const text = root.textContent || ''
  const out = []
  for (const m of text.matchAll(/undefined|NaN|\[object Object\]/g)) out.push(`hatalı değer: ${m[0]}`)
  for (const m of text.matchAll(/\b(act|chkfail|kwfail|dbq|alh|meta)\.[a-z][A-Za-z_.]*/g)) out.push(`ham anahtar: ${m[0]}`)
  for (const m of text.matchAll(/\b\d{4}-\d{2}-\d{2}T\d{2}:\d{2}/g)) out.push(`ham ISO: ${m[0]}`)
  for (const code of RAW_CODES) {
    if (new RegExp(`(^|[^A-Za-z_])${code}([^A-Za-z_]|$)`).test(text)) out.push(`ham kod: ${code}`)
  }
  return out
}

const LANGS = [['tr', TR], ['en', EN]]

beforeEach(() => {
  vi.clearAllMocks()
  window.history.replaceState(null, '', '/?tab=activity')
  api.getActivity.mockResolvedValue(clone(listJson))
  api.getActivitySummary.mockResolvedValue(clone(summaryJson))
  api.teams.directory.mockResolvedValue({ success: true, data: [{ id: 1, name: 'Ödeme Kanalları' }, { id: 2, name: 'Altyapı' }] })
  api.getActivityRelated.mockResolvedValue(clone(relHttp))
  api.getActivityTimeline.mockResolvedValue(clone(tlHttp))
  api.admin.getAlert.mockResolvedValue({ success: false, status: 404 })
  api.admin.getAlertNotifications.mockResolvedValue({ success: true, data: [] })
  api.admin.getAlertPushDeliveries.mockResolvedValue({ success: true, data: [] })
  api.admin.getAlertStormPush.mockResolvedValue({ success: true, data: null })
  api.monitoring.getChangeDetail.mockResolvedValue({ success: false, status: 404 })
})
afterEach(() => { cleanup(); vi.restoreAllMocks() })

const setLang = (lang) => localStorage.setItem('site-monitor-lang', lang)

describe.each(LANGS)('gerçek dökümler — %s', (lang, D) => {
  beforeEach(() => setLang(lang))

  it('liste: satırlar, rozetler, KPI etiketleri — ham değer yok; DB sorgu nedeni adıyla, sayaç "toplam kayıt"', async () => {
    const { container } = render(<ActivityLog />)
    await waitFor(() => expect(container.querySelectorAll('[data-slot="act-item"], [data-slot="act-fold"]').length).toBeGreaterThan(0))
    await waitFor(() => expect(screen.getByText(D['act.sum.total'])).toBeInTheDocument())
    expect(problems(container)).toEqual([])
    const db = q('[data-slot="act-reason"][data-code="DB_SQL_ERROR"]', container)
    if (db) expect(db.textContent).toBe(D['dbq.fail.DB_SQL_ERROR'])
  })

  it.each([
    ['http503sched', d503], ['httpFail-limited', dLimited], ['httpFail', dHttpFail], ['certFail', dCert], ['dbqFail', dDbq],
    ['scrFail', dScr], ['configChanged', dCfg], ['alertTriggered', dAlert],
  ])('ayrıntı sayfası (%s): ham değer yok', async (_name, body) => {
    api.getActivityFullDetail.mockResolvedValue(clone(body))
    const { container } = render(<ActivityDetailPage id={body.data.id} onBack={() => {}} onOpenActivity={() => {}} onOpenTimeline={() => {}} />)
    await waitFor(() => expect(q('[data-slot="act-detail-page"]', container)).toHaveAttribute('data-phase', 'ready'))
    // Alt bölümlerin (kök neden paneli, değişiklik) okumaları otursun
    await waitFor(() => expect(q('[data-slot="act-related"][data-state="loading"]', container)).toBeNull())
    expect(problems(container)).toEqual([])
  })

  it('zamanlanmış HTTP 503 (WARNING kaydı, ok=false): başlık "Kontrol başarısız", alarm yok notu ve kök neden paneli çizilir', async () => {
    api.getActivityFullDetail.mockResolvedValue(clone(d503))
    const { container } = render(<ActivityDetailPage id={10} onBack={() => {}} onOpenActivity={() => {}} onOpenTimeline={() => {}} />)
    await waitFor(() => expect(q('[data-slot="act-detail-page"]', container)).toHaveAttribute('data-phase', 'ready'))
    const status = q('[data-slot="act-dt-status"]', container)
    expect(status).toHaveAttribute('data-status', 'WARNING')
    expect(status).toHaveAttribute('data-failed', 'true')
    expect(status).toHaveTextContent(D['act.dt.checkBadStatus'])
    expect(q('[data-slot="act-dt-check"]', container)).toHaveAttribute('data-health', 'down')
    expect(q('[data-slot="act-dt-alarm"]', container)).toHaveAttribute('data-state', 'none')
    await waitFor(() => expect(q('[data-slot="act-related"]', container)).toHaveAttribute('data-state', 'ok'))
    // Tek hata (be2: total 0, SINGLE) → "başka hatalı kontrol yok" görünür; "ilk N / M" yok
    expect(q('[data-slot="act-rel-total"]', container)).toHaveTextContent(D['act.rel.none'])
    expect(q('[data-slot="act-rel-total"]', container)).toHaveAttribute('data-others', '0')
  })

  it('gizli kontrol satırı (check_hidden): nötr "izniniz yok", sahte teşhis paneli ve "saklama süresi" metni YOK', async () => {
    api.getActivityFullDetail.mockResolvedValue(clone(dLimited))
    const { container } = render(<ActivityDetailPage id={3} onBack={() => {}} onOpenActivity={() => {}} onOpenTimeline={() => {}} />)
    await waitFor(() => expect(q('[data-slot="act-detail-page"]', container)).toHaveAttribute('data-phase', 'ready'))
    const chk = q('[data-slot="act-dt-check"]', container)
    expect(chk).toHaveAttribute('data-match', 'hidden')
    expect(q('[data-slot="act-dt-check-hidden"]', container)).toHaveAttribute('data-reason', 'permission')
    expect(chk).toHaveTextContent(D['act.dt.chk.hidden'])
    expect(chk.textContent).not.toContain(D['act.dt.chk.pseudoNote'])
    expect(q('[data-slot="chkfail-panel"]', container)).toBeNull()
    // Alarm (yaklaşık, can_open false) özetle çizilir, zincir kilitli
    expect(q('[data-slot="act-dt-alarm"]', container)).toHaveAttribute('data-state', 'linked')
    expect(q('[data-slot="act-dt-alarm-locked"]', container)).not.toBeNull()
  })

  it('takım nedeniyle gizli (check_hidden_reason team) kendi metniyle', async () => {
    api.getActivityFullDetail.mockResolvedValue({ ...clone(dLimited), check_hidden_reason: 'team' })
    const { container } = render(<ActivityDetailPage id={3} onBack={() => {}} onOpenActivity={() => {}} onOpenTimeline={() => {}} />)
    await waitFor(() => expect(q('[data-slot="act-dt-check-hidden"]', container)).toHaveAttribute('data-reason', 'team'))
    expect(q('[data-slot="act-dt-check"]', container)).toHaveTextContent(D['act.dt.chk.hiddenTeam'])
  })
})

describe('okunamayan bölümler — asla "yok" denmez (inceleme FR Y1 / CT Y2)', () => {
  beforeEach(() => setLang('en'))
  const page = () => render(<ActivityDetailPage id={10} onBack={() => {}} onOpenActivity={() => {}} onOpenTimeline={() => {}} />)

  it('check_error: "okunamadı" + Yeniden dene (yeni okuma); "bulunamadı" / saklama süresi metni yok', async () => {
    api.getActivityFullDetail.mockResolvedValue({ ...clone(d503), check: null, previous_check: null, next_check: null, check_error: true })
    const { container } = page()
    await waitFor(() => expect(q('[data-slot="act-dt-check-error"]', container)).not.toBeNull())
    const chk = q('[data-slot="act-dt-check"]', container)
    expect(chk).toHaveAttribute('data-match', 'error')
    expect(chk.textContent).not.toContain(EN['act.dt.chk.pseudoNote'])
    expect(chk.textContent).not.toContain(EN['act.dt.chk.none'])
    // Başarısız WARNING kontrolü: rozet tutarlı (kontrol satırı yokken aktivite alanlarından)
    expect(q('[data-slot="act-dt-status"]', container)).toHaveAttribute('data-failed', 'true')
    const calls = api.getActivityFullDetail.mock.calls.length
    fireEvent.click(screen.getByRole('button', { name: EN['act.dt.retry'] }))
    await waitFor(() => expect(api.getActivityFullDetail.mock.calls.length).toBeGreaterThan(calls))
  })

  it('alarm_error: tehlike bandı (bölüm çizilir) — "alarm bulunamadı" DEĞİL', async () => {
    api.getActivityFullDetail.mockResolvedValue({ ...clone(d503), alarm: null, alarm_error: true })
    const { container } = page()
    await waitFor(() => expect(q('[data-slot="act-dt-alarm"]', container)).toHaveAttribute('data-state', 'error'))
    expect(q('[data-slot="act-dt-alarm"]', container)).toHaveTextContent(EN['act.dt.al.readError'])
    expect(container.textContent).not.toContain(EN['act.dt.al.none'])
  })

  it('alarm satırında (ALERT_*) alarm okunamazsa da bölüm görünür', async () => {
    api.getActivityFullDetail.mockResolvedValue({ ...clone(dAlert), alarm: null, alarm_error: true })
    const { container } = render(<ActivityDetailPage id={dAlert.data.id} onBack={() => {}} onOpenActivity={() => {}} onOpenTimeline={() => {}} />)
    await waitFor(() => expect(q('[data-slot="act-dt-alarm"]', container)).toHaveAttribute('data-state', 'error'))
  })

  it('series_error / change_error: kendi "okunamadı" bantları (bölüm sessizce kaybolmaz)', async () => {
    api.getActivityFullDetail.mockResolvedValue({ ...clone(dCfg), series: null, series_error: true, change: null, change_error: true })
    const { container } = render(<ActivityDetailPage id={dCfg.data.id} onBack={() => {}} onOpenActivity={() => {}} onOpenTimeline={() => {}} />)
    await waitFor(() => expect(q('[data-slot="act-dt-series"]', container)).toHaveAttribute('data-state', 'error'))
    expect(q('[data-slot="act-dt-series"]', container)).toHaveTextContent(EN['act.dt.ser.readError'])
    expect(q('[data-slot="act-dt-change"]', container)).toHaveAttribute('data-state', 'error')
    expect(q('[data-slot="act-dt-change"]', container)).toHaveTextContent(EN['act.dt.chg.loadError'])
  })

  it('yaklaşık değişiklik eşleşmesi: rozet + açıklama (kesin gibi gösterilmez — CT O5)', async () => {
    const body = clone(dCfg)
    body.change = { ...(body.change || { kind: 'http', id: 2, seq: 1 }), match: 'approximate' }
    api.getActivityFullDetail.mockResolvedValue(body)
    const { container } = render(<ActivityDetailPage id={dCfg.data.id} onBack={() => {}} onOpenActivity={() => {}} onOpenTimeline={() => {}} />)
    await waitFor(() => expect(q('[data-slot="act-dt-change"]', container)).toHaveAttribute('data-match', 'approximate'))
    const sec = q('[data-slot="act-dt-change"]', container)
    expect(sec).toHaveTextContent(EN['act.dt.chg.approximate'])
    expect(q('[data-slot="act-dt-approx"]', sec)).not.toBeNull()
  })

  it('sertifika kontrol satırı hata ayrıntısını taşıyınca (be1) "eski kayıt, tahmin" notu çıkmaz', async () => {
    const body = clone(dCert)
    body.check.row = { ...body.check.row, error_class: 'DNS', error_stage: 'dns', resolved_ips: '[]', response_ms: 210 }
    api.getActivityFullDetail.mockResolvedValue(body)
    const { container } = render(<ActivityDetailPage id={dCert.data.id} onBack={() => {}} onOpenActivity={() => {}} onOpenTimeline={() => {}} />)
    await waitFor(() => expect(q('[data-slot="act-dt-check"]', container)).not.toBeNull())
    expect(q('[data-slot="chkfail-legacy"]', container)).toBeNull()
  })

  it('Keyword: kontrol satırı yokken `{text}` ayrıntısı düz metne iner — teknik blokta [object Object] yok (FR O4)', async () => {
    const body = clone(dLimited)
    delete body.check_hidden
    body.data = { ...body.data, monitor_type: 'KEYWORD', failure_reason: 'KEYWORD_NOT_FOUND', http_status: 200,
      failure_detail: { text: 'Aranan kelime sayfada yok (HTTP 200).' }, error_message: null, error_class: null }
    expect(normalizeDetail(body).data.failure_detail).toBe('Aranan kelime sayfada yok (HTTP 200).')
    api.getActivityFullDetail.mockResolvedValue(body)
    const { container } = render(<ActivityDetailPage id={3} onBack={() => {}} onOpenActivity={() => {}} onOpenTimeline={() => {}} />)
    await waitFor(() => expect(q('[data-slot="act-dt-check"]', container)).not.toBeNull())
    for (const b of container.querySelectorAll('button[aria-expanded="false"]')) fireEvent.click(b)
    expect(container.textContent).not.toContain('[object Object]')
  })
})

describe.each(LANGS)('zaman çizelgesi ve kök neden — %s', (lang, D) => {
  beforeEach(() => setLang(lang))

  it.each([['http2', tlHttp], ['dbq', tlDbq]])('izleme zaman çizelgesi (%s): ham değer yok', async (_n, body) => {
    api.getActivityTimeline.mockResolvedValue(clone(body))
    const m = body.data.monitor
    const { container } = render(<MonitorTimelineView type={m.type} id={m.id} onBack={() => {}} onOpenActivity={() => {}} onFilterList={() => {}} />)
    await waitFor(() => expect(q('[data-slot="act-tl-view"]', container)).toHaveAttribute('data-state', 'ok'))
    expect(problems(container)).toEqual([])
  })

  it('kök neden (DNS, 5 başka hata): sayılar bu kayıt HARİÇ — "ilk N / M" yok, dağılımda kaydın kendi nedeni yok', async () => {
    api.getActivityRelated.mockResolvedValue(clone(relDns))
    const { container } = render(<RelatedActivityPanel activityId={18} onOpenActivity={() => {}} />)
    await waitFor(() => expect(q('[data-slot="act-related"]', container)).toHaveAttribute('data-state', 'ok'))
    expect(problems(container)).toEqual([])
    const total = q('[data-slot="act-rel-total"]', container)
    expect(total).toHaveAttribute('data-others', '5')
    expect(total).toHaveTextContent(D['act.rel.total'].replace('{0}', '5'))
    expect(container.textContent).not.toContain(D['act.rel.itemsCapped'].replace('{0}', '5').replace('{1}', '5'))
    const reasons = q('[data-slot="act-rel-dist"][data-kind="reason"]', container)
    expect(reasons.textContent).not.toContain(D['chkfail.DNS_NXDOMAIN.short'])
  })
})

describe('kök neden sayıları — eski sunucu ve tavan', () => {
  it('eski sunucu (window_total yok, total bu kaydı da sayar) → başka = total − 1', () => {
    const old = clone(relDns)
    delete old.data.window_total
    delete old.data.anchor_in_window
    old.data.total = 6
    expect(unwrapRelated(old).others).toBe(5)
    expect(unwrapRelated(old).legacyCounts).toBe(true)
    const solo = clone(relHttp)
    delete solo.data.window_total
    delete solo.data.anchor_in_window
    solo.data.total = 1
    expect(unwrapRelated(solo).others).toBe(0)
  })

  it('truncated → "en az N" (sayılar alt sınır)', async () => {
    setLang('en')
    const body = clone(relDns)
    body.data.truncated = true
    api.getActivityRelated.mockResolvedValue(body)
    const { container } = render(<RelatedActivityPanel activityId={18} onOpenActivity={() => {}} />)
    await waitFor(() => expect(q('[data-slot="act-rel-total"]', container)).toHaveAttribute('data-truncated', 'true'))
    expect(q('[data-slot="act-rel-total"]', container)).toHaveTextContent(EN['act.rel.totalAtLeast'].replace('{0}', '5'))
  })

  it('yeni sunucuda 404 = kayıt bulunamadı / erişim yok; eski pod 400 INVALID_PARAMETER = bu sürümde yok', async () => {
    setLang('en')
    api.getActivityRelated.mockResolvedValueOnce({ success: false, status: 404 })
    const { container, unmount } = render(<RelatedActivityPanel activityId={18} onOpenActivity={() => {}} />)
    await waitFor(() => expect(q('[data-slot="act-related"]', container)).toHaveAttribute('data-state', 'notFound'))
    expect(container).toHaveTextContent(EN['act.rel.notFound'])
    unmount()
    api.getActivityRelated.mockResolvedValueOnce({ success: false, status: 400, code: 'INVALID_PARAMETER' })
    const r2 = render(<RelatedActivityPanel activityId={18} onOpenActivity={() => {}} />)
    await waitFor(() => expect(q('[data-slot="act-related"]', r2.container)).toHaveAttribute('data-state', 'unsupported'))
  })
})

describe.each(LANGS)('Excel modeli gerçek JSON dökümüyle — %s', (lang, D) => {
  it('hücrelerde undefined / NaN / ham neden kodu / ham kişi ("scheduler", "system") yok; özet jetonları çevrilir', () => {
    const t = (k, ...a) => {
      let s = D[k] ?? k
      a.forEach((v, i) => { s = s.split(`{${i}}`).join(String(v)) })
      return s
    }
    const body = unwrapExportJson(clone(exportJson))
    expect(body.rows.length).toBeGreaterThan(0)
    const sheet = excelSheet(body.rows, t, lang)
    const cells = sheet.rows.slice(1).flat()
    for (const c of cells) {
      expect(String(c)).not.toMatch(/undefined|NaN|\[object Object\]/)
      expect(['scheduler', 'system']).not.toContain(c)
      expect(RAW_CODES.has(String(c))).toBe(false)
    }
    if (lang === 'en') {
      // Türkçe özet jetonları İngilizce dosyaya düşmez
      expect(cells.join(' ')).not.toMatch(/(^|\s)(satır|değer|çalıştırılamadı|kırık|istek)(\s|$)/)
    } else {
      // Türkçe dosyada İngilizce durum sözcükleri (error / fail / closed) tek başına kalmaz
      const summaries = sheet.rows.slice(1).map((r) => r[7])
      for (const s of summaries) expect(String(s)).not.toMatch(/(^|\s)(error|fail|closed)(\s|$)/)
    }
  })
})
