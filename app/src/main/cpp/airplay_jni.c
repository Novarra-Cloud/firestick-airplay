/*
 * JNI bridge between the UxPlay AirPlay server library and the Android app.
 *
 * The UxPlay library (third_party/uxplay/lib) implements service discovery (mDNS),
 * the AirPlay pairing/FairPlay handshake and the RTP mirroring/audio streams. This file
 * starts and supervises that server and forwards decrypted H.264 video and AAC audio
 * frames to Java (AirPlayServer.java), where they are decoded with MediaCodec.
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

#include <jni.h>
#include <math.h>
#include <pthread.h>
#include <stdarg.h>
#include <stdbool.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

#include "dnssd.h"
#include "logger.h"
#include "raop.h"
#include "stream.h"

#ifdef __ANDROID__
#include <android/log.h>
#define ALOG(prio, ...) __android_log_print(prio, "AirPlayNative", __VA_ARGS__)
#define LOG_I ANDROID_LOG_INFO
#define LOG_W ANDROID_LOG_WARN
#define LOG_E ANDROID_LOG_ERROR
#define LOG_D ANDROID_LOG_DEBUG
#else
#define ALOG(prio, ...) do { (void) (prio); fprintf(stderr, __VA_ARGS__); fputc('\n', stderr); } while (0)
#define LOG_I 4
#define LOG_W 5
#define LOG_E 6
#define LOG_D 3
#endif

/* Seconds without a client heartbeat before the connection is considered dead. */
#define MISSED_FEEDBACK_LIMIT 15

static JavaVM *g_vm = NULL;
static jobject g_server = NULL; /* global ref to the AirPlayServer instance */
static jmethodID m_on_video_frame, m_on_video_size, m_on_video_stop;
static jmethodID m_on_audio_format, m_on_audio_frame, m_on_volume;
static jmethodID m_on_client, m_on_connections, m_on_log;
static jmethodID m_hls_play, m_hls_seek, m_hls_rate, m_hls_stop, m_hls_pause_get_pos, m_hls_info;
static jmethodID m_hls_subtitles;

static pthread_key_t g_env_key;
static pthread_once_t g_env_key_once = PTHREAD_ONCE_INIT;

static pthread_mutex_t g_lock = PTHREAD_MUTEX_INITIALIZER;
static raop_t *g_raop = NULL;
static dnssd_t *g_dnssd = NULL;
static pthread_t g_supervisor;
static volatile bool g_running = false;
static volatile bool g_reset_httpd = false;
static volatile int g_open_connections = 0;
static volatile int g_missed_feedback = 0;
/* true while an "AirPlay video" (HLS/URL) stream is playing; the client then talks to us
 * over the AirPlay video channel and may not send RTSP heartbeats */
static volatile bool g_hls_active = false;
static double g_current_volume = 0.0;

/* ------------------------------------------------------------------ JNI helpers */

static void detach_thread(void *unused) {
    if (g_vm) {
        (*g_vm)->DetachCurrentThread(g_vm);
    }
}

static void make_env_key(void) {
    pthread_key_create(&g_env_key, detach_thread);
}

/* Returns a JNIEnv for the calling thread, attaching native threads on first use. They
 * are detached automatically when the thread exits. */
static JNIEnv *get_env(void) {
    JNIEnv *env = NULL;
    if (!g_vm) {
        return NULL;
    }
    if ((*g_vm)->GetEnv(g_vm, (void **) &env, JNI_VERSION_1_6) == JNI_OK) {
        return env;
    }
    if ((*g_vm)->AttachCurrentThread(g_vm, (void *) &env, NULL) != JNI_OK) {
        return NULL;
    }
    pthread_once(&g_env_key_once, make_env_key);
    pthread_setspecific(g_env_key, env);
    return env;
}

static void clear_exception(JNIEnv *env) {
    if ((*env)->ExceptionCheck(env)) {
        (*env)->ExceptionDescribe(env);
        (*env)->ExceptionClear(env);
    }
}

static jbyteArray to_byte_array(JNIEnv *env, const unsigned char *data, int len) {
    jbyteArray arr = (*env)->NewByteArray(env, len);
    if (arr) {
        (*env)->SetByteArrayRegion(env, arr, 0, len, (const jbyte *) data);
    }
    return arr;
}

