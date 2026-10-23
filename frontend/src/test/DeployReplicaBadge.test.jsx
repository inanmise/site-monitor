import { describe, it, expect, beforeEach } from 'vitest'
import { render, screen, within } from './test-utils.jsx'
import { LANG_STORAGE_KEY } from '../i18n/dateLocale.js'
import DeployRecords from '../components/admin/releases/DeployRecords.jsx'
import { DeployCard } from '../components/admin/releases/DeployTimeline.jsx'

/**
 * Sürüm & Dağıtım geçmişi — ek pod rozeti (2026-10-23, çok pod R2b, MP-37e): sunucu satırı `replica: true` taşıyorsa (aynı sürüm
 * başka canlı pod'da koşarken açılan ek pod) RESTART "Ek pod" olarak nötr tonla çizilir; alan yoksa bugünkü "Yeniden başlatma".
 * Yeniden başlatma sayıları sunucudan gelir ve ek pod'ları zaten saymaz.
 */
const row = (id, over = {}) => ({
  id, startedAt: '2026-10-23T10:00:00Z', environment: 'prod', version: '20.167.0', previousVersion: '20.167.0',
  kind: 'RESTART', source: 'STARTUP', pod: `sitemonitor-prod-7d9f8c6b5d-${id}`, ...over,
})

describe('ek pod rozeti', () => {
  beforeEach(() => {
    try { localStorage.clear(); localStorage.setItem(LANG_STORAGE_KEY, 'tr') } catch { /* yok */ }
  })

  it('tablo: replica → "Ek pod" (nötr, data-replica); replica yok / false → "Yeniden başlatma"', async () => {
    render(<DeployRecords rows={[row(1, { replica: true }), row(2), row(3, { replica: false })]} narrow={false}
      canEdit={false} sort="started_at" dir="desc" onSort={() => {}} />)
    const table = await screen.findByTestId('deploy-table')
    const rows = within(table).getAllByRole('row').slice(1)
    const extra = within(rows[0]).getByText('Ek pod').closest('[data-slot="badge"]')
    expect(extra.getAttribute('data-replica')).toBe('true')
    expect(extra.getAttribute('data-kind')).toBe('RESTART')
    expect(extra.className).toContain('bg-muted')
    expect(within(rows[1]).getByText('Yeniden başlatma')).toBeTruthy()
    expect(within(rows[2]).getByText('Yeniden başlatma')).toBeTruthy()
    expect(within(table).queryAllByText('Ek pod')).toHaveLength(1)
  })

  it('kart (dar ekran): replica kartı işaretli ve "Ek pod" der; geçiş türleri değişmez', async () => {
    render(
      <div>
        <DeployCard d={row(4, { replica: true })} duration={null} />
        <DeployCard d={row(5, { kind: 'UPGRADE', previousVersion: '20.166.0', replica: true })} duration={null} />
      </div>,
    )
    const cards = await screen.findAllByRole('article')
    expect(cards[0].getAttribute('data-replica')).toBe('true')
    expect(within(cards[0]).getByText('Ek pod')).toBeTruthy()
    expect(within(cards[1]).queryByText('Ek pod')).toBeNull()
    expect(within(cards[1]).getByText('Yükseltme')).toBeTruthy()
  })

  it('İngilizce: "Extra pod"', async () => {
    localStorage.setItem(LANG_STORAGE_KEY, 'en')
    render(<DeployCard d={row(6, { replica: true })} duration={null} />)
    expect(await screen.findByText('Extra pod')).toBeTruthy()
  })
})
