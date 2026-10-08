import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, fireEvent, act, waitFor } from './test-utils.jsx'
import { Globe } from 'lucide-react'
import { createRef } from 'react'

const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))
// DetailTabs `countsFor` iki hafif istek atar (değişiklik toplamı + not sayısı); gerisi uç çağırmaz.
vi.mock('../api/client', () => ({
  api: withApiFallback({ monitoring: { getChanges: vi.fn(), getMonitorNotes: vi.fn() } }),
  formatDate: (s) => s ?? '', formatDateSec: (s) => s ?? '', formatDateOnly: (s) => s ?? '',
}))
import { api } from '../api/client'
import {
  MonitorCard, MonitorCardHeader, MonitorCardTop, MonitorCardTitle, MonitorStatusBadge, MonitorAlarmIcon, CARD_LAYER,
  MonitorCardContent, MonitorCardFooter, MonitorCardTag, MonitorMetric, CARD_CHECK, CARD_COPY,
} from '../components/monitoring/MonitorCard.jsx'
import { MonitorDetailModal, DetailTabs, DetailSummary, useDeepLinkTab } from '../components/monitoring/MonitorDetail.jsx'
import { MonitorFormModal, FormGrid, FormField, CheckField, FormHint, FormNoTeamAlert } from '../components/monitoring/MonitorForm.jsx'
import MonitorCardActions from '../components/MonitorCardActions.jsx'
import SimpleTooltip from '../components/ui/SimpleTooltip.jsx'
import HintPopover from '../components/ui/HintPopover.jsx'
import { TabsContent } from '@/components/shadcn/tabs'
import { Button } from '@/components/shadcn/button'

/**
 * İzleme sayfalarının ORTAK shadcn kabukları (2026-09-25 geçişi): kart (stretched button), detay
 * penceresi (ModalShell + Tabs) ve düzenleme formu penceresi. Dokuz sayfa bu parçaları paylaşır;
 * sözleşme sayfa testlerine dağılmasın diye burada tek yerde pinlenir.
 */

describe('MonitorCard — stretched button (R18)', () => {
  const draw = (onOpen = vi.fn(), onInner = vi.fn()) => {
    render(
      <MonitorCard status="down" alarm>
        <MonitorCardHeader>
          <MonitorCardTop end={<Button className={CARD_LAYER} onClick={onInner}>iç</Button>}>
            <MonitorStatusBadge status="down">Kapalı</MonitorStatusBadge>
          </MonitorCardTop>
          <MonitorCardTitle onOpen={onOpen} label="a.example.com — detayları aç" title="a.example.com">a.example.com</MonitorCardTitle>
        </MonitorCardHeader>
      </MonitorCard>)
    return { onOpen, onInner }
  }

  it('kart role="button" DEĞİL; başlık GERÇEK bir düğme ve adı satırı taşır', () => {
    const { onOpen } = draw()
    const card = document.querySelector('[data-slot="card"]')
    expect(card).not.toHaveAttribute('role')
    expect(card).not.toHaveAttribute('tabindex')
    const open = screen.getByRole('button', { name: 'a.example.com — detayları aç' })
    expect(open.tagName).toBe('BUTTON')
    fireEvent.click(open)
    expect(onOpen).toHaveBeenCalledTimes(1)
  })

  it('içteki etkileşimli öğeye basmak detayı AÇMAZ (örtünün üstünde, ayrı düğme)', () => {
    const { onOpen, onInner } = draw()
    fireEvent.click(screen.getByRole('button', { name: 'iç' }))
    expect(onInner).toHaveBeenCalledTimes(1)
    expect(onOpen).not.toHaveBeenCalled()
  })

  it('durum/alarm kancaları: data-status + data-alarm; rozet durum tonunu taşır', () => {
    draw()
    const card = document.querySelector('[data-slot="card"]')
    expect(card).toHaveAttribute('data-status', 'down')
    expect(card).toHaveAttribute('data-alarm', 'true')
    expect(document.querySelector('[data-slot="badge"][data-status="down"]').textContent).toContain('Kapalı')
  })

  it('bilinmeyen durum "unknown"a düşer (uydurma ton sınıfı üretilmez)', () => {
    render(<MonitorCard status="bogus"><span>x</span></MonitorCard>)
    expect(document.querySelector('[data-slot="card"]')).toHaveAttribute('data-status', 'unknown')
  })

  it('alarm işareti: alarm yoksa hiç çizilmez; varsa seviye GÖRÜNÜR metin (yalnız-hover ipucu değil) + tam ad ekran okuyucuda', () => {
    const { rerender } = render(<MonitorAlarmIcon monitor={{ active_alarm: false }} label="Aktif alarm" />)
    expect(document.querySelector('[data-slot="monitor-alarm"]')).toBeNull()
    rerender(<MonitorAlarmIcon monitor={{ active_alarm: true, alarm_level: 'CRITICAL' }} label="Aktif alarm — CRITICAL" />)
    const badge = document.querySelector('[data-slot="monitor-alarm"]')
    expect(badge).toHaveAttribute('data-level', 'CRITICAL')
    // Görünür metin seviyenin çevirisi (dokunmatikte de okunur); tam metin sr-only
    expect(badge.querySelector('[aria-hidden="true"]:not(svg)').textContent).toMatch(/^(Kritik|Critical)$/)
    expect(badge.textContent).toContain('Aktif alarm — CRITICAL')
    expect(badge.querySelector('svg')).toHaveClass('animate-pulse')   // onaylanmamış → nabız
    rerender(<MonitorAlarmIcon monitor={{ active_alarm: true, alarm_level: null, alarm_acknowledged: true }} label="Aktif alarm" />)
    expect(document.querySelector('[data-slot="monitor-alarm"] svg')).not.toHaveClass('animate-pulse')
    expect(document.querySelector('[data-slot="monitor-alarm"]').textContent).toMatch(/^Alarm/)
  })
})

