package com.glass.launcher;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.provider.Settings;
import android.util.Base64;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Glass Launcher - native shell for the user's index.html.
 *
 * The HTML is used exactly as authored (assets/index.html is a byte-for-byte copy).
 * This class only does what a WebView page cannot: read the installed app list,
 * hand it to the page through setApps(), and launch apps when the page asks.
 *
 * Everything is defensive: a HOME app must never leave the phone without a launcher,
 * so every risky step is wrapped and there is a plain native fallback screen.
 */
public class MainActivity extends Activity {

  private static final String TAG = "GlassLauncher";

  private WebView web;
  private volatile boolean loaded = false;
  private BroadcastReceiver pkgReceiver;
  private boolean rebuilding = false;

  @Override protected void onCreate(Bundle b) {
    super.onCreate(b);
    installCrashGuard();

    try { setupWindow(); } catch (Throwable t) { log(t); }

    try {
      startWeb();
      registerPkgReceiver();
    } catch (Throwable t) {
      log(t);
      showFallback();
    }
  }

  /* ------------------------------------------------------------------ window */

  private void setupWindow() {
    Window win = getWindow();
    win.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
    win.setStatusBarColor(Color.TRANSPARENT);
    win.setNavigationBarColor(Color.TRANSPARENT);

    if (Build.VERSION.SDK_INT >= 28) {
      WindowManager.LayoutParams lp = win.getAttributes();
      lp.layoutInDisplayCutoutMode =
          WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
      win.setAttributes(lp);
    }
    hideStatusBar();
  }

