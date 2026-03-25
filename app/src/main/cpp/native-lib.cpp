#include <jni.h>
#include <string>
#include <vector>
#include <cstdlib>
#include <cstring>
#include <unistd.h>
#include <pthread.h>
#include <android/log.h>
#include "node.h"

#define LOG_TAG "NodeJS-Output"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

// 用于重定向 stdout/stderr 的线程函数
void *redirect_logger(void *p) {
    int pipe_fd = *(int *) p;
    char buffer[1024];
    ssize_t r;
    while ((r = read(pipe_fd, buffer, sizeof(buffer) - 1)) > 0) {
        buffer[r] = '\0';
        // 将 Node 的输出打印到 Logcat
        LOGI("%s", buffer);
    }
    return nullptr;
}

void start_redirecting_stdout() {
    int pipe_fds[2];
    pipe(pipe_fds);
    dup2(pipe_fds[1], STDOUT_FILENO);
    dup2(pipe_fds[1], STDERR_FILENO);

    pthread_t thread;
    int *p_fd = (int *) malloc(sizeof(int));
    *p_fd = pipe_fds[0];
    pthread_create(&thread, nullptr, redirect_logger, p_fd);
    pthread_detach(thread);
}

extern "C" {
    JNIEXPORT jint JNICALL
    Java_cn_al01_sillytavern_1launcher_MainActivity_chdirNative(JNIEnv* env, jobject /* this */, jstring path) {
    const char* nativePath = env->GetStringUTFChars(path, nullptr);
    int result = chdir(nativePath);
    env->ReleaseStringUTFChars(path, nativePath);
    return result;
    }

    JNIEXPORT jint JNICALL
    Java_cn_al01_sillytavern_1launcher_MainActivity_startNodeWithArguments(
            JNIEnv *env,
            jobject /* this */,
            jobjectArray arguments) {

        // 启动重定向，这样 node -v 的输出就会出现在 Logcat 中
        static bool is_redirected = false;
        if (!is_redirected) {
            start_redirecting_stdout();
            is_redirected = true;
        }

        jsize argc = env->GetArrayLength(arguments);
        char **argv = (char **) malloc(sizeof(char *) * argc);

        for (int i = 0; i < argc; i++) {
            auto js = (jstring) env->GetObjectArrayElement(arguments, i);
            const char *native_str = env->GetStringUTFChars(js, nullptr);
            argv[i] = strdup(native_str);
            env->ReleaseStringUTFChars(js, native_str);
        }

        // 启动 Node 引擎
        int result = node::Start(argc, argv);

        for (int i = 0; i < argc; i++) {
            free(argv[i]);
        }
        free(argv);

        return jint(result);
    }
}