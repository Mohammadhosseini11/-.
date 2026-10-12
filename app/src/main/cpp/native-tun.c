#include <jni.h>
#include <pthread.h>
#include <dlfcn.h>
#include <android/log.h>
#include <string.h>
#include <stdlib.h>

#define TAG "GozarCore"

typedef int  (*hev_main_fn)(const char*, int);
typedef void (*hev_quit_fn)(void);

static hev_main_fn s_main = NULL;
static hev_quit_fn s_quit = NULL;
static pthread_t   s_thread;
static char       *s_cfg = NULL;
static int         s_fd  = -1;
static volatile int s_running = 0;

static void* run_thread(void* arg) {
    __android_log_print(ANDROID_LOG_INFO, TAG, "hev bridge: entering main");
    int r = s_main(s_cfg, s_fd);
    __android_log_print(ANDROID_LOG_INFO, TAG, "hev bridge: main exited rc=%d", r);
    s_running = 0;
    return NULL;
}

JNIEXPORT jint JNICALL
Java_com_gozar_vpn_TunBridge_start(JNIEnv* env, jclass clz, jstring cfg, jint fd) {
    if (s_running) return -2;

    const char* c = (*env)->GetStringUTFChars(env, cfg, 0);
    if (s_cfg) free(s_cfg);
    s_cfg = strdup(c);
    (*env)->ReleaseStringUTFChars(env, cfg, c);
    s_fd = (int) fd;

    if (!s_main) {
        s_main = (hev_main_fn) dlsym(RTLD_DEFAULT, "hev_socks5_tunnel_main");
    }
    if (!s_quit) {
        s_quit = (hev_quit_fn) dlsym(RTLD_DEFAULT, "hev_socks5_tunnel_quit");
        if (!s_quit) s_quit = (hev_quit_fn) dlsym(RTLD_DEFAULT, "hev_socks5_tunnel_terminate");
    }
    if (!s_main) {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "hev symbols NOT found in libgozar-tun");
        return -1;
    }

    __android_log_print(ANDROID_LOG_INFO, TAG, "hev bridge: start fd=%d cfg=%s", s_fd, s_cfg);
    s_running = 1;
    if (pthread_create(&s_thread, NULL, run_thread, NULL) != 0) {
        s_running = 0;
        return -3;
    }
    return 0;
}

JNIEXPORT void JNICALL
Java_com_gozar_vpn_TunBridge_stop(JNIEnv* env, jclass clz) {
    if (s_quit && s_running) {
        __android_log_print(ANDROID_LOG_INFO, TAG, "hev bridge: quit");
        s_quit();
    }
}