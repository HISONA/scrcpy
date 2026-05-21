package com.genymobile.scrcpy;

import com.genymobile.scrcpy.audio.AudioCapture;
import com.genymobile.scrcpy.audio.AudioCodec;
import com.genymobile.scrcpy.audio.AudioDirectCapture;
import com.genymobile.scrcpy.audio.AudioEncoder;
import com.genymobile.scrcpy.audio.AudioPlaybackCapture;
import com.genymobile.scrcpy.audio.AudioRawRecorder;
import com.genymobile.scrcpy.audio.AudioSource;
import com.genymobile.scrcpy.control.ControlChannel;
import com.genymobile.scrcpy.control.Controller;
import com.genymobile.scrcpy.device.DesktopConnection;
import com.genymobile.scrcpy.device.Device;
import com.genymobile.scrcpy.device.Streamer;
import com.genymobile.scrcpy.model.ConfigurationException;
import com.genymobile.scrcpy.model.NewDisplay;
import com.genymobile.scrcpy.opengl.OpenGLRunner;
import com.genymobile.scrcpy.util.Ln;
import com.genymobile.scrcpy.util.LogUtils;
import com.genymobile.scrcpy.video.CameraCapture;
import com.genymobile.scrcpy.video.NewDisplayCapture;
import com.genymobile.scrcpy.video.ScreenCapture;
import com.genymobile.scrcpy.video.SurfaceCapture;
import com.genymobile.scrcpy.video.SurfaceEncoder;
import com.genymobile.scrcpy.video.VideoSource;

import android.annotation.SuppressLint;
import android.os.Build;
import android.os.Looper;
import android.system.Os;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

public final class Server {

    public static final String SERVER_PATH;

    static {
        String[] classPaths = System.getProperty("java.class.path").split(File.pathSeparator);
        SERVER_PATH = classPaths[0];
    }

    /**
     * Tracks completion of async processors (video encoder, audio encoder, controller).
     *
     * When all processors are done or a fatal error occurs, the Looper is quit
     * so that scrcpySession() returns and the server can accept a new connection.
     *
     * When any processor ends (typically because the client disconnected):
     *   1. connection.shutdown() — unblocks Controller's blocking read()
     *   2. stop all other processors — unblocks SurfaceEncoder's
     *      dequeueOutputBuffer(-1) via EOS signal, and AudioEncoder's
     *      AudioRecord.read() via capture release
     */
    private static class Completion {
        private int running;
        private boolean fatalError;
        private final DesktopConnection connection;
        private final List<AsyncProcessor> asyncProcessors;

        Completion(int running, DesktopConnection connection, List<AsyncProcessor> asyncProcessors) {
            this.running = running;
            this.connection = connection;
            this.asyncProcessors = asyncProcessors;
        }

        synchronized void addCompleted(boolean fatalError) {
            --running;
            if (fatalError) {
                this.fatalError = true;
            }

            // Shut down the connection to unblock Controller's read()
            if (connection != null) {
                try {
                    connection.shutdown();
                } catch (Exception e) {
                    Ln.d("Connection shutdown during completion: " + e.getMessage());
                }
            }

            // Stop all other processors to unblock their blocking calls:
            //  - SurfaceEncoder: dequeueOutputBuffer(-1) → signalEndOfInputStream → EOS
            //  - AudioEncoder: AudioRecord.read() → capture.stop()/release()
            for (AsyncProcessor processor : asyncProcessors) {
                processor.stop();
            }

            if (running == 0 || this.fatalError) {
                Looper.getMainLooper().quitSafely();
            }
        }

        synchronized boolean hasFatalError() {
            return fatalError;
        }
    }

    private Server() {
        // not instantiable
    }

