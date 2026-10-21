/**
 *  Copyright (C) 2018  Juho Vähä-Herttua
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

#include "plist/plist/plist.h"
#include <ctype.h>
#include <stdlib.h>
/* This file should be only included from raop.c as it defines static handler
 * functions and depends on raop internals */

static int raop_get_plist_number(plist_t root_node, const char *key, double *out);

typedef void (*raop_handler_t)(raop_conn_t *, http_request_t *,
                               http_response_t *, char **, int *);

static plist_t
raop_get_first_stream_node(plist_t root_node)
{
    plist_t streams_node;

    if (!root_node) {
        return NULL;
    }
    streams_node = plist_dict_get_item(root_node, "streams");
    if (!streams_node) {
        return NULL;
    }
    return plist_array_get_item(streams_node, 0);
}

static int
raop_get_stream_type(plist_t stream_node, uint64_t *type)
{
    plist_t type_node;

    if (!stream_node || !type) {
        return 0;
    }
    type_node = plist_dict_get_item(stream_node, "type");
    if (!type_node) {
        return 0;
    }
    plist_get_uint_val(type_node, type);
    return 1;
}

static const char *
raop_stream_type_name(uint64_t type)
{
    if (type == 110) {
        return "mirror-video";
    }
    if (type == 96) {
        return "audio";
    }
    return "unknown";
}

static void
raop_write_plist_response(http_response_t *response,
                          plist_t root_node,
                          char **response_data,
                          int *response_datalen)
{
    char *rsp = NULL;
    uint32_t len = 0;

    if (!root_node || !response_data || !response_datalen) {
        return;
    }

    plist_to_bin(root_node, &rsp, &len);
    if (!rsp || len == 0) {
        return;
    }

    http_response_add_header(response, "Content-Type", "application/x-apple-binary-plist");
    *response_data = malloc(len);
    if (!*response_data) {
        free(rsp);
        return;
    }
    memcpy(*response_data, rsp, len);
    *response_datalen = (int) len;
    free(rsp);
}

static void
raop_handler_server_info(raop_conn_t *conn,
                         http_request_t *request, http_response_t *response,
                         char **response_data, int *response_datalen)
{
    plist_t root_node;

    (void)conn;
    (void)request;

    root_node = plist_new_dict();
    plist_dict_set_item(root_node, "deviceid", plist_new_string(conn->raop->device_id));
    plist_dict_set_item(root_node, "features", plist_new_uint(GLOBAL_FEATURES));
    plist_dict_set_item(root_node, "macAddress", plist_new_string(conn->raop->device_id));
    plist_dict_set_item(root_node, "model", plist_new_string(GLOBAL_MODEL));
    plist_dict_set_item(root_node, "name", plist_new_string(conn->raop->name));
    plist_dict_set_item(root_node, "protovers", plist_new_string("1.0"));
    plist_dict_set_item(root_node, "srcvers", plist_new_string(GLOBAL_SOURCE_VERSION));
    plist_dict_set_item(root_node, "sourceVersion", plist_new_string(GLOBAL_SOURCE_VERSION));
    plist_dict_set_item(root_node, "statusFlags", plist_new_uint(4));

    raop_write_plist_response(response, root_node, response_data, response_datalen);
    plist_free(root_node);
}

static plist_t
raop_new_audio_format(uint64_t type)
{
    plist_t node = plist_new_dict();
    plist_dict_set_item(node, "type", plist_new_uint(type));
    plist_dict_set_item(node, "audioInputFormats", plist_new_uint(0x1FFFFFC));
    plist_dict_set_item(node, "audioOutputFormats", plist_new_uint(0x1FFFFFC));
    return node;
}

static plist_t
raop_new_audio_latency(uint64_t type)
{
    plist_t node = plist_new_dict();
    plist_dict_set_item(node, "type", plist_new_uint(type));
    plist_dict_set_item(node, "audioType", plist_new_string("default"));
    plist_dict_set_item(node, "inputLatencyMicros", plist_new_bool(0));
    plist_dict_set_item(node, "outputLatencyMicros", plist_new_bool(0));
    return node;
}

