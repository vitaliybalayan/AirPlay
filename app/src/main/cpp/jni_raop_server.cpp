//
// Created by Administrator on 2019/1/29/029.
//

#include <jni.h>
#include <stddef.h>
#include "lib/raop.h"
#include "log.h"
#include "lib/stream.h"
#include "lib/logger.h"
#include <malloc.h>
#include <cstring>
#include <cstdio>

static JavaVM* g_JavaVM;
static jobject g_RaopServerObj = NULL;  // global ref for log forwarding
static jmethodID g_onRecvVideoDataM = NULL;
static jmethodID g_onRecvAudioDataM = NULL;
static jmethodID g_onAudioVolumeChangedM = NULL;

void OnRecvAudioData(void *observer, pcm_data_struct *data) {
    jobject obj = (jobject) observer;
    JNIEnv* jniEnv = NULL;
    int attached = 0;
    int getEnvStat = g_JavaVM->GetEnv((void **)&jniEnv, JNI_VERSION_1_6);
    if (getEnvStat == JNI_EDETACHED) {
        g_JavaVM->AttachCurrentThread(&jniEnv, NULL);
        attached = 1;
    }
    if (jniEnv == NULL || g_onRecvAudioDataM == NULL) return;
    
    jshortArray sarr = jniEnv->NewShortArray(data->data_len);
    if (sarr == NULL) return;
    jniEnv->SetShortArrayRegion(sarr, 0, data->data_len, (jshort *) data->data);
    jniEnv->CallVoidMethod(obj, g_onRecvAudioDataM, sarr, data->pts);
    jniEnv->DeleteLocalRef(sarr);
    
    if (attached) {
        g_JavaVM->DetachCurrentThread();
    }
}


void OnRecvVideoData(void *observer, h264_decode_struct *data) {
    jobject obj = (jobject) observer;
    JNIEnv* jniEnv = NULL;
    int attached = 0;
    int getEnvStat = g_JavaVM->GetEnv((void **)&jniEnv, JNI_VERSION_1_6);
    if (getEnvStat == JNI_EDETACHED) {
        g_JavaVM->AttachCurrentThread(&jniEnv, NULL);
        attached = 1;
    }
    if (jniEnv == NULL || g_onRecvVideoDataM == NULL) return;
    
    jbyteArray barr = jniEnv->NewByteArray(data->data_len);
    if (barr == NULL) return;
    jniEnv->SetByteArrayRegion(barr, 0, data->data_len, (jbyte *) data->data);
    jniEnv->CallVoidMethod(obj, g_onRecvVideoDataM, barr, data->frame_type, data->pts, data->pts);
    jniEnv->DeleteLocalRef(barr);
    
    if (attached) {
        g_JavaVM->DetachCurrentThread();
    }
}

extern "C" void
audio_process(void *cls, pcm_data_struct *data)
{
    OnRecvAudioData(cls, data);
}

extern "C" void
audio_set_volume(void *cls, void *opaque, float volume)
{
    jobject obj = (jobject) cls;
    JNIEnv* jniEnv = NULL;
    int attached = 0;
    int getEnvStat = g_JavaVM->GetEnv((void **)&jniEnv, JNI_VERSION_1_6);
    if (getEnvStat == JNI_EDETACHED) {
        g_JavaVM->AttachCurrentThread(&jniEnv, NULL);
        attached = 1;
    }
    if (jniEnv != NULL && g_onAudioVolumeChangedM != NULL) {
        jniEnv->CallVoidMethod(obj, g_onAudioVolumeChangedM, volume);
    }
    if (attached) {
        g_JavaVM->DetachCurrentThread();
    }
}

extern "C" void
video_process(void *cls, h264_decode_struct *data)
{
    OnRecvVideoData(cls, data);
}

// Forward C++ logs to Java for on-screen display
static void forwardLogToJava(int level, const char *msg) {
    if (g_RaopServerObj == NULL || g_JavaVM == NULL) return;
    
    JNIEnv* jniEnv = NULL;
    int attached = 0;
    int getEnvStat = g_JavaVM->GetEnv((void **)&jniEnv, JNI_VERSION_1_6);
    if (getEnvStat == JNI_EDETACHED) {
        g_JavaVM->AttachCurrentThread(&jniEnv, NULL);
        attached = 1;
    }
    if (jniEnv == NULL) return;
    
    jclass cls = jniEnv->GetObjectClass(g_RaopServerObj);
    jmethodID mid = jniEnv->GetMethodID(cls, "onNativeLog", "(ILjava/lang/String;)V");
    jniEnv->DeleteLocalRef(cls);
    
    if (mid != NULL) {
        jstring jmsg = jniEnv->NewStringUTF(msg);
        jniEnv->CallVoidMethod(g_RaopServerObj, mid, level, jmsg);
        jniEnv->DeleteLocalRef(jmsg);
    }
    
    if (attached) {
        g_JavaVM->DetachCurrentThread();
    }
}

