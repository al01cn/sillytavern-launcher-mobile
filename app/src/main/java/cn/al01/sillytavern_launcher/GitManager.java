package cn.al01.sillytavern_launcher;

import android.content.Context;
import android.content.pm.ApplicationInfo;
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
 * 5. 支持 jniLibs 方案 (Android 10+ 的 exec 限制)
 */
public class GitManager {
    private static final String TAG = "GitManager";
    private static final String GIT_DIR_NAME = "git";
    private static final String SO_DIR_NAME = "lib";
    private static volatile String lastErrorSummary = "";
    private static volatile File lastGitExecutable = null;

    public static File getLastGitExecutable() {
        return lastGitExecutable;
    }



    
    // jniLibs 方案：二进制文件名伪装为 .so (Android 10+ 可执行)
    private static final String JNI_GIT_MAIN = "libgit.so";
    private static final String JNI_GIT_MAIN_ALT = "libgit2.so";
    private static final String JNI_GIT_CORE = "libgit-core.so";


    private static void setLastError(String summary) {
        if (summary == null) {
            lastErrorSummary = "";
            return;
        }
        lastErrorSummary = summary.trim();
    }

    public static String getLastErrorSummary() {
        return lastErrorSummary == null ? "" : lastErrorSummary;
    }

    public static boolean setup(Context context) {

        AppLogger lg = AppLogger.getInstance();
        lastErrorSummary = "";
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

            lg.i(TAG, "========== Git 环境初始化开始 ==========");
            lg.i(TAG, "设备 ABI: " + abi);
            lg.i(TAG, "选定架构: " + arch);
            lg.i(TAG, "目标目录: " + gitRoot.getAbsolutePath());
            lg.i(TAG, "库目录: " + soRoot.getAbsolutePath());
            lg.i(TAG, "API 级别: " + Build.VERSION.SDK_INT);

            // 2. 确定 git 主程序路径
            File gitMain = resolveGitMain(context, arch, gitRoot, lg);
            if (gitMain == null || !gitMain.exists()) {
                setLastError("无法定位 Git 主程序（未找到 jniLibs/libgit.so，且 assets 提取失败）");
                lg.e(TAG, "无法定位 Git 主程序");
                return false;
            }

            lastGitExecutable = gitMain;
            lg.i(TAG, "Git 主程序: " + gitMain.getAbsolutePath());


            // 3. 提取 Git 辅助程序 (从 assets)
            String gitAssetPath = firstExistingAssetDir(context, new String[]{
                    "git/" + arch,
                    "lib/git/" + arch
            });
            if (gitAssetPath != null && syncAssets(context, gitAssetPath, gitRoot)) {
                lg.i(TAG, "Git 辅助程序提取完成，来源: " + gitAssetPath);
            } else {
                lg.w(TAG, "辅助程序提取失败，继续使用主程序复制");
            }


            // 4. 提取并处理所有 SO 库及其版本别名
            extractAllSoLibraries(context, arch, soRoot, lg);

            // 5. 修复辅助程序 (git-remote-https 是 HTTPS 克隆的核心)


            File gitCoreDir = new File(gitRoot, "libexec/git-core");
            repairBrokenSymlinks(gitMain, gitCoreDir);

            // 5.5 提取 CA 证书
            File cacertFile = new File(filesDir, "cacert.pem");
            try {
                extractSingleFile(context, "ca/cacert.pem", cacertFile);
                lg.i(TAG, "证书已提取到: " + cacertFile.getAbsolutePath());
            } catch (Exception e) {
                lg.w(TAG, "证书提取失败: " + e.getMessage());
            }

            // 6. 环境注入
            injectEnvironment(context, gitRoot, soRoot, gitCoreDir);

            // 7. 最终验证
            boolean result = verifyGit(gitMain, lg);


            lg.i(TAG, result ? "Git 环境配置成功" : "Git 环境配置失败");
            return result;
        } catch (Exception e) {
            setLastError("Git 初始化异常: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            lg.e(TAG, "Git 初始化失败", e);
            return false;
        }

    }
    
