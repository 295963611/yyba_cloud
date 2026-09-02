package com.yyba.cloud.api;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;
import com.yyba.cloud.util.PrefsManager;

import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Type;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class ApiClient {

    public static class AuthFailureException extends Exception {
        public final int httpCode;

        public AuthFailureException(int httpCode, String message) {
            super(message);
            this.httpCode = httpCode;
        }
    }

    public static boolean isAuthFailure(Throwable error) {
        if (error instanceof AuthFailureException) {
            return true;
        }
        String msg = error != null ? error.getMessage() : null;
        return msg != null && (msg.contains("HTTP 403") || msg.contains("HTTP 401"));
    }

    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
    private static final int DOWNLOAD_BUFFER_SIZE = 64 * 1024;

    private final OkHttpClient client;
    private final OkHttpClient downloadClient;
    private final Gson gson = new Gson();
    private final PrefsManager prefs;

    public ApiClient(PrefsManager prefs) {
        this.prefs = prefs;
        this.client = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(120, TimeUnit.SECONDS)
                .writeTimeout(120, TimeUnit.SECONDS)
                .build();
        this.downloadClient = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.SECONDS)
                .writeTimeout(120, TimeUnit.SECONDS)
                .build();
    }

    public LoginResult login(String username, String password) throws Exception {
        JsonObject body = new JsonObject();
        body.addProperty("username", username);
        body.addProperty("password", password);
        Request request = new Request.Builder()
                .url(baseUrl() + "/api/auth/login")
                .post(RequestBody.create(body.toString(), JSON))
                .build();
        JsonObject data = execute(request);
        String userId = data.has("userId") ? data.get("userId").getAsString() : "0";
        String role = data.has("role") ? data.get("role").getAsString() : "MEMBER";
        return new LoginResult(
                data.get("token").getAsString(),
                data.get("username").getAsString(),
                userId,
                role
        );
    }

    public void deleteFile(String relativePath) throws Exception {
        String encoded = URLEncoder.encode(relativePath, StandardCharsets.UTF_8.name());
        Request request = authBuilder()
                .url(baseUrl() + "/api/files?path=" + encoded)
                .delete()
                .build();
        execute(request);
    }

    public List<CloudFile> listTrash() throws Exception {
        Request request = authBuilder()
                .url(baseUrl() + "/api/files/trash")
                .get()
                .build();
        try (Response response = client.newCall(request).execute()) {
            String body = response.body() != null ? response.body().string() : "";
            JsonObject root = gson.fromJson(body, JsonObject.class);
            if (root == null || root.get("code").getAsInt() != 200) {
                String msg = root != null && root.has("message") ? root.get("message").getAsString() : "请求失败";
                throw new Exception(msg);
            }
            Type type = new TypeToken<List<CloudFile>>(){}.getType();
            return gson.fromJson(root.get("data"), type);
        }
    }

    public void restoreFile(String relativePath) throws Exception {
        String encoded = URLEncoder.encode(relativePath, StandardCharsets.UTF_8.name());
        Request request = authBuilder()
                .url(baseUrl() + "/api/files/restore?path=" + encoded)
                .put(RequestBody.create("", JSON))
                .build();
        execute(request);
    }

    public void permanentDeleteFromTrash(String relativePath) throws Exception {
        String encoded = URLEncoder.encode(relativePath, StandardCharsets.UTF_8.name());
        Request request = authBuilder()
                .url(baseUrl() + "/api/files/trash?path=" + encoded)
                .delete()
                .build();
        execute(request);
    }

    public int fetchTrashRetentionDays() throws Exception {
        Request request = authBuilder().url(baseUrl() + "/api/config").get().build();
        JsonObject data = execute(request);
        if (data != null && data.has("trashRetentionDays")) {
            return data.get("trashRetentionDays").getAsInt();
        }
        return 30;
    }

    public UploadInitResult initUpload(String fileName, String relativePath, long totalSize,
                                       String mimeType, String sha256, Long capturedAt) throws Exception {
        JsonObject body = new JsonObject();
        body.addProperty("fileName", fileName);
        body.addProperty("relativePath", relativePath);
        body.addProperty("totalSize", totalSize);
        body.addProperty("mimeType", mimeType);
        body.addProperty("sha256", sha256);
        if (capturedAt != null && capturedAt > 0) {
            body.addProperty("capturedAt", capturedAt);
        }
        body.addProperty("source", "ANDROID_SYNC");
        Request request = authBuilder()
                .url(baseUrl() + "/api/upload/init")
                .post(RequestBody.create(body.toString(), JSON))
                .build();
        JsonObject data = execute(request);
        return gson.fromJson(data, UploadInitResult.class);
    }

    public void uploadChunk(String uploadId, int chunkIndex, byte[] data) throws Exception {
        RequestBody body = RequestBody.create(data, MediaType.parse("application/octet-stream"));
        Request request = authBuilder()
                .url(baseUrl() + "/api/upload/" + uploadId + "/chunk/" + chunkIndex)
                .put(body)
                .build();
        execute(request);
    }

    public UploadInitResult getUploadStatus(String uploadId) throws Exception {
        Request request = authBuilder()
                .url(baseUrl() + "/api/upload/" + uploadId + "/status")
                .get()
                .build();
        JsonObject data = execute(request);
        return gson.fromJson(data, UploadInitResult.class);
    }

    public int reconcileDisk(String category) throws Exception {
        String encoded = URLEncoder.encode(category == null ? "ALL" : category, StandardCharsets.UTF_8.name());
        Request request = authBuilder()
                .url(baseUrl() + "/api/files/reconcile?category=" + encoded)
                .post(RequestBody.create(new byte[0], null))
                .build();
        JsonObject data = execute(request);
        return data.has("indexed") ? data.get("indexed").getAsInt() : 0;
    }

    public List<CloudFile> listFiles(String path) throws Exception {
        String encoded = URLEncoder.encode(path == null ? "" : path, StandardCharsets.UTF_8.name());
        Request request = authBuilder()
                .url(baseUrl() + "/api/files?path=" + encoded)
                .get()
                .build();
        try (Response response = client.newCall(request).execute()) {
            String body = response.body() != null ? response.body().string() : "";
            JsonObject root = gson.fromJson(body, JsonObject.class);
            if (root == null || root.get("code").getAsInt() != 200) {
                String msg = root != null && root.has("message") ? root.get("message").getAsString() : "请求失败";
                throw new Exception(msg);
            }
            Type type = new TypeToken<List<CloudFile>>(){}.getType();
            return gson.fromJson(root.get("data"), type);
        }
    }

    public AlbumPhotoPage fetchAlbumPhotos(String fileType, long beforeTime, int limit) throws Exception {
        String encodedType = URLEncoder.encode(fileType == null ? "PHOTO" : fileType, StandardCharsets.UTF_8.name());
        Request request = authBuilder()
                .url(baseUrl() + "/api/files/album-photos?fileType=" + encodedType
                        + "&beforeTime=" + beforeTime + "&limit=" + limit)
                .get()
                .build();
        try (Response response = client.newCall(request).execute()) {
            String body = response.body() != null ? response.body().string() : "";
            JsonObject root = gson.fromJson(body, JsonObject.class);
            if (root == null || root.get("code").getAsInt() != 200) {
                String msg = root != null && root.has("message") ? root.get("message").getAsString() : "请求失败";
                throw new Exception(msg);
            }
            return gson.fromJson(root.get("data"), AlbumPhotoPage.class);
        }
    }

    public List<SyncIndexItem> fetchSyncIndex() throws Exception {
        return fetchSyncIndexPage(null).items;
    }

    public SyncIndexPage fetchSyncIndexPage(Long since) throws Exception {
        return fetchSyncIndexPage(since, null, 500);
    }

    public SyncIndexPage fetchSyncIndexPage(Long since, String cursor, int limit) throws Exception {
        StringBuilder url = new StringBuilder(baseUrl()).append("/api/files/sync-index?");
        boolean hasParam = false;
        if (since != null && since > 0) {
            url.append("since=").append(since);
            hasParam = true;
        }
        if (cursor != null && !cursor.isEmpty()) {
            if (hasParam) {
                url.append('&');
            }
            url.append("cursor=").append(URLEncoder.encode(cursor, StandardCharsets.UTF_8.name()));
            hasParam = true;
        }
        if (since == null || since <= 0) {
            if (hasParam) {
                url.append('&');
            }
            url.append("limit=").append(Math.max(1, Math.min(limit, 500)));
        }
        Request request = authBuilder().url(url.toString()).get().build();
        try (Response response = client.newCall(request).execute()) {
            String body = response.body() != null ? response.body().string() : "";
            JsonObject root = gson.fromJson(body, JsonObject.class);
            if (root == null || root.get("code").getAsInt() != 200) {
                String msg = root != null && root.has("message") ? root.get("message").getAsString() : "请求失败";
                throw new Exception(msg);
            }
            SyncIndexPage page = gson.fromJson(root.get("data"), SyncIndexPage.class);
            if (page == null) {
                page = new SyncIndexPage();
            }
            if (page.items == null) {
                page.items = new java.util.ArrayList<>();
            }
            if (page.removedPaths == null) {
                page.removedPaths = new java.util.ArrayList<>();
            }
            return page;
        }
    }

    public VerifySyncResponse verifySync(List<Long> fileIds) throws Exception {
        JsonObject body = new JsonObject();
        com.google.gson.JsonArray arr = new com.google.gson.JsonArray();
        for (Long id : fileIds) {
            if (id != null) {
                arr.add(id);
            }
        }
        body.add("fileIds", arr);
        return postVerifySync(body);
    }

    public VerifySyncResponse verifySyncItems(List<VerifySyncItem> items) throws Exception {
        JsonObject body = new JsonObject();
        com.google.gson.JsonArray arr = new com.google.gson.JsonArray();
        if (items != null) {
            for (VerifySyncItem item : items) {
                if (item == null) {
                    continue;
                }
                arr.add(item.toJsonObject());
            }
        }
        body.add("items", arr);
        return postVerifySync(body);
    }

    private VerifySyncResponse postVerifySync(JsonObject body) throws Exception {
        Request request = authBuilder()
                .url(baseUrl() + "/api/files/verify-sync")
                .post(RequestBody.create(body.toString(), JSON))
                .build();
        try (Response response = client.newCall(request).execute()) {
            String respBody = response.body() != null ? response.body().string() : "";
            JsonObject root = gson.fromJson(respBody, JsonObject.class);
            if (root == null || root.get("code").getAsInt() != 200) {
                String msg = root != null && root.has("message") ? root.get("message").getAsString() : "请求失败";
                throw new Exception(msg);
            }
            VerifySyncResponse result = gson.fromJson(root.get("data"), VerifySyncResponse.class);
            if (result == null) {
                result = new VerifySyncResponse();
            }
            if (result.invalidIds == null) {
                result.invalidIds = new java.util.ArrayList<>();
            }
            if (result.validItems == null) {
                result.validItems = new java.util.ArrayList<>();
            }
            if (result.rebuiltRecords == null) {
                result.rebuiltRecords = new java.util.ArrayList<>();
            }
            return result;
        }
    }

    public AlbumPhotoPage fetchSharedMedia(String scope, String fileType, long beforeTime, int limit) throws Exception {
        String encodedScope = URLEncoder.encode(scope, StandardCharsets.UTF_8.name());
        String encodedType = URLEncoder.encode(fileType == null ? "PHOTO" : fileType, StandardCharsets.UTF_8.name());
        Request request = authBuilder()
                .url(baseUrl() + "/api/files/shared?scope=" + encodedScope
                        + "&fileType=" + encodedType + "&beforeTime=" + beforeTime + "&limit=" + limit)
                .get()
                .build();
        try (Response response = client.newCall(request).execute()) {
            String body = response.body() != null ? response.body().string() : "";
            JsonObject root = gson.fromJson(body, JsonObject.class);
            if (root == null || root.get("code").getAsInt() != 200) {
                String msg = root != null && root.has("message") ? root.get("message").getAsString() : "请求失败";
                throw new Exception(msg);
            }
            return gson.fromJson(root.get("data"), AlbumPhotoPage.class);
        }
    }

    public int updateShare(java.util.List<String> paths, int share) throws Exception {
        JsonObject body = new JsonObject();
        com.google.gson.JsonArray arr = new com.google.gson.JsonArray();
        for (String path : paths) {
            arr.add(path);
        }
        body.add("paths", arr);
        body.addProperty("share", share);
        Request request = authBuilder()
                .url(baseUrl() + "/api/files/share")
                .put(RequestBody.create(body.toString(), JSON))
                .build();
        return executeShareUpdate(request, paths.size());
    }

    public int updateShareAll(String fileType, int share) throws Exception {
        String encodedType = URLEncoder.encode(fileType, StandardCharsets.UTF_8.name());
        Request request = authBuilder()
                .url(baseUrl() + "/api/files/share/all?fileType=" + encodedType + "&share=" + share)
                .put(RequestBody.create("{}", JSON))
                .build();
        return executeShareUpdate(request, 1);
    }

    /** 分享接口幂等；空响应/网络抖动时重试一次，避免服务端已成功但客户端误报失败 */
    private int executeShareUpdate(Request request, int fallbackCount) throws Exception {
        Exception lastError = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                JsonObject data = execute(request);
                if (data.has("updated")) {
                    return data.get("updated").getAsInt();
                }
                return Math.max(fallbackCount, 0);
            } catch (Exception e) {
                lastError = e;
                if (attempt == 0 && isRetryableShareError(e)) {
                    Thread.sleep(300L);
                    continue;
                }
                break;
            }
        }
        throw lastError != null ? lastError : new Exception("请求失败");
    }

    private static boolean isRetryableShareError(Exception error) {
        if (error instanceof java.io.IOException) {
            return true;
        }
        String msg = error.getMessage();
        return msg != null && (msg.contains("服务器无响应") || msg.contains("timeout"));
    }

    public byte[] downloadThumbnailBytes(String relativePath, Long ownerId) throws Exception {
        String encoded = URLEncoder.encode(relativePath, StandardCharsets.UTF_8.name());
        String url = baseUrl() + "/api/files/thumbnail?path=" + encoded;
        if (ownerId != null) {
            url += "&ownerId=" + ownerId;
        }
        Request request = authBuilder().url(url).get().build();
        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new Exception("缩略图下载失败 HTTP " + response.code());
            }
            if (response.body() == null) {
                throw new Exception("缩略图内容为空");
            }
            return response.body().bytes();
        }
    }

    public byte[] downloadThumbnailBytes(String relativePath) throws Exception {
        return downloadThumbnailBytes(relativePath, null);
    }

    public interface DownloadProgressListener {
        /** @param totalBytes 服务端 Content-Length，未知时为 -1 */
        void onProgress(long bytesRead, long totalBytes);
    }

    /** 流式下载大文件，避免整文件读入内存 */
    public void downloadFileStream(String relativePath, Long ownerId, OutputStream out) throws Exception {
        downloadFileStream(relativePath, ownerId, out, null);
    }

    public void downloadFileStream(String relativePath, Long ownerId, OutputStream out,
                                   DownloadProgressListener progress) throws Exception {
        Request request = authBuilder().url(buildDownloadUrl(relativePath, ownerId)).get().build();
        try (Response response = downloadClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new Exception("下载失败 HTTP " + response.code());
            }
            if (response.body() == null) {
                throw new Exception("下载内容为空");
            }
            long totalBytes = response.body().contentLength();
            if (progress != null && totalBytes > 0) {
                progress.onProgress(0, totalBytes);
            }
            try (InputStream in = response.body().byteStream()) {
                byte[] buffer = new byte[DOWNLOAD_BUFFER_SIZE];
                int read;
                long written = 0;
                while ((read = in.read(buffer)) != -1) {
                    out.write(buffer, 0, read);
                    written += read;
                    if (progress != null) {
                        progress.onProgress(written, totalBytes > 0 ? totalBytes : -1);
                    }
                }
                out.flush();
            }
        }
    }

    private static final int MAX_PREVIEW_BYTES = 8 * 1024 * 1024;

    public byte[] downloadFileBytes(String relativePath, Long ownerId) throws Exception {
        return downloadFileBytes(relativePath, ownerId, Integer.MAX_VALUE);
    }

    /** 限制最大读取字节，避免大图预览 OOM */
    public byte[] downloadFileBytes(String relativePath, Long ownerId, int maxBytes) throws Exception {
        Request request = authBuilder().url(buildDownloadUrl(relativePath, ownerId)).get().build();
        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new Exception("下载失败 HTTP " + response.code());
            }
            if (response.body() == null) {
                throw new Exception("下载内容为空");
            }
            long contentLength = response.body().contentLength();
            if (contentLength > maxBytes) {
                throw new Exception("文件过大，请下载后查看");
            }
            try (InputStream in = response.body().byteStream();
                 java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream()) {
                byte[] buffer = new byte[DOWNLOAD_BUFFER_SIZE];
                int read;
                int total = 0;
                while ((read = in.read(buffer)) != -1) {
                    total += read;
                    if (total > maxBytes) {
                        throw new Exception("文件过大，请下载后查看");
                    }
                    out.write(buffer, 0, read);
                }
                return out.toByteArray();
            }
        }
    }

    private String buildDownloadUrl(String relativePath, Long ownerId) throws Exception {
        String encoded = URLEncoder.encode(relativePath, StandardCharsets.UTF_8.name());
        String url = baseUrl() + "/api/files/download?path=" + encoded;
        if (ownerId != null) {
            url += "&ownerId=" + ownerId;
        }
        return url;
    }

    /** 媒体直链，供 ExoPlayer 边下边播；鉴权走 Authorization Header（见 VideoPreviewActivity） */
    public String buildMediaFileUrl(String relativePath, Long ownerId) throws Exception {
        String encodedPath = URLEncoder.encode(relativePath, StandardCharsets.UTF_8.name());
        String url = baseUrl() + "/media/file?path=" + encodedPath;
        if (ownerId != null) {
            url += "&ownerId=" + ownerId;
        }
        return url;
    }

    public byte[] downloadFileBytes(String relativePath) throws Exception {
        return downloadFileBytes(relativePath, null);
    }

    public JsonObject completeUpload(String uploadId) throws Exception {
        Request request = authBuilder()
                .url(baseUrl() + "/api/upload/" + uploadId + "/complete")
                .post(RequestBody.create("{}", JSON))
                .build();
        return execute(request);
    }

    public enum TokenValidation {
        VALID,
        EXPIRED,
        FAILED,
        NETWORK_ERROR
    }

    /** 轻量校验本地 token 是否仍被服务端接受 */
    public TokenValidation validateToken() {
        if (prefs.getToken() == null) {
            return TokenValidation.EXPIRED;
        }
        Request request = authBuilder().url(baseUrl() + "/api/config").get().build();
        try (Response response = client.newCall(request).execute()) {
            if (response.code() == 401 || response.code() == 403) {
                return TokenValidation.EXPIRED;
            }
            if (!response.isSuccessful()) {
                return TokenValidation.FAILED;
            }
            String body = response.body() != null ? response.body().string() : "";
            if (body.isEmpty()) {
                return TokenValidation.FAILED;
            }
            JsonObject root = gson.fromJson(body, JsonObject.class);
            if (root == null || !root.has("code")) {
                return TokenValidation.FAILED;
            }
            if (root.get("code").getAsInt() != 200) {
                return TokenValidation.EXPIRED;
            }
            return TokenValidation.VALID;
        } catch (Exception e) {
            return TokenValidation.NETWORK_ERROR;
        }
    }

    private Request.Builder authBuilder() {
        return new Request.Builder()
                .header("Authorization", "Bearer " + prefs.getToken());
    }

    private JsonObject execute(Request request) throws Exception {
        try (Response response = client.newCall(request).execute()) {
            String body = response.body() != null ? response.body().string() : "";
            if (body.isEmpty()) {
                if (response.code() == 401 || response.code() == 403) {
                    throw new AuthFailureException(response.code(), "登录已失效，请重新登录");
                }
                if (response.isSuccessful()) {
                    return new JsonObject();
                }
                throw new Exception("服务器无响应 (HTTP " + response.code() + ")");
            }
            JsonObject root = gson.fromJson(body, JsonObject.class);
            if (root == null || !root.has("code")) {
                if (response.code() == 401 || response.code() == 403) {
                    throw new AuthFailureException(response.code(), "登录已失效，请重新登录");
                }
                throw new Exception("服务器响应无效 (HTTP " + response.code() + ")");
            }
            int code = root.get("code").getAsInt();
            if (code == 401 || code == 403) {
                String msg = root.has("message") ? root.get("message").getAsString() : "登录已失效，请重新登录";
                throw new AuthFailureException(code, msg);
            }
            if (code != 200) {
                String msg = root.has("message") ? root.get("message").getAsString() : "请求失败";
                throw new Exception(msg);
            }
            if (!root.has("data") || root.get("data").isJsonNull()) {
                return new JsonObject();
            }
            return root.getAsJsonObject("data");
        }
    }

    private String baseUrl() {
        String url = prefs.getServerUrl();
        if (url == null || url.isEmpty()) {
            throw new IllegalStateException("服务器地址未配置");
        }
        if (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        return url;
    }

    public static class LoginResult {
        public final String token;
        public final String username;
        public final String userId;
        public final String role;

        public LoginResult(String token, String username, String userId, String role) {
            this.token = token;
            this.username = username;
            this.userId = userId;
            this.role = role;
        }
    }

    public static class UploadInitResult {
        public String uploadId;
        public int chunkSize;
        public int totalChunks;
        public java.util.List<Integer> uploadedChunks;
        public String status;
    }
}
