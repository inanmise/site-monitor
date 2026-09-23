import { useCallback, useEffect, useMemo, useState } from 'react'
import { Search, Copy, RotateCcw } from 'lucide-react'
import { useDialog } from '../ui/Dialog.jsx'
import { useToast } from '../ui/Toast.jsx'
import { api, formatDateSec } from '../../api/client'
import VersionTimeline from '../scripted/VersionTimeline.jsx'
import StatusBlock from '../ui/StatusBlock.jsx'
import AlertBanner from '../ui/AlertBanner.jsx'
import PaginationBar from '../ui/PaginationBar.jsx'
import { LoadingBlock } from '../ui/Progress.jsx'
import ChangeDiffChips from './ChangeDiffChips.jsx'
import { fieldLabel, formatValue, parseSnapshot, shortUserAgent } from './changeFields.js'
import { copyText } from '../../utils/copyText.js'

/**
 * Bir izlemenin YAPILANDIRMA geçmişi — "kim, ne zaman, hangi IP'den, neyi değiştirdi".
 *
 * <p>{@code CheckHistoryTab}'ın kardeşi ve onunla KARIŞTIRILMAMALI: orası kontrol SONUÇLARINI
 * (hedef ayakta mıydı) gösterir, burası ayarların kendisinin nasıl değiştiğini. Sentetik izlemede
 * ayrıca bir "Sürümler" sekmesi var; o da script GÖVDESİNİN sürümleri — üçü farklı sorulara cevap
 * verir ve ayrı durmaları bilinçlidir.
 *
 * <p>Zaman çizelgesi {@code VersionTimeline} ile çizilir (saf sunum bileşeni, sürüm geçmişiyle
 * ortak görsel dil). İlk kayıt (seq 0) seçildiğinde snapshot "ilk değerler" paneli olarak açılır.
 */
