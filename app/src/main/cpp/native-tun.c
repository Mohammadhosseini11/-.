#include <jni.h>
#include <pthread.h>
#include <dlfcn.h>
#include <android/log.h>
#include <string.h>
#include <stdlib.h>

#define TAG "GozarCore"

typedef int  (*hev_main_fn)(const char*, int);
typedef void (*hev_quit_fn)(void);

static pthread_t    s_thread;
static char        *s_cfg = NULL;
static int          s_fd  = -1;
static volatile int s_running = 0;

#ifndef HEV_STATIC
static void       *s_lib  = NULL;
static hev_main_fn p_main = NULL;
static hev_quit_fn p_quit = NULL;

static int load_hev(void) {
    if (p_main) return 1;
    s_lib = dlopen("libhev-socks5-tunnel.so", RTLD_NOW | RTLD_GLOBAL);
    if (!s_lib) {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "dlopen failed: %s", dlerror());
        return 0;
    }
    p_main = (hev_main_fn) dlsym(s_lib, "hev_socks5_tunnel_main");
    p_quit = (hev_quit_fn) dlsym(s_lib, "hev_socks5_tunnel_quit");
    if (!p_main) {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "symbol hev_socks5_tunnel_main not found");
        return 0;
    }
    __android_log_print(ANDROID_LOG_INFO, TAG, "hev loaded (dynamic)");
    return 1;
}
#define HAVE_BRIDGE() load_hev()
#define CALL_MAIN(cfg, fd) p_main(cfg, fd)
#define CALL_QUIT()        do { if (p_quit) p_quit(); } while (0)
#else
extern int  hev_socks5_tunnel_main(const char *config_file, int tun_fd);
extern void hev_socks5_tunnel_quit(void);
#define HAVE_BRIDGE() 1
#define CALL_MAIN(cfg, fd) hev_socks5_tunnel_main(cfg, fd)
#define CALL_QUIT()        hev_socks5_tunnel_quit()
#endif

static void* run_thread(void* arg) {
    __android_log_print(ANDROID_LOG_INFO, TAG, "hev bridge: entering main (fd=%d)", s_fd);
    int r = CALL_MAIN(s_cfg, s_fd);
    __android_log_print(ANDROID_LOG_INFO, TAG, "hev bridge: main exited rc=%d", r);
    s_running = 0;
    return NULL;
}

JNIEXPORT jint JNICALL
Java_com_gozar_vpn_TunBridge_nativeStart(JNIEnv* env, jclass clz, jstring cfg, jint fd) {
    if (s_running) return -2;
    if (!HAVE_BRIDGE()) return -1;

    const char* c = (*env)->GetStringUTFChars(env, cfg, 0);
    if (s_cfg) free(s_cfg);
    s_cfg = strdup(c);
    (*env)->ReleaseStringUTFChars(env, cfg, c);
    s_fd = (int) fd;

    __android_log_print(ANDROID_LOG_INFO, TAG, "hev bridge: start cfg=%s", s_cfg);
    s_running = 1;
    if (pthread_create(&s_thread, NULL, run_thread, NULL) != 0) {
        s_running = 0;
        return -3;
    }
    return 0;
}

JNIEXPORT void JNICALL
Java_com_gozar_vpn_TunBridge_nativeStop(JNIEnv* env, jclass clz) {
    if (s_running) {
        __android_log_print(ANDROID_LOG_INFO, TAG, "hev bridge: quit");
        CALL_QUIT();
    }
}
