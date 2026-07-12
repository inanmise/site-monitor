import { describe, it, expect } from 'vitest'
import { render } from './test-utils.jsx'
import WeeklyMonitoringStrip from '../components/WeeklyMonitoringStrip.jsx'

const t = (key, ...a) => {
  const m = {
    'wr.monTitle': 'İzleme Göstergeleri', 'wr.monTypeCert': 'Sertifika', 'wr.monTypeHttp': 'HTTP', 'wr.monTypeDomain': 'Alan Adı',
    'wr.monTypePing': 'Ping', 'wr.monTypePort': 'Port', 'wr.monTypeDns': 'DNS', 'wr.monTypeKeyword': 'Keyword',
    'wr.monLblActive': 'İzleme', 'wr.monLblChecks': 'Kontrol', 'wr.monLblOpened': 'Açılan',
    'wr.monLblResolved': 'Çözülen', 'wr.monLblOpen': 'Açık',
    'wr.monExtraExpiring': '≤30 gün', 'wr.monExtraResp': 'Ort. Yanıt', 'wr.monExtraClosed': 'Kapalı Port', 'wr.monExtraChanges': 'Değişim',
    'wr.monTop3': 'En Sorunlu 3', 'wr.monAlarmsShort': `${a[0]} alarm`, 'wr.monNoMonitors': 'İzleme yok',
  }
  return m[key] ?? key
}

// Backend snake_case.
const stats = {
  types: [
    { type: 'cert', active_monitors: 5, total_checks: 100, success_rate: 99.8, alarms_opened: 1, alarms_resolved: 0, alarms_open: 1, success_rate_delta: 0.2, extra: 3, top3: [] },
    { type: 'http', active_monitors: 3, total_checks: 50, success_rate: 96.0, alarms_opened: 4, alarms_resolved: 2, alarms_open: 2, success_rate_delta: -1.5, extra: 180, top3: [{ name: 'a.com', success_rate: 90.0, alarms: 2 }] },
    { type: 'domain', active_monitors: 2, total_checks: 14, success_rate: 98.0, alarms_opened: 0, alarms_resolved: 0, alarms_open: 0, success_rate_delta: null, extra: 1, top3: [] },
    { type: 'port', active_monitors: 0, total_checks: 0, success_rate: null, alarms_opened: 0, alarms_resolved: 0, alarms_open: 0, success_rate_delta: null, extra: 0, top3: [] },
  ],
}

describe('WeeklyMonitoringStrip', () => {
  it('stats null → hiçbir şey render etmez', () => {
    const { container } = render(<WeeklyMonitoringStrip stats={null} t={t} />)
    expect(container.querySelector('.wr-mon')).toBeNull()
  })

  it('kart grid + oran renk eşikleri (99.8 yeşil / 98 amber / 96 kırmızı) + boş kart + delta + top-3', () => {
    const { container, getByText } = render(<WeeklyMonitoringStrip stats={stats} t={t} />)
    expect(getByText('Sertifika')).toBeTruthy()
    expect(container.querySelector('.wr-mon-rate--good')).toBeTruthy()   // cert 99.8
    expect(container.querySelector('.wr-mon-rate--amber')).toBeTruthy()  // domain 98
    expect(container.querySelector('.wr-mon-rate--bad')).toBeTruthy()    // http 96
    expect(getByText('İzleme yok')).toBeTruthy()                          // port aktif 0
    expect(container.querySelector('.wr-mon-delta.down')).toBeTruthy()    // http kötüleşti
    expect(getByText('a.com')).toBeTruthy()                               // http top-3
  })

  it('metrik satırları: büyük oran + hizalı etiket/değer + türe-özgü ekstra (http ms, cert ≤30 gün)', () => {
    const { getByText, getAllByText } = render(<WeeklyMonitoringStrip stats={stats} t={t} />)
    expect(getByText('99.8')).toBeTruthy()                       // cert erişim oranı (büyük sayı)
    expect(getByText('180 ms')).toBeTruthy()                     // http ort. yanıt ekstrası (birimli)
    expect(getByText('Ort. Yanıt')).toBeTruthy()                 // http ekstra etiketi (yalnız http/ping)
    expect(getAllByText('≤30 gün').length).toBeGreaterThan(0)    // cert + domain ekstra etiketi
    expect(getAllByText('İzleme').length).toBeGreaterThan(0)     // aktif-izleme metrik etiketi (kartlarda tekrar)
    expect(getAllByText('Açık').length).toBeGreaterThan(0)       // açık-alarm metrik etiketi
  })

  it('loading + veri yok → yükleniyor göstergesi', () => {
    const { container } = render(<WeeklyMonitoringStrip stats={null} loading={true} t={t} />)
    expect(container.querySelector('.wr-mon--loading')).toBeTruthy()
  })
})
