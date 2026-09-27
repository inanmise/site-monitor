import { describe, it, expect, vi } from 'vitest'
import { render, screen, fireEvent, within } from './test-utils.jsx'
import { pressMenuTrigger } from './helpers/dropdownMenu.js'
import CertificateCard from '../components/CertificateCard.jsx'
import { formatDateOnly } from '../api/client'

function makeCert(overrides = {}) {
  return {
    domain: 'test.example.com',
    subject: 'CN=test.example.com',
    issuer_cn: 'Test CA',
    issuer: 'Test CA',
    not_after: '2027-01-01T00:00:00',
    checked_at: '2026-05-01T00:00:00',
    status: 'valid',
    warning: false,
    days_remaining: 60,
    ...overrides,
  }
}

/** Sunucu damgası biçimi (UTC, Z'siz) — sabit tarih YOK (kayan pencere tuzağı), hep şimdiye göre. */
const stamp = (deltaMs) => new Date(Date.now() + deltaMs).toISOString().slice(0, 19)
const DAY = 86400000

describe('CertificateCard', () => {
  it('renders the domain name', () => {
    render(<CertificateCard cert={makeCert()} onClick={() => {}} />)
    expect(screen.getByText('test.example.com')).toBeDefined()
  })

  it('shows Valid badge when days=60 and status=valid', () => {
    render(<CertificateCard cert={makeCert({ days_remaining: 60, status: 'valid' })} onClick={() => {}} />)
    expect(screen.getByText('Valid')).toBeDefined()
  })

  it('shows Warning badge when warning=true and days=35', () => {
    render(<CertificateCard cert={makeCert({ days_remaining: 35, warning: true })} onClick={() => {}} />)
    expect(screen.getByText('Warning')).toBeDefined()
  })

  it('shows CRITICAL badge when days=5 (≤ critDays threshold)', () => {
    render(<CertificateCard cert={makeCert({ days_remaining: 5, status: 'valid' })} onClick={() => {}} />)
    expect(screen.getByText('CRITICAL')).toBeDefined()
  })

  it('shows Error badge when status=error', () => {
    render(<CertificateCard cert={makeCert({ status: 'error', days_remaining: null })} onClick={() => {}} />)
    expect(screen.getByText('Error')).toBeDefined()
  })

  it('shows Expired badge (in words) when days<0 — no longer the generic CRITICAL label', () => {
    render(<CertificateCard cert={makeCert({ days_remaining: -5, status: 'valid' })} onClick={() => {}} />)
    expect(screen.getByText('Expired')).toBeDefined()
    expect(screen.queryByText('CRITICAL')).toBeNull()
  })

  it('calls onClick with domain string when card is clicked', () => {
    const onClick = vi.fn()
    render(<CertificateCard cert={makeCert()} onClick={onClick} />)
    // Stretched-button deseni: kartın açma düğmesi alan adıdır (::after kartın tamamını örter).
    const card = screen.getByText('test.example.com').closest('[data-domain]')
    expect(card.getAttribute('data-slot')).toBe('card')
    fireEvent.click(card.querySelector('[data-cert-open]'))
    expect(onClick).toHaveBeenCalledWith('test.example.com')
    expect(onClick).not.toHaveBeenCalledWith(expect.objectContaining({ domain: expect.anything() }))
  })

  it('renders issuer in the meta line (subject moved to detail modal)', () => {
    render(<CertificateCard cert={makeCert()} onClick={() => {}} />)
    // Issuer remains visible on the card; subject is rendered in the modal only.
    expect(screen.getByText(/Test CA/)).toBeDefined()
  })

  it('renders silent alert badge when hasSilentAlert=true', () => {
    render(<CertificateCard cert={makeCert()} onClick={() => {}} hasSilentAlert={true} />)
    expect(screen.getByText('Alert fired — no notification sent')).toBeDefined()
  })

  it('shows the team name when team_name is present', () => {
    render(<CertificateCard cert={makeCert({ team_name: 'Dijital SY' })} onClick={() => {}} />)
    expect(screen.getByText('Dijital SY')).toBeDefined()
  })

  it('does not render a team line when team_name is absent', () => {
    render(<CertificateCard cert={makeCert()} onClick={() => {}} />)
    expect(screen.queryByText('Dijital SY')).toBeNull()
  })
})

