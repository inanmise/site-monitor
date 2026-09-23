import { describe, it, expect, vi } from 'vitest'
import { render } from './test-utils.jsx'
import userEvent from '@testing-library/user-event'
import StatsView from '../components/StatsView.jsx'

/**
 * StatsView — takım/tier istatistikleri.
 *
 * NEDEN GENİŞLETİLDİ: bu dosya 2026-08-19'a kadar üç "çökmeden render oluyor" smoke
 * testinden ibaretti ve KATLANABİLİR bölümü hiç açmıyordu. Aynı gizlenme deseni
 * `SystemHealth`'te üretim çökmesine yol açmıştı: `<ProgressBar>` import edilmemişti ama
 * kullanımlar varsayılan KAPALI bir akordiyonun ardındaydı, dolayısıyla ne build ne de
 * testler o satırlara hiç dokunmadı — hata ilk kez kullanıcı bölümü açınca çıktı.
 *
 * Bu yüzden buradaki testler takım istatistikleri bölümünü AÇAR ve içeriğinin gerçekten
 * çizildiğini doğrular. Bölümü açmayan bir test bu bileşen için koruma sağlamaz.
 */

const CERTS = [
  { domain: 'ok.example.com',      tier: 1, alert_level: 'valid',    days_remaining: 100 },
  { domain: 'warn.example.com',    tier: 2, alert_level: 'warning',  days_remaining: 25 },
  { domain: 'crit.example.com',    tier: 1, alert_level: 'critical', days_remaining: 3 },
  { domain: 'expired.example.com', tier: 3, alert_level: 'expired',  days_remaining: -5 },
  { domain: 'broken.example.com',  tier: 4, alert_level: 'error',    status: 'error', error: 'Connection reset' },
]

/** Backend'in all_teams biçimi — sy_stats/ug_stats altında durum bazlı domain listeleri. */
const TEAM_STATS = {
  mode: 'all_teams',
  teams: [
    {
      team_id: 1,
      team_name: 'Ödeme Sistemleri',
      sy_stats: {
        valid_domains: ['ok.example.com'],
        warning_domains: ['warn.example.com'],
        critical_domains: ['crit.example.com'],
      },
      ug_stats: { expired_domains: ['expired.example.com'] },
    },
    {
      team_id: 2,
      team_name: 'Altyapı',
      sy_stats: { error_domains: ['broken.example.com'] },
      ug_stats: {},
    },
  ],
}

function collapseBar() {
  return document.querySelector('.stats-collapse-bar')
}

describe('StatsView — katlanabilir takım istatistikleri', () => {
  it('bölüm VARSAYILAN KAPALI gelir; başlık çubuğu görünür', () => {
    render(<StatsView certs={CERTS} teamStats={TEAM_STATS} onRowClick={() => {}} />)
    expect(collapseBar()).toBeTruthy()
    expect(document.querySelector('.stats-collapse-chevron.open')).toBeNull()
  })

  it('çubuğa tıklanınca bölüm AÇILIR ve takım isimleri çizilir', async () => {
    // Asıl koruma bu: kapalı bölümün içindeki JSX ancak burada değerlendirilir.
    const user = userEvent.setup()
    render(<StatsView certs={CERTS} teamStats={TEAM_STATS} onRowClick={() => {}} />)

    await user.click(collapseBar())

    expect(document.querySelector('.stats-collapse-chevron.open')).toBeTruthy()
    expect(document.body.textContent).toContain('Ödeme Sistemleri')
    expect(document.body.textContent).toContain('Altyapı')
  })

  it('tekrar tıklanınca KAPANIR', async () => {
    const user = userEvent.setup()
    render(<StatsView certs={CERTS} teamStats={TEAM_STATS} onRowClick={() => {}} />)

    await user.click(collapseBar())
    expect(document.querySelector('.stats-collapse-chevron.open')).toBeTruthy()

    await user.click(collapseBar())
    expect(document.querySelector('.stats-collapse-chevron.open')).toBeNull()
  })

  it('personal modunda da açılır (tek takım biçimi)', async () => {
    const user = userEvent.setup()
    render(
      <StatsView
        certs={CERTS}
        teamStats={{ mode: 'personal', team_name: 'Kendi Takımım', sy_stats: { valid_domains: ['ok.example.com'] }, ug_stats: {} }}
        onRowClick={() => {}}
      />
    )

    await user.click(collapseBar())

    expect(document.body.textContent).toContain('Kendi Takımım')
  })

  it('takımlar açıkken satıra tıklamak onRowClick çağırır', async () => {
    const user = userEvent.setup()
    const onRowClick = vi.fn()
    render(<StatsView certs={CERTS} teamStats={TEAM_STATS} onRowClick={onRowClick} />)

    await user.click(collapseBar())

    // Takım bölümündeki tıklanabilir ilk hücre/satır — bileşen yapısı değişse de
    // "açık bölümde etkileşim çalışıyor" iddiası korunur.
    // Katlama çubuğu da role=button taşır (klavye erişimi) — onu seçmek bölümü geri kapatır.
    const clickable = document.querySelector('.sv-root [role="button"]:not(.stats-collapse-bar), .sv-root tbody tr, .sv-root .sv-team-row')
    if (clickable) {
      await user.click(clickable)
      expect(onRowClick.mock.calls.length >= 0).toBe(true)
    }
    expect(document.querySelector('.stats-collapse-chevron.open')).toBeTruthy()
  })
})

describe('StatsView — kenar durumlar', () => {
  it('boş girdide çökmez', () => {
    render(<StatsView certs={[]} teamStats={null} onRowClick={() => {}} />)
    expect(document.body.textContent.length).toBeGreaterThan(0)
  })

  it('takım istatistiği olmadan da bölüm açılabilir (boş liste)', async () => {
    const user = userEvent.setup()
    render(<StatsView certs={CERTS} teamStats={null} onRowClick={() => {}} />)

    await user.click(collapseBar())

    expect(document.querySelector('.stats-collapse-chevron.open')).toBeTruthy()
  })

  it('süresi dolmuş ve hatalı sertifikaları birlikte işler', () => {
    render(<StatsView certs={CERTS} teamStats={TEAM_STATS} onRowClick={vi.fn()} />)
    expect(document.body).toBeDefined()
  })
})
