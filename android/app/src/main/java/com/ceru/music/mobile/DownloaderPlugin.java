package com.ceru.music.mobile;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.os.Environment;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;
import android.webkit.MimeTypeMap;

import androidx.activity.result.ActivityResult;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.ActivityCallback;
import com.getcapacitor.annotation.CapacitorPlugin;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Streams an audio file to the app's external Music directory so downloads do
 * not have to be buffered in the WebView and can report real progress.
 * Every hop is validated against the same SSRF rules as SecureHttpPlugin.
 *
 * <p>When the user picks a custom folder (SAF tree) the same streaming logic
 * writes through {@link ContentResolver} instead, and files are handed back to
 * the WebView through {@link LocalStreamServer} because {@code content://} URIs
 * cannot be used by the audio element.</p>
 */
@CapacitorPlugin(name = "Downloader")
public class DownloaderPlugin extends Plugin {
    private static final int MAX_REDIRECTS = 5;
    private static final long PROGRESS_INTERVAL_MS = 250L;
    private static final String BLOCKED_MESSAGE = "出于安全原因，不能访问本机、回环或局域网地址";
    private static final String PREFS_NAME = "ceru.downloads";
    private static final String PREF_TREE = "treeUri";
    private static final long MAX_TEXT_BYTES = 2L * 1024 * 1024;

    private final ExecutorService executor = Executors.newFixedThreadPool(3);
    private final Map<String, Job> jobs = new ConcurrentHashMap<>();
    private final LocalStreamServer server = new LocalStreamServer();
    private final String streamSalt = UUID.randomUUID().toString();

    private static final class Job {
        volatile boolean cancelled;
    }

    private interface Sink {
        OutputStream open() throws Exception;
    }

    private static final class Target {
        final String name;
        final File file;
        final Uri uri;
        final long size;

        Target(String name, File file, Uri uri, long size) {
            this.name = name;
            this.file = file;
            this.uri = uri;
            this.size = size;
        }
    }

    // ---------------------------------------------------------------- folder

    @PluginMethod
    public void chooseDirectory(PluginCall call) {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        try {
            startActivityForResult(call, intent, "directoryPicked");
        } catch (Exception error) {
            call.reject("无法打开目录选择器");
        }
    }

    @ActivityCallback
    private void directoryPicked(PluginCall call, ActivityResult result) {
        if (call == null) return;
        if (result == null || result.getResultCode() != Activity.RESULT_OK || result.getData() == null) {
            call.reject("已取消");
            return;
        }
        Uri uri = result.getData().getData();
        if (uri == null) {
            call.reject("未选择目录");
            return;
        }
        try {
            getContext().getContentResolver().takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        } catch (SecurityException ignored) {
            // Some providers grant the permission implicitly; writing may still work.
        }
        prefs().edit().putString(PREF_TREE, uri.toString()).apply();
        JSObject ret = directoryResult();
        ret.put("uri", uri.toString());
        ret.put("label", labelFor(uri));
        ret.put("isDefault", false);
        call.resolve(ret);
    }

    @PluginMethod
    public void getDirectory(PluginCall call) {
        call.resolve(directoryResult());
    }

    @PluginMethod
    public void clearDirectory(PluginCall call) {
        prefs().edit().remove(PREF_TREE).apply();
        call.resolve(directoryResult());
    }

    private JSObject directoryResult() {
        String stored = prefs().getString(PREF_TREE, "");
        JSObject ret = new JSObject();
        ret.put("uri", stored == null ? "" : stored);
        boolean isDefault = stored == null || stored.isEmpty();
        ret.put("isDefault", isDefault);
        ret.put("label", isDefault ? "应用专属目录" : labelFor(Uri.parse(stored)));
        return ret;
    }

    private SharedPreferences prefs() {
        return getContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    // -------------------------------------------------------------- download

    @PluginMethod
    public void download(final PluginCall call) {
        final String id = call.getString("id");
        final String url = call.getString("url");
        final String fileName = call.getString("fileName", "audio.mp3");
        final String treeUri = call.getString("treeUri", "");
        final boolean overwrite = Boolean.TRUE.equals(call.getBoolean("overwrite", false));
        final JSObject headers = call.getObject("headers", new JSObject());
        if (id == null || id.isEmpty()) {
            call.reject("下载任务缺少标识");
            return;
        }
        if (url == null || url.isEmpty()) {
            call.reject("下载地址为空");
            return;
        }
        Job job = new Job();
        jobs.put(id, job);
        final String safeName = sanitizeFileName(fileName);
        executor.execute(() -> {
            try {
                Target target = treeUri.isEmpty()
                        ? writeToAppDirectory(safeName, url, headers, id, job, overwrite)
                        : writeToTree(treeUri, safeName, url, headers, id, job, overwrite);
                JSObject result = new JSObject();
                result.put("id", id);
                result.put("path", target.file == null ? "" : target.file.getAbsolutePath());
                result.put("uri", target.uri == null ? "" : target.uri.toString());
                result.put("fileName", target.name);
                result.put("size", target.size);
                call.resolve(result);
            } catch (Exception error) {
                call.reject(messageOf(error));
            } finally {
                jobs.remove(id);
            }
        });
    }

    @PluginMethod
    public void cancel(PluginCall call) {
        String id = call.getString("id");
        Job job = id == null ? null : jobs.get(id);
        if (job != null) job.cancelled = true;
        call.resolve();
    }

    private Target writeToAppDirectory(String fileName, String url, JSObject headers, String id, Job job,
                                       boolean overwrite) throws Exception {
        File target = uniqueFile(fileName, !overwrite);
        try {
            long size = perform(id, url, headers, () -> new FileOutputStream(target), job);
            return new Target(target.getName(), target, null, size);
        } catch (Exception error) {
            //noinspection ResultOfMethodCallIgnored
            target.delete();
            throw error;
        }
    }

    private Target writeToTree(String treeUriString, String fileName, String url, JSObject headers,
                               String id, Job job, boolean overwrite) throws Exception {
        Uri treeUri = Uri.parse(treeUriString);
        Uri parent = treeDocument(treeUri);
        ContentResolver resolver = getContext().getContentResolver();
        String name = fileName;
        Uri document = overwrite ? findChild(treeUri, fileName) : null;
        if (document == null) {
            name = overwrite ? fileName : uniqueTreeName(treeUri, fileName);
            document = DocumentsContract.createDocument(resolver, parent, mimeTypeFor(name), name);
        }
        if (document == null) throw new Exception("无法在所选目录创建文件");
        final Uri target = document;
        final String finalName = name;
        try {
            long size = perform(id, url, headers, () -> resolver.openOutputStream(target, "w"), job);
            return new Target(finalName, null, target, size);
        } catch (Exception error) {
            try {
                DocumentsContract.deleteDocument(resolver, target);
            } catch (Exception ignored) {
            }
            throw error;
        }
    }

    private long perform(String id, String initialUrl, JSObject initialHeaders, Sink sink, Job job)
            throws Exception {
        String currentUrl = initialUrl;
        JSObject headers = initialHeaders;
        for (int redirects = 0; redirects <= MAX_REDIRECTS; redirects++) {
            if (job.cancelled) throw new Exception("已取消");
            URL url = new URL(currentUrl);
            validateUrl(url);

            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(30_000);
            connection.setReadTimeout(60_000);
            connection.setRequestMethod("GET");
            applyHeaders(connection, headers);

            int status = connection.getResponseCode();
            if (status == 301 || status == 302 || status == 303 || status == 307 || status == 308) {
                String location = connection.getHeaderField("Location");
                connection.disconnect();
                if (location == null || location.isEmpty()) {
                    throw new Exception("重定向缺少目标地址");
                }
                if (redirects == MAX_REDIRECTS) throw new Exception("重定向次数过多");
                URL next = new URL(url, location);
                if (!sameOrigin(url, next)) headers = stripSensitiveHeaders(headers);
                currentUrl = next.toString();
                continue;
            }
            if (status < 200 || status >= 300) {
                connection.disconnect();
                throw new Exception("下载失败: HTTP " + status);
            }

            long total = connection.getContentLengthLong();
            long received = 0;
            long lastSent = 0L;
            byte[] buffer = new byte[16 * 1024];
            try (InputStream input = connection.getInputStream();
                 OutputStream output = sink.open()) {
                int read;
                while ((read = input.read(buffer)) != -1) {
                    if (job.cancelled) throw new Exception("已取消");
                    output.write(buffer, 0, read);
                    received += read;
                    long now = System.currentTimeMillis();
                    if (now - lastSent >= PROGRESS_INTERVAL_MS) {
                        lastSent = now;
                        emitProgress(id, received, total);
                    }
                }
            } finally {
                connection.disconnect();
            }
            emitProgress(id, received, total);
            if (received == 0) throw new Exception("下载内容为空");
            return received;
        }
        throw new Exception("重定向次数过多");
    }

    private void emitProgress(String id, long received, long total) {
        JSObject event = new JSObject();
        event.put("id", id);
        event.put("received", received);
        event.put("total", total);
        notifyListeners("progress", event);
    }

    // ----------------------------------------------------------------- files

    @PluginMethod
    public void exists(PluginCall call) {
        String fileName = call.getString("fileName");
        String treeUri = call.getString("treeUri", "");
        if (fileName == null || fileName.isEmpty()) {
            call.reject("文件名不能为空");
            return;
        }
        JSObject ret = new JSObject();
        try {
            if (treeUri.isEmpty()) {
                File file = appFile(fileName);
                ret.put("exists", file.exists());
                ret.put("size", file.exists() ? file.length() : 0);
            } else {
                Uri document = findChild(Uri.parse(treeUri), fileName);
                long size = document == null ? 0 : querySize(document);
                ret.put("exists", document != null);
                ret.put("size", size);
            }
            call.resolve(ret);
        } catch (Exception error) {
            call.reject(messageOf(error));
        }
    }

    @PluginMethod
    public void readText(PluginCall call) {
        String fileName = call.getString("fileName");
        String treeUri = call.getString("treeUri", "");
        if (fileName == null || fileName.isEmpty()) {
            call.reject("文件名不能为空");
            return;
        }
        try (InputStream input = openForRead(treeUri, fileName)) {
            if (input == null) {
                call.reject("文件不存在");
                return;
            }
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[8 * 1024];
            long total = 0;
            int read;
            while ((read = input.read(chunk)) != -1) {
                total += read;
                if (total > MAX_TEXT_BYTES) break;
                buffer.write(chunk, 0, read);
            }
            JSObject ret = new JSObject();
            ret.put("text", buffer.toString(StandardCharsets.UTF_8.name()));
            call.resolve(ret);
        } catch (Exception error) {
            call.reject(messageOf(error));
        }
    }

    @PluginMethod
    public void writeText(PluginCall call) {
        String fileName = call.getString("fileName");
        String text = call.getString("text", "");
        String treeUri = call.getString("treeUri", "");
        if (fileName == null || fileName.isEmpty()) {
            call.reject("文件名不能为空");
            return;
        }
        try {
            byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
            if (treeUri.isEmpty()) {
                File target = uniqueFile(fileName, false);
                try (OutputStream output = new FileOutputStream(target)) {
                    output.write(bytes);
                }
                JSObject ret = new JSObject();
                ret.put("fileName", target.getName());
                ret.put("path", target.getAbsolutePath());
                ret.put("uri", "");
                ret.put("size", bytes.length);
                call.resolve(ret);
                return;
            }
            ContentResolver resolver = getContext().getContentResolver();
            Uri tree = Uri.parse(treeUri);
            Uri document = findChild(tree, fileName);
            if (document == null) {
                document = DocumentsContract.createDocument(resolver, treeDocument(tree),
                        mimeTypeFor(fileName), fileName);
            }
            if (document == null) throw new Exception("无法在所选目录创建文件");
            try (OutputStream output = resolver.openOutputStream(document, "wt")) {
                if (output == null) throw new Exception("无法写入文件");
                output.write(bytes);
            }
            JSObject ret = new JSObject();
            ret.put("fileName", fileName);
            ret.put("path", "");
            ret.put("uri", document.toString());
            ret.put("size", bytes.length);
            call.resolve(ret);
        } catch (Exception error) {
            call.reject(messageOf(error));
        }
    }

    @PluginMethod
    public void delete(PluginCall call) {
        String fileName = call.getString("fileName");
        String treeUri = call.getString("treeUri", "");
        if (fileName == null || fileName.isEmpty()) {
            call.reject("文件名不能为空");
            return;
        }
        try {
            if (treeUri.isEmpty()) {
                File dir = musicDir();
                File target = new File(dir, fileName);
                String root = dir.getCanonicalPath() + File.separator;
                if (!target.getCanonicalPath().startsWith(root)) {
                    call.reject("文件不在下载目录内");
                    return;
                }
                if (target.exists() && !target.delete()) {
                    call.reject("删除文件失败");
                    return;
                }
                call.resolve();
                return;
            }
            Uri document = findChild(Uri.parse(treeUri), fileName);
            if (document != null
                    && !DocumentsContract.deleteDocument(getContext().getContentResolver(), document)) {
                call.reject("删除文件失败");
                return;
            }
            call.resolve();
        } catch (Exception error) {
            call.reject(messageOf(error));
        }
    }

    /**
     * Returns a loopback URL the WebView can hand to an {@code <audio>} element.
     * Files stored in the app directory keep using {@code convertFileSrc} on the
     * JS side, so this is only used for SAF documents.
     */
    @PluginMethod
    public void localUrl(PluginCall call) {
        String fileName = call.getString("fileName");
        String treeUri = call.getString("treeUri", "");
        if (fileName == null || fileName.isEmpty()) {
            call.reject("文件名不能为空");
            return;
        }
        try {
            String type = mimeTypeFor(fileName);
            String seed;
            long size;
            if (treeUri.isEmpty()) {
                File file = appFile(fileName);
                if (!file.exists()) {
                    call.reject("文件不存在");
                    return;
                }
                seed = "file:" + file.getAbsolutePath();
                size = file.length();
                final File source = file;
                server.publish(tokenFor(seed), new LocalStreamServer.Source() {
                    @Override
                    public InputStream open() throws java.io.IOException {
                        if (!source.exists()) throw new java.io.IOException("文件不存在");
                        return new FileInputStream(source);
                    }

                    @Override
                    public long size() {
                        return source.length();
                    }
                }, type);
            } else {
                Uri document = findChild(Uri.parse(treeUri), fileName);
                if (document == null) {
                    call.reject("文件不存在");
                    return;
                }
                seed = "saf:" + document;
                size = querySize(document);
                final ContentResolver resolver = getContext().getContentResolver();
                final Uri source = document;
                server.publish(tokenFor(seed), new LocalStreamServer.Source() {
                    @Override
                    public InputStream open() throws java.io.IOException {
                        InputStream stream = resolver.openInputStream(source);
                        if (stream == null) throw new java.io.IOException("无法读取文件");
                        return stream;
                    }

                    @Override
                    public long size() {
                        return querySize(source);
                    }
                }, type);
            }
            JSObject ret = new JSObject();
            ret.put("url", "http://127.0.0.1:" + server.port() + "/s/" + tokenFor(seed));
            ret.put("size", size);
            call.resolve(ret);
        } catch (Exception error) {
            call.reject(messageOf(error));
        }
    }

    private String tokenFor(String seed) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] bytes = digest.digest((streamSalt + "|" + seed).getBytes(StandardCharsets.UTF_8));
        StringBuilder token = new StringBuilder();
        for (int index = 0; index < 12; index += 1) {
            token.append(String.format(Locale.ROOT, "%02x", bytes[index] & 0xff));
        }
        return token.toString();
    }

    private InputStream openForRead(String treeUri, String fileName) throws Exception {
        if (treeUri.isEmpty()) {
            File file = appFile(fileName);
            return file.exists() ? new FileInputStream(file) : null;
        }
        Uri document = findChild(Uri.parse(treeUri), fileName);
        if (document == null) return null;
        return getContext().getContentResolver().openInputStream(document);
    }

    // ----------------------------------------------------------- SAF helpers

    private static Uri treeDocument(Uri treeUri) {
        return DocumentsContract.buildDocumentUriUsingTree(
                treeUri, DocumentsContract.getTreeDocumentId(treeUri));
    }

    private Set<String> childNames(Uri treeUri) {
        Set<String> names = new HashSet<>();
        String documentId = DocumentsContract.getTreeDocumentId(treeUri);
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, documentId);
        try (Cursor cursor = getContext().getContentResolver().query(
                children, new String[] { DocumentsContract.Document.COLUMN_DISPLAY_NAME },
                null, null, null)) {
            if (cursor == null) return names;
            while (cursor.moveToNext()) {
                String name = cursor.getString(0);
                if (name != null) names.add(name);
            }
        } catch (Exception ignored) {
        }
        return names;
    }

    private Uri findChild(Uri treeUri, String fileName) {
        String documentId = DocumentsContract.getTreeDocumentId(treeUri);
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, documentId);
        try (Cursor cursor = getContext().getContentResolver().query(
                children,
                new String[] {
                        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                        DocumentsContract.Document.COLUMN_DISPLAY_NAME
                },
                null, null, null)) {
            if (cursor == null) return null;
            while (cursor.moveToNext()) {
                String name = cursor.getString(1);
                if (fileName.equals(name)) {
                    return DocumentsContract.buildDocumentUriUsingTree(treeUri, cursor.getString(0));
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private long querySize(Uri document) {
        try (Cursor cursor = getContext().getContentResolver().query(
                document, new String[] { OpenableColumns.SIZE }, null, null, null)) {
            if (cursor != null && cursor.moveToFirst() && !cursor.isNull(0)) {
                return cursor.getLong(0);
            }
        } catch (Exception ignored) {
        }
        try {
            android.content.res.AssetFileDescriptor descriptor =
                    getContext().getContentResolver().openAssetFileDescriptor(document, "r");
            if (descriptor != null) {
                long length = descriptor.getLength();
                descriptor.close();
                if (length > 0) return length;
            }
        } catch (Exception ignored) {
        }
        return 0;
    }

    private String uniqueTreeName(Uri treeUri, String fileName) {
        Set<String> names = childNames(treeUri);
        if (!names.contains(fileName)) return fileName;
        int dot = fileName.lastIndexOf('.');
        String base = dot > 0 ? fileName.substring(0, dot) : fileName;
        String extension = dot > 0 ? fileName.substring(dot) : "";
        for (int index = 1; index < 1000; index += 1) {
            String candidate = base + " (" + index + ")" + extension;
            if (!names.contains(candidate)) return candidate;
        }
        return base + "-" + System.currentTimeMillis() + extension;
    }

    private String labelFor(Uri treeUri) {
        try (Cursor cursor = getContext().getContentResolver().query(
                treeDocument(treeUri), new String[] { DocumentsContract.Document.COLUMN_DISPLAY_NAME },
                null, null, null)) {
            if (cursor != null && cursor.moveToFirst() && cursor.getString(0) != null) {
                return cursor.getString(0);
            }
        } catch (Exception ignored) {
        }
        String id = DocumentsContract.getTreeDocumentId(treeUri);
        int colon = id.lastIndexOf(':');
        String tail = colon >= 0 ? id.substring(colon + 1) : id;
        return tail.isEmpty() ? "已选目录" : tail;
    }

    private static String mimeTypeFor(String fileName) {
        int dot = fileName.lastIndexOf('.');
        String extension = dot >= 0 ? fileName.substring(dot + 1).toLowerCase(Locale.ROOT) : "";
        switch (extension) {
            case "mp3":
                return "audio/mpeg";
            case "flac":
                return "audio/flac";
            case "m4a":
                return "audio/mp4";
            case "aac":
                return "audio/aac";
            case "ogg":
            case "opus":
                return "audio/ogg";
            case "wav":
                return "audio/wav";
            case "lrc":
                // Must not be a MIME type the provider knows: ExternalStorageProvider
                // appends the MIME's extension, which turned "x.lrc" into "x.lrc.txt".
                return "application/x-lrc";
            case "jpg":
            case "jpeg":
                return "image/jpeg";
            case "png":
                return "image/png";
            case "webp":
                return "image/webp";
            default:
                String guessed = MimeTypeMap.getSingleton()
                        .getMimeTypeFromExtension(extension);
                return guessed == null ? "application/octet-stream" : guessed;
        }
    }

    // --------------------------------------------------------------- helpers

    private File appFile(String fileName) throws Exception {
        File dir = musicDir();
        File target = new File(dir, fileName);
        String root = dir.getCanonicalPath() + File.separator;
        if (!target.getCanonicalPath().startsWith(root)) {
            throw new Exception("文件不在下载目录内");
        }
        return target;
    }

    private File musicDir() {
        File dir = getContext().getExternalFilesDir(Environment.DIRECTORY_MUSIC);
        if (dir == null) dir = new File(getContext().getFilesDir(), "Music");
        if (!dir.exists() && !dir.mkdirs()) {
            dir = new File(getContext().getFilesDir(), "Music");
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
        }
        return dir;
    }

    private File uniqueFile(String fileName, boolean allowDuplicate) throws Exception {
        File dir = musicDir();
        File candidate = new File(dir, fileName);
        if (!allowDuplicate || !candidate.exists()) return candidate;
        int dot = fileName.lastIndexOf('.');
        String base = dot > 0 ? fileName.substring(0, dot) : fileName;
        String ext = dot > 0 ? fileName.substring(dot) : "";
        for (int index = 1; index < 1000; index++) {
            candidate = new File(dir, base + " (" + index + ")" + ext);
            if (!candidate.exists()) return candidate;
        }
        return new File(dir, System.currentTimeMillis() + ext);
    }

    private static String sanitizeFileName(String value) {
        String name = value == null ? "" : value.trim();
        name = name.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_");
        name = name.replaceAll("^[.\\s]+", "").replaceAll("[.\\s]+$", "");
        if (name.isEmpty()) name = "audio";
        if (name.length() > 120) {
            int dot = name.lastIndexOf('.');
            String ext = dot > 0 && name.length() - dot <= 6 ? name.substring(dot) : "";
            name = name.substring(0, 120 - ext.length()) + ext;
        }
        if (!name.contains(".")) name = name + ".mp3";
        return name;
    }

    private static String messageOf(Exception error) {
        return error.getMessage() == null ? "下载失败" : error.getMessage();
    }

    private void validateUrl(URL url) throws Exception {
        String scheme = url.getProtocol();
        if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
            throw new Exception("只支持 HTTP/HTTPS 下载");
        }
        URI uri = url.toURI();
        if (uri.getUserInfo() != null) throw new Exception("下载地址不能包含用户名或密码");
        String host = url.getHost();
        if (host == null || host.isEmpty()) throw new Exception("无效的下载地址");
        InetAddress[] addresses = InetAddress.getAllByName(host);
        if (addresses.length == 0) throw new Exception("无法解析服务器地址");
        boolean vpnActive = isVpnActive();
        boolean domainName = !isIpLiteral(host);
        for (InetAddress address : addresses) {
            if (isBlockedAddress(address)) {
                if (isVpnFakeAddress(address) && (vpnActive || domainName)) continue;
                throw new Exception(BLOCKED_MESSAGE);
            }
        }
    }

    private static boolean isIpLiteral(String host) {
        return host.indexOf(':') >= 0 || host.matches("\\d{1,3}(\\.\\d{1,3}){3}");
    }

    private boolean isVpnActive() {
        ConnectivityManager manager =
                (ConnectivityManager) getContext().getSystemService(Context.CONNECTIVITY_SERVICE);
        if (manager == null) return false;
        Network network = manager.getActiveNetwork();
        if (network == null) return false;
        NetworkCapabilities capabilities = manager.getNetworkCapabilities(network);
        return capabilities != null && capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN);
    }

    private static boolean isVpnFakeAddress(InetAddress address) {
        byte[] bytes = address.getAddress();
        if (bytes.length == 4) {
            int a = bytes[0] & 0xff;
            int b = bytes[1] & 0xff;
            return (a == 198 && (b == 18 || b == 19)) || (a == 100 && b >= 64 && b <= 127);
        }
        if (bytes.length == 16) return (bytes[0] & 0xfe) == 0xfc;
        return false;
    }

    private static boolean isBlockedAddress(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress()
                || address.isLinkLocalAddress() || address.isSiteLocalAddress()
                || address.isMulticastAddress()) {
            return true;
        }
        byte[] bytes = address.getAddress();
        if (bytes.length == 4) {
            int a = bytes[0] & 0xff;
            int b = bytes[1] & 0xff;
            if (a == 0 || a == 10 || a == 127 || a >= 224) return true;
            if (a == 169 && b == 254) return true;
            if (a == 172 && b >= 16 && b <= 31) return true;
            if (a == 192 && b == 168) return true;
            if (a == 100 && b >= 64 && b <= 127) return true;
            if (a == 192 && b == 0) return true;
            if (a == 198 && (b == 18 || b == 19)) return true;
            if (a == 198 && b == 51) return true;
            if (a == 203 && b == 0) return true;
        } else if (bytes.length == 16) {
            int first = bytes[0] & 0xff;
            if ((first & 0xfe) == 0xfc) return true;
            if (first == 0xfe && (bytes[1] & 0xc0) == 0x80) return true;
            boolean mapped = true;
            for (int i = 0; i < 10; i++) {
                if (bytes[i] != 0) {
                    mapped = false;
                    break;
                }
            }
            if (mapped && bytes[10] == (byte) 0xff && bytes[11] == (byte) 0xff) {
                int a = bytes[12] & 0xff;
                int b = bytes[13] & 0xff;
                return a == 0 || a == 10 || a == 127 || a >= 224
                        || (a == 169 && b == 254)
                        || (a == 172 && b >= 16 && b <= 31)
                        || (a == 192 && b == 168)
                        || (a == 100 && b >= 64 && b <= 127);
            }
        }
        return false;
    }

    private static boolean sameOrigin(URL first, URL second) {
        return first.getProtocol().equalsIgnoreCase(second.getProtocol())
                && first.getHost().equalsIgnoreCase(second.getHost())
                && effectivePort(first) == effectivePort(second);
    }

    private static int effectivePort(URL url) {
        if (url.getPort() >= 0) return url.getPort();
        return "https".equalsIgnoreCase(url.getProtocol()) ? 443 : 80;
    }

    private static JSObject stripSensitiveHeaders(JSObject headers) throws Exception {
        JSObject filtered = new JSObject();
        Iterator<String> keys = headers.keys();
        while (keys.hasNext()) {
            String name = keys.next();
            String lower = name.toLowerCase(Locale.ROOT);
            if (lower.contains("authorization") || lower.contains("cookie")
                    || lower.contains("token") || lower.contains("key")) continue;
            filtered.put(name, headers.getString(name));
        }
        return filtered;
    }

    private static void applyHeaders(HttpURLConnection connection, JSObject headers) {
        Iterator<String> keys = headers.keys();
        while (keys.hasNext()) {
            String name = keys.next();
            String lower = name.toLowerCase(Locale.ROOT);
            if (lower.equals("host") || lower.equals("connection") || lower.equals("content-length")
                    || lower.equals("transfer-encoding") || lower.startsWith("proxy-")
                    || lower.equals("upgrade") || lower.equals("accept-encoding")
                    || lower.equals("range")) continue;
            String value = headers.getString(name);
            if (value == null || value.contains("\r") || value.contains("\n")) continue;
            connection.setRequestProperty(name, value);
        }
    }

    @Override
    protected void handleOnDestroy() {
        for (Job job : jobs.values()) job.cancelled = true;
        executor.shutdownNow();
        server.stop();
        super.handleOnDestroy();
    }
}