/**
 * Kart aksiyonları (Çalıştır / Düzenle / Kopyala / Sil) — diğer izleme türlerindeki kanonik dörtlü.
 * Butonlar handler VARLIĞINA bağlı (yetkisiz kullanıcıda çizilmez).
 */
describe('CertificateCard — aksiyon butonları', () => {
  const actionBtns = () => [...document.querySelectorAll('[data-slot="cert-card-actions"] button')]

  it('handler geçilmezse hiçbir aksiyon/menü çizilmez; alt çubuk yine son kontrol zamanını taşır (dibe sabit)', () => {
    render(<CertificateCard cert={makeCert()} onClick={() => {}} />)
    expect(actionBtns()).toHaveLength(0)
    expect(document.querySelector('[data-slot="cert-card-more"]')).toBeNull()
    const footer = document.querySelector('[data-slot="card-footer"]')
    expect(footer).not.toBeNull()
    expect(footer.querySelector('[data-slot="cert-checked-at"]')).not.toBeNull()
  })

  it('üç handler geçilince üç buton çıkar ve her biri KENDİ handler\'ını çağırır', () => {
    const onCheckNow = vi.fn(), onEdit = vi.fn(), onDuplicate = vi.fn(), onClick = vi.fn()
    render(<CertificateCard cert={makeCert()} onClick={onClick}
      onCheckNow={onCheckNow} onEdit={onEdit} onDuplicate={onDuplicate} />)

    const [run, edit, dup] = actionBtns()
    expect(actionBtns()).toHaveLength(3)
    fireEvent.click(run);  expect(onCheckNow).toHaveBeenCalledTimes(1)
    fireEvent.click(edit); expect(onEdit).toHaveBeenCalledTimes(1)
    fireEvent.click(dup);  expect(onDuplicate).toHaveBeenCalledTimes(1)
    // Kart detayı AÇILMAMALI — grup stopPropagation yapıyor.
    expect(onClick).not.toHaveBeenCalled()
  })

  it("Sil handlerı geçilince dördüncü buton çıkar ve KART DETAYINI açmaz", () => {
    const onDelete = vi.fn(), onClick = vi.fn()
    render(<CertificateCard cert={makeCert()} onClick={onClick}
      onCheckNow={() => {}} onEdit={() => {}} onDuplicate={() => {}} onDelete={onDelete} />)

    const btns = actionBtns()
    expect(btns).toHaveLength(4)
    fireEvent.click(btns[3])
    expect(onDelete).toHaveBeenCalledTimes(1)
    // Yıkıcı eylemde çift açılış EN pahalı hata: onay diyaloğuyla birlikte detay modali da açılırdı.
    expect(onClick).not.toHaveBeenCalled()
  })

  /**
   * 2026-09-25 (R15): dört eylemin ADI kartı ayırır (alan adı + eylem) — 50 kartlık panoda 50 özdeş
   * "Sil" duyuluyordu. İpucu (title) kısa kalır.
   */
  it('dört eylemin erişilebilir adı alan adını taşır; ipucu kısa kalır', () => {
    render(<CertificateCard cert={makeCert()} onClick={() => {}}
      onCheckNow={() => {}} onEdit={() => {}} onDuplicate={() => {}} onDelete={() => {}} />)
    const btns = actionBtns()
    expect(btns).toHaveLength(4)
    for (const b of btns) {
      expect(b.getAttribute('aria-label')).toMatch(/^test\.example\.com — \S/)
      // İpucu: Çalıştır'da title, diğerlerinde shadcn Tooltip (title yok) — ikisinde de alan adı YOK.
      expect(b.getAttribute('title') ?? '').not.toMatch(/test\.example\.com/)
    }
  })

  it('yetkisi olmayanda (onDelete yok) Sil butonu HİÇ çizilmez', () => {
    render(<CertificateCard cert={makeCert()} onClick={() => {}}
      onCheckNow={() => {}} onEdit={() => {}} onDuplicate={() => {}} />)
    expect(actionBtns()).toHaveLength(3)
  })

  it('deleting=true iken YALNIZ Sil kilitlenir (çift tık koruması)', () => {
    render(<CertificateCard cert={makeCert()} onClick={() => {}} deleting
      onCheckNow={() => {}} onEdit={() => {}} onDuplicate={() => {}} onDelete={() => {}} />)
    const [run, edit, dup, del] = actionBtns()
    expect(del.disabled).toBe(true)
    expect(run.disabled).toBe(false)
    expect(edit.disabled).toBe(false)
    expect(dup.disabled).toBe(false)
  })

  it('checking=true iken Çalıştır devre dışı, diğerleri değil', () => {
    render(<CertificateCard cert={makeCert()} onClick={() => {}} checking
      onCheckNow={() => {}} onEdit={() => {}} onDuplicate={() => {}} />)
    const [run, edit, dup] = actionBtns()
    expect(run.disabled).toBe(true)
    expect(edit.disabled).toBe(false)
    expect(dup.disabled).toBe(false)
  })

  it('stretched button: kart role=button DEĞİL, tek açma düğmesi alan adını taşır; eylem düğmeleri detayı açmaz', () => {
    const onClick = vi.fn(), onEdit = vi.fn()
    render(<CertificateCard cert={makeCert()} onClick={onClick} onEdit={onEdit} />)

    const card = document.querySelector('[data-domain="test.example.com"]')
    // Kartın içinde takım rozeti / eylem düğmeleri var: kart düğme olursa iç içe etkileşimli öğe (axe nested-interactive).
    expect(card.getAttribute('role')).toBeNull()
    expect(card.getAttribute('tabindex')).toBeNull()
    // Sol renk şeridi yok (kullanıcı kuralı 2026-09-26): durum data-status + rozetle taşınır.
    expect(card.getAttribute('data-status')).toBe('valid')
    const open = screen.getByRole('button', { name: /test\.example\.com — (open certificate details|sertifika detayını aç)/ })
    expect(open).toHaveAttribute('data-cert-open', 'true')
    fireEvent.click(open)
    expect(onClick).toHaveBeenCalledWith('test.example.com')

    onClick.mockClear()
    fireEvent.click(actionBtns()[0])
    expect(onEdit).toHaveBeenCalledTimes(1)
    expect(onClick).not.toHaveBeenCalled()
  })

  it('durum rozeti data-status taşır; kritik kart "critical"', () => {
    render(<CertificateCard cert={makeCert({ days_remaining: 5 })} onClick={() => {}} />)
    expect(document.querySelector('[data-domain]').getAttribute('data-status')).toBe('critical')
    expect(document.querySelector('[data-slot="cert-status"]').textContent).toBe('CRITICAL')
  })

  it('yalnız çipler varken ikisi de render edilir (space-between yerleşim bekçisi)', () => {
    render(<CertificateCard cert={makeCert()} onClick={() => {}} hasSilentAlert hasMailFailure />)
    expect(document.querySelectorAll('[data-slot="cert-card-chips"] [data-chip]')).toHaveLength(2)
    expect(actionBtns()).toHaveLength(0)
  })
})

