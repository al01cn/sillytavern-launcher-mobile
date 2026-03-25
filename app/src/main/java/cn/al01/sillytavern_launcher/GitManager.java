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
 * GitManager 终极补全版
 * 1. 自动处理所有 Git 及其依赖库 (Curl, SSL, SSH2, HTTP2, IDN2) 的版本别名
 * 2. 修复模板路径和环境路径
 * 3. 增强对 git-remote-https 等子程序的修复
 * 4. 修复 HTTPS 克隆所需的 SSL 证书路径
 */
public class GitManager {
    private static final String TAG = "GitManager";
    private static final String GIT_DIR_NAME = "git";
    private static final String SO_DIR_NAME = "lib";

    public static boolean setup(Context context) {
        try {
            File filesDir = context.getFilesDir();
            File gitRoot = new File(filesDir, GIT_DIR_NAME);
            File soRoot = new File(filesDir, SO_DIR_NAME);

            if (!gitRoot.exists()) gitRoot.mkdirs();
            if (!soRoot.exists()) soRoot.mkdirs();

            // 1. 架构识别
            String abi = Build.SUPPORTED_ABIS[0];
            String arch = abi.equalsIgnoreCase("x86_64") ? "x86_64" :
                    (abi.contains("arm64") ? "arm64-v8a" : "armeabi-v7a");

            Log.i(TAG, "检测架构: " + arch + " -> 准备提取全量依赖...");

            // 2. 提取 Git 核心资源 (二进制文件)
            String gitAssetPath = "lib/git/" + arch;
            if (!syncAssets(context, gitAssetPath, gitRoot)) {
                Log.e(TAG, "❌ 提取 Git 核心失败");
                return false;
            }

            // 3. 提取并处理所有 SO 库及其版本别名
            extractAllSoLibraries(context, arch, soRoot);

            // 4. 定位主程序
            File gitMain = new File(gitRoot, "libexec/git-core/git");
            if (!gitMain.exists()) {
                Log.e(TAG, "❌ 找不到 Git: " + gitMain.getAbsolutePath());
                return false;
            }

            // 5. 修复辅助程序 (git-remote-https 是 HTTPS 克隆的核心)
            File gitCoreDir = new File(gitRoot, "libexec/git-core");
            repairBrokenSymlinks(gitMain, gitCoreDir);

            // 5.5 提取 CA 证书 (解决 HTTPS 克隆时 SSL 验证失败问题)
            File cacertFile = new File(filesDir, "cacert.pem");
            // 根据用户反馈，证书在 assets/ca/ 目录下
            String cacertAssetPath = "ca/cacert.pem";
            try {
                // 每次启动都校验一下，确保证书路径正确注入
                extractSingleFile(context, cacertAssetPath, cacertFile);
                Log.i(TAG, "✅ 证书已提取到: " + cacertFile.getAbsolutePath());
            } catch (Exception e) {
                Log.w(TAG, "⚠️ 提取证书失败，请确认 assets/ca/cacert.pem 是否存在: " + e.getMessage());
            }

            // 6. 环境注入
            injectEnvironment(context, gitRoot, soRoot, gitCoreDir);

            // 7. 最终验证
            return verifyGit(gitMain);
        } catch (Exception e) {
            Log.e(TAG, "Git 初始化失败", e);
            return false;
        }
    }

