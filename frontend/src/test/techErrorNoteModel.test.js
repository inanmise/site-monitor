import { describe, it, expect, beforeEach } from 'vitest'
import { javaStringTable, readJavaMain, readJavaTest } from './helpers/javaTable.js'
import {
  NOTE_ALT_MAX, NOTE_BACKUP_PREFIX, NOTE_LIMITS, NOTE_REF_MAX_DIGITS, NOTE_REF_PREFIX,
  conflictInfoOf, dropNoteBackup, fileBaseName, imageUsedInDraft, imagesById, insertAtCaret, isChannelGone, isNoteConflict,
  isOldServerAnswer, limitsOf, normalizeNoteBody, noteBackupKey, noteCardState, noteCharCount, noteDirty, noteImageAlt,
  noteImageIdOf, noteImageMarkdown, noteImageRefIds, noteImageRefs, noteImageRefusalKey, noteState, noteTooLong,
  readNoteBackup, retryAfterSeconds, sameNoteMeta, versionCountKeys, writeNoteBackup,
} from '../components/techerror/notes/noteModel.js'
import { PERSONAL_PREFIXES } from '../utils/personalStorage.js'

// Kanal notları saf modeli (2026-11-20). Görsel başvurusu taraması sunucudaki TechErrorNoteRefs.ids ile AYNI doğruluk
// tablosunu kullanır — tablo Java test kaynağından okunur (TechErrorNoteRefsTest.REF_TABLE), iki taraf ayrışamaz.

const JAVA_TEST = readJavaTest('service/techerror/TechErrorNoteRefsTest.java')
const JAVA_MAIN = readJavaMain('service/techerror/TechErrorNoteRefs.java')
const REF_TABLE = javaStringTable(JAVA_TEST, 'REF_TABLE')

describe('görsel başvuruları — Java ile ortak doğruluk tablosu', () => {
  it('tablo okunur ve boş değildir (okuyucu sessizce boş dönerse test anlamsız kalırdı)', () => {
    expect(REF_TABLE.length).toBeGreaterThanOrEqual(20)
    for (const row of REF_TABLE) {
      expect(row).toHaveLength(2)
      expect(typeof row[0]).toBe('string')
      expect(typeof row[1]).toBe('string')
    }
  })

  it.each(REF_TABLE.map((r, i) => [i, r[0], r[1]]))('satır %i: %j → [%s]', (_i, input, expected) => {
    expect(noteImageRefIds(input).join(',')).toBe(expected)
    // Sözleşmedeki biçim (sayı dizisi) de aynı sırayı ve aynı kimlikleri verir
    expect(noteImageRefs(input).map(String).join(',')).toBe(expected)
  })

  it('önek ve rakam sınırı sunucu sabitleriyle aynıdır', () => {
    expect(JAVA_MAIN).toContain(`PREFIX = "${NOTE_REF_PREFIX}"`)
    expect(JAVA_MAIN).toMatch(new RegExp(`MAX_DIGITS = ${NOTE_REF_MAX_DIGITS};`))
  })

  it('dizgi olmayan girdi boş listedir; 18 haneli kimlik dizgi olarak kayıpsız kalır', () => {
    expect(noteImageRefIds(null)).toEqual([])
    expect(noteImageRefIds(undefined)).toEqual([])
    expect(noteImageRefIds(42)).toEqual([])
    expect(noteImageRefIds('te-note-img:999999999999999999')).toEqual(['999999999999999999'])
    // İnceleme 2026-11-20: kimlikler KANONİK DİZGİ — 2^53'ü aşan 18 haneli kimlik sayıya çevrilseydi yuvarlanıp eşleşmezdi
    expect(noteImageRefs('![a](te-note-img:12)')).toEqual(['12'])
    expect(noteImageRefs('te-note-img:9007199254740993 te-note-img:9007199254740992'))
      .toEqual(['9007199254740993', '9007199254740992'])
    expect(imagesById([{ id: 12 }]).has(noteImageRefs('![a](te-note-img:012)')[0])).toBe(true)
  })

  it('tarama doğrusaldır: sınır uzunluğunda önek / rakam yığını anında biter', () => {
    const max = NOTE_LIMITS.max_chars
    const prefixes = NOTE_REF_PREFIX.repeat(Math.floor(max / NOTE_REF_PREFIX.length))
    const digits = NOTE_REF_PREFIX + '9'.repeat(max - NOTE_REF_PREFIX.length)
    const partial = 'te-note-img'.repeat(Math.floor(max / 11))
    let many = ''
    for (let i = 1; many.length < max - 24; i++) many += `${NOTE_REF_PREFIX}${i} `
    const started = performance.now()
    expect(noteImageRefIds(prefixes)).toEqual([])
    expect(noteImageRefIds(digits)).toEqual([])
    expect(noteImageRefIds(partial)).toEqual([])
    expect(noteImageRefIds(many).length).toBeGreaterThan(3000)
    expect(performance.now() - started).toBeLessThan(2000)
  })
})

