#!/usr/bin/env python3
"""
Site Monitor marka varlığı üretici.
Kaynak: beyaz zeminli JPEG logo -> kırpılmış şeffaf PNG master + durum varyantları
(ok/yeşil, warning/amber, critical/kırmızı, muted/gri) + favicon/e-posta/UI boyutları.

Kullanım: python3 make_variants.py <kaynak.jpg> <çıktı_klasörü>
Deterministik ve yeniden koşulabilir — logo değişirse aynı script yeniden üretir.
"""
import sys, os
import numpy as np
from PIL import Image

SRC = sys.argv[1]
OUT = sys.argv[2]
os.makedirs(OUT, exist_ok=True)

# ---------- 1) Yükle, beyaz zeminden kırp ----------
img = Image.open(SRC).convert("RGB")
a = np.asarray(img).astype(np.float32)

# beyazdan uzaklık (kanal bazında maksimum fark)
dist = (255.0 - a).max(axis=2)
mask = dist > 12
ys, xs = np.where(mask)
y0, y1, x0, x1 = ys.min(), ys.max(), xs.min(), xs.max()
pad = int(0.05 * max(y1 - y0, x1 - x0))
y0, x0 = max(0, y0 - pad), max(0, x0 - pad)
y1, x1 = min(a.shape[0], y1 + pad + 1), min(a.shape[1], x1 + pad + 1)
a = a[y0:y1, x0:x1]
dist = dist[y0:y1, x0:x1]

# ---------- 2) Beyaz -> şeffaf, YALNIZ dış arka plan (iç beyazlar korunur) ----------
# Logo içindeki beyazlar (yörünge şeridi, hekzagon grafik çizgisi, nokta dolguları)
# koyu temada kaybolmasın diye: near-white maskesi kenardan flood-fill ile ayrılır;
# sadece kenara bağlı bileşen arka plan sayılır.
from scipy import ndimage
T_LO, T_HI = 8.0, 70.0
nearwhite = dist < T_HI
lab, n = ndimage.label(nearwhite)
border_labels = np.unique(np.concatenate(
    [lab[0, :], lab[-1, :], lab[:, 0], lab[:, -1]]))
border_labels = border_labels[border_labels != 0]
background = np.isin(lab, border_labels)
alpha = np.ones_like(dist)
alpha[background] = np.clip((dist[background] - T_LO) / (T_HI - T_LO), 0.0, 1.0)
# arka plan sınırındaki yarı saydam piksellerde beyazla karışan rengi geri çöz
af = np.clip(alpha, 1e-4, 1.0)[..., None]
ink = np.where(alpha[..., None] < 1.0,
               np.clip((a - (1.0 - af) * 255.0) / af, 0, 255), a)
rgba = np.dstack([ink, alpha * 255.0]).astype(np.uint8)
master = Image.fromarray(rgba, "RGBA")

