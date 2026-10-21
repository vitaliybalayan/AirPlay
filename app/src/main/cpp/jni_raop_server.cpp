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
static jmethodID g_onVideoStreamStoppedM = NULL;
static jmethodID g_onDirectPlayM = NULL;
static jmethodID g_onDirectStopM = NULL;
static jmethodID g_onDirectScrubM = NULL;
static jmethodID g_onDirectRateM = NULL;
static jmethodID g_getDirectPlaybackInfoM = NULL;
static jmethodID g_onAudioSessionM = NULL;
static jmethodID g_onAudioMetadataM = NULL;
static jmethodID g_onAudioCoverArtM = NULL;
static jmethodID g_onAudioProgressM = NULL;

// Attaches the calling native thread to the JVM for the lifetime of the object.
struct ScopedJniEnv {
    JNIEnv *env = NULL;
    bool attached = false;

    ScopedJniEnv() {
        if (g_JavaVM == NULL) return;
        if (g_JavaVM->GetEnv((void **) &env, JNI_VERSION_1_6) == JNI_EDETACHED) {
            if (g_JavaVM->AttachCurrentThread(&env, NULL) == JNI_OK) {
                attached = true;
            } else {
                env = NULL;
            }
        }
    }

    ~ScopedJniEnv() {
        if (attached) {
            g_JavaVM->DetachCurrentThread();
        }
    }
};

static void clearPendingException(JNIEnv *env) {
    if (env->ExceptionCheck()) {
        env->ExceptionDescribe();
        env->ExceptionClear();
    }
}

