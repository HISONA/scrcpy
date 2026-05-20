#include "server.h"

#include <assert.h>
#include <inttypes.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "util/log.h"
#include "util/net_intr.h"
#include "util/str.h"

#define SC_DEFAULT_DEVICE_PORT 27183

static bool
sc_server_sleep(struct sc_server *server, sc_tick deadline) {
    sc_mutex_lock(&server->mutex);
    bool timed_out = false;
    while (!server->stopped && !timed_out) {
        timed_out = !sc_cond_timedwait(&server->cond_stopped,
                                       &server->mutex, deadline);
    }
    bool stopped = server->stopped;
    sc_mutex_unlock(&server->mutex);

    return !stopped;
}

static bool
device_read_info(struct sc_intr *intr, sc_socket device_socket,
                 struct sc_server_info *info) {
    uint8_t buf[SC_DEVICE_NAME_FIELD_LENGTH];
    ssize_t r = net_recv_all_intr(intr, device_socket, buf, sizeof(buf));
    if (r < SC_DEVICE_NAME_FIELD_LENGTH) {
        LOGE("Could not retrieve device information");
        return false;
    }
    buf[SC_DEVICE_NAME_FIELD_LENGTH - 1] = '\0';
    memcpy(info->device_name, (char *) buf, sizeof(info->device_name));

    return true;
}

static sc_socket
connect_to_device(struct sc_server *server, uint32_t host, uint16_t port,
                  unsigned attempts, sc_tick delay) {
    do {
        LOGD("Connecting to device %08" PRIx32 ":%" PRIu16
             " (remaining attempts: %u)", host, port, attempts);

        sc_socket socket = net_socket();
        if (socket != SC_SOCKET_NONE) {
            bool ok = net_connect_intr(&server->intr, socket, host, port);
            if (ok) {
                // Read dummy byte to confirm the connection is alive
                char byte;
                if (net_recv_intr(&server->intr, socket, &byte, 1) == 1) {
                    return socket;
                }
            }
            net_close(socket);
        }

        if (sc_intr_is_interrupted(&server->intr)) {
            // Stop immediately
            break;
        }

        if (attempts > 1) {
            sc_tick deadline = sc_tick_now() + delay;
            bool ok = sc_server_sleep(server, deadline);
            if (!ok) {
                LOGI("Connection attempt stopped");
                break;
            }
        }
    } while (--attempts);

    return SC_SOCKET_NONE;
}

static bool
sc_server_connect_to(struct sc_server *server, struct sc_server_info *info) {
    const struct sc_server_params *params = &server->params;

    bool video = params->video;
    bool audio = params->audio;
    bool control = params->control;

    // Resolve device host
    uint32_t host = params->tunnel_host;
    if (!host) {
        if (params->device_host) {
            if (!net_parse_ipv4(params->device_host, &host)) {
                LOGE("Invalid device host: %s", params->device_host);
                return false;
            }
        } else {
            LOGE("No device host specified. Use --device-host=<IP>");
            return false;
        }
    }

    uint16_t port = params->tunnel_port;
    if (!port) {
        port = params->device_port;
    }
    if (!port) {
        port = SC_DEFAULT_DEVICE_PORT;
    }

    LOGI("Connecting to device at %s:%" PRIu16,
         params->device_host ? params->device_host : "?", port);

    sc_socket video_socket = SC_SOCKET_NONE;
    sc_socket audio_socket = SC_SOCKET_NONE;
    sc_socket control_socket = SC_SOCKET_NONE;

    // Connect to the server: 3 sequential TCP connections
    // (same protocol as the original scrcpy tunnel_forward mode)
    unsigned attempts = 100;
    sc_tick delay = SC_TICK_FROM_MS(100);

    // First socket
    sc_socket first_socket = connect_to_device(server, host, port,
                                                attempts, delay);
    if (first_socket == SC_SOCKET_NONE) {
        LOGE("Could not connect to device");
        goto fail;
    }

    if (video) {
        video_socket = first_socket;
    }

    // Second socket (if needed)
    if (audio) {
        if (!video) {
            audio_socket = first_socket;
        } else {
            audio_socket = net_socket();
            if (audio_socket == SC_SOCKET_NONE) {
                goto fail;
            }
            bool ok = net_connect_intr(&server->intr, audio_socket, host, port);
            if (!ok) {
                goto fail;
            }
        }
    }

    // Third socket (if needed)
    if (control) {
        if (!video && !audio) {
            control_socket = first_socket;
        } else {
            control_socket = net_socket();
            if (control_socket == SC_SOCKET_NONE) {
                goto fail;
            }
            bool ok = net_connect_intr(&server->intr, control_socket,
                                       host, port);
            if (!ok) {
                goto fail;
            }
        }
    }

    if (control_socket != SC_SOCKET_NONE) {
        // Disable Nagle's algorithm for the control socket
        // (it only impacts the sending side, so it is useless to set it
        // for the other sockets)
        bool ok = net_set_tcp_nodelay(control_socket, true);
        (void) ok; // error already logged
    }

    // Read device metadata from the first connected socket
    sc_socket first = video ? video_socket
                   : audio ? audio_socket
                           : control_socket;

    bool ok = device_read_info(&server->intr, first, info);
    if (!ok) {
        goto fail;
    }

    assert(!video || video_socket != SC_SOCKET_NONE);
    assert(!audio || audio_socket != SC_SOCKET_NONE);
    assert(!control || control_socket != SC_SOCKET_NONE);

    server->video_socket = video_socket;
    server->audio_socket = audio_socket;
    server->control_socket = control_socket;

    return true;

fail:
    if (video_socket != SC_SOCKET_NONE) {
        if (!net_close(video_socket)) {
            LOGW("Could not close video socket");
        }
    }

    if (audio_socket != SC_SOCKET_NONE) {
        if (!net_close(audio_socket)) {
            LOGW("Could not close audio socket");
        }
    }

    if (control_socket != SC_SOCKET_NONE) {
        net_close(control_socket);
    }

    return false;
}

