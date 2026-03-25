package cn.al01.sillytavern_launcher;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.TextView;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import android.system.Os;

public class MainActivity extends AppCompatActivity {

    private static final String ZIP_NAME = "sillytavern.zip";
    private static final String TARGET_DIR_NAME = "SillyTavern";

    private AlertDialog progressDialog;
    private ProgressBar progressBar;
    private TextView progressMessage;

    static {
        try {
            System.loadLibrary("node");
            System.loadLibrary("native-lib");
            Log.i("NodeJS", "原生库加载成功");
        } catch (UnsatisfiedLinkError e) {
            Log.e("NodeJS", "加载库失败: " + e.getMessage());
        }
    }

    public native int startNodeWithArguments(String[] arguments);
    public native int chdirNative(String path);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        setupEnvironment();
        checkAndLaunch();
    }

    private void setupEnvironment() {
        try {
            String filesDir = getFilesDir().getAbsolutePath();
            File stFolder = new File(filesDir, TARGET_DIR_NAME);

            Os.setenv("HOME", filesDir, true);
            Os.setenv("TMPDIR", getCacheDir().getAbsolutePath(), true);
            Os.setenv("NODE_PATH", new File(stFolder, "node_modules").getAbsolutePath(), true);
            Os.setenv("LANG", "en_US.UTF-8", true);
            Os.setenv("LC_ALL", "en_US.UTF-8", true);
        } catch (Exception e) {
            Log.e("NodeJS", "环境变量设置失败", e);
        }
    }

    private void checkAndLaunch() {
        File stFolder = new File(getFilesDir(), TARGET_DIR_NAME);
        File serverJs = new File(stFolder, "server.js");

        if (!stFolder.exists() || !serverJs.exists()) {
            showProgressDialog();
            new Thread(() -> {
                // 1. 执行解压
                boolean success = performUnzip();

                // 2. 无论是否解压成功，都先尝试关闭弹窗并继续后续逻辑
                runOnUiThread(() -> {
                    if (progressDialog != null && progressDialog.isShowing()) {
                        progressDialog.dismiss();
                    }

                    if (success) {
                        // 开启新线程处理耗时的补丁和启动工作，避免阻塞 UI
                        new Thread(() -> {
                            patchSourceCode(stFolder);
                            locateAndLaunch();
                        }).start();
                    } else {
                        showErrorDialog("解压失败，请确认 assets 中存在 " + ZIP_NAME);
                    }
                });
            }).start();
        } else {
            // 已存在，直接在后台线程打补丁并启动
            new Thread(() -> {
                patchSourceCode(stFolder);
                locateAndLaunch();
            }).start();
        }
    }

    private void patchSourceCode(File dir) {
        File[] files = dir.listFiles();
        if (files == null) return;

        for (File file : files) {
            if (file.isDirectory()) {
                patchSourceCode(file);
            } else {
                String name = file.getName().toLowerCase();
                // 仅处理代码文件
                if (name.endsWith(".js") || name.endsWith(".mjs") || name.endsWith(".cjs")) {
                    applyCodePatch(file);
                }
            }
        }
    }

    private void applyCodePatch(File file) {
        try {
            StringBuilder content = new StringBuilder();
            boolean changed = false;

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    String oldLine = line;

                    // --- 1. 修复正则 ---
                    if (line.contains("\\p{")) {
                        StringBuilder sb = new StringBuilder();
                        boolean inBracket = false;
                        for (int i = 0; i < line.length(); i++) {
                            char c = line.charAt(i);
                            if (c == '\\' && i + 1 < line.length()) {
                                char nextC = line.charAt(i + 1);
                                if (nextC == 'p' && i + 2 < line.length() && line.charAt(i + 2) == '{') {
                                    int endBrace = line.indexOf('}', i + 3);
                                    if (endBrace != -1) {
                                        String prop = line.substring(i + 3, endBrace);
                                        String replacement = getUnicodePropertyRange(prop);
                                        if (replacement != null) {
                                            if (inBracket) sb.append(replacement);
                                            else sb.append("[").append(replacement).append("]");
                                            i = endBrace; continue;
                                        }
                                    }
                                }
                                sb.append(c).append(nextC); i++; continue;
                            } else if (c == '[') inBracket = true;
                            else if (c == ']') inBracket = false;
                            sb.append(c);
                        }
                        line = sb.toString();
                        line = line.replaceAll("/([gimsy]*)[uv]+([gimsy]*)/", "/$1$2/");
                    }

                    // --- 2. 修复 TextDecoder ---
                    if (line.contains("TextDecoder") && (line.contains("fatal") || line.contains("!0"))) {
                        line = line.replaceAll("\\{\\s*fatal\\s*:\\s*(true|!0)\\s*\\}", "{}");
                        line = line.replaceAll("fatal\\s*:\\s*(true|!0)", "ignore:true");
                    }

                    // --- 3. 拦截自动打开 (兜底) ---
                    if (line.contains("xdg-open")) {
                        line = line.replace("xdg-open", "echo");
                    }

                    if (!line.equals(oldLine)) {
                        changed = true;
                    }
                    content.append(line).append("\n");
                }
            }

            if (changed) {
                try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8))) {
                    writer.write(content.toString());
                }
            }
        } catch (Exception e) {
            Log.e("NodeJS", "补丁失败: " + file.getName());
        }
    }

    private String getUnicodePropertyRange(String prop) {
        switch (prop) {
            case "L": case "Letter": case "Alpha": return "a-zA-Z\\u00C0-\\u00FF\\u0100-\\u017F";
            case "Lu": return "A-Z\\u00C0-\\u00D6\\u00D8-\\u00DE";
            case "Ll": return "a-z\\u00DF-\\u00F6\\u00F8-\\u00FF";
            case "N": case "Number": return "0-9\\u00B2\\u00B3\\u00B9\\u00BC-\\u00BE";
            case "Digit": return "0-9";
            case "Alnum": return "a-zA-Z\\u00C0-\\u00FF\\u0100-\\u017F0-9";
            case "Cc": return "\\x00-\\x1f\\x7f-\\x9f";
            case "Cf": return "\\xad\\u0600-\\u0605\\u061c\\u06dd\\u070f\\u08e2\\u180e\\ufeff\\ufff9-\\ufffb";
            case "Co": return "\\ue000-\\uf8ff";
            case "Cs": return "\\ud800-\\udfff";
            default: return null;
        }
    }

    private void locateAndLaunch() {
        File stFolder = new File(getFilesDir(), TARGET_DIR_NAME);
        File serverJs = findFile(stFolder, "server.js");

        if (serverJs == null || !serverJs.exists()) {
            runOnUiThread(() -> showErrorDialog("未找到 server.js。"));
            return;
        }

        chdirNative(serverJs.getParentFile().getAbsolutePath());
        launchNodeEngine(serverJs.getAbsolutePath());
    }

    private void launchNodeEngine(String scriptPath) {
        Log.i("NodeJS", "正在启动 SillyTavern...");
        String[] nodeArgs = {"node", scriptPath, "--no-open"};
        startNodeWithArguments(nodeArgs);
    }

    private File findFile(File dir, String name) {
        File[] files = dir.listFiles();
        if (files == null) return null;
        for (File f : files) {
            if (f.getName().equalsIgnoreCase(name)) return f;
            if (f.isDirectory()) {
                File found = findFile(f, name);
                if (found != null) return found;
            }
        }
        return null;
    }

    private boolean performUnzip() {
        try {
            File stRoot = new File(getFilesDir(), TARGET_DIR_NAME);
            if (!stRoot.exists()) stRoot.mkdirs();

            int totalFiles = 0;
            try (InputStream is = getAssets().open(ZIP_NAME); ZipInputStream zis = new ZipInputStream(is)) {
                while (zis.getNextEntry() != null) { totalFiles++; zis.closeEntry(); }
            }

            try (InputStream is = getAssets().open(ZIP_NAME); ZipInputStream zis = new ZipInputStream(is)) {
                ZipEntry ze; byte[] buffer = new byte[1024 * 64]; int currentFile = 0;
                while ((ze = zis.getNextEntry()) != null) {
                    File file = new File(stRoot, ze.getName());
                    if (ze.isDirectory()) file.mkdirs();
                    else {
                        File parent = file.getParentFile();
                        if (parent != null && !parent.exists()) parent.mkdirs();
                        try (FileOutputStream fos = new FileOutputStream(file)) {
                            int len; while ((len = zis.read(buffer)) > 0) fos.write(buffer, 0, len);
                        }
                    }
                    currentFile++;
                    updateProgress(currentFile, totalFiles);
                    zis.closeEntry();
                }
            }
            return true;
        } catch (Exception e) {
            Log.e("NodeJS", "解压异常", e);
            return false;
        }
    }

    private void showProgressDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle(R.string.extracting_title);
        builder.setCancelable(false);
        View customView = LayoutInflater.from(this).inflate(R.layout.dialog_progress, null);
        progressBar = customView.findViewById(R.id.progress_bar);
        progressMessage = customView.findViewById(R.id.progress_message);
        builder.setView(customView);
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