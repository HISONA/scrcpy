package com.genymobile.scrcpy.util;

import com.genymobile.scrcpy.AndroidVersions;
import com.genymobile.scrcpy.BuildConfig;

import android.os.Build;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;

import java.io.FileDescriptor;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.SocketException;
import java.nio.ByteBuffer;
import java.util.Scanner;

public final class IO {
    private IO() {
        // not instantiable
    }

    // ---- FileDescriptor-based methods (retained for non-socket usages like UHID) ----

    private static int write(FileDescriptor fd, ByteBuffer from) throws IOException {
        while (true) {
            try {
                return Os.write(fd, from);
            } catch (ErrnoException e) {
                if (e.errno != OsConstants.EINTR) {
                    throw new IOException(e);
                }
            }
        }
    }

    public static void writeFully(FileDescriptor fd, ByteBuffer from) throws IOException {
        if (Build.VERSION.SDK_INT >= AndroidVersions.API_23_ANDROID_6_0) {
            while (from.hasRemaining()) {
                write(fd, from);
            }
        } else {
            int position = from.position();
            int remaining = from.remaining();
            while (remaining > 0) {
                int w = write(fd, from);
                if (BuildConfig.DEBUG && w < 0) {
                    throw new AssertionError("Os.write() returned a negative value (" + w + ")");
                }
                remaining -= w;
                position += w;
                from.position(position);
            }
        }
    }

    public static void writeFully(FileDescriptor fd, byte[] buffer, int offset, int len) throws IOException {
        writeFully(fd, ByteBuffer.wrap(buffer, offset, len));
    }

    // ---- OutputStream-based methods (for TCP Socket) ----

    public static void writeFully(OutputStream os, ByteBuffer from) throws IOException {
        if (from.hasArray()) {
            os.write(from.array(), from.arrayOffset() + from.position(), from.remaining());
            from.position(from.limit());
        } else {
            byte[] tmp = new byte[from.remaining()];
            from.get(tmp);
            os.write(tmp);
        }
        os.flush();
    }

    public static void writeFully(OutputStream os, byte[] buffer, int offset, int len) throws IOException {
        os.write(buffer, offset, len);
        os.flush();
    }

    public static String toString(InputStream inputStream) {
        StringBuilder builder = new StringBuilder();
        Scanner scanner = new Scanner(inputStream);
        while (scanner.hasNextLine()) {
            builder.append(scanner.nextLine()).append('\n');
        }
        return builder.toString();
    }

    public static boolean isBrokenPipe(IOException e) {
        Throwable cause = e.getCause();
        if (cause instanceof ErrnoException && ((ErrnoException) cause).errno == OsConstants.EPIPE) {
            return true;
        }
        // Handle TCP socket errors
        if (e instanceof SocketException) {
            String msg = e.getMessage();
            return msg != null && (msg.contains("Broken pipe") || msg.contains("Connection reset"));
        }
        return false;
    }

    public static boolean isBrokenPipe(Exception e) {
        return e instanceof IOException && isBrokenPipe((IOException) e);
    }
}
