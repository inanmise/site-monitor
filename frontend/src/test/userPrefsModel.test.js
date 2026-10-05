import { describe, it, expect } from 'vitest'
import {
  LOCAL_PREF_KEYS, isSyncedLocalKey, readLocalSnapshot, planHydration, normalizeFavorites, toggleFavorite, isFavorite,
  MAX_FAVORITES, upsertView, renameView, deleteView, viewsFor, MAX_VIEWS_PER_LIST, pickParams, sameParams,
  resolveLandingTab, VIEW_SPECS, MONITOR_TYPES, isSyncableValue, cleanServerLocal,
} from '../hooks/userPrefsModel.js'
import { PAGE_STATE_PARAMS, PAGE_STATE_PREFIXES } from '../hooks/useUrlQuerySync.js'

/**
 * Kişisel tercihler modeli (öneri 23) — saf işlevler: beyaz liste, girişteki birleştirme planı (sunucu kazanır, tarayıcı
 * boşlukları doldurur), favori / görünüm yardımcıları, görünüm parametre seçimi ve açılış sekmesi doğrulaması.
 */
function memStorage(init = {}) {
  const m = new Map(Object.entries(init))
  return {
    get length() { return m.size },
    key: (i) => [...m.keys()][i] ?? null,
    getItem: (k) => (m.has(k) ? m.get(k) : null),
    setItem: (k, v) => { m.set(k, String(v)) },
    removeItem: (k) => { m.delete(k) },
  }
}

describe('userPrefsModel — beyaz liste', () => {
  // 2026-10-05 (ürün kararı): tema ARTIK aynalanır ("kullanıcının şema seçimlerini hatırlayalım") — dil hâlâ dışarıda.
  it('gerçek tercihler (tema dahil) aynalanır; oturum, dil, taslak, son kullanılanlar, tur ve bildirim kutusu aynalanmaz', () => {
    for (const k of ['sidebar-open', 'today-panel-open', 'certtable-presets', 'sm.audit.savedViews', 'sm.pageSize.dashboard-certs',
      'sm.checkRun.teams', 'sm.checkRun.teams.http', 'inventory-saved-views', 'site-monitor-theme']) {
      expect(isSyncedLocalKey(k), k).toBe(true)
    }
    for (const k of ['site-monitor-remembered-user', 'site-monitor-lang', 'sm.session.active',
      'sm.storage.owner', 'sm.palette.recent:ali', 'sm.dexp.recent', 'wr.draft.12', 'sm.tour', 'inbox-seen:ali',
      'inbox-dismissed:ali', 'nav-section-open', 'sm.banner.dismissedVersion', 'sm.pageSize.', 'sm.pageSize.a b', '', null]) {
      expect(isSyncedLocalKey(k), String(k)).toBe(false)
    }
  })

  it('yoğunluk tercihi listede YOK (kullanıcı kararı 2026-09-27: tarayıcıya yazılmaz)', () => {
    expect(LOCAL_PREF_KEYS.some((k) => /density/i.test(k))).toBe(false)
  })

  it('anlık görüntü yalnız beyaz listedekileri okur', () => {
    const s = memStorage({ 'sidebar-open': 'false', 'site-monitor-lang': 'en', 'sm.pageSize.x': '25' })
    expect(readLocalSnapshot(s)).toEqual({ 'sidebar-open': 'false', 'sm.pageSize.x': '25' })
    expect(readLocalSnapshot(null)).toEqual({})
  })

  it('tema değeri doğrulanır: bilinen kimlik aynalanır, bilinmeyen değer ne yüklenir ne yerele yazılır', () => {
    expect(isSyncableValue('site-monitor-theme', 'crucible')).toBe(true)
    expect(isSyncableValue('site-monitor-theme', 'sepia')).toBe(false)
    expect(isSyncableValue('site-monitor-theme', null)).toBe(true)          // silme her zaman geçer
    expect(isSyncableValue('sidebar-open', 'anything')).toBe(true)          // kuralı olmayan anahtar
    expect(readLocalSnapshot(memStorage({ 'site-monitor-theme': 'obsidian' }))).toEqual({ 'site-monitor-theme': 'obsidian' })
    expect(readLocalSnapshot(memStorage({ 'site-monitor-theme': 'sepia' }))).toEqual({})
    expect(cleanServerLocal({ 'site-monitor-theme': 'sepia', 'sidebar-open': 'true' })).toEqual({ 'sidebar-open': 'true' })
  })
})

