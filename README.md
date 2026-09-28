# یکاری — پوستهٔ اندروید اپ پیک

اپ اندروید بومی (Kotlin) که وب‌اپ پیک ([`yekari_courier`](https://github.com/ahmadierfan/yekari_courier)،
Nuxt) را تمام‌صفحه در WebView باز می‌کند و هرچه وب‌ویو خودش ندارد را پر می‌کند.
هم‌زاد این ریپو [`yekari_android_customer`](https://github.com/ahmadierfan/yekari_android_customer) است؛
کد `ir.yekari.shell` در هر دو یکی است و فقط `app/build.gradle.kts` (بالای فایل)، `AppFeatures.kt`
و منابع رنگ/متن فرق دارند، به‌علاوهٔ پوشهٔ `services/` که فقط این‌جاست.

الگوها از اپ نمونهٔ [`homeease_android`](https://github.com/ahmadierfan/homeease_android) گرفته شده‌اند:
ساختار پوشه‌ها (`services/`، `notifications/`، `utils/`)، انتخابگر فایل دوربین + گالری با FileProvider،
«دو بار برگشت برای خروج»، انتخابگر مسیریاب برای `geo:`، تشخیص گفتار فارسی، قفل عمودی، و در اپ پیک
دیالوگ معافیت از بهینه‌سازی باتری + راهنمای Autostart سازنده‌ها (`OemAutostartHelper`).
عمداً متفاوت: مجوزها همان لحظهٔ نیاز خواسته می‌شوند نه همه در شروع؛ `ACCESS_BACKGROUND_LOCATION`،
FCM و MQTT فعلاً نیستند (بک‌اند یکاری هنوز push ندارد)؛ هیچ فایل `google-services.json` یا کلیدی در ریپو نیست.

## چه چیزی را پوشش می‌دهد

| نیاز وب‌اپ | این‌جا |
|---|---|
| `<input type="file">` (فاکتور خرید، مدرک تحویل، مدارک احراز، عکس در چت) | انتخابگر دوربین + گالری؛ عکس دوربین تا ۲۰۴۸px کوچک و درست‌چرخانده می‌شود |
| `navigator.geolocation` | مجوز موقعیت همان لحظه که صفحه می‌خواهد (دکمهٔ «موقعیت من» از `getLocation` پل استفاده می‌کند) |
| آنلاین‌بودن وقتی اپ بسته است | سرویس پیش‌زمینه: ارسال موقعیت + اعلان پیشنهاد تازه (پایین‌تر) |
| `getUserMedia` (پیام صوتی) | مجوز میکروفون |
| `navigator.share`، `clipboard` | با پل بومی پر می‌شوند (وب‌ویو ندارد) |
| `tel:`، `intent:`، نشان/گوگل‌مپ | در اپ مربوط باز می‌شوند؛ `geo:` با انتخابگر «انتخاب مسیریاب» (نشان، بلد، گوگل‌مپ) |
| دکمهٔ برگشت | اول `window.__yekariBack?.()`، بعد تاریخچه، در صفحهٔ اول «دو بار برای خروج» |
| قطع اینترنت / سرور خاموش | صفحهٔ «اتصال برقرار نشد» فارسی که با وصل‌شدن خودش برمی‌گردد |
| تم تیره/روشن | رنگ نوار وضعیت و ناوبری از `--surface` صفحه و `data-theme` |
| لینک عمیق | `yekari-courier://courier/mission/12` و در نسخهٔ prod لینک https دامنه (App Link) |

مجوزها هیچ‌وقت همه با هم هنگام باز شدن خواسته نمی‌شوند؛ فقط اجازهٔ اعلان یک بار بعد از اولین
صفحه (اندروید ۱۳+).

## اجرا روی گوشی/شبیه‌ساز (توسعه)

۱. وب‌اپ و API را طبق ریپوی `yekari_back` بالا بیاور (`scripts/dev-all.sh`).
۲. گوشی را با USB وصل کن (یا شبیه‌ساز) و پورت‌ها را به لپ‌تاپ برگردان:

```bash
adb reverse tcp:3501 tcp:3501   # وب‌اپ پیک
adb reverse tcp:8000 tcp:8000   # API لاراول
```

۳. ساخت و نصب:

```bash
./gradlew installDevDebug
```

چرا `localhost` و نه `10.0.2.2`: موقعیت، میکروفون و clipboard فقط روی https یا `localhost`
کار می‌کنند؛ با `adb reverse`، وب‌ویو همان `localhost` لپ‌تاپ را می‌بیند و «امن» حساب می‌شود.

برای گوشی روی وای‌فای (بدون USB):

```bash
./gradlew installDevDebug -Pyekari.dev.webUrl=http://192.168.1.20:3501 -Pyekari.dev.trustedHosts=192.168.1.20
```

(در این حالت موقعیت/میکروفون کار نمی‌کنند چون آدرس امن نیست؛ API هم باید با
`NUXT_PUBLIC_API_BASE=http://192.168.1.20:8000/api/v1` بالا آمده باشد.)

دیباگ وب‌ویو: `chrome://inspect` در کروم لپ‌تاپ (فقط نسخهٔ debug).

## پیکربندی

همه در `gradle.properties` و قابل تغییر با `-P`:

| ویژگی | معنی |
|---|---|
| `yekari.dev.webUrl` / `yekari.prod.webUrl` | آدرس وب‌اپ |
| `yekari.dev.trustedHosts` / `yekari.prod.trustedHosts` | میزبان‌هایی که داخل اپ باز می‌شوند و پل بومی را می‌بینند (با زیردامنه‌ها). API باید در آن باشد تا برگشت از درگاه داخل اپ بماند |
| `yekari.versionCode` / `yekari.versionName` | نسخه |

⚠️ `yekari.prod.webUrl=https://courier.yekari.ir` فعلاً فرضی است؛ پیش از انتشار دامنهٔ واقعی را بگذار.

نسخهٔ dev شناسهٔ `ir.yekari.courier.dev` دارد و کنار نسخهٔ prod نصب می‌شود؛ فقط dev اجازهٔ http دارد.

## انتشار

```bash
./gradlew assembleProdRelease      # APK
./gradlew bundleProdRelease        # AAB برای مارکت
```

امضا از `keystore.properties` در ریشه (در گیت نیست):

```properties
storeFile=release.jks
storePassword=…
keyAlias=…
keyPassword=…
```

یا متغیرهای محیطی `YEKARI_KEYSTORE`، `YEKARI_KEYSTORE_PASSWORD`، `YEKARI_KEY_ALIAS`، `YEKARI_KEY_PASSWORD`.
CI (`.github/workflows/android.yml`) با سکرت‌های هم‌نام (+ `YEKARI_KEYSTORE_BASE64`) امضا می‌کند و
بدونشان APK بدون امضا می‌سازد؛ خروجی‌ها در Artifacts همان اجرا هستند.

App Link (باز شدن لینک https دامنه در اپ) به فایل `https://<دامنه>/.well-known/assetlinks.json`
با SHA-256 کلید امضا نیاز دارد:

```bash
keytool -list -v -keystore release.jks | grep SHA256
```

## پل جاوااسکریپت (`window.YekariAndroid`)

فقط روی صفحه‌های خود یکاری در دسترس است. User-Agent هم `YekariAndroid/<نسخه> (courier)` دارد.

| متد | کار |
|---|---|
| `info()` | JSON: `{platform, app, version, build, sdk, tracking}` |
| `share(json)` | اشتراک‌گذاری `{title, text, url}` (پشت `navigator.share`) |
| `copy(text)` | کپی (پشت `navigator.clipboard.writeText`) |
| `haptic(kind)` | لرزش کوتاه: `light` / `success` / `error` |
| `permission(kind)` | `granted` / `prompt` / `unsupported` — `location`, `camera`, `microphone`, `notifications` |
| `requestPermission(kind)` | جواب در رویداد `yekari:permission`، `yekari:speech` با `detail: {kind, granted}` |
| `getLocation(id)` | موقعیت فعلی از GPS/شبکهٔ گوشی (بدون سرویس گوگل)؛ مجوز را همان لحظه می‌خواهد، برای مکان‌یاب خاموش یا مجوز بسته‌شده دیالوگ تنظیمات نشان می‌دهد. جواب در `yekari:location`: `{id, ok, lat, lng, accuracy}` یا `{id, ok: false, error: denied\|disabled\|unavailable}` |
| `notify(title, body, path)` | اعلان محلی؛ لمسش `path` را باز می‌کند |
| `openSettings()` | تنظیمات اپ (وقتی مجوزی برای همیشه رد شده) |
| `openExternal(url)` | باز کردن در اپ/مرورگر بیرونی |
| `startSpeechRecognition()` | گفتار به متن فارسی؛ نتیجه در `yekari:speech` (`detail.text`) و `window.onSpeechResult(text)` |
| `startTracking(json)` | روشن‌کردن سرویس آنلاین: `{apiBase, token, interval}`؛ جواب در `yekari:tracking` |
| `stopTracking()` | خاموش‌کردن سرویس (آفلاین/خروج) |
| `setBars(surface, dark)` | خودکار صدا زده می‌شود؛ لازم نیست وب‌اپ بداند |

رویدادها روی `window`: `yekari:ready` (پل آماده)، `yekari:resume` / `yekari:pause` (اپ جلو/پشت آمد)،
`yekari:permission`، `yekari:tracking` (`detail: {running, reason?}`).

وب‌اپ برای بستن شیت/مودال با دکمهٔ برگشت می‌تواند `window.__yekariBack = () => { … return true }`
تعریف کند؛ `true` یعنی «خودم بستم، ناوبری نکن».

## آنلاین در پس‌زمینه (`services/`)

وقتی پیک در وب‌اپ آنلاین می‌شود، `stores/courier.ts` (ریپوی `yekari_courier`، `utils/native.ts`)
`startTracking` را صدا می‌زند؛ با آفلاین‌شدن یا خروج `stopTracking`. تا وقتی سرویس روشن است:

- اعلان ثابت «آنلاین هستی» (اندروید بدون آن اجازهٔ کار در پس‌زمینه نمی‌دهد).
- هر ۱۵ ثانیه آخرین موقعیت به `POST /courier/location` (از `LocationManager` خود اندروید، نه
  Google Play Services، تا روی گوشی‌های بدون سرویس گوگل هم کار کند).
- وقتی اپ جلوی چشم نیست، هر ۸ ثانیه `GET /courier/current`؛ پیشنهاد تازه → اعلان پرصدا که با تمام
  شدن فرصت پذیرش خودش پاک می‌شود و لمسش `/courier` را باز می‌کند.
- پاسخ ۴۰۱ (خروج، ابطال توکن) → سرویس خاموش.

فقط مجوز موقعیت «هنگام استفاده» لازم است (سرویس وقتی اپ باز است روشن می‌شود)؛ `ACCESS_BACKGROUND_LOCATION`
عمداً خواسته نمی‌شود. توکن فقط در حافظهٔ سرویس است و فقط به میزبان‌های `trustedHosts` فرستاده می‌شود.
اگر اندروید اپ را کامل ببندد سرویس خودبه‌خود برنمی‌گردد (توکن ندارد)؛ با باز کردن دوبارهٔ اپ، وب‌اپ
دوباره روشنش می‌کند. اولین بار در هر اجرا که سرویس روشن می‌شود، اگر اپ از بهینه‌سازی باتری معاف
نباشد دیالوگ معافیت و (یک بار برای همیشه) راهنمای Autostart شیائومی/هواوی/اوپو/… نشان داده می‌شود
(`utils/BatteryGuide.kt`).

## هنوز نیست

- **اعلان push واقعی** (FCM/وب‌سوکت) — فعلاً پیشنهاد تازه با polling سرویس اعلان می‌شود و فقط وقتی
  پیک آنلاین است؛ تصمیم «سوکت به جای polling» در بک‌اند هنوز باز است.
- آیکون فعلی از `logo.svg` دیزاین‌سیستم ساخته شده؛ نسخهٔ نهایی طراح جایگزینش شود.
