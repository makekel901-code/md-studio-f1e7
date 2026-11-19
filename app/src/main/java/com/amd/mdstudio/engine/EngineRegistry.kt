package com.amd.mdstudio.engine

import com.amd.mdstudio.engine.core.EngineDescriptor

/** Source-derived capability map plus MD Studio's clean-room implementation state. */
object EngineRegistry {
    val all = listOf(
        EngineDescriptor("agent", "مدير التنفيذ الهندسي", "AdkCodingAgent / AgentSteering / AgentProgressService", "واجهة فعلية لتخطيط أدوات MD Studio وعرض الخطوات ومعاملاتها ثم تنفيذها بعد موافقة واحدة؛ يضيف نقطة استعادة قبل أول تعديل. تنسيق فرق وكلاء مستقلين غير متاح بعد", EngineDescriptor.Status.IMPLEMENTED),
        EngineDescriptor("project-lifecycle", "إدارة دورة حياة المشروع", "ProjectCatalog lifecycle journal", "حالات موحدة للمشروع وسجل أحداث محلي دائم وفتح المشروع ومتابعة نتائج البناء", EngineDescriptor.Status.IMPLEMENTED),
        EngineDescriptor("project-backup", "نسخ المشروعات واستعادتها", "ProjectArchiveManager", "تصدير ZIP يستبعد الأسرار ومفاتيح التوقيع وملفات البناء والملفات الثنائية، واستيراد آمن مع منع Zip Slip", EngineDescriptor.Status.IMPLEMENTED),
        EngineDescriptor("workspace", "محرك مساحة العمل", "WorkspaceAdkTools + ReadFileTool + WriteFileTool + PatchFileTool", "قراءة وكتابة وتعديل وبحث ونقاط استعادة وتشخيص", EngineDescriptor.Status.IMPLEMENTED),
        EngineDescriptor("project-scaffolder", "منشئ مشاريع Android", "ProjectGenerator / AndroidProjectTemplate", "إنشاء مشروع Kotlin/Compose حقيقي بملفات Gradle وManifest وMainActivity", EngineDescriptor.Status.IMPLEMENTED),
        EngineDescriptor("build", "محرك البناء", "GitHubWorkflowBuildProvider + GitHubClient", "إنشاء مستودع خاص تلقائيًا، ورفع المشروع إلى فرع مستقل، وتجهيز Workflow وتشغيله ومتابعته وتنزيل APK عبر GitHub Actions", EngineDescriptor.Status.IMPLEMENTED, "يتطلب ربط GitHub برمز يملك صلاحيات إنشاء المستودع والكتابة إلى المحتوى وWorkflow وتشغيل Actions"),
        EngineDescriptor("runtime-test", "اختبار التطبيق وتشغيله", "AdkRuntimeTester + RunRuntimeTestsTool + LaunchRuntimeAppTool", "فحص الجهاز وفتح مثبت Android بموافقة المستخدم وتشغيل الحزمة المثبتة وجمع بيانات إصدارها", EngineDescriptor.Status.IMPLEMENTED, "التثبيت يتطلب تأكيد المستخدم؛ جمع الأدلة لا يساوي اختبار وظائف آلي أو تسجيل شاشة"),
        EngineDescriptor("libraries", "مكتبة الاعتمادات", "SearchLibrariesTool + InstallLibraryTool + GetLibraryDetailsTool", "بحث مباشر في Maven Central وإضافة اعتماد إلى Gradle مع نسخة احتياطية", EngineDescriptor.Status.IMPLEMENTED),
        EngineDescriptor("web-search", "البحث والقراءة على الويب", "SearchWebTool + ReadWebPageTool", "موصل بحث وقراءة صفحات قابل للربط بمزود", EngineDescriptor.Status.REQUIRES_PROVIDER),
        EngineDescriptor("app-backend", "محرك App Backend", "EnableAppBackendTool + TestAppBackendTool + PublishAppBackendTool", "موصل HTTPS JSON عام مع مسارات health وpublish قابلة للتهيئة", EngineDescriptor.Status.ADAPTER_READY, "يلزم إعداد خادم مملوك للمستخدم ومخطط API الخاص به"),
        EngineDescriptor("multiplayer", "محرك Multiplayer", "EnableMultiplayerTool + TestMultiplayerServerTool + PublishMultiplayerRulesTool", "موصل HTTPS JSON عام لفحص خادم Multiplayer ونشر قواعد المشروع", EngineDescriptor.Status.ADAPTER_READY, "يلزم إعداد خادم مملوك للمستخدم ومخطط API الخاص به"),
        EngineDescriptor("runtime-ai", "ذكاء اصطناعي داخل التطبيقات", "RuntimeAiProvider + EnableRuntimeAiTool", "موصل HTTPS JSON قابل للتهيئة لخدمة Runtime AI يملكها المستخدم", EngineDescriptor.Status.ADAPTER_READY, "يلزم عنوان endpoint ومخطط JSON ومفتاح الخدمة من مالكها"),
        EngineDescriptor("runtime-search", "بحث داخل التطبيقات المنشأة", "RuntimeSearchProvider + EnableRuntimeSearchTool", "موصل HTTPS JSON قابل للتهيئة لخدمة Runtime Search يملكها المستخدم", EngineDescriptor.Status.ADAPTER_READY, "يلزم عنوان endpoint ومخطط JSON ومفتاح الخدمة من مالكها"),
        EngineDescriptor("local-models", "نماذج الذكاء الاصطناعي المحلية", "CompressedGemini / SessionAwareAdkModel / recovery models", "تشغيل GGUF عبر llama.cpp داخل Termux مع جسر استدعاء من التطبيق", EngineDescriptor.Status.ADAPTER_READY, "JNI مدمج غير موجود بعد؛ يحتاج إعداد Termux والنموذج"),
        EngineDescriptor("request-recovery", "استعادة الطلبات والسياق", "AdkRequestRecovery + ContextRecoveryModel + OutputLimitRecoveryModel", "إطار استعادة قابل للربط بموفر الذكاء الاصطناعي", EngineDescriptor.Status.ADAPTER_READY),
        EngineDescriptor("device-info", "معلومات الجهاز", "DeviceInfoTools + GetDeviceInfoTool", "قراءة إصدار Android والمعمارية والذاكرة والمعالجات", EngineDescriptor.Status.IMPLEMENTED),
        EngineDescriptor("image-tools", "أدوات الصور والأيقونات", "GenerateAppIconTool / GenerateImageTool / EditImageTool", "لم يتم نقلها ضمن هذه الدفعة", EngineDescriptor.Status.NOT_PORTABLE_FROM_APK, "لا يوجد سورس أصلي أو تكامل مرخص محدد"),
        EngineDescriptor("billing", "الفوترة والترخيص", "account usage / AI tasks / payments route strings", "لا يتم نسخ أو تعطيل فوترة طرف ثالث؛ يلزم API/خادم مملوك أو استحقاق رسمي", EngineDescriptor.Status.NOT_PORTABLE_FROM_APK)
    )
}