    /**
     * 解析 Git 主程序路径
     * 优先级: jniLibs > filesDir > assets
     */
    private static File resolveGitMain(Context context, String arch, File gitRoot, AppLogger lg) {
        // 方案 1: jniLibs 路径 (Android 10+ 推荐)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            File jniPath = new File(context.getApplicationInfo().nativeLibraryDir, JNI_GIT_MAIN);
            File jniAltPath = new File(context.getApplicationInfo().nativeLibraryDir, JNI_GIT_MAIN_ALT);

            lg.i(TAG, "检查 jniLibs: " + jniPath.getAbsolutePath());
            lg.i(TAG, "  - 存在: " + jniPath.exists() + ", 大小: " + (jniPath.exists() ? jniPath.length() : 0) + " bytes");
            lg.i(TAG, "检查 jniLibs(备用): " + jniAltPath.getAbsolutePath());
            lg.i(TAG, "  - 存在: " + jniAltPath.exists() + ", 大小: " + (jniAltPath.exists() ? jniAltPath.length() : 0) + " bytes");

            if (jniPath.exists()) {
                lg.i(TAG, "使用 jniLibs 方案 (Android 10+ 可执行): " + JNI_GIT_MAIN);
                return jniPath;
            }

            // Android 10+ 无法执行 filesDir，可返回失败并提示打包 libgit.so

            if (jniAltPath.exists()) {
                setLastError("检测到 libgit2.so，但它是共享库不是 Git 可执行主程序；请为当前 ABI 打包 libgit.so（可执行 Git）");
                lg.e(TAG, "检测到 " + JNI_GIT_MAIN_ALT + "，但该文件不可作为 git --version 的可执行入口");
                return null;
            }

            // Android 10+ 上，filesDir 属于 app_data_file，容易被 SELinux execute_no_trans 拒绝
            setLastError("Android 10+ 设备未找到 jniLibs Git 主程序（libgit.so）。请为当前 ABI 打包可执行 Git 库");
            lg.e(TAG, "Android 10+ 未找到 jniLibs Git 主程序: " + jniPath.getAbsolutePath());
            lg.i(TAG, "提示：请将可执行 git 二进制以 libgit.so 形式放入 jniLibs/git/<abi>/ 或 nativeLibraryDir 对应 ABI 目录");
            return null;


        }


        // 方案 2: filesDir/git/libexec/git-core/git (仅 Android 9 及以下)

        File filesDirGit = new File(gitRoot, "libexec/git-core/git");
        lg.i(TAG, "检查 filesDir: " + filesDirGit.getAbsolutePath());
        lg.i(TAG, "  - 存在: " + filesDirGit.exists());
        if (filesDirGit.exists()) {
            lg.i(TAG, "使用 filesDir 方案");
            return filesDirGit;
        }
        