    /**
     * Run a single mirroring session.
     *
     * This method opens a DesktopConnection (TCP), starts the encoders and
     * controller, runs the Looper until the connection drops or a fatal error
     * occurs, then cleans up all resources.
     *
     * @return {@code true} if the session ended normally (reconnect is safe),
     *         {@code false} if a fatal/configuration error occurred (should not retry).
     */
    private static boolean scrcpySession(Options options) throws ConfigurationException {
        if (Build.VERSION.SDK_INT < AndroidVersions.API_31_ANDROID_12 && options.getVideoSource() == VideoSource.CAMERA) {
            Ln.e("Camera mirroring is not supported before Android 12");
            throw new ConfigurationException("Camera mirroring is not supported");
        }

        if (Build.VERSION.SDK_INT < AndroidVersions.API_29_ANDROID_10) {
            if (options.getNewDisplay() != null) {
                Ln.e("New virtual display is not supported before Android 10");
                throw new ConfigurationException("New virtual display is not supported");
            }
            if (options.getDisplayImePolicy() != -1) {
                Ln.e("Display IME policy is not supported before Android 10");
                throw new ConfigurationException("Display IME policy is not supported");
            }
        }

        // CleanUp is handled once in the outer loop, not per session
        CleanUp cleanUp = null;
        if (options.getCleanup()) {
            cleanUp = CleanUp.start(options);
        }

        int scid = options.getScid();
        boolean tunnelForward = options.isTunnelForward();
        boolean control = options.getControl();
        boolean video = options.getVideo();
        boolean audio = options.getAudio();
        boolean sendDummyByte = options.getSendDummyByte();

        Workarounds.apply();

        List<AsyncProcessor> asyncProcessors = new ArrayList<>();
        final boolean[] sessionFatalError = {false};

        try {
            Ln.i("Waiting for client connection...");
            DesktopConnection connection = DesktopConnection.open(scid, tunnelForward, video, audio, control, sendDummyByte);
            try {
                Ln.i("Client connected");

                if (options.getSendDeviceMeta()) {
                    connection.sendDeviceMeta(Device.getDeviceName());
                }

                Controller controller = null;

                if (control) {
                    ControlChannel controlChannel = connection.getControlChannel();
                    controller = new Controller(controlChannel, cleanUp, options);
                    asyncProcessors.add(controller);
                }

                if (audio) {
                    AudioCodec audioCodec = options.getAudioCodec();
                    AudioSource audioSource = options.getAudioSource();
                    AudioCapture audioCapture;
                    if (audioSource.isDirect()) {
                        audioCapture = new AudioDirectCapture(audioSource);
                    } else {
                        audioCapture = new AudioPlaybackCapture(options.getAudioDup());
                    }

                    Streamer audioStreamer = new Streamer(connection.getAudioOutputStream(), audioCodec, options.getSendStreamMeta(), options.getSendFrameMeta());
                    AsyncProcessor audioRecorder;
                    if (audioCodec == AudioCodec.RAW) {
                        audioRecorder = new AudioRawRecorder(audioCapture, audioStreamer);
                    } else {
                        audioRecorder = new AudioEncoder(audioCapture, audioStreamer, options);
                    }
                    asyncProcessors.add(audioRecorder);
                }

                if (video) {
                    Streamer videoStreamer = new Streamer(connection.getVideoOutputStream(), options.getVideoCodec(), options.getSendStreamMeta(),
                            options.getSendFrameMeta());
                    SurfaceCapture surfaceCapture;
                    if (options.getVideoSource() == VideoSource.DISPLAY) {
                        NewDisplay newDisplay = options.getNewDisplay();
                        if (newDisplay != null) {
                            surfaceCapture = new NewDisplayCapture(controller, options);
                        } else {
                            assert options.getDisplayId() != Device.DISPLAY_ID_NONE;
                            surfaceCapture = new ScreenCapture(controller, options);
                        }
                    } else {
                        surfaceCapture = new CameraCapture(options);
                    }
                    SurfaceEncoder surfaceEncoder = new SurfaceEncoder(surfaceCapture, videoStreamer, options);
                    asyncProcessors.add(surfaceEncoder);

                    if (controller != null) {
                        controller.setSurfaceCapture(surfaceCapture);
                    }
                }

                final Completion completion = new Completion(asyncProcessors.size(), connection, asyncProcessors);
                for (AsyncProcessor asyncProcessor : asyncProcessors) {
                    asyncProcessor.start((fatalError) -> {
                        completion.addCompleted(fatalError);
                    });
                }

                // Run until all processors complete or a fatal error occurs.
                // The Looper is quit by the Completion callback.
                Looper.loop();

                sessionFatalError[0] = completion.hasFatalError();

            } finally {
                // Stop all async processors
                for (AsyncProcessor asyncProcessor : asyncProcessors) {
                    asyncProcessor.stop();
                }

                connection.shutdown();

                try {
                    if (cleanUp != null) {
                        cleanUp.interrupt();
                    }
                    for (AsyncProcessor asyncProcessor : asyncProcessors) {
                        asyncProcessor.join();
                    }

                    OpenGLRunner.shutdown();
                } catch (InterruptedException e) {
                    // ignore
                }

                connection.close();
            }
        } catch (IOException e) {
            // Connection failed or broken pipe during streaming.
            // This is expected when the client disconnects.
            Ln.w("Session ended: " + e.getMessage());
            return true; // safe to reconnect
        }

        // If a fatal error occurred in an async processor, do not reconnect
        if (sessionFatalError[0]) {
            Ln.e("Fatal error in session, will not reconnect");
            return false;
        }

        return true; // normal end, safe to reconnect
    }

