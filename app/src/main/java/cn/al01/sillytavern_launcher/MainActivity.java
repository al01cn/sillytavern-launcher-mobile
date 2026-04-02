package cn.al01.sillytavern_launcher;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;
import android.view.LayoutInflater;
import org.json.JSONObject;

import android.view.View;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;

import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.List;
import java.util.Objects;

import android.system.Os;
import android.system.ErrnoException;

import java.io.BufferedReader;
import java.io.InputStreamReader;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "NodeJS";
    private static final int REQ_WRITE_STORAGE = 100;

    private AlertDialog progressDialog;
    private ProgressBar progressBar;
    private TextView progressMessage;

    private Button btnLaunch;
    private WebView webView;
    private View mainLayout;

    // 日志 UI 组件
    private TextView tvLog;
    private TextView tvLogPath;
    private ScrollView scrollLog;
    private Button btnClearLog;

    private volatile boolean gitAvailable = false;
    private String gitFailureReason = "";

    private final AppLogger logger = AppLogger.getInstance();
    private ExtensionInstaller extensionInstaller;


    private static boolean nodeLibLoaded = false;

    private static boolean nativeLibLoaded = false;

    static {
        try {
            System.loadLibrary("node");
            nodeLibLoaded = true;
        } catch (UnsatisfiedLinkError e) {
            Log.e(TAG, "无法加载 node 库: " + e.getMessage(), e);
        }

        try {
            System.loadLibrary("native-lib");
            nativeLibLoaded = true;
        } catch (UnsatisfiedLinkError e) {
            Log.e(TAG, "无法加载 native-lib 库: " + e.getMessage(), e);
        }
    }


    public native int startNodeWithArguments(String[] arguments);
    public native int chdirNative(String path);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // ---- 初始化日志系统 ----
        requestStoragePermissionIfNeeded();
        logger.init(this);
        logger.i(TAG, "===== 应用启动 =====");

        // ---- 初始化 UI ----
        btnLaunch = findViewById(R.id.btn_launch);
        webView = findViewById(R.id.webview_main);
        mainLayout = findViewById(R.id.main_container);
        tvLog = findViewById(R.id.tv_log);
        tvLogPath = findViewById(R.id.tv_log_path);
        scrollLog = findViewById(R.id.scroll_log);
        btnClearLog = findViewById(R.id.btn_clear_log);

        // 显示日志文件路径
        updateLogPathHint();

        // 清空日志按钮
        btnClearLog.setOnClickListener(v -> {
            tvLog.setText("");
            logger.i(TAG, "---- 日志已清空 ----");
        });

        // 注册日志监听，新日志追加到 UI
        logger.addListener(line -> runOnUiThread(() -> appendLogLine(line)));

        // 把已有缓存都刷到 UI（init 里写的那条）
        List<String> cached = logger.getBuffer();
        for (String l : cached) {
            appendLogLine(l);
        }

        // ---- 基础 native 库加载状态 ----
        logNativeLibraryStatus();

        // ---- 基础环境 ----
        setupBaseEnvironment();


        // ---- 打印系统架构信息 ----
        logSystemInfo();
        emitStartupSelfCheckReport("onCreate", SillyTavern.getRootFolder(this), null);

        initWebView();


        btnLaunch.setEnabled(false);

        // ---- 检查资源 ----
        checkResourcesAndPrepare();

        // ---- 启动按钮 ----
        btnLaunch.setOnClickListener(v -> {
            btnLaunch.setEnabled(false);
            btnLaunch.setText("正在启动...");
            logger.i(TAG, "用户点击启动按钮");
            if (!gitAvailable) {
                logger.w(TAG, "无 Git 模式下启动：扩展安装不可用");
            }
            startSillyTavern();
            watchSillyTavernOutput();
        });

    }

    /** 更新日志文件路径提示 */
    private void updateLogPathHint() {
        File f = logger.getCurrentLogFile();
        if (f != null && tvLogPath != null) {
            tvLogPath.setText("日志: " + f.getAbsolutePath());
        }
    }

    /** 向日志 TextView 末尾追加一行，并自动滚动到底 */
    private void appendLogLine(String line) {
        if (tvLog == null) return;
        // 给不同级别加颜色（简单做法：通过 SpannableString 也行，这里先用纯文本 + 换行）
        tvLog.append(line + "\n");
        // 滚到底部
        if (scrollLog != null) {
            scrollLog.post(() -> scrollLog.fullScroll(View.FOCUS_DOWN));
        }
    }

    /** Android 9 及以下需要申请 WRITE_EXTERNAL_STORAGE 权限 */
    private void requestStoragePermissionIfNeeded() {
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                        new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE},
                        REQ_WRITE_STORAGE);
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_WRITE_STORAGE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                // 权限获取后重新 init，若之前已 init 且 mFileWriter==null，则重试
                logger.i(TAG, "存储权限已授予");
                if (logger.getCurrentLogFile() == null) {
                    logger.init(this);
                    updateLogPathHint();
                }
            } else {
                logger.w(TAG, "存储权限被拒绝，日志将仅保留在内存中");
            }
        }
    }

    /** 输出当前 native 库加载状态，便于定位 UnsatisfiedLinkError */
    private void logNativeLibraryStatus() {
        logger.i(TAG, "native 加载状态: node=" + (nodeLibLoaded ? "OK" : "FAILED")
                + ", native-lib=" + (nativeLibLoaded ? "OK" : "FAILED"));
        if (!nodeLibLoaded) {
            logger.e(TAG, "关键库 node 未加载，后续 Node.js 启动一定失败");
        }
    }

    private void emitStartupSelfCheckReport(String phase, File stFolder, Boolean gitReady) {
        try {
            File filesDir = getFilesDir();
            File serverJs = new File(stFolder, "server.js");
            File gitRoot = new File(filesDir, "git");
            File gitCore = new File(gitRoot, "libexec/git-core");
            File gitMain = new File(gitCore, "git");
            File soRoot = new File(filesDir, "lib");
            File cacert = new File(filesDir, "cacert.pem");

            logger.i(TAG, "========== 启动自检报告 [" + phase + "] ==========");
            logger.i(TAG, "库加载: node=" + (nodeLibLoaded ? "OK" : "FAILED")
                    + ", native-lib=" + (nativeLibLoaded ? "OK" : "FAILED"));
            logger.i(TAG, "源码目录: " + stFolder.getAbsolutePath() + " (exists=" + stFolder.exists() + ")");
            logger.i(TAG, "入口脚本: " + serverJs.getAbsolutePath() + " (exists=" + serverJs.exists() + ")");
            logger.i(TAG, "Git 根目录: " + gitRoot.getAbsolutePath() + " (exists=" + gitRoot.exists() + ")");
            logger.i(TAG, "Git 主程序: " + gitMain.getAbsolutePath() + " (exists=" + gitMain.exists()
                    + ", size=" + (gitMain.exists() ? gitMain.length() : 0) + ")");
            logger.i(TAG, "SO 目录: " + soRoot.getAbsolutePath() + " (exists=" + soRoot.exists() + ")");
            logger.i(TAG, "证书文件: " + cacert.getAbsolutePath() + " (exists=" + cacert.exists() + ")");
            logger.i(TAG, "环境变量: HOME=" + safeEnv("HOME")
                    + ", GIT_EXEC_PATH=" + safeEnv("GIT_EXEC_PATH")
                    + ", LD_LIBRARY_PATH=" + safeEnv("LD_LIBRARY_PATH"));
            if (gitReady != null) {
                logger.i(TAG, "Git setup 结果: " + (gitReady ? "OK" : "FAILED"));
            }
            logger.i(TAG, "========================================");
        } catch (Exception e) {
            logger.w(TAG, "生成启动自检报告失败: " + e.getMessage());
        }
    }

    private String safeEnv(String key) {
        String val = Os.getenv(key);
        if (val == null || val.isEmpty()) return "<empty>";
        return val;
    }

    /**
     * 输出系统架构及设备信息到日志，方便排查架构适配问题
     */
    private void logSystemInfo() {


        logger.i(TAG, "========== 系统信息 ==========");
        logger.i(TAG, "设备型号: " + Build.MODEL + " (" + Build.PRODUCT + ")");
        logger.i(TAG, "Android 版本: " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")");

        // CPU ABI（首选 ABI）
        String primaryAbi = Build.SUPPORTED_ABIS[0];
        logger.i(TAG, "首选 ABI: " + primaryAbi);
        logger.i(TAG, "所有支持的 ABI: " + String.join(", ", Build.SUPPORTED_ABIS));

        // 检测 32/64 位
        boolean is64bit = primaryAbi.contains("64") || primaryAbi.contains("arm64") || primaryAbi.contains("x86_64");
        logger.i(TAG, "运行模式: " + (is64bit ? "64-bit" : "32-bit"));

        // CPU 核心数
        int cores = Runtime.getRuntime().availableProcessors();
        logger.i(TAG, "CPU 核心数: " + cores);

        // JVM 内存情况
        Runtime rt = Runtime.getRuntime();
        long maxMem = rt.maxMemory() / (1024 * 1024);
        long totalMem = rt.totalMemory() / (1024 * 1024);
        long freeMem = rt.freeMemory() / (1024 * 1024);
        logger.i(TAG, "JVM 最大内存: " + maxMem + " MB");
        logger.i(TAG, "JVM 当前已分配: " + totalMem + " MB (可用 " + freeMem + " MB)");

        // 架构适配提示
        logger.i(TAG, "原生库架构: " + System.getProperty("os.arch"));
        logger.i(TAG, "=============================");
    }

    private void initWebView() {
        WebSettings settings = webView.getSettings();
        extensionInstaller = new ExtensionInstaller(this);




        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);

        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);
        settings.setAllowFileAccessFromFileURLs(true);
        settings.setAllowUniversalAccessFromFileURLs(true);

        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);
        settings.setSupportZoom(true);
        settings.setBuiltInZoomControls(true);
        settings.setDisplayZoomControls(false);

        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);

        webView.addJavascriptInterface(new ExtensionBridge(), "AndroidExtensionBridge");

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return false;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                injectExtensionInterceptor();
            }

            @Override
            public void onReceivedError(WebView view, int errorCode, String description, String failingUrl) {
                logger.e(TAG, "WebView 错误: " + description + " 地址: " + failingUrl);
            }
        });

        WebView.setWebContentsDebuggingEnabled(true);
        logger.i(TAG, "WebView 初始化完成");
    }

    private void injectExtensionInterceptor() {
        String script = "(() => {" +
                " if (window.__extensionInterceptorInstalled) return;" +
                " window.__extensionInterceptorInstalled = true;" +
                " const originalFetch = window.fetch;" +
                " window.fetch = async function(input, init = {}) {" +
                "   try {" +
                "     const url = typeof input === 'string' ? input : (input instanceof Request ? input.url : '');" +
                "     const method = (init.method || (input.method ? input.method : 'GET')).toUpperCase();" +
                "     if (url && url.includes('/api/extensions/install') && method === 'POST') {" +
                "       const body = init.body ? init.body : (input.body ? await input.clone().text() : '');" +
                "       const response = await window.AndroidExtensionBridge.installExtension(body || '{}');" +
                "       const parsed = JSON.parse(response);" +
                "       const payload = JSON.stringify(parsed.body || {});" +
                "       const headers = new Headers({'Content-Type': 'application/json'});" +
                "       return new Response(payload, { status: parsed.status || 200, headers });" +
                "     }" +
                "   } catch (err) {" +
                "     console.error('[ExtensionInterceptor] Failed to intercept', err);" +
                "   }" +
                "   return originalFetch.call(this, input, init);" +
                " };" +
                " console.log('[ExtensionInterceptor] Installed');" +
                "})();";
        webView.evaluateJavascript(script, null);
    }

    private class ExtensionBridge {
        @JavascriptInterface
        public String installExtension(String payload) {
            InstallResultHolder holder = new InstallResultHolder();
            Thread worker = new Thread(() -> {
                ExtensionInstaller.InstallResult result = extensionInstaller.installFromJson(payload);
                holder.json = result.toJson();
                logger.i(TAG, "扩展安装回调: " + result.message + " -> " + result.targetPath);

            });
            try {
                worker.start();
                worker.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            if (holder.json == null) {
                holder.json = "{\"status\":500}";
            }
            return holder.json;
        }
    }

    private static class InstallResultHolder {
        volatile String json;
    }



    private void setupBaseEnvironment() {
        try {
            String filesDir = getFilesDir().getAbsolutePath();
            Os.setenv("HOME", filesDir, true);
            Os.setenv("NODE_PATH", filesDir + "/node_modules", true);
            logger.i(TAG, "基础环境已设置，HOME=" + filesDir);
        } catch (ErrnoException e) {
            logger.e(TAG, "基础环境设置失败", e);
        }
    }

    /**
     * 初始化配置文件：如果酒馆里没有 settings.json，就从 assets 复制过去
     */
    private void initializeSettingsFile() {
        File defaultUserDir = new File(SillyTavern.getRootFolder(this), "data/default-user");
        File settingsFile = new File(defaultUserDir, "settings.json");

        if (!settingsFile.exists()) {
            logger.i(TAG, "Settings 文件不存在，正在从 assets 复制默认配置...");

            if (!defaultUserDir.exists()) {
                defaultUserDir.mkdirs();
            }

            try (InputStream is = getAssets().open("st_config/settings.json");
                 FileOutputStream fos = new FileOutputStream(settingsFile)) {

                byte[] buffer = new byte[8192];
                int len;
                while ((len = is.read(buffer)) > 0) {
                    fos.write(buffer, 0, len);
                }

                logger.i(TAG, "Settings 文件复制成功: " + settingsFile.getAbsolutePath());
            } catch (Exception e) {
                logger.e(TAG, "复制 Settings 文件失败", e);
            }
        } else {
            logger.i(TAG, "Settings 文件已存在，跳过初始化");
        }
    }

    private void checkResourcesAndPrepare() {
        File stFolder = SillyTavern.getRootFolder(this);
        File serverJs = new File(stFolder, "server.js");

        if (!stFolder.exists() || !serverJs.exists()) {
            logger.i(TAG, "酒馆源码不存在，开始解压...");
            showProgressDialog();
            new Thread(() -> {
                boolean success = SillyTavern.unzipSource(this, this::updateProgress);
                runOnUiThread(() -> {
                    if (progressDialog != null) progressDialog.dismiss();
                    if (success) {
                        logger.i(TAG, "解压完成，开始初始化配置");
                        initializeSettingsFile();
                        prepareGitEnvironment();
                    } else {
                        logger.e(TAG, "解压酒馆源码失败");
                        showErrorDialog("解压酒馆源码失败");
                        btnLaunch.setText("资源缺失");
                    }
                });
            }).start();
        } else {
            logger.i(TAG, "酒馆源码已存在，跳过解压");
            prepareGitEnvironment();
        }
    }

    private void prepareGitEnvironment() {
        File stFolder = SillyTavern.getRootFolder(this);
        runOnUiThread(() -> btnLaunch.setText("正在配置 Git..."));
        logger.i(TAG, "开始配置 Git 环境...");
        new Thread(() -> {
            boolean gitReady = GitManager.setup(this);
            gitAvailable = gitReady;
            gitFailureReason = gitReady ? "" : GitManager.getLastErrorSummary();
            runOnUiThread(() -> {
                if (gitReady) {
                    logger.i(TAG, "Git 环境配置成功");
                    emitStartupSelfCheckReport("after-git-setup", stFolder, true);
                    ensureGitRepo(stFolder);
                } else {
                    if (gitFailureReason == null || gitFailureReason.trim().isEmpty()) {
                        gitFailureReason = "未知原因（请查看 GitManager 详细日志）";
                    }
                    logger.e(TAG, "Git 环境配置失败: " + gitFailureReason);
                    emitStartupSelfCheckReport("after-git-setup", stFolder, false);
                    enterGitDegradedMode();
                }
            });
        }).start();
    }



    private void startSillyTavern() {
        File stFolder = SillyTavern.getRootFolder(this);
        logger.i(TAG, "正在应用 JS Polyfill 补丁...");
        new Thread(() -> {
            Polyfill.applyPatch(stFolder);
            logger.i(TAG, "Polyfill 完成，正在启动 Node.js...");
            File entry = SillyTavern.findEntryScript(stFolder, "server.js");
            if (entry != null) {
                logger.i(TAG, "启动脚本: " + entry.getAbsolutePath());
                emitStartupSelfCheckReport("before-node-start", stFolder, null);
                chdirNative(Objects.requireNonNull(entry.getParentFile()).getAbsolutePath());
                startNodeWithArguments(SillyTavern.getLaunchArguments(entry.getAbsolutePath()));
            } else {

                logger.e(TAG, "找不到 server.js 入口脚本");
            }
        }).start();
    }

    /**
     * 监听 Node.js 的 stdout/stderr 输出（通过 logcat）
     * 当检测到酒馆的 "Go to: <a href="http://localhost:XXXX/">http://localhost:XXXX/</a>" 信号时，打开 WebView
     */
    private void watchSillyTavernOutput() {
        new Thread(() -> {
            if (!gitAvailable) {
                logger.w(TAG, "无 Git 模式：扩展安装与仓库同步不可用");
            }
            Process logcatProcess = null;
            try {
                // 用 -T 1 从最新一条开始读，避免读到历史日志
                logcatProcess = new ProcessBuilder(
                        "logcat", "-T", "1", "-s", "NodeJS-Output:I"
                ).redirectErrorStream(true).start();

                BufferedReader reader = new BufferedReader(
                        new InputStreamReader(logcatProcess.getInputStream()));

                logger.i(TAG, "开始监听酒馆启动日志...");
                String line;
                while ((line = reader.readLine()) != null) {

                    // logcat 格式: "I/NodeJS-Output( pid): 实际内容"
                    // 提取冒号后面的实际输出内容
                    String content = extractLogcatContent(line);
                    if (content == null || content.isEmpty()) continue;

                    // 转发给 AppLogger，在 UI 和日志文件中显示
                    logger.raw(content.trim());

                    // 检测酒馆 ready 信号
                    if (content.contains("Go to: http://localhost:") && content.contains("to open SillyTavern")) {
                        // 提取端口号
                        int port = extractPortFromLine(content);
                        if (port > 0) {
                            logger.i(TAG, "检测到酒馆就绪信号，端口: " + port);
                            onSillyTavernReady(port);
                            break;
                        }
                    }
                }
            } catch (Exception e) {
                logger.e(TAG, "logcat 监听异常", e);
            } finally {
                if (logcatProcess != null) {
                    logcatProcess.destroy();
                }
            }
        }, "ST-logcat-watcher").start();
    }

    /**
     * 从 logcat 格式行中提取实际日志内容
     * 长格式: "04-01 10:51:00.911  4670  4887 I NodeJS-Output : 酒馆输出内容"
     * 短格式: "I/NodeJS-Output( 1234): 酒馆输出内容"
     */
    private String extractLogcatContent(String logcatLine) {
        // 先试长格式：找 "NodeJS-Output : " 或 tag 后的 " : "
        // logcat 长格式: 日期 时间 pid tid 级别 TAG : 内容
        int idx1 = logcatLine.indexOf("NodeJS-Output : ");
        if (idx1 >= 0) {
            return logcatLine.substring(idx1 + "NodeJS-Output : ".length());
        }

        // 再试短格式: "I/NodeJS-Output( pid): 内容"
        int idx2 = logcatLine.indexOf("): ");
        if (idx2 >= 0 && idx2 + 3 < logcatLine.length()) {
            return logcatLine.substring(idx2 + 3);
        }

        // 兜底：直接返回整行（去掉前后空白）
        String trimmed = logcatLine.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /**
     * 从酒馆的 ready 行中提取端口号
     * 例如 "Go to: <a href="http://localhost:11451/">http://localhost:11451/</a> to open SillyTavern" -> 11451
     */
    private int extractPortFromLine(String line) {
        try {
            int start = line.indexOf("localhost:");
            if (start < 0) return 0;
            start += "localhost:".length();
            int end = start;
            while (end < line.length() && Character.isDigit(line.charAt(end))) {
                end++;
            }
            if (end > start) {
                return Integer.parseInt(line.substring(start, end));
            }
        } catch (NumberFormatException e) {
            // ignore
        }
        return 0;
    }

    /**
     * 酒馆就绪后，延迟切换到 WebView
     */
    private void onSillyTavernReady(int port) {
        try {
            Thread.sleep(800); // 给酒馆一点时间完成初始化
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        runOnUiThread(() -> {
            mainLayout.setVisibility(View.GONE);
            webView.setVisibility(View.VISIBLE);
            String url = "http://127.0.0.1:" + port;
            webView.loadUrl(url);
            logger.i(TAG, "WebView 已加载 " + url);
        });
    }

    @Override
    public void onBackPressed() {
        if (webView.getVisibility() == View.VISIBLE && webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }

    /**
     * 自动初始化 Git 仓库
     */
    private void ensureGitRepo(File folder) {
        if (!gitAvailable) {
            logger.w(TAG, "gitAvailable=false，直接启用启动按钮");
            btnLaunch.setEnabled(true);
            btnLaunch.setText("一键启动酒馆 (无 Git)");
            return;
        }

        File gitDir = new File(folder, ".git");
        if (!gitDir.exists()) {
            logger.i(TAG, "Git 仓库不存在，开始初始化: " + folder.getAbsolutePath());
            if (!gitAvailable) {
                logger.w(TAG, "当前处于无 Git 模式，跳过 git init");
            } else {
                try {
                    btnLaunch.setEnabled(false);
                    btnLaunch.setText("正在初始化 Git 元数据");

                    File gitExecFile = GitManager.getLastGitExecutable();
                    String gitExec;
                    if (gitExecFile != null && gitExecFile.exists()) {
                        gitExec = gitExecFile.getAbsolutePath();
                        logger.i(TAG, "git init 复用已验证可执行: " + gitExec);
                    } else {
                        gitExec = new File(getFilesDir(), "git/libexec/git-core/git").getAbsolutePath();
                        logger.w(TAG, "GitManager 尚未缓存路径，回退到 filesDir: " + gitExec);
                    }

                    ProcessBuilder pb = new ProcessBuilder(gitExec, "init");

                    String currentPath = Os.getenv("PATH");
                    String nativeHint = Os.getenv("ANDROID_NATIVE_PATH");
                    if (!TextUtils.isEmpty(currentPath)) {
                        logger.i(TAG, "git init PATH=" + currentPath);
                    }
                    if (!TextUtils.isEmpty(nativeHint)) {
                        logger.i(TAG, "git init ANDROID_NATIVE_PATH=" + nativeHint);
                    }

                    pb.environment().put("PATH", currentPath);
                    pb.environment().put("LD_LIBRARY_PATH", Os.getenv("LD_LIBRARY_PATH"));
                    pb.environment().put("HOME", Os.getenv("HOME"));
                    pb.environment().put("GIT_EXEC_PATH", Os.getenv("GIT_EXEC_PATH"));
                    pb.environment().put("ANDROID_NATIVE_PATH", nativeHint);
                    pb.environment().put("GIT_PREFERRED_BIN", Os.getenv("GIT_PREFERRED_BIN"));
                    pb.environment().put("GIT_BINARY", Os.getenv("GIT_BINARY"));




                    pb.directory(folder);
                    pb.redirectErrorStream(true);
                    Process p = pb.start();
                    p.waitFor();
                    logger.i(TAG, "git init 完成，退出码=" + p.exitValue());

                } catch (Exception e) {
                    logger.e(TAG, "git init 失败", e);
                }
            }
        } else {
            logger.i(TAG, "Git 仓库已存在，跳过初始化");
        }

        if (!gitAvailable) {
            logger.w(TAG, "无 Git 模式，无需重复 setup");
            return;
        }

        new Thread(() -> {
            boolean gitReady = GitManager.setup(this);
            gitAvailable = gitReady;
            runOnUiThread(() -> {
                if (gitReady) {
                    logger.i(TAG, "Git 就绪，启动按钮已启用");
                    logger.i(TAG, "Final PATH=" + Os.getenv("PATH"));
                    logger.i(TAG, "Final ANDROID_NATIVE_PATH=" + Os.getenv("ANDROID_NATIVE_PATH"));

                    btnLaunch.setEnabled(true);
                    btnLaunch.setText("一键启动酒馆");
                } else {
                    logger.e(TAG, "Git 最终检查失败");
                    enterGitDegradedMode();
                }

            });
        }).start();
    }

    private void enterGitDegradedMode() {
        gitAvailable = false;
        runOnUiThread(() -> {
            btnLaunch.setEnabled(true);
            btnLaunch.setText("一键启动酒馆 (无 Git)");
        });
        if (gitFailureReason == null || gitFailureReason.trim().isEmpty()) {
            gitFailureReason = "未知原因";
        }
        logger.w(TAG, "进入 Git 降级模式: " + gitFailureReason);
        logger.w(TAG, "此模式下不可安装扩展、不可使用 git init/clone/pull");
    }




    private void showProgressDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        View v = LayoutInflater.from(this).inflate(R.layout.dialog_progress, null);
        progressBar = v.findViewById(R.id.progress_bar);
        progressMessage = v.findViewById(R.id.progress_message);
        builder.setView(v);
        progressDialog = builder.create();
        progressDialog.show();
    }

    private void updateProgress(int current, int total) {
        final int percent = (int) ((current / (float) total) * 100);
        runOnUiThread(() -> {
            if (progressBar != null) {
                progressBar.setMax(total);
                progressBar.setProgress(current);
            }
            if (progressMessage != null) {
                progressMessage.setText(getString(R.string.extracting_msg, percent));
            }
        });
    }

    private void showErrorDialog(String msg) {
        logger.e(TAG, "错误弹窗: " + msg);
        new AlertDialog.Builder(this)
                .setTitle("提示")
                .setMessage(msg)
                .setPositiveButton("确定", null)
                .show();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        logger.removeListener(null); // 移除监听（实际上这里 null 没效果，onDestroy 时 Activity 已销毁问题不大）
        logger.close();
    }
}