        // 方案 3: 直接从 assets 提取到 filesDir
        lg.i(TAG, "从 assets 提取 Git 主程序...");
        try {
            String gitBase = firstExistingAssetDir(context, new String[]{
                    "git/" + arch,
                    "lib/git/" + arch
            });
            if (gitBase == null) {
                lg.e(TAG, "assets 中不存在 Git 目录: git/" + arch + " 或 lib/git/" + arch);
                return null;
            }
            extractSingleFile(context, gitBase + "/libexec/git-core/git", filesDirGit);
            filesDirGit.setExecutable(true, true);
            chmodRecursive(filesDirGit);
            if (filesDirGit.exists()) {
                lg.i(TAG, "从 assets 提取成功: " + filesDirGit.getAbsolutePath());
                return filesDirGit;
            }
        } catch (Exception e) {
            lg.e(TAG, "从 assets 提取 Git 主程序失败", e);
        }

        
        return null;
    }

    private static void extractAllSoLibraries(Context context, String arch, File soDestDir, AppLogger lg) {
        int soCount = 0;
        try {
            lg.i(TAG, "开始提取 SO 库...");
            String[] libFolders = context.getAssets().list("lib");

            if (libFolders != null && libFolders.length > 0) {
                for (String folderName : libFolders) {
                    if (folderName.equals("git")) continue;

                    String archSpecificPath = "lib/" + folderName + "/" + arch;
                    String[] files = context.getAssets().list(archSpecificPath);

                    if (files != null && files.length > 0) {
                        for (String fileName : files) {
                            if (fileName.endsWith(".so") && !isAssetDirectory(context, archSpecificPath + "/" + fileName)) {
                                File targetFile = new File(soDestDir, fileName);
                                extractSingleFile(context, archSpecificPath + "/" + fileName, targetFile);
                                handleLibraryAliases(soDestDir, fileName, lg);
                                soCount++;
                            }
                        }
                    }
                }
            }

            // assets 中没有依赖库时，回退到 APK 解包后的 nativeLibraryDir
            if (soCount == 0) {
                ApplicationInfo appInfo = context.getApplicationInfo();
                File nativeLibDir = new File(appInfo.nativeLibraryDir);
                lg.w(TAG, "assets 未提取到 SO，回退到 nativeLibraryDir: " + nativeLibDir.getAbsolutePath());

                File[] libs = nativeLibDir.listFiles((dir, name) -> name.endsWith(".so"));
                if (libs != null) {
                    for (File lib : libs) {
                        String name = lib.getName();
                        // git 可执行伪装库不应进入运行时依赖目录
                        if (name.equals(JNI_GIT_MAIN) || name.equals(JNI_GIT_CORE)) continue;

                        File dst = new File(soDestDir, name);
                        copyFile(lib, dst);
                        dst.setReadable(true, true);
                        handleLibraryAliases(soDestDir, name, lg);
                        soCount++;
                    }
                }
            }

            lg.i(TAG, "SO 库提取完成: " + soCount + " 个");
        } catch (Exception e) {
            lg.e(TAG, "SO 库提取出错", e);
        }
    }


    private static void handleLibraryAliases(File dir, String fileName, AppLogger lg) {
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
            lg.w(TAG, "创建别名失败: " + fileName, e);
        }
    }

    private static void createAlias(File src, File dst) throws Exception {
        if (dst.exists()) return;
        copyFile(src, dst);
        dst.setExecutable(true, true);
        dst.setReadable(true, true);
    }

    private static String firstExistingAssetDir(Context context, String[] candidates) {
        for (String candidate : candidates) {
            try {
                String[] items = context.getAssets().list(candidate);
                if (items != null && items.length > 0) {
                    return candidate;
                }
            } catch (Exception ignored) {
            }
        }
        return null;
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
        // 确保父目录链存在
        File parent = destFile.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }
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
            String nativeLibDir = context.getApplicationInfo().nativeLibraryDir;
            String nativeGitPath = nativeLibDir + "/git";

            String oldLd = Os.getenv("LD_LIBRARY_PATH");
            String oldPath = Os.getenv("PATH");
            String mergedLd = appendPath(soPath, oldLd);

            String preferredPathHead = nativeLibDir + ":" + corePath + ":" + gitRoot.getAbsolutePath();

            String mergedPath = appendPath(preferredPathHead, oldPath);

            Log.i(TAG, "Inject PATH=" + mergedPath);

            Os.setenv("LD_LIBRARY_PATH", mergedLd, true);
            Os.setenv("PATH", mergedPath, true);
            Os.setenv("ANDROID_NATIVE_PATH", nativeLibDir, true);
            Os.setenv("GIT_PREFERRED_BIN", nativeGitPath, true);
            Os.setenv("GIT_BINARY", nativeGitPath, true);




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

    private static String appendPath(String head, String tail) {
        if (tail == null || tail.trim().isEmpty() || "null".equalsIgnoreCase(tail.trim())) {
            return head;
        }
        return head + ":" + tail;
    }

    private static boolean verifyGit(File gitExe, AppLogger lg) {

        try {
            lg.i(TAG, "========== Git 验证开始 ==========");
            lg.i(TAG, "路径: " + gitExe.getAbsolutePath());
            lg.i(TAG, "存在: " + gitExe.exists() + ", 大小: " + (gitExe.exists() ? gitExe.length() : 0) + " bytes");
            lg.i(TAG, "可执行: " + gitExe.canExecute());
            
            // 列出环境变量
            lg.i(TAG, "LD_LIBRARY_PATH: " + Os.getenv("LD_LIBRARY_PATH"));
            lg.i(TAG, "GIT_EXEC_PATH: " + Os.getenv("GIT_EXEC_PATH"));
            lg.i(TAG, "GIT_SSL_CAINFO: " + Os.getenv("GIT_SSL_CAINFO"));
            
            // 强制 chmod (针对 Android 10+)
            chmodRecursive(gitExe.getParentFile());
            
            ProcessBuilder pb = new ProcessBuilder(gitExe.getAbsolutePath(), "--version");
            String ldPath = Os.getenv("LD_LIBRARY_PATH");
            pb.environment().put("LD_LIBRARY_PATH", ldPath != null ? ldPath : "");
            pb.environment().put("GIT_EXEC_PATH", Os.getenv("GIT_EXEC_PATH"));
            pb.environment().put("GIT_TEMPLATE_DIR", Os.getenv("GIT_TEMPLATE_DIR"));
            pb.environment().put("PATH", Os.getenv("PATH"));
            pb.environment().put("HOME", Os.getenv("HOME"));
            pb.environment().put("GIT_SSL_CAINFO", Os.getenv("GIT_SSL_CAINFO"));
            
            // 不合并错误流，分别捕获
            Process p = pb.start();
            
            // 读取标准输出
            StringBuilder stdout = new StringBuilder();
            StringBuilder stderr = new StringBuilder();
            try (java.io.BufferedReader outReader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(p.getInputStream()));
                 java.io.BufferedReader errReader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(p.getErrorStream()))) {
                String line;
                while ((line = outReader.readLine()) != null) {
                    stdout.append(line).append("\n");
                }
                while ((line = errReader.readLine()) != null) {
                    stderr.append(line).append("\n");
                }
            }
            
            int exitCode = p.waitFor();
            String outStr = stdout.toString().trim();
            String errStr = stderr.toString().trim();
            
            lg.i(TAG, "退出码: " + exitCode);
            lg.i(TAG, "标准输出: " + (outStr.isEmpty() ? "(空)" : outStr));
            if (!errStr.isEmpty()) {
                lg.w(TAG, "标准错误: " + errStr);
            }
            
            // 诊断常见错误
            if (exitCode == 0) {
                setLastError("");
                lg.i(TAG, "Git 验证通过!");
                return true;
            } else if (exitCode == 127) {
                setLastError("Git 验证失败：缺少依赖库（exit=127），请检查 files/lib 下是否包含 libcrypto/libssl/libcurl 等");
                lg.e(TAG, "错误码 127 = 找不到依赖库 (libcrypto/libssl/libcurl 等缺失)");
                lg.e(TAG, "LD_LIBRARY_PATH: " + ldPath);
                // 列出已提取的库
                File soDir = new File(Os.getenv("HOME"), SO_DIR_NAME);
                if (soDir.exists()) {
                    String[] libs = soDir.list();
                    lg.i(TAG, "已提取的库: " + (libs != null ? String.join(", ", libs) : "无"));
                }
            } else if (exitCode == 139) {
                setLastError("Git 验证失败：发生段错误（exit=139），疑似 ABI 不兼容或二进制损坏");
                lg.e(TAG, "错误码 139 = Segmentation Fault (架构不兼容/二进制损坏)");
                lg.e(TAG, "请确认 git 是为 Android NDK 交叉编译的");
            } else if (exitCode == 132) {
                setLastError("Git 验证失败：非法指令（exit=132），当前文件更像共享库而非可执行 Git 主程序（请改为打包 libgit.so）");
                lg.e(TAG, "错误码 132 = Illegal instruction，通常是把 .so 当可执行文件运行导致");
            } else if (exitCode == -1 || exitCode == 255) {

                setLastError("Git 验证失败：进程被终止（exit=" + exitCode + "），可能是执行权限或 SELinux 限制");
                lg.e(TAG, "错误码 " + exitCode + " = 进程被终止 (可能是 exec 权限问题)");
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    lg.e(TAG, "Android 10+ 不允许 exec() 可写目录下的二进制文件");
                    lg.e(TAG, "解决方案: 已使用 jniLibs 方案");
                }
            } else {
                String detail = !errStr.isEmpty() ? errStr : (outStr.isEmpty() ? "无详细输出" : outStr);
                setLastError("Git 验证失败：exit=" + exitCode + "，详情=" + detail);
            }

            return false;
        } catch (Exception e) {
            String msg = "Git 验证异常: " + e.getClass().getSimpleName() + ": " + e.getMessage();
            setLastError(msg);
            lg.e(TAG, "验证异常: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            return false;
        }

    }
    
    /**
     * 递归设置可执行权限 (针对 Android 10+ 的 exec 限制)
     */
    private static void chmodRecursive(File file) {
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) chmodRecursive(child);
            }
        } else {
            file.setExecutable(true, false);
            file.setReadable(true, false);
            // 尝试使用系统 chmod 命令
            try {
                Runtime.getRuntime().exec(new String[]{"chmod", "755", file.getAbsolutePath()}).waitFor();
            } catch (Exception ignored) {}
        }
    }

    private static void copyFile(File src, File dst) throws Exception {
        try (FileInputStream in = new FileInputStream(src); FileOutputStream out = new FileOutputStream(dst)) {
            byte[] buf = new byte[1024 * 64];
            int len; while ((len = in.read(buf)) > 0) out.write(buf, 0, len);
        }
    }
}