extern "C" void
log_callback(void *cls, int level, const char *msg) {
    // Always log to logcat
    switch (level) {
        case LOGGER_DEBUG: {
            LOGD("%s", msg);
            break;
        }
        case LOGGER_WARNING: {
            LOGW("%s", msg);
            break;
        }
        case LOGGER_INFO: {
            LOGI("%s", msg);
            break;
        }
        case LOGGER_ERR: {
            LOGE("%s", msg);
            break;
        }
        default:break;
    }
    // Also forward to Java for on-screen display
    forwardLogToJava(level, msg);
}

extern "C" JNIEXPORT jint JNICALL
JNI_OnLoad(JavaVM* vm, void* reserved) {
    g_JavaVM = vm;
    return JNI_VERSION_1_6;
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_fang_myapplication_RaopServer_start(JNIEnv* env, jobject object) {
    // Save global ref for log forwarding
    g_RaopServerObj = env->NewGlobalRef(object);
    
    jclass cls = env->GetObjectClass(object);
    g_onRecvVideoDataM = env->GetMethodID(cls, "onRecvVideoData", "([BIJJ)V");
    g_onRecvAudioDataM = env->GetMethodID(cls, "onRecvAudioData", "([SJ)V");
    g_onAudioVolumeChangedM = env->GetMethodID(cls, "onAudioVolumeChanged", "(F)V");
    env->DeleteLocalRef(cls);
    
    raop_t *raop;
    raop_callbacks_t raop_cbs;
    memset(&raop_cbs, 0, sizeof(raop_cbs));
    raop_cbs.cls = (void *) env->NewGlobalRef(object);;
    raop_cbs.audio_process = audio_process;
    raop_cbs.audio_set_volume = audio_set_volume;
    raop_cbs.video_process = video_process;
    raop = raop_init(10, &raop_cbs);
    if (raop == NULL) {
        LOGE("raop = NULL");
        forwardLogToJava(LOGGER_ERR, "raop_init FAILED - returned NULL");
        return 0;
    } else {
        LOGD("raop init success");
        forwardLogToJava(LOGGER_INFO, "raop_init SUCCESS");
    }

    raop_set_log_callback(raop, log_callback, NULL);
    raop_set_log_level(raop, RAOP_LOG_DEBUG);

    unsigned short port = 0;
    int start_result = raop_start(raop, &port);
    raop_set_port(raop, port);
    
    char portmsg[128];
    sprintf(portmsg, "raop_start result=%d, port=%d", start_result, raop_get_port(raop));
    LOGD("%s", portmsg);
    forwardLogToJava(LOGGER_INFO, portmsg);
    
    return (jlong) (void *) raop;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_fang_myapplication_RaopServer_getPort(JNIEnv* env, jobject object, jlong opaque) {
    raop_t *raop = (raop_t *) (void *) opaque;
    return raop_get_port(raop);
}

extern "C" JNIEXPORT void JNICALL
Java_com_fang_myapplication_RaopServer_stop(JNIEnv* env, jobject object, jlong opaque) {
    raop_t *raop = (raop_t *) (void *) opaque;
    jobject obj = (jobject) raop_get_callback_cls(raop);
    raop_destroy(raop);
    env->DeleteGlobalRef(obj);
    if (g_RaopServerObj != NULL) {
        env->DeleteGlobalRef(g_RaopServerObj);
        g_RaopServerObj = NULL;
    }
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_fang_myapplication_RaopServer_getPublicKey(JNIEnv* env, jobject object, jlong opaque) {
    raop_t *raop = (raop_t *) (void *) opaque;
    unsigned char pk[32];
    raop_get_public_key(raop, pk);
    
    // Convert to hex string
    char hex[65];
    for (int i = 0; i < 32; i++) {
        sprintf(hex + i * 2, "%02x", pk[i]);
    }
    hex[64] = '\0';
    return env->NewStringUTF(hex);
}
