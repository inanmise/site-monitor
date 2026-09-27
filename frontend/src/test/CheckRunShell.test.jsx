import { describe, it, expect, vi } from 'vitest'
import { render, screen, fireEvent } from './test-utils.jsx'
import CheckRunShell, { CELL } from '../components/check/CheckRunShell.jsx'
import MonitorCheckRunModal from '../components/check/MonitorCheckRunModal.jsx'
import CheckAllButton from '../components/check/CheckAllButton.jsx'

const baseRow = (over = {}) => ({
  monitor: { id: 1, name: 'API', url: 'https://api.example.com' },
  start: new Date('2026-09-03T10:00:00'), end: new Date('2026-09-03T10:00:01'),
  ms: 191, ok: true, data: {}, error: null, ...over,
})

const runOf = (rows, over = {}) => ({
  rows, total: rows.length, done: true, teamLabel: null,
  startedAt: 1000, finishedAt: 3000, ...over,
})

describe('CheckRunShell', () => {
  // Pencere ui/ModalShell (Radix Dialog) → içerik document.body'ye PORTAL'lanır: sorgular belge geneli.
  it('kolonları verilen SIRAYLA çizer ve varsayılan hücre görünümü monospace', () => {
    render(
      <CheckRunShell run={runOf([baseRow()])} nameHeader="İzleme" nameOf={r => r.monitor.name}
        columns={[
          { key: 'a', label: 'A', render: () => 'a-değeri' },
          { key: 'b', label: 'B', tdClassName: 'my-target', render: () => 'b-değeri' },
          { key: 'c', label: 'C', tdClassName: '', render: () => 'c-değeri' },
        ]}
        onClose={() => {}} onCancel={() => {}} />)

    const heads = [...document.querySelectorAll('thead th')].map(th => th.textContent.trim())
    expect(heads.slice(1, 5)).toEqual(['İzleme', 'A', 'B', 'C'])
    const cells = [...document.querySelectorAll('tbody td')]
    expect(cells[2].getAttribute('data-slot')).toBe('table-cell')           // shadcn TableCell
    expect(cells[2].className).toContain('font-mono')                       // varsayılan: CELL.mono
    expect(cells[3].className.split(/ +/)).toContain('my-target')           // çağıranın sınıfı eklenir
    expect(cells[3].className).not.toContain('font-mono')
    expect(cells[4].className).not.toContain('font-mono')                   // '' → ek sınıf YOK
    expect(CELL.mono).toContain('font-mono')
  })

  it('hata mesajı YALNIZ başarısız satırda görünür', () => {
    render(
      <CheckRunShell
        run={runOf([baseRow(), baseRow({ ok: false, error: 'connect timed out' })])}
        nameHeader="İzleme" nameOf={r => r.monitor.name} columns={[]}
        onClose={() => {}} onCancel={() => {}} />)

    expect(document.querySelectorAll('[data-col="status"]').length).toBe(2)
    expect(document.querySelectorAll('[data-slot="check-row-msg"]').length).toBe(1)
    expect(document.querySelectorAll('tr[data-error="true"]').length).toBe(1)
    expect(document.querySelectorAll('[data-ok="false"]').length).toBe(1)
    expect(screen.getByText('connect timed out')).toBeInTheDocument()
  })

  it('süre DUVAR SAATİNDEN gelir, satır sürelerinin toplamından değil', () => {
    // İki satır 191 ms + 5002 ms sürdü ama koşum paralel: gerçek süre 2.000 s.
    render(
      <CheckRunShell
        run={runOf([baseRow({ ms: 191 }), baseRow({ ms: 5002 })], { startedAt: 1000, finishedAt: 3000 })}
        nameHeader="İzleme" nameOf={r => r.monitor.name} columns={[]}
        onClose={() => {}} onCancel={() => {}} />)

    expect(screen.getByText(/(Toplam|Total) 2\.000 s/)).toBeInTheDocument()
  })

  it('Durdur yalnız koşum sürerken çıkar ve onCancel çağırır', () => {
    const onCancel = vi.fn()
    const { rerender } = render(
      <CheckRunShell run={runOf([baseRow()], { done: false, finishedAt: null })}
        nameHeader="İzleme" nameOf={r => r.monitor.name} columns={[]}
        onClose={() => {}} onCancel={onCancel} />)

    const stop = screen.getByText(/Durdur|Stop/)
    fireEvent.click(stop)
    expect(onCancel).toHaveBeenCalled()

    rerender(<CheckRunShell run={runOf([baseRow()])} nameHeader="İzleme"
      nameOf={r => r.monitor.name} columns={[]} onClose={() => {}} onCancel={onCancel} />)
    expect(screen.queryByText(/Durdur|Stop/)).toBeNull()
  })

  it('pencere: ModalShell (role=dialog, başlık), sayaç rozeti x/y; Kapat onClose çağırır', () => {
    const onClose = vi.fn()
    render(<CheckRunShell run={runOf([baseRow()], { total: 3, done: false, finishedAt: null })} nameHeader="İzleme"
      nameOf={r => r.monitor.name} columns={[]} onClose={onClose} onCancel={() => {}} />)
    expect(screen.getByRole('dialog', { name: /kontrol ilerlemesi|check progress/i })).toBeInTheDocument()
    expect(document.querySelector('[data-slot="check-count"]').textContent).toBe('1/3')
    fireEvent.click(screen.getAllByRole('button', { name: /^(kapat|close)$/i }).at(-1))
    expect(onClose).toHaveBeenCalled()
  })

  it('run yoksa HİÇBİR ŞEY çizmez', () => {
    render(
      <CheckRunShell run={null} nameHeader="x" nameOf={() => ''} columns={[]}
        onClose={() => {}} onCancel={() => {}} />)
    expect(screen.queryByRole('dialog')).toBeNull()
  })
})