static void
raop_handler_info(raop_conn_t *conn,
                  http_request_t *request, http_response_t *response,
                  char **response_data, int *response_datalen)
{
    unsigned char pk[32];
    plist_t root_node;
    plist_t audio_formats;
    plist_t audio_latencies;
    plist_t displays;
    plist_t display;

    (void)request;

    pairing_get_public_key(conn->raop->pairing, pk);

    audio_formats = plist_new_array();
    plist_array_append_item(audio_formats, raop_new_audio_format(100));
    plist_array_append_item(audio_formats, raop_new_audio_format(101));

    audio_latencies = plist_new_array();
    plist_array_append_item(audio_latencies, raop_new_audio_latency(100));
    plist_array_append_item(audio_latencies, raop_new_audio_latency(101));

    /* The sender encodes the mirror stream for this display: 1080p at 60 fps. */
    display = plist_new_dict();
    plist_dict_set_item(display, "uuid", plist_new_string("e0ff8a27-6738-3d56-8a16-cc53aacee925"));
    plist_dict_set_item(display, "width", plist_new_uint(1920));
    plist_dict_set_item(display, "height", plist_new_uint(1080));
    plist_dict_set_item(display, "widthPixels", plist_new_uint(1920));
    plist_dict_set_item(display, "heightPixels", plist_new_uint(1080));
    plist_dict_set_item(display, "widthPhysical", plist_new_bool(0));
    plist_dict_set_item(display, "heightPhysical", plist_new_bool(0));
    plist_dict_set_item(display, "refreshRate", plist_new_uint(60));
    plist_dict_set_item(display, "maxFPS", plist_new_uint(60));
    plist_dict_set_item(display, "features", plist_new_uint(14));
    plist_dict_set_item(display, "overscanned", plist_new_bool(0));
    plist_dict_set_item(display, "rotation", plist_new_bool(0));
    displays = plist_new_array();
    plist_array_append_item(displays, display);

    root_node = plist_new_dict();
    plist_dict_set_item(root_node, "audioFormats", audio_formats);
    plist_dict_set_item(root_node, "audioLatencies", audio_latencies);
    plist_dict_set_item(root_node, "deviceID", plist_new_string(conn->raop->device_id));
    plist_dict_set_item(root_node, "displays", displays);
    plist_dict_set_item(root_node, "features", plist_new_uint((GLOBAL_FEATURES_EXT << 32) | GLOBAL_FEATURES));
    plist_dict_set_item(root_node, "keepAliveLowPower", plist_new_uint(1));
    plist_dict_set_item(root_node, "keepAliveSendStatsAsBody", plist_new_uint(1));
    plist_dict_set_item(root_node, "macAddress", plist_new_string(conn->raop->device_id));
    plist_dict_set_item(root_node, "model", plist_new_string(GLOBAL_MODEL));
    plist_dict_set_item(root_node, "name", plist_new_string(conn->raop->name));
    plist_dict_set_item(root_node, "pi", plist_new_string("2e388006-13ba-4041-9a67-25dd4a43d536"));
    plist_dict_set_item(root_node, "pk", plist_new_data((const char *) pk, sizeof(pk)));
    plist_dict_set_item(root_node, "sourceVersion", plist_new_string(GLOBAL_SOURCE_VERSION));
    plist_dict_set_item(root_node, "statusFlags", plist_new_uint(4));
    plist_dict_set_item(root_node, "vv", plist_new_uint(2));

    raop_write_plist_response(response, root_node, response_data, response_datalen);
    plist_free(root_node);
}

static void
raop_handler_pairsetup(raop_conn_t *conn,
                       http_request_t *request, http_response_t *response,
                       char **response_data, int *response_datalen)
{
	unsigned char public_key[32];
	const char *data;
	int datalen;

	data = http_request_get_data(request, &datalen);
	if (datalen != 32) {
		logger_log(conn->raop->logger, LOGGER_ERR, "Invalid pair-setup data");
		return;
	}

	pairing_get_public_key(conn->raop->pairing, public_key);
    pairing_session_set_setup_status(conn->pairing);

	*response_data = malloc(sizeof(public_key));
	if (*response_data) {
		http_response_add_header(response, "Content-Type", "application/octet-stream");
		memcpy(*response_data, public_key, sizeof(public_key));
		*response_datalen = sizeof(public_key);
	}
}

static void
raop_handler_pairverify(raop_conn_t *conn,
                        http_request_t *request, http_response_t *response,
                        char **response_data, int *response_datalen)
{
    if (pairing_session_check_handshake_status(conn->pairing)) {
        return;
    }
	unsigned char public_key[32];
	unsigned char signature[64];
	const unsigned char *data;
	int datalen;

	data = (unsigned char *) http_request_get_data(request, &datalen);
	if (datalen < 4) {
		logger_log(conn->raop->logger, LOGGER_ERR, "Invalid pair-verify data");
		return;
	}
	switch (data[0]) {
	case 1:
		if (datalen != 4 + 32 + 32) {
			logger_log(conn->raop->logger, LOGGER_ERR, "Invalid pair-verify data");
			return;
		}
		/* We can fall through these errors, the result will just be garbage... */
		if (pairing_session_handshake(conn->pairing, data + 4, data + 4 + 32)) {
			logger_log(conn->raop->logger, LOGGER_ERR, "Error initializing pair-verify handshake");
		}
		if (pairing_session_get_public_key(conn->pairing, public_key)) {
			logger_log(conn->raop->logger, LOGGER_ERR, "Error getting ECDH public key");
		}
		if (pairing_session_get_signature(conn->pairing, signature)) {
			logger_log(conn->raop->logger, LOGGER_ERR, "Error getting ED25519 signature");
		}
		*response_data = malloc(sizeof(public_key) + sizeof(signature));
		if (*response_data) {
			http_response_add_header(response, "Content-Type", "application/octet-stream");
			memcpy(*response_data, public_key, sizeof(public_key));
			memcpy(*response_data + sizeof(public_key), signature, sizeof(signature));
			*response_datalen = sizeof(public_key) + sizeof(signature);
		}
		break;
	case 0:
		if (datalen != 4 + 64) {
			logger_log(conn->raop->logger, LOGGER_ERR, "Invalid pair-verify data");
			return;
		}

		if (pairing_session_finish(conn->pairing, data + 4)) {
			logger_log(conn->raop->logger, LOGGER_ERR, "Incorrect pair-verify signature");
			http_response_set_disconnect(response, 1);
			return;
		}
        http_response_add_header(response, "Content-Type", "application/octet-stream");
		break;
	}
}

static void
raop_handler_fpsetup(raop_conn_t *conn,
                        http_request_t *request, http_response_t *response,
                        char **response_data, int *response_datalen)
{
	const unsigned char *data;
	int datalen;

	data = (unsigned char *) http_request_get_data(request, &datalen);
	if (datalen == 16) {
		*response_data = malloc(142);
		if (*response_data) {
            http_response_add_header(response, "Content-Type", "application/octet-stream");
			if (!fairplay_setup(conn->fairplay, data, (unsigned char *) *response_data)) {
				*response_datalen = 142;
			} else {
				// Handle error?
				free(*response_data);
				*response_data = NULL;
			}
		}
	} else if (datalen == 164) {
		*response_data = malloc(32);
		if (*response_data) {
            http_response_add_header(response, "Content-Type", "application/octet-stream");
			if (!fairplay_handshake(conn->fairplay, data, (unsigned char *) *response_data)) {
				*response_datalen = 32;
			} else {
				// Handle error?
				free(*response_data);
				*response_data = NULL;
			}
		}
	} else {
		logger_log(conn->raop->logger, LOGGER_ERR, "Invalid fp-setup data length");
		return;
	}
}

