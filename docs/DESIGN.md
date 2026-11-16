# Roadmaxxing — Tasarım Dili

Son güncelleme: 16 Kasım 2026

Ana renk keşfedilen yolun rengidir: Volt yeşili. Uygulama yalnızca koyu temada çalışır; kategorinin alışılmış iskeletini korur (referans: Wheelz), farklılığı harita stili ve paylaşım kartıyla yaratır. Hedef his: sade, akıcı, kullanıcıya iyi hissettiren.

## Renk token'ları

| Token | Değer | Kullanım |
| --- | --- | --- |
| `primary` | `#C8FF3D` | Keşfedilen yollar, ana buton, vurgu sayıları |
| `onPrimary` | `#1A2600` | Yeşil buton üzerindeki metin (beyaz asla) |
| `background` | `#0B0B0D` | Ana zemin |
| `surface` | `#16171B` | Kartlar, alt sayfalar |
| `surfaceRaised` | `#1F2026` | Seçili kart, çip arka planı |
| `textPrimary` | `#F2F0EA` | Kırık beyaz ana metin |
| `textSecondary` | `#8A8D94` | Açıklamalar, etiketler |
| `roadUnexplored` | `#2C2F36` | Haritada sürülmemiş yollar |
| `danger` | `#FF5A5F` | Hata, silme |

Neden Volt yeşili: Kategoride (Wheelz, TripRank: mavi; Wheelz haritası: kırmızı) kullanılmıyor; ETS2'deki "yeşil = keşfedildi" fikrini karşılıyor; siyah zeminde en yüksek kontrastı veriyor, bu da Instagram akışında kartın göze çarpmasını sağlıyor.

Onboarding ve paywall zemininde Wheelz'deki mor parıltının yerine ana rengin çok hafif bir tonu kullanılır.

## Tipografi

- Yazı tipi: **Inter** (çok dilli, Türkçe karakter desteği tam).
- Başlık 34/700 · alt başlık 22/600 · gövde 16/400 · etiket 13/500.
- Sayılar için tabular rakamlar (yüzdeler ve km sayaçları zıplamasın).

## Hareket

- Tek imza animasyonu: yeni açılan yolun çizgi boyunca "yanarak" ilerlemesi (600 ms).
- Kilometre taşlarında yüzde sayacının sayıp durması ve hafif haptik.
- Geçişler 250 ms, ease-out.

## Erişilebilirlik

- Metin kontrastı en az 4.5:1.
- Renk tek başına bilgi taşımaz: keşfedilen yollar daha kalın da çizilir.
- Dinamik yazı boyutu desteği.

## Bileşenler ve kalıplar

**Wheelz'den alınan kalıplar (kullanıcının alışık olduğu iskelet):**

- Harita her zaman arkada canlı; içerik 32 dp köşeli yüzen panellerde.
- Yüzen 4 sekmeli alt çubuk: Harita, Sürüşler, İstatistikler, Profil. Aktif sekme koyu bir hap içinde.
- Büyük ve kalın sayfa başlıkları, gri ikincil metinler.
- Hap şeklinde filtre çipleri (ör. "En yeni", "En eski").
- Kart kart izin ekranı: her izin ayrı kart, sağda kendi butonu, neden istendiği açıklaması.
- Güvenli sürüş onay ekranı: iki madde + iki onay kutusu (güvenli süreceğim, şartlar ve gizlilik).
- Paywall: 3 plan kartı, rozetler ("En avantajlı", "Popüler", "Esnek"), yıllık plan varsayılan seçili, altta "Geri yükle · Şartlar · Gizlilik".
- Davet ekranı: kazanılan hafta ilerleme göstergesi (Wheelz: 6 hafta; biz: 8 hafta).
- Harita katmanları alt sayfası: tema seçimi (premium temalar kilitli) + katman anahtarları. Bizde: "Keşfedilmemiş yollar" katmanı Pro.

**Bilerek farklılaştığımız yerler:**

