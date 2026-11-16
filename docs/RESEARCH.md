# Roadmaxxing — Pazar Araştırması ve Rakipler

Son güncelleme: 16 Kasım 2026. Rakamlar araştırma tarihindeki mağaza ve web kaynaklarından alınmıştır; tahmini gelir rakamları kaynaklar arasında çelişkilidir ve plan yapmak için kullanılmamalıdır.

## Sonuç

Fikir doğrulanıyor: Harita tamamlama mekaniği yürüyüş, koşu ve bisiklette kanıtlanmış ve para kazandırıyor; sürüş tarafında bu niş henüz neredeyse boş. Sürüş takibi kategorisi de büyüyor. Rakiplerin en büyük zayıflığı (ilk kullanımdan sonra sıkıcılaşma) bizim ana mekaniğimizin doğrudan çözdüğü sorun.

**Konumlandırma:** "Wheelz gibi bir sürüş takipçisi + harita" değil, "Sürüş için Wandrer". Keşif ana mesaj, hız istatistikleri ikinci planda.

## Harita tamamlama başka alanlarda

| Ürün | Alan | Kanıt | Bizim için anlamı |
| --- | --- | --- | --- |
| [Wandrer](https://wandrer.earth/faq) | Bisiklet, koşu | Kurucusu 2016'da şehrindeki her yolu sürmeye başlayınca kurmuş; Strava verisini OSM ile eşleştiriyor; ~45 bin kullanıcı; aylık 2-3 $ abonelik; **ücretli özellik "hiç gidilmemiş yollar" katmanı** | Keşfedilmemiş yollar Pro özelliği olabilir; son %10 problemi bilinen bir dert |
| [CityStrides](https://www.citystrides.com) | Koşu | 2013'ten beri yayında, freemium. Kullanıcı yorumu: 10 yıllık koşusunun hep şehrin aynı %2'lik yollarında geçtiğini fark etmemiş | Mekanik uzun ömürlü; pazarlama mesajı hazır |
| [Fog of World](https://apptail.io/app/app-Bdc/united-states) | Yürüyüş, seyahat | 137 ülkede "Günün Uygulaması"; 2,9 bin oyla 4,81; en sık şikâyet arka plan konumu ve pil | Pil ve izin yönetimi kritik |
| [TRACE Walks](https://apps.apple.com/app/id6818195985) | Yürüyüş | Yürüdüğün her sokağı renklendiriyor | Aynı konseptin yürüyüş versiyonu; isim olarak "Trace" kullanılamaz |

## Sürüş tarafında doğrudan rakipler

| Ürün | Durum | Not |
| --- | --- | --- |
| [OVERDRIVE — Drive & Discover](https://mwm.ai/apps/overdrive-drive-discover/6790023849) | 23 Temmuz 2026, sadece iPhone, sıralamalarda yok | Sürdüğün her yolu karanlık 3D şehir modelinde aydınlatıyor. Pro: sınırsız boyama, 1.000+ bölge, günlük görevler, seriler, tam geçmiş. **En yakın rakip, henüz ölçeklenmemiş** |
| pugson'ın uygulaması (X) | Temmuz 2026'da TestFlight | "Arabalar için Strava": kart paylaşımı, fog-of-war tarzı şehir yüzdesi, yakıt takibi, ısı haritası, CarPlay ile otomatik kayıt |
| [Open Road](https://www.openroaddrive.com/) | iOS + Android, 50 bin+ sürücü | Sürdüğün yollar haritada doluyor, konvoy, segmentler, liderlik tablosu |

Bir otomobil forumunda kullanıcı her yolculuğunu tek haritada görmek istemiş, aldığı cevap "böyle bir şey bilmiyorum, özel yapmak gerekir" olmuş: talep var, karşılayan az.

## Sürüş takibi kategorisi (referans uygulamalar)

| Ürün | Veri | Not |
| --- | --- | --- |
| [Wheelz — Social Drive Tracker](https://apps.apple.com/us/app/wheelz-social-drive-tracker/id6774287184) (Vortac Labs) | 2 Haziran 2026'da çıktı; iOS ~3 bin oyla 4,75; Google Play 4,8-4,9 | **UI referansımız.** Sosyal özellikler, ısı haritası, 3D araç tasarlayıcı, premium harita temaları, liderlik tablosu, konvoy |
| TripRank / Enroad (Uros Mijajlovic, 11X Studio) | Ocak 2026; Android 50 bin+ indirme; App Store'da adı TripRank'ten Enroad'a değişti | Koyu arayüz, otomatik kayıt, paylaşılabilir istatistik görseli; sert paywall eleştirileri |

## Kullanıcı şikâyetleri (yorumlardan) ve bizim cevabımız

| Sorun | Kanıt | Bizim cevabımız |
| --- | --- | --- |
| Sıkıcılaşma (en kritik) | TripRank yorumu: aracın hızını ilk kez takip ettikten sonra uygulama yavaş yavaş çok sıkıcı hâle geliyor | Harita tamamlama her sürüşe bir sebep veriyor; retention motorumuz |
| Sert paywall | TripRank'te ücret ödemeden kendi kaydettiğin sürüşü bile göremiyorsun; gece "%80 indirim" bildirimleri ve "çark çevir" döngüsü | Ana döngü ücretsiz; sürülen yollar asla silinmez; karanlık desen yok |
| Gizlilik ve güven | Wheelz: sadece Google ile giriş, kısıtlanamayan arka plan izinleri yüzünden Pro'ya geçmekte tereddüt | Apple, Google, e-posta ile giriş; gizlilik bölgeleri; şeffaf izin ekranları |
| Otomatik algılama hataları | Wheelz: uçakta internet varken sürüş başlatıyor, uzun trafikte kaydı kesiyor; kullanıcılar "ölü saatler" istiyor | Hız/hareket filtreleri, sessiz saatler, kolay manuel düzeltme |
| Zorunlu bildirimler | Wheelz: bildirim açılmadan uygulama kullanılamıyor | Bildirimler tamamen isteğe bağlı |
| Doğruluk | TripRank: ortalama hızın maksimum hızdan yüksek çıkması, yanlış maksimum hız | Map matching ve veri doğrulama, testler |
| Tek araç sınırı | Wheelz ücretsiz sürümde 1 araç | MVP'de araç garajı yok (Faz 3+) |
| Yarım yerelleştirme | Wheelz'in Türkçe arayüzünde İngilizce metinler | İlk günden tam çeviri |
| Günlük ihtiyacı | Wheelz kullanıcısı: uygulamayı gittiği yerlerin günlüğü olarak görüyor, sürüşe not yazmak istiyor | Sonraki sürümler için not |

## Para kazandıran kalıplar

- **Fiyat aralığı:** Wheelz Pro haftalık 4,99 $, aylık 9,99 $, yıllık 49,99 $ (Türkiye: ₺169,99 / ₺279,99 / ₺2.259,99).
- **Ücretsiz katman sınırı geçmişte:** Wheelz ücretsiz sürümde son 30 günlük sürüş geçmişini saklıyor. Özellikten değil geçmişten sınırlamak akıllıca: kullanıcı önce bağlanıyor.
- **Davetle Pro:** Wheelz önce "8 kişiyi davet et, ömür boyu Pro" sunuyordu (kullanıcılar eşiğin yüksek olduğundan şikâyet etti); şimdi "katılan her arkadaş için 1 hafta, en fazla 6 hafta". Aşamalı model daha sürdürülebilir.
- **Premium harita temaları:** Wheelz'de kilitli temalar (Vice World, Brick City, Nuclear). Bizde sonraki sürümlerde hem Pro satışı hem kart çeşitliliği için.
- **Tahmini gelir:** TripRank için bir kaynak aylık ~95 bin $, diğeri 10 bin $'ın altında diyor. Güvenilmez.

## Ücretsiz altyapı sınırları (araştırma tarihinde)

| Servis | Ücretsiz sınır |
| --- | --- |
| OpenFreeMap | Herkese açık sunucu ücretsiz, harita görüntüleme ve istek sayısında limit yok, API anahtarı yok; hizmet "olduğu gibi" sunuluyor |
| Cloudflare R2 | Ayda 10 GB depolama, 1 milyon yazma (Class A), 10 milyon okuma (Class B); internete çıkış ücretsiz |
| Firestore Spark | 1 GiB veri, günde 50 bin okuma, 20 bin yazma, 20 bin silme; ödeme yöntemi gerekmez |
| RevenueCat | Aylık takip edilen gelir 2.500 $'a kadar ücretsiz, sonrası %1 |
| PostHog | Cömert ücretsiz kota (KMP SDK resmî, pre-release) |

## Kaynaklar

- [Wandrer SSS](https://wandrer.earth/faq)
- [CityStrides](https://www.citystrides.com)
- [Fog of World (AppTail)](https://apptail.io/app/app-Bdc/united-states)
- [OVERDRIVE (MWM)](https://mwm.ai/apps/overdrive-drive-discover/6790023849)
- [Wheelz — App Store](https://apps.apple.com/us/app/wheelz-social-drive-tracker/id6774287184)
- [Wheelz — Google Play](https://play.google.com/store/apps/details?id=com.gigamow.wheelz)
- [TripRank — Google Play](https://play.google.com/store/apps/details?id=io.uros.triprank)
- [Open Road](https://www.openroaddrive.com/)
- [OpenFreeMap](https://openfreemap.org)
- [Cloudflare R2 fiyatlandırma](https://developers.cloudflare.com/r2/pricing/)
- [Firebase fiyatlandırma planları](https://firebase.google.com/docs/projects/billing/firebase-pricing-plans)
- [PostHog KMP SDK](https://posthog.com/docs/libraries/kmp)
- [MapLibre Compose](https://maplibre.org/maplibre-compose/)
