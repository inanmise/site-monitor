import { describe, it, expect } from 'vitest'
import {
  categoryCounts, isWithinEditWindow, liveNoteCount, notePermissions, noteTs, visibleNotes,
} from '../components/certmodal/notesModel.js'

/**
 * Notlar sekmesi saf modeli (2026-09-28). İzin kuralları eski NotesTab ile BİREBİR (sunucu da uygular): düzenle = yazar +
 * 24 saat + silinmemiş; sil = yazar ya da yönetici; geri al = silinmiş + yönetici; salt okunurda hiçbiri.
 * Damgalar sunucu biçiminde: UTC, `Z`siz "yyyy-MM-ddTHH:mm:ss".
 */
const NOW = Date.parse('2026-09-28T12:00:00Z')
const n = (over = {}) => ({ id: 1, author_username: 'kisi.a', author_name: 'Kişi A', note: 'Deploy tamam', category: 'DEPLOYMENT',
  created_at: '2026-09-28T10:00:00', deleted_at: null, ...over })

describe('notesModel', () => {
  it('noteTs Z\'siz damgayı UTC okur; 24 saatlik düzenleme penceresi', () => {
    expect(noteTs('2026-09-28T10:00:00')).toBe(Date.parse('2026-09-28T10:00:00Z'))
    expect(isWithinEditWindow('2026-09-28T10:00:00', NOW)).toBe(true)
    expect(isWithinEditWindow('2026-09-27T11:59:00', NOW)).toBe(false)   // 24 saat 1 dk önce
    expect(isWithinEditWindow(null, NOW)).toBe(false)
  })

  it('izinler: yazar düzenler (pencere içinde), yönetici başkasınınkini siler, silinmişi yalnız yönetici geri alır', () => {
    const ctx = { currentUser: 'kisi.a', isAdmin: false, now: NOW }
    expect(notePermissions(n(), ctx)).toMatchObject({ isAuthor: true, canEdit: true, canDelete: true, canRestore: false })
    expect(notePermissions(n({ created_at: '2026-09-26T10:00:00' }), ctx)).toMatchObject({ canEdit: false, canDelete: true })
    expect(notePermissions(n({ author_username: 'kisi.b' }), ctx)).toMatchObject({ canEdit: false, canDelete: false })
    expect(notePermissions(n({ author_username: 'kisi.b' }), { ...ctx, isAdmin: true })).toMatchObject({ canEdit: false, canDelete: true })
    expect(notePermissions(n({ deleted_at: '2026-09-28T11:00:00' }), { ...ctx, isAdmin: true })).toMatchObject({ canEdit: false, canDelete: false, canRestore: true })
    expect(notePermissions(n(), { ...ctx, isAdmin: true, readOnly: true })).toMatchObject({ canEdit: false, canDelete: false, canRestore: false })
  })

  it('süzme + sayılar: kategori, arama (Türkçe harf duyarsız), silinmişleri gizle', () => {
    const list = [n(), n({ id: 2, category: 'INCIDENT', note: 'İSTANBUL POP kesintisi' }), n({ id: 3, category: 'NOTE', deleted_at: '2026-09-28T11:00:00' })]
    const labelOf = (c) => ({ NOTE: 'Not', DEPLOYMENT: 'Deploy', INCIDENT: 'Incident', RENEWAL: 'Yenileme' })[c]
    expect(categoryCounts(list, { labelOf })).toMatchObject({ ALL: 3, DEPLOYMENT: 1, INCIDENT: 1, NOTE: 1, RENEWAL: 0 })
    expect(categoryCounts(list, { showDeleted: false, labelOf }).ALL).toBe(2)
    expect(visibleNotes(list, { query: 'istanbul', labelOf }).map((x) => x.id)).toEqual([2])
    expect(visibleNotes(list, { category: 'DEPLOYMENT', labelOf }).map((x) => x.id)).toEqual([1])
    expect(visibleNotes(list, { query: 'kişi a', labelOf }).length).toBe(3)   // yazar adında da arar
    expect(liveNoteCount(list)).toBe(2)
  })
})
