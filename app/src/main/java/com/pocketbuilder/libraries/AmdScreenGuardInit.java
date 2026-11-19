package com.pocketbuilder.libraries;

import android.app.Application;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;

/**
 * AMD Studio — بيوصّل حارس الشاشات قبل ما أي شاشة تتفتح.
 *
 * <p>مكتوب كـContentProvider مقصود: الـProvider بيتعمل قبل أول شاشة في التطبيق، وبياخد سياق
 * التطبيق، فالحارس بيسجّل نفسه من غير ما نلمس كلاس Application اللي ممكن يكون التطبيق مستخدمه
 * لنفسه. ما بيقرأ ولا بيكتب أي بيانات — تسجيل مستمع وبس.</p>
 */
public final class AmdScreenGuardInit extends ContentProvider {

    public boolean onCreate() {
        try {
            Context context = getContext();
            if (context != null) {
                Context app = context.getApplicationContext();
                if (app instanceof Application) {
                    AmdScreenGuard.watch((Application) app);
                } else if (context instanceof Application) {
                    AmdScreenGuard.watch((Application) context);
                }
            }
        } catch (Throwable ignored) {
        }
        return true;
    }

    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs,
                        String sortOrder) {
        return null;
    }

    public String getType(Uri uri) {
        return null;
    }

    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
}
