package cn.al01.sillytavern_launcher;

import androidx.appcompat.app.AppCompatActivity;
import android.os.Bundle;
import android.util.Log;
import java.io.File;

import cn.al01.sillytavern_launcher.R;

public class MainActivity extends AppCompatActivity {

    // 1. 加载原生库 (必须按顺序：先 node，后 native-lib)
    static {
        System.loadLibrary("node");
        System.loadLibrary("native-lib");
    }

    // 2. 声明 C++ 中的 native 方法
    // 注意：这个方法名对应 C++ 里的 Java_com_example_sillytavern_MainActivity_startNodeWithArguments
    public native int startNodeWithArguments(String[] arguments);

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

    // 在 MainActivity.java 中修改
    private void startSillyTavern() {
        try {
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