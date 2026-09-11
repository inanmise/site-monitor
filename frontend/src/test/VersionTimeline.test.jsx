import { describe, it, expect, vi } from 'vitest'
import { render, screen, fireEvent } from './test-utils.jsx'
import VersionTimeline from '../components/scripted/VersionTimeline.jsx'
import { DEPLOY_KIND_STYLE } from '../utils/releaseUi.js'

vi.mock('../api/client', () => ({ formatDateSec: (s) => `F(${s})` }))
vi.mock('../components/ui/UserBadge.jsx', () => ({ default: ({ username }) => <span data-testid="ub">{username}</span> }))

const ROWS = [
  { id: 1, version: '1.0.0', event_type: 'CREATE', created_at: 't1', created_by: 'ops', note: 'ilk', current: false },
  { id: 2, version: '1.1.0', event_type: 'EDIT', created_at: 't2', created_by: 'ops', note: null, current: true },
]

describe('VersionTimeline — mevcut sözleşme değişmedi', () => {
  it('varsayılan: v-önek, tarih + UserBadge meta, not satırı, ŞU AN rozeti, seçim', () => {
    const onPick = vi.fn()
    render(<VersionTimeline rows={ROWS} selId={2} onPick={onPick} eventLabel={(e) => `E:${e}`} currentLabel="ŞU AN" />)
    expect(screen.getByText('v1.0.0')).toBeInTheDocument()
    expect(screen.getAllByTestId('ub').length).toBe(2)
    expect(screen.getByText('ilk')).toBeInTheDocument()
    expect(screen.getByText('ŞU AN')).toBeInTheDocument()
    expect(document.querySelector('.sc-vt-item.is-sel')).not.toBeNull()
    fireEvent.click(screen.getByText('v1.0.0').closest('[role="button"]'))
    expect(onPick).toHaveBeenCalledWith(ROWS[0])
    expect(document.querySelectorAll('.sc-vt-caret').length).toBe(2)
  })
})

describe('VersionTimeline — 2026-09-11 genişlemesi (sürüm & dağıtım)', () => {
  it('eventStyles yeni türleri tonlar, renderMeta UserBadge yerine geçer, renderBelow satır altına açılır, caret özelleşir', () => {
    const rows = [
      { id: 10, version: '20.54.0', event_type: 'UPGRADE', created_at: 'd1', current: true },
      { id: 11, version: '20.53.2', event_type: 'ROLLBACK', created_at: 'd2', current: false },
    ]
    render(
      <VersionTimeline rows={rows} selId={null} onPick={() => {}} eventLabel={(e) => e} currentLabel="ŞU AN"
        eventStyles={DEPLOY_KIND_STYLE}
        renderMeta={(v) => <span data-testid="meta">M:{v.created_at}</span>}
        renderBelow={(v) => (v.id === 10 ? <div data-testid="below">detay</div> : null)}
        caret={() => <i data-testid="caret" />} />
    )
    expect(document.querySelector('.sc-vt-dot--up')).not.toBeNull()       // UPGRADE
    expect(document.querySelector('.sc-vt-dot--danger')).not.toBeNull()   // ROLLBACK
    expect(screen.getAllByTestId('meta').length).toBe(2)
    expect(screen.queryAllByTestId('ub').length).toBe(0)
    expect(screen.getByTestId('below')).toBeInTheDocument()
    expect(document.querySelectorAll('.sc-vt-below').length).toBe(1)
    expect(screen.getAllByTestId('caret').length).toBe(2)
    expect(document.querySelectorAll('.sc-vt-caret').length).toBe(0)
  })

  it('versionPrefix boş verilince çip yalnız sürümü yazar', () => {
    render(<VersionTimeline rows={ROWS.slice(0, 1)} selId={null} onPick={() => {}} eventLabel={() => ''} currentLabel="" versionPrefix="" />)
    expect(screen.getByText('1.0.0')).toBeInTheDocument()
  })
})
