package org.fluidmessages;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.ImageDecoder;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.net.Uri;
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader;
import com.tom_roush.pdfbox.pdmodel.PDDocument;
import com.tom_roush.pdfbox.pdmodel.PDDocumentInformation;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Prepares an attachment for sending: copies it into private storage, removes metadata when the
 * Privacy setting is on, and fits photos into a size limit. Metadata removal covers photos (EXIF,
 * XMP, GPS), MP4/3GP/WebM video and audio (container tags and location), MP3 ID3 tags, PDF document
 * information and XMP, and Office/OpenDocument properties. Other formats are sent unchanged.
 */
final class MediaSanitizer {
    static final long MAX_FILE = 25L * 1024 * 1024;
    private static final Set<String> OOXML = Set.of("docx", "docm", "dotx", "xlsx", "xlsm", "xltx", "pptx", "pptm", "potx");
    private static final Set<String> ODF = Set.of("odt", "ods", "odp", "odg");

    /** A ready-to-send copy. {@code cleaned} is false when metadata could not be removed from this type. */
    record Prepared(File file, String mime, String name, boolean cleaned) {
        byte[] bytes() throws IOException { return Files.readAllBytes(file.toPath()); }
        void delete() { file.delete(); }
    }

    private MediaSanitizer() {}

    static boolean enabled(Context context) {
        return context.getSharedPreferences("fluid", Context.MODE_PRIVATE).getBoolean("stripMetadata", true);
    }

