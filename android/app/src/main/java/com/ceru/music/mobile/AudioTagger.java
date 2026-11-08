package com.ceru.music.mobile;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Rewrites FLAC / MP3 files so the cover art and lyrics live inside the audio file's own
 * metadata block. Pure Java on purpose: no Android APIs, so it can be unit-tested with javac.
 */
final class AudioTagger {

    private static final int FLAC_STREAMINFO = 0;
    private static final int FLAC_PADDING = 1;
    private static final int FLAC_VORBIS_COMMENT = 4;
    private static final int FLAC_PICTURE = 6;

    private AudioTagger() {}

    static final class Meta {
        final String title;
        final String artist;
        final String album;
        final String lyrics;
        final byte[] cover;

        Meta(String title, String artist, String album, String lyrics, byte[] cover) {
            this.title = nullToEmpty(title).trim();
            this.artist = nullToEmpty(artist).trim();
            this.album = nullToEmpty(album).trim();
            this.lyrics = nullToEmpty(lyrics);
            this.cover = cover;
        }
    }

    /** True when we know how to embed tags for this extension. */
    static boolean supports(String extension) {
        String ext = nullToEmpty(extension).toLowerCase(Locale.ROOT);
        return "flac".equals(ext) || "mp3".equals(ext);
    }

    /**
     * Writes {@code source} into {@code target} with the given metadata embedded.
     * Unsupported formats are copied unchanged so callers can stay branch-free.
     */
    static void embed(File source, File target, String extension, Meta meta) throws IOException {
        String ext = nullToEmpty(extension).toLowerCase(Locale.ROOT);
        boolean wanted = meta.cover != null || !meta.lyrics.isEmpty()
                || !meta.title.isEmpty() || !meta.artist.isEmpty() || !meta.album.isEmpty();
        if (!supports(ext) || !wanted) {
            copyFile(source, target);
            return;
        }
        InputStream in = new FileInputStream(source);
        try {
            OutputStream out = new FileOutputStream(target);
            try {
                if ("flac".equals(ext)) writeFlac(in, out, meta);
                else writeMp3(in, out, meta);
            } finally {
                out.close();
            }
        } finally {
            in.close();
        }
    }

    // ------------------------------------------------------------------ FLAC

    private static void writeFlac(InputStream in, OutputStream out, Meta meta) throws IOException {
        byte[] magic = new byte[4];
        readFully(in, magic, 4);
        if (magic[0] != 'f' || magic[1] != 'L' || magic[2] != 'a' || magic[3] != 'C') {
            throw new IOException("不是有效的 FLAC 文件");
        }
        List<Integer> types = new ArrayList<>();
        List<byte[]> blocks = new ArrayList<>();
        boolean last = false;
        while (!last) {
            byte[] header = new byte[4];
            readFully(in, header, 4);
            last = (header[0] & 0x80) != 0;
            int type = header[0] & 0x7F;
            int length = ((header[1] & 0xFF) << 16) | ((header[2] & 0xFF) << 8) | (header[3] & 0xFF);
            if (length < 0 || length > 16 * 1024 * 1024) throw new IOException("FLAC 元数据块长度异常");
            byte[] data = new byte[length];
            readFully(in, data, length);
            types.add(type);
            blocks.add(data);
        }

        String vendor = "Ceru Music";
        List<String> comments = new ArrayList<>();
        for (int i = 0; i < types.size(); i++) {
            if (types.get(i) != FLAC_VORBIS_COMMENT) continue;
            Vorbis parsed = parseVorbis(blocks.get(i));
            if (!parsed.vendor.isEmpty()) vendor = parsed.vendor;
            comments.addAll(parsed.entries);
        }

        byte[] commentBlock = buildVorbis(vendor, mergeComments(comments, meta));
        byte[] pictureBlock = meta.cover == null ? null : buildPicture(meta.cover);

        List<Integer> outTypes = new ArrayList<>();
        List<byte[]> outBlocks = new ArrayList<>();
        boolean picturePlaced = false;
        boolean commentPlaced = false;
        for (int i = 0; i < types.size(); i++) {
            int type = types.get(i);
            if (type == FLAC_VORBIS_COMMENT) {
                if (!commentPlaced) {
                    outTypes.add(FLAC_VORBIS_COMMENT);
                    outBlocks.add(commentBlock);
                    commentPlaced = true;
                }
                continue;
            }
            // The old picture block is replaced by the fresh cover below.
            if (type == FLAC_PICTURE) continue;
            // Padding stays last-ish, so the cover goes in front of it.
            if (type == FLAC_PADDING && pictureBlock != null && !picturePlaced) {
                outTypes.add(FLAC_PICTURE);
                outBlocks.add(pictureBlock);
                picturePlaced = true;
            }
            outTypes.add(type);
            outBlocks.add(blocks.get(i));
        }
        if (!commentPlaced) {
            // STREAMINFO must stay first, so the comment goes right after it.
            outTypes.add(1, FLAC_VORBIS_COMMENT);
            outBlocks.add(1, commentBlock);
        }
        if (pictureBlock != null && !picturePlaced) {
            outTypes.add(FLAC_PICTURE);
            outBlocks.add(pictureBlock);
        }

        out.write(magic, 0, 4);
        for (int i = 0; i < outBlocks.size(); i++) {
            byte[] data = outBlocks.get(i);
            boolean isLast = i == outBlocks.size() - 1;
            int type = outTypes.get(i);
            byte[] header = new byte[4];
            header[0] = (byte) ((isLast ? 0x80 : 0x00) | (type & 0x7F));
            header[1] = (byte) ((data.length >>> 16) & 0xFF);
            header[2] = (byte) ((data.length >>> 8) & 0xFF);
            header[3] = (byte) (data.length & 0xFF);
            out.write(header, 0, 4);
            out.write(data, 0, data.length);
        }
        copyStream(in, out);
    }

