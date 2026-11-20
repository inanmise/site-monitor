import { describe, it, expect, beforeEach } from 'vitest'
import {
  DRAFT_PREFIX, DRAFT_MAX_AGE_MS, DRAFT_MAX_BYTES, draftScope, draftKey, readDraft, writeDraft, removeDraft, applyDraft,
} from '../components/incidenthistory/incidentDraft.js'
import { OWNER_KEY, PERSONAL_PREFIXES, clearPersonalStorage, claimPersonalStorage } from '../utils/personalStorage.js'

// Olay kaydı formunun yerel taslağı ("Sağlam Kayıt", 2026-11-20): kullanıcıya özel anahtar, 7 gün, 256 KB, kendiliğinden
// uygulanmaz; çıkışta silinir.

const NOW = Date.UTC(2026, 10, 20, 9, 0, 0)

beforeEach(() => {
  localStorage.clear()
  localStorage.setItem(OWNER_KEY, 'ayse')
})

describe('incidentDraft', () => {
  it('kapsam: düzenleme → kimlik; yeni → new; alarmdan yeni → new.alert.<id>', () => {
    expect(draftScope('edit', { id: 7 })).toBe('7')
    expect(draftScope('create', {})).toBe('new')
    expect(draftScope('create', { alert_event_id: 42 })).toBe('new.alert.42')
    expect(draftScope('create', { id: 9 })).toBe('new')                  // oluşturma kipinde kimlik yok sayılır
  })

  it('anahtar kullanıcıya özel ve kişisel önekte (çıkışta silinir; başka kullanıcı girince silinir)', () => {
    expect(draftKey('7')).toBe(`${DRAFT_PREFIX}7:ayse`)
    expect(PERSONAL_PREFIXES).toContain(DRAFT_PREFIX)
    writeDraft('7', { changes: { title: 'x' } }, NOW)
    expect(localStorage.getItem(`${DRAFT_PREFIX}7:ayse`)).toBeTruthy()
    claimPersonalStorage('mehmet')
    expect(localStorage.getItem(`${DRAFT_PREFIX}7:ayse`)).toBeNull()
    localStorage.setItem(OWNER_KEY, 'mehmet')
    writeDraft('7', { changes: { title: 'y' } }, NOW)
    clearPersonalStorage()
    expect(Object.keys(localStorage).filter((k) => k.startsWith(DRAFT_PREFIX))).toEqual([])
  })

  it('sahip işareti yokken (çıkış sonrası kapanış / pagehide) HİÇBİR anahtar yazılmaz ve ekisiz anahtar okunmaz', () => {
    clearPersonalStorage()                                            // çıkış: önce sahip işareti silinir
    expect(localStorage.getItem(OWNER_KEY)).toBeNull()
    expect(writeDraft('7', { changes: { description: 'gizli olay metni' } }, NOW)).toBe(false)
    expect(Object.keys(localStorage).filter((k) => k.startsWith(DRAFT_PREFIX))).toEqual([])
    localStorage.setItem(`${DRAFT_PREFIX}7`, JSON.stringify({ v: 1, at: NOW, changes: { title: 'x' } }))
    expect(readDraft('7', NOW)).toBeNull()
  })

  it('yaz → oku: sürüm / damga taşınır; boş değişiklik taslağı SİLER', () => {
    expect(writeDraft('7', { changes: { title: 'Yeni' }, baseVersion: 3, baseUpdatedAt: '2026-11-19T10:00:00' }, NOW)).toBe(true)
    const d = readDraft('7', NOW + 1000)
    expect(d).toMatchObject({ v: 1, at: NOW, base_version: 3, base_updated_at: '2026-11-19T10:00:00', changes: { title: 'Yeni' } })
    writeDraft('7', { changes: {} }, NOW)
    expect(readDraft('7', NOW)).toBeNull()
  })

  it('7 günden eski / bozuk / gelecekten gelen taslak okunmaz ve silinir', () => {
    writeDraft('7', { changes: { title: 'x' } }, NOW)
    expect(readDraft('7', NOW + DRAFT_MAX_AGE_MS)).not.toBeNull()
    expect(readDraft('7', NOW + DRAFT_MAX_AGE_MS + 1)).toBeNull()
    expect(localStorage.getItem(draftKey('7'))).toBeNull()

    localStorage.setItem(draftKey('8'), '{bozuk')
    expect(readDraft('8', NOW)).toBeNull()
    expect(localStorage.getItem(draftKey('8'))).toBeNull()

    localStorage.setItem(draftKey('9'), JSON.stringify({ v: 1, at: NOW + 3_600_000, changes: { title: 'x' } }))
    expect(readDraft('9', NOW)).toBeNull()
    localStorage.setItem(draftKey('10'), JSON.stringify({ v: 2, at: NOW, changes: { title: 'x' } }))
    expect(readDraft('10', NOW)).toBeNull()
    localStorage.setItem(draftKey('11'), JSON.stringify({ v: 1, at: NOW, changes: ['title'] }))
    expect(readDraft('11', NOW)).toBeNull()
  })

  it('256 KB üstü taslak yazılmaz (false) ve önceki taslak kalır', () => {
    writeDraft('7', { changes: { title: 'önceki' } }, NOW)
    expect(writeDraft('7', { changes: { description: 'x'.repeat(DRAFT_MAX_BYTES) } }, NOW)).toBe(false)
    expect(readDraft('7', NOW).changes).toEqual({ title: 'önceki' })
  })

  it('applyDraft yalnız izinli anahtarları yazar; öteki alanlar olduğu gibi kalır', () => {
    removeDraft('7')
    const form = { title: 'a', tags: 'b', status: 'OPEN' }
    expect(applyDraft(form, { title: 'yeni', hack: 1 }, ['title', 'tags'])).toEqual({ title: 'yeni', tags: 'b', status: 'OPEN' })
    expect(form.title).toBe('a')
  })
})