describe('noteImageIdOf — tek adres TAM bir not görseli başvurusu mu', () => {
  it.each([
    ['te-note-img:42', '42'],
    ['te-note-img:007', '7'],
    ['te-note-img:0', '0'],
    ['te-note-img:100000000000000000', '100000000000000000'],
    ['te-note-img:1234567890123456789', null],      // 19 rakam
    ['te-note-img:', null],
    ['te-note-img:12a', null],
    ['te-note-img:12 ', null],
    [' te-note-img:12', null],
    ['te-note-img:12?x=1', null],
    ['TE-NOTE-IMG:12', null],
    ['https://example.com/a.png', null],
    ['data:image/png;base64,AAAA', null],
    ['/api/tech-error-reports/channel-notes/images/12', null],
    ['', null],
  ])('%j → %j', (url, expected) => {
    expect(noteImageIdOf(url)).toBe(expected)
  })

  it('dizgi olmayan değer başvuru değildir', () => {
    expect(noteImageIdOf(null)).toBeNull()
    expect(noteImageIdOf(12)).toBeNull()
    expect(noteImageIdOf({})).toBeNull()
  })
})

describe('gövde normalleştirme (sunucunun kayıtta yaptığıyla aynı)', () => {
  it('CRLF ve CR satır sonu LF olur; NUL atılır', () => {
    expect(normalizeNoteBody('a\r\nb\rc\nd')).toBe('a\nb\nc\nd')
    expect(normalizeNoteBody('a\0b\0')).toBe('ab')
    expect(normalizeNoteBody('\r\n\r\n')).toBe('\n\n')
    expect(normalizeNoteBody('\r')).toBe('\n')
  })

  it('eşleşmemiş vekil atılır; geçerli çift (emoji) aynen kalır', () => {
    expect(normalizeNoteBody('a\uD83Db')).toBe('ab')          // yalnız üst vekil
    expect(normalizeNoteBody('a\uDE00b')).toBe('ab')          // yalnız alt vekil
    expect(normalizeNoteBody('a😀b')).toBe('a😀b')
    expect(normalizeNoteBody('\uD83D')).toBe('')
    expect(normalizeNoteBody('\uDE00\uD83D')).toBe('')        // ters sıra: ikisi de yalnız
    expect(normalizeNoteBody('\uD83D😀')).toBe('😀')
  })

  it('temiz metin AYNI dizgi olarak döner; dizgi olmayan değer boş metindir', () => {
    const s = 'Veri **raporlama** aracından alınır.\n\n- bir\n- iki'
    expect(normalizeNoteBody(s)).toBe(s)
    expect(normalizeNoteBody(null)).toBe('')
    expect(normalizeNoteBody(undefined)).toBe('')
    expect(normalizeNoteBody(5)).toBe('')
  })

  it('uzunluk normalleştirilmiş metnin UTF-16 birimidir; sınır tam değerde aşılmış sayılmaz', () => {
    expect(noteCharCount('a\r\nb')).toBe(3)
    expect(noteCharCount('😀')).toBe(2)
    expect(noteTooLong('x'.repeat(NOTE_LIMITS.max_chars))).toBe(false)
    expect(noteTooLong('x'.repeat(NOTE_LIMITS.max_chars + 1))).toBe(true)
    // CRLF tek karakter sayılır: 60 000 satır sonu sınırı aşmaz
    expect(noteTooLong('\r\n'.repeat(NOTE_LIMITS.max_chars))).toBe(false)
    expect(noteTooLong('abc', { max_chars: 2 })).toBe(true)
    // Bozuk sınır sözleşme varsayılanına düşer
    expect(noteTooLong('abc', { max_chars: 0 })).toBe(false)
  })

  it('taslak yalnız normalleştirilmiş metin farklıysa kirlidir', () => {
    expect(noteDirty('a\r\nb', 'a\nb')).toBe(false)
    expect(noteDirty('a', 'a ')).toBe(true)
    expect(noteDirty('', null)).toBe(false)
    expect(noteDirty('x', undefined)).toBe(true)
  })

  it('limitsOf: sunucunun değerleri; eksik / bozuk alan sözleşme varsayılanıdır', () => {
    expect(limitsOf(null)).toEqual({ ...NOTE_LIMITS })
    expect(limitsOf({ limits: { max_chars: 100, max_images: 'çok', max_total_bytes: -1 } })).toEqual({ ...NOTE_LIMITS, max_chars: 100 })
    expect(NOTE_LIMITS).toEqual({
      max_chars: 60000, max_images: 40, max_image_bytes: 5242880, max_total_bytes: 41943040, unlinked_keep_days: 30, max_versions: 20,
    })
  })
})

