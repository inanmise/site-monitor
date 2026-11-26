import { describe, it, expect, beforeEach } from 'vitest'
import { render } from './test-utils.jsx'
import SignalChips, { signalText, signalTone } from '../components/oncallroutine/SignalChips.jsx'
import { TR } from '../i18n/tr.js'
import { LANG_STORAGE_KEY } from '../i18n/dateLocale.js'

/**
 * Nöbet Rutinleri — canlı sayaç çipinin tonu (inceleme bulgusu #6, 2026-11-26). Kısmi sayım (`partial: true`, "≥ n") bir
 * ALT SINIRDIR: n > 0 sorunu kanıtlar ama "0 sorun"u kanıtlamaz → kısmi iken çip ASLA yeşil (`ok`) değildir: değer 0 ise
 * nötr, 0'dan büyükse uyarı; sunucu `tone: 'ok'` dese bile kısmi değer nötr. Okunamayan / değersiz çip her zaman nötr.
 */
const chip = (key) => document.querySelector(`[data-slot="or-task-signal"][data-signal="${key}"]`)

beforeEach(() => {
  localStorage.clear()
  localStorage.setItem(LANG_STORAGE_KEY, 'tr')
})

describe('signalTone — doğruluk tablosu', () => {
  it.each([
    ['yok (null)', null, 'neutral'],
    ['tanımsız', undefined, 'neutral'],
    ['değer null', { value: null }, 'neutral'],
    ['okunamadı (error)', { value: 3, error: 'unavailable' }, 'neutral'],
    ['okunamadı, sunucu tonu kötü', { value: 3, tone: 'bad', error: 'unavailable' }, 'neutral'],
    ['değer 0 (tam sayım)', { value: 0 }, 'ok'],
    ['değer > 0 (tam sayım)', { value: 4 }, 'warn'],
    ['kısmi 0', { value: 0, partial: true }, 'neutral'],
    ['kısmi > 0', { value: 12, partial: true }, 'warn'],
    ['sunucu ok + kısmi', { value: 0, tone: 'ok', partial: true }, 'neutral'],
    ['sunucu ok + kısmi (değer > 0)', { value: 5, tone: 'ok', partial: true }, 'neutral'],
    ['sunucu ok, tam sayım', { value: 0, tone: 'ok', partial: false }, 'ok'],
    ['sunucu bad', { value: 2, tone: 'bad' }, 'bad'],
    ['sunucu bad + kısmi', { value: 2, tone: 'bad', partial: true }, 'bad'],
    ['sunucu warn + kısmi', { value: 7, tone: 'warn', partial: true }, 'warn'],
    ['bilinmeyen sunucu tonu → değerden', { value: 3, tone: 'mor' }, 'warn'],
    ['bilinmeyen sunucu tonu, kısmi 0 → nötr', { value: 0, tone: 'mor', partial: true }, 'neutral'],
  ])('%s → %s', (_label, sig, tone) => {
    expect(signalTone(sig)).toBe(tone)
  })
})

describe('signalText', () => {
  it('null → "—", kısmi → "≥ n", tam → n', () => {
    expect(signalText(null)).toBe('—')
    expect(signalText({ value: null })).toBe('—')
    expect(signalText({ value: 0, partial: true })).toBe('≥ 0')
    expect(signalText({ value: 12, partial: true })).toBe('≥ 12')
    expect(signalText({ value: 0 })).toBe('0')
  })
})

describe('SignalChips — kısmi sıfır çizimi', () => {
  it('kısmi 0 çip nötr ve "≥ 0" (hiçbir zaman yeşil değil); tam 0 yeşil', () => {
    render(
      <SignalChips keys={['open_incidents', 'expiring_certs']} signals={{
        open_incidents: { value: 0, tone: 'ok', partial: true, hidden: false, error: null, link: null },
        expiring_certs: { value: 0, tone: 'ok', partial: false, hidden: false, error: null, link: null },
      }} />,
    )
    const partial = chip('open_incidents')
    expect(partial.dataset.tone).toBe('neutral')
    expect(partial.textContent).toBe(`${TR['or.sig.open_incidents.label']}≥ 0`)
    expect(partial.getAttribute('aria-label')).toContain('≥ 0')
    // Karşılaştırma: kısmi olmayan 0 yeşil kalır
    expect(chip('expiring_certs').dataset.tone).toBe('ok')
  })
})