describe('MonitorCheckRunModal', () => {
  it('izleme adı, hedefi ve türe özgü kolonlar basılır (http)', () => {
    const run = runOf([baseRow({ data: { http_status: 200, response_ms: 191 } })])
    render(<MonitorCheckRunModal run={run} type="http" onClose={() => {}} onCancel={() => {}} />)

    expect(screen.getByText('API')).toBeInTheDocument()
    expect(screen.getByText('https://api.example.com')).toBeInTheDocument()
    expect(screen.getByText('200')).toBeInTheDocument()
    // "191 ms" İKİ kez geçer: türe özel yanıt süresi kolonu ve iskeletin süre kolonu.
    expect(screen.getAllByText('191 ms').length).toBe(2)
  })

  it('gövdesiz satırda (403/429) kolonlar tire basar, patlamaz', () => {
    const run = runOf([baseRow({ ok: false, data: null, error: 'Bu takımın izlemesini çalıştıramazsınız' })])
    render(<MonitorCheckRunModal run={run} type="pagespeed" onClose={() => {}} onCancel={() => {}} />)

    expect(screen.getByText('Bu takımın izlemesini çalıştıramazsınız')).toBeInTheDocument()
    expect(screen.getAllByText('—').length).toBeGreaterThan(0)
  })
})

describe('CheckAllButton', () => {
  it('aday yoksa HİÇ çizilmez — basılamayan gri düğme göstermek yerine yüzey yok', () => {
    const { container } = render(<CheckAllButton count={0} running={false} onClick={() => {}} />)
    expect(container.querySelector('button')).toBeNull()
  })

  it('nötr araç çubuğu sınıfını taşır (mavi DEĞİL) ve sayıyı gösterir', () => {
    render(<CheckAllButton count={12} running={false} onClick={() => {}} />)
    const btn = screen.getByRole('button')
    // Komşusu "Yenile" ile BİREBİR aynı görünüm: shadcn Button outline + sm (birincil mavi DEĞİL)
    expect(btn.getAttribute('data-variant')).toBe('outline')
    expect(btn.getAttribute('data-size')).toBe('sm')
    expect(btn.textContent).toContain('12')
  })

  it('koşarken kilitlenir ve ilerlemeyi etikette gösterir', () => {
    render(<CheckAllButton count={12} running done={3} total={12} onClick={() => {}} />)
    const btn = screen.getByRole('button')
    expect(btn).toBeDisabled()
    expect(btn).toHaveAttribute('aria-busy', 'true')
    expect(btn.textContent).toMatch(/3.*12/)
  })
})
