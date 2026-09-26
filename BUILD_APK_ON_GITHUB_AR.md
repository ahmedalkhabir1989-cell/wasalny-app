# وصلني V4.1.1 — بناء APK من GitHub

## 1) إنشاء Repository
- افتح GitHub وأنشئ Repository جديد، ويفضل Private.
- ارفع **محتويات هذا المجلد** إلى جذر الـ Repository.
- تأكد أن الملف `.github/workflows/build-apk.yml` موجود.

## 2) إعداد API_BASE (اختياري)
لو عايز تستخدم السيرفر الحالي، اتركه كما هو.
ولو عندك رابط Backend آخر:
Repository → Settings → Secrets and variables → Actions → New repository secret

Name: `API_BASE`
Value: رابط السيرفر بدون `/` في النهاية.

## 3) تشغيل البناء
من GitHub:
Actions → Build Wasalny APK → Run workflow

أو اعمل Push على فرع `main`.

## 4) تنزيل APK
بعد نجاح الـ workflow:
Actions → افتح آخر تشغيل ناجح → Artifacts → `wasalny-v4-apk`

نزّل الملف المضغوط واستخرج APK ثم ثبته على الموبايل.

## ملاحظات
- هذه نسخة Debug للتجربة.
- `minSdk 24`، أي Android 7 أو أحدث.
- Google Maps API Key اختياري؛ بدون مفتاح الخرائط قد لا تعمل الخريطة بشكل صحيح.
- الـ workflow يستخدم JDK 17 وGradle 8.2 وAndroid SDK.