describe('MonitorFormModal — emek biriken form penceresi', () => {
  const draw = (props = {}) => {
    const onClose = vi.fn()
    render(
      <MonitorFormModal onClose={onClose} icon={Globe} title="Yeni HTTP izleme"
        footer={<Button onClick={onClose}>İptal</Button>} {...props}>
        <FormGrid>
          <FormField label="Ad">{({ id }) => <input id={id} />}</FormField>
          <CheckField checked={false} onCheckedChange={() => {}} label="Aktif" />
        </FormGrid>
      </MonitorFormModal>)
    return { onClose }
  }

  it('role="dialog" + başlık adı; alt çubuk footer (kaydırılan gövdenin DIŞINDA)', () => {
    draw()
    const dlg = screen.getByRole('dialog', { name: /yeni http izleme/i })
    const footer = dlg.querySelector('[data-slot="dialog-footer"]')
    const body = dlg.querySelector('[data-slot="modal-shell-body"]')
    expect(footer.contains(screen.getByRole('button', { name: 'İptal' }))).toBe(true)
    expect(body.contains(screen.getByRole('button', { name: 'İptal' }))).toBe(false)
    expect(dlg).toHaveAttribute('data-scroll-body', 'true')
  })

  it('Escape ve örtü tıklaması KAPATMAZ (form emeği korunur); X kapatır', () => {
    const { onClose } = draw()
    fireEvent.keyDown(screen.getByRole('dialog'), { key: 'Escape' })
    fireEvent.click(document.querySelector('[data-slot="dialog-overlay"]'))
    expect(onClose).not.toHaveBeenCalled()
    fireEvent.click(screen.getByRole('button', { name: /^(kapat|close)$/i }))
    expect(onClose).toHaveBeenCalledTimes(1)
  })

  it('meşgul evresi başlıkta (şerit); kopya rozeti yalnız kopyalamada', () => {
    draw({ busyLabel: 'Kaydediliyor…', duplicate: true })
    expect(document.querySelector('[data-slot="check-running"]').textContent).toContain('Kaydediliyor…')
    expect(document.querySelector('[data-slot="duplicate-badge"]')).not.toBeNull()
  })

  it('alanlar etiketli: FormField etiketi kontrole bağlı, onay kutusu adını etiketten alır', () => {
    draw()
    expect(screen.getByLabelText('Ad').tagName).toBe('INPUT')
    expect(screen.getByRole('checkbox', { name: 'Aktif' })).toHaveAttribute('aria-checked', 'false')
  })
})

