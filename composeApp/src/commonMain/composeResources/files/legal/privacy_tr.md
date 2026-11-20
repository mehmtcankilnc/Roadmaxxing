<!--
TASLAK: geliştirme ekibi tarafından yazıldı, henüz hukuki incelemeden geçmedi. Yayından önce sahibi veya bir
avukat bu metni gözden geçirip kesinleştirmelidir. privacy_en.md ve uygulamanın gerçekte yaptığı şeylerle
aynı kalmalıdır.
-->

# Gizlilik Politikası

Son güncelleme: 2026-11-20

Bu politika, Roadmaxxing'in verilerinle ne yaptığını anlatır. Uygulamanın bugünkü hâlini tarif eder. Kısa ve sade tutmaya çalıştık.

## Kısaca

- Hesabın yoksa sürüşlerin ve onlardan hesaplanan yüzdeler **telefonunda kalır**. Hiçbir şey yüklenmez.
- Hesap **isteğe bağlıdır**. Hesap oluşturursan e-posta adresini, bir hesap kimliğini ve giriş yöntemini saklarız; ayrıca uygulama sürüşlerinin bir özetini ve ayarlarını buluta **yedekler** (ayrıntılar aşağıda). **Ham GPS noktaları asla yüklenmez.**
- Konum verini **satmayız**, reklam amacıyla da paylaşmayız.
- Verini herhangi bir yere gönderen analiz veya hata raporlama araçları kullanmıyoruz.

## Uygulama neleri toplar?

**Konum, yalnızca bir sürüş kaydedilirken.** Uygulama sürüşe başlamış olabileceğini düşündüğünde, doğrulamak için konumuna kısa süreliğine bakar. Sürüşse, sürüş bitene kadar GPS noktalarını (zaman, enlem, boylam, hız, yön ve doğruluk) kaydeder. Sürüşte değilken yüksek doğruluklu GPS kapalıdır. Bunun için konum izni, otomatik kayıt için de "Her zaman izin ver" seçeneği gerekir.

**Hareket etkinliği.** Uygulama, sürüşe başlamış olabileceğini fark etmek için telefonunun fiziksel etkinlik sensörünü kullanır; böylece GPS'in sürekli açık kalması gerekmez. Yalnızca etkinlik türü (örneğin "araçta") kullanılır.

**Sürüş sonuçları.** Sürüşten sonra uygulama izini yollarla eşleştirir ve hangi yolları sürdüğünü, ayrıca mesafe, süre, ortalama ve en yüksek hız gibi basit değerleri saklar.

**Hesap bilgileri (isteğe bağlı).** Uygulamayı hesapsız kullanabilirsin. Hesap oluşturmayı seçersen e-posta adresin ve bir parolayla kaydolabilir ya da Google ile giriş yapabilirsin. Bu durumda e-posta adresini, bir hesap kimliğini ve giriş sağlayıcısını (e-posta veya Google) saklarız. Google ile giriş yaparsan, Google'ın giriş için paylaştığı bilgileri (örneğin e-posta adresin) alırız. Parolanı uygulamada saklamayız; parolalar kimlik doğrulama hizmeti tarafından işlenir.

## Verin nerede işlenir ve saklanır?

Yol eşleştirme ve yüzde hesabı **cihazında** yapılır. Kaydedilen izler, sürüşler ve sürülen yollar telefonundaki uygulama depolama alanında tutulur. Hesabın yoksa bize veya başka birine gönderilmez. Android ve iOS, kendi cihaz ayarlarına göre uygulama verisini yedekleyebilir; bu, uygulamanın kontrolü dışındadır.

**Hesaplar.** Hesaplar, Google LLC'nin bir hizmeti olan **Firebase Authentication** ile sağlanır; Google hesap bilgilerini bizim adımıza (veri işleyen olarak) işler. Giriş yapmak veya çıkış yapmak telefonundaki sürüşleri asla değiştirmez.

**Bulut yedeği, yalnızca hesapla.** Ancak ve yalnızca hesabın olduğunda ve giriş yaptığında uygulama şunları **Google Cloud Firestore**'a (Google LLC'nin bir hizmeti olan Firebase'in parçası; verini bizim adımıza, veri işleyen olarak saklar) yedekler: her sürüş için bir özet (başlangıç ve bitiş zamanı, mesafe, süre, ortalama ve en yüksek hız, açtığı yeni yolun uzunluğu), sürüşün **sadeleştirilmiş rotası** ve sürdüğün **yol parçalarının kimlikleri**; ayrıca ayarların (mesafe birimi, sessiz saatler ve harita katmanı seçimleri), gizlilik bölgelerin ve ulaştığın kilometre taşları. **Ham GPS noktaları (kaydedilen izlerin) asla yüklenmez.** Amaç, ilerlemeni yedeklemek ve başka bir telefonda geri yüklemektir. Veri hesabının altında saklanır ve yalnızca sana, hesabın üzerinden görünür; başka kullanıcılar okuyamaz. Hesabın yoksa hiçbir şey yüklenmez. Bir telefonda giriş yaptığında, o telefondaki sürüşler hesabına yüklenir. Sürüşlerin olduğu bir telefonda farklı bir hesap giriş yaparsa uygulama, onları o hesaba ekleyip eklemeyeceğini, o hesabın verisiyle değiştirip değiştirmeyeceğini ya da iptal edip çıkış yapıp yapmayacağını sorar.

