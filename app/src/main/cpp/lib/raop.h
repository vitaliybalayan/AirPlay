#ifndef RAOP_H
#define RAOP_H

#include "stream.h"
#if defined (WIN32) && defined(DLL_EXPORT)
# define RAOP_API __declspec(dllexport)
#else
# define RAOP_API
#endif

#ifdef __cplusplus
extern "C" {
#endif


/* Define syslog style log levels */
#define RAOP_LOG_EMERG       0       /* system is unusable */
#define RAOP_LOG_ALERT       1       /* action must be taken immediately */
#define RAOP_LOG_CRIT        2       /* critical conditions */
#define RAOP_LOG_ERR         3       /* error conditions */
#define RAOP_LOG_WARNING     4       /* warning conditions */
#define RAOP_LOG_NOTICE      5       /* normal but significant condition */
#define RAOP_LOG_INFO        6       /* informational */
#define RAOP_LOG_DEBUG       7       /* debug-level messages */


typedef struct raop_s raop_t;

typedef void (*raop_log_callback_t)(void *cls, int level, const char *msg);

typedef struct raop_playback_info_s {
	double duration;
	double position;
	double rate;
	double buffered;
	int ready;
} raop_playback_info_t;

struct raop_callbacks_s {
	void* cls;

	void  (*audio_process)(void *cls, pcm_data_struct *data);
    void  (*video_process)(void *cls, h264_decode_struct *data);
    void  (*video_stop)(void *cls, const char *reason);
    /* start_seconds / start_fraction are negative when not provided by the sender */
    void  (*direct_play)(void *cls, const char *url, double start_seconds, double start_fraction);
    void  (*direct_stop)(void *cls, const char *reason);
    void  (*direct_scrub)(void *cls, double position);
    void  (*direct_rate)(void *cls, double rate);
    /* Returns non-zero while a direct playback session is active */
    int   (*direct_get_playback_info)(void *cls, raop_playback_info_t *info);

	/* Optional but recommended callback functions */
	void  (*audio_flush)(void *cls, void *session);
	void  (*audio_set_volume)(void *cls, void *session, float volume);
	void  (*audio_set_metadata)(void *cls, void *session, const void *buffer, int buflen);
	void  (*audio_set_coverart)(void *cls, void *session, const void *buffer, int buflen);
	void  (*audio_remote_control_id)(void *cls, const char *dacp_id, const char *active_remote_header);
	void  (*audio_set_progress)(void *cls, void *session, unsigned int start, unsigned int curr, unsigned int end);
	/* An audio stream started (active=1) or stopped; compression_type is the SETUP "ct" (2=ALAC, 8=AAC-ELD) */
	void  (*audio_session)(void *cls, int active, int compression_type);
};
typedef struct raop_callbacks_s raop_callbacks_t;

RAOP_API raop_t *raop_init(int max_clients, raop_callbacks_t *callbacks);

RAOP_API void raop_set_log_level(raop_t *raop, int level);
RAOP_API void raop_set_log_callback(raop_t *raop, raop_log_callback_t callback, void *cls);
RAOP_API void raop_set_port(raop_t *raop, unsigned short port);
RAOP_API void raop_set_device_info(raop_t *raop, const char *name, const char *device_id);
/* Receiver volume in AirPlay dB, reported to senders on GET_PARAMETER volume */
RAOP_API void raop_set_volume(raop_t *raop, float volume_db);
RAOP_API unsigned short raop_get_port(raop_t *raop);
RAOP_API void *raop_get_callback_cls(raop_t *raop);
RAOP_API int raop_start(raop_t *raop, unsigned short *port);
RAOP_API int raop_is_running(raop_t *raop);
RAOP_API void raop_stop(raop_t *raop);
RAOP_API void raop_get_public_key(raop_t *raop, unsigned char *pk);

RAOP_API void raop_destroy(raop_t *raop);

#ifdef __cplusplus
}
#endif
#endif
