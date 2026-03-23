/**
 *  Copyright (C) 2011-2012  Juho Vähä-Herttua
 *
 *  This library is free software; you can redistribute it and/or
 *  modify it under the terms of the GNU Lesser General Public
 *  License as published by the Free Software Foundation; either
 *  version 2.1 of the License, or (at your option) any later version.
 *
 *  This library is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 *  Lesser General Public License for more details.
 */

#include <stdlib.h>
#include <stdio.h>
#include <string.h>
#include <assert.h>

#include "raop.h"
#include "raop_rtp.h"
#include "raop_rtp.h"
#include "pairing.h"
#include "httpd.h"

#include "global.h"
#include "fairplay.h"
#include "netutils.h"
#include "logger.h"
#include "compat.h"
#include "raop_rtp_mirror.h"
#include <android/log.h>

struct raop_s {
	/* Callbacks for audio */
	raop_callbacks_t callbacks;

	/* Logger instance */
	logger_t *logger;

	/* Pairing, HTTP daemon and RSA key */
	pairing_t *pairing;
	httpd_t *httpd;

    unsigned short port;
};

struct raop_conn_s {
	raop_t *raop;
	raop_rtp_t *raop_rtp;
	raop_rtp_mirror_t *raop_rtp_mirror;
	fairplay_t *fairplay;
	pairing_session_t *pairing;
	struct sockaddr_storage local_saddr;
	socklen_t local_saddrlen;
	struct sockaddr_storage remote_saddr;
	socklen_t remote_saddrlen;

	int id;
	int setup_step;
	int feedback_count;
	int request_count;
	int saw_teardown;
	int reverse_channel;
	char apple_session_id[128];
	char role[32];
	char last_method[32];
	char last_url[128];
};
typedef struct raop_conn_s raop_conn_t;

static int g_next_conn_id = 1;

static int is_direct_play_url(const char *url);
static int url_path_matches(const char *url, const char *path);

static void
conn_copy_string(char *dst, size_t dstlen, const char *src)
{
	if (!dst || dstlen == 0) {
		return;
	}
	if (!src) {
		dst[0] = '\0';
		return;
	}
	snprintf(dst, dstlen, "%s", src);
}

static void
conn_set_role(raop_conn_t *conn, const char *role)
{
	if (!conn || !role || !role[0]) {
		return;
	}
	if (strcmp(conn->role, role)) {
		conn_copy_string(conn->role, sizeof(conn->role), role);
	}
}

static void
conn_note_session_id(raop_conn_t *conn, const char *session_id)
{
	if (!conn || !session_id || !session_id[0]) {
		return;
	}
	if (!conn->apple_session_id[0]) {
		conn_copy_string(conn->apple_session_id, sizeof(conn->apple_session_id), session_id);
		return;
	}
	if (strcmp(conn->apple_session_id, session_id)) {
		logger_log(conn->raop->logger, LOGGER_WARNING,
		           "[RTSP#%d] X-Apple-Session-ID changed %s -> %s",
		           conn->id,
		           conn->apple_session_id,
		           session_id);
		conn_copy_string(conn->apple_session_id, sizeof(conn->apple_session_id), session_id);
	}
}

static void
conn_note_request(raop_conn_t *conn, const char *method, const char *url, int is_http_airplay)
{
	if (!conn || !method || !url) {
		return;
	}
	conn->request_count++;
	conn_copy_string(conn->last_method, sizeof(conn->last_method), method);
	conn_copy_string(conn->last_url, sizeof(conn->last_url), url);

	if (!strcmp(url, "/reverse")) {
		conn_set_role(conn, "reverse");
	} else if (!strcmp(url, "/feedback")) {
		conn_set_role(conn, "feedback");
	} else if (is_direct_play_url(url)) {
		conn_set_role(conn, "direct-play");
	} else if (!strcmp(method, "SETUP") || !strcmp(method, "RECORD") ||
	           !strcmp(method, "GET_PARAMETER") || !strcmp(method, "SET_PARAMETER") ||
	           !strcmp(method, "FLUSH") || !strcmp(method, "TEARDOWN")) {
		conn_set_role(conn, "rtsp-control");
	} else if (is_http_airplay) {
		conn_set_role(conn, "http-airplay");
	} else if (!conn->role[0]) {
		conn_set_role(conn, "rtsp");
	}
}

