import { describe, it, expect, beforeEach } from 'vitest'
import {
  MAX_WIDGETS, WIDGET_CATALOG, DEFAULT_TYPES, addWidget, addableTypes, alertsSummary, certSummary, columnsFor, defaultLayout,
  effectiveLayout, monitorSummary, moveWidget, moveWidgetTo, normalizeLayout, oncallRows, paginate, qualityTone, readTvSettings,
  removeWidget, resizeWidget, rowsFor, sameLayout, spanOf, statusSummary, stormSummary, toDoc, typeVisible, visibleWidgets,
  writeTvSettings, NOC_CONSOLE, NOC_PRESET, NOC_CALL_SLA_MIN, STYLES, incidentsSummary, lightSummary, maintenanceSummary,
  nocUncalledSummary, presetLayout, setWidgetStyle, statStyleOf, MATRIX_NO_TEAM, healthTone, matrixCellFocus, serviceGrid, teamTypeMatrix, typeHeat,
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
    expect(typeVisible('cert_expiring', NONE)).toBe(true)
    // 2026-10-12: alarm / izleme kutuları uçlarının iznini ister (alerts.read / monitoring.read)
    expect(typeVisible('alerts_open', NONE)).toBe(false)
    expect(typeVisible('monitor_health', NONE)).toBe(false)
    expect(typeVisible('monitor_health', (p) => p === 'monitoring.read')).toBe(true)
    expect(typeVisible('watchdog', NONE)).toBe(false)
    expect(typeVisible('watchdog', (p) => p === 'system_health.read')).toBe(true)
    expect(typeVisible('bilinmeyen_kutu', ALL)).toBe(false)
  })
  it('varsayılan yerleşim yetkisiz kutuları atlar ve kimlikler sunucu biçiminde', () => {
    const all = defaultLayout(ALL)
    expect(all.map((w) => w.type)).toEqual(DEFAULT_TYPES)
    const none = defaultLayout(NONE)
    expect(none.map((w) => w.type)).toEqual(['cert_expiring', 'status_services', 'activity'])
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
      expect(readTvSettings()).toEqual({ interval: 30, panels: 'stat', density: 'compact' })
      localStorage.setItem('sm.mydash.tv', '{"interval": 7}')
      expect(readTvSettings()).toEqual({ interval: 30, panels: 'stat', density: 'compact' })
      writeTvSettings({ interval: 60 })
      expect(readTvSettings()).toEqual({ interval: 60, panels: 'stat', density: 'compact' })
      localStorage.setItem('sm.mydash.tv', 'bozuk')
      expect(readTvSettings()).toEqual({ interval: 30, panels: 'stat', density: 'compact' })
    })
    it('2026-10-13: renkli paneller TV\'de varsayılan; kapatılınca hatırlanır; bilinmeyen değer varsayılana düşer', () => {
      writeTvSettings({ interval: 15, panels: 'detail' })
      expect(readTvSettings()).toEqual({ interval: 15, panels: 'detail', density: 'compact' })
      localStorage.setItem('sm.mydash.tv', '{"interval": 15, "panels": "neon"}')
      expect(readTvSettings()).toEqual({ interval: 15, panels: 'stat', density: 'compact' })
      // 2026-10-13: yoğunluk hatırlanır; bilinmeyen değer varsayılana (Sık) düşer
      writeTvSettings({ interval: 15, panels: 'stat', density: 'dense' })
      expect(readTvSettings().density).toBe('dense')
      writeTvSettings({ interval: 15, panels: 'stat', density: 'devasa' })
      expect(readTvSettings().density).toBe('compact')
      // Kutunun kendi görünümü her yerde; TV ayarı yalnız TV'de
      expect(statStyleOf({ style: 'stat' }, false, 'detail')).toBe(true)
      expect(statStyleOf({}, false, 'stat')).toBe(false)
      expect(statStyleOf({}, true, 'stat')).toBe(true)
      expect(statStyleOf({}, true, 'detail')).toBe(false)
    })
  })
})