    private static final class Vorbis {
        String vendor = "";
        final List<String> entries = new ArrayList<>();
    }

    private static Vorbis parseVorbis(byte[] data) {
        Vorbis vorbis = new Vorbis();
        if (data.length < 8) return vorbis;
        int pos = 0;
        int vendorLength = readIntLE(data, pos);
        pos += 4;
        if (vendorLength < 0 || pos + vendorLength > data.length) return vorbis;
        vorbis.vendor = new String(data, pos, vendorLength, StandardCharsets.UTF_8);
        pos += vendorLength;
        if (pos + 4 > data.length) return vorbis;
        int count = readIntLE(data, pos);
        pos += 4;
        for (int i = 0; i < count && pos + 4 <= data.length; i++) {
            int length = readIntLE(data, pos);
            pos += 4;
            if (length < 0 || pos + length > data.length) break;
            vorbis.entries.add(new String(data, pos, length, StandardCharsets.UTF_8));
            pos += length;
        }
        return vorbis;
    }

    private static List<String> mergeComments(List<String> existing, Meta meta) {
        Set<String> replaced = new HashSet<>();
        replaced.add("title");
        replaced.add("artist");
        replaced.add("album");
        replaced.add("lyrics");
        replaced.add("unsyncedlyrics");
        replaced.add("syncedlyrics");
        List<String> merged = new ArrayList<>();
        for (String entry : existing) {
            int eq = entry.indexOf('=');
            if (eq <= 0) continue;
            String key = entry.substring(0, eq).toLowerCase(Locale.ROOT);
            if (replaced.contains(key)) continue;
            merged.add(entry);
        }
        addPair(merged, "TITLE", meta.title);
        addPair(merged, "ARTIST", meta.artist);
        addPair(merged, "ALBUM", meta.album);
        if (!meta.lyrics.isEmpty()) {
            merged.add("LYRICS=" + meta.lyrics);
            merged.add("SYNCEDLYRICS=" + meta.lyrics);
        }
        return merged;
    }

    private static void addPair(List<String> into, String key, String value) {
        if (value != null && !value.isEmpty()) into.add(key + "=" + value);
    }

