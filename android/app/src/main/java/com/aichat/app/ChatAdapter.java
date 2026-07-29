package com.aichat.app;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

public class ChatAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    private static final int VIEW_TYPE_USER = 1;
    private static final int VIEW_TYPE_ASSISTANT = 2;

    private final List<ChatMessage> messages = new ArrayList<>();

    public void addMessage(ChatMessage message) {
        messages.add(message);
        notifyItemInserted(messages.size() - 1);
    }

    public void updateLastAssistantMessage(String content) {
        if (messages.isEmpty()) return;
        int lastIndex = messages.size() - 1;
        ChatMessage last = messages.get(lastIndex);
        if (last.isAssistant()) {
            last.setContent(content);
            notifyItemChanged(lastIndex);
        }
    }

    public void markLastAssistantComplete() {
        if (messages.isEmpty()) return;
        int lastIndex = messages.size() - 1;
        ChatMessage last = messages.get(lastIndex);
        if (last.isAssistant()) {
            last.setStreaming(false);
            notifyItemChanged(lastIndex);
        }
    }

    public void removeLastIfStreaming() {
        if (messages.isEmpty()) return;
        int lastIndex = messages.size() - 1;
        ChatMessage last = messages.get(lastIndex);
        if (last.isAssistant() && last.isStreaming()) {
            messages.remove(lastIndex);
            notifyItemRemoved(lastIndex);
        }
    }

    public List<ChatMessage> getMessagesForApi() {
        List<ChatMessage> result = new ArrayList<>();
        for (ChatMessage msg : messages) {
            if (!msg.isStreaming()) {
                result.add(msg);
            }
        }
        return result;
    }

    public void clear() {
        messages.clear();
        notifyDataSetChanged();
    }

    @Override
    public int getItemViewType(int position) {
        return messages.get(position).isUser() ? VIEW_TYPE_USER : VIEW_TYPE_ASSISTANT;
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inflater = LayoutInflater.from(parent.getContext());
        if (viewType == VIEW_TYPE_USER) {
            View view = inflater.inflate(R.layout.item_message_user, parent, false);
            return new UserViewHolder(view);
        } else {
            View view = inflater.inflate(R.layout.item_message_assistant, parent, false);
            return new AssistantViewHolder(view);
        }
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        ChatMessage message = messages.get(position);
        if (holder instanceof UserViewHolder) {
            ((UserViewHolder) holder).bind(message);
        } else if (holder instanceof AssistantViewHolder) {
            ((AssistantViewHolder) holder).bind(message);
        }
    }

    @Override
    public int getItemCount() {
        return messages.size();
    }

    static class UserViewHolder extends RecyclerView.ViewHolder {
        final TextView textContent;
        final LinearLayout layoutFile;
        final TextView textFileName;
        final ImageView iconFile;

        UserViewHolder(@NonNull View itemView) {
            super(itemView);
            textContent = itemView.findViewById(R.id.text_content);
            layoutFile = itemView.findViewById(R.id.layout_file);
            textFileName = itemView.findViewById(R.id.text_file_name);
            iconFile = itemView.findViewById(R.id.icon_file);
        }

        void bind(ChatMessage message) {
            textContent.setText(message.getContent());
            if (message.hasFile()) {
                layoutFile.setVisibility(View.VISIBLE);
                textFileName.setText(message.getFileName());
            } else {
                layoutFile.setVisibility(View.GONE);
            }
        }
    }

    static class AssistantViewHolder extends RecyclerView.ViewHolder {
        final TextView textContent;

        AssistantViewHolder(@NonNull View itemView) {
            super(itemView);
            textContent = itemView.findViewById(R.id.text_content);
        }

        void bind(ChatMessage message) {
            textContent.setText(message.getContent());
        }
    }
}
