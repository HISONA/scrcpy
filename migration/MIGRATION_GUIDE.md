# scrcpy ADB + libusb 의존성 제거 — 전체 마이그레이션 가이드

## 개요

이 패치는 scrcpy에서 두 가지 외부 의존성을 완전히 제거합니다:

1. **ADB** — PC 클라이언트가 디바이스에 직접 TCP/IP로 연결
2. **libusb** — AOA (Android Open Accessory) 및 OTG 모드 제거

```
기존: PC scrcpy → ADB → USB/TCP → adbd → abstract socket → scrcpy-server
                → libusb → AOA HID (키보드/마우스/게임패드)

변경: PC scrcpy → TCP/IP (Wi-Fi) → scrcpy-server (TCP:27183)
                → UHID (컨트롤 소켓 경유, 키보드/마우스/게임패드)
```

UHID 입력 방식은 컨트롤 소켓을 통해 동작하므로 libusb가 불필요하며, 그대로 유지됩니다.

---

## 변경 파일 전체 목록

### Android 서버 (Java) — 5개 파일

| 파일 | 경로 | 변경 |
|------|------|------|
| `DesktopConnection.java` | `server/.../device/` | `LocalSocket` → `Socket`, `LocalServerSocket` → `ServerSocket` |
| `Streamer.java` | `server/.../device/` | `FileDescriptor` → `OutputStream` |
| `ControlChannel.java` | `server/.../control/` | 생성자 `LocalSocket` → `Socket` |
| `IO.java` | `server/.../util/` | `OutputStream` 오버로드 추가 |
| `Server.java` | `server/.../` | `getVideoFd()` → `getVideoOutputStream()` |

### PC 클라이언트 (C) — 7개 파일

| 파일 | 변경 유형 | 내용 |
|------|-----------|------|
| `server.h` | **재작성** | ADB tunnel 제거, `device_host`/`device_port` 추가 |
| `server.c` | **재작성** | ADB 관련 ~800줄 제거, 직접 TCP ~270줄 |
| `options.h` | **수정** | AOA enum 제거, ADB 옵션 제거, `device_host`/`device_port` 추가 |
| `main.c` | **수정** | OTG 분기 제거 |
| `version.c` | **수정** | libusb 버전 출력 제거 |
| `meson.build` | **재작성** | ADB/USB 소스 제거, libusb 의존성 제거 |
| `meson_options.txt` | **수정** | `usb` 옵션 제거 |

### 삭제 대상 파일 — 22개

**ADB 관련 (8개):**

| 파일 | 이유 |
|------|------|
| `app/src/adb/adb.c` | ADB CLI 래퍼 |
| `app/src/adb/adb.h` | 위 헤더 |
| `app/src/adb/adb_device.c` | ADB 디바이스 선택 |
| `app/src/adb/adb_device.h` | 위 헤더 |
| `app/src/adb/adb_parser.c` | `adb devices` 파서 |
| `app/src/adb/adb_parser.h` | 위 헤더 |
| `app/src/adb/adb_tunnel.c` | ADB forward/reverse 터널 |
| `app/src/adb/adb_tunnel.h` | 위 헤더 |

**USB/libusb/AOA 관련 (12개):**

| 파일 | 이유 |
|------|------|
| `app/src/usb/usb.c` | libusb 초기화/디바이스 열거 |
| `app/src/usb/usb.h` | 위 헤더 |
| `app/src/usb/aoa_hid.c` | AOA HID 프로토콜 |
| `app/src/usb/aoa_hid.h` | 위 헤더 |
| `app/src/usb/keyboard_aoa.c` | AOA 키보드 |
| `app/src/usb/keyboard_aoa.h` | 위 헤더 |
| `app/src/usb/mouse_aoa.c` | AOA 마우스 |
| `app/src/usb/mouse_aoa.h` | 위 헤더 |
| `app/src/usb/gamepad_aoa.c` | AOA 게임패드 |
| `app/src/usb/gamepad_aoa.h` | 위 헤더 |
| `app/src/usb/scrcpy_otg.c` | OTG 전용 모드 |
| `app/src/usb/scrcpy_otg.h` | 위 헤더 |

**ADB 파일 전송 (2개):**

| 파일 | 이유 |
|------|------|
| `app/src/file_pusher.c` | `adb push` 기반 |
| `app/src/file_pusher.h` | 위 헤더 |

### 추가 수정 필요 (본 패치 미포함)

