import { describe, it, expect, beforeEach } from 'vitest'
import {
  MAX_WIDGETS, WIDGET_CATALOG, DEFAULT_TYPES, addWidget, addableTypes, alertsSummary, certSummary, columnsFor, defaultLayout,
  effectiveLayout, monitorSummary, moveWidget, moveWidgetTo, normalizeLayout, oncallRows, paginate, qualityTone, readTvSettings,
  removeWidget, resizeWidget, rowsFor, sameLayout, spanOf, statusSummary, stormSummary, toDoc, typeVisible, visibleWidgets,
  writeTvSettings,
} from '../components/mydash/myDashModel.js'
import { WIDGETS } from '../components/mydash/widgets.jsx'

/**
 * Panom saf modeli (2026-10-11): katalog ↔ bileşen eşlemesi, yerleşim normalizasyonu (sunucu kurallarıyla aynı), düzenleme
 * işlemleri (gizli kutuyu koruyarak), TV sayfalaması (CSS ızgara yerleşimi taklidi) ve kutu özetleri.
 */
const ALL = () => true
const NONE = () => false

describe('katalog', () => {
  it('her katalog türünün bir bileşeni var, bileşen listesi katalogla aynı', () => {
    expect(Object.keys(WIDGETS).sort()).toEqual(WIDGET_CATALOG.map((w) => w.type).sort())
  })
  it('izin isteyen kutu yetkisizken görünmez, izinsiz kutu herkese görünür', () => {
    expect(typeVisible('alerts_open', NONE)).toBe(true)
    expect(typeVisible('watchdog', NONE)).toBe(false)
    expect(typeVisible('watchdog', (p) => p === 'system_health.read')).toBe(true)
    expect(typeVisible('bilinmeyen_kutu', ALL)).toBe(false)
  })
  it('varsayılan yerleşim yetkisiz kutuları atlar ve kimlikler sunucu biçiminde', () => {
    const all = defaultLayout(ALL)
    expect(all.map((w) => w.type)).toEqual(DEFAULT_TYPES)
    const none = defaultLayout(NONE)
    expect(none.map((w) => w.type)).toEqual(['alerts_open', 'cert_expiring', 'monitor_health', 'status_services', 'activity'])
    for (const w of all) expect(w.id).toMatch(/^[a-z0-9-]{1,40}$/)
  })
})

describe('yerleşim', () => {
  it('normalize: bozuk satır, yinelenen kimlik/tür ve geçersiz boyut düşer; boş dizi geçerli', () => {
    expect(normalizeLayout(null)).toBeNull()
    expect(normalizeLayout({})).toBeNull()
    expect(normalizeLayout({ widgets: [] })).toEqual([])
    const out = normalizeLayout({ widgets: [
      { id: 'a', type: 'alerts_open', size: 'md' },
      { id: 'a', type: 'activity', size: 'sm' },          // kimlik tekrarı
      { id: 'b', type: 'alerts_open', size: 'sm' },       // tür tekrarı
      { id: 'C', type: 'activity', size: 'sm' },          // büyük harf kimlik
      { id: 'd', type: 'activity', size: 'xl' },          // boyut
      null,
      { id: 'e', type: 'gelecekteki_kutu', size: 'lg' },  // bilinmeyen tür SAKLANIR
    ] })
    expect(out).toEqual([{ id: 'a', type: 'alerts_open', size: 'md' }, { id: 'e', type: 'gelecekteki_kutu', size: 'lg' }])
  })
  it('kayıt yoksa varsayılan; kayıt boşsa boş pano', () => {
    expect(effectiveLayout(null, ALL)).toEqual(defaultLayout(ALL))
    expect(effectiveLayout({ v: 1, widgets: [] }, ALL)).toEqual([])
  })
  it('görünür kutular bilinmeyen ve yetkisiz türleri gizler; belge biçimi sunucuyla aynı', () => {
    const list = [{ id: 'a', type: 'watchdog', size: 'sm' }, { id: 'b', type: 'gelecekteki_kutu', size: 'sm' }, { id: 'c', type: 'activity', size: 'md' }]
    expect(visibleWidgets(list, NONE).map((w) => w.id)).toEqual(['c'])
    expect(toDoc(list)).toEqual({ v: 1, widgets: list })
  })
  it('ekle: her tür bir kez, katalog boyutuyla; tavan aşılmaz', () => {
    const one = addWidget([], 'oncall')
    expect(one).toEqual([{ id: 'oncall', type: 'oncall', size: 'sm' }])
    expect(addWidget(one, 'oncall')).toBe(one)
    expect(addWidget(one, 'bilinmeyen')).toBe(one)
    const full = Array.from({ length: MAX_WIDGETS }, (_, i) => ({ id: `x${i}`, type: `t_${'abcdefghijklmnopqrstuvwxyz'[i]}`, size: 'sm' }))
    expect(addWidget(full, 'oncall')).toBe(full)
    expect(addableTypes([{ id: 'a', type: 'alerts_open', size: 'md' }], NONE)).not.toContain('alerts_open')
    expect(addableTypes([], NONE)).not.toContain('watchdog')
  })
  it('kaldır / boyutlandır', () => {
    const l = defaultLayout(ALL)
    expect(removeWidget(l, 'oncall').some((w) => w.type === 'oncall')).toBe(false)
    expect(resizeWidget(l, 'oncall', 'lg').find((w) => w.id === 'oncall').size).toBe('lg')
    expect(resizeWidget(l, 'oncall', 'xl')).toBe(l)
  })
  it('taşı: gizli kutu araya girmez ve yerini korur; kenarda değişmez', () => {
    const l = [{ id: 'a', type: 'alerts_open', size: 'sm' }, { id: 'h', type: 'watchdog', size: 'sm' }, { id: 'b', type: 'activity', size: 'sm' }]
    const vis = (w) => w.type !== 'watchdog'
    expect(moveWidget(l, 'b', -1, vis).map((w) => w.id)).toEqual(['b', 'h', 'a'])
    expect(moveWidget(l, 'a', -1, vis)).toBe(l)
    expect(moveWidget(l, 'b', 1, vis)).toBe(l)
    expect(moveWidgetTo(l, 'b', 'a').map((w) => w.id)).toEqual(['b', 'a', 'h'])
    expect(moveWidgetTo(l, 'a', 'b').map((w) => w.id)).toEqual(['h', 'b', 'a'])
    expect(moveWidgetTo(l, 'a', 'a')).toBe(l)
    expect(sameLayout(l, l.map((w) => ({ ...w })))).toBe(true)
    expect(sameLayout(l, moveWidgetTo(l, 'b', 'a'))).toBe(false)
  })
})

