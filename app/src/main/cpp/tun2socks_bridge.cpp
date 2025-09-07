// Real integration bridge with fallback stub.
// Enhanced: multi-name dlopen attempts + detailed logging to help users
// drop in a prebuilt libtun2socks_core.so for various ABIs (arm / x86 / x86_64).
#include <jni.h>
#include <string>
#include <atomic>
#include <thread>
#include <dlfcn.h>
#include <unistd.h>
#include <sys/types.h>
#include <sys/stat.h>
#include <fcntl.h>
#include <netinet/in.h>
#include <arpa/inet.h>
#include <cstring>
#include <android/log.h>

#define LOG_TAG "Tun2SocksBridge"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

static JavaVM* g_vm = nullptr;
static jclass g_bridgeClass = nullptr;
static jmethodID g_onPacketCaptured = nullptr;
static std::atomic<bool> g_running{false};
static int g_tun_fd = -1;
static int g_mtu = 1500;
static std::string g_socks_server;
static std::string g_dns_server;

// Dynamic core symbols (optional)
typedef int (*fn_core_init)(int,const char*,const char*,int);
typedef int (*fn_core_start)();
typedef void (*fn_core_stop)();
typedef void (*fn_core_setlog)(int);
typedef const char* (*fn_core_version)();
typedef void (*fn_core_register_cb)(void (*)(int,int,const char*,int,const char*,int,const uint8_t*,int));
typedef void (*fn_core_set_protect)(int (*)(int));

static fn_core_init core_init = nullptr;
static fn_core_start core_start = nullptr;
static fn_core_stop core_stop = nullptr;
static fn_core_setlog core_setlog = nullptr;
static fn_core_version core_version = nullptr;
static fn_core_register_cb core_register = nullptr;
static fn_core_set_protect core_set_protect = nullptr;
static jmethodID g_protectFd = nullptr; // static int protectFd(int fd)
static void* core_handle = nullptr;
static bool core_loaded = false;

static void jniAttachThread(JNIEnv** env) {
    if (g_vm->GetEnv((void**)env, JNI_VERSION_1_6) != JNI_OK) {
        g_vm->AttachCurrentThread(env, nullptr);
    }
}
static void jniDetachThread() { g_vm->DetachCurrentThread(); }

// Callback executed by core engine if available
static void core_packet_cb(int direction, int proto, const char* srcIp, int srcPort,
                           const char* dstIp, int dstPort, const uint8_t* payload, int length) {
    JNIEnv* env = nullptr;
    jniAttachThread(&env);
    if (!g_onPacketCaptured) return;
    jstring jsrc = env->NewStringUTF(srcIp);
    jstring jdst = env->NewStringUTF(dstIp);
    jbyteArray arr = nullptr;
    if (payload && length > 0) {
        arr = env->NewByteArray(length);
        env->SetByteArrayRegion(arr, 0, length, (const jbyte*)payload);
    }
    env->CallStaticVoidMethod(g_bridgeClass, g_onPacketCaptured,
                              direction, proto, jsrc, srcPort, jdst, dstPort, arr);
    env->DeleteLocalRef(jsrc);
    env->DeleteLocalRef(jdst);
    if (arr) env->DeleteLocalRef(arr);
}

static void load_core_if_present() {
    if (core_loaded) return;
    const char* candidates[] = {
            "libtun2socks_core.so",            // primary expected name
            "libtun2socks_core_v1.so",         // alternative versioned name (optional)
            "libtun2socks.so"                  // some distributions may use this
    };
    for (const char* name : candidates) {
        core_handle = dlopen(name, RTLD_NOW);
        if (core_handle) {
            LOGI("Loaded core library candidate: %s", name);
            break;
        } else {
            const char* err = dlerror();
            LOGW("Failed to load %s: %s", name, err ? err : "unknown");
        }
    }
    if (!core_handle) {
        LOGW("No core library found. Using stub fallback (synthetic packets only).");
        return; // fallback to stub
    }
    core_init = (fn_core_init)dlsym(core_handle, "tt_init");
    core_start = (fn_core_start)dlsym(core_handle, "tt_start");
    core_stop = (fn_core_stop)dlsym(core_handle, "tt_stop");
    core_setlog = (fn_core_setlog)dlsym(core_handle, "tt_set_log_level");
    core_version = (fn_core_version)dlsym(core_handle, "tt_version");
    core_register = (fn_core_register_cb)dlsym(core_handle, "tt_register_callback");
    core_set_protect = (fn_core_set_protect)dlsym(core_handle, "tt_set_protect_callback");
    
    LOGI("Symbol lookup results: init=%p start=%p stop=%p setlog=%p version=%p register=%p protect=%p", 
         (void*)core_init, (void*)core_start, (void*)core_stop, (void*)core_setlog, 
         (void*)core_version, (void*)core_register, (void*)core_set_protect);

    if (!(core_init && core_start && core_stop)) {
        LOGE("Core library missing required symbols (tt_init/tt_start/tt_stop). Reverting to stub.");
        dlclose(core_handle);
        core_handle = nullptr;
        return;
    }
    if (core_register) {
    if (!core_set_protect) {
        LOGW("Core library missing optional tt_set_protect_callback symbol; sockets may loop through VPN.");
    }
        core_register(core_packet_cb);
    } else {
        LOGW("Core library missing optional tt_register_callback symbol; no live packet callbacks.");
    }
    core_loaded = true;
    LOGI("Core library initialized successfully.");
}

