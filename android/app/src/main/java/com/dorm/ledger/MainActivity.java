package com.dorm.ledger;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Base64;
import android.view.View;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.ProgressBar;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import java.io.File;
import java.util.Locale;

/**
 * 唯一的 Activity：一个承载网页的「壳」。
 *
 * 设计原则：网页端 JS / HTML / CSS 的业务逻辑、算法、数据结构与导出格式**完全不动**，
 * 这里只补齐 WebView 在移动端的能力与坑点：
 *   1. 软键盘：windowSoftInputMode=adjustResize + 手动把 IME 高度作为 WebView 底部边距，
 *      并把安全区 / 键盘高度通过 CSS 变量传给网页，保证输入框不被遮挡、视口不缩放。
 *   2. 安全区：Edge-to-Edge 沉浸式 + 把 systemBars 高度通过 --sat / --sab 传给网页 env(...)。
 *   3. 本地存储：强制开启 DOM Storage / Database，数据落在应用私有目录，卸载前不丢。
 *   4. 返回键：优先关闭网页弹层 → 再网页后退 → 最后才退出 App。
 *   5. 导入 / 导出：onShowFileChooser 走系统文件选择器；导出在网页侧被桥接脚本接管，
 *      由原生写入「下载」目录 / 应用目录并唤起系统分享。
 *   6. 旋转：Manifest 锁定竖屏 + configChanges，避免 Activity 重建导致数据丢失。
 *   7. 防白屏：网页打包进 assets 离线加载，配加载进度条与闪屏。
 */
public class MainActivity extends AppCompatActivity {

    /** 离线加载：网页与全部资源都打包在 assets 目录 */
    private static final String INDEX_URL = "file:///android_asset/index.html";

    private WebView webView;
    private ProgressBar progress;
    private View splash;

    /** 网页 <input type="file"> 触发的原生文件选择回调 */
    private ValueCallback<Uri[]> filePathCallback;

    /** 缓存最近一次的安全区 / 键盘尺寸，页面加载完成后需要重放一次 */
    private int lastTopInset = 0;
    private int lastBottomInset = 0;
    private int lastImeInset = 0;
    private boolean lastImeVisible = false;

