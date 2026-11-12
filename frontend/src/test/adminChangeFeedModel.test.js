import { describe, it, expect } from 'vitest'
import fs from 'node:fs'
import path from 'node:path'
import {
  FEED_AREAS, FEED_DAYS, DEFAULT_DAYS, normDays, parseAreas, toggleArea, layoutFor, FEED_TABLE_MIN_PX, rowPayload,
} from '../components/admin/adminChangeFeedModel.js'
import { parseChanges, isDiffShape, eventKind } from '../components/admin/historyFormat.js'

// Çapraz yönetim değişiklik akışı (2026-11-13): alan / gün listeleri sunucuyla AYNI (Java kaynağı okunur).
const JAVA = path.resolve(__dirname, '../../../backend/src/main/java/com/sitemonitor')
const read = (p) => fs.readFileSync(path.join(JAVA, p), 'utf8')

describe('adminChangeFeedModel — sunucu sözleşmesi', () => {
  it('FEED_AREAS = AdminActivityFeedService.AREAS (sıra dahil)', () => {
    const src = read('service/AdminActivityFeedService.java')
    const m = src.match(/AREAS\s*=\s*List\.of\(([\s\S]*?)\);/)
    const java = [...m[1].matchAll(/"([a-z]+)"/g)].map(x => x[1])
    expect(FEED_AREAS).toEqual(java)
  })
  it('FEED_DAYS = AdminController.FEED_DAYS', () => {
    const src = read('controller/AdminController.java')
    const m = src.match(/FEED_DAYS\s*=\s*List\.of\(([^)]*)\)/)
    expect(FEED_DAYS).toEqual(m[1].split(',').map(s => Number(s.trim())))
  })
})

describe('adminChangeFeedModel — süzgeç yardımcıları', () => {
  it('normDays: bilinen değer aynen, bilinmeyen → 30', () => {
    expect(normDays('7')).toBe(7)
    expect(normDays('0')).toBe(0)
    expect(normDays('45')).toBe(DEFAULT_DAYS)
    expect(normDays('abc')).toBe(DEFAULT_DAYS)
    expect(normDays(null)).toBe(DEFAULT_DAYS)
  })
  it('parseAreas: yalnız bilinen alanlar, kanonik sıra, tekrar yok', () => {
    expect(parseAreas('oncall,teams,bogus,teams')).toEqual(['teams', 'oncall'])
    expect(parseAreas('')).toEqual([])
  })
  it('toggleArea: ekle / çıkar; hepsi seçilince "tümü" (boş dizi)', () => {
    const avail = ['teams', 'groups', 'oncall']
    expect(toggleArea([], 'groups', avail)).toEqual(['groups'])
    expect(toggleArea(['groups'], 'groups', avail)).toEqual([])
    expect(toggleArea(['teams', 'groups'], 'oncall', avail)).toEqual([])
    expect(toggleArea(['oncall'], 'teams', avail)).toEqual(['teams', 'oncall'])
  })
  it('layoutFor: jsdom (0) ve geniş kap → tablo; dar → kart', () => {
    expect(layoutFor(0)).toBe('table')
    expect(layoutFor(FEED_TABLE_MIN_PX)).toBe('table')
    expect(layoutFor(FEED_TABLE_MIN_PX - 1)).toBe('cards')
  })
})

describe('adminChangeFeedModel — rowPayload', () => {
  it('fark → diff; düz anlık görüntü → snapshot; changes yoksa JSON detail anlık görüntü; hiçbiri → null', () => {
    expect(rowPayload({ changes: '{"name":{"from":"a","to":"b"}}' }, parseChanges, isDiffShape).kind).toBe('diff')
    expect(rowPayload({ changes: '{"name":"a"}' }, parseChanges, isDiffShape).kind).toBe('snapshot')
    const d = rowPayload({ changes: null, detail: '{"scope":"ALL","recipient":"T"}' }, parseChanges, isDiffShape)
    expect(d).toEqual({ kind: 'snapshot', data: { scope: 'ALL', recipient: 'T' } })
    expect(rowPayload({ changes: null, detail: '{}' }, parseChanges, isDiffShape)).toBeNull()
    expect(rowPayload({ changes: 'bozuk', detail: null }, parseChanges, isDiffShape)).toBeNull()
  })
})

describe('historyFormat.eventKind — yeni alan işlemleri', () => {
  it('ekleme / kaydetme / varsayılan / taşıma tonları', () => {
    expect(eventKind('OVERRIDE_ADD')).toBe('ev-create')
    expect(eventKind('MEMBER_ADD')).toBe('ev-create')
    expect(eventKind('OVERRIDE_DELETE')).toBe('ev-delete')
    expect(eventKind('SCHEDULE_SAVE')).toBe('ev-edit')
    expect(eventKind('DEFAULT')).toBe('ev-edit')
    expect(eventKind('REASSIGN')).toBe('ev-edit')
    expect(eventKind('FROBNICATE')).toBe('ev-other')
  })
})
