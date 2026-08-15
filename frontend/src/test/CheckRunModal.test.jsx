import { describe, it, expect, vi } from 'vitest'
import { render, screen, fireEvent } from './test-utils.jsx'
import CheckRunModal from '../components/check/CheckRunModal.jsx'
import CheckTeamPicker, { teamBuckets, NO_TEAM } from '../components/check/CheckTeamPicker.jsx'

const certIndex = {
  'a.akbank.com': { team_name: 'SY-Dijital', tier: 1, port: 443 },
  'b.akbank.com': { team_name: 'SY-Kart', tier: 3, port: 8443 },
}

const run = {
  total: 2,
  done: true,
  teamLabel: 'SY-Dijital',
  rows: [
    { domain: 'a.akbank.com', start: new Date('2026-08-08T10:00:00'), end: new Date(), ms: 191, ok: true,
      data: { days_remaining: 31, not_after: '2026-09-09T02:59:00', http_status: 200, port: 443 } },
    { domain: 'b.akbank.com', start: new Date('2026-08-08T10:00:01'), end: new Date(), ms: 5002, ok: false,
      error: 'connect timed out',
      data: { status: 'error', error: 'connect timed out', http_status: null, port: 8443 } },
  ],
}

describe('CheckRunModal', () => {
  it('sonuç kolonlarını basar: takım, tier, port, HTTP, kalan gün, bitiş tarihi', () => {
    render(<CheckRunModal run={run} certIndex={certIndex} onClose={() => {}} onCancel={() => {}} />)

    expect(screen.getByText('a.akbank.com')).toBeInTheDocument()
    // Takım hem satır kolonunda hem özet şeridinde geçer (kapsam etiketi) → ikisi de beklenir.
    expect(screen.getAllByText('SY-Dijital').length).toBe(2)
    expect(screen.getByText('T1')).toBeInTheDocument()
    expect(screen.getByText('8443')).toBeInTheDocument()
    expect(screen.getByText('200')).toBeInTheDocument()
    expect(screen.getByText(/31 (gün|days)/)).toBeInTheDocument()
    expect(screen.getByText('09.09.2026')).toBeInTheDocument()
  })

  it('durum kendi kolonunda ve hata satırı mesajı gösterir', () => {
    render(<CheckRunModal run={run} certIndex={certIndex} onClose={() => {}} onCancel={() => {}} />)
    expect(document.querySelectorAll('.chk-td-status').length).toBe(2)
    expect(document.querySelector('.chk-tick-err')).not.toBeNull()
    expect(screen.getByText('connect timed out')).toBeInTheDocument()
  })

  it('koşarken Durdur butonu görünür ve onCancel çağırır; bitince gizlenir', () => {
    const onCancel = vi.fn()
    const { rerender } = render(
      <CheckRunModal run={{ ...run, done: false }} certIndex={certIndex} onClose={() => {}} onCancel={onCancel} />)
    const stop = screen.getByRole('button', { name: /durdur|stop/i })
    fireEvent.click(stop)
    expect(onCancel).toHaveBeenCalled()

    rerender(<CheckRunModal run={run} certIndex={certIndex} onClose={() => {}} onCancel={onCancel} />)
    expect(screen.queryByRole('button', { name: /durdur|^stop$/i })).toBeNull()
  })

  it('süre DUVAR SAATİ\'nden gelir — satır sürelerinin toplamı değil (kontroller paralel)', () => {
    // İki satırın ms toplamı 5193 (~5,2 s) ama koşum 2 sn sürmüş: paralel koşumda toplam,
    // gerçekte geçen sürenin çok üstünde çıkar ve kullanıcıya yanlış bilgi verirdi.
    const started = Date.now() - 2000
    render(<CheckRunModal run={{ ...run, startedAt: started, finishedAt: started + 2000 }}
      certIndex={certIndex} onClose={() => {}} onCancel={() => {}} />)

    expect(screen.getByText(/(Toplam|Total) 2\.000 s/)).toBeInTheDocument()
    expect(screen.queryByText(/5\.193 s/)).toBeNull()
  })

  it('run yoksa hiçbir şey render etmez', () => {
    const { container } = render(<CheckRunModal run={null} certIndex={{}} onClose={() => {}} onCancel={() => {}} />)
    expect(container.querySelector('.chk-modal')).toBeNull()
  })
})

describe('CheckTeamPicker', () => {
  const certs = [
    { domain: 'a.akbank.com', team_id: 1, team_name: 'SY-Dijital' },
    { domain: 'b.akbank.com', team_id: 1, team_name: 'SY-Dijital' },
    { domain: 'c.akbank.com', team_id: 2, team_name: 'SY-Kart' },
    { domain: 'd.akbank.com', team_id: null, team_name: null },
  ]

  it('takımları certs\'ten türetir, sayar; takımsız en sonda', () => {
    const b = teamBuckets(certs)
    expect(b.map(x => x.key)).toEqual(['1', '2', NO_TEAM])
    expect(b[0].count).toBe(2)
    expect(b.at(-1).key).toBe(NO_TEAM)
  })

  it('seçimi daraltıp başlatınca yalnız seçili takım anahtarları gönderilir', () => {
    localStorage.clear()
    const onStart = vi.fn()
    render(<CheckTeamPicker certs={certs} onStart={onStart} onClose={() => {}} />)

    fireEvent.click(screen.getByText('SY-Kart'))          // 2 numaralı takımı çıkar
    fireEvent.click(screen.getByText(/takımsız|no team/i))
    fireEvent.click(screen.getByRole('button', { name: /kontrolü başlat|start check/i }))

    expect(onStart).toHaveBeenCalled()
    expect(onStart.mock.calls[0][0]).toEqual(['1'])
    expect(JSON.parse(localStorage.getItem('sm.checkRun.teams'))).toEqual(['1'])
  })
})
