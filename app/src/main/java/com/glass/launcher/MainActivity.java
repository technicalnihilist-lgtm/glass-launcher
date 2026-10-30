package com.glass.launcher;

import android.app.Activity;
import android.app.WallpaperManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.provider.Settings;
import android.util.Base64;
import android.util.DisplayMetrics;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import java.io.ByteArrayOutputStream;
import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Glass Launcher - a real Android HOME app.
 *
 * The UI is a WebView page, but every measurement it uses comes from the device:
 * window insets, density, screen size, locale, battery state and the system
 * wallpaper. Nothing is hard-coded to one phone.
 */
public class MainActivity extends Activity {

  private WebView web;
  private volatile boolean loaded = false;
  private int insT, insB, insL, insR;
  private BroadcastReceiver battReceiver, pkgReceiver;
  private String wallpaperData = null;

  @Override protected void onCreate(Bundle b) {
    super.onCreate(b);
    setupWindow();

    web = new WebView(this);
    web.setBackgroundColor(Color.BLACK);
    web.setOverScrollMode(View.OVER_SCROLL_NEVER);
    web.setLongClickable(false);
    web.setHapticFeedbackEnabled(true);

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
    s.setMediaPlaybackRequiresUserGesture(true);
    if (Build.VERSION.SDK_INT >= 26) s.setSafeBrowsingEnabled(false);

    web.addJavascriptInterface(new Bridge(), "Android");
    web.setWebViewClient(new WebViewClient() {
      @Override public void onPageFinished(WebView v, String url) {
        loaded = true;
        pushSystem();
        pushBattery(null);
        pushApps();
        pushWallpaper();
      }
    });
    web.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
      @Override public WindowInsets onApplyWindowInsets(View v, WindowInsets in) {
        readInsets(in);
        pushSystem();
        js("onSysChange()");
        return in;
      }
    });

    setContentView(web);
    web.loadUrl("file:///android_asset/index.html");

    // battery
    battReceiver = new BroadcastReceiver() {
      @Override public void onReceive(Context c, Intent i) { pushBattery(i); }
    };
    registerReceiver(battReceiver, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));

    // installed / removed apps
    IntentFilter f = new IntentFilter();
    f.addAction(Intent.ACTION_PACKAGE_ADDED);
    f.addAction(Intent.ACTION_PACKAGE_REMOVED);
    f.addAction(Intent.ACTION_PACKAGE_CHANGED);
    f.addAction(Intent.ACTION_PACKAGE_REPLACED);
    f.addDataScheme("package");
    pkgReceiver = new BroadcastReceiver() {
      @Override public void onReceive(Context c, Intent i) { pushApps(); }
    };
    if (Build.VERSION.SDK_INT >= 33) registerReceiver(pkgReceiver, f, Context.RECEIVER_EXPORTED);
    else registerReceiver(pkgReceiver, f);
  }

  /* ---------------- window: edge to edge, custom status bar ---------------- */

  private void setupWindow() {
    Window win = getWindow();
    win.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
    win.setStatusBarColor(Color.TRANSPARENT);
    win.setNavigationBarColor(Color.TRANSPARENT);
    if (Build.VERSION.SDK_INT >= 28) win.setNavigationBarDividerColor(Color.TRANSPARENT);
    if (Build.VERSION.SDK_INT >= 28) {
      WindowManager.LayoutParams lp = win.getAttributes();
      lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
      win.setAttributes(lp);
    }
    hideStatusBar();
  }

  // Hide only the status bar: the launcher draws its own clock and battery.
  // The navigation bar stays, so back/home gestures behave like the system expects.
  private void hideStatusBar() {
    Window win = getWindow();
    if (Build.VERSION.SDK_INT >= 30) {
      win.setDecorFitsSystemWindows(false);
      android.view.WindowInsetsController c = win.getInsetsController();
      if (c != null) {
        c.hide(WindowInsets.Type.statusBars());
        c.setSystemBarsBehavior(android.view.WindowInsetsController.BEHAVIOR_DEFAULT);
      }
    } else {
      win.getDecorView().setSystemUiVisibility(
          View.SYSTEM_UI_FLAG_LAYOUT_STABLE
              | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
              | View.SYSTEM_UI_FLAG_FULLSCREEN);
    }
  }

  private void readInsets(WindowInsets in) {
    int t = 0, b = 0, l = 0, r = 0;
    try {
      if (Build.VERSION.SDK_INT >= 30) {
        Insets cut = in.getInsets(WindowInsets.Type.displayCutout());
        Insets bars = in.getInsets(WindowInsets.Type.navigationBars());
        Insets sys = in.getInsets(WindowInsets.Type.systemBars());
        t = Math.max(cut.top, 0);
        b = Math.max(bars.bottom, sys.bottom);
        l = Math.max(cut.left, 0);
        r = Math.max(cut.right, 0);
      } else {
        t = in.getSystemWindowInsetTop();
        b = in.getSystemWindowInsetBottom();
        l = in.getSystemWindowInsetLeft();
        r = in.getSystemWindowInsetRight();
        if (Build.VERSION.SDK_INT >= 28 && in.getDisplayCutout() != null) {
          t = Math.max(t, in.getDisplayCutout().getSafeInsetTop());
          b = Math.max(b, in.getDisplayCutout().getSafeInsetBottom());
          l = Math.max(l, in.getDisplayCutout().getSafeInsetLeft());
          r = Math.max(r, in.getDisplayCutout().getSafeInsetRight());
        }
      }
    } catch (Throwable ignored) { }
    if (t > 0 && t < dp(18)) t = dp(18);
    insT = t; insB = b; insL = l; insR = r;
  }

  private int dp(float v) {
    return Math.round(v * getResources().getDisplayMetrics().density);
  }

  /* ---------------- data pushed into the page ---------------- */

  private void js(final String code) {
    if (web == null) return;
    runOnUiThread(new Runnable() {
      @Override public void run() {
        try { web.evaluateJavascript(code, null); } catch (Throwable ignored) { }
      }
    });
  }

  private void pushSystem() {
    if (!loaded) return;
    js("setSystem(" + JSONObject.quote(systemJson().toString()) + ")");
  }

  private JSONObject systemJson() {
    DisplayMetrics dm = getResources().getDisplayMetrics();
    JSONObject o = new JSONObject();
    try {
      o.put("t", insT);
      o.put("b", insB);
      o.put("l", insL);
      o.put("r", insR);
      o.put("d", (double) dm.density);
      o.put("model", Build.MODEL == null ? "" : Build.MODEL);
      o.put("manu", cap(Build.MANUFACTURER));
      o.put("sdk", Build.VERSION.SDK_INT);
      o.put("rel", Build.VERSION.RELEASE == null ? "" : Build.VERSION.RELEASE);
      o.put("lang", Locale.getDefault().toString());
      o.put("w", Math.round(dm.widthPixels / dm.density));
      o.put("h", Math.round(dm.heightPixels / dm.density));
    } catch (Exception ignored) { }
    return o;
  }

  private static String cap(String s) {
    if (s == null || s.length() == 0) return "";
    return Character.toUpperCase(s.charAt(0)) + s.substring(1).toLowerCase(Locale.ROOT);
  }

  private void pushBattery(Intent i) {
    if (!loaded) return;
    Intent in = i;
    if (in == null) in = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
    int level = -1, scale = 100;
    boolean charging = false;
    if (in != null) {
      level = in.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
      scale = in.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
      int st = in.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
      charging = st == BatteryManager.BATTERY_STATUS_CHARGING || st == BatteryManager.BATTERY_STATUS_FULL;
    }
    int pct = level < 0 ? -1 : Math.round(level * 100f / Math.max(scale, 1));
    js("setBattery(" + pct + "," + (charging ? "true" : "false") + ")");
  }

  private void pushApps() {
    if (!loaded) return;
    new Thread(new Runnable() {
      @Override public void run() {
        try {
          final PackageManager pm = getPackageManager();
          Intent q = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
          List<ResolveInfo> raw = pm.queryIntentActivities(q, 0);
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

          int px = Math.min(192, Math.max(96, dp(56)));
          JSONArray apps = new JSONArray();
          for (int k = 0; k < list.size(); k++) {
            ResolveInfo ri = list.get(k);
            String pkg = ri.activityInfo.packageName;
            if (pkg.equals(getPackageName())) continue;
            JSONObject o = new JSONObject();
            o.put("n", ri.loadLabel(pm).toString());
            o.put("p", pkg);
            o.put("i", png(ri.loadIcon(pm), px));
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
          t.printStackTrace();
        }
      }
    }).start();
  }

  private String pick(Intent i) {
    try {
      ResolveInfo r = getPackageManager().resolveActivity(i, PackageManager.MATCH_DEFAULT_ONLY);
      if (r == null || r.activityInfo == null) return "";
      String p = r.activityInfo.packageName;
      return getPackageManager().getLaunchIntentForPackage(p) != null ? p : "";
    } catch (Throwable t) { return ""; }
  }

  private String png(Drawable d, int size) {
    try {
      Bitmap bm = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
      Canvas c = new Canvas(bm);
      d.setBounds(0, 0, size, size);
      d.draw(c);
      ByteArrayOutputStream o = new ByteArrayOutputStream();
      bm.compress(Bitmap.CompressFormat.PNG, 100, o);
      bm.recycle();
      return Base64.encodeToString(o.toByteArray(), Base64.NO_WRAP);
    } catch (Throwable t) { return ""; }
  }

  private void pushWallpaper() {
    if (!loaded) return;
    new Thread(new Runnable() {
      @Override public void run() {
        try {
          if (wallpaperData == null) {
            WallpaperManager wm = (WallpaperManager) getSystemService(WALLPAPER_SERVICE);
            Drawable d = wm.getDrawable();
            if (d == null) { wallpaperData = ""; }
            else {
              DisplayMetrics dm = getResources().getDisplayMetrics();
              int bw = 720;
              int bh = Math.max(600, Math.round(720f * dm.heightPixels / Math.max(dm.widthPixels, 1)));
              Bitmap bm = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888);
              Canvas c = new Canvas(bm);
              d.setBounds(0, 0, bw, bh);
              d.draw(c);
              ByteArrayOutputStream o = new ByteArrayOutputStream();
              bm.compress(Bitmap.CompressFormat.JPEG, 82, o);
              bm.recycle();
              wallpaperData = Base64.encodeToString(o.toByteArray(), Base64.NO_WRAP);
            }
          }
          js("setWallpaper(" + JSONObject.quote(wallpaperData) + ")");
        } catch (Throwable t) {
          js("setWallpaper('')");
        }
      }
    }).start();
  }

  /* ---------------- page -> system ---------------- */

  public class Bridge {
    @JavascriptInterface public void launch(String pkg) {
      try {
        Intent i = getPackageManager().getLaunchIntentForPackage(pkg);
        if (i == null) return;
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        startActivity(i);
        overridePendingTransition(0, 0);
      } catch (Throwable ignored) { }
    }

    @JavascriptInterface public void appInfo(String pkg) {
      try {
        Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + pkg));
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(i);
        overridePendingTransition(0, 0);
      } catch (Throwable ignored) { }
    }

    @JavascriptInterface public void refresh() { pushApps(); }

    @JavascriptInterface public void tick() {
      runOnUiThread(new Runnable() {
        @Override public void run() { web.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP); }
      });
    }

    @JavascriptInterface public String info() { return systemJson().toString(); }
  }

  /* ---------------- lifecycle ---------------- */

  @Override public void onBackPressed() { js("goBack()"); }

  @Override protected void onNewIntent(Intent i) {
    super.onNewIntent(i);
    if (Intent.ACTION_MAIN.equals(i.getAction())) js("goHome()");
  }

  @Override protected void onResume() {
    super.onResume();
    hideStatusBar();
    wallpaperData = null;
    if (loaded) { js("onResumeL()"); pushBattery(null); }
  }

  @Override public void onConfigurationChanged(Configuration cfg) {
    super.onConfigurationChanged(cfg);
    pushSystem();
    js("onSysChange()");
  }

  @Override protected void onDestroy() {
    try { if (battReceiver != null) unregisterReceiver(battReceiver); } catch (Throwable ignored) { }
    try { if (pkgReceiver != null) unregisterReceiver(pkgReceiver); } catch (Throwable ignored) { }
    super.onDestroy();
  }
}