static void
raop_handler_options(raop_conn_t *conn,
                     http_request_t *request, http_response_t *response,
                     char **response_data, int *response_datalen)
{
	http_response_add_header(response, "Public", "SETUP, RECORD, PAUSE, FLUSH, TEARDOWN, OPTIONS, GET_PARAMETER, SET_PARAMETER");
}



static void
raop_handler_setup(raop_conn_t *conn,
                   http_request_t *request, http_response_t *response,
                   char **response_data, int *response_datalen)
{
    unsigned short remote_cport=0, remote_tport=0;

    const char *transport;
    char buffer[1024];
    int use_udp;
    const char *dacp_id;
    const char *active_remote_header;

    const char *data;
    int datalen;

    data = http_request_get_data(request, &datalen);

    dacp_id = http_request_get_header(request, "DACP-ID");
    active_remote_header = http_request_get_header(request, "Active-Remote");

    if (dacp_id && active_remote_header) {
        logger_log(conn->raop->logger, LOGGER_DEBUG, "DACP-ID: %s", dacp_id);
        logger_log(conn->raop->logger, LOGGER_DEBUG, "Active-Remote: %s", active_remote_header);
        if (conn->raop_rtp) {
            raop_rtp_remote_control_id(conn->raop_rtp, dacp_id, active_remote_header);
        }
    }

    transport = http_request_get_header(request, "Transport");
    if (transport) {
        logger_log(conn->raop->logger, LOGGER_INFO, "[RTSP#%d] SETUP transport=%s", conn->id, transport);
        use_udp = strncmp(transport, "RTP/AVP/TCP", 11);
    } else {
        logger_log(conn->raop->logger, LOGGER_INFO, "[RTSP#%d] SETUP transport=(null)", conn->id);
        use_udp = 0;
    }


    // 解析bplist
    plist_t root_node = NULL;
    plist_t stream_note = NULL;
    uint64_t stream_type = 0;
    int has_stream_type = 0;
    int is_init_setup = 0;
    int is_mirror_setup = 0;
    int is_audio_setup = 0;
    plist_from_bin(data, datalen, &root_node);
    if (!root_node) {
        logger_log(conn->raop->logger, LOGGER_ERR, "SETUP invalid plist");
        http_response_set_disconnect(response, 1);
        return;
    }

    stream_note = raop_get_first_stream_node(root_node);
    has_stream_type = raop_get_stream_type(stream_note, &stream_type);
    is_init_setup = plist_dict_get_item(root_node, "eiv") &&
                    plist_dict_get_item(root_node, "ekey") &&
                    plist_dict_get_item(root_node, "timingPort");
    is_mirror_setup = has_stream_type && stream_type == 110;
    is_audio_setup = has_stream_type && stream_type == 96;

    if (is_init_setup) {
		unsigned char aesiv[16];
		unsigned char aeskey[16];
        logger_log(conn->raop->logger, LOGGER_INFO, "[RTSP#%d] SETUP step=1 FairPlay/audio init", conn->id);
        conn->setup_step = 1;
        if (conn->raop_rtp) {
            logger_log(conn->raop->logger, LOGGER_INFO, "[RTSP#%d] SETUP step=1 replacing previous audio RTP session", conn->id);
            raop_rtp_destroy(conn->raop_rtp);
            conn->raop_rtp = NULL;
        }
        if (conn->raop_rtp_mirror) {
            logger_log(conn->raop->logger, LOGGER_INFO, "[RTSP#%d] SETUP step=1 replacing previous mirror RTP session", conn->id);
            raop_rtp_mirror_destroy(conn->raop_rtp_mirror);
            conn->raop_rtp_mirror = NULL;
        }
        // 第一次setup
        plist_t eiv_note = plist_dict_get_item(root_node, "eiv");
        char* eiv= NULL;
        uint64_t eiv_len = 0;
        if (!eiv_note) {
            logger_log(conn->raop->logger, LOGGER_ERR, "SETUP 1 missing eiv");
            plist_free(root_node);
            http_response_set_disconnect(response, 1);
            return;
        }
        plist_get_data_val(eiv_note, &eiv, &eiv_len);
        if (!eiv || eiv_len < 16) {
            logger_log(conn->raop->logger, LOGGER_ERR, "SETUP 1 invalid eiv length=%llu", eiv_len);
            free(eiv);
            plist_free(root_node);
            http_response_set_disconnect(response, 1);
            return;
        }
        memcpy(aesiv, eiv, 16);
        free(eiv);
        plist_t ekey_note = plist_dict_get_item(root_node, "ekey");
        char* ekey= NULL;
        uint64_t ekey_len = 0;
        if (!ekey_note) {
            logger_log(conn->raop->logger, LOGGER_ERR, "SETUP 1 missing ekey");
            plist_free(root_node);
            http_response_set_disconnect(response, 1);
            return;
        }
        plist_get_data_val(ekey_note, &ekey, &ekey_len);
        if (!ekey || ekey_len < 72) {
            logger_log(conn->raop->logger, LOGGER_ERR, "SETUP 1 invalid ekey length=%llu", ekey_len);
            free(ekey);
            plist_free(root_node);
            http_response_set_disconnect(response, 1);
            return;
        }
        // 时间port
		uint64_t timing_rport;
        plist_t time_note = plist_dict_get_item(root_node, "timingPort");
        if (!time_note) {
            logger_log(conn->raop->logger, LOGGER_ERR, "SETUP 1 missing timingPort");
            free(ekey);
            plist_free(root_node);
            http_response_set_disconnect(response, 1);
            return;
        }
        plist_get_uint_val(time_note, &timing_rport);
        logger_log(conn->raop->logger, LOGGER_INFO, "[RTSP#%d] SETUP step=1 timingPort=%llu", conn->id, timing_rport);
        // ekey是72字节
        int ret = fairplay_decrypt(conn->fairplay, ekey, aeskey);
        free(ekey);
        if (ret != 0) {
            logger_log(conn->raop->logger, LOGGER_ERR, "[RTSP#%d] fairplay_decrypt failed ret=%d", conn->id, ret);
        }
		unsigned char ecdh_secret[32];
        pairing_get_ecdh_secret_key(conn->pairing, ecdh_secret);
        conn->raop_rtp = raop_rtp_init(conn->raop->logger, &conn->raop->callbacks, &conn->remote_saddr, conn->remote_saddrlen, aeskey, aesiv, ecdh_secret, timing_rport);
		conn->raop_rtp_mirror = raop_rtp_mirror_init(conn->raop->logger, &conn->raop->callbacks, &conn->remote_saddr, conn->remote_saddrlen, aeskey, ecdh_secret, timing_rport);
        logger_log(conn->raop->logger, conn->raop_rtp ? LOGGER_INFO : LOGGER_ERR, conn->raop_rtp ? "[RTSP#%d] Audio RTP init success" : "[RTSP#%d] Audio RTP init failed", conn->id);
        logger_log(conn->raop->logger, conn->raop_rtp_mirror ? LOGGER_INFO : LOGGER_ERR, conn->raop_rtp_mirror ? "[RTSP#%d] Mirror RTP init success" : "[RTSP#%d] Mirror RTP init failed", conn->id);
    } else if (is_mirror_setup) {
		unsigned short tport=0, dport=0;
        logger_log(conn->raop->logger, LOGGER_INFO, "[RTSP#%d] SETUP step=2 stream negotiation", conn->id);
        if (!stream_note) {
            logger_log(conn->raop->logger, LOGGER_ERR, "SETUP 2 empty streams");
            plist_free(root_node);
            http_response_set_disconnect(response, 1);
            return;
        }
        conn->setup_step = 2;
        logger_log(conn->raop->logger, LOGGER_INFO, "[RTSP#%d] SETUP step=2 stream type=%llu (%s)", conn->id, stream_type,
                   raop_stream_type_name(stream_type));
        conn_set_role(conn, "mirror-control");
		plist_t stream_id_note = plist_dict_get_item(stream_note, "streamConnectionID");
		uint64_t streamConnectionID;
        if (!stream_id_note) {
            logger_log(conn->raop->logger, LOGGER_ERR, "SETUP 2 missing streamConnectionID");
            plist_free(root_node);
            http_response_set_disconnect(response, 1);
            return;
        }
		plist_get_uint_val(stream_id_note, &streamConnectionID);
        logger_log(conn->raop->logger, LOGGER_INFO, "[RTSP#%d] SETUP step=2 streamConnectionID=%llu", conn->id, streamConnectionID);


        if (conn->raop_rtp_mirror) {
			raop_rtp_init_mirror_aes(conn->raop_rtp_mirror, streamConnectionID);
			raop_rtp_start_mirror(conn->raop_rtp_mirror, use_udp, remote_tport, &tport, &dport);
            logger_log(conn->raop->logger, LOGGER_INFO, "[RTSP#%d] Mirror started dataPort=%u timingPort=%u", conn->id, dport, tport);
        } else {
            logger_log(conn->raop->logger, LOGGER_ERR, "[RTSP#%d] Mirror RTP not initialized at SETUP; video mirroring will fail", conn->id);
            http_response_set_disconnect(response, 1);
        }
        plist_t r_node = plist_new_dict();
        plist_t s_node = plist_new_array();
        plist_t s_sub_node = plist_new_dict();
        plist_t data_port_node = plist_new_uint(dport);
        plist_t type_node = plist_new_uint(110);
        plist_t event_port_node = plist_new_uint(conn->raop->port);
        plist_t timing_port_node = plist_new_uint(tport);
        plist_dict_set_item(s_sub_node, "dataPort", data_port_node);
        plist_dict_set_item(s_sub_node, "type", type_node);
        plist_array_append_item(s_node, s_sub_node);
        plist_dict_set_item(r_node, "eventPort", event_port_node);
        plist_dict_set_item(r_node, "timingPort", timing_port_node);
        plist_dict_set_item(r_node, "streams", s_node);
        raop_write_plist_response(response, r_node, response_data, response_datalen);
        plist_free(r_node);
        logger_log(conn->raop->logger, LOGGER_INFO, "[RTSP#%d] SETUP step=2 response dataPort=%u timingPort=%u", conn->id, dport, tport);
    } else if (is_audio_setup) {
        logger_log(conn->raop->logger, LOGGER_INFO, "[RTSP#%d] SETUP step=3 audio stream", conn->id);
        {
            double ct = -1, spf = -1, audio_format = -1, sr = -1;
            raop_get_plist_number(stream_note, "ct", &ct);
            raop_get_plist_number(stream_note, "spf", &spf);
            raop_get_plist_number(stream_note, "audioFormat", &audio_format);
            raop_get_plist_number(stream_note, "sr", &sr);
            logger_log(conn->raop->logger, LOGGER_INFO,
                       "[RTSP#%d] SETUP step=3 audio ct=%.0f audioFormat=0x%llx spf=%.0f sr=%.0f",
                       conn->id, ct, (unsigned long long) audio_format, spf, sr);
        }
        conn->setup_step = 3;
        conn_set_role(conn, "audio-control");
        unsigned short cport = 0, tport = 0, dport = 0;

        if (conn->raop_rtp) {
            double ct = 8, spf = 480;
            raop_get_plist_number(stream_note, "ct", &ct);
            raop_get_plist_number(stream_note, "spf", &spf);
            raop_rtp_set_audio_format(conn->raop_rtp, (int) ct, (int) spf);
            if (conn->raop->callbacks.audio_session) {
                conn->raop->callbacks.audio_session(conn->raop->callbacks.cls, 1, (int) ct);
            }
            raop_rtp_start_audio(conn->raop_rtp, use_udp, remote_cport, remote_tport, &cport, &tport, &dport);
            logger_log(conn->raop->logger, LOGGER_INFO, "[RTSP#%d] Audio started dataPort=%u controlPort=%u timingPort=%u", conn->id, dport, cport, tport);
        } else {
            logger_log(conn->raop->logger, LOGGER_ERR, "[RTSP#%d] Audio RTP not initialized at SETUP; audio will stay disabled", conn->id);
        }
        // 需要返回端口
		/**
		 * <dict>
	<key>streams</key>
	<array>
		<dict>
			<key>dataPort</key>
			<integer>42820</integer>
			<key>controlPort</key>
			<integer>46440</integer>
			<key>type</key>
			<integer>96</integer>
		</dict>
	</array>

	<key>timingPort</key>
	<integer>46440</integer>
</dict>
</plist>
		 */
		plist_t r_node = plist_new_dict();
		plist_t s_node = plist_new_array();
		plist_t s_sub_node = plist_new_dict();
		plist_t data_port_node = plist_new_uint(dport);
		plist_t type_node = plist_new_uint(96);
		plist_t control_port_node = plist_new_uint(cport);
		plist_t timing_port_node = plist_new_uint(tport);
		plist_dict_set_item(s_sub_node, "dataPort", data_port_node);
		plist_dict_set_item(s_sub_node, "type", type_node);
		plist_dict_set_item(s_sub_node, "controlPort", control_port_node);
		plist_array_append_item(s_node, s_sub_node);
		plist_dict_set_item(r_node, "timingPort", timing_port_node);
		plist_dict_set_item(r_node, "streams", s_node);
		raop_write_plist_response(response, r_node, response_data, response_datalen);
		plist_free(r_node);

		logger_log(conn->raop->logger, LOGGER_INFO, "[RTSP#%d] SETUP step=3 response dataPort=%u controlPort=%u timingPort=%u", conn->id, dport, cport, tport);
    } else {
        logger_log(conn->raop->logger, LOGGER_ERR,
                   "[RTSP#%d] SETUP payload not recognized: setup_step=%d has_stream=%s stream_type=%s",
                   conn->id,
                   conn->setup_step,
                   stream_note ? "yes" : "no",
                   has_stream_type ? raop_stream_type_name(stream_type) : "missing");
        http_response_set_disconnect(response, 1);
    }
    plist_free(root_node);
}

