/** Kanal teknik hata raporu test verisi (2026-10-13) — sunucu yanıtlarının şekli (TechErrorReportController). */

export const TEMPLATE = {
  v: 1,
  lang: 'tr',
  title: '{kanal} Kanalı Teknik Hata Mesajları - {ay} {yil}',
  sections: [
    { id: 'summary', kind: 'metrics', title: '{kanal} Kanalı — {ay} {yil} Özeti', fields: [
      { id: 'error-rate', label: 'Teknik hata oranı', type: 'percent', hint: 'İlgili ay/yıl verilerine göre' },
      { id: 'total-tx', label: 'Toplam işlem adedi', type: 'number' },
      { id: 'sla-above', label: 'SLA hedefin üstünde mi?', type: 'yesno' },
    ] },
    { id: 'issues', kind: 'issues', title: 'Teknik hataların anomali olarak arttığı konular', item_title: '{konu} Konusu ile yaşanan problem',
      description_label: 'Ne zaman, nerede, nasıl gerçekleşti?', customers_label: 'Etkilenen müşteri sayısı',
      errors_label: 'Bu sorundan kaynaklı teknik hata adedi' },
    { id: 'trend-note', kind: 'text', title: 'Grafikler hakkında', text: 'Yıllık trend ve detay grafikleri aşağıda paylaşılmıştır.' },
    { id: 'charts', kind: 'images', title: 'Grafikler', slots: [
      { id: 'monthly-tx', label: '{kanal} Kanalı Ay Bazlı Toplam İşlem Adetleri', width: 'half' },
      { id: 'tech-errors', label: '{kanal} Kanalı Teknik Hata Grafiği', width: 'half' },
      { id: 'daily-errors', label: '{kanal} Kanalı Teknik Hata (Günlük)', width: 'full' },
    ] },
  ],
}

export const TEAMS = [
  { id: 1, name: 'Dijital Kanallar Uygulama Geliştirme (Bireysel / Mobil)', active: true, has_email: true, can_edit: true, can_manage: false },
  { id: 2, name: 'Kurumsal Kanallar', active: true, has_email: false, can_edit: false, can_manage: false },
]

export function channel(id, name, extra = {}) {
  return {
    id, team_id: 1, team_name: TEAMS[0].name, name, description: null, active: true, sort_order: null, template_version: 3,
    template_updated_at: '2026-10-01T08:00:00Z', lang: 'tr', section_count: 4, field_count: 3, slot_count: 3,
    can_edit: true, can_delete: false, report_count: 2, period_report: null, ...extra,
  }
}

export function overview(channels = [channel(10, 'Mobil'), channel(20, 'POS')]) {
  return { success: true, data: { period: { year: 2026, month: 9 }, teams: TEAMS, channels, can_create: true } }
}

export function templateResponse(ch, version = 3, extra = {}) {
  return { success: true, data: { channel: ch, template: TEMPLATE, version, ...extra } }
}

export function reportResponse(extra = {}) {
  return {
    success: true,
    data: {
      report: {
        id: 100, channel_id: 10, team_id: 1, year: 2026, month: 9, status: 'draft', send_state: null, last_send_status: null,
        updated_at: '2026-10-02T07:00:00Z', last_sent_at: null, version: 4, title: 'Mobil Kanalı Teknik Hata Mesajları - Eylül 2026',
        updated_by_name: 'Ayşe Yılmaz', file_name: 'mobil-teknik-hata-2026-09.pdf',
      },
      channel: channel(10, 'Mobil'),
      template: TEMPLATE,
      values: { fields: { 'error-rate': 0.42 }, issues: {}, texts: {} },
      images: [],
      previous: { id: 90, year: 2026, month: 8, fields: { 'error-rate': 0.5, 'total-tx': 1000000 } },
      can_edit: true,
      can_delete: true,
      team_email_set: true,
      template_update: { differs: false, lost_values: 0, lost_images: 0 },
      limits: { image_max_bytes: 5242880, report_image_max_bytes: 41943040 },
      ...extra,
    },
  }
}
