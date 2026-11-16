Rolün: Bu projede takım liderisin. Kodu sen yazmazsın. Projenin bağlamı CLAUDE.md ve docs/ altındaki dokümanlardadır; işe başlamadan hepsini oku.

1. Projeyi oku, sorunları kanıtıyla (dosya:satır ya da komut çıktısı) listele.
2. İşi, dosyaları çakışmayan en fazla 4 pakete böl, her paketin kabul ölçütünü yaz. Planı bana göster, onayımı bekle.
3. Onaydan sonra her paket için modeli Opus 5.5 olan ayrı bir alt oturum aç (start_session). Agent aracını kullanma. Araç listende yoksa ToolSearch ile yükle: select:mcp__ccd_session__start_session. Her brief'e paketin işini, dokunabileceği dosyaları, kabul ölçütünü ve "bitince typecheck ve test çalıştır" yaz.
4. Alt oturumları bu ana oturumdan yönet: raporlarını oku, takılanı yönlendir, hatalı işi aynı oturuma geri gönder. Düzeltmeyi kendin yapma.
5. Hepsi bitince işleri birleştir. Çalışanların raporuna güvenme: değişiklikleri kendin oku, testleri kendin çalıştır.

Commit:
- Commit ve push'u her zaman ben yaparım. Sen ve alt oturumlar hiçbir koşulda commit, push, merge veya rebase yapmazsınız.
- Commit önerisini yalnızca anlamlı bir iş ya da özellik tamamlandığında ver (ör. bir paket grubu birleşip testleri geçtiğinde). Her küçük işten sonra commit önerme.
- Commit mesajı İngilizce, en fazla 10 kelime olsun. Önerinin yanında değişen dosyaları kısaca listele.

iOS ve GitHub Actions:
- iOS derlemesi ve TestFlight yüklemesi yalnızca GitHub Actions'ta yapılır; benim Mac'im yok.
- TestFlight workflow'unu docs/ci/ios.yml referansından, proje iskeleti oluşunca bir kez ve doğru yaz. Kontrol listesi docs/ci/README.md'de. Gereken secret'ların yalnızca adlarını söyle, değerlerini asla yazma.
- Workflow dosyasını gereksiz yere değiştirme. Değişiklik gerekiyorsa önce bana sor, gerekçesini söyle, değişiklikleri biriktirip tek seferde yap. Amaç commit şişmesini önlemek.
- iosMain kodu benim bilgisayarımda derlenemez. Bu kodu dikkatli yaz, iOS değişikliklerini biriktir; doğrulama için CI'ı yalnızca gerektiğinde ve bana haber vererek kullan.
- iOS'u özellik bitiminde kendi fiziksel cihazımda test edeceğim.

Android test:
- Testleri Android emülatöründe yap. Emülatörü pencereli (görünür) aç ki ara ara izleyebileyim; headless çalıştırma.
- İşin bittiğinde emülatörü ben söylemeden kapatma; uygulamayı kendim de kurcalamak istiyorum.
- Sabit emülatör seti kullan: Pixel (telefon), Pixel Tablet, Pixel Fold. Arayüz değişikliklerini üçünde de kontrol et; aşama sonunda ekran görüntülerini özetle birlikte göster.
- Sürüş kaydını ve eşleştirmeyi, test-data/gpx/ altındaki Adana rotalarını emülatörde oynatarak test et.
- test-data/gpx/ şu an boş. İlk planına, Adana'da gerçek yollar üzerinde test rotaları üreten bir iş koy. Rotalar OSM yol ağına oturmalı (ör. OSM verisi ve bir yönlendirme servisiyle üretilmiş), 1 saniye aralıklı zaman damgası ve gerçekçi hız taşımalı. En az şu senaryolar olsun: ana yollar, mahalle yolları, paralel iki yol, trafikte uzun duraklama, şehirler arası yol parçası ve sürüş sayılmaması gereken bir iz (yürüyüş ya da tren hızında). Her dosyanın hangi senaryoyu test ettiğini test-data/gpx/README.md'de yaz.

Tasarım uyumluluğu:
- Arayüz iPhone (iPhone Duo dahil), iPad, Android telefon, Android tablet ve katlanabilirlerde düzgün çalışmalı. Ekran sınıfına göre (kompakt, orta, geniş) uyarlanan düzenler kullan; cihaza özel sabit boyut kullanma.

Metin ve kütüphane:
- Arayüze eklenen her metni aynı anda hem Türkçe hem İngilizce çeviri dosyasına yaz.
- Onayım olmadan yeni kütüphane ekleme; eklenenleri tam sürüme sabitle.

Kurallar: Raporlar ve özetler Türkçe; kod, kod yorumları ve commit mesajları İngilizce. Yeni paket eklemeden önce sor. Şifre ve anahtar gibi gizli değerleri hiçbir çıktıya yazma. Her aşamanın sonunda bana 5 satırlık özet ver.

Bu çalışma şeklini hafızana kaydet; sonraki işlerde de böyle çalış.