describe('ızgara + TV sayfaları', () => {
  it('sütun eşikleri kap sorgularıyla aynı', () => {
    expect([0, 671, 672, 895, 896, 1279, 1280].map(columnsFor)).toEqual([1, 1, 2, 2, 3, 3, 4])
    expect([spanOf('sm', 4), spanOf('md', 4), spanOf('lg', 4), spanOf('md', 1), spanOf('lg', 3)]).toEqual([1, 2, 4, 1, 3])
    expect([500, 800, 844, 1080, 1440, 2160].map(rowsFor)).toEqual([1, 1, 1, 2, 3, 3])
  })
  it('sayfalama yoğun: kutu sayfanın sığdığı ilk satırına girer, satırlar dolunca yeni sayfa', () => {
    const w = (id, size) => ({ id, type: 'x', size })
    // 3 sütun × 2 satır: md | md, sonra küçükler önceki satırların sonundaki boşluklara → tek sayfa
    expect(paginate([w('a', 'md'), w('b', 'md'), w('c', 'sm'), w('d', 'sm')], 3, 2)).toEqual([[w('a', 'md'), w('b', 'md'), w('c', 'sm'), w('d', 'sm')]])
    // 3 sütun × 1 satır: ikinci orta kutu sığmaz → yeni sayfa; küçük kutu o sayfanın satırına
    expect(paginate([w('a', 'md'), w('b', 'md'), w('c', 'sm')], 3, 1).map((p) => p.map((x) => x.id))).toEqual([['a'], ['b', 'c']])
  })
  it('sayfalama satıra sığmayanı alt satıra, satır dolunca yeni sayfaya taşır', () => {
    const w = (id, size) => ({ id, type: 'x', size })
    // 4 sütun × 2 satır: [md md] [sm sm md] → sayfa 1; [lg] → sayfa 2
    const pages = paginate([w('a', 'md'), w('b', 'md'), w('c', 'sm'), w('d', 'sm'), w('e', 'md'), w('f', 'lg')], 4, 2)
    expect(pages.map((p) => p.map((x) => x.id))).toEqual([['a', 'b', 'c', 'd', 'e'], ['f']])
    // 3 sütun: sm, md → 1. satır dolu; md → 2. satır; lg → 3. satır = yeni sayfa
    expect(paginate([w('a', 'sm'), w('b', 'md'), w('c', 'md'), w('d', 'lg')], 3, 2).map((p) => p.length)).toEqual([3, 1])
    expect(paginate([], 4, 2)).toEqual([])
    expect(paginate([w('a', 'lg')], 1, 1)).toEqual([[w('a', 'lg')]])
  })
  describe('TV ayarı', () => {
    beforeEach(() => { localStorage.clear() })
    it('geçersiz değer varsayılana düşer; yazılan okunur', () => {
      expect(readTvSettings()).toEqual({ interval: 30 })
      localStorage.setItem('sm.mydash.tv', '{"interval": 7}')
      expect(readTvSettings()).toEqual({ interval: 30 })
      writeTvSettings({ interval: 60 })
      expect(readTvSettings()).toEqual({ interval: 60 })
      localStorage.setItem('sm.mydash.tv', 'bozuk')
      expect(readTvSettings()).toEqual({ interval: 30 })
    })
  })
})

