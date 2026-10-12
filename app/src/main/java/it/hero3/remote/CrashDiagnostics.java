package it.hero3.remote;

import android.app.*;
import android.content.*;
import android.os.Build;
import android.util.Log;
import java.io.*;
import java.util.List;

/** Records Java failures and exposes Android's previous process-exit details when available. */
public final class CrashDiagnostics extends Application {
    private static final String FILE = "last-error.txt";
    @Override public void onCreate() {
        super.onCreate();
        Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            record(this, "Eccezione non gestita · " + thread.getName(), error);
            if (previous != null) previous.uncaughtException(thread, error);
            else { android.os.Process.killProcess(android.os.Process.myPid()); System.exit(1); }
        });
    }
    static void record(Context context, String stage, Throwable error) {
        Log.e("Hero3Remote", stage, error);
        try (FileOutputStream out = context.openFileOutput(FILE, Context.MODE_PRIVATE)) {
            String report = new java.util.Date() + "\n" + stage + "\n" + Log.getStackTraceString(error);
            out.write(report.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (IOException ignored) {}
    }
    static void share(Activity activity) {
        StringBuilder report = new StringBuilder(activity.getString(R.string.app_name)).append(" · diagnostica\nAndroid ").append(Build.VERSION.RELEASE).append(" · ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append('\n');
        try { report.append("App ").append(activity.getPackageManager().getPackageInfo(activity.getPackageName(), 0).versionName).append('\n'); }
        catch (android.content.pm.PackageManager.NameNotFoundException ignored) {}
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(activity.openFileInput(FILE)))) {
            String line; while ((line = reader.readLine()) != null && report.length() < 24000) report.append(line).append('\n');
        } catch (IOException ignored) { report.append("Nessuna eccezione Java salvata.\n"); }
        if (Build.VERSION.SDK_INT >= 30) {
            ActivityManager manager = (ActivityManager) activity.getSystemService(Context.ACTIVITY_SERVICE);
            List<ApplicationExitInfo> exits = manager.getHistoricalProcessExitReasons(activity.getPackageName(), 0, 3);
            for (ApplicationExitInfo exit : exits) {
                report.append("\nUscita processo: ").append(new java.util.Date(exit.getTimestamp())).append(" · reason=").append(exit.getReason()).append(" · status=").append(exit.getStatus()).append("\n").append(exit.getDescription()).append('\n');
                if (exit.getReason() != ApplicationExitInfo.REASON_CRASH && exit.getReason() != ApplicationExitInfo.REASON_CRASH_NATIVE && exit.getReason() != ApplicationExitInfo.REASON_ANR) continue;
                try (InputStream input = exit.getTraceInputStream()) {
                    if (input != null) {
                        byte[] bytes = new byte[16000]; int count = input.read(bytes);
                        if (count > 0) report.append(new String(bytes, 0, count, java.nio.charset.StandardCharsets.UTF_8)).append('\n');
                    }
                } catch (IOException ignored) {}
            }
        }
        Intent intent = new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_SUBJECT, activity.getString(R.string.app_name) + " · diagnostica").putExtra(Intent.EXTRA_TEXT, report.toString());
        activity.startActivity(Intent.createChooser(intent, "Condividi diagnostica"));
    }
}
