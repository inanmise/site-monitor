import { describe, it, expect } from 'vitest'
import fs from 'node:fs'
import path from 'node:path'

/**
 * BEKÇİ: izleme düzenleme formlarında Kaydet/Test düğmelerinin METNİ meşgulken DEĞİŞMEZ.
 *
 * Gerekçe (2026-09-19, üretim ekran görüntüsü): envanter formunda "Kaydet" → "Kaydediliyor…" →
 * "İlk kontrol koşuyor…" (84→134→179 px) + araya giren şerit alt barı taşırıyor, Test et modalın
 * dışına kayıyordu. Dokuz izleme formunda da aynı sınıf vardı: "Kaydet" → "..." (84→48 px) Sil/İptal'i
 * 36 px sağa kaydırıyordu. Kural: düğme metni sabit + `aria-busy`; evre BAŞLIKTAKİ
 * `CheckRunningStrip` şeridinde (`.modal-icon-hdr-running`) anlatılır. jsdom yerleşimi
 * kanıtlayamadığı için sözleşme kaynakta pinlenir.
 */
const COMPONENTS = path.resolve(__dirname, '..', 'components')

// Dosya adı ÜRETİLMEZ: dizin listelenir (Windows harf-duyarsız FS uyuşmazlığı yerelde gizler).
const FORMS = fs.readdirSync(COMPONENTS)
  .filter(n => /MonitorPage\.jsx$/.test(n))
  .sort()

describe('izleme formu meşgul etiketleri (kayma yok)', () => {
  it('dokuz izleme formu bulunur', () => {
    expect(FORMS.length).toBeGreaterThanOrEqual(9)
  })

  for (const file of FORMS) {
    const src = fs.readFileSync(path.join(COMPONENTS, file), 'utf8')
    if (!src.includes('modal-sticky-actions')) continue   // düzenleme modalı olmayan sayfa

    it(`${file}: Kaydet/Test metni sabit, evre başlıktaki şeritte`, () => {
      // Düğme metni ternary ile değişmez ("..." dâhil): `.save` / `.test(Run)` anahtarı ternary'nin
      // içinde geçmez (başlıktaki şeritte `saving ? t('mon.saving') : t('x.testing')` MEŞRU).
      expect(src).not.toMatch(/\{saving \? (?:'\.\.\.'|t\([^)]*\)) : t\('[a-z]+\.save'\)/)
      expect(src).not.toMatch(/\{testing \? t\([^)]*\) : t\('[a-z]+\.test(?:Run)?'\)/)
      // Kilit + aria-busy.
      expect(src).toMatch(/onClick=\{save\}[^>]*aria-busy=\{saving \|\| undefined\}/s)
      expect(src).toMatch(/aria-busy=\{testing(?: \|\| undefined)?\}/)
      // Şerit başlıkta, iki evreyi de anlatır.
      expect(src).toContain('className="modal-icon-hdr-running"')
      expect(src).toMatch(/<CheckRunningStrip running=\{saving \|\| testing\} label=\{saving \? t\('mon\.saving'\) : t\('[a-z]+\.testing'\)\}/)
      // Şerit alt barda DEĞİL.
      const actions = src.slice(src.lastIndexOf('className="modal-actions"'))
      expect(actions).not.toContain('CheckRunningStrip')
    })
  }
})