describe('kutu özetleri', () => {
  it('alarmlar: seviyeler türler arası toplanır, öğeler yeniden eskiye', () => {
    const s = alertsSummary({ total: 4, tabs: {
      http: { count: 2, unacked: 1, levels: { critical: 1, high: 0, warning: 1, other: 0 }, items: [
        { id: 1, created_at: '2026-10-11T08:00:00', domain: 'a.example.com' }, { id: 2, created_at: '2026-10-11T10:00:00', domain: 'b.example.com' }] },
      ping: { count: 2, unacked: 2, levels: { critical: 0, high: 2, warning: 0, other: 0 }, items: [{ id: 3, created_at: '2026-10-11T09:00:00', domain: 'c.example.com' }] },
    } }, 2)
    expect(s).toMatchObject({ total: 4, critical: 1, high: 2, warning: 1, other: 0, unacked: 3, tone: 'danger' })
    expect(s.items.map((i) => i.id)).toEqual([2, 3])
    expect(alertsSummary(null).tone).toBe('success')
    expect(alertsSummary({ total: 1, tabs: { http: { levels: { warning: 1 } } } }).tone).toBe('warning')
  })
  it('izleme: sağlıklı = etkin − sorunlu − gecikmiş − bilinmiyor; sorunlu tür önce', () => {
    const s = monitorSummary({ totals: { active: 10, down: 1, stale: 2, unknown: 1, paused: 3, success_rate_window: 97.5 }, types: [
      { type: 'http', active: 5, down: 0, stale: 2 }, { type: 'ping', active: 5, down: 1, stale: 0 }, { type: 'dns', active: 0 }] })
    expect(s).toMatchObject({ active: 10, healthy: 6, healthyPct: 60, successRate: 97.5, tone: 'danger' })
    expect(s.types.map((x) => x.type)).toEqual(['ping', 'http'])
    expect(monitorSummary({}).healthyPct).toBeNull()
  })
  it('sertifika: acil alan adları tekilleşir, ton bitişe göre', () => {
    const s = certSummary({ total_certificates: 20, valid_count: 15, expired: 1, expiring_in_7_days: 0, expiring_in_30_days: 3,
      expired_domains: ['x.example.com'], critical_domains: ['x.example.com', 'y.example.com', 'z.example.com'] }, 2)
    expect(s).toMatchObject({ total: 20, valid: 15, expired: 1, exp30: 3, urgent: ['x.example.com', 'y.example.com'], urgentMore: 1, tone: 'danger' })
    expect(certSummary({ expiring_in_30_days: 1 }).tone).toBe('warning')
    expect(certSummary({}).tone).toBe('success')
  })
  it('hizmet durumu: en kötü durum ve sorunlular ağırdan hafife', () => {
    const s = statusSummary({ services: [
      { key: 'a', state: 'operational' }, { key: 'b', state: 'degraded', since: '2026-10-11T08:00:00' },
      { key: 'c', state: 'major_outage', since: '2026-10-11T09:00:00' }, { key: 'd', state: 'maintenance' }] })
    expect(s.worst).toBe('major_outage')
    expect(s.problems.map((p) => p.key)).toEqual(['c', 'b'])
    expect(s.tone).toBe('danger')
    expect(statusSummary({ services: [] })).toMatchObject({ worst: 'no_data', tone: 'muted' })
  })
  it('fırtına: yalnız fırtınadaki ve eşiğe yakın takımlar, fırtına önce', () => {
    const s = stormSummary({ settings: { enabled: true }, totals: { storming: 1, near: 1 }, teams: [
      { team_id: 1, status: 'NEAR', window_targets: 4 }, { team_id: 2, status: 'CALM' }, { team_id: 3, status: 'STORM', window_targets: 9 }] })
    expect(s.teams.map((x) => x.team_id)).toEqual([3, 1])
    expect(s.tone).toBe('danger')
    expect(stormSummary({ settings: { enabled: false } }).enabled).toBe(false)
  })
  it('nöbet: takım adı eklenir ve Türkçe sıralanır', () => {
    const rows = oncallRows([{ team_id: 2, name: 'B' }, { team_id: 1, name: 'A' }], [{ id: 1, name: 'Ödeme' }, { id: 2, name: 'Çağrı' }])
    expect(rows.map((r) => r.team_name)).toEqual(['Çağrı', 'Ödeme'])
  })
  it('veri kalitesi bandı → ton', () => {
    expect(['EXCELLENT', 'GOOD', 'NEEDS_ATTENTION', 'POOR', 'NO_DATA'].map(qualityTone)).toEqual(['success', 'success', 'warning', 'danger', 'muted'])
  })
})