static jstring to_jstring(JNIEnv *env, const char *s) {
    /* NewStringUTF needs modified UTF-8; client names are plain UTF-8 which is fine
     * except for 4-byte sequences (emoji). Replace any of those to be safe. */
    if (!s) {
        s = "";
    }
    size_t n = strlen(s);
    char *copy = malloc(n + 1);
    if (!copy) {
        return NULL;
    }
    size_t j = 0;
    for (size_t i = 0; i < n; i++) {
        unsigned char c = (unsigned char) s[i];
        if (c >= 0xF0) {
            copy[j++] = '?';
            while (i + 1 < n && (((unsigned char) s[i + 1]) & 0xC0) == 0x80) {
                i++;
            }
        } else {
            copy[j++] = (char) c;
        }
    }
    copy[j] = '\0';
    jstring js = (*env)->NewStringUTF(env, copy);
    free(copy);
    return js;
}

static void java_log(int level, const char *msg) {
    JNIEnv *env = get_env();
    if (!env || !g_server) {
        return;
    }
    jstring js = to_jstring(env, msg);
    (*env)->CallVoidMethod(env, g_server, m_on_log, (jint) level, js);
    clear_exception(env);
    if (js) {
        (*env)->DeleteLocalRef(env, js);
    }
}

static void call_void(jmethodID m) {
    JNIEnv *env = get_env();
    if (!env || !g_server) {
        return;
    }
    (*env)->CallVoidMethod(env, g_server, m);
    clear_exception(env);
}

/* ------------------------------------------------------------------ RAOP callbacks */

static void log_callback(void *cls, int level, const char *msg) {
    int prio = (level <= LOGGER_ERR) ? LOG_E : (level == LOGGER_WARNING) ? LOG_W
             : (level >= LOGGER_DEBUG) ? LOG_D : LOG_I;
    ALOG(prio, "%s", msg);
    if (level <= LOGGER_INFO) {
        java_log(level, msg);
    }
}

static void conn_init(void *cls) {
    g_open_connections++;
    JNIEnv *env = get_env();
    if (env && g_server) {
        (*env)->CallVoidMethod(env, g_server, m_on_connections, (jint) g_open_connections);
        clear_exception(env);
    }
}

static void conn_destroy(void *cls) {
    if (g_open_connections > 0) {
        g_open_connections--;
    }
    if (g_open_connections == 0) {
        g_missed_feedback = 0;
    }
    JNIEnv *env = get_env();
    if (env && g_server) {
        (*env)->CallVoidMethod(env, g_server, m_on_connections, (jint) g_open_connections);
        clear_exception(env);
    }
}

static void conn_feedback(void *cls) {
    g_missed_feedback = 0;
}

static void conn_reset(void *cls, int reason) {
    ALOG(LOG_W, "connection reset requested (reason %d)", reason);
    g_reset_httpd = true;
}

static void video_reset(void *cls, reset_type_t type) {
    ALOG(LOG_I, "video_reset type %d", (int) type);
    switch (type) {
    case RESET_TYPE_ON_VIDEO_PLAY:
        /* the HLS player is started by on_video_play() */
        return;
    case RESET_TYPE_NOHOLD:
        /* a new client took over: drop any AirPlay video state of the old one */
        if (g_raop) {
            raop_destroy_airplay_video(g_raop, -1);
        }
        g_hls_active = false;
        call_void(m_hls_stop);
        break;
    case RESET_TYPE_HLS_SHUTDOWN:
        if (g_raop) {
            raop_destroy_airplay_video(g_raop, -1);
            raop_remove_hls_connections(g_raop);
        }
        g_hls_active = false;
        call_void(m_hls_stop);
        break;
    case RESET_TYPE_HLS_EOS:
        g_hls_active = false;
        call_void(m_hls_stop);
        break;
    case RESET_TYPE_RTP_TO_HLS_TEARDOWN:
    case RESET_TYPE_RTP_SHUTDOWN:
    default:
        break;
    }
    /* screen mirroring (if any) has ended */
    call_void(m_on_video_stop);
}

