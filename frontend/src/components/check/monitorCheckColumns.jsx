import { fmtDay, daysClass, httpClass } from './CheckRunShell.jsx'
import { formatBytes } from '../../utils/formatBytes.js'

/**
 * Toplu koşumda tür başına eşzamanlılık tavanı.
 *
 * <p>Altısı için 6 — sertifika akışının tavanıyla aynı; tek pod aynı anda başka kullanıcılara da
 * hizmet ediyor, sınırsız fan-out bilinçli olarak yapılmıyor.
 *
 * <p>Üçü DAHA DÜŞÜK ve sayılar keyfi değil:
 * <ul>
 *   <li>{@code scripted: 2} — sunucudaki k6 süreç havuzu {@code site.monitor.scripted.pool-size}
 *       ve varsayılanı 2. Altı ile koşulursa fazlası {@code skipped} dönerdi; kullanıcı
 *       monitörlerini bozuk sanardı. Tavan havuzu AŞMAMALI.</li>
 *   <li>{@code page/pagespeed: 3} — tek kontrol ana sayfayı ve onlarca alt kaynağı çekiyor;
 *       altı paralel koşum tek pod'un giden bağlantılarını doldurur.</li>
 * </ul>
 */
export const CHECK_CONCURRENCY_BY_TYPE = {
  http: 6, domain: 6, port: 6, dns: 6, keyword: 6, ping: 6,
  page: 3, pagespeed: 3, scripted: 2,
}

const num = (v) => (v == null ? '—' : v)
const ms = (v) => (v == null ? '—' : `${v} ms`)

/**
 * İzleme türü → koşum tablosunun hedef sütunu ve türe özgü kolonları.
 *
 * <p><b>Neden tür başına adaptör:</b> dokuz türün sonuç gövdesi ORTAK bir şema taşımıyor.
 * {@code enrichDns} hiç {@code status} yazmıyor; yazanların sözlükleri de ayrı
 * ({@code up/down}, {@code open/closed}, {@code OK/DEGRADED}, {@code PASS/FAIL}). Tek bir
 * "durum" kolonu ya boş kalırdı ya da ham İngilizce enum'ları Türkçe arayüze basardı.
 * Türler arası TEK ortak sinyal satırın ✓/✕ tik'idir; gerisi buradan gelir.
 *
 * <p>Her {@code render} {@code data === null} iken de çalışmak ZORUNDA: 403/429/ağ hatası
 * satırlarında gövde yoktur ve tabloyu çizen döngü o satırda patlayamaz.
 *
 * <p>Kolon sayısı tür başına en fazla üç: modal {@code max-width: 92vw} ile sınırlı,
 * dördüncü kolon sertifika tablosundan geniş bir yüzey yaratır.
 *
 * @param {string} type  kanonik izleme türü (http, domain, port, dns, keyword, ping, page, pagespeed, scripted)
 * @param {Function} t   çeviri fonksiyonu
 * @returns {{targetOf: Function, columns: Array}}
 */
