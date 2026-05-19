src = [
    'src/main.c',
    'src/audio_player.c',
    'src/audio_regulator.c',
    'src/cli.c',
    'src/clock.c',
    'src/compat.c',
    'src/control_msg.c',
    'src/controller.c',
    'src/decoder.c',
    'src/delay_buffer.c',
    'src/demuxer.c',
    'src/device_msg.c',
    'src/disconnect.c',
    'src/events.c',
    'src/icon.c',
    'src/fps_counter.c',
    'src/frame_buffer.c',
    'src/input_manager.c',
    'src/keyboard_sdk.c',
    'src/mouse_capture.c',
    'src/mouse_sdk.c',
    'src/opengl.c',
    'src/options.c',
    'src/packet_merger.c',
    'src/receiver.c',
    'src/recorder.c',
    'src/scrcpy.c',
    'src/screen.c',
    'src/sdl_hints.c',
    'src/server.c',
    'src/texture.c',
    'src/version.c',
    'src/hid/hid_gamepad.c',
    'src/hid/hid_keyboard.c',
    'src/hid/hid_mouse.c',
    'src/trait/frame_source.c',
    'src/trait/packet_source.c',
    'src/uhid/gamepad_uhid.c',
    'src/uhid/keyboard_uhid.c',
    'src/uhid/mouse_uhid.c',
    'src/uhid/uhid_output.c',
    'src/util/acksync.c',
    'src/util/audiobuf.c',
    'src/util/average.c',
    'src/util/env.c',
    'src/util/file.c',
    'src/util/intmap.c',
    'src/util/intr.c',
    'src/util/log.c',
    'src/util/memory.c',
    'src/util/net.c',
    'src/util/net_intr.c',
    'src/util/process.c',
    'src/util/process_intr.c',
    'src/util/rand.c',
    'src/util/sdl.c',
    'src/util/strbuf.c',
    'src/util/str.c',
    'src/util/term.c',
    'src/util/thread.c',
    'src/util/tick.c',
    'src/util/timeout.c',
]

# Removed from src:
#   'src/adb/adb.c',
#   'src/adb/adb_device.c',
#   'src/adb/adb_parser.c',
#   'src/adb/adb_tunnel.c',
#   'src/file_pusher.c',

feature_test_macros = [
    '-D_GNU_SOURCE',
    '-D_POSIX_C_SOURCE=200809L',
    '-D_XOPEN_SOURCE=700',
]

conf = configuration_data()

if host_machine.system() == 'windows'
    windows = import('windows')
    src += [
        'src/util/command.c',
        'src/sys/win/file.c',
        'src/sys/win/process.c',
        windows.compile_resources('scrcpy-windows.rc'),
    ]
    feature_test_macros += [
        '-D_WIN32_WINNT=0x0600',
        '-DWINVER=0x0600',
    ]
else
    src += [
        'src/sys/unix/file.c',
        'src/sys/unix/process.c',
    ]
    if host_machine.system() == 'darwin'
        feature_test_macros += '-D_DARWIN_C_SOURCE'
    endif
endif


add_project_arguments(feature_test_macros, language: 'c')

v4l2_support = get_option('v4l2') and host_machine.system() == 'linux'
if v4l2_support
    src += [ 'src/v4l2_sink.c' ]
endif

# USB/libusb support removed — AOA and OTG modes are no longer available.
# UHID input (keyboard_uhid, mouse_uhid, gamepad_uhid) works over the
# control socket and does NOT require libusb; it is always included.

cc = meson.get_compiler('c')

static = get_option('static')

dependencies = [
    dependency('libavformat', version: '>= 57.33', static: static),
    dependency('libavcodec', version: '>= 57.37', static: static),
    dependency('libavutil', static: static),
    dependency('libswresample', static: static),
    dependency('sdl3', version: '>= 3.2.0', static: static),
]

if v4l2_support
    dependencies += dependency('libavdevice', static: static)
endif

# Removed:
#   dependency('libusb-1.0', static: static)

if host_machine.system() == 'windows'
    dependencies += cc.find_library('mingw32')
    dependencies += cc.find_library('ws2_32')
endif

check_functions = [
    'strdup',
    'asprintf',
    'vasprintf',
    'nrand48',
    'jrand48',
    'reallocarray',
]

foreach f : check_functions
  if cc.has_function(f)
    define = 'HAVE_' + f.underscorify().to_upper()
    conf.set(define, true)
  endif
endforeach

conf.set('HAVE_SOCK_CLOEXEC', host_machine.system() != 'windows' and
                              cc.has_header_symbol('sys/socket.h', 'SOCK_CLOEXEC'))

conf.set_quoted('SCRCPY_VERSION', meson.project_version())
conf.set_quoted('PREFIX', get_option('prefix'))
conf.set('PORTABLE', get_option('portable'))

# Default port for direct TCP connection to device
conf.set('DEFAULT_DEVICE_PORT', '27183')

conf.set('SERVER_DEBUGGER', get_option('server_debugger'))
conf.set('HAVE_V4L2', v4l2_support)

# HAVE_USB is no longer defined — all #ifdef HAVE_USB blocks become dead code
# and should be removed from source files.

configure_file(configuration: conf, output: 'config.h')

src_dir = include_directories('src')

executable('scrcpy', src,
           dependencies: dependencies,
           include_directories: src_dir,
           install: true,
           c_args: [])

datadir = get_option('datadir')
