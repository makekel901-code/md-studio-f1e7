# حالة Llama / GGUF في MD Studio

## الموجود الآن
- اختيار ملف GGUF عبر Android Storage Access Framework.
- حفظ URI للنموذج مع إذن القراءة المستمر.
- حقل لكتابة Prompt محلي.
- جسر `TermuxLlamaBridge` ينسخ النموذج إلى مجلد مشاركة يختاره المستخدم، ثم يرسل الطلب إلى `llama-cli` داخل Termux.
- سكريبت `scripts/setup-llama-termux.sh` يجهز المصدر ويبني `llama-cli` داخل Termux.
- استقبال مخرجات التنفيذ وعرض رد النموذج في تبويب المكتبة.

## حدود التنفيذ
هذا المسار **استدلال محلي فعلي عبر llama.cpp CLI داخل Termux** بعد تثبيت Termux وتجهيز النموذج وبناء المحرك. لكنه ليس JNI مدمجًا داخل عملية MD Studio، ولا يعمل قبل إعداد Termux. كما لم يُختبر على جهاز فعلي في بيئة إعداد هذه الحزمة.

## إعداد المحرك
1. جهز Termux وإذن `RUN_COMMAND` ومجلد المشاركة من تعليمات `docs/BUILD_ENGINES_PHASE3_AR.md`.
2. انسخ `scripts/setup-llama-termux.sh` إلى Termux ونفذه.
3. اختر ملف GGUF من MD Studio.
4. اختر مجلد المشاركة في مركز البناء.
5. افتح المكتبة، اكتب Prompt، ثم شغّل Llama محليًا.

يستخدم الجسر حاليًا إعدادًا محافظًا للسياق (`2048`) وحدًا أقصى للتوليد (`256` token) لتقليل ضغط الذاكرة. قد تحتاج النماذج الكبيرة إلى ذاكرة أكبر أو إعدادات أقل، وقد يوقف Android/Termux العملية إذا تجاوزت موارد الجهاز.

## المسار المستقبلي
يمكن لاحقًا استبدال CLI بربط Android JNI/Gradle مدمج. وثائق llama.cpp الرسمية تتضمن Android binding داخل `examples/llama.android`، وتشرح أيضًا تشغيل CLI داخل Termux. لم يتم بعد نقل Android binding إلى وحدة مستقلة داخل MD Studio.

المصدر الرسمي: https://github.com/ggml-org/llama.cpp/blob/master/docs/android.md
