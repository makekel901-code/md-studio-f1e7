package MD_Studio_Migration.legacy-reference.assets;

import android.app.Activity;
import android.app.Application;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.webkit.ConsoleMessage;
import java.io.PrintWriter;
import java.io.StringWriter;

/** Builder-supplied helper. No network/storage permissions, and dormant without a test URI grant. */
public final class Diagnostics extends ContentProvider {
    private static volatile Context appContext;
    private static volatile Uri destination;
    private static boolean handlerInstalled;
    private static long activatedAt;
    private static final String EXTRA = "com.pocketbuilder.runtime.DIAGNOSTICS_URI";

    @Override public boolean onCreate() {
        appContext = getContext().getApplicationContext();
        if (appContext instanceof Application) {
            ((Application) appContext).registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
                // Android 10+: activate before user onCreate, so startup exceptions are captured too.
                public void onActivityPreCreated(Activity activity, Bundle state) { activate(activity); }
                public void onActivityCreated(Activity activity, Bundle state) { activate(activity); lifecycle("created", activity); }
                public void onActivityStarted(Activity activity) { lifecycle("started", activity); }
                public void onActivityResumed(Activity activity) { lifecycle("resumed", activity); }
                public void onActivityPaused(Activity activity) { lifecycle("paused", activity); }
                public void onActivityStopped(Activity activity) { lifecycle("stopped", activity); }
                public void onActivitySaveInstanceState(Activity activity, Bundle state) { }
                public void onActivityDestroyed(Activity activity) { lifecycle("destroyed", activity); }
            });
        }
        return true;
    }

    private static synchronized void activate(Activity activity) {
        String raw = activity.getIntent().getStringExtra(EXTRA);
        if (raw == null) {
            // Opening the launcher normally must not continue a previous diagnostic session.
            if (android.content.Intent.ACTION_MAIN.equals(activity.getIntent().getAction())) destination = null;
            return;
        }
        Uri uri = Uri.parse(raw);
        if (!"content".equals(uri.getScheme()) || !"com.pocketbuilder.diagnostics".equals(uri.getAuthority())) return;
        if (uri.equals(destination)) return;
        destination = uri;
        activatedAt = System.currentTimeMillis();
        if (!handlerInstalled) {
            final Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
            Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
                public void uncaughtException(Thread thread, Throwable error) {
                    try { report("crash", trace(error)); }
                    finally {
                        if (previous != null) previous.uncaughtException(thread, error);
                        else { android.os.Process.killProcess(android.os.Process.myPid()); System.exit(10); }
                    }
                }
            });
            handlerInstalled = true;
        }
        report("event", "Diagnostic test session activated (helper v1)");
    }

    private static void lifecycle(String event, Activity activity) {
        report("lifecycle", activity.getClass().getName() + " " + event);
    }

    /** Record a short, non-sensitive feature milestone, never form contents or credentials. */
    public static void event(String message) { report("event", message); }
    public static void error(Throwable error) { report("event", trace(error)); }

    /** Call from an existing WebChromeClient.onConsoleMessage override. Does not replace clients. */
    public static void console(ConsoleMessage message) {
        if (message != null && (message.messageLevel() == ConsoleMessage.MessageLevel.ERROR
                || message.messageLevel() == ConsoleMessage.MessageLevel.WARNING)) {
            report("web_error", "line " + message.lineNumber() + ": " + message.message());
        }
    }

    /** Call from WebViewClient error callbacks or native request failure handlers. Do not pass URLs/bodies. */
    public static void networkError(String operation, int code) {
        report("network_error", operation + " failed, code=" + code);
    }

    private static String trace(Throwable error) {
        StringWriter buffer = new StringWriter();
        error.printStackTrace(new PrintWriter(buffer));
        return buffer.toString();
    }

    private static synchronized void report(String kind, String message) {
        Uri target = destination;
        if (target == null || appContext == null) return;
        if (System.currentTimeMillis() - activatedAt > 30 * 60 * 1000L) { destination = null; return; }
        try {
            ContentValues values = new ContentValues();
            values.put("kind", kind);
            String safe = message == null ? "" : message;
            values.put("message", safe.length() > 16000 ? safe.substring(0, 16000) : safe);
            appContext.getContentResolver().insert(target, values);
        } catch (Throwable ignored) {
            // Revoked grants or a stopped builder must never crash the generated app.
            destination = null;
        }
    }

    @Override public String getType(Uri uri) { return null; }
    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String order) { return null; }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri, String selection, String[] args) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { throw new UnsupportedOperationException(); }
}