describe('insertAtCaret — parça kendi satırına, boş satırlarla ayrılarak girer', () => {
  it.each([
    // [gövde, imleç, beklenen gövde]
    ['', 0, 'X\n'],
    ['abc', 3, 'abc\n\nX\n'],
    ['abc\n', 4, 'abc\n\nX\n'],
    ['abc\n\n', 5, 'abc\n\nX\n'],
    ['abc', 0, 'X\n\nabc'],
    ['abcdef', 3, 'abc\n\nX\n\ndef'],
    ['abc\ndef', 3, 'abc\n\nX\n\ndef'],
    ['abc\ndef', 4, 'abc\n\nX\n\ndef'],
    ['abc\n\ndef', 5, 'abc\n\nX\n\ndef'],
    ['abc\n\ndef', 3, 'abc\n\nX\n\ndef'],
  ])('%j @ %i → %j', (body, caret, expected) => {
    const r = insertAtCaret(body, caret, 'X')
    expect(r.body).toBe(expected)
    // İmleç eklenen parçanın (ve ardındaki ayırıcının) hemen ardındadır
    expect(r.body.slice(0, r.caret).endsWith('X\n') || r.body.slice(0, r.caret).endsWith('X\n\n') || r.body.slice(0, r.caret).endsWith('X')).toBe(true)
    expect(r.caret).toBeLessThanOrEqual(r.body.length)
  })

  it('imleç bilinmiyorsa / sınır dışıysa metnin sonuna ekler', () => {
    expect(insertAtCaret('abc', null, 'X').body).toBe('abc\n\nX\n')
    expect(insertAtCaret('abc', undefined, 'X').body).toBe('abc\n\nX\n')
    expect(insertAtCaret('abc', NaN, 'X').body).toBe('abc\n\nX\n')
    expect(insertAtCaret('abc', 99, 'X').body).toBe('abc\n\nX\n')
    expect(insertAtCaret('abc', -5, 'X').body).toBe('X\n\nabc')
    expect(insertAtCaret(null, 0, 'X')).toEqual({ body: 'X\n', caret: 2 })
  })

  it('art arda iki ekleme iki ayrı paragraf verir (ikincisi birincinin ardına)', () => {
    const one = insertAtCaret('Giriş', 5, '![a](te-note-img:1)')
    const two = insertAtCaret(one.body, one.caret, '![b](te-note-img:2)')
    expect(two.body).toBe('Giriş\n\n![a](te-note-img:1)\n\n![b](te-note-img:2)\n')
    expect(noteImageRefIds(two.body)).toEqual(['1', '2'])
  })
})

