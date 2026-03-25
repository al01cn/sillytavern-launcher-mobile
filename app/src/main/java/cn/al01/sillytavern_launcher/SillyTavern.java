package cn.al01.sillytavern_launcher;

import android.content.Context;
import android.util.Log;
import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry;
import org.apache.commons.compress.archivers.sevenz.SevenZFile;
import org.apache.commons.compress.utils.SeekableInMemoryByteChannel;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

public class SillyTavern {
    public static final String TARGET_DIR_NAME = "SillyTavern";
    private static final String TAG = "SillyTavern";
    private static final String SOURCE_ASSET = "sillytavern.7z"; // 修改为你的文件名

    public interface ProgressListener {
        void onProgress(int current, int total);
    }

    public static File getRootFolder(Context context) {
        return new File(context.getFilesDir(), TARGET_DIR_NAME);
    }

    /**
     * 将 Assets 中的 7z 文件转换为内存字节通道，以便 SevenZFile 读取
     * 注意：如果 7z 文件非常大（如超过 200MB），建议先存为临时文件再读取
     */
    private static byte[] getAssetBytes(Context context) throws Exception {
        try (InputStream is = context.getAssets().open(SillyTavern.SOURCE_ASSET)) {
            int size = is.available();
            byte[] buffer = new byte[size];
            is.read(buffer);
            return buffer;
        }
    }

    public static boolean unzipSource(Context context, ProgressListener listener) {
        File root = getRootFolder(context);
        if (!root.exists()) root.mkdirs();

        try {
            // 1. 读取 7z 文件到内存通道
            // 如果文件巨大导致 OOM，请改用 File 缓存
            byte[] data = getAssetBytes(context);
            SeekableInMemoryByteChannel channel = new SeekableInMemoryByteChannel(data);

            try (SevenZFile sevenZFile = new SevenZFile(channel)) {
                // 2. 统计总数
                int total = 0;
                Iterable<SevenZArchiveEntry> entries = sevenZFile.getEntries();
                for (SevenZArchiveEntry entry : entries) {
                    if (!entry.isDirectory()) total++;
                }

                // 3. 重新读取进行解压
                int count = 0;
                SevenZArchiveEntry entry;
                while ((entry = sevenZFile.getNextEntry()) != null) {
                    File file = new File(root, entry.getName());

                    if (entry.isDirectory()) {
                        file.mkdirs();
                    } else {
                        File parent = file.getParentFile();
                        if (parent != null && !parent.exists()) parent.mkdirs();

                        try (FileOutputStream fos = new FileOutputStream(file)) {
                            byte[] content = new byte[8192];
                            int len;
                            while ((len = sevenZFile.read(content)) > 0) {
                                fos.write(content, 0, len);
                            }
                        }
                        count++;
                        if (listener != null) listener.onProgress(count, total);
                    }
                }
            }
            return true;
        } catch (Exception e) {
            Log.e(TAG, "7z 解压失败: " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }

    public static File findEntryScript(File dir, String name) {
        File[] files = dir.listFiles();
        if (files == null) return null;
        for (File f : files) {
            if (f.getName().equalsIgnoreCase(name)) return f;
            if (f.isDirectory()) {
                File found = findEntryScript(f, name);
                if (found != null) return found;
            }
        }
        return null;
    }

    public static String[] getLaunchArguments(String scriptPath) {
        return new String[]{"node", scriptPath, "--no-open"};
    }
}