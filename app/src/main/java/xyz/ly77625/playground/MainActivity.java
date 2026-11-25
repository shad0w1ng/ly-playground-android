package xyz.ly77625.playground;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.ValueCallback;
import android.webkit.WebBackForwardList;
import android.webkit.WebChromeClient;
import android.webkit.WebHistoryItem;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.Locale;

/**
 * 77625 Playground 的 Android 外壳。
 *
 * 设计原则（对应需求）：
 * 1. 只做“壳 + 视觉增强”，不重写业务：所有页面、接口、鉴权都来自 https://ly.77625.xyz 本身。
 * 2. 登录完全使用站点自带登录页；会话依赖 WebView 的 Cookie（与浏览器一致），
 *    进程被杀后依然保留，不需要任何自定义账号体系。
 * 3. 通过 assets/skin 注入的 CSS/JS 把 Playground 调整为聊天软件形态，
 *    不新增/替换任何 API 调用。
 */
public class MainActivity extends Activity {

    /** 入口直接落在 Playground 路由；未登录时站点自己会跳到 #/login。 */
    private static final String START_URL = "https://ly.77625.xyz/#/playground";
    private static final String SITE_HOST = "ly.77625.xyz";
    private static final String SITE_SUFFIX = ".77625.xyz";
    /** 这些域名交给系统浏览器，避免把外部站点困在应用里。 */
    private static final String[] EXTERNAL_HOSTS = {"qq.77625.xyz"};

    private static final int REQ_FILE_CHOOSER = 1201;
    private static final long EXIT_INTERVAL_MS = 2000L;
    private static final long LOADING_TIMEOUT_MS = 9000L;

    private WebView web;
    private View loadingPanel;
    private View errorPanel;
    private TextView errorText;

    private ValueCallback<Uri[]> fileCallback;

    private String skinCss = "";
    private String skinJs = "";

    private final Handler ui = new Handler(Looper.getMainLooper());

    private boolean errorVisible = false;
    private boolean readyNotified = false;
    private boolean seenConsole = false;
    private long lastBackPress = 0L;