describe('görsel markdown\'ı ve alt metin', () => {
  it('uzantısız dosya adı alt metindir', () => {
    expect(fileBaseName('ekran.goruntusu.png')).toBe('ekran.goruntusu')
    expect(fileBaseName('adsiz')).toBe('adsiz')
    expect(fileBaseName('.gizli')).toBe('.gizli')
    expect(fileBaseName(null)).toBe('')
    expect(noteImageMarkdown(7, 'Qlik ana ekranı.png')).toBe('![Qlik ana ekranı](te-note-img:7)')
  })

  it('alt metin markdown\'ı bozamaz: köşeli parantez, ters bölü, ters tırnak, açılı parantez, yıldız, satır sonu boşluk olur', () => {
    expect(noteImageAlt('a[b]c.png')).toBe('a b c')
    expect(noteImageAlt('x](https://example.com/a.png) ![y.png')).toBe('x (https://example.com/a.png) ! y')
    expect(noteImageAlt('a\\b`c<d>e*f\ng.png')).toBe('a b c d e f g')
    expect(noteImageAlt('   çok    boşluk   .png')).toBe('çok boşluk')
    // Üretilen markdown tek bir not başvurusu taşır; alt metindeki dış adres ikinci bir görsel OLUŞTURMAZ
    const md = noteImageMarkdown(3, 'x](https://example.com/a.png) ![y.png')
    expect(md.endsWith('](te-note-img:3)')).toBe(true)
    expect(md.slice(2, md.length - '](te-note-img:3)'.length)).not.toMatch(/[[\]]/)
  })

  it('boş kalan alt metin yedek metne düşer; uzunluk sınırlıdır', () => {
    expect(noteImageAlt('[].png', 'görsel')).toBe('görsel')
    expect(noteImageAlt('', 'görsel')).toBe('görsel')
    expect(noteImageAlt(null)).toBe('')
    expect(noteImageAlt(`${'a'.repeat(300)}.png`).length).toBe(NOTE_ALT_MAX)
    expect(noteImageMarkdown(9, null, 'görsel')).toBe('![görsel](te-note-img:9)')
  })

  it('imagesById kimliği dizgiye çevirir; bozuk satırları atlar', () => {
    const map = imagesById([{ id: 1, file_name: 'a.png' }, null, { file_name: 'kimliksiz' }, { id: '2', file_name: 'b.png' }])
    expect([...map.keys()]).toEqual(['1', '2'])
    expect(imagesById(null).size).toBe(0)
    expect(imagesById('x').size).toBe(0)
  })

  it('taslakta kullanım: kimlik başvuru kümesindeyse', () => {
    const used = new Set(noteImageRefIds('![a](te-note-img:5)'))
    expect(imageUsedInDraft({ id: 5 }, used)).toBe(true)
    expect(imageUsedInDraft({ id: '5' }, used)).toBe(true)
    expect(imageUsedInDraft({ id: 6 }, used)).toBe(false)
    expect(imageUsedInDraft(null, used)).toBe(false)
    expect(imageUsedInDraft({ id: 5 }, ['5'])).toBe(false)   // küme değil
  })
})

describe('yerel taslak yedeği', () => {
  beforeEach(() => { localStorage.clear() })

  it('anahtar kişisel kayıt ailesindedir (çıkışta / kullanıcı değişince silinir)', () => {
    expect(noteBackupKey(10)).toBe(`${NOTE_BACKUP_PREFIX}10`)
    expect(PERSONAL_PREFIXES.some((p) => noteBackupKey(10).startsWith(p))).toBe(true)
    // Kartın açık / kapalı kaydı da aynı ailede
    expect(PERSONAL_PREFIXES.some((p) => 'sm.te.open.rn.100'.startsWith(p))).toBe(true)
  })

  it('yaz → oku → sil', () => {
    expect(readNoteBackup(10)).toBeNull()
    expect(writeNoteBackup(10, { version: 3, body: 'taslak' })).toBe(true)
    const b = readNoteBackup(10)
    expect(b.version).toBe(3)
    expect(b.body).toBe('taslak')
    expect(typeof b.at).toBe('number')
    expect(readNoteBackup(11)).toBeNull()
    dropNoteBackup(10)
    expect(readNoteBackup(10)).toBeNull()
  })

  it('bozuk kayıt yok sayılır (fırlatmaz)', () => {
    localStorage.setItem(noteBackupKey(10), '{bozuk')
    expect(readNoteBackup(10)).toBeNull()
    localStorage.setItem(noteBackupKey(10), JSON.stringify({ version: 1 }))
    expect(readNoteBackup(10)).toBeNull()
    localStorage.setItem(noteBackupKey(10), JSON.stringify('metin'))
    expect(readNoteBackup(10)).toBeNull()
    localStorage.setItem(noteBackupKey(10), JSON.stringify({ body: 'x', version: 'iki', at: 'dün' }))
    expect(readNoteBackup(10)).toEqual({ version: null, body: 'x', at: null })
  })

  it('depolama yazılamıyorsa false döner, fırlatmaz', () => {
    const original = Storage.prototype.setItem
    Storage.prototype.setItem = () => { throw new Error('QuotaExceededError') }
    try {
      expect(writeNoteBackup(10, { version: 1, body: 'x' })).toBe(false)
    } finally {
      Storage.prototype.setItem = original
    }
  })
})

