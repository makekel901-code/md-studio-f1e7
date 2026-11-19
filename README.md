# MD Studio (مستورد)

تم إنشاء هذا المشروع بواسطة AMD Studio.

## البناء
يتم البناء تلقائيًا عبر GitHub Actions (ملف `.github/workflows/android-build.yml`).
شغّل Workflow باسم "AMD Studio Build" ثم استلم ملف APK من مخرجات التشغيل (Artifacts).
الـ workflow يبني نسخة إصدار كاملة بالأمر `assembleRelease` لا نسخة تجريبية.

## التوقيع
التوقيع لا يعمل إلا بالأسرار (Secrets) الأربعة، بنفس الأسماء بالحرف:
- `ANDROID_KEYSTORE_BASE64`
- `ANDROID_KEYSTORE_PASSWORD`
- `ANDROID_KEY_ALIAS`
- `ANDROID_KEY_PASSWORD`

أضِفها من: Settings ← Secrets and variables ← Actions ← New repository secret.
لا تُقرأ أي متغيرات (Variables). لو نقص أي سر يفشل التشغيل برسالة صريحة بدلًا من
إخراج APK بتوقيع التطوير، ثم يتحقق الـ workflow من أن الشهادة داخل الـ APK هي نفس
شهادة المفتاح المرفوع.

من داخل AMD Studio: بطاقة «مفتاح توقيع هذا المشروع» ← «إضافة الأسرار الأربعة يدويًا»
تعرض كل قيمة بزر نسخ وزر لفتح صفحة الأسرار، مع قائمة تتبّع لما أضفته.

المطور: AMD Studio