describe('userPrefsModel — girişteki birleştirme planı', () => {
  it('BOŞ sunucu: tarayıcıdaki her tercih yüklenir (ilk girişte taşıma), yerele yazılacak bir şey yok', () => {
    const plan = planHydration(undefined, { 'sidebar-open': 'false', 'sm.pageSize.a': '25' })
    expect(plan.toUpload).toEqual({ 'sidebar-open': 'false', 'sm.pageSize.a': '25' })
    expect(plan.toWrite).toEqual({})
  })

  it('sunucu KAZANIR: farklı/eksik değerler yerele yazılır; yalnız tarayıcıda olanlar yüklenir', () => {
    // 2026-10-05: tema da sunucudan gelir (aynalanır); dil anahtarı beyaz liste dışı → yazılmaz
    const plan = planHydration(
      { 'sidebar-open': 'true', 'today-panel-open': 'true', 'site-monitor-theme': 'dark', 'site-monitor-lang': 'tr' },
      { 'sidebar-open': 'false', 'sm.pageSize.a': '25' },
    )
    expect(plan.toWrite).toEqual({ 'sidebar-open': 'true', 'today-panel-open': 'true', 'site-monitor-theme': 'dark' })
    expect(plan.toUpload).toEqual({ 'sm.pageSize.a': '25' })
  })

  it('giriş ile belge arasında kullanıcının değiştirdiği anahtar ezilmez; tarayıcıdaki hâli (silindiyse null) yüklenir', () => {
    const plan = planHydration({ 'sidebar-open': 'true', 'today-panel-open': 'false' }, { 'sidebar-open': 'false' },
      new Set(['sidebar-open', 'today-panel-open']))
    expect(plan.toWrite).toEqual({})
    expect(plan.toUpload).toEqual({ 'sidebar-open': 'false', 'today-panel-open': null })
  })
})

describe('userPrefsModel — favoriler', () => {
  it('ekle / çıkar; tür beyaz listesi ve kimlik doğrulaması; tekil', () => {
    let r = toggleFavorite([], { type: 'http', id: '5', name: ' Ana sayfa ' })
    expect(r).toMatchObject({ on: true, full: false })
    expect(r.list).toEqual([{ type: 'http', id: 5, name: 'Ana sayfa' }])
    expect(isFavorite(r.list, 'http', '5')).toBe(true)
    r = toggleFavorite(r.list, { type: 'http', id: 5 })
    expect(r).toMatchObject({ on: false, list: [] })
    expect(normalizeFavorites([{ type: 'ssl', id: 1 }, { type: 'ping', id: 0 }, { type: 'ping', id: 2 }, { type: 'ping', id: '2' }]))
      .toEqual([{ type: 'ping', id: 2 }])
    expect(MONITOR_TYPES).toHaveLength(9)
  })

  it(`en çok ${MAX_FAVORITES} favori: tavan doluyken ekleme yapılmaz (full)`, () => {
    const full = Array.from({ length: MAX_FAVORITES }, (_, i) => ({ type: 'ping', id: i + 1 }))
    const r = toggleFavorite(full, { type: 'http', id: 1 })
    expect(r).toMatchObject({ on: false, full: true })
    expect(r.list).toHaveLength(MAX_FAVORITES)
  })
})

