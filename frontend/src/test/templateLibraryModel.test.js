import { describe, it, expect } from 'vitest'
import { readFileSync } from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import {
  LIBRARY_MAX, LIBRARY_NAME_MAX, defaultEntry, entryCounts, initialSource, libraryEntries, libraryNameError, libraryOptions,
  nameTaken,
} from '../components/techerror/templateLibraryModel.js'

// Takım şablon kütüphanesi (2026-10-18, D17) — saf yardımcılar. Sınırlar sunucuyla aynı (Java kaynağından okunur).
const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../../..')
const JAVA = readFileSync(path.join(ROOT, 'backend/src/main/java/com/sitemonitor/service/techerror/TechErrorTeamTemplateService.java'), 'utf8')
const javaInt = (name) => Number(new RegExp(`static final int ${name} = (\\d+);`).exec(JAVA)?.[1])

describe('templateLibraryModel', () => {
  it('sınırlar sunucuyla aynı (MAX_PER_TEAM, MAX_NAME)', () => {
    expect(LIBRARY_MAX).toBe(javaInt('MAX_PER_TEAM'))
    expect(LIBRARY_NAME_MAX).toBe(javaInt('MAX_NAME'))
  })

  it('kayıtlar: dizi değilse boş; önce varsayılan, sonra ad (Türkçe, büyük/küçük harf duyarsız), sonra kimlik', () => {
    expect(libraryEntries(null)).toEqual([])
    expect(libraryEntries([])).toEqual([])                                   // yedek mock / eski sunucu
    expect(libraryEntries({ templates: 'x' })).toEqual([])
    const list = libraryEntries({ templates: [
      { id: 3, name: 'zeytin' }, { id: 2, name: 'İnternet', is_default: true }, { id: 1, name: 'ana' }, { id: 4, name: 'Ana' }, null,
    ] })
    expect(list.map((e) => e.id)).toEqual([2, 1, 4, 3])
    expect(defaultEntry(list).id).toBe(2)
    expect(defaultEntry([{ id: 1 }])).toBeNull()
  })

  it('ilk kaynak: varsayılan varsa kütüphane (o kayıt), yoksa arayüz dilinin varsayılanı', () => {
    expect(initialSource([{ id: 7, is_default: true }], 'tr')).toEqual({ source: 'library', libraryId: '7' })
    expect(initialSource([{ id: 7 }], 'en')).toEqual({ source: 'en', libraryId: '' })
    expect(initialSource([], 'tr')).toEqual({ source: 'tr', libraryId: '' })
    expect(initialSource(null, 'de')).toEqual({ source: 'tr', libraryId: '' })
  })

  it('seçenekler: varsayılana ek; sayımlar eksikse "—" (0 değil); ad kuralı ve çakışma (Türkçe)', () => {
    expect(libraryOptions([{ id: 1, name: 'A', is_default: true }, { id: 2, name: 'B' }], '(takım varsayılanı)'))
      .toEqual([{ value: '1', label: 'A (takım varsayılanı)', entry: { id: 1, name: 'A', is_default: true } },
        { value: '2', label: 'B', entry: { id: 2, name: 'B' } }])
    expect(entryCounts({ lang: 'en', section_count: 0, field_count: 3 })).toEqual({ lang: 'en', sections: 0, fields: 3, slots: '—' })
    expect(entryCounts({ lang: 'xx' }).lang).toBeNull()
    expect(libraryNameError('  ')).toBe('te.lib.err.nameRequired')
    expect(libraryNameError('x'.repeat(101))).toBe('te.lib.err.nameTooLong')
    expect(libraryNameError(' Ana ')).toBeNull()
    const list = [{ id: 1, name: 'İnternet Şubesi' }]
    expect(nameTaken(list, '  internet   şubesi ')).toBe(true)
    expect(nameTaken(list, 'internet şubesi', 1)).toBe(false)                // kendisi
    expect(nameTaken(list, 'Mobil')).toBe(false)
  })
})
