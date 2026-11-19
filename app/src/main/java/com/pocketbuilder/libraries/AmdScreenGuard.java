package com.pocketbuilder.libraries;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Application;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.InputType;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/**
 * AMD Studio — حارس الشاشات.
 *
 * <p>المشكلة اللي بيمنعها: التطبيق المتولّد كان بيتثبت وبيفتح على شاشة بيضاء فاضية. الحارس ده
 * بيتوصّل قبل أي شاشة، وبعد ما الشاشة ترسم بيتأكد إن جواها محتوى حقيقي (حقل أو زر أو قائمة أو
 * نصين على الأقل). لو لقاها فاضية فعلًا، بيبني فيها شاشة كاملة شغالة: اسم التطبيق، وأزرار كل
 * شاشات التطبيق اللي في المانيفست، ودفتر حفظ حقيقي بيعرض المحفوظ فورًا ويحذفه — بدل الشاشة
 * البيضاء.</p>
 *
 * <p>قواعد الأمان: ما بيلمسش أي شاشة فيها محتوى (لازم محتوى أقل من عنصرين عشان يتدخّل)، وبيتجاهل
 * شاشات البداية السريعة (Splash/Welcome/Intro)، والتنقل بيستخدم Class.forName فما يقدرش يكسر
 * البناء لو شاشة اتغيّر اسمها.</p>
 */
public final class AmdScreenGuard {

    public static final String MARKER = "amd_screen_guard";

    private static final String PREFS = "amd_guard_store";
    private static final String KEY = "items";

    /** كل شاشات التطبيق: الاسم الظاهر ثم اسم الصنف الكامل. */
    private static final String[][] SCREENS = new String[][]{
            { "Main", "com.pocketbuilder.libraries.MainActivity" },
            { "AMD Report", "com.pocketbuilder.libraries.diag.AmdReport" }
    };

    private AmdScreenGuard() {
    }