static int video_set_codec_and_size(void *cls, video_codec_t codec, unsigned short width_source,
                                    unsigned short height_source, unsigned short x_offset,
                                    unsigned short y_offset, unsigned short width, unsigned short height) {
    ALOG(LOG_I, "video codec %d source %ux%u offset %u,%u display %ux%u", (int) codec,
         width_source, height_source, x_offset, y_offset, width, height);
    if (codec != VIDEO_CODEC_H264) {
        /* Only H.264 is advertised (feature bit 42 is off), so this should not happen. */
        return -1;
    }
    JNIEnv *env = get_env();
    if (env && g_server) {
        (*env)->CallVoidMethod(env, g_server, m_on_video_size,
                               (jint) width_source, (jint) height_source,
                               (jint) width, (jint) height);
        clear_exception(env);
    }
    return 0;
}

static void video_process(void *cls, raop_ntp_t *ntp, video_decode_struct *data) {
    if (!data || data->data_len <= 0 || data->is_h265) {
        return;
    }
    JNIEnv *env = get_env();
    if (!env || !g_server) {
        return;
    }
    jbyteArray arr = to_byte_array(env, data->data, data->data_len);
    if (!arr) {
        clear_exception(env);
        return;
    }
    (*env)->CallVoidMethod(env, g_server, m_on_video_frame, arr, (jlong) data->ntp_time_remote);
    clear_exception(env);
    (*env)->DeleteLocalRef(env, arr);
}

static void video_pause(void *cls) {}
static void video_resume(void *cls) {}
static void video_flush(void *cls) {}
static void audio_flush(void *cls) {}

static void audio_get_format(void *cls, unsigned char *ct, unsigned short *spf, bool *usingScreen,
                             bool *isMedia, uint64_t *audioFormat) {
    ALOG(LOG_I, "audio format ct=%d spf=%d usingScreen=%d isMedia=%d", *ct, *spf, *usingScreen, *isMedia);
    JNIEnv *env = get_env();
    if (env && g_server) {
        (*env)->CallVoidMethod(env, g_server, m_on_audio_format, (jint) *ct);
        clear_exception(env);
    }
}

static void audio_process(void *cls, raop_ntp_t *ntp, audio_decode_struct *data) {
    if (!data || data->data_len <= 0) {
        return;
    }
    JNIEnv *env = get_env();
    if (!env || !g_server) {
        return;
    }
    jbyteArray arr = to_byte_array(env, data->data, data->data_len);
    if (!arr) {
        clear_exception(env);
        return;
    }
    (*env)->CallVoidMethod(env, g_server, m_on_audio_frame, arr, (jint) data->ct);
    clear_exception(env);
    (*env)->DeleteLocalRef(env, arr);
}

static double audio_set_client_volume(void *cls) {
    return g_current_volume;
}

static void audio_set_volume(void *cls, float volume) {
    /* AirPlay volume is in dB, -30..0, with -144 meaning mute. Convert the slider
     * position to a perceptual gain (each halving of the slider is -10 dB). */
    double frac;
    if (volume <= -30.0f) {
        frac = 0.0;
    } else if (volume >= 0.0f) {
        frac = 1.0;
    } else {
        frac = (30.0 + volume) / 30.0;
    }
    g_current_volume = (volume < -30.0f) ? -30.0 : (volume > 0.0f ? 0.0 : volume);
    double gain = 0.0;
    if (frac > 0.0) {
        double db = 10.0 * log2(frac);
        if (db < -30.0 + 30.0 * frac) {
            db = -30.0 + 30.0 * frac;
        }
        gain = pow(10.0, db / 20.0);
    }
    JNIEnv *env = get_env();
    if (env && g_server) {
        (*env)->CallVoidMethod(env, g_server, m_on_volume, (jfloat) gain);
        clear_exception(env);
    }
}

static void audio_set_metadata(void *cls, const void *buffer, int buflen) {}
static void audio_set_coverart(void *cls, const void *buffer, int buflen) {}
static void audio_stop_coverart_rendering(void *cls) {}
static void audio_set_progress(void *cls, uint32_t *start, uint32_t *curr, uint32_t *end) {}
static void export_dacp(void *cls, const char *active_remote, const char *dacp_id) {}
static void mirror_video_running(void *cls, bool is_running) {}