/* Volume is applied right away from the RTSP thread. Routing it through the
 * audio RTP thread (as before) lost it whenever no audio stream was running,
 * e.g. during URL video playback or silent mirroring. */
static void
raop_apply_volume(raop_conn_t *conn, float volume_db)
{
    if (volume_db > 0.0f) {
        volume_db = 0.0f;
    } else if (volume_db < -144.0f) {
        volume_db = -144.0f;
    }
    conn->raop->volume = volume_db;
    if (conn->raop->callbacks.audio_set_volume) {
        conn->raop->callbacks.audio_set_volume(conn->raop->callbacks.cls, NULL, volume_db);
    }
}

static void
raop_handler_get_parameter(raop_conn_t *conn,
                           http_request_t *request, http_response_t *response,
                           char **response_data, int *response_datalen)
{
	const char *content_type;
	const char *data;
	int datalen;
	char *datastr = NULL;
	char *current;

	content_type = http_request_get_header(request, "Content-Type");
	data = http_request_get_data(request, &datalen);
	if (!content_type) {
		logger_log(conn->raop->logger, LOGGER_WARNING, "GET_PARAMETER without Content-Type");
		return;
	}
	if (!strcmp(content_type, "text/parameters")) {
		if (!data || datalen <= 0) {
			logger_log(conn->raop->logger, LOGGER_WARNING, "GET_PARAMETER without body");
			return;
		}
		datastr = calloc(1, datalen + 1);
		if (!datastr) {
			logger_log(conn->raop->logger, LOGGER_ERR, "GET_PARAMETER alloc failed");
			return;
		}
		memcpy(datastr, data, datalen);
		current = datastr;

		while (current) {
			const char *next;
			int handled = 0;

			/* This is a bit ugly, but seems to be how airport works too */
			if (!strncmp(current, "volume\r\n", 8)) {
				char volume[64];
				float current_volume = conn->raop->volume;
				snprintf(volume, sizeof(volume), "volume: %.6f\r\n", current_volume);

				http_response_add_header(response, "Content-Type", "text/parameters");
				*response_data = strdup(volume);
				if (*response_data) {
					*response_datalen = strlen(*response_data);
				}
				logger_log(conn->raop->logger, LOGGER_INFO,
				           "[RTSP#%d] GET_PARAMETER volume -> %.2f dB",
				           conn->id,
				           current_volume);
				handled = 1;
			}

			next = strstr(current, "\r\n");
			if (next && !handled) {
				logger_log(conn->raop->logger, LOGGER_WARNING,
				           "Found an unknown parameter: %.*s", (int)(next - current), current);
				current = (char *)next + 2;
			} else if (next) {
				current = (char *)next + 2;
			} else {
				current = NULL;
			}
		}
		free(datastr);
	}
}