  // The page draws its own clock, so the system status bar is hidden.
  // The navigation bar is left alone - back/home gestures stay native.
  private void hideStatusBar() {
    try {
      Window win = getWindow();
      if (Build.VERSION.SDK_INT >= 30) {
        win.setDecorFitsSystemWindows(false);
        if (win.getInsetsController() != null) {
          win.getInsetsController().hide(WindowInsets.Type.statusBars());
        }
      } else {
        win.getDecorView().setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_FULLSCREEN);
      }
    } catch (Throwable t) {
      log(t);
    }
  }

  /* ----------------------------------------------------------------- webview */

  private void startWeb() {
    web = new WebView(this);
    web.setBackgroundColor(Color.BLACK);
    web.setOverScrollMode(View.OVER_SCROLL_NEVER);
    web.setLongClickable(false);

    WebSettings s = web.getSettings();
    s.setJavaScriptEnabled(true);
    s.setDomStorageEnabled(true);
    s.setSupportZoom(false);
    s.setBuiltInZoomControls(false);
    s.setDisplayZoomControls(false);
    s.setTextZoom(100);
    s.setAllowFileAccess(true);
    s.setLoadWithOverviewMode(false);
    s.setUseWideViewPort(true);
    if (Build.VERSION.SDK_INT >= 26) {
      try { s.setSafeBrowsingEnabled(false); } catch (Throwable ignored) { }
    }

    web.addJavascriptInterface(new Bridge(), "Android");
    web.setWebChromeClient(new WebChromeClient());
    web.setWebViewClient(new WebViewClient() {
      @Override public void onPageFinished(WebView v, String url) {
        loaded = true;
        pushApps();
      }

      // The WebView renderer can die on its own (heavy blur, low memory, GPU reset).
      // Without this the whole launcher would be killed with it - rebuild instead.
      @Override public boolean onRenderProcessGone(WebView v, RenderProcessGoneDetail detail) {
        Log.w(TAG, "render process gone - rebuilding webview");
        rebuildWeb();
        return true;
      }
    });

    setContentView(web);
    web.loadUrl("file:///android_asset/index.html");
  }

  private void rebuildWeb() {
    if (rebuilding) return;
    rebuilding = true;
    try {
      ViewGroup parent = web == null ? null : (ViewGroup) web.getParent();
      if (parent != null) parent.removeView(web);
      if (web != null) web.destroy();
    } catch (Throwable t) {
      log(t);
    }
    web = null;
    loaded = false;
    try {
      startWeb();
    } catch (Throwable t) {
      log(t);
      showFallback();
    }
    rebuilding = false;
  }

  private void js(final String code) {
    if (web == null || !loaded) return;
    runOnUiThread(new Runnable() {
      @Override public void run() {
        try {
          web.evaluateJavascript(code, null);
        } catch (Throwable t) {
          log(t);
        }
      }
    });
  }

  /* ------------------------------------------------------------- app list */

  private void pushApps() {
    if (web == null) return;
    new Thread(new Runnable() {
      @Override public void run() {
        try {
          final PackageManager pm = getPackageManager();
          Intent q = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
          List<ResolveInfo> raw = pm.queryIntentActivities(q, 0);
          if (raw == null) raw = new ArrayList<ResolveInfo>();
          final List<ResolveInfo> list = new ArrayList<ResolveInfo>(raw);

          final Collator col = Collator.getInstance(Locale.getDefault());
          col.setStrength(Collator.PRIMARY);
          Collections.sort(list, new Comparator<ResolveInfo>() {
            @Override public int compare(ResolveInfo a, ResolveInfo c) {
              String x = a.loadLabel(pm).toString();
              String y = c.loadLabel(pm).toString();
              int r = col.compare(x, y);
              return r != 0 ? r : x.compareToIgnoreCase(y);
            }
          });

          // Icon payload is the biggest thing we send to the page. Keep it small enough
          // that a phone with 200+ apps cannot blow the WebView's memory.
          int count = list.size();
          int px;
          if (count > 180) px = 72;
          else if (count > 90) px = 88;
          else px = 108;

          JSONArray apps = new JSONArray();
          for (int k = 0; k < count; k++) {
            ResolveInfo ri = list.get(k);
            if (ri == null || ri.activityInfo == null) continue;
            String pkg = ri.activityInfo.packageName;
            if (pkg == null || pkg.equals(getPackageName())) continue;
            JSONObject o = new JSONObject();
            o.put("n", label(pm, ri));
            o.put("p", pkg);
            o.put("i", png(icon(pm, ri), px));
            apps.put(o);
          }

          JSONArray dock = new JSONArray();
          dock.put(pick(new Intent(Intent.ACTION_DIAL)));
          dock.put(pick(new Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:"))));
          dock.put(pick(new Intent(Intent.ACTION_VIEW, Uri.parse("https://"))));
          dock.put(pick(new Intent(MediaStore.ACTION_IMAGE_CAPTURE)));

          JSONObject out = new JSONObject().put("apps", apps).put("dock", dock);
          js("setApps(" + JSONObject.quote(out.toString()) + ")");
        } catch (Throwable t) {
          log(t);
        }
      }
    }).start();
  }

  private String label(PackageManager pm, ResolveInfo ri) {
    try {
      CharSequence cs = ri.loadLabel(pm);
      return cs == null ? ri.activityInfo.packageName : cs.toString();
    } catch (Throwable t) {
      return ri.activityInfo.packageName;
    }
  }

  private Drawable icon(PackageManager pm, ResolveInfo ri) {
    try {
      Drawable d = ri.loadIcon(pm);
      if (d != null) return d;
    } catch (Throwable ignored) { }
    try {
      return pm.getDefaultActivityIcon();
    } catch (Throwable t) {
      return null;
    }
  }

  private String pick(Intent i) {
    try {
      ResolveInfo r = getPackageManager().resolveActivity(i, PackageManager.MATCH_DEFAULT_ONLY);
      if (r == null || r.activityInfo == null) return "";
      String p = r.activityInfo.packageName;
      return getPackageManager().getLaunchIntentForPackage(p) != null ? p : "";
    } catch (Throwable t) {
      return "";
    }
  }

  private String png(Drawable d, int size) {
    if (d == null) return "";
    Bitmap bm = null;
    try {
      bm = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
      Canvas c = new Canvas(bm);
      d.setBounds(0, 0, size, size);
      d.draw(c);
      ByteArrayOutputStream o = new ByteArrayOutputStream();
      bm.compress(Bitmap.CompressFormat.PNG, 100, o);
      return Base64.encodeToString(o.toByteArray(), Base64.NO_WRAP);
    } catch (Throwable t) {
      return "";
    } finally {
      if (bm != null) {
        try { bm.recycle(); } catch (Throwable ignored) { }
      }
    }
  }

  private void registerPkgReceiver() {
    IntentFilter f = new IntentFilter();
    f.addAction(Intent.ACTION_PACKAGE_ADDED);
    f.addAction(Intent.ACTION_PACKAGE_REMOVED);
    f.addAction(Intent.ACTION_PACKAGE_REPLACED);
    f.addDataScheme("package");
    pkgReceiver = new BroadcastReceiver() {
      @Override public void onReceive(Context c, Intent i) {
        pushApps();
      }
    };
    if (Build.VERSION.SDK_INT >= 33) registerReceiver(pkgReceiver, f, Context.RECEIVER_EXPORTED);
    else registerReceiver(pkgReceiver, f);
  }

  /* --------------------------------------------------------------- bridge */

  public class Bridge {
    @JavascriptInterface public void launch(String pkg) {
      launchPackage(pkg);
    }

    @JavascriptInterface public void appInfo(String pkg) {
      try {
        Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:" + pkg));
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(i);
      } catch (Throwable t) {
        log(t);
      }
    }

    @JavascriptInterface public void refresh() {
      pushApps();
    }
  }

  private void launchPackage(String pkg) {
    if (pkg == null || pkg.length() == 0) return;
    try {
      Intent i = getPackageManager().getLaunchIntentForPackage(pkg);
      if (i == null) {
        // app vanished between listing and tapping - close the page's overlay
        js("goHome()");
        return;
      }
      i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
      startActivity(i);
      overridePendingTransition(0, 0);
    } catch (Throwable t) {
      log(t);
    }
  }

  /* ------------------------------------------------------------- fallback */

  // Plain native app list, used only if the WebView cannot start.
  // A launcher that shows *something* beats a black screen.
  private void showFallback() {
    try {
      ScrollView sv = new ScrollView(this);
      sv.setBackgroundColor(Color.BLACK);
      LinearLayout ll = new LinearLayout(this);
      ll.setOrientation(LinearLayout.VERTICAL);
      ll.setPadding(dp(14), dp(34), dp(14), dp(20));

      TextView head = new TextView(this);
      head.setText("Glass Launcher - safe mode");
      head.setTextColor(Color.WHITE);
      head.setTextSize(17);
      head.setPadding(0, 0, 0, dp(12));
      ll.addView(head);

      PackageManager pm = getPackageManager();
      List<ResolveInfo> list = pm.queryIntentActivities(
          new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0);
      final Collator col = Collator.getInstance(Locale.getDefault());
      Collections.sort(list, new Comparator<ResolveInfo>() {
        @Override public int compare(ResolveInfo a, ResolveInfo c) {
          return col.compare(a.loadLabel(getPackageManager()).toString(),
              c.loadLabel(getPackageManager()).toString());
        }
      });
      for (int i = 0; i < list.size(); i++) {
        ResolveInfo ri = list.get(i);
        if (ri.activityInfo == null) continue;
        final String pkg = ri.activityInfo.packageName;
        if (pkg.equals(getPackageName())) continue;
        Button b = new Button(this);
        b.setAllCaps(false);
        b.setText(label(pm, ri));
        b.setOnClickListener(new View.OnClickListener() {
          @Override public void onClick(View v) { launchPackage(pkg); }
        });
        ll.addView(b);
      }
      sv.addView(ll);
      setContentView(sv);
    } catch (Throwable t) {
      log(t);
    }
  }

  private int dp(int v) {
    try {
      return Math.round(v * getResources().getDisplayMetrics().density);
    } catch (Throwable t) {
      return v * 3;
    }
  }

  /* ------------------------------------------------------------ crash log */

  private void installCrashGuard() {
    final Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();
    Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
      @Override public void uncaughtException(Thread t, Throwable e) {
        try {
          File f = new File(getFilesDir(), "last_crash.txt");
          PrintWriter pw = new PrintWriter(new FileWriter(f, false));
          pw.println(new Date().toString());
          pw.println("Android " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")");
          pw.println(Build.MANUFACTURER + " " + Build.MODEL);
          e.printStackTrace(pw);
          pw.flush();
          pw.close();
        } catch (Throwable ignored) { }
        if (prev != null) prev.uncaughtException(t, e);
      }
    });
  }

  private void log(Throwable t) {
    try {
      Log.e(TAG, "handled: " + t, t);
    } catch (Throwable ignored) { }
  }

  /* ------------------------------------------------------------ lifecycle */

  @Override public void onBackPressed() {
    if (web != null && loaded) js("goBack()");
    else super.onBackPressed();
  }

  @Override protected void onNewIntent(Intent i) {
    super.onNewIntent(i);
    if (i != null && Intent.ACTION_MAIN.equals(i.getAction())) js("goHome()");
  }

  @Override protected void onResume() {
    super.onResume();
    hideStatusBar();
    if (web != null && loaded) js("onResumeL()");
  }

  @Override protected void onDestroy() {
    try {
      if (pkgReceiver != null) unregisterReceiver(pkgReceiver);
    } catch (Throwable ignored) { }
    pkgReceiver = null;
    try {
      if (web != null) {
        ViewGroup p = (ViewGroup) web.getParent();
        if (p != null) p.removeView(web);
        web.destroy();
      }
    } catch (Throwable ignored) { }
    web = null;
    super.onDestroy();
  }
}
