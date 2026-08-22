import { render, screen } from './test-utils.jsx'
import { describe, it, expect } from 'vitest'
import ScriptedTemplateInfo from '../components/scripted/ScriptedTemplateInfo.jsx'

/**
 * Şablon bilgi paneli — monitör formunda seçili şablonun ne işe yaradığını anlatır.
 *
 * <p>Testi YOKTU. 2026-08-22'de kardeş yüzeyde ("Görüntüle" penceresi) arayüz İngilizceyken Türkçe
 * metin gösterilmesi hatası çıkınca, aynı veriyi basan üç yüzeyin de İngilizce yolunun çizilmesi
 * gerektiği anlaşıldı: kart listesi, bilgi paneli ve görüntüleme penceresi. Bu dosya üçüncüsünü
 * kapatır — panel bugün doğru çalışıyor, ama hiçbir test bunu tutmuyordu.
 */
const t = (k, ...a) => (a.length ? `${k}:${a.join('|')}` : k)

const TPL = {
  name: 'Ödeme akışı', name_en: 'Payment flow',
  description: 'Kart ucunu uçtan uca dener', description_en: 'Exercises the card endpoint end to end',
  when_to_use: 'Ödeme kesintisinde', when_to_use_en: 'During a payment outage',
  scope: 'team', team_name: 'Kanal', current_version: '1.2.0',
  env: [{ name: 'BASE_URL' }, { name: 'API_KEY' }],
}

describe('ScriptedTemplateInfo — dil', () => {
  it('İngilizce arayüzde İNGİLİZCE açıklamayı gösterir', () => {
    render(<ScriptedTemplateInfo tpl={TPL} lang="en" t={t} />)

    expect(screen.getByText('Exercises the card endpoint end to end')).toBeInTheDocument()
    expect(screen.getByText('During a payment outage')).toBeInTheDocument()
    expect(screen.queryByText('Kart ucunu uçtan uca dener')).not.toBeInTheDocument()
  })

  it('Türkçe arayüzde Türkçe açıklamayı gösterir', () => {
    render(<ScriptedTemplateInfo tpl={TPL} lang="tr" t={t} />)

    expect(screen.getByText('Kart ucunu uçtan uca dener')).toBeInTheDocument()
    expect(screen.queryByText('Exercises the card endpoint end to end')).not.toBeInTheDocument()
  })

  it('İngilizce karşılığı yoksa Türkçe metne düşer — panel BOŞ kalmaz', () => {
    render(<ScriptedTemplateInfo lang="en" t={t}
      tpl={{ ...TPL, description_en: null, when_to_use_en: null }} />)

    expect(screen.getByText('Kart ucunu uçtan uca dener')).toBeInTheDocument()
    expect(screen.getByText('Ödeme kesintisinde')).toBeInTheDocument()
  })

  it('kapsam rozeti ve gereken env değişkenleri görünür', () => {
    render(<ScriptedTemplateInfo tpl={TPL} lang="tr" t={t} />)

    expect(screen.getByText('Kanal')).toBeInTheDocument()
    expect(screen.getByText('v1.2.0')).toBeInTheDocument()
    expect(screen.getByText('BASE_URL, API_KEY')).toBeInTheDocument()
  })

  it('şablon seçilmemişken hiçbir şey çizmez', () => {
    const { container } = render(<ScriptedTemplateInfo tpl={null} lang="tr" t={t} />)
    expect(container).toBeEmptyDOMElement()
  })
})
