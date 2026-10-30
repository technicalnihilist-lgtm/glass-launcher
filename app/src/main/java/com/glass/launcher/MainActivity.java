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
import android.widget.Toast;
import android.widget.TextView;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.FileReader;
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
  private int safeTop = 0, safeBottom = 0; // in CSS px (dp)

  @Override protected void onCreate(Bundle b) {
    super.onCreate(b);
    installCrashGuard();

    try { setupWindow(); } catch (Throwable t) { log(t); }
    showCrashNotice();

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
    // Force-dark / algorithmic darkening inverts images too - app icons came out as
    // dark silhouettes on phones running dark mode.
    try {
      if (Build.VERSION.SDK_INT >= 33) s.setAlgorithmicDarkeningAllowed(false);
      else if (Build.VERSION.SDK_INT >= 29) s.setForceDark(WebSettings.FORCE_DARK_OFF);
    } catch (Throwable ignored) { }

    web.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
      @Override public WindowInsets onApplyWindowInsets(View v, WindowInsets in) {
        readSafeArea(in);
        pushSafeArea();
        return in;
      }
    });

    web.addJavascriptInterface(new Bridge(), "Android");
    web.setWebChromeClient(new WebChromeClient());
    web.setWebViewClient(new WebViewClient() {
      @Override public void onPageFinished(WebView v, String url) {
        loaded = true;
        pushSafeArea();
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

  // The page runs edge to edge, so it needs to know how much of the screen the
  // system bars and the camera cutout own - otherwise the dock sits behind the
  // navigation bar (very visible on 3-button navigation).
  private void readSafeArea(WindowInsets in) {
    try {
      float d = getResources().getDisplayMetrics().density;
      int t = 0, b = 0;
      if (Build.VERSION.SDK_INT >= 30) {
        t = in.getInsets(WindowInsets.Type.displayCutout()).top;
        b = in.getInsets(WindowInsets.Type.navigationBars()).bottom;
      } else {
        b = in.getSystemWindowInsetBottom();
        if (Build.VERSION.SDK_INT >= 28 && in.getDisplayCutout() != null) {
          t = in.getDisplayCutout().getSafeInsetTop();
        }
      }
      safeTop = Math.round(t / d);
      safeBottom = Math.round(b / d);
    } catch (Throwable t) {
      log(t);
    }
  }

  private void pushSafeArea() {
    js("setSafeArea(" + safeTop + "," + safeBottom + ")");
  }

  private void showCrashNotice() {
    try {
      File f = new File(getFilesDir(), "last_crash.txt");
      if (!f.exists()) return;
      BufferedReader r = new BufferedReader(new FileReader(f));
      String line, ex = null;
      while ((line = r.readLine()) != null) {
        if (line.contains("Exception") || line.contains("Error")) { ex = line.trim(); break; }
      }
      r.close();
      f.delete();
      if (ex != null) {
        if (ex.length() > 160) ex = ex.substring(0, 160);
        Toast.makeText(this, "Last crash: " + ex, Toast.LENGTH_LONG).show();
      }
    } catch (Throwable t) {
      log(t);
    }
  }

  private void js(final String code) {
    final WebView w = web;
    if (w == null || !loaded) return;
    runOnUiThread(new Runnable() {
      @Override public void run() {
        try {
          w.evaluateJavascript(code, null);
        } catch (Throwable t) {
          log(t);
        }
      }
    });
  }

  /* ------------------------------------------------------------- app list */

  private volatile String lastPayload = "";

  // The app list is sent in two steps: names and packages first (a few KB), then the
  // icons one batch at a time. Sending everything at once meant a 2-4 MB javascript
  // string on a normal phone, which the WebView silently drops or dies on - the page
  // then keeps showing its built-in demo apps and nothing works.
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

          JSONArray apps = new JSONArray();
          List<String> pkgs = new ArrayList<String>();
          List<ResolveInfo> shown = new ArrayList<ResolveInfo>();
          for (int k = 0; k < list.size(); k++) {
            ResolveInfo ri = list.get(k);
            if (ri == null || ri.activityInfo == null) continue;
            String pkg = ri.activityInfo.packageName;
            if (pkg == null || pkg.equals(getPackageName())) continue;
            JSONObject o = new JSONObject();
            o.put("n", label(pm, ri));
            o.put("p", pkg);
            o.put("i", "");
            apps.put(o);
            pkgs.add(pkg);
            shown.add(ri);
          }

          JSONArray dock = new JSONArray();
          dock.put(pick(new Intent(Intent.ACTION_DIAL)));
          dock.put(pick(new Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:"))));
          dock.put(pick(new Intent(Intent.ACTION_VIEW, Uri.parse("https://"))));
          dock.put(pick(new Intent(MediaStore.ACTION_IMAGE_CAPTURE)));

          JSONObject out = new JSONObject().put("apps", apps).put("dock", dock);
          lastPayload = out.toString();
          js("setApps(" + JSONObject.quote(lastPayload) + ")");

          // icons, in small batches so the UI stays responsive
          int count = shown.size();
          int px = count > 180 ? 72 : (count > 90 ? 88 : 108);
          StringBuilder sb = new StringBuilder();
          int inBatch = 0;
          for (int k = 0; k < count; k++) {
            if (web == null) return;
            String b = png(icon(pm, shown.get(k)), px);
            if (b == null || b.length() == 0) continue;
            sb.append("setIcon(").append(JSONObject.quote(pkgs.get(k))).append(",")
              .append(JSONObject.quote(b)).append(");");
            if (++inBatch >= 6) {
              js(sb.toString());
              sb.setLength(0);
              inBatch = 0;
              try { Thread.sleep(20); } catch (InterruptedException ignored) { }
            }
          }
          if (sb.length() > 0) js(sb.toString());

          if (count == 0) {
            Log.w(TAG, "no launchable apps found");
            toast("No apps found - is the launcher allowed to list them?");
          } else {
            verifyApps(count, 0);
          }
        } catch (Throwable t) {
          log(t);
        }
      }
    }).start();
  }

  // If the page still shows no real apps, resend the list once without icons.
  private void verifyApps(final int expected, final int attempt) {
    final WebView w = web;
    if (w == null) return;
    w.postDelayed(new Runnable() {
      @Override public void run() {
        try {
          w.evaluateJavascript("document.querySelectorAll('.app[data-p]').length",
              new android.webkit.ValueCallback<String>() {
                @Override public void onReceiveValue(String v) {
                  try {
                    int n = Integer.parseInt(v == null ? "0" : v.replaceAll("[^0-9]", ""));
                    Log.i(TAG, "rendered " + n + " of " + expected + " apps");
                    if (n == 0 && expected > 0 && attempt < 1 && lastPayload.length() > 0) {
                      js("setApps(" + JSONObject.quote(lastPayload) + ")");
                      verifyApps(expected, attempt + 1);
                    }
                  } catch (Throwable ignored) { }
                }
              });
        } catch (Throwable t) {
          log(t);
        }
      }
    }, 1800);
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

      // a legacy icon with a transparent background would show as a dark glyph on a
      // black tile; give it an iOS style light tile instead
      boolean clear = Color.alpha(bm.getPixel(1, 1)) < 250
          || Color.alpha(bm.getPixel(size - 2, 1)) < 250
          || Color.alpha(bm.getPixel(1, size - 2)) < 250
          || Color.alpha(bm.getPixel(size - 2, size - 2)) < 250;
      if (clear) {
        Bitmap base = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas bc = new Canvas(base);
        bc.drawColor(0xFFF3F3F7);
        bc.drawBitmap(bm, 0, 0, null);
        bm.recycle();
        bm = base;
      }

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
    @JavascriptInterface public void launch(final String pkg) {
      runOnUiThread(new Runnable() {
        @Override public void run() { launchPackage(pkg); }
      });
    }

    @JavascriptInterface public void appInfo(final String pkg) {
      runOnUiThread(new Runnable() {
        @Override public void run() {
          try {
            Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:" + pkg));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
          } catch (Throwable t) {
            log(t);
          }
        }
      });
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

  private void toast(final String msg) {
    runOnUiThread(new Runnable() {
      @Override public void run() {
        try { Toast.makeText(MainActivity.this, msg, Toast.LENGTH_LONG).show(); } catch (Throwable ignored) { }
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