static void
raop_handler_set_parameter(raop_conn_t *conn,
                           http_request_t *request, http_response_t *response,
                           char **response_data, int *response_datalen)
{
	const char *content_type;
	const char *data;
	int datalen;

	content_type = http_request_get_header(request, "Content-Type");
	data = http_request_get_data(request, &datalen);
	if (!content_type) {
		logger_log(conn->raop->logger, LOGGER_WARNING, "SET_PARAMETER without Content-Type");
		return;
	}
	if (!strcmp(content_type, "text/parameters")) {
		char *datastr;
		datastr = calloc(1, datalen+1);
		if (data && datastr && datalen > 8 && !strncmp(data, "volume: ", 8)) {
			float vol = 0.0f;
			memcpy(datastr, data, datalen);
			if (sscanf(datastr + 8, "%f", &vol) == 1) {
				logger_log(conn->raop->logger, LOGGER_INFO,
				           "[RTSP#%d] SET_PARAMETER volume=%.2f dB",
				           conn->id,
				           vol);
				raop_apply_volume(conn, vol);
			}
		} else if (data && datastr && conn->raop_rtp) {
			memcpy(datastr, data, datalen);
			if (!strncmp(datastr, "progress: ", 10)) {
				unsigned int start, curr, end;
				sscanf(datastr+10, "%u/%u/%u", &start, &curr, &end);
				logger_log(conn->raop->logger, LOGGER_INFO,
				           "[RTSP#%d] SET_PARAMETER progress=%u/%u/%u",
				           conn->id,
				           start, curr, end);
				if (conn->raop->callbacks.audio_set_progress) {
					conn->raop->callbacks.audio_set_progress(conn->raop->callbacks.cls, NULL, start, curr, end);
				}
			} else {
				logger_log(conn->raop->logger, LOGGER_INFO,
				           "[RTSP#%d] SET_PARAMETER text body=%s",
				           conn->id,
				           datastr);
			}
		} else if (!conn->raop_rtp) {
			logger_log(conn->raop->logger, LOGGER_WARNING, "RAOP not initialized at SET_PARAMETER");
		}
		free(datastr);
	} else if (!strcmp(content_type, "image/jpeg") || !strcmp(content_type, "image/png")) {
		logger_log(conn->raop->logger, LOGGER_INFO, "[RTSP#%d] SET_PARAMETER coverart bytes=%d", conn->id, datalen);
		if (conn->raop->callbacks.audio_set_coverart) {
			conn->raop->callbacks.audio_set_coverart(conn->raop->callbacks.cls, NULL, data, datalen);
		}
	} else if (!strcmp(content_type, "application/x-dmap-tagged")) {
		logger_log(conn->raop->logger, LOGGER_INFO, "[RTSP#%d] SET_PARAMETER metadata bytes=%d", conn->id, datalen);
		if (conn->raop->callbacks.audio_set_metadata) {
			conn->raop->callbacks.audio_set_metadata(conn->raop->callbacks.cls, NULL, data, datalen);
		}
	}
}