    /** Android 11(API 30) 起 Edge-to-Edge 下 adjustResize 不再自动收缩窗口，需手动处理 IME */
    private boolean handleImeManually() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.R;
    }

    private final ActivityResultLauncher<Intent> fileChooserLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                ValueCallback<Uri[]> callback = filePathCallback;
                filePathCallback = null;
                if (callback == null) return;
                if (result.getResultCode() != RESULT_OK) {
                    callback.onReceiveValue(null);
                    return;
                }
                Intent data = result.getData();
                Uri[] uris = null;
                try {
                    if (data != null && data.getClipData() != null) {
                        ClipData clip = data.getClipData();
                        uris = new Uri[clip.getItemCount()];
                        for (int i = 0; i < clip.getItemCount(); i++) {
                            uris[i] = clip.getItemAt(i).getUri();
                        }
                    } else if (data != null && data.getData() != null) {
                        uris = new Uri[]{data.getData()};
                    }
                } catch (Exception e) {
                    uris = null;
                }
                callback.onReceiveValue(uris);
            });

    /** Android 9 及以下写公共「下载」目录需要的存储权限（授权结果不阻塞主流程） */
    private final ActivityResultLauncher<String> storagePermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
                // 忽略结果：授权后用户再次导出即可
            });

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // —— 沉浸式：内容铺满到状态栏 / 导航栏之下（配合网页 env(safe-area-inset-*)）——
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            getWindow().getAttributes().layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }
        setContentView(R.layout.activity_main);

        webView = findViewById(R.id.webview);
        progress = findViewById(R.id.progress);
        splash = findViewById(R.id.splash);

        // 页面为浅色背景，状态栏 / 导航栏图标用深色
        WindowInsetsControllerCompat controller =
                new WindowInsetsControllerCompat(getWindow(), getWindow().getDecorView());
        controller.setAppearanceLightStatusBars(true);
        controller.setAppearanceLightNavigationBars(true);

        setupWebView();
        setupWindowInsets();
        setupBackHandling();

        if (savedInstanceState != null && webView.restoreState(savedInstanceState) != null) {
            // 从系统回收中恢复，保留历史与页面状态
        } else {
            webView.loadUrl(INDEX_URL);
        }
    }

    // =====================================================================
    // WebView 初始化与设置
    // =====================================================================

    private void setupWebView() {
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);              // 网页本身依赖 JS
        settings.setDomStorageEnabled(true);              // 关键：localStorage 持久化
        settings.setDatabaseEnabled(true);                // 关键：WebSQL / IndexedDB 兼容
        settings.setLoadsImagesAutomatically(true);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);
        settings.setAllowFileAccessFromFileURLs(true);    // file:// 页面内的本地资源访问
        settings.setJavaScriptCanOpenWindowsAutomatically(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        settings.setTextZoom(100);                        // 固定文字缩放为 100%，避免被系统字号放大撑乱布局

        // 关闭缩放，避免软键盘聚焦时整体视口被放大
        settings.setSupportZoom(false);
        settings.setBuiltInZoomControls(false);
        settings.setDisplayZoomControls(false);
        settings.setUseWideViewPort(true);
        settings.setLoadWithOverviewMode(true);

        // 网页自带配色，禁用系统「强制深色」，防止配色被反相
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            settings.setForceDark(WebSettings.FORCE_DARK_OFF);
        }

        WebView.setWebContentsDebuggingEnabled(true);     // 便于用 chrome://inspect 调试

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                String scheme = request.getUrl().getScheme();
                if (scheme == null) return false;
                // 本地页面内的跳转（含 blob: / data:）交给 WebView 自己处理
                if (scheme.equals("file") || scheme.equals("about")
                        || scheme.equals("blob") || scheme.equals("data")) {
                    return false;
                }
                openExternally(request.getUrl());          // 外链（如帮助文档）交给系统浏览器
                return true;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                hideSplash();
                pushInsetsToWeb();                         // 页面脚本已就绪，重放一次安全区 / 键盘尺寸
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                progress.setProgress(newProgress);
                progress.setVisibility(
                        (newProgress >= 1 && newProgress <= 99) ? View.VISIBLE : View.GONE);
            }

            @Override
            public boolean onShowFileChooser(WebView webView,
                                             ValueCallback<Uri[]> filePathCallback,
                                             FileChooserParams fileChooserParams) {
                // 复用上一次未完成的选择器回调，避免回调泄漏
                if (MainActivity.this.filePathCallback != null) {
                    MainActivity.this.filePathCallback.onReceiveValue(null);
                }
                MainActivity.this.filePathCallback = filePathCallback;

                Intent intent = fileChooserParams.createIntent();
                try {
                    fileChooserLauncher.launch(intent);
                    return true;
                } catch (ActivityNotFoundException e) {
                    MainActivity.this.filePathCallback = null;
                    Toast.makeText(MainActivity.this, "没有可用的文件选择器", Toast.LENGTH_SHORT).show();
                    return false;
                }
            }
        });

        // 原生桥接：仅在网页侧主动调用时生效，不改变任何业务逻辑
        webView.addJavascriptInterface(new Bridge(), "AndroidBridge");

        // 极少数情况下网页直接触发下载（如未来新增 <a href> 下载），兜底处理
        webView.setDownloadListener((url, userAgent, contentDisposition, mimeType, contentLength) ->
                handleDirectDownload(url, mimeType));
    }

    // =====================================================================
    // 安全区 & 软键盘
    // =====================================================================

    private void setupWindowInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(webView, (view, insets) -> {
            androidx.core.graphics.Insets bars =
                    insets.getInsets(WindowInsetsCompat.Type.systemBars());
            androidx.core.graphics.Insets ime =
                    insets.getInsets(WindowInsetsCompat.Type.ime());
            boolean imeVisible = insets.isVisible(WindowInsetsCompat.Type.ime());

            lastTopInset = bars.top;
            lastBottomInset = bars.bottom;
            lastImeVisible = imeVisible;
            lastImeInset = imeVisible ? ime.bottom : 0;

            // 键盘弹出时把 WebView 的可视区底部抬起，等价于「窗口自适应收缩」：
            // 输入框不会被键盘遮挡，底部按钮也不会被顶飞。
            // 注意：键盘弹出的动画过程中 insets 会回调很多次，只有高度真正变化时才
            // 重新布局，否则每次回调都触发一次 WebView 重排，会连带触发 Chromium 反复
            // 执行「聚焦元素滚入可视区」，表现为页面持续上滑、越滑越多。
            int targetMargin = handleImeManually() ? lastImeInset : 0;
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) view.getLayoutParams();
            if (lp.bottomMargin != targetMargin) {
                lp.bottomMargin = targetMargin;
                view.setLayoutParams(lp);
            }

            pushInsetsToWeb();
            return insets;
        });
        ViewCompat.requestApplyInsets(webView);
    }

    /** 把安全区 / 键盘尺寸写入网页的 CSS 变量 */
    private void pushInsetsToWeb() {
        String js = "window.__applyInsets&&window.__applyInsets({"
                + "top:" + lastTopInset + ",bottom:" + lastBottomInset
                + ",ime:" + lastImeInset + ",imeVisible:" + lastImeVisible + "});";
        webView.evaluateJavascript(js, null);
    }

    // =====================================================================
    // 返回键：弹层 → 网页后退 → 退出
    // =====================================================================

    private void setupBackHandling() {
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                // 询问网页：当前是否有打开的弹层（照片放大层 / 裁剪层 / 照片气泡）
                webView.evaluateJavascript(
                        "(window.__hasOverlay&&window.__hasOverlay())?'1':'0'",
                        value -> {
                            if ("\"1\"".equals(value) || "1".equals(value)) {
                                // 网页自身已有「Esc 逐层关闭弹层」的逻辑，这里派发一个 Esc 即可
                                webView.evaluateJavascript(
                                        "window.__nativeBack&&window.__nativeBack();", null);
                            } else if (webView.canGoBack()) {
                                webView.goBack();
                            } else {
                                // 交还给系统：退出 App
                                setEnabled(false);
                                getOnBackPressedDispatcher().onBackPressed();
                            }
                        });
            }
        });
    }

    // =====================================================================
    // 文件导出（保存到「下载」目录 + 应用私有目录，并唤起系统分享）
    // =====================================================================

    /** 网页通过 AndroidBridge.saveBlob 传来的 base64 文件内容 */
    private void persistAndShare(final String fileName, final String mimeType, final byte[] bytes) {
        final String name = sanitizeFileName(fileName);
        final String mime = (mimeType == null || mimeType.trim().isEmpty())
                ? "application/octet-stream" : mimeType;

        // 写盘放到子线程，避免阻塞 WebView 的 JS 线程
        new Thread(() -> {
            boolean savedToDownloads;
            try {
                savedToDownloads = saveToDownloads(name, mime, bytes);
            } catch (Exception e) {
                savedToDownloads = false;
            }
            File privateFile;
            try {
                privateFile = saveToPrivate(name, bytes);
            } catch (Exception e) {
                privateFile = null;
            }

            final boolean ok = savedToDownloads;
            final File file = privateFile;
            runOnUiThread(() -> {
                String tip;
                if (ok && file != null) {
                    tip = "备份已保存到「下载」目录";
                } else if (file != null) {
                    tip = "备份已保存到应用目录";
                } else {
                    tip = "备份保存失败，请检查存储权限";
                }
                Toast.makeText(MainActivity.this, tip, Toast.LENGTH_LONG).show();
                // 调起系统分享，方便发送到微信 / QQ
                if (file != null) shareFile(file, mime);
            });
        }).start();
    }

    /**
     * 写入公共「下载」目录。
     * API 29+：走 MediaStore，无需任何权限；
     * API 24-28：直接写文件，需要 WRITE_EXTERNAL_STORAGE。
     */
    private boolean saveToDownloads(String name, String mime, byte[] bytes) throws Exception {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContentValues values = new ContentValues();
            values.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
            values.put(MediaStore.MediaColumns.MIME_TYPE, mime);
            values.put(MediaStore.MediaColumns.RELATIVE_PATH,
                    Environment.DIRECTORY_DOWNLOADS + "/宿舍账本");
            values.put(MediaStore.MediaColumns.IS_PENDING, 1);

            Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
            if (uri == null) return false;
            try {
                java.io.OutputStream os = getContentResolver().openOutputStream(uri);
                if (os == null) {
                    getContentResolver().delete(uri, null, null);
                    return false;
                }
                os.write(bytes);
                os.close();
            } catch (Exception e) {
                getContentResolver().delete(uri, null, null);
                return false;
            }
            values.clear();
            values.put(MediaStore.MediaColumns.IS_PENDING, 0);
            getContentResolver().update(uri, values, null, null);
            return true;
        }

        // Android 9 及以下
        if (checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
            storagePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE);
            return false;
        }
        File baseDir = Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DOWNLOADS);
        File dir = new File(baseDir, "宿舍账本");
        if (!dir.exists() && !dir.mkdirs()) return false;
        java.io.OutputStream os = new java.io.FileOutputStream(new File(dir, name));
        os.write(bytes);
        os.close();
        return true;
    }

    /** 写入应用私有目录（导出数据在卸载前一直保留，且可安全分享） */
    private File saveToPrivate(String name, byte[] bytes) {
        try {
            File dir = new File(getFilesDir(), "exports");
            if (!dir.exists()) dir.mkdirs();
            File file = new File(dir, name);
            java.io.OutputStream os = new java.io.FileOutputStream(file);
            os.write(bytes);
            os.close();
            return file;
        } catch (Exception e) {
            return null;
        }
    }

    /** 通过 FileProvider 把私有文件以 content:// 分享出去 */
    private void shareFile(File file, String mime) {
        try {
            Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", file);
            Intent intent = new Intent(Intent.ACTION_SEND);
            intent.setType(mime);
            intent.putExtra(Intent.EXTRA_STREAM, uri);
            intent.putExtra(Intent.EXTRA_SUBJECT, file.getName());
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(intent, getString(R.string.share_backup)));
        } catch (Exception e) {
            // 分享失败不影响已保存的备份文件
        }
    }

    /** 兜底：网页若直接触发下载（非桥接路径），这里转成原生保存 */
    private void handleDirectDownload(String url, String mimeType) {
        if (url == null) return;
        if (url.startsWith("data:")) {
            int comma = url.indexOf(',');
            if (comma <= 0) return;
            String meta = url.substring(5, comma);
            boolean isBase64 = meta.contains("base64");
            String payload = url.substring(comma + 1);
            byte[] bytes;
            try {
                bytes = isBase64
                        ? Base64.decode(payload, Base64.DEFAULT)
                        : payload.getBytes("UTF-8");
            } catch (Exception e) {
                return;
            }
            String mime = mimeType;
            if (mime == null || mime.trim().isEmpty()) {
                int semi = meta.indexOf(';');
                mime = (semi >= 0 ? meta.substring(0, semi) : meta);
                if (mime.trim().isEmpty()) mime = "application/octet-stream";
            }
            persistAndShare("宿舍账本备份-" + System.currentTimeMillis() + ".json", mime, bytes);
        } else {
            openExternally(Uri.parse(url));
        }
    }

    private void openExternally(Uri uri) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, uri));
        } catch (Exception e) {
            Toast.makeText(this, "无法打开该链接", Toast.LENGTH_SHORT).show();
        }
    }

    private String sanitizeFileName(String raw) {
        String base = (raw == null || raw.trim().isEmpty()) ? "宿舍账本备份.json" : raw;
        return base.replaceAll("[\\\\/:*?\"<>|\\u0000-\\u001F]", "_");
    }

    // =====================================================================
    // 原生桥接（暴露给网页 window.AndroidBridge）
    // 只提供能力，不改变网页任何业务逻辑
    // =====================================================================

    public class Bridge {

        /** 保存并分享网页生成的备份文件：fileName(含扩展名) / mime / base64 内容 */
        @JavascriptInterface
        public void saveBlob(String fileName, String mime, String base64) {
            byte[] bytes;
            try {
                bytes = Base64.decode(base64, Base64.DEFAULT);
            } catch (Exception e) {
                bytes = null;
            }
            if (bytes == null || bytes.length == 0) {
                runOnUiThread(() -> Toast.makeText(MainActivity.this,
                        "备份内容为空，导出失败", Toast.LENGTH_SHORT).show());
                return;
            }
            persistAndShare(fileName, mime, bytes);
        }

        /** 复制文本到系统剪贴板（file:// 页面拿不到 navigator.clipboard 时使用） */
        @JavascriptInterface
        public void copyText(String text) {
            ClipboardManager manager =
                    (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (manager == null) return;
            manager.setPrimaryClip(ClipData.newPlainText("宿舍账本", text == null ? "" : text));
        }

        /** 供网页做平台判断 */
        @JavascriptInterface
        public String platform() {
            return "android";
        }
    }

    // =====================================================================
    // 生命周期
    // =====================================================================

    private void hideSplash() {
        if (splash.getVisibility() != View.VISIBLE) return;
        splash.animate()
                .alpha(0f)
                .setDuration(220)
                .withEndAction(() -> splash.setVisibility(View.GONE))
                .start();
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        webView.saveState(outState);
    }

    @Override
    protected void onDestroy() {
        if (filePathCallback != null) {
            filePathCallback.onReceiveValue(null);
            filePathCallback = null;
        }
        if (webView != null) {
            if (webView.getParent() instanceof FrameLayout) {
                ((FrameLayout) webView.getParent()).removeView(webView);
            }
            webView.destroy();
        }
        super.onDestroy();
    }
}