package com.sitemonitor.service.mail;

import com.sitemonitor.service.EmailNotificationService.InlineImage;

import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;

/**
 * Teknik hata raporu e-postasındaki SIFIR İŞARETİ (2026-10-18, kullanıcı isteği: "0 adet gibi olursa 0 yanına yeşil
 * yuvarlak tik"): yeşil dolu yuvarlak + beyaz tik, Content-ID ile gömülü küçük PNG. Outlook CSS {@code border-radius}'u ve
 * yazı tipindeki ✓ karakterini güvenilir çizmez; görsel her istemcide aynıdır. Bir kez üretilir (sınıf yüklenirken değil,
 * ilk kullanımda) ve bütün postalarda aynı bayttır; bir postada kaç sıfır olursa olsun TEK ek ({@link #CID}).
 */
public final class TechErrorZeroMark {

    private TechErrorZeroMark() {}

    /** Gövdedeki {@code src="cid:…"} ile ekin Content-ID'si. */
    public static final String CID = "te-zero-ok";
    /** Gösterim ölçüsü (px; görsel 2× çözünürlükte — retina ekranda keskin). */
    public static final int SIZE = 16;
    /** PDF ile aynı yeşil (green-700 #15803d): beyaz ve renkli kutucuk zemininde ≥ 4:1, beyaz tik üstünde ≥ 5:1. */
    static final Color GREEN = new Color(0x15, 0x80, 0x3d);

    private static final class Holder {
        static final byte[] PNG = safeRender();
    }

    /** Üretim hatası (AWT yok vb.) postayı düşürmez: görsel yerine metin işareti kullanılır ({@link #html}). */
    private static byte[] safeRender() {
        try {
            return render();
        } catch (RuntimeException | Error e) {
            return null;
        }
    }

    /** PNG baytları (32 × 32, saydam zemin); üretilemediyse null. */
    public static byte[] png() {
        return Holder.PNG == null ? null : Holder.PNG.clone();
    }

    /** Ek; görsel üretilemediyse null (gövde o zaman metin işareti taşır — kırık görsel olmaz). */
    public static InlineImage inline() {
        return Holder.PNG == null ? null : new InlineImage(CID, Holder.PNG, "image/png");
    }

    /** Ekran okuyucu ve görselleri kapalı istemci için metin (raporun dilinde). */
    public static String alt(boolean en) {
        return en ? "zero — no issues" : "sıfır — sorun yok";
    }

    /**
     * Değerin yanına konan görsel: {@code width} / {@code height} öznitelikli (Outlook ölçüyü öznitelikten alır), kenarlıksız,
     * satır ortasında; önünde bölünmez boşluk (değerle aynı satırda kalır).
     */
    public static String html(boolean en) {
        if (Holder.PNG == null) return "&nbsp;&#10003;";
        return "&nbsp;<img src=\"cid:" + CID + "\" width=\"" + SIZE + "\" height=\"" + SIZE + "\" alt=\"" + MailKit.esc(alt(en))
                + "\" style=\"display:inline-block;width:" + SIZE + "px;height:" + SIZE + "px;border:0;outline:none;text-decoration:none;"
                + "vertical-align:middle\" data-slot=\"te-zero-ok\">";
    }

    /** Düz metin eşi. */
    public static String plain(boolean en) {
        return " ✓ (" + alt(en) + ")";
    }

    static byte[] render() {
        int n = SIZE * 2;
        BufferedImage img = new BufferedImage(n, n, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
            g.setColor(GREEN);
            g.fill(new Ellipse2D.Float(0.5f, 0.5f, n - 1f, n - 1f));
            g.setColor(Color.WHITE);
            g.setStroke(new BasicStroke(n * 0.13f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            Path2D.Float tick = new Path2D.Float();
            tick.moveTo(n * 0.27f, n * 0.50f);
            tick.lineTo(n * 0.43f, n * 0.66f);
            tick.lineTo(n * 0.73f, n * 0.34f);
            g.draw(tick);
        } finally {
            g.dispose();
        }
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(img, "png", out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