    /**
     * Prepare the main Looper. Must be called once before the first Looper.loop().
     *
     * Android only allows one main looper per thread, so on reconnect we need
     * to re-prepare it because quitSafely() disposes the looper.
     */
    private static void prepareMainLooper() {
        // Like Looper.prepareMainLooper(), but with quitAllowed set to true
        Looper.prepare();
        synchronized (Looper.class) {
            try {
                @SuppressLint("DiscouragedPrivateApi")
                Field field = Looper.class.getDeclaredField("sMainLooper");
                field.setAccessible(true);
                field.set(null, Looper.myLooper());
            } catch (ReflectiveOperationException e) {
                throw new AssertionError(e);
            }
        }
    }

    /**
     * Re-prepare the Looper for a new session.
     *
     * After Looper.loop() returns (due to quitSafely()), the current Looper
     * is dead. We must create a new one for the next session.
     */
    private static void resetMainLooper() {
        // Clear the current thread's looper so we can prepare a new one
        try {
            @SuppressLint("DiscouragedPrivateApi")
            Field threadLocalField = Looper.class.getDeclaredField("sThreadLocal");
            threadLocalField.setAccessible(true);
            ThreadLocal<?> threadLocal = (ThreadLocal<?>) threadLocalField.get(null);
            if (threadLocal != null) {
                threadLocal.remove();
            }
        } catch (ReflectiveOperationException e) {
            Ln.w("Could not reset thread-local Looper", e);
        }
        prepareMainLooper();
    }

    public static void main(String... args) {
        int status = 0;
        try {
            internalMain(args);
        } catch (Throwable t) {
            Ln.e(t.getMessage(), t);
            status = 1;
        } finally {
            System.exit(status);
        }
    }

    private static void internalMain(String... args) throws Exception {
        Thread.UncaughtExceptionHandler defaultHandler = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            Ln.e("Exception on thread " + t, e);
            if (defaultHandler != null) {
                defaultHandler.uncaughtException(t, e);
            }
        });

        dropRootPrivileges();

        prepareMainLooper();

        Options options = Options.parse(args);

        Ln.disableSystemStreams();
        Ln.initLogLevel(options.getLogLevel());

        Ln.i("Device: [" + Build.MANUFACTURER + "] " + Build.BRAND + " " + Build.MODEL + " (Android " + Build.VERSION.RELEASE + ")");

        if (options.getList()) {
            if (options.getCleanup()) {
                CleanUp.unlinkSelf();
            }

            if (options.getListEncoders()) {
                Ln.i(LogUtils.buildVideoEncoderListMessage());
                Ln.i(LogUtils.buildAudioEncoderListMessage());
            }
            if (options.getListDisplays()) {
                Ln.i(LogUtils.buildDisplayListMessage());
            }
            if (options.getListCameras() || options.getListCameraSizes()) {
                Workarounds.apply();
                Ln.i(LogUtils.buildCameraListMessage(options.getListCameraSizes()));
            }
            if (options.getListApps()) {
                Workarounds.apply();
                Ln.i("Processing Android apps... (this may take some time)");
                Ln.i(LogUtils.buildAppListMessage());
            }
            return;
        }

        // ---- Persistent server loop ----
        // Keep running and accept new connections after each session ends.
        // Only exit on fatal errors or ConfigurationException.

        long reconnectDelay = 1000; // ms

        while (true) {

            Ln.i("=== Session starting ===");

            try {
                boolean canReconnect = scrcpySession(options);
                if (!canReconnect) {
                    Ln.i("Session ended with fatal error, shutting down");
                    break;
                }
            } catch (ConfigurationException e) {
                // Configuration errors cannot be recovered by reconnecting
                Ln.e("Configuration error, shutting down");
                break;
            }

            Ln.i("Session ended, waiting for new connection...");

            // Reset the Looper for the next session
            resetMainLooper();

            // Small delay to avoid busy-loop if connections fail instantly
            try {
                Thread.sleep(reconnectDelay);
            } catch (InterruptedException e) {
                Ln.i("Interrupted during reconnect delay, shutting down");
                break;
            }
        }

        Ln.i("Server shutting down after session ... ");
    }

    @SuppressWarnings("deprecation")
    private static void dropRootPrivileges() {
        try {
            if (Os.getuid() == 0) {
                Os.setuid(2000);
            }
        } catch (Exception e) {
            Ln.w("Cannot set UID", e);
        }
    }
}

