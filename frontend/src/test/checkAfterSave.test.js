import { describe, it, expect, vi } from 'vitest'
import { CHECK_FIELDS, shouldCheckAfterSave, startCheckAfterSave } from '../utils/checkAfterSave.js'

/**
 * Kayıt sonrası kontrol KARARI (utils/checkAfterSave) — dokuz tür, tek kural (2026-09-28).
 * Sayfa kablolaması monitorCheckAfterSave.test.jsx / ScriptedMonitorPage.firstCheck.test.jsx'te; burada karar tablosu.
 */
const KINDS = ['http', 'ping', 'keyword', 'port', 'dns', 'page', 'pagespeed', 'scripted', 'domain']

/** Alanı "farklı" yapan değer — tipine göre (metin / sayı / mantıksal). */
const bump = (v) => (typeof v === 'boolean' ? !v : typeof v === 'number' ? v + 1 : `${v ?? ''}x`)

const META = { name: 'Renamed', tags: 'prod,web', group_name: 'Other', notify_email: false, notify_webhook: false,
  alert_level: 'CRITICAL', notification_group_id: 9, interval_seconds: 600, confirm_attempts: 5, noc_notify: true }

describe('shouldCheckAfterSave', () => {
  it('dokuz türün HER biri için alan listesi tanımlı ve boş değil', () => {
    expect(Object.keys(CHECK_FIELDS).sort()).toEqual([...KINDS].sort())
    for (const k of KINDS) expect(CHECK_FIELDS[k].length).toBeGreaterThan(0)
  })

  it.each(KINDS)('%s: oluşturma (etkin, kimlikli) → kontrol', (kind) => {
    expect(shouldCheckAfterSave(kind, { isNew: true, after: { id: 7, active: true } })).toBe(true)
    // `active` alanı yanıtta yoksa da etkin sayılır (sunucu varsayılanı: yeni izleme etkin).
    expect(shouldCheckAfterSave(kind, { isNew: true, after: { id: 7 } })).toBe(true)
  })

  it.each(KINDS)('%s: DURAKLATILMIŞ izleme hiç kontrol edilmez (oluşturma ya da hedef değişikliği)', (kind) => {
    expect(shouldCheckAfterSave(kind, { isNew: true, after: { id: 7, active: false } })).toBe(false)
    const f = CHECK_FIELDS[kind][0]
    expect(shouldCheckAfterSave(kind, { before: { id: 7, active: false, [f]: 'a' }, after: { id: 7, active: false, [f]: 'b' } })).toBe(false)
  })

  it.each(KINDS)('%s: kimliksiz / başarısız yanıt → kontrol YOK', (kind) => {
    expect(shouldCheckAfterSave(kind, { isNew: true, after: {} })).toBe(false)
    expect(shouldCheckAfterSave(kind, { isNew: true, after: null })).toBe(false)
    expect(shouldCheckAfterSave(kind, { isNew: true })).toBe(false)
  })

  it.each(KINDS)('%s: kontrolü etkileyen HER alan tek başına taze kontrol başlatır', (kind) => {
    const before = { id: 7, active: true }
    for (const f of CHECK_FIELDS[kind]) before[f] = f.endsWith('_ms') || f.endsWith('_count') || f === 'port' ? 10 : 'v'
    for (const f of CHECK_FIELDS[kind]) {
      const after = { ...before, [f]: bump(before[f]) }
      expect(shouldCheckAfterSave(kind, { before, after }), `${kind}.${f}`).toBe(true)
    }
  })

  it.each(KINDS)('%s: yalnız meta düzenlemesi (ad / etiket / grup / bildirim / aralık) → kontrol YOK', (kind) => {
    const before = { id: 7, active: true, url: 'https://www.example.com/', host: 'h.example.com', domain: 'example.com' }
    expect(shouldCheckAfterSave(kind, { before, after: { ...before, ...META } })).toBe(false)
  })

  it('normalleştirme: boş/null/undefined eşit, metin kırpılır — sahte "değişti" yok', () => {
    const before = { id: 1, url: 'https://www.example.com/', expected_status: null, timeout_ms: 5000 }
    expect(shouldCheckAfterSave('http', { before, after: { ...before, expected_status: '', url: ' https://www.example.com/ ' } })).toBe(false)
    expect(shouldCheckAfterSave('http', { before, after: { ...before, expected_status: undefined } })).toBe(false)
    expect(shouldCheckAfterSave('http', { before, after: { ...before, timeout_ms: 7000 } })).toBe(true)
  })

  it('satırda görünmeyen gizli alan (başlık / parola) yazıldıysa `extraChanged` kontrolü başlatır; duraklatılmışta yine YOK', () => {
    const row = { id: 3, active: true, url: 'https://www.example.com/' }
    expect(shouldCheckAfterSave('keyword', { before: row, after: row })).toBe(false)
    expect(shouldCheckAfterSave('keyword', { before: row, after: row, extraChanged: true })).toBe(true)
    expect(shouldCheckAfterSave('pagespeed', { before: row, after: { ...row, active: false }, extraChanged: true })).toBe(false)
  })

  it('sentetik: içerik değişikliği sunucunun yeni SÜRÜMÜYLE görünür; sürüm aynıysa (yalnız ad/etiket) koşum yok', () => {
    const before = { id: 4, active: true, script_version: '1.0.2', timeout_seconds: 60, use_proxy: 'AUTO' }
    expect(shouldCheckAfterSave('scripted', { before, after: { ...before, script_version: '1.0.3' } })).toBe(true)
    expect(shouldCheckAfterSave('scripted', { before, after: { ...before, timeout_seconds: 90 } })).toBe(true)
    expect(shouldCheckAfterSave('scripted', { before, after: { ...before, name: 'x', tags: 'y' } })).toBe(false)
  })

  it('bilinmeyen tür → yalnız oluşturmada kontrol (düzenlemede alan listesi yok)', () => {
    expect(shouldCheckAfterSave('nope', { isNew: true, after: { id: 1 } })).toBe(true)
    expect(shouldCheckAfterSave('nope', { before: { id: 1, a: 1 }, after: { id: 1, a: 2 } })).toBe(false)
  })
})

describe('startCheckAfterSave', () => {
  it('checkNow\'u satırla (ve verilen seçenekle) çağırır, sonucu BEKLEMEZ', () => {
    const checkNow = vi.fn(() => new Promise(() => {}))
    startCheckAfterSave(checkNow, { id: 5 })
    expect(checkNow).toHaveBeenCalledWith({ id: 5 })
    startCheckAfterSave(checkNow, { id: 6 }, { silent: true })
    expect(checkNow).toHaveBeenLastCalledWith({ id: 6 }, { silent: true })
  })

  it('ret YUTULUR (işlenmemiş ret yok) ve senkron hata kayıt akışını bozmaz', async () => {
    const onUnhandled = vi.fn()
    process.on('unhandledRejection', onUnhandled)
    try {
      startCheckAfterSave(() => Promise.reject(new Error('Failed to fetch')), { id: 5 })
      expect(() => startCheckAfterSave(() => { throw new Error('boom') }, { id: 5 })).not.toThrow()
      await new Promise((r) => setTimeout(r, 0))
      expect(onUnhandled).not.toHaveBeenCalled()
    } finally {
      process.off('unhandledRejection', onUnhandled)
    }
  })
})