void OnRecvAudioData(void *observer, pcm_data_struct *data) {
    jobject obj = (jobject) observer;
    JNIEnv* jniEnv = NULL;
    int attached = 0;
    int getEnvStat = g_JavaVM->GetEnv((void **)&jniEnv, JNI_VERSION_1_6);
    if (getEnvStat == JNI_EDETACHED) {
        g_JavaVM->AttachCurrentThread(&jniEnv, NULL);
        attached = 1;
    }
    if (jniEnv == NULL || g_onRecvAudioDataM == NULL) {
        if (attached) {
            g_JavaVM->DetachCurrentThread();
        }
        return;
    }
    
    jsize sample_count = data->data_len / (int) sizeof(unsigned short);
    jshortArray sarr = jniEnv->NewShortArray(sample_count);
    if (sarr == NULL) {
        if (attached) {
            g_JavaVM->DetachCurrentThread();
        }
        return;
    }
    jniEnv->SetShortArrayRegion(sarr, 0, sample_count, (jshort *) data->data);
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
    if (jniEnv == NULL || g_onRecvVideoDataM == NULL) {
        if (attached) {
            g_JavaVM->DetachCurrentThread();
        }
        return;
    }
    
    jbyteArray barr = jniEnv->NewByteArray(data->data_len);
    if (barr == NULL) {
        if (attached) {
            g_JavaVM->DetachCurrentThread();
        }
        return;
    }
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

extern "C" void
video_stop(void *cls, const char *reason)
{
    jobject obj = (jobject) cls;
    JNIEnv* jniEnv = NULL;
    int attached = 0;
    int getEnvStat = g_JavaVM->GetEnv((void **)&jniEnv, JNI_VERSION_1_6);
    if (getEnvStat == JNI_EDETACHED) {
        g_JavaVM->AttachCurrentThread(&jniEnv, NULL);
        attached = 1;
    }
    if (jniEnv != NULL && g_onVideoStreamStoppedM != NULL) {
        jstring jreason = jniEnv->NewStringUTF(reason ? reason : "native-video-stop");
        if (jreason != NULL) {
            jniEnv->CallVoidMethod(obj, g_onVideoStreamStoppedM, jreason);
            jniEnv->DeleteLocalRef(jreason);
        }
    }
    if (attached) {
        g_JavaVM->DetachCurrentThread();
    }
}

extern "C" void
direct_play(void *cls, const char *url, double start_seconds, double start_fraction)
{
    ScopedJniEnv scoped;
    if (scoped.env == NULL || g_onDirectPlayM == NULL) return;
    jstring jurl = scoped.env->NewStringUTF(url ? url : "");
    if (jurl == NULL) return;
    scoped.env->CallVoidMethod((jobject) cls, g_onDirectPlayM, jurl, (jdouble) start_seconds, (jdouble) start_fraction);
    clearPendingException(scoped.env);
    scoped.env->DeleteLocalRef(jurl);
}

extern "C" void
direct_stop(void *cls, const char *reason)
{
    ScopedJniEnv scoped;
    if (scoped.env == NULL || g_onDirectStopM == NULL) return;
    jstring jreason = scoped.env->NewStringUTF(reason ? reason : "direct-stop");
    if (jreason == NULL) return;
    scoped.env->CallVoidMethod((jobject) cls, g_onDirectStopM, jreason);
    clearPendingException(scoped.env);
    scoped.env->DeleteLocalRef(jreason);
}

extern "C" void
direct_scrub(void *cls, double position)
{
    ScopedJniEnv scoped;
    if (scoped.env == NULL || g_onDirectScrubM == NULL) return;
    scoped.env->CallVoidMethod((jobject) cls, g_onDirectScrubM, (jdouble) position);
    clearPendingException(scoped.env);
}

extern "C" void
direct_rate(void *cls, double rate)
{
    ScopedJniEnv scoped;
    if (scoped.env == NULL || g_onDirectRateM == NULL) return;
    scoped.env->CallVoidMethod((jobject) cls, g_onDirectRateM, (jdouble) rate);
    clearPendingException(scoped.env);
}

extern "C" void
audio_session(void *cls, int active, int compression_type)
{
    ScopedJniEnv scoped;
    if (scoped.env == NULL || g_onAudioSessionM == NULL) return;
    scoped.env->CallVoidMethod((jobject) cls, g_onAudioSessionM, (jboolean) (active != 0), (jint) compression_type);
    clearPendingException(scoped.env);
}

static void
call_with_bytes(void *cls, jmethodID method, const void *buffer, int buflen)
{
    ScopedJniEnv scoped;
    if (scoped.env == NULL || method == NULL || buflen < 0) return;
    jbyteArray arr = scoped.env->NewByteArray(buflen);
    if (arr == NULL) return;
    if (buflen > 0) {
        scoped.env->SetByteArrayRegion(arr, 0, buflen, (const jbyte *) buffer);
    }
    scoped.env->CallVoidMethod((jobject) cls, method, arr);
    clearPendingException(scoped.env);
    scoped.env->DeleteLocalRef(arr);
}

extern "C" void
audio_set_metadata(void *cls, void *session, const void *buffer, int buflen)
{
    call_with_bytes(cls, g_onAudioMetadataM, buffer, buflen);
}

extern "C" void
audio_set_coverart(void *cls, void *session, const void *buffer, int buflen)
{
    call_with_bytes(cls, g_onAudioCoverArtM, buffer, buflen);
}

extern "C" void
audio_set_progress(void *cls, void *session, unsigned int start, unsigned int curr, unsigned int end)
{
    ScopedJniEnv scoped;
    if (scoped.env == NULL || g_onAudioProgressM == NULL) return;
    scoped.env->CallVoidMethod((jobject) cls, g_onAudioProgressM, (jlong) start, (jlong) curr, (jlong) end);
    clearPendingException(scoped.env);
}

// Java returns {active, ready, duration, position, rate, buffered}
extern "C" int
direct_get_playback_info(void *cls, raop_playback_info_t *info)
{
    ScopedJniEnv scoped;
    if (scoped.env == NULL || g_getDirectPlaybackInfoM == NULL || info == NULL) return 0;
    jdoubleArray result = (jdoubleArray) scoped.env->CallObjectMethod((jobject) cls, g_getDirectPlaybackInfoM);
    clearPendingException(scoped.env);
    if (result == NULL) return 0;
    int active = 0;
    jdouble values[6];
    if (scoped.env->GetArrayLength(result) >= 6) {
        scoped.env->GetDoubleArrayRegion(result, 0, 6, values);
        active = values[0] != 0.0;
        info->ready = values[1] != 0.0;
        info->duration = values[2];
        info->position = values[3];
        info->rate = values[4];
        info->buffered = values[5];
    }
    scoped.env->DeleteLocalRef(result);
    return active;
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
    if (jniEnv == NULL) {
        if (attached) g_JavaVM->DetachCurrentThread();
        return;
    }
    
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
Java_com_fang_myapplication_RaopServer_start(JNIEnv* env, jobject object, jstring deviceName, jstring deviceId) {
    // Save global ref for log forwarding
    g_RaopServerObj = env->NewGlobalRef(object);
    
    jclass cls = env->GetObjectClass(object);
    g_onRecvVideoDataM = env->GetMethodID(cls, "onRecvVideoData", "([BIJJ)V");
    g_onRecvAudioDataM = env->GetMethodID(cls, "onRecvAudioData", "([SJ)V");
    g_onAudioVolumeChangedM = env->GetMethodID(cls, "onAudioVolumeChanged", "(F)V");
    g_onVideoStreamStoppedM = env->GetMethodID(cls, "onVideoStreamStopped", "(Ljava/lang/String;)V");
    g_onDirectPlayM = env->GetMethodID(cls, "onDirectPlay", "(Ljava/lang/String;DD)V");
    g_onDirectStopM = env->GetMethodID(cls, "onDirectStop", "(Ljava/lang/String;)V");
    g_onDirectScrubM = env->GetMethodID(cls, "onDirectScrub", "(D)V");
    g_onDirectRateM = env->GetMethodID(cls, "onDirectRate", "(D)V");
    g_getDirectPlaybackInfoM = env->GetMethodID(cls, "getDirectPlaybackInfo", "()[D");
    g_onAudioSessionM = env->GetMethodID(cls, "onAudioSession", "(ZI)V");
    g_onAudioMetadataM = env->GetMethodID(cls, "onAudioMetadata", "([B)V");
    g_onAudioCoverArtM = env->GetMethodID(cls, "onAudioCoverArt", "([B)V");
    g_onAudioProgressM = env->GetMethodID(cls, "onAudioProgress", "(JJJ)V");
    env->DeleteLocalRef(cls);
    
    raop_t *raop;
    raop_callbacks_t raop_cbs;
    memset(&raop_cbs, 0, sizeof(raop_cbs));
    raop_cbs.cls = (void *) env->NewGlobalRef(object);
    raop_cbs.audio_process = audio_process;
    raop_cbs.audio_set_volume = audio_set_volume;
    raop_cbs.video_process = video_process;
    raop_cbs.video_stop = video_stop;
    raop_cbs.direct_play = direct_play;
    raop_cbs.direct_stop = direct_stop;
    raop_cbs.direct_scrub = direct_scrub;
    raop_cbs.direct_rate = direct_rate;
    raop_cbs.direct_get_playback_info = direct_get_playback_info;
    raop_cbs.audio_session = audio_session;
    raop_cbs.audio_set_metadata = audio_set_metadata;
    raop_cbs.audio_set_coverart = audio_set_coverart;
    raop_cbs.audio_set_progress = audio_set_progress;
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

    const char *name = deviceName ? env->GetStringUTFChars(deviceName, NULL) : NULL;
    const char *id = deviceId ? env->GetStringUTFChars(deviceId, NULL) : NULL;
    raop_set_device_info(raop, name, id);
    if (name) env->ReleaseStringUTFChars(deviceName, name);
    if (id) env->ReleaseStringUTFChars(deviceId, id);
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

extern "C" JNIEXPORT void JNICALL
Java_com_fang_myapplication_RaopServer_setReceiverVolume(JNIEnv* env, jobject object, jlong opaque, jfloat volumeDb) {
    raop_set_volume((raop_t *) (void *) opaque, volumeDb);
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
