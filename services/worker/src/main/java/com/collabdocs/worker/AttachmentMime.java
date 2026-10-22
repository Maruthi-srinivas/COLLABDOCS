package com.collabdocs.worker;

public final class AttachmentMime {
    private AttachmentMime() {}

    public static String sniff(byte[] bytes) {
        if (bytes == null || bytes.length < 3) return null;
        if ((bytes[0] & 0xff) == 0xff && (bytes[1] & 0xff) == 0xd8 && (bytes[2] & 0xff) == 0xff) return "image/jpeg";
        if (bytes.length >= 8 && bytes[0] == (byte) 0x89 && bytes[1] == 0x50 && bytes[2] == 0x4e && bytes[3] == 0x47) return "image/png";
        if (bytes.length >= 6 && bytes[0] == 'G' && bytes[1] == 'I' && bytes[2] == 'F') return "image/gif";
        if (bytes.length >= 12 && bytes[0] == 'R' && bytes[8] == 'W' && bytes[9] == 'E') return "image/webp";
        return null;
    }
}
