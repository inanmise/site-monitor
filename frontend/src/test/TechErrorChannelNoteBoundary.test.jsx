import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen } from './test-utils.jsx'

/**
 * Kanal notu görüntüleyicisinin hata sınırı (inceleme 2026-11-20 #5): aşırı iç içe bir not markdown ağacını kurarken
 * yığını taşırabilir. Çökme çevreleyen ekranı (sekme, rapor kartı, görsel paneli) DÜŞÜRMEZ — yerinde "not çizilemedi" +
 * notun ham metni (sarar) görünür; metin değişince yeniden denenir. Parça önbelleği modül düzeyinde olduğu için bu dosya
 * kendi (çizimde fırlatan) görüntüleyicisini yükler.
 */
vi.mock('../utils/importWithDeadline.js', async (importOriginal) => {
  const mod = await importOriginal()
  return {
    ...mod,
    importWithDeadline: vi.fn(() => Promise.resolve({
      default: function ThrowingView({ body }) {
        if (String(body).includes('>>>>')) throw new RangeError('Maximum call stack size exceeded')
        return <div data-testid="stub-view">{body}</div>
      },
    })),
  }
})
import { LANG_STORAGE_KEY } from '../i18n/dateLocale.js'
import { LazyNoteView, loadNotePart } from '../components/techerror/notes/lazyParts.jsx'

const slot = (name) => document.querySelector(`[data-slot="${name}"]`)
const DEEP = `${'>'.repeat(40)} derin alıntı\n\nhttps://cok-uzun-bir-adres.example.com/${'a'.repeat(200)}`

beforeEach(() => {
  localStorage.clear()
  localStorage.setItem(LANG_STORAGE_KEY, 'tr')
})

describe('kanal notu görüntüleyicisi — hata sınırı', () => {
  it('çizilemeyen not ekranı düşürmez: açıklama + ham metin (sarar, kendi kabında kayar); metin değişince yeniden çizilir', async () => {
    await loadNotePart('view')
    const errSpy = vi.spyOn(console, 'error').mockImplementation(() => {})
    try {
      const { rerender } = render(<div data-testid="page"><p>Sayfanın geri kalanı</p><LazyNoteView body={DEEP} images={[]} /></div>)
      expect(screen.getByTestId('page')).toHaveTextContent('Sayfanın geri kalanı')
      const box = slot('te-note-view-broken')
      expect(box).not.toBeNull()
      expect(box).toHaveTextContent('Not biçimli olarak çizilemedi')
      const raw = slot('te-note-view-raw')
      expect(raw.textContent).toBe(DEEP)
      expect(raw.className).toContain('whitespace-pre-wrap')
      expect(raw.className).toContain('[overflow-wrap:anywhere]')
      expect(raw.className).toContain('overflow-y-auto')
      expect(raw.className).not.toMatch(/\btruncate\b/)
      expect(raw).toHaveAttribute('tabindex', '0')

      rerender(<div data-testid="page"><p>Sayfanın geri kalanı</p><LazyNoteView body="Düz not" images={[]} /></div>)
      expect(screen.getByTestId('stub-view')).toHaveTextContent('Düz not')
      expect(slot('te-note-view-broken')).toBeNull()
    } finally {
      errSpy.mockRestore()
    }
  })
})