describe('CertificateCard — yakın zamanda yenilendi rozeti (2026-09-12, #6)', () => {
  const base = { domain: 'r.example.com', status: 'valid', alert_level: 'OK', days_remaining: 300, not_after: '2027-07-01T00:00:00', checked_at: '2026-09-12T10:00:00', public_key_algorithm: 'RSA', public_key_size: 2048, signature_algorithm: 'SHA256withRSA' }
  it('not_before 5 gün önce → "5 gün önce yenilendi"; 90 gün önce → rozet yok', () => {
    const ago = (d) => new Date(Date.now() - d * 86400000).toISOString()
    const { container, unmount } = render(<CertificateCard cert={{ ...base, not_before: ago(5) }} isWeak={false} onClick={() => {}} />)
    expect(container.querySelector('[data-slot="cert-renewed"]')).not.toBeNull()
    expect(container.querySelector('[data-slot="cert-renewed"]').textContent).toMatch(/5 gün önce yenilendi|Renewed 5 days ago/)
    unmount()
    const { container: c2 } = render(<CertificateCard cert={{ ...base, not_before: ago(90) }} isWeak={false} onClick={() => {}} />)
    expect(c2.querySelector('[data-slot="cert-renewed"]')).toBeNull()
  })
  it('algoritma çipi başlığı imza algoritmasını da taşır', () => {
    const { container } = render(<CertificateCard cert={{ ...base, not_before: '2026-01-01T00:00:00' }} isWeak={false} onClick={() => {}} />)
    expect(container.querySelector('[data-slot="cert-algo"]').getAttribute('title')).toMatch(/SHA256withRSA/)
  })
})