static void report_client_request(void *cls, char *deviceid, char *model, char *name, bool *admit) {
    ALOG(LOG_I, "connection request from %s (%s) deviceID %s", name, model, deviceid);
    *admit = true;
    JNIEnv *env = get_env();
    if (env && g_server) {
        jstring jn = to_jstring(env, name);
        jstring jm = to_jstring(env, model);
        (*env)->CallVoidMethod(env, g_server, m_on_client, jn, jm);
        clear_exception(env);
        if (jn) (*env)->DeleteLocalRef(env, jn);
        if (jm) (*env)->DeleteLocalRef(env, jm);
    }
}

static void display_pin(void *cls, char *pin) {}
static void register_client(void *cls, const char *device_id, const char *pk, const char *name) {}
static bool check_register(void *cls, const char *pk) { return true; }
static const char *passwd(void *cls, int *len) { *len = 0; return NULL; }
static void get_custom_profile(void *cls, const char **custom_profile) { *custom_profile = NULL; }

/* ---- "AirPlay video": the client hands over a video URL (the AirPlay button in a video
 * player). The video is played by ExoPlayer on the Java side, so the phone is free. */

static void on_video_play(void *cls, const char *location, const float start_position) {
    ALOG(LOG_I, "AirPlay video play: %s (start %.1f s)", location ? location : "(null)", start_position);
    if (!location) {
        return;
    }
    JNIEnv *env = get_env();
    if (!env || !g_server) {
        return;
    }
    g_hls_active = true;
    g_missed_feedback = 0;
    jstring url = to_jstring(env, location);
    (*env)->CallVoidMethod(env, g_server, m_hls_play, url, (jfloat) start_position);
    clear_exception(env);
    if (url) (*env)->DeleteLocalRef(env, url);
}

static void on_video_scrub(void *cls, const float position) {
    JNIEnv *env = get_env();
    if (env && g_server) {
        (*env)->CallVoidMethod(env, g_server, m_hls_seek, (jfloat) position);
        clear_exception(env);
    }
}

static void on_video_rate(void *cls, const float rate) {
    JNIEnv *env = get_env();
    if (env && g_server) {
        (*env)->CallVoidMethod(env, g_server, m_hls_rate, (jfloat) rate);
        clear_exception(env);
    }
}

static void on_video_stop(void *cls) {
    ALOG(LOG_I, "AirPlay video stop");
    g_hls_active = false;
    call_void(m_hls_stop);
}

/* Subtitles turned on/off in the iPhone's video player. */
static void on_video_subtitles(void *cls, bool enabled, const char *lang) {
    ALOG(LOG_I, "client subtitles %s (%s)", enabled ? "on" : "off", lang ? lang : "?");
    JNIEnv *env = get_env();
    if (!env || !g_server) {
        return;
    }
    jstring jl = lang ? to_jstring(env, lang) : NULL;
    (*env)->CallVoidMethod(env, g_server, m_hls_subtitles, (jboolean) enabled, jl);
    clear_exception(env);
    if (jl) (*env)->DeleteLocalRef(env, jl);
}

static float on_video_playlist_remove(void *cls) {
    JNIEnv *env = get_env();
    float pos = 0.0f;
    if (env && g_server) {
        pos = (*env)->CallFloatMethod(env, g_server, m_hls_pause_get_pos);
        clear_exception(env);
    }
    return pos;
}

/* Polled by the client (about once a second) to update its progress bar. */
static void on_video_acquire_playback_info(void *cls, playback_info_t *info) {
    g_missed_feedback = 0;
    info->ready_to_play = true;
    info->playback_likely_to_keep_up = true;
    info->playback_buffer_empty = false;
    info->playback_buffer_full = true;
    JNIEnv *env = get_env();
    if (!env || !g_server) {
        return;
    }
    jdoubleArray arr = (jdoubleArray) (*env)->CallObjectMethod(env, g_server, m_hls_info);
    clear_exception(env);
    if (!arr) {
        return;
    }
    jdouble v[5] = {0};
    if ((*env)->GetArrayLength(env, arr) >= 5) {
        (*env)->GetDoubleArrayRegion(env, arr, 0, 5, v);
    }
    (*env)->DeleteLocalRef(env, arr);
    /* v = {duration, position, rate, still_playing, buffering} */
    info->duration = v[0];
    info->position = v[1];
    info->rate = (float) v[2];
    info->seek_start = 0.0;
    info->seek_duration = v[0];
    if (v[4] > 0.5) {
        info->playback_buffer_empty = true;
        info->playback_buffer_full = false;
    }
    if (v[3] < 0.5) {
        /* the video has finished: tell the client */
        info->position = -1.0;
        info->duration = -1.0;
    }
}

