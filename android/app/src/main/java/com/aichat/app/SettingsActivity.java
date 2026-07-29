package com.aichat.app;

import android.os.Bundle;
import android.text.TextUtils;
import android.view.MenuItem;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

public class SettingsActivity extends AppCompatActivity {

    private ConfigManager configManager;
    private EditText editServerUrl;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }

        configManager = new ConfigManager(this);
        editServerUrl = findViewById(R.id.edit_server_url);
        Button btnSave = findViewById(R.id.btn_save);

        editServerUrl.setText(configManager.getServerUrl());

        btnSave.setOnClickListener(v -> saveConfig());
    }

    private void saveConfig() {
        String url = editServerUrl.getText().toString().trim();

        if (TextUtils.isEmpty(url)) {
            Toast.makeText(this, "服务器地址不能为空", Toast.LENGTH_SHORT).show();
            return;
        }

        // Remove trailing slash
        if (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }

        configManager.setServerUrl(url);
        Toast.makeText(this, "保存成功", Toast.LENGTH_SHORT).show();
        finish();
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            onBackPressed();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }
}
