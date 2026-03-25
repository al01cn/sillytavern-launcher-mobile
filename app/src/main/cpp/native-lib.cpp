#include <jni.h>
#include <string>
#include <vector>
#include <cstdlib>
#include <cstring>
#include "include/node/node.h"

extern "C" JNIEXPORT jint JNICALL
// 注意这里的 sillytavern_1launcher，下划线后面要加一个 1
Java_cn_al01_sillytavern_1launcher_MainActivity_startNodeWithArguments(
        JNIEnv* env,
        jobject /* this */,
        jobjectArray arguments) {

    jsize argc = env->GetArrayLength(arguments);
    char **argv = (char**)malloc(sizeof(char*) * argc);

    for (int i = 0; i < argc; i++) {
        jstring js = (jstring)env->GetObjectArrayElement(arguments, i);
        const char* native_str = env->GetStringUTFChars(js, 0);
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