package MD_Studio_Migration.legacy-reference.assets;

/** Call from an accessible About / Third-party licences menu item on the UI thread. */
public final class PocketAbout {
    private PocketAbout() {}
    public static boolean isRequired(android.content.Context context) {
        try {
            java.io.InputStream in=context.getAssets().open("pocketbuilder/third-party-notices-required.txt");
            try {
                byte[] flag=new byte[6]; int n=0, b;
                while(n<flag.length && (b=in.read())!=-1) flag[n++]=(byte)b;
                return !"false".equals(new String(flag,0,n,"UTF-8"));
            } finally { in.close(); }
        } catch(java.io.IOException error) { return true; } // Legacy APKs keep their notices.
    }
    public static void show(android.app.Activity activity) {
        if (!isRequired(activity)) return;
        if (android.os.Looper.myLooper()!=android.os.Looper.getMainLooper()) {
            final android.app.Activity target=activity;
            activity.runOnUiThread(new Runnable(){public void run(){show(target);}});
            return;
        }
        String text;
        try {
            java.io.InputStream input=activity.getAssets().open("pocketbuilder/third-party-notices.txt");
            java.io.ByteArrayOutputStream bytes=new java.io.ByteArrayOutputStream();
            try {byte[] buffer=new byte[4096];int n;while((n=input.read(buffer))!=-1)bytes.write(buffer,0,n);}
            finally {input.close();}
            text=new String(bytes.toByteArray(),"UTF-8");
        } catch(java.io.IOException error) { text="Third-party notices could not be loaded."; }
        android.widget.TextView view=new android.widget.TextView(activity);
        view.setText(text);view.setTextIsSelectable(true);int padding=(int)(20*activity.getResources().getDisplayMetrics().density);view.setPadding(padding,padding,padding,padding);
        android.widget.ScrollView scroll=new android.widget.ScrollView(activity);scroll.addView(view);
        new android.app.AlertDialog.Builder(activity).setTitle("Third-party licences").setView(scroll).setPositiveButton("Close",null).show();
    }
}
