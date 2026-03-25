package cn.al01.sillytavern_launcher;

import android.content.Context;
import android.os.Build;
import android.util.Log;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import android.system.Os;
import android.system.ErrnoException;

/**
 * GitManager 最终强化版
 * 支持：libpcre2-8.so, libiconv.so, libz.so.1 自动修复
 * 逻辑：优先使用 Assets 中的库，缺失时尝试从系统拷贝
 */
public class GitManager {
    private static final String TAG = "GitManager";
    private static final String BIN_DIR_NAME = "bin";

    public static boolean setup(Context context) {
        try {
            File binDir = new File(context.getFilesDir(), BIN_DIR_NAME);
            if (!binDir.exists()) binDir.mkdirs();

            // 1. 释放 Assets 中的所有组件 (git, libpcre2-8.so, libiconv.so 等)
            // 请确保文件放在 assets/git/bin/[arch]/ 目录下
            if (!installGitComponents(context, binDir)) return false;

            // 2. 补全 Android 系统缺失或名称不匹配的库 (如 libz.so.1)
            repairSystemLibraryLinks(binDir);

            // 3. 修复 Git 助手程序 (解决 git clone https 协议不支持问题)
            repairAllHelpers(binDir);

            // 4. 环境变量注入 (注入 PATH, LD_LIBRARY_PATH, GIT_EXEC_PATH)
            injectEnvironment(context, binDir.getAbsolutePath());

            // 5. 验证执行
            return verifyGit(binDir);

        } catch (Exception e) {
            Log.e(TAG, "Git 初始化过程中发生崩溃", e);
            return false;
        }
    }

    private static boolean installGitComponents(Context context, File binDir) {
        try {
            String abi = Build.SUPPORTED_ABIS[0];
            String arch = abi.contains("arm64") ? "arm64-v8a" : (abi.contains("x86_64") ? "x86_64" : "armeabi-v7a");
            String assetFolder = "git/bin/" + arch;

            String[] files = context.getAssets().list(assetFolder);
            if (files == null || files.length == 0) {
                Log.e(TAG, "Assets 目录为空或未找到: " + assetFolder);
                return false;
            }

            for (String fileName : files) {
                String assetPath = assetFolder + "/" + fileName;

                try {
                    String[] sub = context.getAssets().list(assetPath);
                    if (sub != null && sub.length > 0) continue;
                } catch (Exception ignored) {}

                File destFile = new File(binDir, fileName);

                if (!destFile.exists() || fileName.equals("git") || fileName.endsWith(".so") || fileName.contains(".so.")) {
                    try (InputStream is = context.getAssets().open(assetPath);
                         FileOutputStream fos = new FileOutputStream(destFile)) {
                        byte[] buffer = new byte[1024 * 64];
                        int len;
                        while ((len = is.read(buffer)) > 0) fos.write(buffer, 0, len);
                    }
                }
                destFile.setExecutable(true, false);
                destFile.setReadable(true, false);
            }
            return true;
        } catch (Exception e) {
            Log.e(TAG, "从 Assets 释放资源失败", e);
            return false;
        }
    }

    private static void repairSystemLibraryLinks(File binDir) {
        // 只有当 Assets 里没提供这些库时，才尝试从系统拷贝
        String[] libsToFix = {"libz.so:libz.so.1"};
        String[] systemPaths = {
                "/system/lib64", "/system/lib",
                "/apex/com.android.runtime/lib64", "/apex/com.android.runtime/lib"
        };

        for (String pair : libsToFix) {
            String[] parts = pair.split(":");
            String srcName = parts[0];
            String dstName = parts[1];
            File dstFile = new File(binDir, dstName);

            if (!dstFile.exists()) {
                for (String sPath : systemPaths) {
                    File srcFile = new File(sPath, srcName);
                    if (srcFile.exists()) {
                        try {
                            copyFile(srcFile, dstFile);
                            dstFile.setExecutable(true, false);
                            Log.i(TAG, "已从系统补全库: " + dstName + " (源: " + sPath + ")");
                            break;
                        } catch (Exception e) {
                            Log.w(TAG, "尝试拷贝系统库失败: " + srcName);
                        }
                    }
                }
            }
        }
    }

    private static void repairAllHelpers(File binDir) {
        File gitMain = new File(binDir, "git");
        if (!gitMain.exists()) return;

        String[] helpers = {
                "git-remote-http", "git-remote-https", "git-remote-ftp", "git-remote-ftps",
                "git-receive-pack", "git-upload-pack"
        };

        for (String hName : helpers) {
            File hFile = new File(binDir, hName);
            if (!hFile.exists() || (hFile.length() < 10000 && hFile.length() != gitMain.length())) {
                try {
                    copyFile(gitMain, hFile);
                    hFile.setExecutable(true, false);
                    Log.d(TAG, "助手已修复: " + hName);
                } catch (Exception e) {
                    Log.e(TAG, "修复助手失败: " + hName);
                }
            }
        }
    }

    private static void injectEnvironment(Context context, String binPath) {
        try {
            String oldPath = System.getenv("PATH");
            Os.setenv("PATH", binPath + ":" + (oldPath != null ? oldPath : ""), true);
            Os.setenv("GIT_EXEC_PATH", binPath, true);

            String oldLd = System.getenv("LD_LIBRARY_PATH");
            String newLd = binPath + ":" + (oldLd != null ? oldLd : "/system/lib64:/system/lib");
            Os.setenv("LD_LIBRARY_PATH", newLd, true);

            Os.setenv("HOME", context.getFilesDir().getAbsolutePath(), true);
            Log.i(TAG, "Git 环境变量注入完成");
        } catch (ErrnoException e) {
            Log.e(TAG, "环境变量注入出错", e);
        }
    }

    private static boolean verifyGit(File binDir) {
        try {
            String gitPath = new File(binDir, "git").getAbsolutePath();
            Process p = Runtime.getRuntime().exec(new String[]{gitPath, "--version"});
            int code = p.waitFor();

            if (code == 0) {
                Log.i(TAG, "✅ Git 验证成功！所有依赖 (.so) 已就绪。");
                return true;
            } else {
                InputStream es = p.getErrorStream();
                byte[] b = new byte[Math.max(0, es.available())];
                es.read(b);
                Log.e(TAG, "❌ Git 验证失败，错误详情: " + new String(b));
            }
        } catch (Exception e) {
            Log.e(TAG, "验证 Git 过程中发生异常", e);
        }
        return false;
    }

    private static void copyFile(File src, File dst) throws Exception {
        try (FileInputStream in = new FileInputStream(src);
             FileOutputStream out = new FileOutputStream(dst)) {
            byte[] buf = new byte[1024 * 64];
            int len;
            while ((len = in.read(buf)) > 0) out.write(buf, 0, len);
        }
    }
}