static int
run_server(void *data) {
    struct sc_server *server = data;

    LOGI("scrcpy direct TCP mode (no ADB)");

    bool ok = sc_server_connect_to(server, &server->info);
    if (!ok) {
        server->cbs->on_connection_failed(server, server->cbs_userdata);
        return -1;
    }

    LOGI("Connected to device: %s", server->info.device_name);
    server->cbs->on_connected(server, server->cbs_userdata);

    // Wait for server_stop()
    sc_mutex_lock(&server->mutex);
    while (!server->stopped) {
        sc_cond_wait(&server->cond_stopped, &server->mutex);
    }
    sc_mutex_unlock(&server->mutex);

    // Interrupt sockets to wake up blocking calls
    if (server->video_socket != SC_SOCKET_NONE) {
        // There is no video_socket if --no-video is set
        net_interrupt(server->video_socket);
    }

    if (server->audio_socket != SC_SOCKET_NONE) {
        // There is no audio_socket if --no-audio is set
        net_interrupt(server->audio_socket);
    }

    if (server->control_socket != SC_SOCKET_NONE) {
        // There is no control_socket if --no-control is set
        net_interrupt(server->control_socket);
    }

    return 0;
}

bool
sc_server_init(struct sc_server *server, const struct sc_server_params *params,
              const struct sc_server_callbacks *cbs, void *cbs_userdata) {
    server->params = *params;

    bool ok = net_init();
    if (!ok) {
        return false;
    }

    ok = sc_mutex_init(&server->mutex);
    if (!ok) {
        return false;
    }

    ok = sc_cond_init(&server->cond_stopped);
    if (!ok) {
        sc_mutex_destroy(&server->mutex);
        return false;
    }

    ok = sc_intr_init(&server->intr);
    if (!ok) {
        sc_cond_destroy(&server->cond_stopped);
        sc_mutex_destroy(&server->mutex);
        return false;
    }

    server->stopped = false;

    server->video_socket = SC_SOCKET_NONE;
    server->audio_socket = SC_SOCKET_NONE;
    server->control_socket = SC_SOCKET_NONE;

    assert(cbs);
    assert(cbs->on_connection_failed);
    assert(cbs->on_connected);
    assert(cbs->on_disconnected);

    server->cbs = cbs;
    server->cbs_userdata = cbs_userdata;

    return true;
}

bool
sc_server_start(struct sc_server *server) {
    bool ok =
        sc_thread_create(&server->thread, run_server, "scrcpy-server", server);
    if (!ok) {
        LOGE("Could not create server thread");
        return false;
    }

    return true;
}

void
sc_server_stop(struct sc_server *server) {
    sc_mutex_lock(&server->mutex);
    server->stopped = true;
    sc_cond_signal(&server->cond_stopped);
    sc_intr_interrupt(&server->intr);
    sc_mutex_unlock(&server->mutex);
}

void
sc_server_join(struct sc_server *server) {
    sc_thread_join(&server->thread, NULL);
}

void
sc_server_destroy(struct sc_server *server) {
    if (server->video_socket != SC_SOCKET_NONE) {
        net_close(server->video_socket);
    }
    if (server->audio_socket != SC_SOCKET_NONE) {
        net_close(server->audio_socket);
    }
    if (server->control_socket != SC_SOCKET_NONE) {
        net_close(server->control_socket);
    }

    sc_intr_destroy(&server->intr);
    sc_cond_destroy(&server->cond_stopped);
    sc_mutex_destroy(&server->mutex);

    net_cleanup();
}