#include "raop_handlers.h"

static int
is_direct_play_url(const char *url)
{
	return url &&
	       (url_path_matches(url, "/play") ||
	        url_path_matches(url, "/scrub") ||
	        url_path_matches(url, "/rate") ||
	        url_path_matches(url, "/stop") ||
	        url_path_matches(url, "/playback-info") ||
	        url_path_matches(url, "/setProperty") ||
	        url_path_matches(url, "/getProperty") ||
	        url_path_matches(url, "/action"));
}

static int
url_path_matches(const char *url, const char *path)
{
	size_t path_len;

	if (!url || !path) {
		return 0;
	}
	path_len = strlen(path);
	if (strncmp(url, path, path_len) != 0) {
		return 0;
	}
	return url[path_len] == '\0' || url[path_len] == '?';
}

static void
conn_parse_teardown_targets(const char *data, int datalen,
                            int *stop_audio, int *stop_mirror, int *parsed)
{
	plist_t root_node = NULL;
	plist_t streams_node = NULL;
	uint32_t stream_count = 0;
	uint32_t i;

	if (stop_audio) {
		*stop_audio = 1;
	}
	if (stop_mirror) {
		*stop_mirror = 1;
	}
	if (parsed) {
		*parsed = 0;
	}
	if (!data || datalen <= 0) {
		return;
	}

	plist_from_bin(data, datalen, &root_node);
	if (!root_node) {
		return;
	}

	streams_node = plist_dict_get_item(root_node, "streams");
	if (!streams_node) {
		plist_free(root_node);
		return;
	}

	stream_count = plist_array_get_size(streams_node);
	if (stream_count == 0) {
		plist_free(root_node);
		return;
	}

	if (stop_audio) {
		*stop_audio = 0;
	}
	if (stop_mirror) {
		*stop_mirror = 0;
	}
	if (parsed) {
		*parsed = 1;
	}

	for (i = 0; i < stream_count; i++) {
		plist_t stream_node = plist_array_get_item(streams_node, i);
		uint64_t stream_type = 0;

		if (!raop_get_stream_type(stream_node, &stream_type)) {
			continue;
		}
		if (stream_type == 96 && stop_audio) {
			*stop_audio = 1;
		} else if (stream_type == 110 && stop_mirror) {
			*stop_mirror = 1;
		}
	}

	plist_free(root_node);
}

static void
conn_log_teardown_targets(raop_conn_t *conn, int parsed, int stop_audio, int stop_mirror)
{
	const char *scope = "all";

	if (parsed) {
		if (stop_audio && stop_mirror) {
			scope = "audio+mirror";
		} else if (stop_audio) {
			scope = "audio-only";
		} else if (stop_mirror) {
			scope = "mirror-only";
		} else {
			scope = "none";
		}
	}

	logger_log(conn->raop->logger, LOGGER_INFO,
	           "[RTSP#%d] TEARDOWN targets=%s parsed=%s audio=%s mirror=%s",
	           conn->id,
	           scope,
	           parsed ? "yes" : "no",
	           stop_audio ? "stop" : "keep",
	           stop_mirror ? "stop" : "keep");
}

