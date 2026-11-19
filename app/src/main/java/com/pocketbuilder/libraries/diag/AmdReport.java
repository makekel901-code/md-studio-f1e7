package com.pocketbuilder.libraries.diag;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageInfo;
import android.os.Build;
import android.os.Bundle;
import android.util.DisplayMetrics;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * أداة تقرير مخفية. مالهاش أيقونة ولا زر: AMD Studio بيفتحها بأمر صريح ويقرا
 * المشاكل من هنا. كل سطر «مشكلة» مقروء من النظام فعلًا، وكل «إصلاح» مكتوب جنبه.
 */
public final class AmdReport extends Activity {

    public static final String BEGIN = "AMDSTUDIO-REPORT-BEGIN";
    public static final String END = "AMDSTUDIO-REPORT-END";
    private static final String PREFS = "amd_self_report";
    private static Context appContext;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        String text = build(this);
        save(this, text);
        ScrollView scroll = new ScrollView(this);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        float d = getResources().getDisplayMetrics().density;
        int pad = (int) (14f * d);
        col.setPadding(pad, pad, pad, pad);
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(11f);
        view.setTextColor(0xFFE8EDF5);
        view.setTextIsSelectable(true);
        col.addView(view);
        scroll.addView(col);
        scroll.setBackgroundColor(0xFF0B0F16);
        setContentView(scroll);
    }

    /** يسجّل أي انهيار حقيقي في التطبيق عشان يظهر في التقرير الجاي. */
    public static void install(Context c) {
        if (c == null) {
            return;
        }
        if (appContext == null) {
            appContext = c.getApplicationContext();
        }
        try {
            final Thread.UncaughtExceptionHandler old =
                    Thread.getDefaultUncaughtExceptionHandler();
            Thread.setDefaultUncaughtExceptionHandler(
                    new Thread.UncaughtExceptionHandler() {
                public void uncaughtException(Thread t, Throwable e) {
                    try {
                        recordCrash(e);
                    } catch (Throwable ignored) {
                    }
                    if (old != null) {
                        old.uncaughtException(t, e);
                    }
                }
            });
        } catch (Throwable ignored) {
        }
    }

    private static void recordCrash(Throwable e) {
        if (appContext == null || e == null) {
            return;
        }
        String line = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
                .format(new Date()) + " — " + e.getClass().getName()
                + (e.getMessage() == null ? "" : ": " + e.getMessage());
        try {
            prefs(appContext).edit().putString("last_crash", line).apply();
        } catch (Throwable ignored) {
        }
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static void save(Context c, String text) {
        try {
            prefs(c).edit().putString("last_report", text).apply();
        } catch (Throwable ignored) {
        }
    }

    public static String last(Context c) {
        try {
            String v = prefs(c).getString("last_report", "");
            return v == null ? "" : v;
        } catch (Throwable t) {
            return "";
        }
    }

    /** التقرير الكامل: حقائق النظام أولًا، وبعدها المشاكل وإصلاحها. */
    public static String build(Context c) {
        StringBuilder sb = new StringBuilder();
        sb.append(BEGIN).append("\n");
        String pkg = c.getPackageName();
        sb.append("الحزمة: ").append(pkg).append("\n");
        sb.append("الإصدار: ").append(versionName(c)).append("\n");
        sb.append("أندرويد: ").append(Build.VERSION.RELEASE)
                .append(" (API ").append(Build.VERSION.SDK_INT).append(")\n");
        sb.append("الجهاز: ").append(Build.MANUFACTURER).append(" ")
                .append(Build.MODEL).append("\n");
        DisplayMetrics dm = c.getResources().getDisplayMetrics();
        sb.append("الشاشة: ").append(Math.round(dm.widthPixels / dm.density))
                .append("x").append(Math.round(dm.heightPixels / dm.density))
                .append(" dp • كثافة ").append((int) dm.density).append("\n");
        sb.append("اللغة: ").append(Locale.getDefault().toString()).append("\n\n");
        ActivityInfo[] acts = activities(c);
        sb.append("الشاشات المسجّلة: ").append(acts.length).append("\n");
        for (int i = 0; i < acts.length; i++) {
            String name = acts[i].name;
            boolean exists = classExists(name, pkg);
            sb.append("• ").append(shortName(name))
                    .append(exists ? " — الصنف موجود" : " — الصنف مش موجود")
                    .append("\n");
        }
        sb.append("\n");
        boolean launcher = false;
        try {
            launcher = c.getPackageManager().getLaunchIntentForPackage(pkg) != null;
        } catch (Throwable ignored) {
        }
        boolean storage = storageWorks(c);
        String crash = "";
        try {
            crash = prefs(c).getString("last_crash", "");
        } catch (Throwable ignored) {
            crash = "";
        }
        if (crash == null) {
            crash = "";
        }
        sb.append("شاشة البداية: ").append(launcher ? "موجودة" : "مش موجودة")
                .append("\n");
        sb.append("التخزين: ").append(storage ? "بيكتب ويقرا" : "مش شغال")
                .append("\n");
        sb.append("آخر انهيار: ").append(crash.length() == 0 ? "مفيش" : crash)
                .append("\n\n");
        int problems = 0;
        StringBuilder fix = new StringBuilder();
        if (!launcher) {
            problems++;
            fix.append("• مفيش شاشة بداية: التطبيق مش هيفتح من قائمة التطبيقات. ")
                    .append("الحل: شاشة رئيسية واحدة بفلتر MAIN وLAUNCHER.\n");
        }
        for (int i = 0; i < acts.length; i++) {
            if (!classExists(acts[i].name, pkg)) {
                problems++;
                fix.append("• الشاشة ").append(shortName(acts[i].name))
                        .append(" مسجّلة في المانيفست والصنف مش موجود — ")
                        .append("التطبيق هيتوقف لما حد يفتحها. الحل: اكتب الصنف ")
                        .append("أو شيل الوسم من المانيفست.\n");
            }
        }
        if (!storage) {
            problems++;
            fix.append("• التخزين مش بيكتب: أي حفظ بيانات هيفشل. ")
                    .append("الحل: استخدم SharedPreferences بسيطة بدل مسار ملفات ممنوع.\n");
        }
        if (crash.length() > 0) {
            problems++;
            fix.append("• فيه انهيار مسجّل: ").append(crash)
                    .append(" — الحل: راجع الشاشة اللي حصل فيها الانهيار ")
                    .append("وأضف التحقق من البيانات الفارغة.\n");
        }
        if (acts.length <= 1) {
            fix.append("• التطبيق فيه شاشة واحدة مسجّلة — لو المفروض فيه شاشات ")
                    .append("تانية، سجّلها في المانيفست وضيف طريق للوصول ليها.\n");
        }
        sb.append("المشاكل: ").append(problems).append("\n");
        if (fix.length() == 0) {
            sb.append("• مفيش مشكلة قابلة للتحقق الآن.\n");
        } else {
            sb.append(fix);
        }
        sb.append(END).append("\n");
        return sb.toString();
    }

    private static String versionName(Context c) {
        try {
            PackageInfo info = c.getPackageManager().getPackageInfo(
                    c.getPackageName(), 0);
            return info.versionName + " (" + info.versionCode + ")";
        } catch (Throwable t) {
            return "غير معروف";
        }
    }

    private static ActivityInfo[] activities(Context c) {
        try {
            PackageInfo info = c.getPackageManager().getPackageInfo(
                    c.getPackageName(),
                    android.content.pm.PackageManager.GET_ACTIVITIES);
            if (info != null && info.activities != null) {
                return info.activities;
            }
        } catch (Throwable ignored) {
        }
        return new ActivityInfo[0];
    }

    private static boolean classExists(String name, String pkg) {
        if (name == null) {
            return false;
        }
        String full = name.startsWith(".") ? pkg + name : name;
        try {
            Class.forName(full);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static String shortName(String name) {
        if (name == null) {
            return "?";
        }
        int dot = name.lastIndexOf('.');
        return dot >= 0 ? name.substring(dot + 1) : name;
    }

    private static boolean storageWorks(Context c) {
        try {
            String probe = "ok" + System.currentTimeMillis();
            prefs(c).edit().putString("probe", probe).commit();
            String back = prefs(c).getString("probe", "");
            return probe.equals(back);
        } catch (Throwable t) {
            return false;
        }
    }
}
