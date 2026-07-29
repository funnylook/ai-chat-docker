package com.aichat.app;

import android.content.Context;
import android.content.SharedPreferences;

public class ConfigManager {

    private static final String PREF_NAME = "ai_chat_config";
    private static final String KEY_SERVER_URL = "server_url";
    private static final String KEY_SELECTED_MODEL = "selected_model";
    private static final String KEY_REASONING_EFFORT = "reasoning_effort";

    private static final String DEFAULT_SERVER_URL = "";
    private static final String DEFAULT_MODEL = "gpt-4o-mini";
    private static final String DEFAULT_EFFORT = "medium";

    private final SharedPreferences prefs;

    public ConfigManager(Context context) {
        prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
    }

    public String getServerUrl() {
        return prefs.getString(KEY_SERVER_URL, DEFAULT_SERVER_URL);
    }

    public void setServerUrl(String url) {
        prefs.edit().putString(KEY_SERVER_URL, url).apply();
    }

    public String getSelectedModel() {
        return prefs.getString(KEY_SELECTED_MODEL, DEFAULT_MODEL);
    }

    public void setSelectedModel(String model) {
        prefs.edit().putString(KEY_SELECTED_MODEL, model).apply();
    }

    public String getReasoningEffort() {
        return prefs.getString(KEY_REASONING_EFFORT, DEFAULT_EFFORT);
    }

    public void setReasoningEffort(String effort) {
        prefs.edit().putString(KEY_REASONING_EFFORT, effort).apply();
    }

    public boolean isConfigured() {
        return !getServerUrl().isEmpty();
    }
}