static void *
conn_init(void *opaque,
          const struct sockaddr_storage *local_saddr,
          socklen_t local_saddrlen,
          const struct sockaddr_storage *remote_saddr,
          socklen_t remote_saddrlen)
{
	raop_t *raop = opaque;
	raop_conn_t *conn;
	unsigned char *local;
	int locallen;
	unsigned char *remote;
	int remotelen;

	assert(raop);
	assert(local_saddr);
	assert(remote_saddr);

	conn = calloc(1, sizeof(raop_conn_t));
	if (!conn) {
		return NULL;
	}
	conn->id = g_next_conn_id++;
	conn->raop = raop;
	conn->raop_rtp = NULL;
	conn->fairplay = fairplay_init(raop->logger);
	//fairplay_init2();
	if (!conn->fairplay) {
		free(conn);
		return NULL;
	}
	conn->pairing = pairing_session_init(raop->pairing);
	if (!conn->pairing) {
		fairplay_destroy(conn->fairplay);
		free(conn);
		return NULL;
	}

	memcpy(&conn->local_saddr, local_saddr, sizeof(conn->local_saddr));
	memcpy(&conn->remote_saddr, remote_saddr, sizeof(conn->remote_saddr));
	conn->local_saddrlen = local_saddrlen;
	conn->remote_saddrlen = remote_saddrlen;

	local = netutils_get_address((void *)local_saddr, &locallen);
	remote = netutils_get_address((void *)remote_saddr, &remotelen);

	if (locallen == 4) {
		logger_log(conn->raop->logger, LOGGER_INFO,
		           "[RTSP#%d] Local %d.%d.%d.%d",
		           conn->id,
		           local[0], local[1], local[2], local[3]);
	} else if (locallen == 16) {
		logger_log(conn->raop->logger, LOGGER_INFO,
		           "[RTSP#%d] Local %02x%02x:%02x%02x:%02x%02x:%02x%02x:%02x%02x:%02x%02x:%02x%02x:%02x%02x",
		           conn->id,
		           local[0], local[1], local[2], local[3], local[4], local[5], local[6], local[7],
		           local[8], local[9], local[10], local[11], local[12], local[13], local[14], local[15]);
	}
	if (remotelen == 4) {
		logger_log(conn->raop->logger, LOGGER_INFO,
		           "[RTSP#%d] Remote %d.%d.%d.%d",
		           conn->id,
		           remote[0], remote[1], remote[2], remote[3]);
	} else if (remotelen == 16) {
		    logger_log(conn->raop->logger, LOGGER_INFO,
		               "[RTSP#%d] Remote %02x%02x:%02x%02x:%02x%02x:%02x%02x:%02x%02x:%02x%02x:%02x%02x:%02x%02x",
		               conn->id,
		               remote[0], remote[1], remote[2], remote[3], remote[4], remote[5], remote[6], remote[7],
		               remote[8], remote[9], remote[10], remote[11], remote[12], remote[13], remote[14], remote[15]);
	}
	logger_log(conn->raop->logger, LOGGER_INFO, "[RTSP#%d] Session opened", conn->id);

	return conn;
}