/* ------------------------------------------------------------------ server lifecycle */

static int parse_mac(const char *mac, char out[6]) {
    unsigned int b[6];
    if (sscanf(mac, "%2x:%2x:%2x:%2x:%2x:%2x", &b[0], &b[1], &b[2], &b[3], &b[4], &b[5]) != 6) {
        return -1;
    }
    for (int i = 0; i < 6; i++) {
        out[i] = (char) b[i];
    }
    return 0;
}

static void set_features(dnssd_t *d) {
    /* UxPlay's default feature set (mirroring + audio, legacy pairing off, no H.265) */
    /* bits 0 and 4: AirPlay video / HLS, so the AirPlay button in video apps hands the
     * video over instead of mirroring the screen (same as UxPlay's -hls option) */
    static const unsigned char on[] = {0, 1, 2, 4, 5, 6, 7, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19,
                                       20, 21, 22, 25, 28, 30};
    for (int bit = 0; bit < 64; bit++) {
        dnssd_set_airplay_features(d, bit, 0);
    }
    for (size_t i = 0; i < sizeof(on); i++) {
        dnssd_set_airplay_features(d, on[i], 1);
    }
}

static void *supervisor_thread(void *arg) {
    int ticks = 0;
    while (g_running) {
        usleep(100 * 1000);
        if (!g_running) {
            break;
        }
        if (++ticks >= 10) {
            ticks = 0;
            if (g_open_connections > 0 && !g_hls_active) {
                if (++g_missed_feedback > MISSED_FEEDBACK_LIMIT) {
                    ALOG(LOG_W, "no heartbeat from client for %d s; resetting", MISSED_FEEDBACK_LIMIT);
                    g_reset_httpd = true;
                }
            } else {
                g_missed_feedback = 0;
            }
        }
        if (g_reset_httpd) {
            g_reset_httpd = false;
            g_missed_feedback = 0;
            call_void(m_on_video_stop);
            pthread_mutex_lock(&g_lock);
            if (g_raop) {
                raop_stop_httpd(g_raop);
                raop_remove_known_connections(g_raop);
                unsigned short port = raop_get_port(g_raop);
                raop_start_httpd(g_raop, &port);
                raop_set_port(g_raop, port);
                ALOG(LOG_I, "AirPlay server restarted on port %u", port);
            }
            pthread_mutex_unlock(&g_lock);
        }
    }
    return NULL;
}

static void stop_locked(void) {
    if (g_raop) {
        raop_destroy(g_raop); /* stops the http server */
        g_raop = NULL;
    }
    if (g_dnssd) {
        dnssd_unregister_raop(g_dnssd);
        dnssd_unregister_airplay(g_dnssd);
        dnssd_destroy(g_dnssd);
        g_dnssd = NULL;
    }
    g_open_connections = 0;
    g_hls_active = false;
}