## Bildirimler

İzin verirsen, bir sürüş kaydedilirken uygulama bir bildirim gösterir. Bildirim cihazında oluşturulur.

## Harita ve yol verisi

Harita, **OpenFreeMap**'ten yüklenen karolarla çizilir. Her web isteğinde olduğu gibi, karoları yüklemek IP adresini ve baktığın harita bölgesini o hizmetin görmesine yol açar. Sürüşlerini veya kaydedilen konumlarını ona göndermeyiz. Yol verisi **OpenStreetMap**'ten gelir; © OpenStreetMap katkıda bulunanlar, Açık Veritabanı Lisansı (ODbL) kapsamında kullanılabilir.

## Kart paylaşımı

Uygulama bir görsel kart (örneğin sürüş özeti) oluşturabilir. Kart **yalnızca sen paylaşmayı veya kaydetmeyi seçtiğinde** oluşturulur. Görseli sonrasında başka bir uygulamada paylaşmak gibi yapacakların sana aittir ve o uygulamanın kurallarına tabidir. Paylaşmadan önce karttaki hız bilgilerini gizleyebilirsin.

## Satış ve paylaşım

Konum verini veya başka bir kişisel verini satmayız. Konumunu reklam için kullanmayız.

## Verini saklama ve silme

Sürüş verin telefonunda durur; hesabın varsa bulut yedeğinde de bulunur. Sürüşlerini tek bir GPX dosyası olarak dışa aktarıp telefonunun paylaşım menüsüyle paylaşabilirsin; hiçbir şey yüklenmez. Telefondaki tüm verini uygulamada Profil → Ayarlar altından silebilirsin. Giriş yapmışsan uygulama bulut yedeğini de silmeyi önerir (varsayılan olarak işaretlidir); işaretlemezsen bulut yedeği kalır. Silme anında gerçekleşir ve geri alınamaz; senin tutmayı seçtiğin bir bulut yedeği dışında geri yükleyebileceğimiz bir yedek yoktur. Verini uygulamayı kaldırarak da silebilirsin. Sürdüğün yolların, sen verini silene kadar haritanda kaldığını unutma.

Hesabın varsa, uygulamada Profil altından silebilirsin. Hesabı silmek, hesap bilgilerini (e-posta adresi, hesap kimliği ve giriş sağlayıcısı) kaldırır ve **bulut yedeğini siler** (sürüşler, ayarlar, gizlilik bölgeleri ve kilometre taşları). Bulut yedeği silinemezse hesap silinmez ve uygulama bunu sana bildirir. Uygulama, telefonundaki sürüş verisini de silmek isteyip istemediğini sorar; bu kısım isteğe bağlıdır ve işaretlemezsen yerel verin telefonunda kalır. Çıkış yapmak hiçbir şeyi silmez: verin telefonunda ve bulut yedeğinde kalır.

## Çocuklar

Roadmaxxing araç kullanabilecek yaştaki kişiler içindir ve çocuklara yönelik değildir. 13 yaşından küçük çocukların (veya yerel yasanın belirlediği yaşın altındakilerin) verisini bilerek toplamayız.

## Haklarının özeti

Yaşadığın yere bağlı olarak 6698 sayılı Kişisel Verilerin Korunması Kanunu (KVKK) ve AB Genel Veri Koruma Tüzüğü (GDPR) gibi yasalar, kişisel verin üzerinde sana haklar tanır. Kısaca: verinin işlenip işlenmediğini öğrenebilir; erişim, düzeltme veya silme talep edebilir; işlemeye itiraz edebilir ve veri koruma otoritene şikâyette bulunabilirsin. Sürüş verinin büyük bölümü cihazında olduğu ve bulut yedeğini ile hesabını uygulamada silebildiğin için bu haklarının çoğunu zaten kendin kullanabilirsin; uygulamayı silmek yerel veriyi de siler. Her türlü talebin için aşağıdaki iletişim bilgisinden bize yazabilirsin.

## Politikadaki değişiklikler

Uygulama değiştikçe bu politikayı güncelleyebiliriz. En üstteki "son güncelleme" tarihi en yeni sürümü gösterir. Bir değişiklik verinin kullanımını etkiliyorsa, yürürlüğe girmeden önce uygulama içinde sana haber vereceğiz.

## İletişim

Soru ve talepler için: [iletişim e-postası]

Veri sorumlusu: [şirket veya sahibin adı ve adresi]