static void
conn_request(void *ptr, http_request_t *request, http_response_t **response)
{
	raop_conn_t *conn = ptr;
	const char *method;
	const char *url;
	const char *cseq;
	const char *content_type;
	const char *user_agent;
	const char *session_id;
	const char *content_location;
	const char *upgrade_header;
	const char *connection_header;
	const char *purpose_header;
	int datalen = 0;
	int code = 200;
	const char *message = "OK";
	int handled = 0;
	int is_http_airplay = 0;
	int is_feedback = 0;
	const char *protocol = "RTSP/1.0";

	char *response_data = NULL;
	int response_datalen = 0;

	method = http_request_get_method(request);
	url = http_request_get_url(request);
	cseq = http_request_get_header(request, "CSeq");
	content_type = http_request_get_header(request, "Content-Type");
	user_agent = http_request_get_header(request, "User-Agent");
	session_id = http_request_get_header(request, "X-Apple-Session-ID");
	content_location = http_request_get_header(request, "Content-Location");
	upgrade_header = http_request_get_header(request, "Upgrade");
	connection_header = http_request_get_header(request, "Connection");
	purpose_header = http_request_get_header(request, "X-Apple-Purpose");
	http_request_get_data(request, &datalen);
	if (!method || !url) {
		logger_log(conn->raop->logger, LOGGER_WARNING,
		           "[RTSP#%d] Request dispatcher got incomplete request method=%s url=%s",
		           conn->id,
		           method ? method : "-",
		           url ? url : "-");
		return;
	}
	is_http_airplay = (cseq == NULL);
	is_feedback = (!strcmp(method, "POST") && !strcmp(url, "/feedback"));
	if (is_http_airplay) {
		protocol = "HTTP/1.1";
	}
	conn_note_session_id(conn, session_id);
	conn_note_request(conn, method, url, is_http_airplay);

	if (is_feedback) {
		conn->feedback_count++;
		if (conn->feedback_count == 1 || conn->feedback_count % 300 == 0) {
			logger_log(conn->raop->logger, LOGGER_INFO,
			           "[RTSP#%d] /feedback keepalive x%d",
			           conn->id,
			           conn->feedback_count);
		}
	} else {
		logger_log(conn->raop->logger, LOGGER_INFO,
		           "[RTSP#%d] %s %s cseq=%s ct=%s bytes=%d session=%s ua=%s",
		           conn->id,
		           method,
		           url ? url : "(null)",
		           cseq ? cseq : "-",
		           content_type ? content_type : "-",
		           datalen,
		           session_id ? session_id : "-",
		           user_agent ? user_agent : "-");
	}
	if (content_location) {
		logger_log(conn->raop->logger, LOGGER_INFO,
		           "[RTSP#%d] Content-Location: %s",
		           conn->id,
		           content_location);
	}
	if (is_http_airplay && (upgrade_header || purpose_header)) {
		logger_log(conn->raop->logger, LOGGER_INFO,
		           "[RTSP#%d] HTTP upgrade=%s connection=%s purpose=%s",
		           conn->id,
		           upgrade_header ? upgrade_header : "-",
		           connection_header ? connection_header : "-",
		           purpose_header ? purpose_header : "-");
	}

	raop_handler_t handler = NULL;
	if (!strcmp(method, "GET") && !strcmp(url, "/info")) {
		handler = &raop_handler_info;
	} else if (!strcmp(method, "GET") && !strcmp(url, "/server-info")) {
		handler = &raop_handler_server_info;
	} else if (!strcmp(method, "POST") && !strcmp(url, "/reverse")) {
		code = 101;
		message = "Switching Protocols";
		conn->reverse_channel = 1;
		logger_log(conn->raop->logger, LOGGER_INFO,
		           "[RTSP#%d] Reverse event channel established",
		           conn->id);
		handled = 1;
	} else if (!strcmp(method, "POST") && !strcmp(url, "/pair-setup")) {
		handler = &raop_handler_pairsetup;
	} else if (!strcmp(method, "POST") && !strcmp(url, "/pair-verify")) {
		handler = &raop_handler_pairverify;
	} else if (!strcmp(method, "POST") && !strcmp(url, "/fp-setup")) {
		handler = &raop_handler_fpsetup;
	} else if (!strcmp(method, "OPTIONS")) {
		handler = &raop_handler_options;
	} else if (!strcmp(method, "SETUP")) {
		handler = &raop_handler_setup;
	} else if (!strcmp(method, "GET_PARAMETER")) {
		handler = &raop_handler_get_parameter;
	} else if (!strcmp(method, "SET_PARAMETER")) {
		handler = &raop_handler_set_parameter;
	} else if (!strcmp(method, "POST") && !strcmp(url, "/feedback")) {
		handler = &raop_handler_feedback;
	} else if (!strcmp(method, "POST") && !strcmp(url, "/audioMode")) {
		handler = &raop_handler_audio_mode;
	} else if (!strcmp(method, "RECORD")) {
        handler = &raop_handler_record;
	} else if (is_direct_play_url(url)) {
		code = 501;
		message = "Not Implemented";
		logger_log(conn->raop->logger, LOGGER_WARNING,
		           "[RTSP#%d] Direct AirPlay playback endpoint %s is not implemented; receiver currently supports mirroring/RAOP only",
		           conn->id, url);
		handled = 1;
	} else if (!strcmp(method, "FLUSH")) {
		const char *rtpinfo;
		int next_seq = -1;

		rtpinfo = http_request_get_header(request, "RTP-Info");
		if (rtpinfo) {
			logger_log(conn->raop->logger, LOGGER_INFO, "Flush with RTP-Info: %s", rtpinfo);
			if (!strncmp(rtpinfo, "seq=", 4)) {
				next_seq = strtol(rtpinfo+4, NULL, 10);
			}
		}
		if (conn->raop_rtp) {
			raop_rtp_flush(conn->raop_rtp, next_seq);
		} else {
			logger_log(conn->raop->logger, LOGGER_WARNING, "RAOP not initialized at FLUSH");
		}
		handled = 1;
	} else if (!strcmp(method, "TEARDOWN")) {
		const char *data;
		int datalen;
		int stop_audio = 1;
		int stop_mirror = 1;
		int parsed_targets = 0;

		data = http_request_get_data(request, &datalen);
		conn_parse_teardown_targets(data, datalen, &stop_audio, &stop_mirror, &parsed_targets);
		conn->saw_teardown = 1;
		logger_log(conn->raop->logger, LOGGER_INFO, "[RTSP#%d] TEARDOWN received", conn->id);
		conn_log_teardown_targets(conn, parsed_targets, stop_audio, stop_mirror);
		if (stop_audio && conn->raop_rtp) {
			/* Stop active audio transport but keep session keys/state for reuse on the same RTSP connection */
			raop_rtp_stop(conn->raop_rtp);
			logger_log(conn->raop->logger, LOGGER_INFO, "[RTSP#%d] Audio RTP stopped", conn->id);
		}
		if (stop_mirror && conn->raop_rtp_mirror) {
			/* Stop active mirror transport but keep session keys/state for reuse on the same RTSP connection */
			raop_rtp_mirror_stop(conn->raop_rtp_mirror);
			logger_log(conn->raop->logger, LOGGER_INFO, "[RTSP#%d] Mirror RTP stopped", conn->id);
		}
		handled = 1;
	} else {
		logger_log(conn->raop->logger, LOGGER_WARNING,
		           "[RTSP#%d] Unhandled request %s %s; replying 200 for compatibility",
		           conn->id, method, url ? url : "(null)");
		handled = 1;
	}

	*response = http_response_init(protocol, code, message);
	if (cseq) {
		http_response_add_header(*response, "CSeq", cseq);
	}
	http_response_add_header(*response, "Server", "AirTunes/220.68");
	if (code == 101) {
		http_response_add_header(*response, "Upgrade", "PTTH/1.0");
		http_response_add_header(*response, "Connection", "Upgrade");
		http_response_add_header(*response, "Content-Length", "0");
	}
	if (!strcmp(method, "TEARDOWN")) {
		http_response_add_header(*response, "Connection", "close");
	}
	if (handler != NULL) {
		handler(conn, request, *response, &response_data, &response_datalen);
		handled = 1;
	}
	if (!handled) {
		logger_log(conn->raop->logger, LOGGER_WARNING,
		           "[RTSP#%d] Request reached fallback path for %s %s",
		           conn->id, method, url ? url : "(null)");
	}
	http_response_finish(*response, response_data, response_datalen);
	if (response_data) {
		free(response_data);
		response_data = NULL;
		response_datalen = 0;
	}
}

