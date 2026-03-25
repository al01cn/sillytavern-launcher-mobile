package cn.al01.sillytavern_launcher;

import androidx.appcompat.app.AppCompatActivity;
import android.os.Bundle;
import android.util.Log;
import java.io.File;

import cn.al01.sillytavern_launcher.R;

public class MainActivity extends AppCompatActivity {

    // 1. 加载原生库 (必须按顺序：先 node，后 native-lib)
    static {
        try {
            // 尝试加载 node
            System.loadLibrary("native-lib");
            System.loadLibrary("node");
            Log.i("NodeJS", "所有原生库加载成功");
        } catch (UnsatisfiedLinkError e) {
            // 这里会打印出到底是哪个库找不到了
            Log.e("NodeJS", "原生库加载失败: " + e.getMessage());
        }
    }

    // 2. 声明 C++ 中的 native 方法
    // 注意：这个方法名对应 C++ 里的 Java_com_example_sillytavern_MainActivity_startNodeWithArguments

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // 3. 启动 Node.js 线程
        // 注意：Node.js 是阻塞运行的，绝对不能在主线程（UI线程）调用，否则程序会卡死
        new Thread(new Runnable() {
            @Override
            public void run() {
                startSillyTavern();
            }
        }).start();
    }
    public native int startNodeWithArguments(String[] arguments);

    // 在 MainActivity.java 中修改
    private void startSillyTavern() {
        try {
            // 设置必要的环境变量，防止 Node 内部崩溃
            String filesDir = getFilesDir().getAbsolutePath();
            android.system.Os.setenv("HOME", filesDir, true);
            android.system.Os.setenv("TMPDIR", getCacheDir().getAbsolutePath(), true);

            Log.i("NodeJS", "Current ABI: " + android.os.Build.SUPPORTED_ABIS[0]);
            Log.i("NodeJS", "正在尝试加载参数并启动...");

            String[] nodeArgs = {"node", "-v"};

            // 调用 native 方法

            int exitCode = startNodeWithArguments(nodeArgs);
            Log.i("NodeJS", "Node.js 运行结束，状态码: " + exitCode);
        } catch (UnsatisfiedLinkError e) {
            Log.e("NodeJS", "找不到原生方法，请检查 .so 命名和包名: " + e.getMessage());
        } catch (Exception e) {
            Log.e("NodeJS", "启动发生异常: " + e.getMessage());
        }
    }

}