# وصلني — Build APK من GitHub (V4.1.1)

## قبل ما ترفع

1. السيرفر على Render شغال (`/health` يرجع ok)
2. جهّز لينك السيرفر، مثال: `https://wasalny-xxxx.onrender.com`

## رفع الكود

ارفع **محتويات** المجلد `android_app_V4_GitHub_Ready` لجذر الريبو (مش المجلد نفسه كـ subfolder).

## Secrets (GitHub → Settings → Secrets → Actions)

| Name | مطلوب؟ | الوصف |
|------|--------|--------|
| `API_BASE` | موصى به | لينك السيرفر بدون `/` في الآخر |
| `MAPS_API_KEY` | اختياري | مفتاح Google Maps (من غيره الخريطة رمادي) |

## Build

- ادفع على `main` أو شغّل workflow يدوياً من Actions
- بعد 3–6 دقايق: Artifacts → `wasalny-v4-apk`
- أو من Releases

## تغيير لينك السيرفر بدون ما تعدّل الكود

Secret `API_BASE` بيتقرأ وقت الـ build ويتسجل في `BuildConfig.API_BASE`.

بديل: في `app/build.gradle.kts` غيّر السطر الافتراضي لـ `API_BASE`.

## ملاحظات استقرار

- أول طلب بعد نوم السيرفر ممكن يطول — التطبيق بيستنى ويعيد المحاولة
- لو فشل الاتصال: استخدم زر **SMS بدون نت**
- minSdk 24 (أندرويد 7+)
