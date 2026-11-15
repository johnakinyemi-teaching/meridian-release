package ca.meridian.test;

import ca.meridian.api.ApiServer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * A real ApiServer on a free local port, and a client that writes the
 * request line onto a socket itself.
 *
 * The client is deliberately not java.net.http: the tests must send a raw
 * single quote, a semicolon and a comment marker in the query string
 * exactly as typed, which is what the old handlers read (they never
 * percent-decode), and no URL library may tidy that up on the way.
 */
final class Http implements AutoCloseable {

    /** One parsed response. Header names are lower case. */
    static final class Response {
        final int status;
        final String body;
        private final Map<String, String> headers;

        Response(int status, Map<String, String> headers, String body) {
            this.status = status;
            this.headers = headers;
            this.body = body;
        }

        String header(String lowerCaseName) {
            return headers.get(lowerCaseName);
        }
    }

    private final ApiServer server;
    private final int port;

    private Http(ApiServer server, int port) {
        this.server = server;
        this.port = port;
    }

    /** Starts the service against the given store file. */
    static Http serve(Store store) throws IOException {
        int port;
        try (ServerSocket probe = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            port = probe.getLocalPort();
        }
        ApiServer server = new ApiServer(port, store.path());
        server.start();
        return new Http(server, port);
    }

    Response get(String target) throws IOException {
        return send("GET", target);
    }

    /** Sends METHOD target HTTP/1.1 with no body and reads the whole reply. */
    Response send(String method, String target) throws IOException {
        String request = method + " " + target + " HTTP/1.1\r\n"
                + "Host: localhost\r\n"
                + "Content-Length: 0\r\n"
                + "Connection: close\r\n"
                + "\r\n";
        try (Socket socket = new Socket(InetAddress.getLoopbackAddress(), port)) {
            socket.setSoTimeout(30_000);
            OutputStream out = socket.getOutputStream();
            out.write(request.getBytes(StandardCharsets.ISO_8859_1));
            out.flush();
            return parse(readAll(socket.getInputStream()), method + " " + target);
        }
    }

    @Override
    public void close() {
        server.stop();
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int n;
        while ((n = in.read(chunk)) >= 0) {
            buf.write(chunk, 0, n);
        }
        return buf.toByteArray();
    }

    private static Response parse(byte[] raw, String what) throws IOException {
        int split = indexOfBlankLine(raw);
        if (split < 0) {
            throw new IOException("no header terminator in reply to " + what);
        }
        String[] lines = new String(raw, 0, split, StandardCharsets.ISO_8859_1).split("\r\n");
        // Status line: HTTP/1.1 200 OK
        int status = Integer.parseInt(lines[0].split(" ")[1]);
        Map<String, String> headers = new HashMap<>();
        for (int i = 1; i < lines.length; i++) {
            int colon = lines[i].indexOf(':');
            if (colon > 0) {
                headers.put(lines[i].substring(0, colon).trim().toLowerCase(Locale.ROOT),
                        lines[i].substring(colon + 1).trim());
            }
        }
        int bodyStart = split + 4;
        int bodyLength = raw.length - bodyStart;
        // The byte-for-byte comparisons rely on a counted body. Refuse
        // anything else rather than guess at it.
        if (headers.containsKey("transfer-encoding")) {
            throw new IOException("unexpected Transfer-Encoding in reply to " + what);
        }
        String declared = headers.get("content-length");
        if (declared != null && Integer.parseInt(declared) != bodyLength) {
            throw new IOException("Content-Length " + declared + " but " + bodyLength
                    + " body bytes in reply to " + what);
        }
        return new Response(status, headers,
                new String(raw, bodyStart, bodyLength, StandardCharsets.UTF_8));
    }

    private static int indexOfBlankLine(byte[] raw) {
        for (int i = 0; i + 3 < raw.length; i++) {
            if (raw[i] == '\r' && raw[i + 1] == '\n' && raw[i + 2] == '\r' && raw[i + 3] == '\n') {
                return i;
            }
        }
        return -1;
    }
}
