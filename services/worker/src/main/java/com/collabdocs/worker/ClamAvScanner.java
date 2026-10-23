package com.collabdocs.worker;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class ClamAvScanner {
    private final boolean enabled;
    private final String host;
    private final int port;

    public ClamAvScanner(@Value("${collabdocs.clamav.enabled:false}") boolean enabled,
                         @Value("${collabdocs.clamav.host:clamav}") String host,
                         @Value("${collabdocs.clamav.port:3310}") int port) {
        this.enabled = enabled;
        this.host = host;
        this.port = port;
    }

    public void scan(byte[] body) throws Exception {
        if (!enabled || body == null) return;
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), 5000);
            socket.setSoTimeout(20000);
            socket.getOutputStream().write("zINSTREAM\0".getBytes());
            int offset = 0;
            while (offset < body.length) {
                int length = Math.min(2048, body.length - offset);
                socket.getOutputStream().write(ByteBuffer.allocate(4).putInt(length).array());
                socket.getOutputStream().write(body, offset, length);
                offset += length;
            }
            socket.getOutputStream().write(new byte[4]);
            socket.getOutputStream().flush();
            ByteArrayOutputStream response = new ByteArrayOutputStream();
            InputStream input = socket.getInputStream();
            int read;
            while ((read = input.read()) >= 0) {
                if (read == 0) break;
                response.write(read);
            }
            String verdict = response.toString();
            if (verdict.contains("FOUND")) {
                throw new MalwareDetected();
            }
            if (!verdict.contains("OK")) {
                throw new IllegalStateException("scanner unavailable");
            }
        }
    }

    public static class MalwareDetected extends RuntimeException {
        public MalwareDetected() {
            super("malware detected");
        }
    }
}