describe('CertificateCard — platform çipi (2026-09-22)', () => {
  const base = { domain: 'p.example.com', status: 'valid', alert_level: 'OK', days_remaining: 300, not_after: '2027-07-01T00:00:00', checked_at: '2026-09-12T10:00:00', team_id: 5, team_name: 'Takım A' }
  it('kompakt: katalog adı (yoksa kod) çizilir, ayrıntı yalnız tooltip; zengin (extra): ayrıntı metni de görünür; platform yoksa çip yok', () => {
    const cert = { ...base, platform: 'OPENSHIFT', platform_name: 'OpenShift', platform_detail: 'ocp-prod' }
    const { container, unmount } = render(<CertificateCard cert={cert} isWeak={false} onClick={() => {}} />)
    const chip = container.querySelector('[data-slot="cert-platform"]')
    expect(chip.querySelector('[data-slot="cert-platform-name"]').textContent).toBe('OpenShift')
    expect(chip.querySelector('[data-slot="cert-platform-detail"]')).toBeNull()
    expect(chip.getAttribute('title')).toMatch(/ocp-prod/)
    unmount()
    const { container: c2, unmount: u2 } = render(<CertificateCard cert={{ ...cert, platform_name: null }} extra={{}} isWeak={false} onClick={() => {}} />)
    expect(c2.querySelector('[data-slot="cert-platform-name"]').textContent).toBe('OPENSHIFT')
    expect(c2.querySelector('[data-slot="cert-platform-detail"]').textContent).toMatch(/ocp-prod/)
    u2()
    const { container: c3 } = render(<CertificateCard cert={base} isWeak={false} onClick={() => {}} />)
    expect(c3.querySelector('[data-slot="cert-platform"]')).toBeNull()
  })
})

/**
 * 2026-09-27 yeniden tasarım: ton sözlüğü (sunucu `alert_level` hükmü — eşik uydurulmaz), kahraman panel (kalan gün,
 * bitiş tarihi, KALAN geçerlilik çubuğu), güven/zincir gerekçe çipleri, plan çipi, telefon menüsü, alt çubuk.
 */
