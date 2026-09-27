import { describe, it, expect } from 'vitest'
import { render } from '@testing-library/react'
import MonitorCardMeta from '../components/MonitorCardMeta.jsx'

/**
 * KART ROZETLERİ (takım / grup) — altı izleme sayfasında satır içi {@code style} nesnesiyle
 * birlikte birebir kopyalanmış on satırdı ve <b>hiçbiri test edilmiyordu</b>. Çıkarımdan önce
 * ölçüldü: boş kontrolünü kaldırmak 803 testin HİÇBİRİNİ kırmadı — yani takımı olmayan
 * monitörlerin kartında ikonlu ama METİNSİZ boş bir satır belirirdi, altı sayfada birden.
 *
 * Boş rozet özellikle envanter-türevi kayıtlarda görünürdü: onların {@code team_name}'i
 * çoğu zaman yok.
 */
const meta = (monitor) => {
  const { container } = render(<MonitorCardMeta monitor={monitor} />)
  return container
}

describe('MonitorCardMeta', () => {
  it('rozetler TEK satırda yan yana ve dar kartta SARAR (2026-09-26: üç ayrı satırdı)', () => {
    const c = meta({ team_name: 'SY-A', group_name: 'Kritik', proxy_effective: 'proxy' })
    const row = c.querySelector('[data-slot="monitor-card-meta"]')
    expect(row).toHaveClass('flex', 'flex-wrap')
    expect([...row.children].map((el) => el.dataset.slot)).toEqual(['meta-team', 'meta-group', 'meta-proxy'])
  })

  it('takım ve grup doluysa iki rozet de çizilir', () => {
    const c = meta({ team_name: 'SY-A', group_name: 'Kritik' })
    expect(c.querySelectorAll('[data-slot^="meta-"]')).toHaveLength(2)
    expect(c.textContent).toContain('SY-A')
    expect(c.textContent).toContain('Kritik')
  })

  it('TAKIM yoksa o rozet HİÇ çizilmez (boş ikonlu satır bırakılmaz)', () => {
    const c = meta({ group_name: 'Kritik' })
    expect(c.querySelectorAll('[data-slot^="meta-"]')).toHaveLength(1)
    expect(c.textContent).toBe('Kritik')
  })

  it('GRUP yoksa o rozet hiç çizilmez', () => {
    const c = meta({ team_name: 'SY-A' })
    expect(c.querySelectorAll('[data-slot^="meta-"]')).toHaveLength(1)
    expect(c.textContent).toBe('SY-A')
  })

  it('ikisi de yoksa hiçbir şey çizilmez (kartta boşluk açılmaz)', () => {
    expect(meta({}).querySelector('[data-slot="monitor-card-meta"]')).toBeNull()   // boş satır kabı da yok
    expect(meta({}).querySelectorAll('[data-slot^="meta-"]')).toHaveLength(0)
    expect(meta({ team_name: null, group_name: undefined }).querySelectorAll('[data-slot^="meta-"]')).toHaveLength(0)
  })

  it('BOŞ STRING de "yok" sayılır — envanter-türevi kayıtlarda sık', () => {
    const c = meta({ team_name: '', group_name: '' })
    expect(c.querySelectorAll('[data-slot^="meta-"]')).toHaveLength(0)
  })
})