describe('2026-10-13: 7/24 Komuta kutuları + renkli panel görünümü', () => {
  it('konsol kutusu 7/24 konsolunu açabilene görünür (sahte izin), diğer yeni kutular kendi iznine bağlı', () => {
    expect(typeVisible('noc_uncalled', (p) => p === 'alerts.read')).toBe(false)
    expect(typeVisible('noc_uncalled', (p) => p === 'alerts.read' || p === NOC_CONSOLE)).toBe(true)
    expect(typeVisible('noc_uncalled', (p) => p === NOC_CONSOLE)).toBe(false)
    expect(typeVisible('noc_light', (p) => p === 'alerts.read')).toBe(true)
    expect(typeVisible('maintenance', NONE)).toBe(false)
    expect(typeVisible('maintenance', (p) => p === 'maintenance.view')).toBe(true)
    expect(typeVisible('incidents_open', (p) => p === 'incidents.view')).toBe(true)
    // Varsayılan pano değişmedi (mevcut kullanıcıların düzeni bozulmaz)
    expect(DEFAULT_TYPES).not.toContain('noc_light')
  })
  it('görünüm alanı: yalnız "stat" saklanır, belgeye yazılır, karşılaştırmaya girer; set/sil', () => {
    expect(STYLES).toEqual(['stat'])
    const list = normalizeLayout({ widgets: [
      { id: 'a', type: 'alerts_open', size: 'md', style: 'stat' },
      { id: 'b', type: 'storms', size: 'sm', style: 'neon' }] })
    expect(list).toEqual([{ id: 'a', type: 'alerts_open', size: 'md', style: 'stat' }, { id: 'b', type: 'storms', size: 'sm' }])
    expect(toDoc(list).widgets).toEqual(list)
    const off = setWidgetStyle(list, 'a', null)
    expect(off[0]).toEqual({ id: 'a', type: 'alerts_open', size: 'md' })
    expect(sameLayout(list, off)).toBe(false)
    expect(setWidgetStyle(off, 'b', 'stat')[1].style).toBe('stat')
    expect(setWidgetStyle(off, 'b', 'neon')).toBe(off)
  })
  it('7/24 hazır düzeni: yetkisiz kutu düşer, çizilemeyen mevcut kutular korunur, tekrar yok, tavan aşılmaz', () => {
    const canSee = (p) => p !== NOC_CONSOLE && p !== 'incidents.view'
    const current = [{ id: 'x', type: 'gelecek_kutu', size: 'sm' }, { id: 'alerts-open', type: 'alerts_open', size: 'sm' }]
    const out = presetLayout(NOC_PRESET, current, canSee)
    expect(out.map((w) => w.type)).toEqual(['noc_light', 'alerts_open', 'maintenance', 'monitor_health', 'storms', 'oncall',
      'status_services', 'gelecek_kutu'])
    expect(out.find((w) => w.type === 'alerts_open').size).toBe('md')
    expect(new Set(out.map((w) => w.id)).size).toBe(out.length)
    expect(presetLayout(NOC_PRESET, [], ALL).length).toBeLessThanOrEqual(MAX_WIDGETS)
  })
  it('durum lambası: kritik/yüksek kırmızı, yalnız uyarı turuncu, açık alarm yok yeşil; yetkisiz gri; en eski sunucudan', () => {
    const tabs = (lv) => ({ http: { count: 1, unacked: 0, levels: lv, items: [] } })
    expect(lightSummary({ total: 2, tabs: tabs({ critical: 0, high: 1, warning: 1, other: 0 }), oldest_open_at: '2026-10-13T05:00:00', oldest_severe_at: '2026-10-13T06:00:00' }))
      .toMatchObject({ tone: 'danger', severe: 1, oldest: '2026-10-13T05:00:00', oldestSevere: '2026-10-13T06:00:00' })
    expect(lightSummary({ total: 1, tabs: tabs({ critical: 0, high: 0, warning: 0, other: 1 }) })).toMatchObject({ tone: 'warning', oldest: null })
    expect(lightSummary({ total: 0, tabs: {} }).tone).toBe('success')
    expect(lightSummary({ visible: false, total: 0, tabs: {} }).tone).toBe('muted')
  })
  it('aranmayı bekleyenler: en uzun bekleyen önce; SLA aşan kırmızı; aranmış/çözülmüş satır sayılmaz; okunan < toplam → alt sınır', () => {
    const now = Date.parse('2026-10-13T10:00:00Z')
    const d = { total: 60, items: [
      { id: 1, domain: 'a.example.com', noc: { sent_at: '2026-10-13T09:55:00' }, call_count: 0 },
      { id: 2, domain: 'b.example.com', noc: { sent_at: '2026-10-13T09:30:00Z' }, call_count: 0 },
      { id: 3, domain: 'c.example.com', noc: { sent_at: '2026-10-13T09:00:00' }, call_count: 2 },
      { id: 4, domain: 'd.example.com', created_at: '2026-10-13T09:40:00', noc: null, call_count: 0, resolved: true }] }
    const s = nocUncalledSummary(d, now)
    expect(s.rows.map((r) => r.id)).toEqual([2, 1])
    expect(s.rows[0]).toMatchObject({ waitMin: 30, overdue: true })
    expect(s.rows[1]).toMatchObject({ waitMin: 5, overdue: false })
    expect(s).toMatchObject({ total: 60, overdue: 1, overdueAtLeast: true, tone: 'danger', oldestSentAt: '2026-10-13T09:30:00Z' })
    expect(nocUncalledSummary({ total: 1, items: [{ id: 9, noc: { sent_at: '2026-10-13T09:58:00' }, call_count: 0 }] }, now).tone).toBe('warning')
    expect(nocUncalledSummary({ total: 0, items: [] }, now).tone).toBe('success')
    expect(NOC_CALL_SLA_MIN).toBe(15)
    // İki sayfa (ilk + son) kesişirse satır bir kez sayılır
    const dup = nocUncalledSummary({ total: 2, items: [d.items[0], d.items[1], d.items[1]] }, now)
    expect(dup.rows.map((r) => r.id)).toEqual([2, 1])
    // Konsol listesi sunucuda kesildi: bekleyen görünmese de YEŞİL değil; görünen gecikmişler alt sınır ("N+")
    expect(nocUncalledSummary({ total: 0, items: [], truncated: true }, now)).toMatchObject({ tone: 'muted', partial: true })
    expect(nocUncalledSummary({ total: 2, items: d.items.slice(0, 2), truncated: true }, now)).toMatchObject({ overdue: 1, overdueAtLeast: true })
  })
  it('bakım: süren turuncu, yoksa yeşil; okunamadı işareti taşınır; bakımdaki izleme hizmetlerden', () => {
    // Bakımdaki izleme yalnız izlemeleri görülebilen hizmetlerden (hizmet listesi kurum geneli; inceleme tur 2)
    const sp = { services: [{ maintenance: 2, monitors_visible: true }, { maintenance: 0, monitors_visible: true }, { maintenance: 12, monitors_visible: false }],
      maintenance: { active: [{ id: 1 }], active_total: 2, active_hidden: 1,
      upcoming: [{ id: 5 }], upcoming_total: 1, upcoming_hidden: 0 } }
    expect(maintenanceSummary(sp)).toMatchObject({ activeTotal: 1, upcomingTotal: 1, hidden: 1, monitorsInMaintenance: 2, tone: 'warning', unavailable: false })
    // Yalnız başka takımın süren bakımı: renk ve sayı GÖRÜLEBİLENDEN (olay kutusuyla aynı kural) — "1 sürüyor" + "yok" çelişkisi yok
    expect(maintenanceSummary({ maintenance: { active: [], active_total: 1, active_hidden: 1, upcoming: [], upcoming_total: 0 } }))
      .toMatchObject({ activeTotal: 0, hidden: 1, tone: 'success' })
    expect(maintenanceSummary({ maintenance: { active: [], upcoming: [] } }).tone).toBe('success')
    expect(maintenanceSummary({ maintenance: { unavailable: true } }).unavailable).toBe(true)
  })
  it('olaylar: görülebilen kritik/yüksek kırmızı, başka açık turuncu, yok yeşil; gizli yalnız sayı; okunamadı işareti', () => {
    const inc = (rows, extra = {}) => ({ incidents: { active: rows, active_visible: rows.length, active_hidden: 0, ...extra } })
    expect(incidentsSummary(inc([{ id: 1, severity: 'HIGH' }, { id: 2, severity: 'LOW' }]))).toMatchObject({ visible: 2, severe: 1, tone: 'danger' })
    expect(incidentsSummary(inc([{ id: 2, severity: 'MEDIUM' }]))).toMatchObject({ severe: 0, tone: 'warning' })
    expect(incidentsSummary(inc([], { active_hidden: 3 }))).toMatchObject({ visible: 0, hidden: 3, tone: 'success' })
    expect(incidentsSummary(inc([], { active_unavailable: true })).unavailable).toBe(true)
  })
})