    private static void extractAllSoLibraries(Context context, String arch, File soDestDir) {
        try {
            String[] libFolders = context.getAssets().list("lib");
            if (libFolders == null) return;

            for (String folderName : libFolders) {
                if (folderName.equals("git")) continue;

                String archSpecificPath = "lib/" + folderName + "/" + arch;
                String[] files = context.getAssets().list(archSpecificPath);

                if (files != null && files.length > 0) {
                    for (String fileName : files) {
                        if (fileName.endsWith(".so") && !isAssetDirectory(context, archSpecificPath + "/" + fileName)) {
                            File targetFile = new File(soDestDir, fileName);
                            extractSingleFile(context, archSpecificPath + "/" + fileName, targetFile);
                            handleLibraryAliases(soDestDir, fileName);
                        }
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "SO 库提取出错", e);
        }
    }

    private static void handleLibraryAliases(File dir, String fileName) {
        try {
            if (fileName.equals("libz.so")) {
                createAlias(new File(dir, fileName), new File(dir, "libz.so.1"));
            } else if (fileName.equals("libpcre2-8.so")) {
                createAlias(new File(dir, fileName), new File(dir, "libpcre2-8.so.0"));
            } else if (fileName.equals("libcurl.so")) {
                createAlias(new File(dir, fileName), new File(dir, "libcurl.so.4"));
            } else if (fileName.equals("libcrypto.so")) {
                createAlias(new File(dir, fileName), new File(dir, "libcrypto.so.3"));
                createAlias(new File(dir, fileName), new File(dir, "libcrypto.so.1.1"));
            } else if (fileName.equals("libssl.so")) {
                createAlias(new File(dir, fileName), new File(dir, "libssl.so.3"));
                createAlias(new File(dir, fileName), new File(dir, "libssl.so.1.1"));
            } else if (fileName.equals("libnghttp2.so")) {
                createAlias(new File(dir, fileName), new File(dir, "libnghttp2.so.14"));
            } else if (fileName.equals("libssh2.so")) {
                createAlias(new File(dir, fileName), new File(dir, "libssh2.so.1"));
            } else if (fileName.equals("libidn2.so")) {
                createAlias(new File(dir, fileName), new File(dir, "libidn2.so.0"));
            } else if (fileName.equals("libiconv.so")) {
                createAlias(new File(dir, fileName), new File(dir, "libiconv.so.2"));
            }
        } catch (Exception e) {
            Log.w(TAG, "创建别名失败: " + fileName, e);
        }
    }

    private static void createAlias(File src, File dst) throws Exception {
        if (dst.exists()) return;
        copyFile(src, dst);
        dst.setExecutable(true, true);
        dst.setReadable(true, true);
    }

    private static boolean isAssetDirectory(Context context, String path) {
        try {
            String[] list = context.getAssets().list(path);
            return list != null && list.length > 0;
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean syncAssets(Context context, String assetPath, File destDir) {
        try {
            String[] items = context.getAssets().list(assetPath);
            if (items == null || items.length == 0) return true;

            for (String item : items) {
                String fullAssetPath = assetPath + "/" + item;
                File targetFile = new File(destDir, item);

                if (isAssetDirectory(context, fullAssetPath)) {
                    if (!targetFile.exists()) targetFile.mkdirs();
                    syncAssets(context, fullAssetPath, targetFile);
                } else {
                    extractSingleFile(context, fullAssetPath, targetFile);
                }
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static void extractSingleFile(Context context, String assetPath, File destFile) throws Exception {
        try (InputStream is = context.getAssets().open(assetPath);
             FileOutputStream fos = new FileOutputStream(destFile)) {
            byte[] buffer = new byte[1024 * 64];
            int len;
            while ((len = is.read(buffer)) > 0) fos.write(buffer, 0, len);
            destFile.setExecutable(true, true);
            destFile.setReadable(true, true);
        }
    }

    private static void repairBrokenSymlinks(File gitMain, File gitCoreDir) {
        if (!gitCoreDir.exists()) return;
        String[] helpers = {"git-remote-http", "git-remote-https", "git-remote-ftp", "git-remote-ftps"};
        for (String hName : helpers) {
            File hFile = new File(gitCoreDir, hName);
            if (!hFile.exists() || hFile.length() < 1024) {
                try {
                    copyFile(gitMain, hFile);
                    hFile.setExecutable(true, true);
                } catch (Exception ignored) {}
            }
        }
    }

    private static void injectEnvironment(Context context, File gitRoot, File soRoot, File gitCoreDir) {
        try {
            String soPath = soRoot.getAbsolutePath();
            String corePath = gitCoreDir.getAbsolutePath();

            Os.setenv("LD_LIBRARY_PATH", soPath + ":" + System.getenv("LD_LIBRARY_PATH"), true);
            Os.setenv("PATH", corePath + ":" + gitRoot.getAbsolutePath() + ":" + System.getenv("PATH"), true);

            File templateDir = new File(gitRoot, "share/git-core/templates");
            if (!templateDir.exists()) templateDir.mkdirs();
            Os.setenv("GIT_TEMPLATE_DIR", templateDir.getAbsolutePath(), true);

            Os.setenv("GIT_EXEC_PATH", corePath, true);
            Os.setenv("HOME", context.getFilesDir().getAbsolutePath(), true);

            // 注入 SSL 证书环境变量
            File cacertFile = new File(context.getFilesDir(), "cacert.pem");
            if (cacertFile.exists()) {
                String certPath = cacertFile.getAbsolutePath();
                Os.setenv("GIT_SSL_CAINFO", certPath, true);
                Os.setenv("CURL_CA_BUNDLE", certPath, true);
                Os.setenv("SSL_CERT_FILE", certPath, true);
            } else {
                Os.setenv("GIT_SSL_CAPATH", "/system/etc/security/cacerts", true);
            }
        } catch (ErrnoException e) {
            Log.e(TAG, "环境注入失败", e);
        }
    }

    private static boolean verifyGit(File gitExe) {
        try {
            ProcessBuilder pb = new ProcessBuilder(gitExe.getAbsolutePath(), "--version");
            pb.environment().put("LD_LIBRARY_PATH", Os.getenv("LD_LIBRARY_PATH"));
            pb.environment().put("GIT_EXEC_PATH", Os.getenv("GIT_EXEC_PATH"));
            pb.environment().put("GIT_TEMPLATE_DIR", Os.getenv("GIT_TEMPLATE_DIR"));
            pb.environment().put("PATH", Os.getenv("PATH"));
            pb.environment().put("HOME", Os.getenv("HOME"));

            pb.redirectErrorStream(true);
            Process p = pb.start();
            InputStream is = p.getInputStream();
            byte[] b = new byte[1024];
            int l = is.read(b);
            if (l > 0) Log.i(TAG, "Git 验证: " + new String(b, 0, l));
            return p.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private static void copyFile(File src, File dst) throws Exception {
        try (FileInputStream in = new FileInputStream(src); FileOutputStream out = new FileOutputStream(dst)) {
            byte[] buf = new byte[1024 * 64];
            int len; while ((len = in.read(buf)) > 0) out.write(buf, 0, len);
        }
    }
}