describe('MonitorDetailModal — detay penceresi', () => {
  it('Escape kapatır; sekmeler shadcn Tabs (mousedown ile değişir); özet ipuçlu', () => {
    const onClose = vi.fn()
    const onTab = vi.fn()
    render(
      <MonitorDetailModal onClose={onClose} status="up" title="a.example.com" badge={<span>Çalışıyor</span>}
        actions={<Button onClick={onClose}>Eylemler X</Button>}>
        <DetailSummary items={[{ key: 'ok', value: '99%', label: 'Başarı', hint: 'İpucu' }, false]} />
        <DetailTabs value="a" onValueChange={onTab} tabs={[['a', 'Birinci'], ['b', 'İkinci']]}>
          <TabsContent value="a">A içeriği</TabsContent>
          <TabsContent value="b">B içeriği</TabsContent>
        </DetailTabs>
      </MonitorDetailModal>)
    const dlg = screen.getByRole('dialog', { name: /a\.example\.com/ })
    // Eylem grubu verilince kabuğun kendi X'i yok (MonitorModalActions X'i kullanılır) — iki kapat düğmesi olmasın.
    expect(screen.queryByRole('button', { name: /^(kapat|close)$/i })).toBeNull()
    expect(screen.getByRole('button', { name: 'Eylemler X' })).toBeInTheDocument()
    expect(screen.getByText('A içeriği')).toBeInTheDocument()
    expect(screen.queryByText('B içeriği')).toBeNull()
    fireEvent.mouseDown(screen.getByRole('tab', { name: 'İkinci' }), { button: 0 })
    expect(onTab).toHaveBeenCalledWith('b')
    fireEvent.keyDown(dlg, { key: 'Escape' })
    expect(onClose).toHaveBeenCalledTimes(1)
  })

  // 2026-10-09: sabit yükseklik yalnız sm+'daydı → telefonda pencere sekme içeriğiyle uzayıp kısalıyor, zıplıyordu.
  it('telefonda TAM EKRAN (yükseklik sekmeden bağımsız 100dvh); sm+ sabit yükseklik aynı', () => {
    render(<MonitorDetailModal onClose={() => {}} status="up" title="a.example.com" badge={null}>gövde</MonitorDetailModal>)
    const dlg = screen.getByRole('dialog', { name: 'a.example.com' })
    expect(dlg).toHaveClass('max-sm:h-[100dvh]', 'max-sm:top-0', 'max-sm:translate-y-0', 'max-sm:rounded-none', 'sm:h-[min(88vh,calc(100dvh-2rem))]')
  })

  it('sayfanın kendi telefon sınıfı (Alan adı: yapışkan başlıklı varyant) paylaşılan tam ekranı ezer', () => {
    render(<MonitorDetailModal onClose={() => {}} status="up" title="a.example.com" badge={null}
      className="max-sm:h-dvh max-sm:max-h-dvh max-sm:pt-0">gövde</MonitorDetailModal>)
    const dlg = screen.getByRole('dialog', { name: 'a.example.com' })
    expect(dlg).toHaveClass('max-sm:h-dvh', 'max-sm:max-h-dvh', 'max-sm:pt-0')
    expect(dlg).not.toHaveClass('max-sm:h-[100dvh]')
    expect(dlg).not.toHaveClass('max-sm:max-h-none')
  })

  it('eylem grubu yoksa (Uptime) kabuğun i18n adlı X düğmesi kapatır', () => {
    const onClose = vi.fn()
    render(<MonitorDetailModal onClose={onClose} status="down" title="b.example.com" badge={null}>gövde</MonitorDetailModal>)
    fireEvent.click(screen.getByRole('button', { name: /^(kapat|close)$/i }))
    expect(onClose).toHaveBeenCalledTimes(1)
  })

  it('içinde açılan form penceresi detayın ÜSTÜNDE katmanlanır (iç içe derinlik)', () => {
    render(
      <MonitorDetailModal onClose={() => {}} status="up" title="a.example.com" badge={null}>
        <MonitorFormModal onClose={() => {}} icon={Globe} title="Düzenle" footer={null}>içerik</MonitorFormModal>
      </MonitorDetailModal>)
    const detail = screen.getByRole('dialog', { name: /a\.example\.com/ })
    const form = screen.getByRole('dialog', { name: /düzenle/i })
    expect(Number(form.style.zIndex)).toBeGreaterThan(Number(detail.style.zIndex))
  })

  it('alt başlık yuvası: her genişlikte görünür satır, pencere ADINA karışmaz; özet `key` uyarısı üretmez', () => {
    const errors = vi.spyOn(console, 'error').mockImplementation(() => {})
    render(
      <MonitorDetailModal onClose={() => {}} status="up" title="a.example.com" subtitle="DNS Detayları" badge={null}>
        <DetailSummary items={[{ key: 'ok', value: '99%', label: 'Başarı' }, { key: 'n', value: 3, label: 'Toplam' }]} />
      </MonitorDetailModal>)
    const dlg = screen.getByRole('dialog', { name: 'a.example.com' })
    const sub = dlg.querySelector('[data-slot="monitor-detail-subtitle"]')
    expect(sub.textContent).toBe('DNS Detayları')
    expect(sub.className).not.toMatch(/\bhidden\b/)
    // `key` nesneden ayrıştırılır: prop olarak yayılsaydı React "key is not a prop" uyarısı basardı
    expect(errors.mock.calls.flat().join(' ')).not.toMatch(/`key` is not a prop|key.*spread/i)
    errors.mockRestore()
  })

  // 2026-09-30 (kullanıcı kararı): sekme listesi SARAR, yatay kaydırma ve kenar soluklaşması YOK — 7 sekme dar pencerede
  // satır satır kırılır; pencere genişliği 1140 px'e çıktı (Sentetik detayının 7 sekmesi 960'a sığmıyordu).
  it('sekme listesi SARAR (yatay kaydırma yok, kenar maskesi yok); pencere 1140 px', () => {
    render(
      <MonitorDetailModal onClose={() => {}} status="up" title="a.example.com" badge={null}>
        <DetailTabs value="a" onValueChange={() => {}} tabs={[['a', 'Bir'], ['b', 'İki'], ['c', 'Üç'], ['d', 'Dört'], ['e', 'Beş'], ['f', 'Altı'], ['g', 'Yedi']]}>
          <TabsContent value="a">A</TabsContent>
        </DetailTabs>
      </MonitorDetailModal>)
    const list = screen.getByRole('tablist')
    expect(list).toHaveClass('flex-wrap')
    expect(list).not.toHaveClass('overflow-x-auto')
    expect(list.className).not.toMatch(/mask-image/)
    expect(screen.getAllByRole('tab').every((tab) => tab.classList.contains('flex-none'))).toBe(true)
    expect(document.querySelector('[data-slot="dialog-content"]')?.className || '').toMatch(/1140px/)
  })
})