export function monitorCheckColumns(type, t) {
  const httpCol = {
    key: 'http', label: t('app.checkColHttp'),
    tdClassName: r => `chk-mono ${httpClass((r.data || {}).http_status ?? null)}`,
    render: r => num((r.data || {}).http_status),
  }
  const msCol = {
    key: 'ms', label: t('mon.checkColMs'),
    render: r => ms((r.data || {}).response_ms),
  }

  switch (type) {
    case 'http':
      return { targetOf: m => m?.url || '', columns: [httpCol, msCol] }

    case 'keyword':
      return {
        targetOf: m => m?.url || '',
        columns: [
          { key: 'found', label: t('mon.checkColFound'), render: r => {
            const d = r.data || {}
            if (d.found == null) return '—'
            return d.found ? `✓${d.occurrences != null ? ` (${d.occurrences})` : ''}` : '✕'
          } },
          httpCol, msCol,
        ],
      }

    case 'port':
      return {
        targetOf: m => (m?.host ? `${m.host}:${m.port ?? ''}`.replace(/:$/, '') : ''),
        columns: [
          { key: 'state', label: t('mon.checkColState'), render: r => {
            const s = (r.data || {}).status
            return s === 'open' ? t('mon.portOpen') : s === 'closed' ? t('mon.portClosed') : '—'
          } },
          msCol,
        ],
      }

    case 'dns':
      return {
        targetOf: m => (m?.domain ? `${m.domain} · ${m.record_type || ''}`.trim().replace(/ ·$/, '') : ''),
        columns: [
          // Yalnız İLK satır: bir A kaydı onlarca IP dönebilir ve tablo satırı sürüklerdi.
          { key: 'value', label: t('mon.checkColValue'), tdClassName: 'chk-td-target',
            render: r => String((r.data || {}).value || '').split('\n')[0] || '—' },
          { key: 'changed', label: t('mon.checkColChanged'), render: r => {
            const d = r.data || {}
            return d.changed ? t('mon.changedYes') : d.rotated ? t('mon.rotated') : '—'
          } },
          msCol,
        ],
      }

    case 'ping':
      return {
        targetOf: m => m?.host || '',
        columns: [
          { key: 'rtt', label: t('mon.checkColRtt'), render: r => num((r.data || {}).rtt_ms) },
          { key: 'loss', label: t('mon.checkColLoss'), render: r => {
            const v = (r.data || {}).packet_loss
            return v == null ? '—' : `%${v}`
          } },
        ],
      }

    case 'domain':
      return {
        targetOf: m => m?.domain || '',
        columns: [
          { key: 'days', label: t('app.checkColDays'),
            tdClassName: r => `chk-mono chk-days ${daysClass((r.data || {}).days_remaining ?? null)}`,
            render: r => {
              const days = (r.data || {}).days_remaining ?? null
              return days == null ? '—' : t('app.checkDaysUnit', days)
            } },
          // `expiry_date` — sertifikadaki `not_after` DEĞİL: alan adı izlemesi kendi alanını yazıyor.
          { key: 'expiry', label: t('app.checkColExpiry'), render: r => fmtDay((r.data || {}).expiry_date) },
        ],
      }

    case 'page':
      return {
        targetOf: m => m?.url || '',
        columns: [
          httpCol,
          { key: 'broken', label: t('mon.checkColBroken'), render: r => {
            const d = r.data || {}
            return d.broken_resources == null ? '—' : `${d.broken_resources}/${d.total_resources ?? '?'}`
          } },
          msCol,
        ],
      }

    case 'pagespeed':
      return {
        targetOf: m => m?.url || '',
        columns: [
          { key: 'ttfb', label: t('mon.checkColTtfb'), render: r => num((r.data || {}).ttfb_ms) },
          msCol,
          { key: 'bytes', label: t('mon.checkColBytes'), render: r => {
            const d = r.data || {}
            return d.total_bytes == null ? '—' : formatBytes(d.total_bytes, !!d.bytes_truncated)
          } },
        ],
      }

    case 'scripted':
      return {
        // Senaryo izlemesinin URL alanı yok — hedefi script taşıyor; grup adı en anlamlı ayraç.
        targetOf: m => m?.group_name || '',
        columns: [
          { key: 'checks', label: t('mon.checkColChecks'), render: r => {
            const d = r.data || {}
            if (d.queued) return t('mon.checkQueued')
            if (d.checks_passed == null && d.checks_failed == null) return '—'
            return `${d.checks_passed ?? 0}✓ / ${d.checks_failed ?? 0}✕`
          } },
          { key: 'duration', label: t('mon.checkColDuration'), render: r => ms((r.data || {}).duration_ms) },
        ],
      }

    default:
      return { targetOf: m => m?.url || m?.host || m?.domain || '', columns: [msCol] }
  }
}