JNIEXPORT jint JNICALL
Java_com_novarra_firestickairplay_AirPlayServer_nativeStart(JNIEnv *env, jobject thiz, jstring jname,
                                                            jstring jmac, jstring jkeyfile,
                                                            jint width, jint height, jint fps) {
    pthread_mutex_lock(&g_lock);
    if (g_raop) {
        pthread_mutex_unlock(&g_lock);
        return 0;
    }
    if (g_server) {
        (*env)->DeleteGlobalRef(env, g_server);
    }
    g_server = (*env)->NewGlobalRef(env, thiz);

    const char *name = (*env)->GetStringUTFChars(env, jname, NULL);
    const char *mac = (*env)->GetStringUTFChars(env, jmac, NULL);
    const char *keyfile = (*env)->GetStringUTFChars(env, jkeyfile, NULL);
    int ret = 0;

    char hw_addr[6];
    if (parse_mac(mac, hw_addr)) {
        ALOG(LOG_E, "invalid device id %s", mac);
        ret = -1;
        goto out;
    }

    int err = 0;
    g_dnssd = dnssd_init(name, (int) strlen(name), hw_addr, sizeof(hw_addr), 0, &err);
    if (err || !g_dnssd) {
        ALOG(LOG_E, "dnssd_init failed: %d", err);
        g_dnssd = NULL;
        ret = -2;
        goto out;
    }
    set_features(g_dnssd);

    raop_callbacks_t cbs;
    memset(&cbs, 0, sizeof(cbs));
    cbs.conn_init = conn_init;
    cbs.conn_destroy = conn_destroy;
    cbs.conn_reset = conn_reset;
    cbs.conn_feedback = conn_feedback;
    cbs.audio_process = audio_process;
    cbs.video_process = video_process;
    cbs.audio_flush = audio_flush;
    cbs.video_flush = video_flush;
    cbs.video_pause = video_pause;
    cbs.video_resume = video_resume;
    cbs.audio_set_client_volume = audio_set_client_volume;
    cbs.audio_set_volume = audio_set_volume;
    cbs.audio_get_format = audio_get_format;
    cbs.audio_set_metadata = audio_set_metadata;
    cbs.audio_set_coverart = audio_set_coverart;
    cbs.audio_stop_coverart_rendering = audio_stop_coverart_rendering;
    cbs.audio_set_progress = audio_set_progress;
    cbs.report_client_request = report_client_request;
    cbs.display_pin = display_pin;
    cbs.register_client = register_client;
    cbs.check_register = check_register;
    cbs.passwd = passwd;
    cbs.export_dacp = export_dacp;
    cbs.video_reset = video_reset;
    cbs.video_set_codec_and_size = video_set_codec_and_size;
    cbs.get_custom_profile = get_custom_profile;
    cbs.mirror_video_running = mirror_video_running;
    cbs.on_video_play = on_video_play;
    cbs.on_video_scrub = on_video_scrub;
    cbs.on_video_rate = on_video_rate;
    cbs.on_video_stop = on_video_stop;
    cbs.on_video_playlist_remove = on_video_playlist_remove;
    cbs.on_video_acquire_playback_info = on_video_acquire_playback_info;
    cbs.on_video_subtitles = on_video_subtitles;

    g_raop = raop_init(&cbs);
    if (!g_raop) {
        ALOG(LOG_E, "raop_init failed");
        ret = -3;
        goto out;
    }
    raop_set_log_callback(g_raop, log_callback, NULL);
    raop_set_log_level(g_raop, LOGGER_INFO);
    /* nohold = 1: a new client may take over from one that is already connected */
    if (raop_init2(g_raop, 1, mac, keyfile)) {
        ALOG(LOG_E, "raop_init2 failed");
        free(g_raop); /* as done by UxPlay itself after a failed raop_init2 */
        g_raop = NULL;
        ret = -4;
        goto out;
    }
    raop_set_plist(g_raop, "hls", 1);
    /* keep Spanish (else English) subtitle renditions in handed-over videos; whether
     * they are shown is toggled on the TV with the remote */
    raop_set_lang(g_raop, "", "es:en", "");
    if (width > 0) raop_set_plist(g_raop, "width", width);
    if (height > 0) raop_set_plist(g_raop, "height", height);
    if (fps > 0) {
        raop_set_plist(g_raop, "refreshRate", fps);
        raop_set_plist(g_raop, "maxFPS", fps);
    }

    unsigned short tcp[3] = {0, 0, 0};
    unsigned short udp[3] = {0, 0, 0};
    raop_set_tcp_ports(g_raop, tcp);
    raop_set_udp_ports(g_raop, udp);

    unsigned short port = raop_get_port(g_raop);
    if (raop_start_httpd(g_raop, &port) < 0) {
        ALOG(LOG_E, "could not start AirPlay http server");
        ret = -5;
        goto out;
    }
    raop_set_port(g_raop, port);
    raop_set_dnssd(g_raop, g_dnssd);

    if (dnssd_register_raop(g_dnssd, port) || dnssd_register_airplay(g_dnssd, port)) {
        ALOG(LOG_E, "mDNS service registration failed");
        ret = -6;
        goto out;
    }
    ALOG(LOG_I, "AirPlay server \"%s\" running on port %u", name, port);

    g_running = true;
    g_reset_httpd = false;
    g_missed_feedback = 0;
    if (pthread_create(&g_supervisor, NULL, supervisor_thread, NULL)) {
        g_running = false;
    }

out:
    if (ret != 0) {
        stop_locked();
    }
    (*env)->ReleaseStringUTFChars(env, jname, name);
    (*env)->ReleaseStringUTFChars(env, jmac, mac);
    (*env)->ReleaseStringUTFChars(env, jkeyfile, keyfile);
    pthread_mutex_unlock(&g_lock);
    return ret;
}

