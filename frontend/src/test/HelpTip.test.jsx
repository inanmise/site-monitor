import { describe, it, expect, afterEach } from 'vitest'
import { render, screen, fireEvent, cleanup } from '@testing-library/react'
import HelpTip from '../components/ui/HelpTip.jsx'
import { EN } from '../i18n/index.jsx'

/**
 * HelpTip — ayar alanlarının yanındaki açıklama baloncuğu.
 *
 * Kilitlenen davranışlar:
 *  • EKSİK ÇEVİRİ = HİÇ ÇİZME. useT eksik anahtarda anahtarın KENDİSİNİ döndürür; korumasız
 *    bir ikon kullanıcıya "help.set.site.monitor.yok" ham anahtarını gösterirdi.
 *  • Tetikleyici <button> DEĞİL role="button" taşıyan <span>: ekranların çoğunda kontrolü
 *    SARAN bir <label> içinde duruyor ve <button> labelable olduğu için etiketin kontrolünü
 *    sessizce çalar (input erişilebilir adını kaybeder). Bu testler o seçimi de pinler.
 *  • Kapanma yolları: yeniden tıklama, Escape, dışarıya mousedown.
 */
// Varsayılan dil EN (LangProvider yokken storedLang -> 'en'): metinler EN sözlükten.
const KEY = 'help.set.site.monitor.storm.enabled'

afterEach(() => cleanup())

describe('HelpTip', () => {
  it('çeviri YOKSA hiçbir şey çizmez (ham anahtar asla görünmez)', () => {
    const { container } = render(<HelpTip helpKey="help.set.boyle.bir.anahtar.yok" />)
    expect(container.innerHTML).toBe('')
    expect(screen.queryByRole('button')).toBeNull()
  })

  it('helpKey verilmezse de hiçbir şey çizmez', () => {
    const { container } = render(<HelpTip />)
    expect(container.innerHTML).toBe('')
  })

  it('tıklayınca açılır, yeniden tıklayınca kapanır', () => {
    render(<HelpTip helpKey={KEY} />)
    const trigger = screen.getByRole('button')
    expect(trigger.getAttribute('aria-expanded')).toBe('false')

    fireEvent.click(trigger)
    expect(trigger.getAttribute('aria-expanded')).toBe('true')
    expect(screen.getByRole('tooltip').textContent).toContain('What it does')

    fireEvent.click(trigger)
    expect(trigger.getAttribute('aria-expanded')).toBe('false')
    expect(screen.queryByRole('tooltip')).toBeNull()
  })

  it('Escape kapatır', () => {
    render(<HelpTip helpKey={KEY} />)
    const trigger = screen.getByRole('button')
    fireEvent.click(trigger)
    expect(screen.getByRole('tooltip')).toBeTruthy()

    fireEvent.keyDown(document, { key: 'Escape' })
    expect(screen.queryByRole('tooltip')).toBeNull()
    expect(trigger.getAttribute('aria-expanded')).toBe('false')
  })

  it('dışarıya mousedown kapatır, baloncuğun İÇİ kapatmaz', () => {
    render(<HelpTip helpKey={KEY} />)
    fireEvent.click(screen.getByRole('button'))
    const pop = screen.getByRole('tooltip')

    // Metni seçmek için baloncuğa basmak kapatmamalı.
    fireEvent.mouseDown(pop)
    expect(screen.queryByRole('tooltip')).toBeTruthy()

    fireEvent.mouseDown(document.body)
    expect(screen.queryByRole('tooltip')).toBeNull()
  })

  it('klavyeyle (Enter / Space) açılır', () => {
    render(<HelpTip helpKey={KEY} />)
    const trigger = screen.getByRole('button')
    fireEvent.keyDown(trigger, { key: 'Enter' })
    expect(trigger.getAttribute('aria-expanded')).toBe('true')
    fireEvent.keyDown(trigger, { key: ' ' })
    expect(trigger.getAttribute('aria-expanded')).toBe('false')
  })

  it('aria sözleşmesi: ad, aria-expanded ve açıkken aria-describedby', () => {
    render(<HelpTip helpKey={KEY} label="Alarm fırtınası" />)
    const trigger = screen.getByRole('button')
    expect(trigger.getAttribute('aria-label')).toBe('Help: Alarm fırtınası')
    expect(trigger.getAttribute('aria-describedby')).toBeNull()

    fireEvent.click(trigger)
    const pop = screen.getByRole('tooltip')
    expect(trigger.getAttribute('aria-describedby')).toBe(pop.getAttribute('id'))
  })

  it('metin body\'ye PORTAL ile çizilir (overflow:hidden atalar kırpamasın)', () => {
    const { container } = render(<HelpTip helpKey={KEY} />)
    fireEvent.click(screen.getByRole('button'))
    const pop = screen.getByRole('tooltip')
    expect(container.contains(pop)).toBe(false)
    expect(document.body.contains(pop)).toBe(true)
    expect(pop.className).toContain('help-tip-pop')
  })

  it('üç satırlı metin pre-line ile çizilir (\\n gerçek satır olur)', () => {
    render(<HelpTip helpKey={KEY} />)
    fireEvent.click(screen.getByRole('button'))
    const body = document.querySelector('.help-tip-text')
    expect(body.textContent).toBe(EN[KEY])
    expect(body.textContent.split('\n')).toHaveLength(3)
  })

  it('tetikleyici <button> DEĞİL (label içinde kontrolün erişilebilir adını çalmasın)', () => {
    render(
      <label>
        Kullanıcı adı
        <HelpTip helpKey={KEY} />
        <input type="text" />
      </label>,
    )
    const trigger = screen.getByRole('button')
    expect(trigger.tagName).toBe('SPAN')
    expect(trigger.getAttribute('tabindex')).toBe('0')
    // Etiketin kontrolü hâlâ input: erişilebilir ad korunur.
    expect(screen.getByLabelText(/Kullanıcı adı/).tagName).toBe('INPUT')
  })
})