    /**
     * @param limit largest result in bytes; photos are recompressed to fit, other files that exceed it fail
     */
    static Prepared prepare(Context context, Attachment attachment, long limit) throws IOException {
        Uri source = attachment.getUri();
        if (source == null) throw new IOException("This attachment isn’t on the phone yet.");
        boolean strip = enabled(context);
        String mime = attachment.getMime().toLowerCase(Locale.ROOT);
        String name = safeName(attachment.getName());
        String extension = extension(name);
        if (attachment.getKind() == AttachmentKind.PHOTO && !mime.equals("image/gif")) {
            return photo(context, source, name, mime, strip, limit);
        }
        File copy = copy(context, source, extension, Math.min(limit, MAX_FILE));
        if (!strip) return new Prepared(copy, mime, name, false);
        try {
            File cleaned = null;
            if (mime.startsWith("video/") || mime.startsWith("audio/mp4") || mime.equals("audio/aac")
                    || mime.equals("audio/3gpp") || mime.equals("audio/webm") || mime.equals("audio/ogg") && extension.equals("webm")) {
                cleaned = remux(context, copy, mime.contains("webm") ? MediaMuxer.OutputFormat.MUXER_OUTPUT_WEBM
                    : mime.contains("3gpp") ? MediaMuxer.OutputFormat.MUXER_OUTPUT_3GPP : MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            } else if (mime.equals("audio/mpeg") || extension.equals("mp3")) {
                cleaned = mp3(context, copy);
            } else if (mime.equals("application/pdf")) {
                cleaned = pdf(context, copy);
            } else if (OOXML.contains(extension)) {
                cleaned = office(context, copy, false);
            } else if (ODF.contains(extension)) {
                cleaned = office(context, copy, true);
            } else if (mime.equals("text/vcard") || mime.equals("text/x-vcard")) {
                return new Prepared(copy, mime, name, true);
            }
            if (cleaned == null) return new Prepared(copy, mime, name, false);
            copy.delete();
            if (cleaned.length() > limit) { cleaned.delete(); throw tooLarge(context, attachment, limit); }
            return new Prepared(cleaned, mime, name, true);
        } catch (IOException | RuntimeException | LinkageError error) {
            if (error instanceof IOException io && io.getMessage() != null && io.getMessage().startsWith("This ")) throw io;
            // A file Android or PDFBox can't parse is still sent; the caller tells the person it wasn't cleaned.
            return new Prepared(copy, mime, name, false);
        }
    }

    static IOException tooLarge(Context context, Attachment attachment, long limit) {
        return new IOException("This " + attachment.getKind().getLabel().toLowerCase(Locale.ROOT) + " is larger than "
            + android.text.format.Formatter.formatShortFileSize(context, limit) + ", the most this protocol can send.");
    }

    private static Prepared photo(Context context, Uri source, String name, String mime, boolean strip, long limit) throws IOException {
        if (!strip) {
            File copy = copy(context, source, extension(name), MAX_FILE);
            if (copy.length() <= limit) return new Prepared(copy, mime, name, false);
            copy.delete();
        }
        // Re-encoding keeps only pixels: EXIF, XMP, GPS, and maker notes are gone. Orientation is applied first.
        boolean alpha = mime.equals("image/png") || mime.equals("image/webp");
        int longest = limit < 2 * 1024 * 1024 ? 1280 : 2560;
        Bitmap bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.getContentResolver(), source), (decoder, info, ignored) -> {
            var size = info.getSize();
            float scale = Math.min(1f, (float) longest / Math.max(size.getWidth(), size.getHeight()));
            decoder.setTargetSize(Math.max(1, Math.round(size.getWidth() * scale)), Math.max(1, Math.round(size.getHeight() * scale)));
            decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
        });
        try {
            var output = new ByteArrayOutputStream();
            if (alpha && bitmap.hasAlpha()) {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, output);
                if (output.size() <= limit) return write(context, output, "png", "image/png", base(name) + ".png");
            }
            for (int quality = 88; quality >= 30; quality -= 10) {
                output.reset();
                if (bitmap.compress(Bitmap.CompressFormat.JPEG, quality, output) && output.size() <= limit) {
                    return write(context, output, "jpg", "image/jpeg", base(name) + ".jpg");
                }
            }
            throw new IOException("This picture is too large to send with this protocol.");
        } finally { bitmap.recycle(); }
    }

    private static Prepared write(Context context, ByteArrayOutputStream data, String extension, String mime, String name) throws IOException {
        File file = AttachmentFiles.INSTANCE.scratch(context, extension);
        try (var out = new FileOutputStream(file)) { data.writeTo(out); }
        return new Prepared(file, mime, name, true);
    }

    private static File copy(Context context, Uri source, String extension, long limit) throws IOException {
        File file = AttachmentFiles.INSTANCE.scratch(context, extension.isEmpty() ? "bin" : extension);
        try (InputStream input = context.getContentResolver().openInputStream(source); var output = new FileOutputStream(file)) {
            if (input == null) throw new IOException("Couldn’t open the attachment.");
            byte[] buffer = new byte[64 * 1024];
            long total = 0;
            for (int read; (read = input.read(buffer)) > 0; ) {
                total += read;
                if (total > limit) throw new IOException("This file is larger than "
                    + android.text.format.Formatter.formatShortFileSize(context, limit) + ", the most this protocol can send.");
                output.write(buffer, 0, read);
            }
        } catch (IOException error) {
            file.delete();
            throw error;
        }
        return file;
    }

    /** Copies audio and video samples into a fresh container, which carries no tags or location. */
    private static File remux(Context context, File input, int format) throws IOException {
        File output = AttachmentFiles.INSTANCE.scratch(context, format == MediaMuxer.OutputFormat.MUXER_OUTPUT_WEBM ? "webm"
            : format == MediaMuxer.OutputFormat.MUXER_OUTPUT_3GPP ? "3gp" : "mp4");
        var extractor = new MediaExtractor();
        MediaMuxer muxer = null;
        try {
            extractor.setDataSource(input.getPath());
            muxer = new MediaMuxer(output.getPath(), format);
            int[] tracks = new int[extractor.getTrackCount()];
            int buffer = 256 * 1024;
            for (int i = 0; i < tracks.length; i++) {
                MediaFormat track = extractor.getTrackFormat(i);
                String type = track.getString(MediaFormat.KEY_MIME);
                if (type == null || !(type.startsWith("audio/") || type.startsWith("video/"))) { tracks[i] = -1; continue; }
                if (track.containsKey(MediaFormat.KEY_ROTATION)) muxer.setOrientationHint(track.getInteger(MediaFormat.KEY_ROTATION));
                if (track.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) buffer = Math.max(buffer, track.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE));
                extractor.selectTrack(i);
                tracks[i] = muxer.addTrack(track);
            }
            if (Arrays.stream(tracks).allMatch(index -> index < 0)) throw new IOException("No media tracks");
            muxer.start();
            ByteBuffer samples = ByteBuffer.allocate(buffer);
            var info = new MediaCodec.BufferInfo();
            while (true) {
                int size = extractor.readSampleData(samples, 0);
                if (size < 0) break;
                int track = tracks[extractor.getSampleTrackIndex()];
                if (track >= 0) {
                    info.set(0, size, extractor.getSampleTime(), (extractor.getSampleFlags() & MediaExtractor.SAMPLE_FLAG_SYNC) != 0
                        ? MediaCodec.BUFFER_FLAG_KEY_FRAME : 0);
                    muxer.writeSampleData(track, samples, info);
                }
                extractor.advance();
            }
            muxer.stop();
            return output;
        } catch (IOException | RuntimeException error) {
            output.delete();
            throw error instanceof IOException io ? io : new IOException(error);
        } finally {
            extractor.release();
            if (muxer != null) try { muxer.release(); } catch (RuntimeException ignored) {}
        }
    }

    /** Drops ID3v2 tags at the start and an ID3v1 tag at the end, leaving the audio frames untouched. */
    private static File mp3(Context context, File input) throws IOException {
        byte[] data = Files.readAllBytes(input.toPath());
        int start = 0;
        while (data.length - start > 10 && data[start] == 'I' && data[start + 1] == 'D' && data[start + 2] == '3') {
            int size = (data[start + 6] & 0x7f) << 21 | (data[start + 7] & 0x7f) << 14 | (data[start + 8] & 0x7f) << 7 | (data[start + 9] & 0x7f);
            boolean footer = (data[start + 5] & 0x10) != 0;
            start += 10 + size + (footer ? 10 : 0);
            if (start > data.length) throw new IOException("Invalid ID3 tag");
        }
        int end = data.length;
        if (end - start >= 128 && data[end - 128] == 'T' && data[end - 127] == 'A' && data[end - 126] == 'G') end -= 128;
        File output = AttachmentFiles.INSTANCE.scratch(context, "mp3");
        try (var out = new FileOutputStream(output)) { out.write(data, start, end - start); }
        return output;
    }

    /** Empties the document information dictionary and removes the XMP metadata stream. */
    private static File pdf(Context context, File input) throws IOException {
        // Encrypted PDFs would need BouncyCastle; they are sent unchanged instead.
        if (new String(Files.readAllBytes(input.toPath()), StandardCharsets.ISO_8859_1).contains("/Encrypt")) return null;
        PDFBoxResourceLoader.init(context.getApplicationContext());
        File output = AttachmentFiles.INSTANCE.scratch(context, "pdf");
        try (PDDocument document = PDDocument.load(input)) {
            if (document.isEncrypted()) return null;
            document.setDocumentInformation(new PDDocumentInformation());
            document.getDocumentCatalog().setMetadata(null);
            document.save(output);
        }
        return output;
    }

    /**
     * Rewrites an Office Open XML or OpenDocument zip with blank document properties. Every other entry
     * is copied byte for byte in its original order, so the document opens exactly as before.
     */
    private static File office(Context context, File input, boolean openDocument) throws IOException {
        File output = AttachmentFiles.INSTANCE.scratch(context, extension(input.getName()));
        boolean changed = false;
        try (var zip = new ZipInputStream(Files.newInputStream(input.toPath()));
             var out = new ZipOutputStream(new FileOutputStream(output))) {
            for (ZipEntry entry; (entry = zip.getNextEntry()) != null; ) {
                byte[] data = zip.readAllBytes();
                String replacement = openDocument ? (entry.getName().equals("meta.xml") ? ODF_META : null) : switch (entry.getName()) {
                    case "docProps/core.xml" -> CORE;
                    case "docProps/app.xml" -> APP;
                    case "docProps/custom.xml" -> CUSTOM;
                    default -> null;
                };
                if (replacement != null) { data = replacement.getBytes(StandardCharsets.UTF_8); changed = true; }
                var copy = new ZipEntry(entry.getName());
                if (entry.getMethod() == ZipEntry.STORED) {
                    var crc = new CRC32();
                    crc.update(data);
                    copy.setMethod(ZipEntry.STORED);
                    copy.setSize(data.length);
                    copy.setCompressedSize(data.length);
                    copy.setCrc(crc.getValue());
                }
                out.putNextEntry(copy);
                out.write(data);
                out.closeEntry();
            }
        } catch (IOException | RuntimeException error) {
            output.delete();
            throw error instanceof IOException io ? io : new IOException(error);
        }
        if (!changed) { output.delete(); return null; }
        return output;
    }

    private static final String CORE = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"
        + "<cp:coreProperties xmlns:cp=\"http://schemas.openxmlformats.org/package/2006/metadata/core-properties\""
        + " xmlns:dc=\"http://purl.org/dc/elements/1.1/\" xmlns:dcterms=\"http://purl.org/dc/terms/\""
        + " xmlns:dcmitype=\"http://purl.org/dc/dcmitype/\" xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"/>";
    private static final String APP = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"
        + "<Properties xmlns=\"http://schemas.openxmlformats.org/officeDocument/2006/extended-properties\""
        + " xmlns:vt=\"http://schemas.openxmlformats.org/officeDocument/2006/docPropsVTypes\"/>";
    private static final String CUSTOM = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"
        + "<Properties xmlns=\"http://schemas.openxmlformats.org/officeDocument/2006/custom-properties\""
        + " xmlns:vt=\"http://schemas.openxmlformats.org/officeDocument/2006/docPropsVTypes\"/>";
    private static final String ODF_META = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
        + "<office:document-meta xmlns:office=\"urn:oasis:names:tc:opendocument:xmlns:office:1.0\" office:version=\"1.2\">"
        + "<office:meta/></office:document-meta>";

    static String safeName(String name) {
        String clean = name.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").trim();
        return clean.isEmpty() ? "attachment" : clean.length() > 120 ? clean.substring(clean.length() - 120) : clean;
    }

    static String extension(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    private static String base(String name) {
        int dot = name.lastIndexOf('.');
        return dot <= 0 ? name : name.substring(0, dot);
    }
}
