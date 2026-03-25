package cn.al01.sillytavern_launcher;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;
import java.io.File;
import java.util.Objects;

import android.system.Os;
import android.system.ErrnoException;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "NodeJS";
    private AlertDialog progressDialog;
    private ProgressBar progressBar;
    private TextView progressMessage;

    // 成员变量
    private Button btnLaunch;

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
        btnLaunch.setEnabled(false); // 初始禁用

        // 2. 检查资源状态
        checkResourcesAndPrepare();

        // 3. 点击启动按钮逻辑
        btnLaunch.setOnClickListener(v -> {
            btnLaunch.setEnabled(false);
            btnLaunch.setText("正在运行...");
            startSillyTavern();
        });
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

    /**
     * 逻辑：先检查解压，再检查 Git
     */
    private void checkResourcesAndPrepare() {
        File stFolder = SillyTavern.getRootFolder(this);
        File serverJs = new File(stFolder, "server.js");

        if (!stFolder.exists() || !serverJs.exists()) {
            // 首次启动，需要解压
            showProgressDialog();
            new Thread(() -> {
                boolean success = SillyTavern.unzipSource(this, this::updateProgress);
                runOnUiThread(() -> {
                    if (progressDialog != null) progressDialog.dismiss();
                    if (success) {
                        // 解压成功后，紧接着准备 Git
                        prepareGitEnvironment();
                    } else {
                        showErrorDialog("解压酒馆源码失败，请确保 assets 中有 sillytavern.7z");
                        btnLaunch.setText("资源缺失");
                    }
                });
            }).start();
        } else {
            // 资源已存在，直接准备 Git
            prepareGitEnvironment();
        }
    }

    /**
     * 在后台静默准备 Git，完成后激活启动按钮
     */
    private void prepareGitEnvironment() {
        runOnUiThread(() -> btnLaunch.setText("正在配置 Git..."));

        new Thread(() -> {
            boolean gitReady = GitManager.setup(this);
            runOnUiThread(() -> {
                if (gitReady) {
                    btnLaunch.setEnabled(true);
                    btnLaunch.setText("启动酒馆");
                    Log.i(TAG, "环境准备就绪");
                } else {
                    btnLaunch.setText("环境配置失败");
                    showErrorDialog("Git 环境初始化失败，请检查 lib 库文件。");
                }
            });
        }).start();
    }

    /**
     * 点击按钮后的最终启动逻辑
     */
    private void startSillyTavern() {
        File stFolder = SillyTavern.getRootFolder(this);
        new Thread(() -> {
            // 1. 应用补丁
            Polyfill.applyPatch(stFolder);

            // 2. 获取入口
            File entry = SillyTavern.findEntryScript(stFolder, "server.js");
            if (entry != null) {
                Log.i(TAG, "启动酒馆中...");
                chdirNative(Objects.requireNonNull(entry.getParentFile()).getAbsolutePath());
                startNodeWithArguments(SillyTavern.getLaunchArguments(entry.getAbsolutePath()));
            } else {
                runOnUiThread(() -> {
                    showErrorDialog("未找到 server.js 入口文件。");
                    btnLaunch.setEnabled(true);
                    btnLaunch.setText("启动酒馆");
                });
            }
        }).start();
    }

    private void showProgressDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("首次启动：正在解压资源").setCancelable(false);
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
                progressMessage.setText("已解压: " + percent + "%");
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