export default function ChangeHistoryTab({ t, kind, monitorId, teamNames = {}, canManage = false }) {
  const [rows, setRows] = useState(null)
  const [total, setTotal] = useState(0)
  const [page, setPage] = useState(0)
  const [size, setSize] = useState(25)
  const [sel, setSel] = useState(null)
  const [detail, setDetail] = useState(null)
  const [error, setError] = useState(null)
  const [restoring, setRestoring] = useState(false)
  const { showNoteConfirm } = useDialog()
  const toast = useToast()

  const load = useCallback(async () => {
    setRows(null)
    try {
      const res = await api.monitoring.getChanges(kind, monitorId, { page, size })
      if (res?.success) {
        setRows(res.data?.changes || [])
        setTotal(res.data?.total || 0)
        setError(null)
      } else {
        setRows([])
        setError(res?.error || t('chg.loadError'))
      }
    } catch (e) {
      setRows([])
      setError(e?.message || String(e))
    }
  }, [kind, monitorId, page, size, t])

  useEffect(() => { load() }, [load])

  // Seçilen olayın TAM detayı (snapshot) ayrı uçtan gelir — liste yanıtı onu taşımaz.
  useEffect(() => {
    if (!sel) { setDetail(null); return }
    let alive = true
    api.monitoring.getChangeDetail(kind, monitorId, sel.seq).then(r => {
      if (alive) setDetail(r?.success ? r.data : null)
    }).catch(() => { if (alive) setDetail(null) })
    return () => { alive = false }
  }, [kind, monitorId, sel])

  /**
   * Seçili anın ayarlarına geri döner. Onay ZORUNLU ve gerekçe notu istenir: bu bir yazma
   * işlemi ve geçmişte "kim neden geri aldı" satırı olarak duracak.
   */
  async function restore() {
    if (!sel) return
    const res = await showNoteConfirm({
      title: t('chg.restoreTitle'),
      message: t('chg.restoreConfirm', sel.seq),
      confirmText: t('chg.restoreAction'),
      noteLabel: t('chg.changeNote'),
    })
    if (!res?.confirmed) return
    setRestoring(true)
    try {
      const r = await api.monitoring.restoreChange(kind, monitorId, sel.seq, res.note)
      if (!r?.success) { toast.error(r?.error || t('chg.restoreError')); return }
      const skipped = r.data?.skipped_masked || []
      toast.success(t('chg.restoreDone', (r.data?.fields || []).length))
      // Atlanan gizli alanlar SESSİZ geçilmez: kullanıcı parolanın dönmediğini bilmeli.
      if (skipped.length) toast.info(t('chg.restoreMasked', skipped.length))
      setSel(null)
      load()
    } finally {
      setRestoring(false)
    }
  }

  const eventLabel = (ev) => {
    const key = `chg.event${ev}`
    const label = t(key)
    return label === key ? ev : label
  }

  /** Satırın sağına aktör + zaman + IP künyesi. Timeline'ın kendi meta satırı ADI gösterir. */
  const renderExtra = (row) => (
    <>
      {row.ip_address && (
        <span className="chg-ip" title={t('chg.ipTitle')}
          role="button" tabIndex={0} aria-label={`${row.ip_address} — ${t('chg.ipTitle')}`}
          onKeyDown={(e) => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); e.stopPropagation(); copyText(row.ip_address) } }}
          onClick={(e) => { e.stopPropagation(); copyText(row.ip_address) }}>
          {row.ip_address}<Copy size={10} aria-hidden="true" />
        </span>
      )}
      {row.user_agent && (
        <span className="chg-ua" title={row.user_agent}>{shortUserAgent(row.user_agent)}</span>
      )}
    </>
  )

  const timelineRows = useMemo(() => (rows || []).map(r => ({
    id: `${r.kind}-${r.seq}`,
    version: String(r.seq),
    event_type: r.event_type,
    created_at: r.at,
    created_by: r.actor,
    note: r.note,
    current: false,
    _raw: r,
  })), [rows])

  if (rows === null) return <LoadingBlock label={t('modal.loading')} />

  return (
    <div className="chg-tab">
      {error && <AlertBanner tone="danger" title={t('chg.loadError')}>{error}</AlertBanner>}

      {rows.length === 0 ? (
        <StatusBlock tone="neutral" icon={Search} title={t('chg.emptyTitle')}
          description={t('chg.emptyText')} />
      ) : (<>
        <VersionTimeline
          rows={timelineRows}
          selId={sel ? `${sel.kind}-${sel.seq}` : null}
          currentLabel={t('chg.initial')}
          eventLabel={eventLabel}
          renderExtra={(row) => renderExtra(row._raw)}
          onPick={(row) => setSel(prev => (prev && prev.seq === row._raw.seq ? null : row._raw))} />

        {sel && (
          <div className="sc-ver-preview chg-detail">
            <div className="sc-ver-preview-head">
              <span className="sc-ver-preview-id">
                <span className="sc-ver-chip sc-ver-chip--cell">#{sel.seq}</span>
                <span className="sc-ver-vs">{eventLabel(sel.event_type)} · {formatDateSec(sel.at)}</span>
              </span>
              {/* Geri döndürme yalnız YÖNETEBİLENE ve durum kaydı olan olaylarda çıkar —
                  düğmenin görünüp 403 vermesi kullanıcıyı boşuna umutlandırırdı. */}
              {canManage && detail?.snapshot && (
                <button type="button" className="btn btn-secondary btn-sm chg-restore-btn"
                  disabled={restoring} onClick={restore}>
                  <RotateCcw size={13} /> {restoring ? t('chg.restoring') : t('chg.restoreAction')}
                </button>
              )}
            </div>
            <div className="sc-ver-preview-body">
              {sel.note && <p className="chg-note">{sel.note}</p>}

              {/* Değişen alanlar — tam liste (konsolda kırpılır, burada değil). */}
              <ChangeDiffChips t={t} changes={sel.changes} teamNames={teamNames} />

              {/* İlk kayıt: "hangi değerlerle doğdu" — asıl istenen bilgi burada. */}
              {detail && detail.snapshot && (
                <div className="chg-snapshot">
                  {/* Başlık OLAY TÜRÜNE bakar, seq'e değil: denetimden taşınan satırlarda ilk
                      kayıt bir UPDATE olabiliyor ve o zaman "İlk değerler" yanlış olurdu. */}
                  <div className="kw-block-title">
                    {sel.event_type === 'CREATE' ? t('chg.initialValues') : t('chg.stateAfter')}
                  </div>
                  <dl className="chg-dl">
                    {parseSnapshot(detail.snapshot).map(f => (
                      <div key={f.key} className="chg-dl-row">
                        <dt>{fieldLabel(t, f.key)}</dt>
                        <dd>{formatValue(f.key, f.value, { t, teamNames })}</dd>
                      </div>
                    ))}
                  </dl>
                </div>
              )}
            </div>
          </div>
        )}

        {/* Sunucu tarafı sayfalama: usePagination istemci dizisini böler, burada sayfa SUNUCUDAN
            geliyor — bu yüzden PaginationBar'a değerler elle veriliyor.
            TABAN DÖNÜŞÜMÜ ŞART: `page` state'i ve API 0-tabanlı, PaginationBar 1-tabanlı
            (clamp min 1, ilk/önceki `page <= 1`'de kapalı). Dönüşüm yapılmadığında state=0'da
            hiçbir sayfa aktif görünmüyor ve "1" düğmesi API'nin 2. sayfasına gidiyordu.
            rangeStart/rangeEnd 0-tabanlı kalır — onlar state'i doğrudan kullanır. */}
        <PaginationBar
          page={page + 1}
          totalPages={Math.max(1, Math.ceil(total / size))}
          totalItems={total}
          rangeStart={total === 0 ? 0 : page * size + 1}
          rangeEnd={Math.min(total, (page + 1) * size)}
          pageSize={size}
          onPageChange={(p) => setPage(p - 1)}
          onPageSizeChange={(s) => { setSize(s); setPage(0) }} />
      </>)}
    </div>
  )
}