// Fallback stub generator
static void start_stub_generator() {
    std::thread([](){
        JNIEnv* e=nullptr; jniAttachThread(&e);
        int counter=0;
        while (g_running.load()) {
            std::this_thread::sleep_for(std::chrono::milliseconds(1200));
            if (!g_onPacketCaptured) continue;
            std::string src = "10.0.0.2";
            std::string dst = "93.184.216.34"; // example
            std::string payload = "HTTP/1.1 200 OK\r\nX-Stub:"+std::to_string(counter++)+"\r\n\r\n";
            jstring jsrc = e->NewStringUTF(src.c_str());
            jstring jdst = e->NewStringUTF(dst.c_str());
            jbyteArray arr = e->NewByteArray((jsize)payload.size());
            e->SetByteArrayRegion(arr,0,(jsize)payload.size(),(const jbyte*)payload.data());
            e->CallStaticVoidMethod(g_bridgeClass, g_onPacketCaptured, 1, 6, jsrc, 443, jdst, 55555, arr);
            e->DeleteLocalRef(jsrc); e->DeleteLocalRef(jdst); e->DeleteLocalRef(arr);
        }
        jniDetachThread();
    }).detach();
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_trafficcapture_tun2socks_Tun2SocksBridge_nativeInit(JNIEnv* env, jobject, jint tunFd, jint mtu, jstring socksServer, jstring dnsServer) {
    if (!g_bridgeClass) {
        jclass local = env->FindClass("com/trafficcapture/tun2socks/Tun2SocksBridge");
        if (!local) return JNI_FALSE;
        g_bridgeClass = (jclass)env->NewGlobalRef(local);
        env->DeleteLocalRef(local);
        g_onPacketCaptured = env->GetStaticMethodID(g_bridgeClass, "onPacketCaptured", "(IILjava/lang/String;ILjava/lang/String;I[B)V");
    g_protectFd = env->GetStaticMethodID(g_bridgeClass, "protectFd", "(I)I");
    }
    g_tun_fd = tunFd;
    g_mtu = mtu;
    const char* socks = socksServer ? env->GetStringUTFChars(socksServer, nullptr) : nullptr;
    const char* dns = dnsServer ? env->GetStringUTFChars(dnsServer, nullptr) : nullptr;
    g_socks_server = socks ? socks : "";
    g_dns_server = dns ? dns : "";
    if (socks) env->ReleaseStringUTFChars(socksServer, socks);
    if (dns) env->ReleaseStringUTFChars(dnsServer, dns);
    load_core_if_present();
    if (core_loaded && core_init) {
        int rc = core_init(g_tun_fd, g_socks_server.c_str(), g_dns_server.c_str(), g_mtu);
        if (rc == 0) {
            LOGI("Core init succeeded (fd=%d mtu=%d socks=%s dns=%s)", g_tun_fd, g_mtu, g_socks_server.c_str(), g_dns_server.c_str());
            return JNI_TRUE;
        } else {
            LOGE("Core init failed rc=%d; using stub fallback.", rc);
        }
    }
    return JNI_TRUE; // allow fallback even if init failed
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_trafficcapture_tun2socks_Tun2SocksBridge_nativeStart(JNIEnv* env, jobject) {
    if (g_running.exchange(true)) return JNI_TRUE;
    if (core_loaded && core_start) {
        if (core_start() == 0) return JNI_TRUE;
    }
    // fallback stub
    start_stub_generator();
    return JNI_TRUE;
}

// Adapter for core -> Java protect(fd)
static int core_protect_adapter(int fd) {
    if (!g_bridgeClass || !g_protectFd) return 0;
    JNIEnv* env = nullptr; jniAttachThread(&env);
    jint r = env->CallStaticIntMethod(g_bridgeClass, g_protectFd, (jint)fd);
    return r == 1 ? 1 : 0;
}

extern "C" JNIEXPORT void JNICALL
Java_com_trafficcapture_tun2socks_Tun2SocksBridge_nativeInstallProtectCallback(JNIEnv* env, jobject) {
    if (core_loaded && core_set_protect) {
        core_set_protect(core_protect_adapter);
        LOGI("Installed protect callback into core");
    } else {
        LOGW("nativeInstallProtectCallback: core not loaded or setter missing");
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_trafficcapture_tun2socks_Tun2SocksBridge_nativeStop(JNIEnv* env, jobject) {
    g_running.store(false);
    if (core_loaded && core_stop) core_stop();
}

extern "C" JNIEXPORT void JNICALL
Java_com_trafficcapture_tun2socks_Tun2SocksBridge_nativeSetLogLevel(JNIEnv* env, jobject, jint level) {
    if (core_loaded && core_setlog) core_setlog(level);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_trafficcapture_tun2socks_Tun2SocksBridge_nativeVersion(JNIEnv* env, jobject) {
    if (core_loaded && core_version) return env->NewStringUTF(core_version());
    return env->NewStringUTF("stub-fallback");
}

jint JNI_OnLoad(JavaVM* vm, void*) {
    g_vm = vm;
    return JNI_VERSION_1_6;
}