| 파일 | 수정 내용 |
|------|-----------|
| `app/src/cli.c` | `--device-host`, `--device-port` 추가, ADB/USB 옵션 파싱 제거 |
| `app/src/options.c` | 기본값 업데이트 |
| `app/src/scrcpy.c` | `#ifdef HAVE_USB` 블록 전부 제거, `file_pusher` 참조 제거 |
| `app/src/input_manager.c` | AOA 입력 모드 분기 제거 (있는 경우) |
| `app/src/events.h` | `SC_EVENT_AOA_OPEN_ERROR` 제거 |
| `app/tests/test_adb_parser.c` | 삭제 |

---

## 입력 방식 비교 (AOA 제거 후)

| 항목 | AOA (제거됨) | UHID (유지됨) | SDK (유지됨) |
|------|-------------|--------------|-------------|
| 전송 경로 | USB (libusb) | 컨트롤 소켓 (TCP) | 컨트롤 소켓 (TCP) |
| libusb 필요 | O | **X** | **X** |
| 동작 방식 | USB HID 디바이스 에뮬레이션 | 커널 UHID 가상 디바이스 | InputManager API |
| Android 버전 | 모든 버전 | 7.0+ | 모든 버전 |
| 물리 키보드 동작 | O | O | 제한적 |
| 게임패드 | O | O | X |

UHID가 AOA의 상위 호환이므로, libusb 제거로 인한 기능 손실은 사실상 없습니다.
단, OTG 전용 모드(`--otg`)는 완전히 제거됩니다 (USB 직접 연결 없이는 불가능).

---

## meson.build 변경 요약

```diff
 # 소스 파일
-    'src/adb/adb.c',
-    'src/adb/adb_device.c',
-    'src/adb/adb_parser.c',
-    'src/adb/adb_tunnel.c',
-    'src/file_pusher.c',

 # USB 소스 (조건부 → 완전 제거)
-if usb_support
-    src += [
-        'src/usb/aoa_hid.c',
-        'src/usb/gamepad_aoa.c',
-        'src/usb/keyboard_aoa.c',
-        'src/usb/mouse_aoa.c',
-        'src/usb/scrcpy_otg.c',
-        'src/usb/usb.c',
-    ]
-endif

 # 의존성
-if usb_support
-    dependencies += dependency('libusb-1.0', static: static)
-endif

 # 설정
-conf.set('HAVE_USB', usb_support)
```

남은 의존성: libavformat, libavcodec, libavutil, libswresample, SDL3, (선택) libavdevice

---

## options.h 변경 요약

```diff
 enum sc_keyboard_input_mode {
     SC_KEYBOARD_INPUT_MODE_AUTO,
-    SC_KEYBOARD_INPUT_MODE_UHID_OR_AOA,
     SC_KEYBOARD_INPUT_MODE_DISABLED,
     SC_KEYBOARD_INPUT_MODE_SDK,
     SC_KEYBOARD_INPUT_MODE_UHID,
-    SC_KEYBOARD_INPUT_MODE_AOA,
 };

 // sc_mouse_input_mode, sc_gamepad_input_mode 도 동일하게 AOA 제거

 struct scrcpy_options {
-    bool force_adb_forward;
-    bool tcpip;
-    const char *tcpip_dst;
-    bool select_usb;
-    bool select_tcpip;
-    bool kill_adb_on_close;
-#ifdef HAVE_USB
-    bool otg;
-#endif
+    const char *device_host;
+    uint16_t device_port;
 };
```

---

## 사용 방법

### 디바이스 준비 (1회)

```bash
# scrcpy-server.jar 배치 (USB, FTP, Termux 등)
adb push scrcpy-server /data/local/tmp/scrcpy-server.jar
```

### 디바이스에서 서버 시작

```bash
CLASSPATH=/data/local/tmp/scrcpy-server.jar \
  app_process / com.genymobile.scrcpy.Server 2.7 \
  tunnel_forward=true
```

### PC에서 연결

```bash
scrcpy --device-host=192.168.1.100
# 또는 포트 지정
scrcpy --device-host=192.168.1.100 --device-port=27183
```

---

## 보안 고려사항

ADB RSA 인증과 USB 물리 연결이 모두 제거되므로:

1. 서버를 `0.0.0.0`이 아닌 특정 인터페이스에 바인드
2. 방화벽으로 포트 27183 접근을 신뢰할 수 있는 IP로 제한
3. 필요 시 연결 초기에 공유 시크릿 검증 추가
4. 민감한 환경에서는 TLS 레이어 추가
