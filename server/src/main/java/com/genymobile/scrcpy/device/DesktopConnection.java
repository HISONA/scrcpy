package com.genymobile.scrcpy.device;

import com.genymobile.scrcpy.control.ControlChannel;
import com.genymobile.scrcpy.util.Ln;
import com.genymobile.scrcpy.util.StringUtils;

import java.io.Closeable;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

public final class DesktopConnection implements Closeable {

    private static final int DEVICE_NAME_FIELD_LENGTH = 64;

    private static final int DEFAULT_PORT = 27183;

    private final Socket videoSocket;
    private final Socket audioSocket;
    private final Socket controlSocket;
    private final ControlChannel controlChannel;

    private DesktopConnection(Socket videoSocket, Socket audioSocket, Socket controlSocket) throws IOException {
        this.videoSocket = videoSocket;
        this.audioSocket = audioSocket;
        this.controlSocket = controlSocket;

        if (videoSocket != null) {
            videoSocket.setTcpNoDelay(true);
        }
        if (audioSocket != null) {
            audioSocket.setTcpNoDelay(true);
        }
        if (controlSocket != null) {
            controlSocket.setTcpNoDelay(true);
        }

        controlChannel = controlSocket != null ? new ControlChannel(controlSocket) : null;
    }

    private static Socket connect(String host, int port) throws IOException {
        Socket socket = new Socket();
        socket.connect(new InetSocketAddress(host, port), 5000);
        socket.setTcpNoDelay(true);
        return socket;
    }

    private static int getPort(int scid) {
        if (scid == -1) {
            return DEFAULT_PORT;
        }
        return DEFAULT_PORT + (scid & 0xFFFF);
    }

    public static DesktopConnection open(int scid, boolean tunnelForward, boolean video, boolean audio, boolean control, boolean sendDummyByte)
            throws IOException {
        int port = getPort(scid);

        Socket videoSocket = null;
        Socket audioSocket = null;
        Socket controlSocket = null;
        try {
            if (tunnelForward) {
                // Server listens on TCP port, PC client connects directly
                try (ServerSocket serverSocket = new ServerSocket()) {
                    serverSocket.setReuseAddress(true);
                    serverSocket.bind(new InetSocketAddress(InetAddress.getByName("0.0.0.0"), port));

                    if (video) {
                        videoSocket = serverSocket.accept();
                        videoSocket.setTcpNoDelay(true);
                        if (sendDummyByte) {
                            videoSocket.getOutputStream().write(0);
                            sendDummyByte = false;
                        }
                    }
                    if (audio) {
                        audioSocket = serverSocket.accept();
                        audioSocket.setTcpNoDelay(true);
                        if (sendDummyByte) {
                            audioSocket.getOutputStream().write(0);
                            sendDummyByte = false;
                        }
                    }
                    if (control) {
                        controlSocket = serverSocket.accept();
                        controlSocket.setTcpNoDelay(true);
                        if (sendDummyByte) {
                            controlSocket.getOutputStream().write(0);
                            sendDummyByte = false;
                        }
                    }
                }
            } else {
                // Reverse mode: server connects to the PC client
                String clientHost = System.getProperty("scrcpy.client_host", "127.0.0.1");
                if (video) {
                    videoSocket = connect(clientHost, port);
                }
                if (audio) {
                    audioSocket = connect(clientHost, port);
                }
                if (control) {
                    controlSocket = connect(clientHost, port);
                }
            }
        } catch (IOException | RuntimeException e) {
            if (videoSocket != null) {
                videoSocket.close();
            }
            if (audioSocket != null) {
                audioSocket.close();
            }
            if (controlSocket != null) {
                controlSocket.close();
            }
            throw e;
        }

        return new DesktopConnection(videoSocket, audioSocket, controlSocket);
    }

    private Socket getFirstSocket() {
        if (videoSocket != null) {
            return videoSocket;
        }
        if (audioSocket != null) {
            return audioSocket;
        }
        return controlSocket;
    }

    /**
     * Gracefully shut down the socket, ignoring ENOTCONN.
     *
     * When the peer (PC client) disconnects first, the socket transitions to
     * a not-connected state. Calling shutdownInput()/shutdownOutput() on such
     * a socket throws "ENOTCONN (Transport endpoint is not connected)".
     * This is expected during normal teardown and can be safely ignored.
     */
    private static void shutdownSocket(Socket socket) {
        try {
            socket.shutdownInput();
        } catch (IOException e) {
            if (!isExpectedShutdownError(e)) {
                Ln.w("shutdown(input) failed", e);
            }
        }
        try {
            socket.shutdownOutput();
        } catch (IOException e) {
            if (!isExpectedShutdownError(e)) {
                Ln.w("shutdown(output) failed", e);
            }
        }
    }

    /**
     * Check if the exception is expected during teardown:
     *   - ENOTCONN: peer already disconnected
     *   - "already shutdown": shutdown() called more than once
     *   - "Socket closed": socket was already closed
     */
    private static boolean isExpectedShutdownError(IOException e) {
        Throwable cause = e.getCause();
        if (cause instanceof android.system.ErrnoException) {
            return ((android.system.ErrnoException) cause).errno
                    == android.system.OsConstants.ENOTCONN;
        }
        String msg = e.getMessage();
        if (msg == null) {
            return false;
        }
        return msg.contains("ENOTCONN")
            || msg.contains("already shutdown")
            || msg.contains("Socket closed");
    }

    public void shutdown() {
        if (videoSocket != null) {
            shutdownSocket(videoSocket);
        }
        if (audioSocket != null) {
            shutdownSocket(audioSocket);
        }
        if (controlSocket != null) {
            shutdownSocket(controlSocket);
        }
    }

    public void close() throws IOException {
        if (videoSocket != null) {
            videoSocket.close();
        }
        if (audioSocket != null) {
            audioSocket.close();
        }
        if (controlSocket != null) {
            controlSocket.close();
        }
    }

    public void sendDeviceMeta(String deviceName) throws IOException {
        byte[] buffer = new byte[DEVICE_NAME_FIELD_LENGTH];

        byte[] deviceNameBytes = deviceName.getBytes(StandardCharsets.UTF_8);
        int len = StringUtils.getUtf8TruncationIndex(deviceNameBytes, DEVICE_NAME_FIELD_LENGTH - 1);
        System.arraycopy(deviceNameBytes, 0, buffer, 0, len);

        OutputStream out = getFirstSocket().getOutputStream();
        out.write(buffer);
        out.flush();
    }

    public OutputStream getVideoOutputStream() {
        try {
            return videoSocket != null ? videoSocket.getOutputStream() : null;
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public OutputStream getAudioOutputStream() {
        try {
            return audioSocket != null ? audioSocket.getOutputStream() : null;
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public ControlChannel getControlChannel() {
        return controlChannel;
    }
}