    private final Runnable loadingTimeout = new Runnable() {
        @Override
        public void run() {
            // 兜底：即使站点没有回调 onReady，也不要把用户永远挡在加载页
            if (!readyNotified) hideLoading();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        applyWindowChrome();

        loadingPanel = findViewById(R.id.loading_panel);
        errorPanel = findViewById(R.id.error_panel);
        errorText = findViewById(R.id.error_text);
        findViewById(R.id.error_retry).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                retry();
            }
        });
        findViewById(R.id.error_external).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openExternal(Uri.parse(START_URL));
            }
        });

        skinCss = readAsset("skin/chat.css");
        skinJs = readAsset("skin/chat.js");

        web = findViewById(R.id.web);
        configureWebView();

        if (savedInstanceState != null) {
            // 进程被回收后恢复，避免白屏重载
            WebBackForwardList restored = web.restoreState(savedInstanceState);
            if (restored == null) web.loadUrl(START_URL);
            hideLoadingDelayed(300);
        } else {
            web.loadUrl(START_URL);
            ui.postDelayed(loadingTimeout, LOADING_TIMEOUT_MS);
        }
    }

    /* ------------------------------------------------------------------ */
    /* WebView 配置                                                        */
    /* ------------------------------------------------------------------ */

    @SuppressLint("SetJavaScriptEnabled")
    private void configureWebView() {
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(false);
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        s.setDisplayZoomControls(false);
        s.setMediaPlaybackRequiresUserGesture(true);
        s.setJavaScriptCanOpenWindowsAutomatically(false);
        // 站点登录走 /api/auth/0z/start 的整页跳转，需要支持新窗口回调
        s.setSupportMultipleWindows(true);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        s.setDefaultTextEncodingName("utf-8");
        // 保留浏览器 UA 主体，仅追加一段标识，方便站点日志排查
        s.setUserAgentString(s.getUserAgentString() + " LyPlayground/1.0");

        if (BuildConfig.DEBUG) {
            WebView.setWebContentsDebuggingEnabled(true);
        }

        web.setBackgroundColor(getColor(R.color.app_bg));
        web.setOverScrollMode(View.OVER_SCROLL_NEVER);
        web.setVerticalScrollBarEnabled(false);

        // 会话保持的关键：与浏览器一致的 Cookie 存储，且允许第三方 Cookie（0z 统一登录回跳需要）
        CookieManager cookies = CookieManager.getInstance();
        cookies.setAcceptCookie(true);
        cookies.setAcceptThirdPartyCookies(web, true);

        web.addJavascriptInterface(new Bridge(), "LyApp");

        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return handleUrl(request.getUrl());
            }

            @SuppressWarnings("deprecation")
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return handleUrl(Uri.parse(url));
            }

            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                readyNotified = false;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                injectSkin();
                hideLoadingDelayed(900);
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (!request.isForMainFrame()) return;
                // 站点内部跳转/取消加载会抛 ERR_ABORTED，这不是真的故障
                CharSequence desc = error.getDescription();
                if (desc != null && desc.toString().contains("ERR_ABORTED")) return;
                showError(describeError(error.getErrorCode()));
            }

            @Override
            public void onReceivedHttpError(WebView view, WebResourceRequest request, WebResourceResponse response) {
                if (request.isForMainFrame() && response.getStatusCode() >= 500) {
                    showError("服务器暂时不可用（HTTP " + response.getStatusCode() + "），请稍后重试");
                }
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback,
                                             FileChooserParams params) {
                if (fileCallback != null) {
                    fileCallback.onReceiveValue(null);
                }
                fileCallback = callback;

                Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType("*/*");
                String[] accept = params.getAcceptTypes();
                if (accept != null && accept.length > 0 && accept[0] != null
                        && accept[0].trim().length() > 0) {
                    intent.putExtra(Intent.EXTRA_MIME_TYPES, accept);
                }
                if (params.getMode() == FileChooserParams.MODE_OPEN_MULTIPLE) {
                    intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                }
                try {
                    startActivityForResult(Intent.createChooser(intent, "选择文件"), REQ_FILE_CHOOSER);
                    return true;
                } catch (ActivityNotFoundException e) {
                    fileCallback = null;
                    toast(getString(R.string.no_file_picker));
                    return false;
                }
            }

            @Override
            public void onPermissionRequest(PermissionRequest request) {
                // 只有应用已持有对应系统权限时才放行，避免页面随意拉起摄像头/麦克风
                boolean granted = true;
                for (String res : request.getResources()) {
                    if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(res)
                            && checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
                            != PackageManager.PERMISSION_GRANTED) {
                        granted = false;
                    }
                    if (PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(res)
                            && checkSelfPermission(android.Manifest.permission.CAMERA)
                            != PackageManager.PERMISSION_GRANTED) {
                        granted = false;
                    }
                }
                if (granted) {
                    request.grant(request.getResources());
                } else {
                    request.deny();
                }
            }

            @Override
            public boolean onCreateWindow(WebView view, boolean isDialog, boolean isUserGesture,
                                          Message resultMsg) {
                // target=_blank 的链接（文档外链、QQ 群等）交给系统浏览器
                WebView popup = new WebView(MainActivity.this);
                popup.setWebViewClient(new WebViewClient() {
                    @Override
                    public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                        openExternal(r.getUrl());
                        return true;
                    }

                    @SuppressWarnings("deprecation")
                    @Override
                    public boolean shouldOverrideUrlLoading(WebView v, String url) {
                        openExternal(Uri.parse(url));
                        return true;
                    }
                });
                WebView.WebViewTransport transport = (WebView.WebViewTransport) resultMsg.obj;
                transport.setWebView(popup);
                resultMsg.sendToTarget();
                return true;
            }
        });

        web.setDownloadListener(new android.webkit.DownloadListener() {
            @Override
            public void onDownloadStart(String url, String userAgent, String contentDisposition,
                                        String mimeType, long contentLength) {
                openExternal(Uri.parse(url));
            }
        });
    }

    /**
     * 路由策略：
     * - qq.77625.xyz 走系统浏览器；
     * - 其余 http/https 一律留在应用内（0z 统一登录会跳转到第三方域名，必须在同一个 WebView 里才能回跳并写入 Cookie）；
     * - tel/mailto/intent 等交给系统。
     */
    private boolean handleUrl(Uri uri) {
        if (uri == null) return false;
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);

        if ("http".equals(scheme) || "https".equals(scheme)) {
            for (String external : EXTERNAL_HOSTS) {
                if (host.equals(external)) {
                    openExternal(uri);
                    return true;
                }
            }
            // 其余 http(s) 全部留在应用内：0z 统一登录要跨域跳转再回跳，只有同一个 WebView 才能带上 Cookie
            return false;
        }

        openExternal(uri);
        return true;
    }

    private void openExternal(Uri uri) {
        if (uri == null) return;
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        try {
            Intent intent;
            if ("intent".equals(scheme)) {
                intent = Intent.parseUri(uri.toString(), Intent.URI_INTENT_SCHEME);
            } else {
                intent = new Intent(Intent.ACTION_VIEW, uri);
            }
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        } catch (Exception e) {
            toast("没有可以打开该链接的应用");
        }
    }

    /* ------------------------------------------------------------------ */
    /* 皮肤注入                                                            */
    /* ------------------------------------------------------------------ */

    private void injectSkin() {
        WebView view = web;
        if (view == null) return;

        if (!skinCss.isEmpty()) {
            String css = skinCss
                    .replace("\\", "\\\\")
                    .replace("'", "\\'")
                    .replace("\r", " ")
                    .replace("\n", " ");
            String code = "(function(){try{"
                    + "var s=document.getElementById('ly-skin');"
                    + "if(!s){s=document.createElement('style');s.id='ly-skin';"
                    + "(document.head||document.documentElement).appendChild(s);}"
                    + "s.textContent='" + css + "';"
                    + "}catch(e){}})();";
            view.evaluateJavascript(code, null);
        }

        if (!skinJs.isEmpty()) {
            view.evaluateJavascript(skinJs, null);
        }
    }

    private String readAsset(String name) {
        InputStream in = null;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            in = getAssets().open(name);
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            return out.toString("UTF-8");
        } catch (Exception e) {
            return "";
        } finally {
            try {
                if (in != null) in.close();
            } catch (Exception ignored) {
            }
        }
    }

    /* ------------------------------------------------------------------ */
    /* 加载 / 错误 / 重试                                                  */
    /* ------------------------------------------------------------------ */

    private void hideLoadingDelayed(long delayMs) {
        ui.removeCallbacks(loadingTimeout);
        ui.postDelayed(new Runnable() {
            @Override
            public void run() {
                hideLoading();
            }
        }, delayMs);
    }

    private void hideLoading() {
        if (loadingPanel == null || loadingPanel.getVisibility() != View.VISIBLE) return;
        loadingPanel.animate().alpha(0f).setDuration(180).withEndAction(new Runnable() {
            @Override
            public void run() {
                loadingPanel.setVisibility(View.GONE);
            }
        }).start();
    }

    private void showError(String message) {
        errorVisible = true;
        hideLoading();
        if (errorText != null) {
            errorText.setText(message == null ? getString(R.string.error_generic) : message);
        }
        if (errorPanel != null) {
            errorPanel.setAlpha(0f);
            errorPanel.setVisibility(View.VISIBLE);
            errorPanel.animate().alpha(1f).setDuration(160).start();
        }
    }

    private void retry() {
        errorVisible = false;
        if (errorPanel != null) errorPanel.setVisibility(View.GONE);
        if (loadingPanel != null) {
            loadingPanel.setAlpha(1f);
            loadingPanel.setVisibility(View.VISIBLE);
        }
        readyNotified = false;
        ui.removeCallbacks(loadingTimeout);
        ui.postDelayed(loadingTimeout, LOADING_TIMEOUT_MS);

        if (web == null) return;
        String current = web.getUrl();
        if (current == null || current.startsWith("about:")) {
            web.loadUrl(START_URL);
        } else {
            web.reload();
        }
    }

    private String describeError(int code) {
        switch (code) {
            case WebViewClient.ERROR_HOST_LOOKUP:
                return getString(R.string.error_dns);
            case WebViewClient.ERROR_CONNECT:
                return getString(R.string.error_connect);
            case WebViewClient.ERROR_TIMEOUT:
                return getString(R.string.error_timeout);
            default:
                return getString(R.string.error_generic);
        }
    }

    /* ------------------------------------------------------------------ */
    /* 返回键                                                              */
    /* ------------------------------------------------------------------ */

    @SuppressWarnings("deprecation")
    @Override
    public void onBackPressed() {
        if (errorVisible || web == null) {
            super.onBackPressed();
            return;
        }
        final WebView view = web;
        view.evaluateJavascript(
                "(function(){try{return (window.__lyBack&&window.__lyBack())?'1':'0';}catch(e){return '0';}})()",
                new ValueCallback<String>() {
                    @Override
                    public void onReceiveValue(String value) {
                        String v = value == null ? "" : value.replace("\"", "").trim();
                        if ("1".equals(v)) return;
                        if (hasOtherDocumentBehind(view)) {
                            view.goBack();
                            return;
                        }
                        confirmExit();
                    }
                });
    }

    /**
     * 只有“上一条历史是另一个文档”时才真正 goBack。
     * 本站是 hash 路由单页应用，路由切换只产生同文档历史，
     * 那种情况下应该退出应用，而不是在 #/login 与 #/playground 之间来回弹。
     */
    private boolean hasOtherDocumentBehind(WebView view) {
        WebBackForwardList list = view.copyBackForwardList();
        int index = list.getCurrentIndex();
        if (index <= 0) return false;
        WebHistoryItem current = list.getCurrentItem();
        WebHistoryItem previous = list.getItemAtIndex(index - 1);
        if (current == null || previous == null) return false;
        String a = stripFragment(current.getUrl());
        String b = stripFragment(previous.getUrl());
        return a != null && b != null && !a.equals(b);
    }

    private String stripFragment(String url) {
        if (url == null) return null;
        int i = url.indexOf('#');
        return i >= 0 ? url.substring(0, i) : url;
    }

    private void confirmExit() {
        long now = System.currentTimeMillis();
        if (now - lastBackPress < EXIT_INTERVAL_MS) {
            finish();
            return;
        }
        lastBackPress = now;
        toast(getString(R.string.press_again_to_exit));
    }

    /* ------------------------------------------------------------------ */
    /* 生命周期                                                            */
    /* ------------------------------------------------------------------ */

    @Override
    protected void onPause() {
        super.onPause();
        // 把内存里的 Cookie 落盘，保证“杀进程后再打开仍是登录态”
        try {
            CookieManager.getInstance().flush();
        } catch (Exception ignored) {
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        if (web != null) web.saveState(outState);
    }

    @Override
    protected void onDestroy() {
        ui.removeCallbacksAndMessages(null);
        if (web != null) {
            ViewGroup parent = (ViewGroup) web.getParent();
            if (parent != null) parent.removeView(web);
            web.stopLoading();
            web.setWebChromeClient(null);
            web.setWebViewClient(null);
            web.removeJavascriptInterface("LyApp");
            web.clearHistory();
            web.destroy();
            web = null;
        }
        super.onDestroy();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQ_FILE_CHOOSER) {
            if (fileCallback == null) return;
            Uri[] result = null;
            if (resultCode == RESULT_OK && data != null) {
                if (data.getClipData() != null) {
                    int count = data.getClipData().getItemCount();
                    result = new Uri[count];
                    for (int i = 0; i < count; i++) {
                        result[i] = data.getClipData().getItemAt(i).getUri();
                    }
                } else if (data.getData() != null) {
                    result = new Uri[]{data.getData()};
                }
            }
            fileCallback.onReceiveValue(result);
            fileCallback = null;
            return;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    /* ------------------------------------------------------------------ */
    /* 外观                                                                */
    /* ------------------------------------------------------------------ */

    private void applyWindowChrome() {
        Window window = getWindow();
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
        window.setStatusBarColor(getColor(R.color.app_bg));
        window.setNavigationBarColor(getColor(R.color.surface));

        View decor = window.getDecorView();
        int flags = decor.getSystemUiVisibility();
        flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;      // 浅色状态栏图标（API 23+）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        }
        decor.setSystemUiVisibility(flags);
    }

    /* ------------------------------------------------------------------ */
    /* JS 桥接                                                             */
    /* ------------------------------------------------------------------ */

    void toast(String text) {
        if (text == null || text.length() == 0) return;
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show();
    }

    private boolean trustedPage() {
        if (web == null) return false;
        String url = web.getUrl();
        if (url == null) return false;
        String host = Uri.parse(url).getHost();
        if (host == null) return false;
        host = host.toLowerCase(Locale.ROOT);
        return host.equals(SITE_HOST) || host.endsWith(SITE_SUFFIX);
    }

    /**
     * 只暴露极少量、无数据能力的回调；
     * 并且校验当前页面必须属于本站，避免外部页面调用。
     */
    public class Bridge {
        @JavascriptInterface
        public void onReady() {
            ui.post(new Runnable() {
                @Override
                public void run() {
                    if (!trustedPage()) return;
                    readyNotified = true;
                    hideLoading();
                }
            });
        }

        @JavascriptInterface
        public void onRouteChanged(final String route) {
            ui.post(new Runnable() {
                @Override
                public void run() {
                    if (!trustedPage()) return;
                    handleRoute(route);
                }
            });
        }

        @JavascriptInterface
        public void toast(final String text) {
            ui.post(new Runnable() {
                @Override
                public void run() {
                    if (!trustedPage()) return;
                    MainActivity.this.toast(text);
                }
            });
        }
    }

    /** 感知路由，用于“登录态失效”提示；首次进入未登录时不会误报。 */
    private void handleRoute(String route) {
        if (route == null) return;
        String r = route.trim();
        if (r.length() == 0 || "home".equals(r) || "docs".equals(r) || r.startsWith("docs/")) return;

        if ("login".equals(r) || "register".equals(r)) {
            if (seenConsole) {
                seenConsole = false;
                toast(getString(R.string.session_expired));
            }
            return;
        }
        seenConsole = true;
    }
}