describe('CertificateCard — yeniden tasarım (2026-09-27)', () => {
  const card = () => document.querySelector('[data-slot="card"][data-domain]')
  const hero = () => document.querySelector('[data-slot="cert-hero"]')

  it.each([
    ['valid', 'Valid', 200],
    ['warning', 'Warning', 25],
    ['high', 'High', 12],
    ['critical', 'CRITICAL', 5],
    ['expired', 'Expired', -12],
    ['error', 'Error', null],
  ])('alert_level=%s → kök/rozet/kahraman aynı tonu taşır, rozet sözcükle "%s"', (level, word, days) => {
    render(<CertificateCard cert={makeCert({ alert_level: level, days_remaining: days, status: level === 'error' ? 'error' : 'valid' })} onClick={() => {}} />)
    expect(card()).toHaveAttribute('data-status', level)
    const badge = document.querySelector('[data-slot="cert-status"]')
    expect(badge).toHaveAttribute('data-status', level)
    expect(badge.textContent).toBe(word)
    expect(hero()).toHaveAttribute('data-tone', level)
  })

  it('sunucu hükmü süreyi ezer: 200 gün kalsa da alert_level=critical ise kart kritik (eşikler sunucuda)', () => {
    render(<CertificateCard cert={makeCert({ alert_level: 'critical', days_remaining: 200 })} onClick={() => {}} />)
    expect(card()).toHaveAttribute('data-status', 'critical')
  })

  it('kahraman: kalan gün + bitiş tarihi + KALAN geçerlilik çubuğu (not_before → not_after) + "90 günden 5"', () => {
    const cert = makeCert({ alert_level: 'critical', days_remaining: 5, not_before: stamp(-85 * DAY), not_after: stamp(5 * DAY) })
    render(<CertificateCard cert={cert} onClick={() => {}} />)
    const h = hero()
    expect(within(h).getByText('5')).toHaveAttribute('data-slot', 'cert-days')
    expect(within(h).getByText('days remaining')).toBeInTheDocument()
    expect(within(h).getByText(formatDateOnly(cert.not_after))).toBeInTheDocument()
    const bar = h.querySelector('[role="progressbar"]')
    expect(bar).not.toBeNull()
    expect(bar).toHaveAttribute('aria-valuenow', '5')      // KALAN gün (geçen değil)
    expect(bar).toHaveAttribute('aria-valuemax', '90')
    expect(h.querySelector('[data-slot="cert-validity"]').textContent).toBe('5 of 90 days left')
  })

  it('dolmuş: gün mutlak değer + "days since expiry" + "Expired on"; çubuk yok. Hata: "—" + "check failed", çubuk yok', () => {
    const { unmount } = render(<CertificateCard cert={makeCert({ alert_level: 'expired', days_remaining: -12, not_before: stamp(-400 * DAY), not_after: stamp(-12 * DAY) })} onClick={() => {}} />)
    expect(within(hero()).getByText('12')).toBeInTheDocument()
    expect(within(hero()).getByText('days since expiry')).toBeInTheDocument()
    expect(within(hero()).getByText('Expired on')).toBeInTheDocument()
    expect(hero().querySelector('[role="progressbar"]')).toBeNull()
    unmount()
    render(<CertificateCard cert={makeCert({ alert_level: 'error', status: 'error', days_remaining: null, error: 'Connection timed out after 10000 ms' })} onClick={() => {}} />)
    expect(within(hero()).getByText('—')).toBeInTheDocument()
    expect(within(hero()).getByText('check failed')).toBeInTheDocument()
    expect(hero().querySelector('[role="progressbar"]')).toBeNull()
    expect(document.querySelector('[data-slot="cert-error"]').textContent).toMatch(/timed out/)
  })

  it('gerekçe çipleri: iptal · güvenilmeyen CA · ad uyuşmazlığı · eksik zincir · zayıf algoritma — sırayla, açıklama dokununca açılır, kart açılmaz', () => {
    const onClick = vi.fn()
    render(<CertificateCard onClick={onClick} isWeak
      cert={makeCert({ revocation_status: 'REVOKED', trust_status: 'UNTRUSTED', security_flags: ['HOSTNAME_MISMATCH'], chain_status: 'INCOMPLETE',
        public_key_algorithm: 'RSA', public_key_size: 1024, signature_algorithm: 'SHA1withRSA' })} />)
    const chips = [...document.querySelectorAll('[data-slot="cert-reason"]')]
    expect(chips.map((c) => c.getAttribute('data-reason'))).toEqual(['revoked', 'untrusted', 'hostname', 'chainIncomplete', 'weak'])
    expect(chips.map((c) => c.textContent)).toEqual(['Revoked', 'Untrusted CA', 'Name mismatch', 'Incomplete chain', 'Weak algorithm'])
    const trigger = screen.getByRole('button', { name: 'test.example.com — Untrusted CA' })
    fireEvent.click(trigger)
    expect(screen.getByRole('tooltip').textContent).toMatch(/self-signed|unknown CA|kendinden imzalı/i)
    expect(onClick).not.toHaveBeenCalled()
    // Zayıf algoritma: anahtar satırı kırmızı + güç işareti
    expect(document.querySelector('[data-slot="cert-algo"]')).toHaveAttribute('data-strength', 'weak')
    expect(document.querySelector('[data-slot="cert-algo"]').textContent).toBe('RSA 1024 · SHA-1')
  })

  it('gerekçe yoksa liste hiç çizilmez; zayıf-algoritma verisi bilinmiyorsa (isWeak undefined) "zayıf" gerekçesi yok', () => {
    render(<CertificateCard cert={makeCert({ public_key_algorithm: 'RSA', public_key_size: 1024 })} onClick={() => {}} />)
    expect(document.querySelector('[data-slot="cert-reasons"]')).toBeNull()
    expect(document.querySelector('[data-slot="cert-algo"]')).toHaveAttribute('data-strength', 'unknown')
  })

  it('e-posta sorunu çipi: tıklama onMailFailureClick çağırır, kartı AÇMAZ; ad alan adını taşır', () => {
    const onClick = vi.fn(), onMail = vi.fn()
    render(<CertificateCard cert={makeCert()} onClick={onClick} hasMailFailure onMailFailureClick={onMail} />)
    fireEvent.click(screen.getByRole('button', { name: 'test.example.com — Mail delivery failing' }))
    expect(onMail).toHaveBeenCalledTimes(1)
    expect(onClick).not.toHaveBeenCalled()
  })

  it('plan çipi: planlı yenileme (live.renewal) kompaktta da görünür, tıklanınca plan penceresi — kart açılmaz', () => {
    const onClick = vi.fn(), onPlan = vi.fn()
    const plan = { planned_at: stamp(4 * DAY).slice(0, 10), by: 'Kişi B', note: 'Değişiklik penceresi', overdue: false, done: false }
    const cert = makeCert({ days_remaining: 12, alert_level: 'high' })
    render(<CertificateCard cert={cert} onClick={onClick} onPlanRenewal={onPlan} live={{ uptime: null, alert: null, renewal: plan }} />)
    const chip = document.querySelector('[data-slot="cert-plan"]')
    expect(chip).toHaveAttribute('data-state', 'planned')
    expect(chip.textContent).toBe(`Renewal planned · ${formatDateOnly(plan.planned_at)}`)
    fireEvent.click(chip)
    expect(onPlan).toHaveBeenCalledWith(cert, plan)
    expect(onClick).not.toHaveBeenCalled()
  })

  it('plan kısayolu: plan verisi YÜKLÜ ve ≤ 30 gün → "Plan renewal"; veri yoksa (bilinmiyor) ya da 200 gün → kısayol yok', () => {
    const onPlan = vi.fn()
    const cert = makeCert({ days_remaining: 20, alert_level: 'warning' })
    const { unmount } = render(<CertificateCard cert={cert} onClick={() => {}} onPlanRenewal={onPlan} live={{ uptime: null, alert: null }} />)
    fireEvent.click(screen.getByRole('button', { name: 'test.example.com — Plan renewal' }))
    expect(onPlan).toHaveBeenCalledWith(cert, null)
    unmount()
    const { unmount: u2 } = render(<CertificateCard cert={cert} onClick={() => {}} onPlanRenewal={onPlan} />)
    expect(document.querySelector('[data-slot="cert-plan"]')).toBeNull()   // plan bilinmiyor → boş form açtırma
    u2()
    render(<CertificateCard cert={makeCert({ days_remaining: 200 })} onClick={() => {}} onPlanRenewal={onPlan} live={{ uptime: null, alert: null }} />)
    expect(document.querySelector('[data-slot="cert-plan"]')).toBeNull()
  })

  it('gecikmiş plan: data-state=overdue; plan eylemi yoksa çip salt bilgi (düğme değil)', () => {
    const plan = { planned_at: stamp(-3 * DAY).slice(0, 10), overdue: true, done: false }
    render(<CertificateCard cert={makeCert({ days_remaining: -2, alert_level: 'expired' })} onClick={() => {}} live={{ renewal: plan }} />)
    const chip = document.querySelector('[data-slot="cert-plan"]')
    expect(chip).toHaveAttribute('data-state', 'overdue')
    expect(chip.closest('button')).toBeNull()
  })

  it('port: 443 dışındaysa alan adının yanında ":8443"; 443 ya da yoksa hiç çizilmez', () => {
    const { unmount } = render(<CertificateCard cert={makeCert({ port: 8443 })} onClick={() => {}} />)
    expect(document.querySelector('[data-slot="cert-port"]').textContent).toBe(':8443')
    unmount()
    render(<CertificateCard cert={makeCert({ port: 443 })} onClick={() => {}} />)
    expect(document.querySelector('[data-slot="cert-port"]')).toBeNull()
  })

  it('iç düğmeler (kopyala, takım, gerekçe) kartın detayını AÇMAZ; yalnız alan adı düğmesi açar', () => {
    const onClick = vi.fn()
    render(<CertificateCard onClick={onClick} cert={makeCert({ team_id: 7, team_name: 'Takım A', chain_status: 'BROKEN' })} />)
    fireEvent.click(screen.getByRole('button', { name: 'test.example.com — Copy domain' }))
    fireEvent.click(screen.getByRole('button', { name: 'test.example.com — Broken chain' }))
    expect(onClick).not.toHaveBeenCalled()
    fireEvent.click(document.querySelector('[data-cert-open]'))
    expect(onClick).toHaveBeenCalledTimes(1)
  })

  it('son kontrol: göreli zaman + ekran okuyucuya kesin zaman; hiç kontrol yoksa açıkça söyler', () => {
    const { unmount } = render(<CertificateCard cert={makeCert({ checked_at: stamp(-12 * 60000) })} onClick={() => {}} />)
    const time = document.querySelector('[data-slot="cert-checked-at"]')
    expect(time.tagName).toBe('TIME')
    expect(time.textContent).toMatch(/^12 min ago \(.+\)$/)
    unmount()
    render(<CertificateCard cert={makeCert({ checked_at: null })} onClick={() => {}} />)
    expect(document.querySelector('[data-slot="cert-checked-at"]')).toHaveAttribute('data-never', 'true')
    expect(screen.getByText('Not checked yet')).toBeInTheDocument()
  })

  it('telefon menüsü ("Diğer işlemler"): Düzenle · Kopyala · Yenileme planla · Sil — seçim kendi işleyicisini çağırır, kart açılmaz', () => {
    const onClick = vi.fn(), onEdit = vi.fn(), onDuplicate = vi.fn(), onDelete = vi.fn(), onPlan = vi.fn()
    const cert = makeCert()
    render(<CertificateCard cert={cert} onClick={onClick} onCheckNow={() => {}} onEdit={onEdit} onDuplicate={onDuplicate}
      onDelete={onDelete} onPlanRenewal={onPlan} />)
    const more = screen.getByRole('button', { name: 'test.example.com — More actions' })
    expect(more.closest('[data-slot="cert-card-more"]')).not.toBeNull()
    pressMenuTrigger(more)
    expect(screen.getAllByRole('menuitem').map((i) => i.textContent)).toEqual(['Edit', 'Duplicate', 'Plan renewal', 'Delete'])
    fireEvent.click(screen.getByRole('menuitem', { name: 'Delete' }))
    expect(onDelete).toHaveBeenCalledTimes(1)
    pressMenuTrigger(more)
    fireEvent.click(screen.getByRole('menuitem', { name: 'Plan renewal' }))
    expect(onPlan).toHaveBeenCalledWith(cert, null)
    expect(onEdit).not.toHaveBeenCalled()
    expect(onClick).not.toHaveBeenCalled()
  })

  it('telefon menüsü: silme sürerken Sil öğesi gizlenir (çift tık koruması)', () => {
    render(<CertificateCard cert={makeCert()} onClick={() => {}} onEdit={() => {}} onDelete={() => {}} deleting />)
    pressMenuTrigger(screen.getByRole('button', { name: 'test.example.com — More actions' }))
    expect(screen.getAllByRole('menuitem').map((i) => i.textContent)).toEqual(['Edit'])
  })
})