# kare tuvale ortala (ikon kullanımı için)
w, h = master.size
side = max(w, h)
sq = Image.new("RGBA", (side, side), (0, 0, 0, 0))
sq.paste(master, ((side - w) // 2, (side - h) // 2), master)
master_sq = sq.resize((1024, 1024), Image.LANCZOS)

# ---------- 3) Durum varyantları (yalnız yeşil ton maskesi kaydırılır) ----------
def make_variant(im_rgba, mode):
    arr = np.asarray(im_rgba).astype(np.float32)
    rgb, al = arr[..., :3], arr[..., 3:]
    hsv = np.asarray(Image.fromarray(rgb.astype(np.uint8), "RGB").convert("HSV")).astype(np.float32)
    H, S, V = hsv[..., 0], hsv[..., 1], hsv[..., 2]
    # yeşil bölge: hue ~[45°,185°] -> PIL ölçeği [32,131]; doygun ve görünür pikseller
    green = (H >= 32) & (H <= 131) & (S > 50) & (V > 40) & (al[..., 0] > 10)
    H2, S2, V2 = H.copy(), S.copy(), V.copy()
    if mode == "critical":      # kırmızı: yeşil merkez ~78 -> ~4; gölge çeşitliliği korunur
        H2[green] = np.clip(4 + (H[green] - 78) * 0.10, 0, 14)
        S2[green] = np.clip(S[green] * 1.12 + 12, 0, 255)
        V2[green] = np.clip(V[green] * 0.96, 0, 255)
    elif mode == "warning":     # amber
        H2[green] = np.clip(24 + (H[green] - 78) * 0.10, 18, 30)
        S2[green] = np.clip(S[green] * 1.08 + 8, 0, 255)
    elif mode == "muted":       # gri-yeşil (duraklatılmış/bilinmiyor)
        S2[green] = S[green] * 0.12
        V2[green] = np.clip(V[green] * 0.92 + 8, 0, 255)
    elif mode == "ok":
        pass
    hsv2 = np.dstack([H2, S2, V2]).astype(np.uint8)
    rgb2 = np.asarray(Image.fromarray(hsv2, "HSV").convert("RGB")).astype(np.uint8)
    return Image.fromarray(np.dstack([rgb2, al.astype(np.uint8)]), "RGBA")

VARIANTS = ["ok", "warning", "critical", "muted"]
out = {}
for v in VARIANTS:
    out[v] = make_variant(master_sq, v)
    out[v].save(f"{OUT}/logo-{v}-1024.png")

# ---------- 4) Boyut setleri ----------
def save_sizes(im, name, sizes):
    for s in sizes:
        im.resize((s, s), Image.LANCZOS).save(f"{OUT}/{name}-{s}.png")

for v in VARIANTS:
    save_sizes(out[v], f"logo-{v}", [512, 192, 64, 32])
    out[v].resize((320, 320), Image.LANCZOS).save(f"{OUT}/email-{v}.png")  # mailde 160px @2x

# favicon.ico (varsayılan = ok)
out["ok"].resize((48, 48), Image.LANCZOS).save(
    f"{OUT}/favicon.ico", sizes=[(16, 16), (32, 32), (48, 48)])
# apple-touch-icon: opak beyaz zemin ister
at = Image.new("RGBA", (1024, 1024), (255, 255, 255, 255))
at.paste(out["ok"], (0, 0), out["ok"])
at.convert("RGB").resize((180, 180), Image.LANCZOS).save(f"{OUT}/apple-touch-icon.png")

# ---------- 5) Marka paleti (baskın doygun renkler) ----------
arr = np.asarray(master_sq).astype(np.float32)
op = arr[arr[..., 3] > 200]
hsv = np.asarray(Image.fromarray(op[:, :3][None].astype(np.uint8), "RGB").convert("HSV"))[0].astype(np.float32)
def dom(mask_):
    if mask_.sum() == 0: return None
    px = op[mask_][:, :3].mean(axis=0).astype(int)
    return "#%02X%02X%02X" % tuple(px)
purple = dom((((hsv[:, 0] > 180) | (hsv[:, 0] < 10)) & (hsv[:, 1] > 90)))
green  = dom(((hsv[:, 0] >= 40) & (hsv[:, 0] <= 120) & (hsv[:, 1] > 90)))
print("PALETTE purple:", purple, " green:", green)

# ---------- 6) Kontrol sayfası (açık + koyu zemin) ----------
cell, padc = 300, 24
sheet = Image.new("RGB", (4 * cell + 5 * padc, 2 * cell + 3 * padc), (255, 255, 255))
dark = Image.new("RGB", (sheet.width, cell + padc), (24, 26, 32))
sheet.paste(dark, (0, cell + 2 * padc))
for i, v in enumerate(VARIANTS):
    t = out[v].resize((cell - 20, cell - 20), Image.LANCZOS)
    x = padc + i * (cell + padc)
    sheet.paste(t, (x + 10, padc + 10), t)
    sheet.paste(t, (x + 10, cell + 2 * padc + 10), t)
sheet.save(f"{OUT}/contact-sheet.png")
print("OK", OUT)
