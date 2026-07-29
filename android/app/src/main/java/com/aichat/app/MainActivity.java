package com.aichat.app;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity {

    private ConfigManager configManager;
    private ApiService apiService;
    private ChatAdapter chatAdapter;
    private RecyclerView recyclerView;
    private EditText editMessage;
    private ImageButton btnSend;
    private ImageButton btnAttach;
    private ProgressBar progressBar;
    private Spinner spinnerModel;
    private Spinner spinnerEffort;
    private LinearLayout layoutFilePreview;
    private TextView textFilePreview;
    private ImageButton btnRemoveFile;

    private boolean isStreaming = false;
    private String currentStreamContent = "";
    private List<ModelInfo> modelList = new ArrayList<>();
    private File pendingFile = null;

    private final ActivityResultLauncher<String[]> filePickerLauncher =
            registerForActivityResult(new ActivityResultContracts.OpenDocument(), this::onFilePicked);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        configManager = new ConfigManager(this);
        apiService = new ApiService(configManager);

        recyclerView = findViewById(R.id.recycler_chat);
        editMessage = findViewById(R.id.edit_message);
        btnSend = findViewById(R.id.btn_send);
        btnAttach = findViewById(R.id.btn_attach);
        progressBar = findViewById(R.id.progress_bar);
        spinnerModel = findViewById(R.id.spinner_model);
        spinnerEffort = findViewById(R.id.spinner_effort);
        layoutFilePreview = findViewById(R.id.layout_file_preview);
        textFilePreview = findViewById(R.id.text_file_preview);
        btnRemoveFile = findViewById(R.id.btn_remove_file);

        chatAdapter = new ChatAdapter();
        LinearLayoutManager layoutManager = new LinearLayoutManager(this);
        layoutManager.setStackFromEnd(true);
        recyclerView.setLayoutManager(layoutManager);
        recyclerView.setAdapter(chatAdapter);

        btnSend.setOnClickListener(v -> onSendClicked());
        btnAttach.setOnClickListener(v -> openFilePicker());
        btnRemoveFile.setOnClickListener(v -> removePendingFile());

        setupEffortSpinner();

        spinnerModel.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (position < modelList.size()) {
                    configManager.setSelectedModel(modelList.get(position).getId());
                    updateEffortVisibility();
                }
            }
            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });

        if (!configManager.isConfigured()) {
            showConfigRequiredDialog();
        } else {
            fetchModels();
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_main, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.action_settings) {
            startActivity(new Intent(this, SettingsActivity.class));
            return true;
        } else if (id == R.id.action_clear) {
            new AlertDialog.Builder(this)
                    .setTitle("清空对话")
                    .setMessage("确定要清空所有对话记录吗？")
                    .setPositiveButton("确定", (d, w) -> chatAdapter.clear())
                    .setNegativeButton("取消", null)
                    .show();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    protected void onResume() {
        super.onResume();
        apiService = new ApiService(configManager);
        if (configManager.isConfigured()) {
            fetchModels();
        }
    }

    private void fetchModels() {
        apiService.fetchModels(new ApiService.ModelCallback() {
            @Override
            public void onSuccess(List<ModelInfo> models) {
                modelList = models;
                populateModelSpinner(models);
            }

            @Override
            public void onError(String message) {
                Toast.makeText(MainActivity.this, "获取模型失败: " + message, Toast.LENGTH_LONG).show();
            }
        });
    }

    private void populateModelSpinner(List<ModelInfo> models) {
        List<String> names = new ArrayList<>();
        for (ModelInfo m : models) {
            names.add(m.getDisplayName());
        }

        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, names);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerModel.setAdapter(adapter);

        // Select saved model
        String savedModel = configManager.getSelectedModel();
        for (int i = 0; i < models.size(); i++) {
            if (models.get(i).getId().equals(savedModel)) {
                spinnerModel.setSelection(i);
                break;
            }
        }
        updateEffortVisibility();
    }

    private void setupEffortSpinner() {
        List<String> efforts = new ArrayList<>();
        efforts.add("低 (快速)");
        efforts.add("中 (平衡)");
        efforts.add("高 (深度推理)");

        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, efforts);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerEffort.setAdapter(adapter);

        // Set saved effort
        switch (configManager.getReasoningEffort()) {
            case "low": spinnerEffort.setSelection(0); break;
            case "high": spinnerEffort.setSelection(2); break;
            default: spinnerEffort.setSelection(1); break;
        }

        spinnerEffort.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                String effort;
                switch (position) {
                    case 0: effort = "low"; break;
                    case 2: effort = "high"; break;
                    default: effort = "medium"; break;
                }
                configManager.setReasoningEffort(effort);
            }
            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });
    }

    private void updateEffortVisibility() {
        int pos = spinnerModel.getSelectedItemPosition();
        if (pos >= 0 && pos < modelList.size()) {
            boolean supportsReasoning = modelList.get(pos).supportsReasoning();
            spinnerEffort.setEnabled(supportsReasoning);
            spinnerEffort.setAlpha(supportsReasoning ? 1.0f : 0.4f);
        }
    }

    private void openFilePicker() {
        filePickerLauncher.launch(new String[]{"*/*"});
    }

    private void onFilePicked(Uri uri) {
        if (uri == null) return;

        try {
            // Copy file to cache
            String fileName = getFileName(uri);
            File cacheFile = new File(getCacheDir(), fileName);

            InputStream inputStream = getContentResolver().openInputStream(uri);
            FileOutputStream outputStream = new FileOutputStream(cacheFile);
            byte[] buffer = new byte[8192];
            int bytesRead;
            while ((bytesRead = inputStream.read(buffer)) != -1) {
                outputStream.write(buffer, 0, bytesRead);
            }
            outputStream.close();
            inputStream.close();

            pendingFile = cacheFile;
            showFilePreview(fileName);
        } catch (Exception e) {
            Toast.makeText(this, "文件读取失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private String getFileName(Uri uri) {
        String result = "file";
        try (android.database.Cursor cursor = getContentResolver().query(uri, null, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) {
                    result = cursor.getString(idx);
                }
            }
        } catch (Exception ignored) {}
        return result;
    }

    private void showFilePreview(String fileName) {
        textFilePreview.setText("📎 " + fileName);
        layoutFilePreview.setVisibility(View.VISIBLE);
    }

    private void removePendingFile() {
        pendingFile = null;
        layoutFilePreview.setVisibility(View.GONE);
    }

    private void onSendClicked() {
        if (isStreaming) {
            apiService.cancelCurrentRequest();
            isStreaming = false;
            chatAdapter.removeLastIfStreaming();
            btnSend.setImageResource(R.drawable.ic_send);
            progressBar.setVisibility(View.GONE);
            return;
        }

        String text = editMessage.getText().toString().trim();
        if (TextUtils.isEmpty(text) && pendingFile == null) return;

        if (!configManager.isConfigured()) {
            showConfigRequiredDialog();
            return;
        }

        // Build display content
        String displayContent = text;
        String fileName = pendingFile != null ? pendingFile.getName() : null;

        chatAdapter.addMessage(new ChatMessage(ChatMessage.ROLE_USER, displayContent, fileName));
        editMessage.setText("");
        scrollToBottom();

        currentStreamContent = "";
        ChatMessage assistantMsg = new ChatMessage(ChatMessage.ROLE_ASSISTANT, "", null, true);
        chatAdapter.addMessage(assistantMsg);
        scrollToBottom();

        isStreaming = true;
        btnSend.setImageResource(R.drawable.ic_stop);
        progressBar.setVisibility(View.VISIBLE);

        // Get selected model
        int modelPos = spinnerModel.getSelectedItemPosition();
        String modelId = modelPos >= 0 && modelPos < modelList.size()
                ? modelList.get(modelPos).getId()
                : configManager.getSelectedModel();

        // Get reasoning effort
        String effort;
        switch (spinnerEffort.getSelectedItemPosition()) {
            case 0: effort = "low"; break;
            case 2: effort = "high"; break;
            default: effort = "medium"; break;
        }

        File fileToSend = pendingFile;
        removePendingFile();

        apiService.sendMessage(chatAdapter.getMessagesForApi(), modelId, effort, fileToSend,
                new ApiService.StreamCallback() {
                    @Override
                    public void onStreamToken(String token) {
                        currentStreamContent += token;
                        chatAdapter.updateLastAssistantMessage(currentStreamContent);
                        scrollToBottom();
                    }

                    @Override
                    public void onStreamComplete(String fullContent) {
                        isStreaming = false;
                        btnSend.setImageResource(R.drawable.ic_send);
                        progressBar.setVisibility(View.GONE);
                        chatAdapter.markLastAssistantComplete();
                        scrollToBottom();
                    }

                    @Override
                    public void onError(String errorMessage) {
                        isStreaming = false;
                        btnSend.setImageResource(R.drawable.ic_send);
                        progressBar.setVisibility(View.GONE);
                        chatAdapter.updateLastAssistantMessage("错误: " + errorMessage);
                        Toast.makeText(MainActivity.this, errorMessage, Toast.LENGTH_LONG).show();
                    }
                });
    }

    private void scrollToBottom() {
        if (chatAdapter.getItemCount() > 0) {
            recyclerView.smoothScrollToPosition(chatAdapter.getItemCount() - 1);
        }
    }

    private void showConfigRequiredDialog() {
        new AlertDialog.Builder(this)
                .setTitle("需要配置")
                .setMessage("请先设置服务器地址")
                .setPositiveButton("去设置", (d, w) -> {
                    startActivity(new Intent(this, SettingsActivity.class));
                })
                .setNegativeButton("取消", null)
                .show();
    }
}
