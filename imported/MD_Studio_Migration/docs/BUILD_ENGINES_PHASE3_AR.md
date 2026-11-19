# MD Studio — المرحلة الثالثة: محركا البناء المحلي والسحابي

## ما أُضيف في هذه المرحلة

### 1) البناء المحلي عبر Termux
- إضافة جسر `TermuxBuildBridge` لإرسال مشروع Gradle إلى مجلد مشترك يختاره المستخدم عبر Android Storage Access Framework.
- تصدير ملفات المشروع إلى ZIP مع استبعاد `.gradle` و`.git` ومجلدات `build` و`.idea` ومرجع APK القديم.
- تشغيل Gradle داخل Termux عبر `com.termux.app.RunCommandService`، واستقبال نتيجة التنفيذ عبر `TermuxResultService`.
- نسخ APK الناتج من مجلد المشاركة إلى مساحة MD Studio الخاصة، ثم إتاحته للتصدير.
- دعم `Debug` و`Release` غير الموقّع.
- إضافة `scripts/setup-termux-mdstudio.sh` لتجهيز JDK 21 وAndroid SDK وأدوات Termux وGradle 8.13.
- استخدام AAPT2 الأصلي الخاص بـ Termux من خلال `android.aapt2FromMavenOverride` بدل افتراض أن ملف AAPT2 المكتبي سيعمل على Android.

### إعداد Termux لمرة واحدة
1. ثبّت إصدار Termux حديثًا من مصدر موثوق، وافتحه مرة واحدة.
2. انسخ ملف `scripts/setup-termux-mdstudio.sh` إلى Termux أو افتح مجلد المشروع من Termux، ثم نفّذ:
   `bash scripts/setup-termux-mdstudio.sh`
3. أعد تشغيل Termux بعد الإعداد.
4. من إعدادات Android امنح MD Studio الإذن الإضافي `Run commands in Termux environment`.
5. داخل Termux يجب أن تكون خاصية `allow-external-apps=true` مفعلة. سكريبت الإعداد يضيفها إلى `~/.termux/termux.properties`.
6. داخل MD Studio استورد مشروع Gradle، واختر مجلدًا من الذاكرة الداخلية المشتركة، ثم شغّل البناء عبر Termux.

> ملاحظات: يتطلب المسار اتصال إنترنت أول مرة لتنزيل Gradle/SDK والتبعيات. نجاحه يعتمد على توافق حزم Termux المتاحة ومعمارية الجهاز ومساحة التخزين والذاكرة. لم يُختبر على هاتف فعلي في بيئة إعداد هذه الحزمة.

### 2) البناء السحابي عبر GitHub Actions
- Workflow يدعم `workflow_dispatch` بخيار `debug` أو `release`.
- خيار لتشغيل اختبارات Unit Tests قبل البناء.
- رفع APK الناتج كـ Actions Artifact باسم واضح.
- عرض تشغيلات Workflow داخل التطبيق.
- قراءة Artifacts الخاصة بكل تشغيل.
- رفع المشروع الحالي إلى فرع مستقل جديد عبر Git Data API (Blobs/Tree/Commit/Ref)، مع استبعاد ملفات البناء والأسرار وAPK وGGUF ومرجع APK القديم.
- تشغيل Workflow `mdstudio-build.yml` على الفرع الجديد بعد الرفع. إذا لم يكن Workflow موجودًا في الفرع الافتراضي، يضيف MD Studio ملف Workflow المخصص إلى الفرع الافتراضي فقط؛ ملفات المشروع نفسها تبقى على فرع `mdstudio/build-*`.
- تنزيل Artifact ZIP واستخراج APK داخله إلى مساحة التطبيق الخاصة.
- تصدير APK إلى مجلد يختاره المستخدم عبر Android Storage Access Framework.

### 3) Llama المحلي عبر Termux
- إضافة `TermuxLlamaBridge` لنسخ نموذج GGUF المختار إلى مجلد المشاركة.
- إضافة حقل Prompt في تبويب المكتبة وإرسال الاستدلال إلى `llama-cli` داخل Termux.
- إضافة `scripts/setup-llama-termux.sh` لاستنساخ llama.cpp وبناء CLI محليًا.
- استقبال النص الناتج وعرضه داخل MD Studio.
- الإعداد الحالي محافظ: سياق 2048 وتوليد حتى 256 token.
- هذا محرك CLI يعمل داخل Termux، وليس JNI مدمجًا داخل APK. لم يُختبر على جهاز فعلي بعد.

### 4) التوقيع
- إعداد Gradle لقبول هوية توقيع Release من متغيرات البيئة، دون وضع ملف keystore أو كلمات المرور داخل المصدر.
- في GitHub أضف الأسرار التالية عند تجهيز شهادة AMD Studio:
  - `MDSTUDIO_KEYSTORE_BASE64`
  - `MDSTUDIO_KEYSTORE_PASSWORD`
  - `MDSTUDIO_KEY_ALIAS`
  - `MDSTUDIO_KEY_PASSWORD`
- إذا لم تُضف الأسرار، ينتج Release غير موقّع. لا توجد شهادة توقيع مضمّنة أو مخترعة في المشروع.

### 5) مساحة العمل والتشخيص
- مستكشف ملفات للمشروع الحالي.
- محرر نصي محدود لملفات Kotlin وJava وXML وGradle وProperties وJSON وYAML وMarkdown وغيرها من الامتدادات النصية المحددة.
- منع تحرير مجلدات `.git` و`.gradle` و`build`، ومنع تجاوز جذر المشروع.
- حد قراءة/حفظ الملف 512 KB.
- فحص بنيوي للمشروع يعرض PASS/FAIL/WARN، ولا يدّعي أنه اختبار تشغيل.

## ما لم يكتمل بعد
- لم يتم نقل جميع وظائف ومحركات PocketBuilder من DEX؛ APK لا يحتوي مشروع المصدر الأصلي.
- محرك البناء المحلي المباشر داخل عملية MD Studio يظل مشروطًا بحزمة JDK/Gradle/SDK مبنية لتعمل على Android. المسار المحلي الموجه للهواتف الآن هو Termux.
- لم يتم دمج llama.cpp كـ Android JNI/Gradle library؛ الاستدلال المحلي المتاح في المصدر يمر عبر Termux CLI ويتطلب إعدادًا خارجيًا واختبارًا على الجهاز.
- لم يتم بناء APK من هذه البيئة أو اختباره على جهاز Android. يجب تنفيذ البناء من GitHub Actions أو Termux ثم اختبار الناتج.

## مراجع تقنية
- Termux RUN_COMMAND: https://github.com/termux/termux-app/wiki/RUN_COMMAND-Intent
- llama.cpp Android: https://github.com/ggml-org/llama.cpp/blob/master/docs/android.md