    /** يتنادى مرة واحدة من AmdScreenGuardInit. */
    public static void watch(Application app) {
        if (app == null) {
            return;
        }
        try {
            app.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
                public void onActivityCreated(Activity activity, Bundle saved) {
                    schedule(activity);
                }

                public void onActivityStarted(Activity activity) {
                }

                public void onActivityResumed(Activity activity) {
                }

                public void onActivityPaused(Activity activity) {
                }

                public void onActivityStopped(Activity activity) {
                }

                public void onActivitySaveInstanceState(Activity activity, Bundle saved) {
                }

                public void onActivityDestroyed(Activity activity) {
                }
            });
        } catch (Throwable ignored) {
        }
    }

    /** فحص أول بعد الرسم، وفحص تاني متأخر — عشان الشاشة اللي بتتبني بالكود تاخد فرصتها. */
    private static void schedule(final Activity activity) {
        if (activity == null || activity.getWindow() == null) {
            return;
        }
        final View root = activity.getWindow().getDecorView();
        if (root == null) {
            return;
        }
        try {
            root.post(new Runnable() {
                public void run() {
                    inspect(activity, false);
                }
            });
            root.postDelayed(new Runnable() {
                public void run() {
                    inspect(activity, true);
                }
            }, 900L);
        } catch (Throwable ignored) {
        }
    }

    private static void inspect(Activity activity, boolean mayFill) {
        try {
            if (activity == null || activity.isFinishing() || activity.getWindow() == null) {
                return;
            }
            View root = activity.getWindow().getDecorView();
            if (root == null || marked(root, 0)) {
                return;
            }
            if (score(root, 0) >= 2) {
                return;
            }
            if (!mayFill || isTransient(activity)) {
                return;
            }
            fill(activity);
        } catch (Throwable ignored) {
        }
    }

    /** شاشات البداية السريعة مش «فاضية» — دي بتعدّي بسرعة، فما بنلمسهاش. */
    private static boolean isTransient(Activity activity) {
        try {
            String name = activity.getClass().getSimpleName().toLowerCase();
            return name.indexOf("splash") >= 0 || name.indexOf("welcome") >= 0
                    || name.indexOf("intro") >= 0 || name.indexOf("launcher") >= 0;
        } catch (Throwable error) {
            return false;
        }
    }

    /** هل الشاشة دي اتملت بالحارس قبل كده؟ */
    private static boolean marked(View view, int depth) {
        if (view == null || depth > 30) {
            return false;
        }
        try {
            Object tag = view.getTag();
            if (tag != null && MARKER.equals(tag.toString())) {
                return true;
            }
            if (view instanceof ViewGroup) {
                ViewGroup group = (ViewGroup) view;
                for (int i = 0; i < group.getChildCount(); i++) {
                    if (marked(group.getChildAt(i), depth + 1)) {
                        return true;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    /** عدد عناصر المحتوى الحقيقية: نص مكتوب، زر، حقل، قائمة، صورة. */
    private static int score(final View view, int depth) {
        if (view == null || depth > 30) {
            return 0;
        }
        int total = 0;
        try {
            String name = view.getClass().getName();
            if (name.endsWith("ListView") || name.endsWith("RecyclerView")
                    || name.endsWith("GridView") || name.endsWith("WebView")
                    || name.endsWith("SurfaceView") || name.endsWith("ViewPager")) {
                total += 2;
            } else if (view instanceof EditText) {
                total += 1;
            } else if (view instanceof Button) {
                total += 1;
            } else if (view instanceof TextView) {
                final CharSequence text = ((TextView) view).getText();
                if (text != null && text.toString().trim().length() >= 2) {
                    total += 1;
                }
            } else if (view instanceof android.widget.ImageView) {
                total += 1;
            } else if (view instanceof android.widget.Spinner
                    || view instanceof android.widget.CheckBox
                    || view instanceof android.widget.Switch) {
                total += 1;
            }
            if (view instanceof ViewGroup) {
                ViewGroup group = (ViewGroup) view;
                for (int i = 0; i < group.getChildCount(); i++) {
                    total += score(group.getChildAt(i), depth + 1);
                }
            }
        } catch (Throwable ignored) {
        }
        return total;
    }

    /** الشاشة البديلة: عنوان، وأزرار كل شاشات التطبيق، ودفتر حفظ حقيقي. */
    private static void fill(final Activity activity) {
        String appName = "التطبيق";
        try {
            CharSequence label = activity.getApplicationInfo()
                    .loadLabel(activity.getPackageManager());
            if (label != null && label.length() > 0) {
                appName = label.toString();
            }
        } catch (Throwable ignored) {
        }
        ScrollView scroll = new ScrollView(activity);
        scroll.setTag(MARKER);
        scroll.setBackgroundColor(Color.parseColor("#0B1220"));
        scroll.setFillViewport(true);

        LinearLayout column = new LinearLayout(activity);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(dp(activity, 18), dp(activity, 26), dp(activity, 18), dp(activity, 30));
        scroll.addView(column, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView head = new TextView(activity);
        head.setText(appName);
        head.setTextColor(Color.parseColor("#F2F6FC"));
        head.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        head.setTypeface(Typeface.DEFAULT_BOLD);
        column.addView(head);

        TextView note = new TextView(activity);
        note.setText("شاشة أساسية شغالة — الشاشة الأصلية طلعت فاضية، ودي نسخة فيها التنقل والحفظ.");
        note.setTextColor(Color.parseColor("#A9B7CA"));
        note.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        LinearLayout.LayoutParams noteParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        noteParams.topMargin = dp(activity, 6);
        column.addView(note, noteParams);

        LinearLayout nav = new LinearLayout(activity);
        nav.setOrientation(LinearLayout.VERTICAL);
        String here = activity.getClass().getName();
        int shown = 0;
        for (int i = 0; i < SCREENS.length; i++) {
            final String target = SCREENS[i][1];
            if (target == null || target.length() == 0 || target.equals(here)) {
                continue;
            }
            Button open = new Button(activity);
            open.setText(SCREENS[i][0]);
            open.setAllCaps(false);
            open.setTextColor(Color.parseColor("#F2F6FC"));
            open.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            open.setBackgroundColor(Color.parseColor("#152238"));
            LinearLayout.LayoutParams buttonParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(activity, 48));
            buttonParams.bottomMargin = dp(activity, 8);
            open.setLayoutParams(buttonParams);
            open.setContentDescription("افتح شاشة " + SCREENS[i][0]);
            open.setOnClickListener(new View.OnClickListener() {
                public void onClick(View view) {
                    openScreen(activity, target);
                }
            });
            nav.addView(open);
            shown++;
        }
        if (shown > 0) {
            column.addView(section(activity, "شاشات التطبيق"));
            column.addView(nav);
        }

        column.addView(section(activity, "محفوظاتك"));
        final SharedPreferences prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        final EditText input = new EditText(activity);
        input.setHint("اكتب حاجة واحفظها — بتفضل بعد ما تقفل التطبيق");
        input.setHintTextColor(Color.parseColor("#6E7F96"));
        input.setTextColor(Color.parseColor("#F2F6FC"));
        input.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        column.addView(input);

        final TextView empty = new TextView(activity);
        empty.setTextColor(Color.parseColor("#A9B7CA"));
        empty.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        empty.setText("لا يوجد شيء محفوظ بعد — اكتب فوق واضغط «حفظ».");

        final LinearLayout list = new LinearLayout(activity);
        list.setOrientation(LinearLayout.VERTICAL);

        Button save = new Button(activity);
        save.setText("حفظ");
        save.setAllCaps(false);
        save.setTextColor(Color.WHITE);
        save.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        save.setBackgroundColor(Color.parseColor("#3285F6"));
        LinearLayout.LayoutParams saveParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(activity, 48));
        saveParams.topMargin = dp(activity, 10);
        saveParams.bottomMargin = dp(activity, 12);
        save.setLayoutParams(saveParams);
        save.setContentDescription("حفظ ما كتبته");
        save.setOnClickListener(new View.OnClickListener() {
            public void onClick(View view) {
                CharSequence raw = input.getText();
                String text = raw == null ? "" : raw.toString().trim();
                if (text.length() == 0) {
                    Toast.makeText(activity, "اكتب حاجة الأول.", Toast.LENGTH_SHORT).show();
                    return;
                }
                String all = prefs.getString(KEY, "");
                String clean = text.replace('\n', ' ');
                all = clean + "\n" + all;
                prefs.edit().putString(KEY, all).apply();
                input.setText("");
                render(activity, list, empty, prefs);
                Toast.makeText(activity, "اتحفظت وظهرت في القائمة.", Toast.LENGTH_SHORT).show();
            }
        });
        column.addView(save);
        column.addView(empty);
        column.addView(list);

        Button clear = new Button(activity);
        clear.setText("مسح الكل");
        clear.setAllCaps(false);
        clear.setTextColor(Color.parseColor("#FFB454"));
        clear.setBackgroundColor(Color.parseColor("#152238"));
        clear.setContentDescription("مسح كل المحفوظات بعد تأكيد");
        clear.setOnClickListener(new View.OnClickListener() {
            public void onClick(View view) {
                confirmClear(activity, prefs, list, empty);
            }
        });
        LinearLayout.LayoutParams clearParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(activity, 48));
        clearParams.topMargin = dp(activity, 14);
        clear.setLayoutParams(clearParams);
        column.addView(clear);

        render(activity, list, empty, prefs);
        try {
            activity.setContentView(scroll);
        } catch (Throwable ignored) {
        }
    }

    /** فتح شاشة من شاشات التطبيق بالاسم — بلا أي مرجع مباشر، فمفيش خطر على البناء. */
    private static void openScreen(Activity activity, String className) {
        try {
            Class<?> target = Class.forName(className);
            Intent intent = new Intent(activity, target);
            activity.startActivity(intent);
        } catch (Throwable error) {
            Toast.makeText(activity, "الشاشة دي مش موجودة في النسخة الحالية.",
                    Toast.LENGTH_SHORT).show();
        }
    }

    /** يرسم المحفوظ من جديد: أحدث عنصر فوق، وحالة فاضية لو مفيش حاجة. */
    private static void render(final Activity activity, final LinearLayout list,
                               final TextView empty, final SharedPreferences prefs) {
        if (list == null) {
            return;
        }
        try {
            list.removeAllViews();
            String all = prefs.getString(KEY, "");
            final String[] lines = all.split("\n");
            int count = 0;
            for (int i = 0; i < lines.length; i++) {
                final String line = lines[i] == null ? "" : lines[i].trim();
                if (line.length() == 0) {
                    continue;
                }
                count++;
                TextView row = new TextView(activity);
                row.setText(line);
                row.setTextColor(Color.parseColor("#F2F6FC"));
                row.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
                row.setPadding(dp(activity, 12), dp(activity, 12), dp(activity, 12),
                        dp(activity, 12));
                row.setBackgroundColor(Color.parseColor("#152238"));
                LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT);
                params.bottomMargin = dp(activity, 8);
                row.setLayoutParams(params);
                row.setContentDescription("اضغط مطولًا لحذف: " + line);
                row.setOnLongClickListener(new View.OnLongClickListener() {
                    public boolean onLongClick(View view) {
                        confirmDelete(activity, line, list, empty, prefs);
                        return true;
                    }
                });
                list.addView(row);
            }
            if (empty != null) {
                empty.setVisibility(count == 0 ? View.VISIBLE : View.GONE);
            }
        } catch (Throwable ignored) {
        }
    }

    private static void confirmDelete(final Activity activity, final String line,
                                      final LinearLayout list, final TextView empty,
                                      final SharedPreferences prefs) {
        try {
            AlertDialog.Builder builder = new AlertDialog.Builder(activity);
            builder.setTitle("حذف");
            builder.setMessage("تحذف «" + line + "»؟");
            builder.setPositiveButton("حذف", new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface dialog, int which) {
                    String all = prefs.getString(KEY, "");
                    String[] lines = all.split("\n");
                    StringBuilder kept = new StringBuilder();
                    boolean removed = false;
                    for (int i = 0; i < lines.length; i++) {
                        String item = lines[i] == null ? "" : lines[i].trim();
                        if (item.length() == 0) {
                            continue;
                        }
                        if (!removed && item.equals(line)) {
                            removed = true;
                            continue;
                        }
                        kept.append(item).append("\n");
                    }
                    prefs.edit().putString(KEY, kept.toString()).apply();
                    render(activity, list, empty, prefs);
                }
            });
            builder.setNegativeButton("إلغاء", null);
            builder.show();
        } catch (Throwable ignored) {
        }
    }

    private static void confirmClear(final Activity activity, final SharedPreferences prefs,
                                     final LinearLayout list, final TextView empty) {
        try {
            AlertDialog.Builder builder = new AlertDialog.Builder(activity);
            builder.setTitle("مسح الكل");
            builder.setMessage("هتمسح كل المحفوظات. تكمل؟");
            builder.setPositiveButton("امسح", new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface dialog, int which) {
                    prefs.edit().remove(KEY).apply();
                    render(activity, list, empty, prefs);
                }
            });
            builder.setNegativeButton("إلغاء", null);
            builder.show();
        } catch (Throwable ignored) {
        }
    }

    private static TextView section(Activity activity, String text) {
        TextView view = new TextView(activity);
        view.setText(text);
        view.setTextColor(Color.parseColor("#3285F6"));
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        view.setTypeface(Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(activity, 18);
        params.bottomMargin = dp(activity, 8);
        view.setLayoutParams(params);
        return view;
    }

    private static int dp(Context context, int value) {
        try {
            return (int) (value * context.getResources().getDisplayMetrics().density + 0.5f);
        } catch (Throwable error) {
            return value;
        }
    }
}
