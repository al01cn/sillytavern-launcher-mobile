package cn.al01.sillytavern_launcher;

import android.content.Context;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class SillyTavern {
    public static final String ZIP_NAME = "sillytavern.zip";
    public static final String TARGET_DIR_NAME = "SillyTavern";

    public interface ProgressListener {
        void onProgress(int current, int total);
    }

    public static File getRootFolder(Context context) {
        return new File(context.getFilesDir(), TARGET_DIR_NAME);
    }

    public static boolean unzipSource(Context context, ProgressListener listener) {
        try {
            File root = getRootFolder(context);
            if (!root.exists()) root.mkdirs();

            int total = 0;
            try (InputStream is = context.getAssets().open(ZIP_NAME); ZipInputStream zis = new ZipInputStream(is)) {
                while (zis.getNextEntry() != null) { total++; zis.closeEntry(); }
            }

            try (InputStream is = context.getAssets().open(ZIP_NAME); ZipInputStream zis = new ZipInputStream(is)) {
                ZipEntry ze;
                byte[] buffer = new byte[1024 * 64];
                int count = 0;
                while ((ze = zis.getNextEntry()) != null) {
                    File file = new File(root, ze.getName());
                    if (ze.isDirectory()) file.mkdirs();
                    else {
                        File parent = file.getParentFile();
                        if (parent != null && !parent.exists()) parent.mkdirs();
                        try (FileOutputStream fos = new FileOutputStream(file)) {
                            int len; while ((len = zis.read(buffer)) > 0) fos.write(buffer, 0, len);
                        }
                    }
                    count++;
                    if (listener != null) listener.onProgress(count, total);
                    zis.closeEntry();
                }
            }
            return true;
        } catch (Exception e) {
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