static void
conn_destroy(void *ptr)
{
	raop_conn_t *conn = ptr;
	if ((conn->raop_rtp || conn->raop_rtp_mirror) && !conn->saw_teardown) {
		logger_log(conn->raop->logger, LOGGER_WARNING,
		           "[RTSP#%d] TCP closed without TEARDOWN; stopping active streams audio=%s mirror=%s role=%s apple-session=%s last=%s %s",
		           conn->id,
		           conn->raop_rtp ? "yes" : "no",
		           conn->raop_rtp_mirror ? "yes" : "no",
		           conn->role[0] ? conn->role : "-",
		           conn->apple_session_id[0] ? conn->apple_session_id : "-",
		           conn->last_method[0] ? conn->last_method : "-",
		           conn->last_url[0] ? conn->last_url : "-");
	}
	logger_log(conn->raop->logger, LOGGER_INFO,
	           "[RTSP#%d] Session closed role=%s requests=%d feedback=%d reverse=%s teardown=%s apple-session=%s last=%s %s",
	           conn->id,
	           conn->role[0] ? conn->role : "-",
	           conn->request_count,
	           conn->feedback_count,
	           conn->reverse_channel ? "yes" : "no",
	           conn->saw_teardown ? "yes" : "no",
	           conn->apple_session_id[0] ? conn->apple_session_id : "-",
	           conn->last_method[0] ? conn->last_method : "-",
	           conn->last_url[0] ? conn->last_url : "-");

	if (conn->raop_rtp) {
		/* This is done in case TEARDOWN was not called */
		raop_rtp_destroy(conn->raop_rtp);
	}
    if (conn->raop_rtp_mirror) {
        /* This is done in case TEARDOWN was not called */
        raop_rtp_mirror_destroy(conn->raop_rtp_mirror);
    }
	pairing_session_destroy(conn->pairing);
	fairplay_destroy(conn->fairplay);
	free(conn);
}