JNIEXPORT void JNICALL
Java_com_novarra_firestickairplay_AirPlayServer_nativeStop(JNIEnv *env, jobject thiz) {
    bool was_running = g_running;
    g_running = false;
    if (was_running) {
        pthread_join(g_supervisor, NULL);
    }
    pthread_mutex_lock(&g_lock);
    stop_locked();
    pthread_mutex_unlock(&g_lock);
}

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved) {
    JNIEnv *env;
    g_vm = vm;
    if ((*vm)->GetEnv(vm, (void **) &env, JNI_VERSION_1_6) != JNI_OK) {
        return JNI_ERR;
    }
    jclass cls = (*env)->FindClass(env, "com/novarra/firestickairplay/AirPlayServer");
    if (!cls) {
        return JNI_ERR;
    }
    m_on_video_frame = (*env)->GetMethodID(env, cls, "onVideoFrame", "([BJ)V");
    m_on_video_size = (*env)->GetMethodID(env, cls, "onVideoSize", "(IIII)V");
    m_on_video_stop = (*env)->GetMethodID(env, cls, "onVideoStop", "()V");
    m_on_audio_format = (*env)->GetMethodID(env, cls, "onAudioFormat", "(I)V");
    m_on_audio_frame = (*env)->GetMethodID(env, cls, "onAudioFrame", "([BI)V");
    m_on_volume = (*env)->GetMethodID(env, cls, "onVolume", "(F)V");
    m_on_client = (*env)->GetMethodID(env, cls, "onClientConnecting", "(Ljava/lang/String;Ljava/lang/String;)V");
    m_on_connections = (*env)->GetMethodID(env, cls, "onConnectionCount", "(I)V");
    m_on_log = (*env)->GetMethodID(env, cls, "onNativeLog", "(ILjava/lang/String;)V");
    m_hls_play = (*env)->GetMethodID(env, cls, "onHlsPlay", "(Ljava/lang/String;F)V");
    m_hls_seek = (*env)->GetMethodID(env, cls, "onHlsSeek", "(F)V");
    m_hls_rate = (*env)->GetMethodID(env, cls, "onHlsRate", "(F)V");
    m_hls_stop = (*env)->GetMethodID(env, cls, "onHlsStop", "()V");
    m_hls_pause_get_pos = (*env)->GetMethodID(env, cls, "onHlsPauseAndGetPosition", "()F");
    m_hls_info = (*env)->GetMethodID(env, cls, "onHlsPlaybackInfo", "()[D");
    m_hls_subtitles = (*env)->GetMethodID(env, cls, "onHlsSubtitles", "(ZLjava/lang/String;)V");
    if (!m_hls_subtitles || !m_hls_play || !m_hls_seek || !m_hls_rate || !m_hls_stop || !m_hls_pause_get_pos || !m_hls_info) {
        return JNI_ERR;
    }
    if (!m_on_video_frame || !m_on_video_size || !m_on_video_stop || !m_on_audio_format ||
        !m_on_audio_frame || !m_on_volume || !m_on_client || !m_on_connections || !m_on_log) {
        return JNI_ERR;
    }
    (*env)->DeleteLocalRef(env, cls);
    return JNI_VERSION_1_6;
}
