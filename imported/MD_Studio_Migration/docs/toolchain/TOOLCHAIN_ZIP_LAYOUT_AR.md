# تجهيز حزمة أدوات البناء المحلية

MD Studio لا يضم JDK أو Gradle أو Android SDK داخل APK. من شاشة البناء يمكن استيراد ZIP لأدوات متوافقة مع Android والجهاز المستهدف.

بنية ZIP المطلوبة:

```text
mdstudio-toolchain/
  jdk/
    bin/java
  gradle/
    bin/gradle
  android-sdk/
    platforms/android-35/android.jar
    build-tools/35.0.0/aapt2
    platform-tools/adb        (اختياري)
```

يمكن وضع `jdk/` و`gradle/` و`android-sdk/` مباشرة في جذر ZIP بدل مجلد `mdstudio-toolchain/`.

ملاحظات:
- يجب أن تكون ملفات التنفيذ مبنية لمعمارية الجهاز (ABI) وقابلة للتشغيل من داخل تطبيق Android.
- قد تمنع بعض إصدارات Android تشغيل ملفات تنفيذية مستخرجة من ZIP بسبب قيود النظام أو SELinux؛ فشل التشغيل لا يعني بالضرورة تلف الأرشيف.
- لا تضع مفاتيح أو كلمات مرور أو ملفات توقيع داخل حزمة الأدوات.
- بعد استيراد مشروع Gradle ZIP، اضغط «بناء المشروع محليًا». لا تعتبر العملية ناجحة إلا إذا ظهر APK الناتج في المسار الذي يعرضه التطبيق.