static void
raop_handler_feedback(raop_conn_t *conn,
                           http_request_t *request, http_response_t *response,
                           char **response_data, int *response_datalen)
{
    (void)conn;
    (void)request;
    (void)response;
    (void)response_data;
    (void)response_datalen;
}

static void
raop_handler_audio_mode(raop_conn_t *conn,
                        http_request_t *request, http_response_t *response,
                        char **response_data, int *response_datalen)
{
    const char *data;
    int datalen;

    (void)response;
    (void)response_data;
    (void)response_datalen;

    data = http_request_get_data(request, &datalen);
    logger_log(conn->raop->logger, LOGGER_INFO,
               "[RTSP#%d] /audioMode bytes=%d accepted",
               conn->id,
               datalen);
}

static void
raop_handler_record(raop_conn_t *conn,
                      http_request_t *request, http_response_t *response,
                      char **response_data, int *response_datalen)
{
    logger_log(conn->raop->logger, LOGGER_INFO, "[RTSP#%d] RECORD audio stream ready", conn->id);
    http_response_add_header(response, "Audio-Latency", "11025");
    http_response_add_header(response, "Audio-Jack-Status", "connected; type=analog");
}

static char *
raop_copy_trimmed_value(const char *start, size_t len)
{
    while (len > 0 && isspace((unsigned char)*start)) {
        start++;
        len--;
    }
    while (len > 0 && isspace((unsigned char)start[len - 1])) {
        len--;
    }
    char *out = calloc(1, len + 1);
    if (!out) {
        return NULL;
    }
    memcpy(out, start, len);
    return out;
}

static char *
raop_find_text_parameter(const char *data, int datalen, const char *name)
{
    const char *cursor = data;
    size_t name_len = strlen(name);

    if (!data || datalen <= 0 || !name) {
        return NULL;
    }
    while (cursor < data + datalen) {
        const char *line_end = memchr(cursor, '\n', (data + datalen) - cursor);
        const char *value;
        size_t line_len = line_end ? (size_t)(line_end - cursor) : (size_t)((data + datalen) - cursor);
        if (line_len > 0 && cursor[line_len - 1] == '\r') {
            line_len--;
        }
        if (line_len > name_len && !strncasecmp(cursor, name, name_len) && cursor[name_len] == ':') {
            value = cursor + name_len + 1;
            return raop_copy_trimmed_value(value, line_len - name_len - 1);
        }
        if (!line_end) {
            break;
        }
        cursor = line_end + 1;
    }
    return NULL;
}

