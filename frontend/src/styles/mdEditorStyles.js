/**
 * Markdown editörü (@uiw/react-md-editor + @uiw/react-markdown-preview) stilleri — AÇILIŞ CSS'inde, ESKİ YERİNDE.
 *
 * Neden (2026-10-02, performans önerisi 22): editörün JS'i açılış paketinden çıktı (CertificateModal → Alarm geçmişi →
 * olay formu zinciri lazy oldu). Vite bir modülün CSS'ini o modülün chunk'ıyla taşır; editör CSS'i de lazy chunk'a
 * düşüp sayfaya SONRADAN, globals.css + App.css'ten SONRA eklenecekti. Kaskad sırası tersine dönerdi: App.css'in
 * editör ezmeleri (`.w-md-editor-text-input`, `.md-editor-box …`, `.wr-editor …`) ile kütüphanenin aynı özgüllükteki
 * kuralları arasında kazanan değişir, haftalık rapor / envanter / olay formu editörleri farklı görünebilirdi.
 *
 * Burada kütüphanenin KENDİ içe aktardığı CSS modüllerinin AYNISI (aynı dosya = aynı modül kimliği) açılış grafiğine
 * alınır: Rollup onları açılış CSS'ine, globals.css'ten ÖNCE koyar (bölmeden önceki bayt düzeniyle aynı) ve lazy chunk
 * yeniden eklemez. Yollar göreli dosya yolu çünkü paketin `exports` alanı bu alt yolları dışa vermiyor. Sıra,
 * kütüphanenin yürütme sırasıdır (bölme öncesi build'in CSS'iyle bayt düzeyinde karşılaştırılarak doğrulandı).
 * Bir @uiw sürüm yükseltmesinde dosya adları değişirse build kırılır — o zaman kütüphanedeki `import "./….css"`
 * satırlarına bakıp bu listeyi güncelleyin.
 */
import '../../node_modules/@uiw/react-markdown-preview/esm/styles/markdown.css'
import '../../node_modules/@uiw/react-md-editor/esm/components/TextArea/index.css'
import '../../node_modules/@uiw/react-md-editor/esm/components/Toolbar/Child.css'
import '../../node_modules/@uiw/react-md-editor/esm/components/Toolbar/index.css'
import '../../node_modules/@uiw/react-md-editor/esm/components/DragBar/index.css'
import '../../node_modules/@uiw/react-md-editor/esm/index.css'