describe('2026-10-13: Grafana tarzı ızgaralar', () => {
  it('ton tablosu: çalışmıyor kırmızı, gecikmiş turuncu, ölçülmemiş / yalnız duraklatılmış gri, aksi yeşil', () => {
    expect(healthTone({ active: 5, down: 1, stale: 2 })).toBe('danger')
    expect(healthTone({ active: 5, stale: 2 })).toBe('warning')
    expect(healthTone({ active: 5, unknown: 1 })).toBe('muted')
    expect(healthTone({ active: 0 })).toBe('muted')
    expect(healthTone({ active: 5 })).toBe('success')
    expect(healthTone()).toBe('muted')
  })
  it('hizmet ızgarası: en kötü önce, sonra takım + ad (Türkçe); bilinmeyen durum "veri yok" (yeşil değil); tavan + kalan', () => {
    const sp = { services: [
      { key: 'a', name: 'Çağrı', team_name: 'B', state: 'operational' },
      { key: 'b', name: 'Ödeme', team_name: 'A', state: 'major_outage' },
      { key: 'c', name: 'Kart', team_name: 'A', state: 'degraded' },
      { key: 'd', name: 'Arşiv', team_name: 'A', state: 'garip' },
      { key: 'e', name: 'Bakım', team_name: 'A', state: 'maintenance' }] }
    const g = serviceGrid(sp, 4)
    expect(g.tiles.map((x) => x.key)).toEqual(['b', 'c', 'e', 'a'])
    expect(g.more).toBe(1)
    expect(g.total).toBe(5)
    expect(serviceGrid(sp, 10).tiles.find((x) => x.key === 'd')).toMatchObject({ state: 'no_data', tone: 'muted' })
    expect(serviceGrid(null).total).toBe(0)
  })
  it('tür ısı haritası: izlemesi olmayan / yalnız silinmiş tür çizilmez; sağlıklı = etkin − sorunlu − ölçülmemiş', () => {
    const h = typeHeat({ types: [
      { type: 'http', total: 10, active: 10, down: 2, stale: 1, unknown: 0 },
      { type: 'ping', total: 4, active: 4, down: 0, stale: 0, unknown: 1 },
      { type: 'dns', total: 3, active: 0, paused: 3 },
      { type: 'port', total: 0 },
      { type: 'page', total: 2, deleted: 2 },
      { type: 'domain', total: 5, active: 5 }] })
    expect(h.cells.map((c) => [c.type, c.tone])).toEqual([['http', 'danger'], ['ping', 'muted'], ['dns', 'muted'], ['domain', 'success']])
    expect(h.cells[0].healthy).toBe(7)
    expect(h).toMatchObject({ down: 2, stale: 1, problemTypes: 1, tone: 'danger' })
    expect(typeHeat({ types: [] })).toMatchObject({ cells: [], tone: 'muted' })
  })
  it('takım × tür matrisi: silinmiş / envanterden çıkmış sayılmaz; en kötü takım önce, takımsız AYNI tondakiler arasında sonda; tavan', () => {
    const row = (team_id, team_name, type, status, extra = {}) => ({ team_id, team_name, type, status, active: status !== 'paused', ...extra })
    const ov = { types: [{ type: 'http' }, { type: 'ping' }, { type: 'dns' }], monitors: [
      row(1, 'Ödeme', 'http', 'up'), row(1, 'Ödeme', 'ping', 'stale'),
      row(2, 'Çağrı', 'http', 'down'), row(2, 'Çağrı', 'http', 'up'),
      row(3, 'Arşiv', 'http', 'up'),
      row(null, null, 'ping', 'down'),
      row(4, 'Eski', 'dns', 'down', { deleted: true }),
      row(5, 'Envanter', 'dns', 'paused', { inventory_inactive: true }),
      row(3, 'Arşiv', 'ping', 'paused')] }
    const m = teamTypeMatrix(ov, 10)
    // Envanterden çıkmış satır İzleme Panosu'nda "duraklatılmış" listelenir → matris de sayar (hücre sayısı = liste)
    expect(m.types).toEqual(['http', 'ping', 'dns'])
    // inceleme: kırmızı takımsız satır tavanın altında kalmasın — ton önce, takımsız yalnız aynı tondakiler arasında sonda;
    // yalnız duraklatılmış takım en sonda
    expect(m.rows.map((r) => r.key)).toEqual(['2', MATRIX_NO_TEAM, '1', '3', '5'])
    expect(m.rows[0].cells.http).toMatchObject({ total: 2, active: 2, down: 1, tone: 'danger' })
    // Sağlıklı takımdaki yalnız duraklatılmış tür takımı griye çekmez (ısı haritasıyla aynı — inceleme tur 2)
    expect(m.rows[3].cells.ping).toMatchObject({ paused: 1, active: 0, tone: 'muted' })
    expect(m.rows[3].worst).toBe('success')
    expect(m.rows[4]).toMatchObject({ worst: 'muted', live: false })
    expect(matrixCellFocus(m.rows[4].cells.dns)).toEqual({ status: 'paused', count: 1 })
    expect(m).toMatchObject({ totalTeams: 5, problemTeams: 3, tone: 'danger' })
    expect(teamTypeMatrix(ov, 2)).toMatchObject({ more: 3 })
    expect(teamTypeMatrix(ov, 2).rows.map((r) => r.key)).toEqual(['2', MATRIX_NO_TEAM])
    expect(teamTypeMatrix({ monitors: [] })).toMatchObject({ rows: [], tone: 'muted' })
    // Tümüyle duraklatılmış takım genel tonu griye çekmez (ısı haritasıyla aynı)
    const calm = teamTypeMatrix({ types: [{ type: 'http' }, { type: 'ping' }], monitors: [row(1, 'A', 'http', 'up'), row(1, 'A', 'ping', 'paused'),
      row(2, 'B', 'http', 'paused')] })
    expect(calm.tone).toBe('success')
    expect(calm.rows.map((r) => [r.key, r.worst])).toEqual([['1', 'success'], ['2', 'muted']])
  })
  it('matris hücresi: yazan sayı = bağlantının süzdüğü listedeki sayı (durum sırası çalışmıyor → gecikmiş → ölçülmedi → sağlıklı → duraklatılmış)', () => {
    expect(matrixCellFocus({ down: 2, stale: 1, unknown: 1, active: 9, paused: 1 })).toEqual({ status: 'down', count: 2 })
    expect(matrixCellFocus({ down: 0, stale: 3, active: 9 })).toEqual({ status: 'stale', count: 3 })
    expect(matrixCellFocus({ down: 0, stale: 0, unknown: 2, active: 9 })).toEqual({ status: 'unknown', count: 2 })
    expect(matrixCellFocus({ down: 0, stale: 0, unknown: 0, active: 9, paused: 4 })).toEqual({ status: 'up', count: 9 })
    expect(matrixCellFocus({ active: 0, paused: 4 })).toEqual({ status: 'paused', count: 4 })
    expect(matrixCellFocus(null)).toEqual({ status: null, count: 0 })
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
    // 2026-10-12: hiç sertifika yoksa nötr; son taraması başarısız sertifika varsa uyarı ("sorun yok" değil)
    expect(certSummary({}).tone).toBe('muted')
    expect(certSummary({ total_certificates: 5, valid_count: 5 }).tone).toBe('success')
    expect(certSummary({ total_certificates: 5, valid_count: 3, error_count: 2 }).tone).toBe('warning')
  })
  it('2026-10-12: bilinmeyen / ölçülmemiş / yetkisiz veri yeşil değil', () => {
    expect(monitorSummary({ totals: { active: 10, unknown: 10 } }).tone).toBe('muted')
    expect(monitorSummary({ totals: { active: 0 } }).tone).toBe('muted')
    expect(monitorSummary({ totals: { active: 10, unknown: 0 } }).tone).toBe('success')
    expect(monitorSummary({ totals: { active: 10, unknown: 2, stale: 1 } }).tone).toBe('warning')
    const nd = statusSummary({ services: [{ key: 'a', state: 'no_data' }, { key: 'b', state: 'no_data' }] })
    expect(nd).toMatchObject({ worst: 'no_data', shownState: 'no_data', allOk: false, tone: 'muted' })
    const mix = statusSummary({ services: [{ key: 'a', state: 'operational' }, { key: 'b', state: 'no_data' }] })
    expect(mix).toMatchObject({ shownState: 'no_data', allOk: false, tone: 'muted' })
    expect(statusSummary({ services: [{ key: 'a', state: 'operational' }, { key: 'b', state: 'maintenance' }] }).tone).toBe('info')
    expect(statusSummary({ services: [{ key: 'a', state: 'operational' }] })).toMatchObject({ allOk: true, tone: 'success' })
    expect(alertsSummary({ visible: false, total: 0, tabs: {} })).toMatchObject({ hidden: true, tone: 'muted' })
  })
  it('hizmet durumu: en kötü durum ve sorunlular ağırdan hafife', () => {
    const s = statusSummary({ services: [
      { key: 'a', state: 'operational' }, { key: 'b', state: 'degraded', since: '2026-10-11T08:00:00' },
      { key: 'c', state: 'major_outage', since: '2026-10-11T09:00:00' }, { key: 'd', state: 'maintenance' }] })
    expect(s.worst).toBe('major_outage')
    expect(s.problems.map((p) => p.key)).toEqual(['c', 'b'])
    expect(s.tone).toBe('danger')
    expect(statusSummary({ services: [] })).toMatchObject({ worst: 'no_data', tone: 'muted' })
    // 2026-10-12: küçük boyutta liste 0'a kısılır — gerçek sorun sayısı ayrıca taşınır ("hepsi çalışıyor" ona bakar)
    const small = statusSummary({ services: [{ key: 'a', state: 'major_outage' }, { key: 'b', state: 'operational' }] }, 0)
    expect(small.problems).toEqual([])
    expect(small.problemCount).toBe(1)
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