static char *
raop_get_plist_string_copy(plist_t root_node, const char *key)
{
    plist_t value_node;
    char *value = NULL;

    if (!root_node || !key) {
        return NULL;
    }
    value_node = plist_dict_get_item(root_node, key);
    if (!value_node) {
        return NULL;
    }
    plist_get_string_val(value_node, &value);
    return value;
}

static int
raop_get_plist_number(plist_t root_node, const char *key, double *out)
{
    plist_t node;

    if (!root_node || !key || !out) {
        return 0;
    }
    node = plist_dict_get_item(root_node, key);
    if (!node) {
        return 0;
    }
    if (plist_get_node_type(node) == PLIST_REAL) {
        plist_get_real_val(node, out);
        return 1;
    }
    if (plist_get_node_type(node) == PLIST_UINT) {
        uint64_t value = 0;
        plist_get_uint_val(node, &value);
        *out = (double) value;
        return 1;
    }
    return 0;
}

static int
raop_get_text_number(const char *data, int datalen, const char *name, double *out)
{
    char *value = raop_find_text_parameter(data, datalen, name);
    char *end = NULL;
    double parsed;

    if (!value) {
        return 0;
    }
    parsed = strtod(value, &end);
    if (end == value) {
        free(value);
        return 0;
    }
    free(value);
    *out = parsed;
    return 1;
}

/* Reads "name=value" from the query string of a request URL. */
static int
raop_get_query_number(const char *url, const char *name, double *out)
{
    const char *query;
    size_t name_len;

    if (!url || !name || !out) {
        return 0;
    }
    query = strchr(url, '?');
    if (!query) {
        return 0;
    }
    query++;
    name_len = strlen(name);
    while (*query) {
        if (!strncmp(query, name, name_len) && query[name_len] == '=') {
            char *end = NULL;
            double parsed = strtod(query + name_len + 1, &end);
            if (end == query + name_len + 1) {
                return 0;
            }
            *out = parsed;
            return 1;
        }
        query = strchr(query, '&');
        if (!query) {
            break;
        }
        query++;
    }
    return 0;
}

/* start_seconds / start_fraction may be NULL; they are set to -1 when absent. */
static char *
raop_extract_direct_play_url(http_request_t *request, double *start_seconds, double *start_fraction)
{
    const char *content_location;
    const char *content_type;
    const char *data;
    int datalen = 0;
    char *url = NULL;
    double ignored_seconds;
    double ignored_fraction;

    if (!start_seconds) start_seconds = &ignored_seconds;
    if (!start_fraction) start_fraction = &ignored_fraction;
    *start_seconds = -1.0;
    *start_fraction = -1.0;

    content_type = http_request_get_header(request, "Content-Type");
    data = http_request_get_data(request, &datalen);

    if (data && datalen > 0 && content_type && !strcmp(content_type, "application/x-apple-binary-plist")) {
        plist_t root_node = NULL;
        plist_from_bin(data, datalen, &root_node);
        if (root_node) {
            url = raop_get_plist_string_copy(root_node, "Content-Location");
            if (!url) url = raop_get_plist_string_copy(root_node, "content-location");
            if (!url) url = raop_get_plist_string_copy(root_node, "URL");
            if (!url) url = raop_get_plist_string_copy(root_node, "url");
            if (!url) url = raop_get_plist_string_copy(root_node, "location");
            raop_get_plist_number(root_node, "Start-Position-Seconds", start_seconds);
            raop_get_plist_number(root_node, "Start-Position", start_fraction);
            plist_free(root_node);
        }
    } else if (data && datalen > 0) {
        url = raop_find_text_parameter(data, datalen, "Content-Location");
        if (!url) url = raop_find_text_parameter(data, datalen, "URL");
        raop_get_text_number(data, datalen, "Start-Position-Seconds", start_seconds);
        raop_get_text_number(data, datalen, "Start-Position", start_fraction);
    }

    if (!url) {
        content_location = http_request_get_header(request, "Content-Location");
        if (content_location && content_location[0]) {
            url = strdup(content_location);
        }
    }
    return url;
}

static int
raop_is_supported_direct_play_url(const char *url)
{
    if (!url || !url[0]) {
        return 0;
    }
    return !strncasecmp(url, "http://", 7) ||
           !strncasecmp(url, "https://", 8);
}

static void
raop_handler_direct_play(raop_conn_t *conn,
                         http_request_t *request, http_response_t *response,
                         char **response_data, int *response_datalen)
{
    double start_seconds = -1.0;
    double start_fraction = -1.0;
    char *url = raop_extract_direct_play_url(request, &start_seconds, &start_fraction);

    (void)response;
    (void)response_data;
    (void)response_datalen;

    if (url && url[0] && raop_is_supported_direct_play_url(url)) {
        logger_log(conn->raop->logger, LOGGER_INFO,
                   "[RTSP#%d] Direct play URL: %s start=%.3fs/%.3f",
                   conn->id, url, start_seconds, start_fraction);
        if (conn->raop->callbacks.direct_play) {
            conn->raop->callbacks.direct_play(conn->raop->callbacks.cls, url, start_seconds, start_fraction);
        }
    } else if (url && url[0]) {
        logger_log(conn->raop->logger, LOGGER_WARNING,
                   "[RTSP#%d] Direct play URL scheme is not supported: %s",
                   conn->id, url);
    } else {
        logger_log(conn->raop->logger, LOGGER_WARNING, "[RTSP#%d] Direct play requested without URL", conn->id);
    }
    free(url);
}

