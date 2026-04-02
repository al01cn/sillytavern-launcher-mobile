package cn.al01.sillytavern_launcher;

import android.util.Log;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class Polyfill {
    private static final String TAG = "Polyfill";

    // 基础垫片：注入 Android 缺失的 Intl 对象
    private static final String INTL_POLYFILL =
            "/* Android Intl Polyfill */ " +
                    "if(typeof Intl === 'undefined') { " +
                    "  global.Intl = { " +
                    "    Collator: function(){ return { compare: (a,b) => String(a).localeCompare(String(b)) }; }, " +
                    "    DateTimeFormat: function(){ return { format: (d) => String(d) }; }, " +
                    "    NumberFormat: function(){ return { format: (n) => String(n) }; }, " +
                    "    ListFormat: function(){ return { format: (arr) => arr.join(', ') }; } " +
                    "  }; " +
                    "}\n";

    public static void applyPatch(File dir) {
        File[] files = dir.listFiles();
        if (files == null) return;

        for (File file : files) {
            if (file.isDirectory()) {
                applyPatch(file);
            } else {
                String name = file.getName().toLowerCase();
                if (name.endsWith(".js") || name.endsWith(".mjs") || name.endsWith(".cjs")) {
                    processFile(file);
                }
            }
        }
    }

    private static void processFile(File file) {
        try {
            String filePath = file.getAbsolutePath();
            String fileName = file.getName();
            StringBuilder content = new StringBuilder();
            boolean changed = false;

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
                String line;
                boolean firstLine = true;
                String normalizedPath = filePath.replace('\\', '/');
                boolean isEntryFile = fileName.equals("server.js") && normalizedPath.endsWith("/SillyTavern/server.js");

                while ((line = reader.readLine()) != null) {
                    String oldLine = line;

                    // 1. 入口文件注入
                    if (firstLine && isEntryFile) {

                        String bootstrap = INTL_POLYFILL
                                + "import { createRequire as __polyfillCreateRequire } from 'module';\n"
                                + "const __polyfillRequire = __polyfillCreateRequire(import.meta.url);\n"
                                + "const __polyfillPath = __polyfillRequire('path');\n"
                                + "const __polyfillFs = __polyfillRequire('fs');\n"
                                + "const __polyfillChildProcess = __polyfillRequire('child_process');\n"
                                + "const __polyfillRealSpawn = __polyfillChildProcess.spawn;\n"
                                + "const __polyfillRealExecFile = __polyfillChildProcess.execFile;\n"
                                + "const preferredGit = process.env.GIT_BINARY || process.env.GIT_PREFERRED_BIN || (process.env.ANDROID_NATIVE_PATH && __polyfillPath.join(process.env.ANDROID_NATIVE_PATH, 'git'));\n"
                                + "if (preferredGit && __polyfillFs.existsSync(preferredGit)) {\n"
                                + "  const preferredDir = __polyfillPath.dirname(preferredGit);\n"
                                + "  process.env.GIT_BINARY = preferredGit;\n"
                                + "  process.env.PATH = preferredDir + ':' + process.env.PATH;\n"
                                + "  process.env.ANDROID_NATIVE_PATH = preferredDir;\n"
                                + "  console.log('[Polyfill] Using preferred git:', preferredGit);\n"
                                + "  __polyfillChildProcess.spawn = function(cmd, args, options) {\n"
                                + "    if (cmd === 'git' || cmd === 'git.exe') cmd = preferredGit;\n"
                                + "    return __polyfillRealSpawn.call(this, cmd, args, options);\n"
                                + "  };\n"
                                + "  __polyfillChildProcess.execFile = function(cmd, args, options, callback) {\n"
                                + "    if (cmd === 'git' || cmd === 'git.exe') cmd = preferredGit;\n"
                                + "    return __polyfillRealExecFile.call(this, cmd, args, options, callback);\n"
                                + "  };\n"
                                + "} else {\n"
                                + "  console.log('[Polyfill] No preferred git found, PATH=', process.env.PATH);\n"
                                + "}\n";



                        if (line.startsWith("#!")) {
                            content.append(line).append("\n");
                            content.append(bootstrap);
                        } else {
                            content.append(bootstrap).append(line).append("\n");
                        }
                        changed = true;
                        firstLine = false;
                        continue;
                    }
                    firstLine = false;


                    // 2. 修复 node-fetch headers.js (最稳妥的改法)
                    if (filePath.contains("node-fetch") && fileName.equals("headers.js")) {
                        if (line.contains("value = String(value)")) {
                            line = line + "; if(name && name.toLowerCase() === 'authorization') value = value.replace(/[^\\x20-\\x7E]/g, '');";
                        }
                    }

                    // 3. 修复通用的 .set('Authorization', ...)
                    // 使用正则捕获组来确保括号闭合正确
                    if (line.contains("Authorization")) {
                        // 匹配 .set('Authorization', value) 或 .set("Authorization", value)
                        Pattern p = Pattern.compile("\\.set\\s*\\(\\s*['\"]Authorization['\"]\\s*,\\s*([^\\)]+)\\)");
                        Matcher m = p.matcher(line);
                        if (m.find()) {
                            String valueExpr = m.group(1);
                            // 只有当值没有已经被清洗过时才处理
                            if (!valueExpr.contains(".replace(")) {
                                line = line.replace(m.group(0), ".set('Authorization', String(" + valueExpr + ").replace(/[^\\x20-\\x7E]/g, ''))");
                            }
                        }
                    }

                    // 4. 修复正则 Unicode
                    if (line.contains("\\p{")) {
                        line = fixUnicodeRegExp(line);
                    }

                    // 5. 修复 TextDecoder
                    if (line.contains("TextDecoder") && (line.contains("fatal") || line.contains("!0"))) {
                        line = line.replaceAll("\\{\\s*fatal\\s*:\\s*(true|!0)\\s*\\}", "{}");
                        line = line.replaceAll("fatal\\s*:\\s*(true|!0)", "ignore:true");
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
            Log.e(TAG, "Patch error: " + file.getName(), e);
        }
    }

    private static String fixUnicodeRegExp(String line) {
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
                        String replacement = getUnicodeRange(prop);
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
        return sb.toString().replaceAll("/([gimsy]*)[uv]+([gimsy]*)/", "/$1$2/");
    }

    private static String getUnicodeRange(String prop) {
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
}