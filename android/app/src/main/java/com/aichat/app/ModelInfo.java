package com.aichat.app;

import org.json.JSONObject;

public class ModelInfo {
    private String id;
    private String name;
    private String provider;
    private boolean supportsReasoning;
    private boolean supportsVision;
    private boolean supportsFiles;
    private int maxTokens;

    public ModelInfo(String id, String name, String provider,
                     boolean supportsReasoning, boolean supportsVision,
                     boolean supportsFiles, int maxTokens) {
        this.id = id;
        this.name = name;
        this.provider = provider;
        this.supportsReasoning = supportsReasoning;
        this.supportsVision = supportsVision;
        this.supportsFiles = supportsFiles;
        this.maxTokens = maxTokens;
    }

    public static ModelInfo fromJson(JSONObject obj) {
        return new ModelInfo(
            obj.optString("id", ""),
            obj.optString("name", ""),
            obj.optString("provider", "unknown"),
            obj.optBoolean("supports_reasoning", false),
            obj.optBoolean("supports_vision", false),
            obj.optBoolean("supports_files", true),
            obj.optInt("max_tokens", 4096)
        );
    }

    public String getId() { return id; }
    public String getName() { return name; }
    public String getProvider() { return provider; }
    public boolean supportsReasoning() { return supportsReasoning; }
    public boolean supportsVision() { return supportsVision; }
    public boolean supportsFiles() { return supportsFiles; }
    public int getMaxTokens() { return maxTokens; }

    public String getDisplayName() {
        return name + " (" + provider + ")";
    }
}
