package cn.al01.sillytavern_launcher;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import android.annotation.SuppressLint;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;
import java.io.File;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.Objects;

import android.system.Os;
import android.system.ErrnoException;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "NodeJS";
    private AlertDialog progressDialog;
    private ProgressBar progressBar;
    private TextView progressMessage;

    private Button btnLaunch;
    private WebView webView;
    private View mainLayout; // 用于隐藏初始 UI

    static {
        try {
            System.loadLibrary("node");
            System.loadLibrary("native-lib");
        } catch (UnsatisfiedLinkError e) {
            Log.e(TAG, "无法加载本地库: " + e.getMessage());
        }
    }

    public native int startNodeWithArguments(String[] arguments);
    public native int chdirNative(String path);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // 1. 初始化 UI 和基础环境
        setupBaseEnvironment();

        btnLaunch = findViewById(R.id.btn_launch);
        webView = findViewById(R.id.webview_main);
        mainLayout = findViewById(R.id.main_container); // 假设你的 XML 里有一个根容器

        initWebView();

        btnLaunch.setEnabled(false);

        // 2. 检查资源状态
        checkResourcesAndPrepare();

        // 3. 点击启动按钮逻辑
        btnLaunch.setOnClickListener(v -> {
            btnLaunch.setEnabled(false);
            btnLaunch.setText("正在启动...");
            startSillyTavern();
            // 开始监控端口，准备切换页面
            monitorPortAndSwitch(11451);
        });
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void initWebView() {
        WebSettings settings = webView.getSettings();

        // 1. 核心配置：必须开启
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true); // 酒馆可能用到 WebSQL/IndexedDB

        // 2. 资源访问权限：酒馆某些主题或插件可能需要访问本地资源
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);
        settings.setAllowFileAccessFromFileURLs(true);
        settings.setAllowUniversalAccessFromFileURLs(true);

        // 3. 界面展示优化
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);
        settings.setSupportZoom(true);
        settings.setBuiltInZoomControls(true);
        settings.setDisplayZoomControls(false);

        // 4. 【重要】解决无法加载的关键：允许混合内容
        // 因为本地服务经常被识别为不安全环境
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);

        // 5. 优化白名单拦截逻辑
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
//                String url = request.getUrl().toString();
                // 更加宽松的本地匹配
                // 允许在 WebView 内跳转
                // 如果是外部链接，可以考虑用浏览器打开而不是直接拦截
                return false;
            }

            @Override
            public void onReceivedError(WebView view, int errorCode, String description, String failingUrl) {
                Log.e(TAG, "WebView 错误: " + description + " 地址: " + failingUrl);
            }
        });

        // 开发环境下开启调试（可选，方便你在电脑 Chrome 调试手机 WebView）
        WebView.setWebContentsDebuggingEnabled(true);
    }

    private void setupBaseEnvironment() {
        try {
            String filesDir = getFilesDir().getAbsolutePath();
            Os.setenv("HOME", filesDir, true);
            Os.setenv("NODE_PATH", filesDir + "/node_modules", true);
        } catch (ErrnoException e) {
            Log.e(TAG, "基础环境设置失败", e);
        }
    }

    private void checkResourcesAndPrepare() {
        File stFolder = SillyTavern.getRootFolder(this);
        File serverJs = new File(stFolder, "server.js");

        if (!stFolder.exists() || !serverJs.exists()) {
            showProgressDialog();
            new Thread(() -> {
                boolean success = SillyTavern.unzipSource(this, this::updateProgress);
                runOnUiThread(() -> {
                    if (progressDialog != null) progressDialog.dismiss();
                    if (success) {
                        prepareGitEnvironment();
                    } else {
                        showErrorDialog("解压酒馆源码失败");
                        btnLaunch.setText("资源缺失");
                    }
                });
            }).start();
        } else {
            prepareGitEnvironment();
        }
    }

    private void prepareGitEnvironment() {
        File stFolder = SillyTavern.getRootFolder(this);
        runOnUiThread(() -> btnLaunch.setText("正在配置 Git..."));
        new Thread(() -> {
            boolean gitReady = GitManager.setup(this);
            runOnUiThread(() -> {
                if (gitReady) {
                    ensureGitRepo(stFolder);
                    btnLaunch.setEnabled(false);
                    btnLaunch.setText("正在初始化 Git 元数据");
                } else {
                    btnLaunch.setText("环境配置失败");
                }
            });
        }).start();
    }

    private void startSillyTavern() {
        File stFolder = SillyTavern.getRootFolder(this);
        new Thread(() -> {
            Polyfill.applyPatch(stFolder);
            File entry = SillyTavern.findEntryScript(stFolder, "server.js");
            if (entry != null) {
                chdirNative(Objects.requireNonNull(entry.getParentFile()).getAbsolutePath());
                // 注意：startNodeWithArguments 通常是阻塞的，所以它后面的代码可能不会立即执行
                startNodeWithArguments(SillyTavern.getLaunchArguments(entry.getAbsolutePath()));
            }
        }).start();
    }

    /**
     * 轮询检测本地端口，成功后切换到 WebView
     */
    private void monitorPortAndSwitch(int port) {
        new Thread(() -> {
            boolean connected = false;
            int attempts = 0;
            while (!connected && attempts < 30) { // 最多尝试 30 秒
                try {
                    Thread.sleep(1000);
                    Socket socket = new Socket();
                    socket.connect(new InetSocketAddress("127.0.0.1", port), 500);
                    socket.close();
                    connected = true;
                } catch (Exception e) {
                    attempts++;
                    Log.d(TAG, "等待端口 " + port + " 开放... (" + attempts + ")");
                }
            }

            if (connected) {
                try {
                    Thread.sleep(500);
                    runOnUiThread(() -> {
                        // 隐藏启动 UI，显示 WebView
                        mainLayout.setVisibility(View.GONE);
                        webView.setVisibility(View.VISIBLE);
                        webView.loadUrl("http://127.0.0.1:" + port);
                    });
                } catch (InterruptedException e) {
                    runOnUiThread(() -> showErrorDialog("延迟启用失败，请检查控制台输出。"));
                    throw new RuntimeException(e);
                }
            } else {
                runOnUiThread(() -> showErrorDialog("酒馆启动超时，请检查控制台输出。"));
            }
        }).start();
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
     * 自动初始化 Git 仓库，解决 fatal: not a git repository 报错
     */
    private void ensureGitRepo(File folder) {
        File gitDir = new File(folder, ".git");
        if (!gitDir.exists()) {
            Log.i(TAG, "Initializing git repository in " + folder.getAbsolutePath());
            try {
                ProcessBuilder pb = new ProcessBuilder("git", "init");
                // 继承 GitManager 设置好的路径
                pb.environment().put("PATH", Os.getenv("PATH"));
                pb.environment().put("LD_LIBRARY_PATH", Os.getenv("LD_LIBRARY_PATH"));
                pb.environment().put("HOME", Os.getenv("HOME"));
                pb.environment().put("GIT_EXEC_PATH", Os.getenv("GIT_EXEC_PATH"));

                pb.directory(folder);
                pb.redirectErrorStream(true);
                Process p = pb.start();
                p.waitFor();
                Log.i(TAG, "Git init success");
                new Thread(() -> {
                    boolean gitReady = GitManager.setup(this);
                    runOnUiThread(() -> {
                        if (gitReady) {
                            btnLaunch.setEnabled(true);
                            btnLaunch.setText("一键启动酒馆");
                        } else {
                            btnLaunch.setText("Git元数据，初始化失败");
                        }
                    });
                }).start();
            } catch (Exception e) {
                Log.e(TAG, "Git init failed: " + e.getMessage());
            }
        }
    }

    private void showProgressDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
//        builder.setTitle("正在解压资源").setCancelable(false);
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
        new AlertDialog.Builder(this)
                .setTitle("提示")
                .setMessage(msg)
                .setPositiveButton("确定", null)
                .show();
    }
}