    private static byte[] buildVorbis(String vendor, List<String> entries) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] vendorBytes = vendor.getBytes(StandardCharsets.UTF_8);
        writeIntLE(out, vendorBytes.length);
        out.write(vendorBytes, 0, vendorBytes.length);
        writeIntLE(out, entries.size());
        for (String entry : entries) {
            byte[] bytes = entry.getBytes(StandardCharsets.UTF_8);
            writeIntLE(out, bytes.length);
            out.write(bytes, 0, bytes.length);
        }
        return out.toByteArray();
    }

    private static byte[] buildPicture(byte[] cover) throws IOException {
        String mime = isPng(cover) ? "image/png" : "image/jpeg";
        int[] size = imageSize(cover);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeIntBE(out, 3); // front cover
        byte[] mimeBytes = mime.getBytes(StandardCharsets.US_ASCII);
        writeIntBE(out, mimeBytes.length);
        out.write(mimeBytes, 0, mimeBytes.length);
        byte[] description = new byte[0];
        writeIntBE(out, description.length);
        out.write(description, 0, description.length);
        writeIntBE(out, size[0]);
        writeIntBE(out, size[1]);
        writeIntBE(out, 24);
        writeIntBE(out, 0);
        writeIntBE(out, cover.length);
        out.write(cover, 0, cover.length);
        return out.toByteArray();
    }

    // ------------------------------------------------------------------- MP3

    private static void writeMp3(InputStream in, OutputStream out, Meta meta) throws IOException {
        byte[] head = new byte[10];
        int read = readUpTo(in, head, 10);
        ByteArrayOutputStream pending = new ByteArrayOutputStream();
        boolean tagged = read == 10 && head[0] == 'I' && head[1] == 'D' && head[2] == '3';
        if (tagged) {
            int flags = head[5] & 0xFF;
            int size = ((head[6] & 0x7F) << 21) | ((head[7] & 0x7F) << 14)
                    | ((head[8] & 0x7F) << 7) | (head[9] & 0x7F);
            long total = 10L + size + (((flags & 0x10) != 0) ? 10L : 0L);
            skipFully(in, total - 10L);
        } else {
            pending.write(head, 0, read);
        }

        ByteArrayOutputStream frames = new ByteArrayOutputStream();
        writeTextFrame(frames, "TIT2", meta.title);
        writeTextFrame(frames, "TPE1", meta.artist);
        writeTextFrame(frames, "TALB", meta.album);
        if (!meta.lyrics.isEmpty()) frames.write(lyricsFrame(meta.lyrics), 0, lyricsFrame(meta.lyrics).length);
        if (meta.cover != null) {
            byte[] apic = apicFrame(meta.cover);
            frames.write(apic, 0, apic.length);
        }
        byte[] body = frames.toByteArray();
        byte[] header = new byte[10];
        header[0] = 'I';
        header[1] = 'D';
        header[2] = '3';
        header[3] = 3;
        header[4] = 0;
        header[5] = 0;
        int size = body.length;
        header[6] = (byte) ((size >>> 21) & 0x7F);
        header[7] = (byte) ((size >>> 14) & 0x7F);
        header[8] = (byte) ((size >>> 7) & 0x7F);
        header[9] = (byte) (size & 0x7F);
        out.write(header, 0, 10);
        out.write(body, 0, body.length);
        pending.writeTo(out);
        copyStream(in, out);
    }

    private static void writeTextFrame(ByteArrayOutputStream out, String id, String value) {
        if (value == null || value.isEmpty()) return;
        byte[] text = utf16(value);
        byte[] payload = new byte[text.length + 1];
        payload[0] = 1;
        System.arraycopy(text, 0, payload, 1, text.length);
        byte[] frame = frame(id, payload);
        out.write(frame, 0, frame.length);
    }

    private static byte[] lyricsFrame(String text) {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.write(1);
        body.write('u');
        body.write('n');
        body.write('d');
        byte[] description = utf16("");
        body.write(description, 0, description.length);
        body.write(0);
        body.write(0);
        byte[] content = utf16(text);
        body.write(content, 0, content.length);
        return frame("USLT", body.toByteArray());
    }

    private static byte[] apicFrame(byte[] cover) {
        String mime = isPng(cover) ? "image/png" : "image/jpeg";
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.write(0);
        byte[] mimeBytes = mime.getBytes(StandardCharsets.US_ASCII);
        body.write(mimeBytes, 0, mimeBytes.length);
        body.write(0);
        body.write(3);
        body.write(0);
        body.write(cover, 0, cover.length);
        return frame("APIC", body.toByteArray());
    }

    private static byte[] frame(String id, byte[] payload) {
        byte[] frame = new byte[10 + payload.length];
        byte[] idBytes = id.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(idBytes, 0, frame, 0, 4);
        int size = payload.length;
        frame[4] = (byte) ((size >>> 24) & 0xFF);
        frame[5] = (byte) ((size >>> 16) & 0xFF);
        frame[6] = (byte) ((size >>> 8) & 0xFF);
        frame[7] = (byte) (size & 0xFF);
        System.arraycopy(payload, 0, frame, 10, payload.length);
        return frame;
    }

    private static byte[] utf16(String value) {
        byte[] raw = value.getBytes(StandardCharsets.UTF_16LE);
        byte[] result = new byte[raw.length + 2];
        result[0] = (byte) 0xFF;
        result[1] = (byte) 0xFE;
        System.arraycopy(raw, 0, result, 2, raw.length);
        return result;
    }

    // --------------------------------------------------------------- helpers

    private static void writeIntLE(OutputStream out, int value) throws IOException {
        out.write(value & 0xFF);
        out.write((value >>> 8) & 0xFF);
        out.write((value >>> 16) & 0xFF);
        out.write((value >>> 24) & 0xFF);
    }

    private static void writeIntBE(OutputStream out, int value) throws IOException {
        out.write((value >>> 24) & 0xFF);
        out.write((value >>> 16) & 0xFF);
        out.write((value >>> 8) & 0xFF);
        out.write(value & 0xFF);
    }

    private static int readIntLE(byte[] data, int offset) {
        return (data[offset] & 0xFF) | ((data[offset + 1] & 0xFF) << 8)
                | ((data[offset + 2] & 0xFF) << 16) | ((data[offset + 3] & 0xFF) << 24);
    }

    private static boolean isPng(byte[] data) {
        return data.length > 8 && (data[0] & 0xFF) == 0x89 && data[1] == 'P' && data[2] == 'N' && data[3] == 'G';
    }

    private static int[] imageSize(byte[] data) {
        if (isPng(data) && data.length > 24) {
            int width = ((data[16] & 0xFF) << 24) | ((data[17] & 0xFF) << 16)
                    | ((data[18] & 0xFF) << 8) | (data[19] & 0xFF);
            int height = ((data[20] & 0xFF) << 24) | ((data[21] & 0xFF) << 16)
                    | ((data[22] & 0xFF) << 8) | (data[23] & 0xFF);
            return new int[]{width, height};
        }
        int index = 2;
        while (index + 9 < data.length) {
            if ((data[index] & 0xFF) != 0xFF) {
                index++;
                continue;
            }
            int marker = data[index + 1] & 0xFF;
            if (marker == 0xD8 || marker == 0xD9 || marker == 0x01
                    || (marker >= 0xD0 && marker <= 0xD7)) {
                index += 2;
                continue;
            }
            int length = ((data[index + 2] & 0xFF) << 8) | (data[index + 3] & 0xFF);
            boolean isSof = marker >= 0xC0 && marker <= 0xCF
                    && marker != 0xC4 && marker != 0xC8 && marker != 0xCC;
            if (isSof) {
                int height = ((data[index + 5] & 0xFF) << 8) | (data[index + 6] & 0xFF);
                int width = ((data[index + 7] & 0xFF) << 8) | (data[index + 8] & 0xFF);
                return new int[]{width, height};
            }
            index += 2 + length;
        }
        return new int[]{0, 0};
    }

    private static void readFully(InputStream in, byte[] buffer, int length) throws IOException {
        int offset = 0;
        while (offset < length) {
            int read = in.read(buffer, offset, length - offset);
            if (read < 0) throw new IOException("文件意外结束");
            offset += read;
        }
    }

    private static int readUpTo(InputStream in, byte[] buffer, int length) throws IOException {
        int offset = 0;
        while (offset < length) {
            int read = in.read(buffer, offset, length - offset);
            if (read < 0) break;
            offset += read;
        }
        return offset;
    }

    private static void skipFully(InputStream in, long count) throws IOException {
        long left = count;
        while (left > 0) {
            long skipped = in.skip(left);
            if (skipped <= 0) {
                if (in.read() < 0) throw new IOException("文件意外结束");
                skipped = 1;
            }
            left -= skipped;
        }
    }

    private static void copyStream(InputStream in, OutputStream out) throws IOException {
        byte[] buffer = new byte[64 * 1024];
        int read;
        while ((read = in.read(buffer)) > 0) {
            out.write(buffer, 0, read);
        }
    }

    private static void copyFile(File source, File target) throws IOException {
        InputStream in = new FileInputStream(source);
        try {
            OutputStream out = new FileOutputStream(target);
            try {
                copyStream(in, out);
            } finally {
                out.close();
            }
        } finally {
            in.close();
        }
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