static void
raop_handler_direct_stop(raop_conn_t *conn,
                         http_request_t *request, http_response_t *response,
                         char **response_data, int *response_datalen)
{
    (void)request;
    (void)response;
    (void)response_data;
    (void)response_datalen;

    logger_log(conn->raop->logger, LOGGER_INFO, "[RTSP#%d] Direct stop", conn->id);
    if (conn->raop->callbacks.direct_stop) {
        conn->raop->callbacks.direct_stop(conn->raop->callbacks.cls, "direct-stop");
    }
}

static int
raop_get_direct_playback_info(raop_conn_t *conn, raop_playback_info_t *info)
{
    memset(info, 0, sizeof(*info));
    if (!conn->raop->callbacks.direct_get_playback_info) {
        return 0;
    }
    return conn->raop->callbacks.direct_get_playback_info(conn->raop->callbacks.cls, info);
}

static void
raop_handler_direct_scrub(raop_conn_t *conn,
                          http_request_t *request, http_response_t *response,
                          char **response_data, int *response_datalen)
{
    const char *method = http_request_get_method(request);
    const char *url = http_request_get_url(request);
    double position = 0.0;

    if (method && !strcmp(method, "POST")) {
        if (raop_get_query_number(url, "position", &position)) {
            logger_log(conn->raop->logger, LOGGER_INFO, "[RTSP#%d] Direct scrub to %.3fs", conn->id, position);
            if (conn->raop->callbacks.direct_scrub) {
                conn->raop->callbacks.direct_scrub(conn->raop->callbacks.cls, position);
            }
        }
        return;
    }

    /* GET /scrub reports the current position as text/parameters */
    raop_playback_info_t info;
    char body[128];
    raop_get_direct_playback_info(conn, &info);
    snprintf(body, sizeof(body), "duration: %.6f\r\nposition: %.6f\r\n", info.duration, info.position);
    http_response_add_header(response, "Content-Type", "text/parameters");
    *response_data = strdup(body);
    if (*response_data) {
        *response_datalen = (int) strlen(*response_data);
    }
}

static void
raop_handler_direct_rate(raop_conn_t *conn,
                         http_request_t *request, http_response_t *response,
                         char **response_data, int *response_datalen)
{
    double rate = 1.0;

    (void)response;
    (void)response_data;
    (void)response_datalen;

    if (raop_get_query_number(http_request_get_url(request), "value", &rate)) {
        logger_log(conn->raop->logger, LOGGER_INFO, "[RTSP#%d] Direct rate=%.3f", conn->id, rate);
        if (conn->raop->callbacks.direct_rate) {
            conn->raop->callbacks.direct_rate(conn->raop->callbacks.cls, rate);
        }
    }
}

static void
raop_handler_direct_volume(raop_conn_t *conn,
                           http_request_t *request, http_response_t *response,
                           char **response_data, int *response_datalen)
{
    double volume = 1.0;

    (void)response;
    (void)response_data;
    (void)response_datalen;

    /* HTTP AirPlay sends a linear 0..1 value; map it onto the RTSP dB scale (-30..0, -144 = mute) */
    if (raop_get_query_number(http_request_get_url(request), "volume", &volume)) {
        float volume_db = volume <= 0.0 ? -144.0f : (float) (30.0 * ((volume > 1.0 ? 1.0 : volume) - 1.0));
        logger_log(conn->raop->logger, LOGGER_INFO, "[RTSP#%d] Direct volume=%.3f (%.2f dB)", conn->id, volume, volume_db);
        raop_apply_volume(conn, volume_db);
    }
}

static plist_t
raop_new_time_ranges(double start, double duration)
{
    plist_t ranges = plist_new_array();
    plist_t range = plist_new_dict();
    plist_dict_set_item(range, "start", plist_new_real(start));
    plist_dict_set_item(range, "duration", plist_new_real(duration));
    plist_array_append_item(ranges, range);
    return ranges;
}

static void
raop_handler_playback_info(raop_conn_t *conn,
                           http_request_t *request, http_response_t *response,
                           char **response_data, int *response_datalen)
{
    raop_playback_info_t info;
    plist_t root_node = plist_new_dict();

    (void)request;

    if (raop_get_direct_playback_info(conn, &info) && info.ready) {
        double loaded = info.buffered > info.position ? info.buffered - info.position : 0.0;
        plist_dict_set_item(root_node, "duration", plist_new_real(info.duration));
        plist_dict_set_item(root_node, "position", plist_new_real(info.position));
        plist_dict_set_item(root_node, "rate", plist_new_real(info.rate));
        plist_dict_set_item(root_node, "readyToPlay", plist_new_bool(1));
        plist_dict_set_item(root_node, "playbackBufferEmpty", plist_new_bool(loaded <= 0.0));
        plist_dict_set_item(root_node, "playbackBufferFull", plist_new_bool(0));
        plist_dict_set_item(root_node, "playbackLikelyToKeepUp", plist_new_bool(1));
        plist_dict_set_item(root_node, "loadedTimeRanges", raop_new_time_ranges(info.position, loaded));
        plist_dict_set_item(root_node, "seekableTimeRanges", raop_new_time_ranges(0.0, info.duration));
    } else {
        /* Still loading (or nothing playing): a fake "ready, duration 0" made iOS stop playback */
        plist_dict_set_item(root_node, "readyToPlay", plist_new_bool(0));
    }
    raop_write_plist_response(response, root_node, response_data, response_datalen);
    plist_free(root_node);
}

static void
raop_handler_empty_plist(raop_conn_t *conn,
                         http_request_t *request, http_response_t *response,
                         char **response_data, int *response_datalen)
{
    plist_t root_node = plist_new_dict();

    (void)conn;
    (void)request;

    raop_write_plist_response(response, root_node, response_data, response_datalen);
    plist_free(root_node);
}
