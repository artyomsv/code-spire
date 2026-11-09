package dev.codespire.modelproxy;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Hand-written HTTP on plain sockets, for what an HTTP client refuses to send or a JDK server refuses to do. */
final class RawSockets {

    /** What {@link #statusOf} returns when the server closed the connection without answering. */
    static final int CLOSED = -1;

    private RawSockets() {
    }

    /** Sends a hand-written request head and returns the status, or {@link #CLOSED}. */
    static int rawStatus(ModelProxy proxy, String head) throws IOException {
        try (Socket socket = new Socket("127.0.0.1", proxy.port())) {
            socket.setSoTimeout(10_000);
            write(socket, head + "\r\n");
            return statusOf(socket);
        }
    }

    static void write(Socket socket, String text) throws IOException {
        socket.getOutputStream().write(text.getBytes(StandardCharsets.ISO_8859_1));
        socket.getOutputStream().flush();
    }

    /** The status of the answer on {@code socket}, or {@link #CLOSED} when it closed without one. */
    static int statusOf(Socket socket) throws IOException {
        BufferedReader reader = new BufferedReader(
                new InputStreamReader(socket.getInputStream(), StandardCharsets.ISO_8859_1));
        String statusLine;
        try {
            statusLine = reader.readLine();
        } catch (SocketException reset) {
            return CLOSED;
        }
        return statusLine == null ? CLOSED : Integer.parseInt(statusLine.split(" ")[1]);
    }

    /**
     * Whether the server closes {@code socket} within {@code seconds}, sending nothing. A server still waiting is a
     * read timeout, which is the failure this exists to see.
     */
    static boolean closesWithin(Socket socket, int seconds) throws IOException {
        socket.setSoTimeout(seconds * 1000);
        try {
            return socket.getInputStream().read() == -1;
        } catch (SocketTimeoutException stillOpen) {
            return false;
        } catch (SocketException reset) {
            return true;
        }
    }

    /** Reads one server-sent event: everything up to and including the first blank line. */
    static String readEvent(InputStream body) {
        try {
            ByteArrayOutputStream event = new ByteArrayOutputStream();
            for (int next = body.read(); next != -1; next = body.read()) {
                event.write(next);
                if (event.toString(StandardCharsets.UTF_8).endsWith("\n\n")) {
                    break;
                }
            }
            return event.toString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * A hand-written vendor: answers one connection with a chunked 200 holding one event, waits until the agent has
     * read that event, then resets the connection (linger 0 sends an RST).
     */
    static void oneEventThenReset(ServerSocket server, CountDownLatch firstEventRead) {
        try (Socket socket = server.accept()) {
            if (!readHead(socket.getInputStream())) {
                return;
            }
            OutputStream out = socket.getOutputStream();
            out.write(("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nTransfer-Encoding: chunked\r\n\r\n"
                    + "e\r\ndata: TEST-1\n\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
            out.flush();
            firstEventRead.await(10, TimeUnit.SECONDS);
            socket.setSoLinger(true, 0);
        } catch (IOException closed) {
            // The test is over or the proxy hung up; either way there is nothing left to serve.
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    /** Accepts each connection, reads the request head, and resets it (linger 0 sends an RST). */
    static void resetEveryConnection(ServerSocket server) {
        while (!server.isClosed()) {
            try (Socket socket = server.accept()) {
                socket.setSoLinger(true, 0);
                socket.getInputStream().read(new byte[1024]);
            } catch (IOException closed) {
                return;
            }
        }
    }

    /** Reads a request head up to its blank line; false when the connection ended first. */
    private static boolean readHead(InputStream in) throws IOException {
        ByteArrayOutputStream head = new ByteArrayOutputStream();
        while (!head.toString(StandardCharsets.ISO_8859_1).endsWith("\r\n\r\n")) {
            int next = in.read();
            if (next == -1) {
                return false;
            }
            head.write(next);
        }
        return true;
    }
}