raop_t *
raop_init(int max_clients, raop_callbacks_t *callbacks)
{
	raop_t *raop;
	pairing_t *pairing;
	httpd_t *httpd;
	httpd_callbacks_t httpd_cbs;

	assert(callbacks);
	assert(max_clients > 0);
	assert(max_clients < 100);

	/* Initialize the network */
	if (netutils_init() < 0) {
		return NULL;
	}

	/* Validate the callbacks structure */
	if (!callbacks->audio_process) {
		return NULL;
	}

	/* Allocate the raop_t structure */
	raop = calloc(1, sizeof(raop_t));
	if (!raop) {
		return NULL;
	}

	/* Initialize the logger */
	raop->logger = logger_init();
	pairing = pairing_init_generate();
	if (!pairing) {
		free(raop);
		return NULL;
	}

	/* Set HTTP callbacks to our handlers */
	memset(&httpd_cbs, 0, sizeof(httpd_cbs));
	httpd_cbs.opaque = raop;
	httpd_cbs.conn_init = &conn_init;
	httpd_cbs.conn_request = &conn_request;
	httpd_cbs.conn_destroy = &conn_destroy;

	/* Initialize the http daemon */
	httpd = httpd_init(raop->logger, &httpd_cbs, max_clients);
	if (!httpd) {
		pairing_destroy(pairing);
		free(raop);
		return NULL;
	}
	/* Copy callbacks structure */
	memcpy(&raop->callbacks, callbacks, sizeof(raop_callbacks_t));
	raop->pairing = pairing;
	raop->httpd = httpd;
	return raop;
}

void
raop_destroy(raop_t *raop)
{
	if (raop) {
		raop_stop(raop);

		pairing_destroy(raop->pairing);
		httpd_destroy(raop->httpd);
		logger_destroy(raop->logger);
		free(raop);

		/* Cleanup the network */
		netutils_cleanup();
	}
}

int
raop_is_running(raop_t *raop)
{
	assert(raop);

	return httpd_is_running(raop->httpd);
}

void
raop_set_log_level(raop_t *raop, int level)
{
	assert(raop);

	logger_set_level(raop->logger, level);
}

void
raop_set_port(raop_t *raop, unsigned short port)
{
    assert(raop);
    raop->port = port;
}

unsigned short
raop_get_port(raop_t *raop)
{
    assert(raop);
    return raop->port;
}

void *
raop_get_callback_cls(raop_t *raop)
{
    assert(raop);
    return raop->callbacks.cls;
}

void
raop_set_log_callback(raop_t *raop, raop_log_callback_t callback, void *cls)
{
	assert(raop);

	logger_set_callback(raop->logger, callback, cls);
}

int
raop_start(raop_t *raop, unsigned short *port)
{
	assert(raop);
	assert(port);
	return httpd_start(raop->httpd, port);
}

void
raop_stop(raop_t *raop)
{
	assert(raop);
	httpd_stop(raop->httpd);
}

void
raop_get_public_key(raop_t *raop, unsigned char *pk)
{
	assert(raop);
	assert(pk);
	pairing_get_public_key(raop->pairing, pk);
}
