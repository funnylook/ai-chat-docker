package com.aichat.app;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

public class ApiService {

    private static final String TAG = "ApiService";
    private static final int TIMEOUT_SECONDS = 120;

    private final ConfigManager config;
    private final OkHttpClient client;
    private final Handler mainHandler;
    private Call currentCall;

    public interface ModelCallback {
        void onSuccess(List<ModelInfo> models);
        void onError(String message);
    }

    public interface StreamCallback {
        void onStreamToken(String token);
        void onStreamComplete(String fullContent);
        void onError(String errorMessage);
    }

    public ApiService(ConfigManager config) {
        this.config = config;
        this.client = new OkHttpClient.Builder()
                .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .writeTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .build();
        this.mainHandler = new Handler(Looper.getMainLooper());
    }

    public void fetchModels(ModelCallback callback) {
        String baseUrl = config.getServerUrl();
        if (!baseUrl.endsWith("/")) baseUrl += "/";
        String url = baseUrl + "api/models";

        Request request = new Request.Builder().url(url).get().build();

        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                mainHandler.post(() -> callback.onError("网络错误: " + e.getMessage()));
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                if (!response.isSuccessful()) {
                    final String err = "HTTP " + response.code();
                    mainHandler.post(() -> callback.onError(err));
                    return;
                }
                try {
                    String body = response.body() != null ? response.body().string() : "";
                    JSONObject json = new JSONObject(body);
                    JSONArray modelsArray = json.getJSONArray("models");
                    List<ModelInfo> models = new java.util.ArrayList<>();
                    for (int i = 0; i < modelsArray.length(); i++) {
                        models.add(ModelInfo.fromJson(modelsArray.getJSONObject(i)));
                    }
                    mainHandler.post(() -> callback.onSuccess(models));
                } catch (JSONException e) {
                    mainHandler.post(() -> callback.onError("解析失败: " + e.getMessage()));
                }
            }
        });
    }

    public void sendMessage(List<ChatMessage> messages, String modelId,
                            String reasoningEffort, File attachedFile, StreamCallback callback) {
        String baseUrl = config.getServerUrl();
        if (!baseUrl.endsWith("/")) baseUrl += "/";
        String url = baseUrl + "api/chat";

        // Build messages JSON
        JSONArray messagesArray = new JSONArray();
        for (ChatMessage msg : messages) {
            if (!msg.isStreaming()) {
                try {
                    JSONObject msgObj = new JSONObject();
                    msgObj.put("role", msg.isUser() ? "user" : "assistant");
                    msgObj.put("content", msg.getContent());
                    messagesArray.put(msgObj);
                } catch (JSONException ignored) {}
            }
        }

        // Build multipart form
        MultipartBody.Builder formBuilder = new MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("model", modelId)
                .addFormDataPart("reasoning_effort", reasoningEffort)
                .addFormDataPart("messages", messagesArray.toString());

        if (attachedFile != null && attachedFile.exists()) {
            try {
                byte[] fileBytes = new byte[(int) attachedFile.length()];
                FileInputStream fis = new FileInputStream(attachedFile);
                fis.read(fileBytes);
                fis.close();

                String mime = guessMimeType(attachedFile.getName());
                formBuilder.addFormDataPart("file", attachedFile.getName(),
                        RequestBody.create(fileBytes, MediaType.parse(mime)));
            } catch (IOException e) {
                callback.onError("文件读取失败: " + e.getMessage());
                return;
            }
        }

        Request request = new Request.Builder()
                .url(url)
                .post(formBuilder.build())
                .build();

        currentCall = client.newCall(request);
        currentCall.enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                mainHandler.post(() -> callback.onError("网络错误: " + e.getMessage()));
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                if (!response.isSuccessful()) {
                    String errorBody = response.body() != null ? response.body().string() : "";
                    final String err = "API错误 " + response.code() + ": " + errorBody;
                    mainHandler.post(() -> callback.onError(err));
                    return;
                }

                try (ResponseBody responseBody = response.body()) {
                    if (responseBody == null) {
                        mainHandler.post(() -> callback.onError("响应为空"));
                        return;
                    }

                    java.io.InputStream inputStream = responseBody.byteStream();
                    java.io.BufferedReader reader = new java.io.BufferedReader(
                            new java.io.InputStreamReader(inputStream, java.nio.charset.StandardCharsets.UTF_8));

                    StringBuilder fullContent = new StringBuilder();
                    String line;

                    while ((line = reader.readLine()) != null) {
                        if (call.isCanceled()) break;

                        if (line.startsWith("data: ")) {
                            String data = line.substring(6).trim();
                            if ("[DONE]".equals(data)) break;

                            try {
                                JSONObject chunk = new JSONObject(data);
                                if (chunk.has("error")) {
                                    final String errMsg = chunk.getString("error");
                                    mainHandler.post(() -> callback.onError(errMsg));
                                    return;
                                }
                                JSONArray choices = chunk.optJSONArray("choices");
                                if (choices != null && choices.length() > 0) {
                                    JSONObject delta = choices.getJSONObject(0).optJSONObject("delta");
                                    if (delta != null) {
                                        String token = delta.optString("content", "");
                                        if (!token.isEmpty()) {
                                            fullContent.append(token);
                                            final String t = token;
                                            mainHandler.post(() -> callback.onStreamToken(t));
                                        }
                                    }
                                }
                            } catch (JSONException ignored) {}
                        }
                    }

                    final String result = fullContent.toString();
                    mainHandler.post(() -> callback.onStreamComplete(result));

                } catch (IOException e) {
                    mainHandler.post(() -> callback.onError("读取响应失败: " + e.getMessage()));
                }
            }
        });
    }

    public void cancelCurrentRequest() {
        if (currentCall != null && !currentCall.isCanceled()) {
            currentCall.cancel();
        }
    }

    private String guessMimeType(String filename) {
        String lower = filename.toLowerCase();
        if (lower.endsWith(".pdf")) return "application/pdf";
        if (lower.endsWith(".doc")) return "application/msword";
        if (lower.endsWith(".docx")) return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
        if (lower.endsWith(".txt")) return "text/plain";
        if (lower.endsWith(".md")) return "text/markdown";
        if (lower.endsWith(".csv")) return "text/csv";
        if (lower.endsWith(".json")) return "application/json";
        if (lower.endsWith(".xml")) return "application/xml";
        if (lower.endsWith(".html")) return "text/html";
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".gif")) return "image/gif";
        if (lower.endsWith(".webp")) return "image/webp";
        if (lower.endsWith(".mp3")) return "audio/mpeg";
        if (lower.endsWith(".mp4")) return "video/mp4";
        if (lower.endsWith(".zip")) return "application/zip";
        return "application/octet-stream";
    }
}