describe('userPrefsModel — kayıtlı görünümler', () => {
  it('kaydet (aynı ad güncellenir), yeniden adlandır (çakışma), sil; boş liste anahtarı düşer', () => {
    let sv = upsertView(undefined, 'http', 'Kritikler', { stat: 'down' }).savedViews
    sv = upsertView(sv, 'http', 'Ekibim', { team: '3' }).savedViews
    const again = upsertView(sv, 'http', 'kritikler', { stat: 'warn' })
    expect(again.replaced).toBe(true)
    expect(viewsFor(again.savedViews, 'http').map((v) => v.name)).toEqual(['kritikler', 'Ekibim'])
    expect(renameView(sv, 'http', 'Ekibim', 'KRİTİKLER').conflict).toBe(true)
    sv = renameView(sv, 'http', 'Ekibim', 'Takımım').savedViews
    expect(viewsFor(sv, 'http').map((v) => v.name)).toEqual(['Kritikler', 'Takımım'])
    sv = deleteView(deleteView(sv, 'http', 'Kritikler'), 'http', 'Takımım')
    expect(sv).toEqual({})
  })

  it(`liste başına en çok ${MAX_VIEWS_PER_LIST} görünüm`, () => {
    let sv = {}
    for (let i = 0; i < MAX_VIEWS_PER_LIST; i++) sv = upsertView(sv, 'ping', `v${i}`, {}).savedViews
    expect(upsertView(sv, 'ping', 'fazla', {}).full).toBe(true)
  })

  it('görünüm parametreleri: tam adlar / önekli aile; geçici durumlar (ayrıntı, pencere, sayfa) alınmaz', () => {
    const search = '?tab=http&team=3&q=api&page=2&ps=25&monitor=9&mtab=history&stat=down&dq=soon'
    expect(pickParams(search, VIEW_SPECS.monitor)).toEqual({ team: '3', q: 'api', stat: 'down', dq: 'soon' })
    expect(pickParams('?tab=monitoring&mo_status=down&mo_dlg=stale&mo_fav=1&q=x', VIEW_SPECS.monitoring))
      .toEqual({ mo_status: 'down', mo_fav: '1' })
    expect(pickParams('?ih_q=a&ih_id=7&ih_sev=HIGH', VIEW_SPECS['incident-history'])).toEqual({ ih_q: 'a', ih_sev: 'HIGH' })
    expect(pickParams('?view=closed&alert=12&level=HIGH&page=3', VIEW_SPECS.alerthistory)).toEqual({ view: 'closed', level: 'HIGH' })
    expect(sameParams({ a: '1', b: '2' }, { b: '2', a: '1' })).toBe(true)
    expect(sameParams({ a: '1' }, { a: '1', b: '2' })).toBe(false)
  })

  it('görünüm parametrelerinin hepsi sekme değişiminde temizlenen sayfa-durumu ailesindendir (bayat süzgeç taşınmaz)', () => {
    const inFamily = (k) => PAGE_STATE_PARAMS.includes(k) || PAGE_STATE_PREFIXES.some((p) => k.startsWith(p))
    for (const spec of Object.values(VIEW_SPECS)) {
      for (const k of spec.keys || []) expect(inFamily(k), k).toBe(true)
      if (spec.prefix) expect(PAGE_STATE_PREFIXES).toContain(spec.prefix)
    }
  })
})

describe('userPrefsModel — açılış sekmesi', () => {
  it('yalnız izinli listedeki geçerli kimlik kabul edilir; bilinmeyen / yasak / bozuk → null (Pano)', () => {
    const allowed = ['monitoring', 'http', 'help']
    expect(resolveLandingTab('monitoring', allowed)).toBe('monitoring')
    expect(resolveLandingTab('sqlplayground', allowed)).toBeNull()
    expect(resolveLandingTab('nope', allowed)).toBeNull()
    expect(resolveLandingTab('<x>', allowed)).toBeNull()
    expect(resolveLandingTab(null, allowed)).toBeNull()
    expect(resolveLandingTab(5, allowed)).toBeNull()
  })
})