describe('DetailTabs — ikon + sayaç rozetleri, derin bağlantı sekmesi (2026-09-27)', () => {
  beforeEach(() => { vi.clearAllMocks() })

  it('her sekmede varsayılan ikon; sayaç > 0 rozet basar, 0/undefined basmaz; açık alarm sayacı kırmızı', () => {
    render(
      <MonitorDetailModal onClose={() => {}} status="up" title="a.example.com" badge={null}>
        <DetailTabs value="control" onValueChange={() => {}} counts={{ changes: 3, alerts: 1, notes: 0 }}
          tabs={[['control', 'Kontrol'], ['alerts', 'Alarm'], ['notes', 'Notlar'], ['changes', 'Değişiklikler']]}>
          <TabsContent value="control">A</TabsContent>
        </DetailTabs>
      </MonitorDetailModal>)
    const tab = (name) => screen.getByRole('tab', { name: new RegExp(`^${name}`) })
    expect(tab('Değişiklikler').querySelector('[data-slot="tab-count"]').textContent).toBe('3')
    expect(tab('Değişiklikler')).toHaveAttribute('data-count', '3')
    expect(tab('Alarm').querySelector('[data-slot="tab-count"]')).toHaveAttribute('data-variant', 'destructive')
    expect(tab('Notlar').querySelector('[data-slot="tab-count"]')).toBeNull()
    expect(tab('Kontrol').querySelector('[data-slot="tab-count"]')).toBeNull()
    for (const el of screen.getAllByRole('tab')) expect(el.querySelector('svg')).not.toBeNull()
  })

  it('countsFor: değişiklik toplamı size=1 zarfından, not sayısı not listesinden gelir', async () => {
    api.monitoring.getChanges.mockResolvedValue({ success: true, data: { changes: [], total: 7 } })
    api.monitoring.getMonitorNotes.mockResolvedValue({ success: true, data: { guide: null, notes: [{ id: 1 }, { id: 2 }] } })
    render(
      <MonitorDetailModal onClose={() => {}} status="up" title="a.example.com" badge={null}>
        <DetailTabs value="control" onValueChange={() => {}}
          countsFor={{ kind: 'http', monitorId: 5, notesType: 'HTTP', notesTarget: 'https://a.example.com/', openAlerts: 0 }}
          tabs={[['control', 'Kontrol'], ['alerts', 'Alarm'], ['notes', 'Notlar'], ['changes', 'Değişiklikler']]}>
          <TabsContent value="control">A</TabsContent>
        </DetailTabs>
      </MonitorDetailModal>)
    await waitFor(() => expect(screen.getByRole('tab', { name: /^Değişiklikler/ })).toHaveAttribute('data-count', '7'))
    expect(api.monitoring.getChanges).toHaveBeenCalledWith('http', 5, { page: 0, size: 1 })
    expect(api.monitoring.getMonitorNotes).toHaveBeenCalledWith('HTTP', 'https://a.example.com/')
    expect(screen.getByRole('tab', { name: /^Notlar/ })).toHaveAttribute('data-count', '2')
    expect(screen.getByRole('tab', { name: /^Alarm/ }).querySelector('[data-slot="tab-count"]')).toBeNull()
  })

  it('countsFor: uç hata verirse (yabancı takım 404) sayaç yok, sekme yine çizilir', async () => {
    api.monitoring.getChanges.mockResolvedValue({ success: false, error: 'Kayıt bulunamadı' })
    api.monitoring.getMonitorNotes.mockRejectedValue(new Error('boom'))
    render(
      <MonitorDetailModal onClose={() => {}} status="up" title="a.example.com" badge={null}>
        <DetailTabs value="control" onValueChange={() => {}}
          countsFor={{ kind: 'http', monitorId: 5, notesType: 'HTTP', notesTarget: 'x' }}
          tabs={[['control', 'Kontrol'], ['notes', 'Notlar'], ['changes', 'Değişiklikler']]}>
          <TabsContent value="control">A</TabsContent>
        </DetailTabs>
      </MonitorDetailModal>)
    await waitFor(() => expect(api.monitoring.getMonitorNotes).toHaveBeenCalled())
    await new Promise((r) => setTimeout(r, 20))
    expect(document.querySelector('[data-slot="tab-count"]')).toBeNull()
    expect(screen.getByRole('tab', { name: /^Değişiklikler/ })).toBeInTheDocument()
  })

  it('sekme listesinde olmayan değer (bozuk mtab) ilk sekmeye düşer — boş gövde kalmaz', () => {
    const onTab = vi.fn()
    render(
      <MonitorDetailModal onClose={() => {}} status="up" title="a.example.com" badge={null}>
        <DetailTabs value="bogus" onValueChange={onTab} tabs={[['a', 'Bir'], ['b', 'İki']]}>
          <TabsContent value="a">A</TabsContent>
        </DetailTabs>
      </MonitorDetailModal>)
    expect(onTab).toHaveBeenCalledWith('a')
  })

  it('useDeepLinkTab: URL’deki mtab yalnız İLK açılışta döner, sonraki açılış yedeğe düşer', () => {
    window.history.replaceState({}, '', '/?tab=http&monitor=1&mtab=changes')
    let consume
    function Probe() { consume = useDeepLinkTab(); return null }
    try {
      render(<Probe />)
      expect(consume()).toBe('changes')
      expect(consume()).toBe('control')
    } finally { window.history.replaceState({}, '', '/') }
  })
})

