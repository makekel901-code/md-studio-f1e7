# MD Studio — تسليم المرحلة الثالثة

## الإضافات

### البناء المحلي
- مسار Termux RUN_COMMAND يصدّر المشروع إلى مجلد مشترك يختاره المستخدم، ثم ينفذ `:app:assembleDebug` أو `:app:assembleRelease` باستخدام JDK 21 وGradle 8.13 وAndroid SDK.
- يستقبل التطبيق stdout/stderr وexit code عبر PendingIntent/IntentService، ثم ينسخ APK الناتج إلى مساحة التطبيق.
- إعداد Termux موثق في `scripts/setup-termux-mdstudio.sh`.

### البناء السحابي
- رفع Snapshot المشروع إلى فرع مستقل `mdstudio/build-*` عبر GitHub Git Database API.
- استبعاد ملفات التوقيع والأسرار والنماذج وملفات APK وملفات البناء من الرفع.
- إضافة Workflow مخصص `mdstudio-build.yml` إلى الفرع الافتراضي عند غيابه أو عدم احتوائه على مدخلات `workflow_dispatch` المطلوبة.
- تشغيل البناء على فرع المشروع الجديد، ثم عرض تشغيلات كلا Workflow الأصلي والمخصص وتنزيل APK من Artifacts.
- دعم Debug وRelease واختبارات Unit Tests. Release يظل غير موقّع ما لم تُضف أسرار شهادة التوقيع.

### الذكاء الاصطناعي المحلي
- اختيار GGUF، ثم تصديره إلى مجلد Termux المشترك.
- إرسال Prompt إلى `llama-cli` المبني عبر `scripts/setup-llama-termux.sh` واستقبال الرد داخل MD Studio.
- المسار CLI داخل Termux وليس JNI مدمجًا داخل APK.

### مساحة العمل
- مستكشف ملفات، محرر نصي محدود، وحماية من الخروج عن جذر المشروع.
- تشخيص بنيوي يوضح الملفات الأساسية ومصادر Kotlin/Java.

## الفحوص المنفذة في بيئة إعداد الحزمة
- AndroidManifest XML: ناجح.
- سكريبت إعداد Termux وLlama (`bash -n`): ناجح.
- `ProjectWorkspaceManager.kt` ترجمة Kotlin مستقلة: ناجحة.
- فحص parsing لملفات Kotlin: لم تظهر أخطاء `expecting` أو أخطاء تركيب صريحة. الترجمة الكاملة لم تكتمل لأن Android SDK وAndroidX/Compose وOkHttp dependencies غير متاحة في بيئة إعداد ZIP.
- GitHub Workflow وWorkflow النصي المولد: تحليل YAML مبدئي ناجح؛ أداة PyYAML تستخدم YAML 1.1 وتعرض المفتاح `on` كقيمة Boolean، بينما GitHub Actions يستخدم صيغة YAML الخاصة به.

## لم يتم تنفيذه أو اختباره
- لم يتم تشغيل Gradle build أو إنتاج APK من هذه البيئة.
- لم يتم اختبار Termux RUN_COMMAND أو البناء على هاتف حقيقي.
- لم يتم اختبار رفع مشروع إلى GitHub بحساب حقيقي أو تشغيل Workflow/تنزيل Artifact.
- لم يتم اختبار تشغيل نموذج GGUF على جهاز فعلي.
- لم يتم نقل كل وظائف PocketBuilder 0.145؛ APK المرجعي ليس مشروع المصدر الأصلي.
