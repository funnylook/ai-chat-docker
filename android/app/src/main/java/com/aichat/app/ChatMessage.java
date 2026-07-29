package com.aichat.app;

public class ChatMessage {

    public static final int ROLE_USER = 0;
    public static final int ROLE_ASSISTANT = 1;

    private int role;
    private String content;
    private String fileName;
    private boolean streaming;

    public ChatMessage(int role, String content) {
        this.role = role;
        this.content = content;
        this.fileName = null;
        this.streaming = false;
    }

    public ChatMessage(int role, String content, String fileName) {
        this.role = role;
        this.content = content;
        this.fileName = fileName;
        this.streaming = false;
    }

    public ChatMessage(int role, String content, String fileName, boolean streaming) {
        this.role = role;
        this.content = content;
        this.fileName = fileName;
        this.streaming = streaming;
    }

    public int getRole() { return role; }
    public void setRole(int role) { this.role = role; }

    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }

    public String getFileName() { return fileName; }
    public void setFileName(String fileName) { this.fileName = fileName; }

    public boolean hasFile() { return fileName != null && !fileName.isEmpty(); }

    public boolean isStreaming() { return streaming; }
    public void setStreaming(boolean streaming) { this.streaming = streaming; }

    public boolean isUser() { return role == ROLE_USER; }
    public boolean isAssistant() { return role == ROLE_ASSISTANT; }
}
