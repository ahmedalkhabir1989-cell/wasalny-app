# وصلني — بناء APK من GitHub

## قبل البناء

1. تأكد من إعداد Firebase وFirestore حسب [FIRESTORE_SETUP_AR.md](FIRESTORE_SETUP_AR.md).
2. أضف سر `MAPS_API_KEY` في GitHub Actions لاستخدام خرائط Google.

## البناء والتنزيل

ادفع إلى `main` أو شغّل **Build Wasalny APK** يدوياً من Actions. بعد نجاح البناء نزّل artifact باسم `Wasalny-APK`.

يتطلب البناء JDK 17 وGradle Wrapper 8.7. أقل إصدار مدعوم Android 7 (API 24).