- Mavi ve mor yerine Volt yeşili.
- Boş durumlar davete değil sürüşe davet eder ("İlk yolunu aç"). Wheelz'in boş "Sürüşler" ekranı ilk sürüşten önce arkadaş davet etmeye itiyor; bunu yapmıyoruz.
- Yarım yerelleştirme yok: Wheelz'in Türkçe arayüzünde "Annual", "Renews every year", "Body/Wheels/Lights" gibi İngilizce metinler duruyor. Bizde tüm metinler çeviri dosyalarından gelir.
- Bildirimler isteğe bağlı; uygulama bildirim izni olmadan da tam çalışır.
- MVP'de 3D araç tasarlayıcı, liderlik tablosu, yer arama ve hız kameraları yok.

## Ekran listesi (MVP)

1. Değer tanıtımı (1-2 ekran): gri şehir haritasının yeşile dönmesi animasyonu.
2. Güvenli sürüş onayı.
3. İzin kartları: konum (Her zaman + kesin konum), hareket, bildirimler (isteğe bağlı).
4. Paywall (kapatılabilir).
5. Keşif haritası (ana ekran): tam ekran koyu harita, üstte şehir yüzdesi kartı, altta yüzen sekme çubuğu.
6. Sürüş özeti + yeni yol animasyonu + kart önizleme.
7. Sürüşler listesi: mini rota çizimi ve yeni km rozeti.
8. İstatistikler: ülke, şehir, ilçe yüzdeleri; yol tipi kırılımı; hız istatistikleri.
9. Profil ve ayarlar: hesap, abonelik, gizlilik bölgeleri, sessiz saatler, birim, dil, davet.
10. Hesap oluşturma (ilk paylaşımda veya 3. sürüşte): Apple, Google, e-posta.
11. Kilometre taşı kutlaması.

## Paylaşım kartı

- Format: 9:16 Story (1080×1920) varsayılan, 4:5 gönderi (1080×1350) alternatif.
- Tek bir dev sayı (yüzde veya yeni km), en fazla 3 destek istatistiği.
- Logo ve uygulama adı her kartta görünür; Pro'da küçük ama kaldırılamaz.
- Şehir kartı: şehir silueti içinde sürülen yollar Volt yeşili, altta büyük puntoyla yüzde. Her şehir farklı göründüğü için her kart benzersiz.
- Gizlilik bölgeleri haritadan kesilir; kullanıcı hız bilgisini gizleyebilir.
- MVP'de 1 ücretsiz + 2 Pro stil.

## UI referansları

`ui-references/wheelz/` klasöründe referans uygulama Wheelz'in (Vortac Labs) ekran görüntüleri var. Bunlar düzen, akış ve kalıp referansıdır; renkleri, metinleri ve görselleri kopyalanmaz.

| Dosya | Ekran | Bizim için not |
| --- | --- | --- |
| `01-onboarding-izinler.jpg` | Kart kart izin ekranı | Kalıbı al; bildirimi isteğe bağlı yap |
| `02-liderlik-tablosu.jpg` | Podyumlu liderlik tablosu | MVP'de yok (Faz 3) |
| `03-davet-et.jpg` | Davet ve kazanılan haftalar | Kalıbı al; 8 hafta |
| `04-yer-arama.jpg` | Yer arama alt paneli | MVP'de yok |
| `05-surusler-bos-durum.jpg` | Boş sürüşler ekranı | Davet yerine "İlk yolunu aç" |
| `06-harita-katmanlar.jpg` | Harita teması ve katmanlar | Tema seçici + Pro katman fikri |
| `07-arac-tasarla-gunduz.jpg` | 3D araç tasarlayıcı (gündüz) | MVP'de yok |
| `08-arac-tasarla-gece.jpg` | 3D araç tasarlayıcı (gece) | MVP'de yok |
| `09-paywall.jpg` | 3 planlı paywall | Kalıbı al; tam yerelleştir |
| `10-guvenli-sur-onay.jpg` | Güvenli sürüş + şartlar onayı | Kalıbı al |
