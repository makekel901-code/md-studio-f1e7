# MD Studio — AMD Studio

بيئة تطوير تطبيقات Android من الهاتف، تعتمد البناء السحابي عبر GitHub Actions لإنتاج APK دون الحاجة إلى JDK أو Gradle أو Android SDK على الهاتف.

## أهم الوظائف في هذه الحزمة

- إنشاء مشروع Kotlin/Jetpack Compose أساسي باسم وحزمة يحددهما المستخدم.
- تجهيز ملفات مصدر إضافية بالذكاء الاصطناعي عبر Gemini، مع عرض الملفات والملخص أولًا، ثم تطبيقها بعد موافقة واحدة.
- إدارة عدة مفاتيح Gemini مشفرة محليًا والتبديل المنضبط عند أخطاء الحصة/الخدمة.
- إدارة حسابات GitHub مشفرة محليًا، وإنشاء مستودع خاص تلقائيًا باسم المشروع عند الطلب، ثم رفع الملفات إلى فرع بناء مستقل، وتجهيز Workflow، وتشغيل GitHub Actions، ومتابعة الحالة وتنزيل APK Artifacts تلقائيًا.
- مستكشف ملفات، تحرير محدود، نسخ استعادة، بحث، وتشخيص بنية المشروع.
- مدير تنفيذ هندسي فعلي يجهز خطة من أدوات المشروع، يعرض الخطوات والمعاملات، وينتظر موافقة واحدة؛ يضيف نقطة استعادة قبل أول تعديل.
- بحث Maven Central وإضافة الاعتمادات مع نسخة احتياطية.
- فتح مثبت Android لتثبيت APK بموافقة المستخدم، ثم تشغيل الحزمة وجمع بيانات إصدارها.
- موصلات HTTPS JSON عامة قابلة للتهيئة لخدمات Runtime AI وRuntime Search وApp Backend وMultiplayer المملوكة للمستخدم.

## فحوص المصدر

يمكن تشغيل `scripts/validate-source.sh` للتحقق من XML وJSON وShell وملفات Workflow YAML عند توفر Ruby. هذه فحوص تركيبية وليست بديلًا عن Gradle build.

## البناء السحابي

المشروع يستخدم Android Gradle Plugin 8.11.1 وGradle 8.13 وJDK 17 في GitHub Actions. من مركز البناء يمكن اختيار «إنشاء مستودع خاص تلقائيًا ثم رفع المشروع وبناء APK»؛ يستمد MD Studio اسم المستودع من `rootProject.name`، ينشئ المستودع الخاص مع README ابتدائي، يرفع ملفات المشروع إلى فرع مستقل، يضيف Workflow إلى الفرع الافتراضي عند الحاجة، يشغّل الاختبارات والبناء، ويتابع التشغيل وينزّل APK. لا يحتاج الهاتف إلى JDK أو Gradle أو Android SDK. صلاحيات Fine-grained token: All repositories، وRepository creation: write (أو Administration: write)، وContents: write، وWorkflows: write، وActions: write، وMetadata: read. البديل Classic token: نطاقا `repo` و`workflow`. لا يطلب التطبيق صلاحية حذف المستودعات. Release يكون موقّعًا عند إعداد أسرار التوقيع، وإلا ينتج APK غير موقّع.

## متطلبات مزودات الخدمات

موصلات Runtime AI/Search وApp Backend وMultiplayer تستخدم عناوين HTTPS ومخططات JSON يحددها مالك الخدمة. لا توجد عناوين خوادم أو مفاتيح إنتاج مضمنة. يجب حفظ الرموز باستخدام Android Keystore وعدم إدخالها في ملفات المشروع أو السجلات.

## حدود مؤكدة

- APK PocketBuilder 0.145 محفوظ كمرجع تحليل فقط. لم يُنقل كود DEX الأصلي حرفيًا ولم يتوفر مشروع المصدر الأصلي.
- لا يمكن استنتاج قواعد الخادم أو محركاته الخلفية من APK وحده.
- مولد المشروع بالذكاء الاصطناعي مقيد بملفات Kotlin/Java وموارد XML وREADME، ولا يضيف اعتماديات أو أذونات جديدة تلقائيًا.
- اختبار تثبيت APK يفتح شاشة نظام Android ويحتاج تأكيد المستخدم؛ جمع بيانات الحزمة لا يعادل اختبارًا وظيفيًا آليًا أو تسجيل شاشة.
- لم يتم بناء هذا المصدر داخل بيئة إعداد الحزمة لعدم توفر Android SDK/Gradle محليًا؛ يجب تشغيل GitHub Actions أو البناء على Termux للتحقق النهائي.

## هوية المنتج

اسم التطبيق: MD Studio.
اسم الاستوديو المطوّر: AMD Studio.
AI Studio ليس اسم المطور أو هوية شهادة التوقيع.


## alpha08 — AMD Studio identity and core settings

- MD Studio remains the Android development product; AMD Studio remains the developer/studio identity; AI Studio is only a tool name.
- Added adaptive launcher branding, persistent system/light/dark theme selection, and a truthful engine-status center in Settings.
- Added an explicit migration matrix for the original AMD Studio core capabilities and remaining gaps: `docs/AMD_STUDIO_CORE_MIGRATION_AR.md`.
- This is a source update. A successful Android Gradle build and device installation are still required to validate the new UI and launcher icon.

## alpha09 — إدارة دورة حياة المشروعات

- حالات المشروع وسجل أحداث محلي دائم، مع عرض تاريخ المشروع من بطاقة المشروع.
- تسجيل بدء البناء ونتيجته الفعلية في السجل.
- تصدير نسخة ZIP احتياطية من شاشة المشروعات؛ يستبعد التصدير الأسرار وملفات التوقيع وملفات البيئة وملفات APK/AAB ومخرجات البناء.
- المدير الهندسي متعدد الوكلاء ومزودات الذكاء الاصطناعي الإضافية وخدمات Backend الإنتاجية ما زالت مجالات استكمال وليست قدرات مكتملة.

## أوضاع الذكاء الاصطناعي (تحديث Alpha 09)

يحتوي هذا التحديث على ثلاثة أوضاع تستخدم مفاتيح Google AI نفسها ومحركات MD Studio نفسها: عادي اقتصادي ومحليّ التنفيذ قدر الإمكان، قوي بتحليل ومراجعة موجهة، واحترافي بتدخل مباشر أعمق ودورات إصلاح محدودة. الوضع العادي لا يُحرم من قدرات إنشاء المشروعات أو البرمجة أو البناء أو الاختبار. يتيح القوي والاحترافي ميزانيات استهلاك اقتصادية/متوازنة/مكثفة، مع عداد للطلبات ورموز الإدخال والإخراج. التفاصيل والحدود موضحة في `docs/AI_OPERATING_MODES_AR.md`.