describe('MonitorCard — duraklatılmış kart, mobil ve paylaşılan parçalar (2026-09-26)', () => {
  const paused = (actions) => render(
    <MonitorCard status="up" inactive>
      <MonitorCardHeader>
        <MonitorCardTop><MonitorStatusBadge status="up">Çalışıyor</MonitorStatusBadge></MonitorCardTop>
        <MonitorCardTitle onOpen={() => {}} label="a.example.com — detayları aç">a.example.com</MonitorCardTitle>
      </MonitorCardHeader>
      <MonitorCardContent><span>ölçü</span></MonitorCardContent>
      <MonitorCardFooter actions={actions}>26/09/2026 12:00</MonitorCardFooter>
    </MonitorCard>)

  it('duraklatılmış kart: "Duraklatıldı" rozeti alt çubukta; durum rozeti SON BİLİNEN durumu gri gösterir (yeşil iddia yok)', () => {
    paused()
    const card = document.querySelector('[data-slot="card"]')
    expect(card).toHaveAttribute('data-inactive', 'true')
    const footer = card.querySelector('[data-slot="card-footer"]')
    expect(footer.querySelector('[data-slot="monitor-paused"]').textContent).toMatch(/Duraklatıldı|Paused/)
    const badge = card.querySelector('[data-slot="badge"][data-status="up"]')
    expect(badge).toHaveAttribute('data-paused', 'true')
    expect(badge).toHaveAttribute('data-variant', 'outline')
    expect(badge.querySelector('.animate-pulse')).toBeNull()
  })

  // 2026-10-09: METİN soluklaşmaz (içerik kabı opacity-60'ken bg-muted/30 üstünde etiketler ~2,3:1'e iniyordu) — yalnız
  // ölçü değerleri gri tona, trend çizgisi yarı saydama iner; başlık/içerik kaplarına opaklık YOK.
  it('duraklatılmış kart: metin soluk DEĞİL, yalnız değerler gri + trend çizgisi soluk; alt çubuk (Sürdür) birincil', () => {
    const onResume = vi.fn()
    paused(<MonitorCardActions onResume={onResume} rowLabel="a.example.com" onCheck={() => {}} onEdit={() => {}} onDuplicate={() => {}} checkTitle="Kontrol" editTitle="Düzenle" />)
    const card = document.querySelector('[data-slot="card"]')
    expect(card.className).toMatch(/\[&_\[data-slot\$=-value\]\]:text-muted-foreground/)
    expect(card.className).toMatch(/\[&_svg\.spark\]:opacity-60/)
    expect(card.className).toMatch(/\[&_svg\.spark\]:pointer-events-none/)   // çizgi dokunuşu örtüye bırakır
    expect(card.className).not.toMatch(/card-content\]\]:opacity|card-header\]\]:opacity/)
    expect(card.className).toMatch(/border-dashed/)
    expect(card.className).not.toMatch(/(^|\s)opacity-/)            // kartın KENDİSİ soluk değil
    const btn = screen.getByRole('button', { name: /^a\.example\.com — (Sürdür|Resume)$/ })
    expect(btn).toHaveAttribute('data-variant', 'default')
    expect(btn.closest('[data-slot="card-footer"]')).not.toBeNull()   // soluk kapların DIŞINDA
    expect(btn.className).not.toMatch(/(^|\s)opacity-\d/)            // koşulsuz soluklaşma yok (yalnız disabled:)
    fireEvent.click(btn)
    expect(onResume).toHaveBeenCalledTimes(1)
  })

  it('etkin kartta Sürdür YOK; zaman damgası saat simgesi + "Son kontrol:" önekiyle', () => {
    render(
      <MonitorCard status="up">
        <MonitorCardFooter actions={<MonitorCardActions onResume={() => {}} onCheck={() => {}} onEdit={() => {}} onDuplicate={() => {}} checkTitle="Kontrol" editTitle="Düzenle" />}>
          26/09/2026 12:00
        </MonitorCardFooter>
      </MonitorCard>)
    expect(screen.queryByRole('button', { name: /Sürdür|Resume/ })).toBeNull()
    expect(document.querySelector('[data-slot="monitor-paused"]')).toBeNull()
    const time = document.querySelector('[data-slot="monitor-card-time"]')
    expect(time.textContent).toMatch(/^(Son kontrol:|Last check:)\s*26\/09\/2026 12:00$/)
    expect(time.querySelector('svg')).not.toBeNull()
  })

  it('mobil: üst satır ve alt çubuk SARAR; dokunmatikte kopyala/kutu/eylem düğmeleri 40 px dokunma alanı', () => {
    render(
      <MonitorCard status="up">
        <MonitorCardHeader><MonitorCardTop end={<span>GET</span>}><span>rozet</span></MonitorCardTop></MonitorCardHeader>
        <MonitorCardFooter actions={<MonitorCardActions onCheck={() => {}} onEdit={() => {}} onDuplicate={() => {}} checkTitle="Kontrol" editTitle="Düzenle" />}>x</MonitorCardFooter>
      </MonitorCard>)
    expect(screen.getByText('rozet').parentElement).toHaveClass('flex-wrap')
    expect(document.querySelector('[data-slot="card-footer"]')).toHaveClass('flex-wrap')
    expect(CARD_COPY).toMatch(/pointer-coarse:size-10/)
    expect(CARD_COPY).toMatch(/pointer-coarse:opacity-100/)          // hover yok → soluk kalmaz
    expect(CARD_CHECK).toMatch(/pointer-coarse:after:-inset-3/)      // 16 + 2×12 = 40 px
    const acts = [...document.querySelectorAll('[data-slot="monitor-card-actions"] [data-slot="button"]')]
    expect(acts.length).toBe(3)
    for (const b of acts) expect(b.className).toMatch(/pointer-coarse:size-10/)
  })

  it('MonitorCardTag ref alır ve ek öznitelikleri iletir (asChild tetik olabilir)', () => {
    const ref = createRef()
    render(<MonitorCardTag ref={ref} data-testid="tag" title="Yöntem">GET</MonitorCardTag>)
    expect(ref.current).toBe(screen.getByTestId('tag'))
    expect(ref.current).toHaveAttribute('title', 'Yöntem')
  })

  it('MonitorMetric `hint`: ipucu odakta açılır; ölçü örtünün üstünde ve odaklanabilir', () => {
    render(<MonitorMetric value="120ms" label="Yanıt" hint="Son kontrolün süresi" />)
    const metric = document.querySelector('[data-slot="monitor-metric"]')
    expect(metric).toHaveClass('z-10')
    expect(metric).toHaveAttribute('tabindex', '0')
    act(() => { metric.focus() })
    expect(screen.getByRole('tooltip').textContent).toBe('Son kontrolün süresi')
  })

  // 2026-10-09: Tooltip dokunmatikte açılmaz → `pointer: coarse`'da ölçü dokun-gör HintPopover tetiği; etiket ≥ 11 px.
  describe('dokunmatik işaretçi (pointer: coarse)', () => {
    const orig = window.matchMedia
    beforeEach(() => {
      window.matchMedia = (q) => ({ ...orig(q), matches: q === '(pointer: coarse)' })
    })
    afterEach(() => { window.matchMedia = orig })

    it('MonitorMetric: dokunuş ipucunu açar (hover gerekmez); ölçü örtünün üstünde, değer yuvası korunur', () => {
      render(<MonitorMetric value="120ms" label="Yanıt" hint="Son kontrolün süresi" />)
      const metric = document.querySelector('[data-slot="monitor-metric"]')
      expect(metric.tagName).toBe('BUTTON')
      expect(metric).toHaveClass('z-10')
      expect(metric.querySelector('[data-slot="monitor-metric-value"]').textContent).toBe('120ms')
      expect(screen.queryByRole('tooltip')).toBeNull()
      fireEvent.click(metric)
      expect(screen.getByRole('tooltip').textContent).toBe('Son kontrolün süresi')
    })

    it('DetailMetric (detay özeti): dokunuş ipucunu açar', () => {
      render(<DetailSummary items={[{ key: 'ok', value: '99%', label: 'Başarı', hint: 'Son 24 saat' }]} />)
      const trigger = document.querySelector('[data-slot="detail-metric"]')
      expect(trigger.tagName).toBe('BUTTON')
      fireEvent.click(trigger)
      expect(screen.getByRole('tooltip').textContent).toBe('Son 24 saat')
    })

    it('ipucu yoksa düz ölçü (düğme yok)', () => {
      render(<MonitorMetric value="3" label="Toplam" />)
      expect(document.querySelector('[data-slot="monitor-metric"]').tagName).toBe('DIV')
      expect(screen.queryByRole('button')).toBeNull()
    })
  })

  it('ölçü etiketi en az 11 px (telefonda 12 px); alt çubuk yazısı 11 px (10/10,5 px değil)', () => {
    render(
      <MonitorCard status="up">
        <MonitorCardContent><MonitorMetric value="3" label="Toplam" /></MonitorCardContent>
        <MonitorCardFooter>26/09/2026 12:00</MonitorCardFooter>
      </MonitorCard>)
    expect(screen.getByText('Toplam')).toHaveClass('text-[11px]', 'max-sm:text-xs')
    const footer = document.querySelector('[data-slot="card-footer"]')
    expect(footer).toHaveClass('text-[11px]', 'max-sm:text-xs')
    expect(footer.className).not.toMatch(/text-\[10/)
  })

  // 2026-10-09: 50 kartlık ızgarada sağlıklı kartların noktası da nabız atıyordu — yalnız sorunda.
  it('durum noktası YALNIZ sorunda (down) nabız atar; çalışan/uyarı/bilinmeyen sabit', () => {
    const dot = (status) => {
      const { unmount } = render(<MonitorStatusBadge status={status}>{status}</MonitorStatusBadge>)
      const pulsing = !!document.querySelector(`[data-slot="badge"][data-status="${status}"] .animate-pulse`)
      unmount()
      return pulsing
    }
    expect(dot('down')).toBe(true)
    expect(dot('up')).toBe(false)
    expect(dot('warn')).toBe(false)
    expect(dot('unknown')).toBe(false)
  })

  it('HintPopover: DOKUN-GÖR — tık/dokunuş açar (hover gerekmez), açıklama tetiğe bağlı; içerik yoksa çocuk olduğu gibi', () => {
    const { rerender } = render(<HintPopover content="Tam sebep metni"><span>ROZET</span></HintPopover>)
    const trigger = screen.getByRole('button', { name: 'ROZET' })
    expect(screen.queryByRole('tooltip')).toBeNull()
    fireEvent.click(trigger)
    const tip = screen.getByRole('tooltip')
    expect(tip.textContent).toBe('Tam sebep metni')
    expect(trigger).toHaveAttribute('aria-describedby', tip.id)
    rerender(<HintPopover content={null}><span>ROZET</span></HintPopover>)
    expect(screen.queryByRole('button', { name: 'ROZET' })).toBeNull()
  })

  it('SimpleTooltip çocuğun kendi data-slot kancasını EZMEZ', () => {
    render(<SimpleTooltip content="İpucu"><Button>Düğme</Button></SimpleTooltip>)
    expect(screen.getByRole('button', { name: 'Düğme' })).toHaveAttribute('data-slot', 'button')
  })
})

describe('MonitorForm — uyarı tonu ve takımsız kullanıcı', () => {
  it('FormHint tone="warn" okunur amber-700 (koyu temada amber-300) — #f59e0b beyazda 2.1:1 idi', () => {
    render(<FormHint tone="warn">Dikkat</FormHint>)
    const p = screen.getByText('Dikkat')
    expect(p).toHaveClass('text-amber-700', 'dark:text-amber-300')
    expect(p).not.toHaveClass('text-warning')
  })

  it('FormNoTeamAlert: uyarı tonunda başlık + yöneticiye başvur metni', () => {
    render(<FormNoTeamAlert />)
    const alert = document.querySelector('[data-slot="alert"][data-tone="warning"]')
    expect(alert.textContent).toMatch(/Takımınız yok|No team/)
    expect(alert.textContent).toMatch(/yöneticinize başvurun|contact your administrator/)
  })
})
