package cn.al01.sillytavern_launcher;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.TextView;
import java.io.File;
import java.util.Objects;

import android.system.Os;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "NodeJS";
    private AlertDialog progressDialog;
    private ProgressBar progressBar;
    private TextView progressMessage;

    static {
        try {
            System.loadLibrary("node");
            System.loadLibrary("native-lib");
        } catch (UnsatisfiedLinkError e) {
            Log.e(TAG, "Failed to load libraries: " + e.getMessage());
        }
    }

    public native int startNodeWithArguments(String[] arguments);
    public native int chdirNative(String path);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        setupBaseEnvironment();
        checkAndLaunch();
    }

    private void setupBaseEnvironment() {
        try {
            String filesDir = getFilesDir().getAbsolutePath();
            Os.setenv("HOME", filesDir, true);
            Os.setenv("TMPDIR", getCacheDir().getAbsolutePath(), true);
            Os.setenv("LANG", "en_US.UTF-8", true);
            Os.setenv("LC_ALL", "en_US.UTF-8", true);
        } catch (Exception e) {
            Log.e(TAG, "Environment error", e);
        }
    }

    private void checkAndLaunch() {
        File stFolder = SillyTavern.getRootFolder(this);
        File serverJs = new File(stFolder, "server.js");

        if (!stFolder.exists() || !serverJs.exists()) {
            showProgressDialog();
            new Thread(() -> {
                boolean success = SillyTavern.unzipSource(this, this::updateProgress);

                runOnUiThread(() -> {
                    if (progressDialog != null) progressDialog.dismiss();
                    if (success) launchProcess(stFolder);
                    else showErrorDialog("解压失败，请检查 sillytavern.zip");
                });
            }).start();
        } else {
            launchProcess(stFolder);
        }
    }

    private void launchProcess(File stFolder) {
        new Thread(() -> {
            // 1. 配置并验证 Git 环境 (如果验证失败则不启动酒馆)
            boolean gitReady = GitManager.setup(this);

            if (!gitReady) {
                runOnUiThread(() -> showErrorDialog("Git 环境验证失败！酒馆无法启动。请检查 Logcat 错误信息。"));
                return; // 核心逻辑：验证失败直接退出线程，不执行后续启动
            }

            // 2. 只有 Git 准备好了，才应用补丁
            Polyfill.applyPatch(stFolder);

            // 3. 寻找入口并启动
            File entry = SillyTavern.findEntryScript(stFolder, "server.js");
            if (entry != null) {
                Log.i(TAG, "Git 验证通过，准备启动酒馆...");
                chdirNative(Objects.requireNonNull(entry.getParentFile()).getAbsolutePath());
                startNodeWithArguments(SillyTavern.getLaunchArguments(entry.getAbsolutePath()));
            } else {
                runOnUiThread(() -> showErrorDialog("未找到 server.js"));
            }
        }).start();
    }

    private void showProgressDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle(R.string.extracting_title).setCancelable(false);
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
            if (progressBar != null) { progressBar.setMax(total); progressBar.setProgress(current); }
            if (progressMessage != null) progressMessage.setText(getString(R.string.extracting_msg, percent));
        });
    }

    private void showErrorDialog(String msg) {
        new AlertDialog.Builder(this).setTitle("提示").setMessage(msg).setPositiveButton("好的", (d, w) -> finish()).show();
    }
}