describe('sunucu yanıtlarının sınıflandırılması', () => {
  const ok = (data) => ({ success: true, data })

  it('noteState: okunamayan hiçbir durum "not yok" sayılmaz', () => {
    expect(noteState(undefined)).toBe('loading')
    expect(noteState(null, { loading: true })).toBe('loading')
    expect(noteState(null)).toBe('failed')
    expect(noteState(ok({ exists: true, body: '# Başlık' }))).toBe('ok')
    expect(noteState(ok({ exists: false, body: '' }))).toBe('empty')
    expect(noteState(ok({ exists: true, body: '  \n ' }))).toBe('empty')
    expect(noteState(ok(null))).toBe('failed')
    expect(noteState(ok({ exists: true, body: 5 }))).toBe('failed')
    expect(noteState(ok([]))).toBe('failed')   // dizi gövde bozuk yanıttır — "henüz not yok" değil
    expect(noteState(ok('metin'))).toBe('failed')
    expect(noteState({ success: false, status: 403, code: 'CHANNEL_NOTES_NOT_SHARED' })).toBe('hidden')
    expect(noteState({ success: false, status: 404, code: 'CHANNEL_NOT_FOUND' })).toBe('notFound')
    expect(noteState({ success: false, status: 404, code: 'NOT_FOUND' })).toBe('unsupported')   // eski pod: bilinmeyen /api yolu
    expect(noteState({ success: false, status: 404 })).toBe('unsupported')
    expect(noteState({ success: false, status: 405 })).toBe('unsupported')
    expect(noteState({ success: false, status: 500 })).toBe('failed')
    expect(noteState({ success: false, status: 0, code: 'NETWORK_ERROR' })).toBe('failed')
    expect(noteState({ success: false, status: 503, code: 'DB_UNAVAILABLE' })).toBe('failed')
  })

  it('eski pod yanıtı: 405 ya da kanal-notu kodu taşımayan 404', () => {
    expect(isOldServerAnswer({ success: false, status: 404, code: 'NOT_FOUND' })).toBe(true)
    expect(isOldServerAnswer({ success: false, status: 404 })).toBe(true)
    expect(isOldServerAnswer({ success: false, status: 405 })).toBe(true)
    expect(isOldServerAnswer({ success: false, status: 404, code: 'CHANNEL_NOT_FOUND' })).toBe(false)
    expect(isOldServerAnswer({ success: false, status: 404, code: 'NOTE_VERSION_NOT_FOUND' })).toBe(false)
    expect(isOldServerAnswer({ success: false, status: 400, code: 'VALIDATION_FAILED' })).toBe(false)
    expect(isOldServerAnswer({ success: true, status: 200 })).toBe(false)
    expect(isOldServerAnswer(null)).toBe(false)
    expect(isChannelGone({ success: false, status: 404, code: 'CHANNEL_NOT_FOUND' })).toBe(true)
    expect(isChannelGone({ success: false, status: 404, code: 'NOT_FOUND' })).toBe(false)
  })

  it('çakışma: kod ya da kodsuz 409; kim / ne zaman / hangi sürüm üst düzeyden (ya da data\'dan) okunur', () => {
    const res = { success: false, status: 409, code: 'CONCURRENT_MODIFICATION', current_version: 6, updated_by_name: ' Ayşe Yılmaz ', updated_at: '2026-11-20T09:00:00Z' }
    expect(isNoteConflict(res)).toBe(true)
    expect(conflictInfoOf(res)).toEqual({ currentVersion: 6, by: 'Ayşe Yılmaz', at: '2026-11-20T09:00:00Z' })
    expect(isNoteConflict({ success: false, status: 409 })).toBe(true)
    expect(isNoteConflict({ success: false, status: 409, code: 'NOTE_IMAGE_IN_USE' })).toBe(false)
    expect(isNoteConflict({ success: true })).toBe(false)
    expect(isNoteConflict(null)).toBe(false)
    expect(conflictInfoOf({ data: { current_version: 2, updated_by_name: 'Can' } })).toEqual({ currentVersion: 2, by: 'Can', at: null })
    expect(conflictInfoOf({ current_version: '6', updated_by_name: '  ' })).toEqual({ currentVersion: null, by: null, at: null })
    expect(conflictInfoOf(null)).toEqual({ currentVersion: null, by: null, at: null })
  })

  it('kart durumu: anahtar yoksa / okunamaz ise kart yok; unavailable "not yok" değildir', () => {
    expect(noteCardState(undefined)).toBe('none')
    expect(noteCardState(null)).toBe('none')
    expect(noteCardState({ readable: false })).toBe('none')
    expect(noteCardState({ readable: 'true' })).toBe('none')
    expect(noteCardState({ exists: true })).toBe('none')
    expect(noteCardState({ readable: true, unavailable: true })).toBe('unknown')
    expect(noteCardState({ readable: true, unavailable: true, exists: false, empty: true })).toBe('unknown')
    expect(noteCardState({ readable: true, exists: true, empty: false })).toBe('note')
    expect(noteCardState({ readable: true, exists: true, empty: true })).toBe('empty')
    expect(noteCardState({ readable: true, exists: false, empty: true })).toBe('empty')
    expect(noteCardState({ readable: true })).toBe('empty')
  })

  it('sameNoteMeta değerle karşılaştırır (kart her rapor yanıtında yeniden çizilmesin)', () => {
    const a = { readable: true, exists: true, empty: false, updated_at: '2026-11-01T08:00:00Z', updated_by_name: 'Ayşe', image_count: 2 }
    expect(sameNoteMeta(a, { ...a })).toBe(true)
    expect(sameNoteMeta(a, a)).toBe(true)
    expect(sameNoteMeta(undefined, null)).toBe(true)
    expect(sameNoteMeta(a, undefined)).toBe(false)
    expect(sameNoteMeta(a, { ...a, updated_at: '2026-11-02T08:00:00Z' })).toBe(false)
    expect(sameNoteMeta(a, { ...a, image_count: 3 })).toBe(false)
    expect(sameNoteMeta(a, { ...a, empty: true })).toBe(false)
    expect(sameNoteMeta(a, { ...a, unavailable: true })).toBe(false)
    expect(sameNoteMeta({ readable: false }, { readable: false, extra: 1 })).toBe(true)
  })

  it('görsel retleri: bilinen kod → i18n anahtarı; bilinmeyen → null', () => {
    expect(noteImageRefusalKey({ code: 'NOTE_IMAGE_LIMIT' })).toBe('te.note.img.limitCount')
    expect(noteImageRefusalKey({ code: 'NOTE_IMAGE_TOTAL_LIMIT' })).toBe('te.note.img.limitTotal')
    expect(noteImageRefusalKey({ code: 'NOTE_IMAGE_IN_USE' })).toBe('te.note.img.inUse')
    expect(noteImageRefusalKey({ code: 'RATE_LIMITED' })).toBe('te.note.img.rateLimited')
    expect(noteImageRefusalKey({ code: 'RATE_LIMITED' }, 30)).toBe('te.note.img.rateLimited')
    // Tekil biçim yalnız 1 saniye için ("1 seconds" yazılmaz)
    expect(noteImageRefusalKey({ code: 'RATE_LIMITED' }, 1)).toBe('te.note.img.rateLimited.one')
    expect(noteImageRefusalKey({ code: 'VALIDATION_FAILED' })).toBeNull()
    expect(noteImageRefusalKey(null)).toBeNull()
  })

  it('bekleme süresi: retry_after_seconds / retryAfter; geçersiz değer null', () => {
    expect(retryAfterSeconds({ retry_after_seconds: 12 })).toBe(12)
    expect(retryAfterSeconds({ retryAfter: 2.2 })).toBe(3)
    expect(retryAfterSeconds({ retryAfter: '7' })).toBe(7)
    expect(retryAfterSeconds({ retry_after_seconds: 0 })).toBeNull()
    expect(retryAfterSeconds({ retryAfter: 'yakında' })).toBeNull()
    expect(retryAfterSeconds({})).toBeNull()
    expect(retryAfterSeconds(null)).toBeNull()
  })
})

describe('sürüm satırı sayımları — tekil / çoğul (inceleme 2026-11-20)', () => {
  it('yalnız 1 için `.one` anahtarı; bilinmeyen sayı çoğul kalır', () => {
    expect(versionCountKeys(1, 1)).toEqual(['te.note.ver.chars.one', 'te.note.ver.images.one'])
    expect(versionCountKeys(120, 0)).toEqual(['te.note.ver.chars', 'te.note.ver.images'])
    expect(versionCountKeys(2, 1)).toEqual(['te.note.ver.chars', 'te.note.ver.images.one'])
    expect(versionCountKeys(null, undefined)).toEqual(['te.note.ver.chars', 'te.note.ver.images'])
  })
})
