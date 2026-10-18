import { describe, it, expect } from 'vitest'
import fs from 'node:fs'
import path from 'node:path'
import {
  FIELD_KEYS, SECTION_IDS, changedFields, exporterState, failedSources, formToBody, isDirty, namespaceOf, orderSections, scrapeSnippet,
  selectorOk, toForm, urlOk, validateForm,
} from '../components/admin/grafana/grafanaModel.js'

// Ayarlar → Grafana panosu modeli (2026-10-18): form ↔ sunucu biçimi, yalnız değişen alanlar, istemci kuralları (sunucuyla
// AYNI doğruluk tablosu — Java kaynağından bölüm ve alan listesi okunur), aktarıcı durumu, kazıma örneği.
const BACKEND = path.resolve(__dirname, '../../../backend/src/main/java/com/sitemonitor')
const read = (p) => fs.readFileSync(path.join(BACKEND, p), 'utf8')

describe('grafanaModel — sunucuyla aynı listeler', () => {
  it('bölüm sırası GrafanaDashboardGenerator.SECTIONS ile aynı', () => {
    const src = read('service/grafana/GrafanaDashboardGenerator.java')
    const m = /List<String> SECTIONS =\s*List\.of\(([^)]*)\)/.exec(src)
    expect(m, 'SECTIONS okunamadı').toBeTruthy()
    expect([...m[1].matchAll(/"([^"]+)"/g)].map((x) => x[1])).toEqual(SECTION_IDS)
  })

  it('form alanları GrafanaDashboardController.FIELDS ile aynı ad ve sırada', () => {
    const src = read('controller/GrafanaDashboardController.java')
    const keys = [...src.matchAll(/FIELDS\.put\("([^"]+)"/g)].map((x) => x[1])
    expect(keys).toEqual(FIELD_KEYS)
  })
})

describe('grafanaModel — doğrulama doğruluk tablosu (GrafanaDashboardSettingsTest ile aynı durumlar)', () => {
  const okSelectors = ['', 'namespace="site-monitor-prod"', 'job="site-monitor", namespace=~"sm-.*"', 'app!="x"', 'env!~"te\\"st"', '  a = "b" ']
  const badSelectors = ['namespace=site', '{job="x"}', 'job="x",', '="x"', '9job="x"', 'job=="x"', 'job="x', 'job="x" namespace="y"',
    'jöb="x"', 'job="a\nb"', `a="${'x'.repeat(400)}"`]
  it.each(okSelectors)('geçerli eşleyici: %s', (s) => { expect(selectorOk(s)).toBe(true) })
  it.each(badSelectors)('geçersiz eşleyici: %s', (s) => { expect(selectorOk(s)).toBe(false) })

  it('bağlantı kökü', () => {
    for (const ok of ['', 'https://sm.example.org', 'http://10.0.0.1:8080/sitemonitor']) expect(urlOk(ok), ok).toBe(true)
    for (const bad of ['ftp://x', 'https://', 'https://x.org/?tab=a', 'https://x.org/#a', 'https://x y', 'javascript:alert(1)',
      'https://x.org/"', 'https:///x']) expect(urlOk(bad), bad).toBe(false)
  })

  it('alan hataları anahtar + parametreyle; boş sayı alanı geçerli (varsayılana dön)', () => {
    const form = toForm({ title: 'x'.repeat(121), uid: 'bad uid', link_base_url: 'ftp://x', selector: 'x', sections: [], top_n: 1,
      export_max_entities: 10, export_refresh_seconds: '', default_team: 'Yok' })
    const errs = validateForm(form, { top_n: { min: 3, max: 100 }, export_max_entities: { min: 100, max: 50000 } }, ['Dijital Kanallar'])
    expect(Object.keys(errs).sort()).toEqual(['default_team', 'export_max_entities', 'link_base_url', 'sections', 'selector', 'title',
      'top_n', 'uid'])
    expect(errs.top_n).toEqual({ key: 'grafana.v.range', params: [3, 100] })
    expect(validateForm(toForm({ title: 'a\tb' })).title.key).toBe('grafana.v.titleControl')
    // Takım dizini yoksa (kapsamlı müdür) takım adı denetlenmez
    expect(validateForm(toForm({ default_team: 'Herhangi' }), {}, [])).toEqual({})
  })
})

describe('grafanaModel — form ve değişiklikler', () => {
  const values = { title: '', uid: 'sitemonitor-overview', link_base_url: '', selector: '', time_from: 'now-24h', refresh: '1m',
    language: 'tr', sections: ['runtime', 'overview'], per_monitor: true, default_team: '', top_n: 10, export_enabled: true,
    export_per_monitor: false, export_max_entities: 5000, export_refresh_seconds: 60 }

  it('toForm: sayılar metin, bölümler katalog sırasında, eksik bool = açık', () => {
    const f = toForm(values)
    expect(f.top_n).toBe('10')
    expect(f.sections).toEqual(['overview', 'runtime'])
    expect(f.export_per_monitor).toBe(false)
    expect(toForm({}).per_monitor).toBe(true)
    expect(toForm({}).sections).toEqual(SECTION_IDS)
  })

  it('changedFields: yalnız değişenler, türleriyle; bölüm sırası değişiklik sayılmaz; boşluk farkı değişiklik değil', () => {
    const saved = toForm(values)
    expect(changedFields(saved, { ...saved })).toEqual({})
    expect(isDirty(saved, { ...saved, sections: ['runtime', 'overview'] })).toBe(false)
    expect(changedFields(saved, { ...saved, uid: ' sitemonitor-overview ' })).toEqual({})
    expect(changedFields(saved, { ...saved, top_n: '20', per_monitor: false, sections: ['alerts'], selector: ' job="x" ' }))
      .toEqual({ top_n: 20, per_monitor: false, sections: ['alerts'], selector: 'job="x"' })
    expect(changedFields(saved, { ...saved, top_n: '' })).toEqual({ top_n: '' })
    expect(changedFields(null, saved)).toEqual({})
    expect(formToBody(saved).export_max_entities).toBe(5000)
    expect(orderSections(['x', 'health', 'alerts', 'health'])).toEqual(['alerts', 'health'])
  })
})

describe('grafanaModel — aktarıcı durumu ve kazıma örneği', () => {
  const base = { state: 'running', enabled: true, refresh_seconds: 60, refreshed_at: '2026-10-18T08:00:00', server_now: '2026-10-18T08:01:00',
    sources: [{ source: 'overview', up: true }] }
  it('çalışıyor / kısmen / takılmış / bekliyor / kapalı / bilinmiyor — bilinmeyen asla "çalışıyor" değil', () => {
    expect(exporterState(base)).toBe('running')
    expect(exporterState({ ...base, sources: [{ source: 'alerts', up: false }] })).toBe('degraded')
    expect(exporterState({ ...base, server_now: '2026-10-18T08:04:00' })).toBe('stale')
    expect(exporterState({ ...base, refreshed_at: null })).toBe('stale')
    expect(exporterState({ state: 'waiting', enabled: true })).toBe('waiting')
    expect(exporterState({ state: 'disabled', enabled: false })).toBe('disabled')
    expect(exporterState(null)).toBe('unknown')
    expect(exporterState({ state: 'weird' })).toBe('unknown')
    expect(failedSources({ sources: [{ source: 'alerts', up: false }, { source: 'x', up: true }] })).toEqual(['alerts'])
  })

  it('kazıma örneği eşleyicideki ad alanını kullanır; yoksa yer tutucu', () => {
    expect(namespaceOf('job="x", namespace="site-monitor-prod"')).toBe('site-monitor-prod')
    expect(namespaceOf('job="x"')).toBeNull()
    expect(scrapeSnippet('namespace="sm-prod"')).toContain('names: [sm-prod]')
    expect(scrapeSnippet('')).toContain('<site-monitor-namespace>')
    expect(scrapeSnippet('')).toContain('metrics_path: /metrics')
  })
})
