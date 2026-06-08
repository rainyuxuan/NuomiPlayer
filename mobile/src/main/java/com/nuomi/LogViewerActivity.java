package com.nuomi;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 应用内日志查看器：读自己进程的 logcat（Android 允许 App 读自己进程的日志，无需特殊权限）。
 * 主要用于在不连电脑的情况下查看 PathB / Mirror / Sniffer 等 tag 的运行情况。
 *
 * 安全性：tag 严格限定在白名单内（{@link #TAGS}），命令通过 ProcessBuilder 以参数数组形式传入，
 * 不经 shell；无任何用户输入会进入命令行。
 */
public class LogViewerActivity extends AppCompatActivity {

    private static final String[] TAGS = {"全部", "PathB", "Mirror", "Sniffer"};
    private static final Set<String> ALLOWED_TAGS = new HashSet<>(Arrays.asList(TAGS));
    private static final int LOG_LINES = 1000;

    private TextView logText;
    private String currentTag = "全部";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(buildLayout());
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(android.R.id.content), (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return insets;
        });
        refresh();
    }

    private View buildLayout() {
        int pad = (int) (16 * getResources().getDisplayMetrics().density);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);

        // 顶部操作栏
        LinearLayout topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        TextView title = new TextView(this);
        title.setText("日志");
        title.setTextSize(20);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        topBar.addView(title, titleLp);

        topBar.addView(makeButton("刷新", v -> refresh()));
        topBar.addView(makeButton("复制", v -> copyAll()));
        topBar.addView(makeButton("分享", v -> shareAll()));
        root.addView(topBar);

        // tag 过滤
        HorizontalScrollView filterScroll = new HorizontalScrollView(this);
        RadioGroup tagGroup = new RadioGroup(this);
        tagGroup.setOrientation(RadioGroup.HORIZONTAL);
        for (int i = 0; i < TAGS.length; i++) {
            RadioButton rb = new RadioButton(this);
            rb.setText(TAGS[i]);
            rb.setId(View.generateViewId());
            rb.setTag(TAGS[i]);
            if (i == 0) rb.setChecked(true);
            tagGroup.addView(rb);
        }
        tagGroup.setOnCheckedChangeListener((g, id) -> {
            RadioButton b = g.findViewById(id);
            if (b != null) {
                Object t = b.getTag();
                if (t instanceof String && ALLOWED_TAGS.contains(t)) {
                    currentTag = (String) t;
                    refresh();
                }
            }
        });
        filterScroll.addView(tagGroup);
        root.addView(filterScroll);

        // 日志显示区
        ScrollView logScroll = new ScrollView(this);
        HorizontalScrollView hScroll = new HorizontalScrollView(this);
        logText = new TextView(this);
        logText.setTypeface(android.graphics.Typeface.MONOSPACE);
        logText.setTextSize(10);
        logText.setHorizontallyScrolling(true);
        logText.setTextIsSelectable(true);
        hScroll.addView(logText);
        logScroll.addView(hScroll);
        LinearLayout.LayoutParams logLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        root.addView(logScroll, logLp);

        return root;
    }

    private android.widget.Button makeButton(String label, View.OnClickListener listener) {
        android.widget.Button b = new android.widget.Button(this);
        b.setText(label);
        b.setOnClickListener(listener);
        return b;
    }

    private void refresh() {
        final String tagAtRequest = currentTag;
        new Thread(() -> {
            String content = readOwnLogcat(tagAtRequest);
            runOnUiThread(() -> {
                if (logText == null) return;
                logText.setText(TextUtils.isEmpty(content)
                        ? "（暂无匹配日志，刷一刷或换 tag）" : content);
            });
        }, "log-reader").start();
    }

    /**
     * 读自己进程的 logcat。Android 7+ 普通 App 在没有 READ_LOGS 权限时只能拿到自己进程的日志，
     * 这正是我们要的；不需要任何额外权限。
     *
     * 调用方式：用 {@link ProcessBuilder}（不经 shell，安全），参数数组化构造，
     * 且 tag 在调用前已经在白名单内校验过（见 {@link #ALLOWED_TAGS}）。
     */
    private String readOwnLogcat(String tag) {
        if (!ALLOWED_TAGS.contains(tag)) {
            return "非法 tag: " + tag;
        }
        List<String> argv = new ArrayList<>();
        argv.add("logcat");
        argv.add("-d");
        argv.add("-t");
        argv.add(String.valueOf(LOG_LINES));
        argv.add("-v");
        argv.add("time");
        if (!"全部".equals(tag)) {
            argv.add(tag + ":I");
            argv.add("*:S");
        }

        StringBuilder sb = new StringBuilder();
        Process p = null;
        try {
            p = new ProcessBuilder(argv).redirectErrorStream(true).start();
            try (BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line;
                while ((line = br.readLine()) != null) {
                    sb.append(line).append('\n');
                }
            }
        } catch (Exception e) {
            sb.append("读 logcat 失败: ").append(e.getMessage());
        } finally {
            if (p != null) p.destroy();
        }
        return sb.toString();
    }

    private void copyAll() {
        if (logText == null) return;
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm == null) return;
        cm.setPrimaryClip(ClipData.newPlainText("nuomi-log", logText.getText()));
        Toast.makeText(this, "已复制到剪贴板", Toast.LENGTH_SHORT).show();
    }

    private void shareAll() {
        if (logText == null) return;
        Intent intent = new Intent(Intent.ACTION_SEND)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_TEXT, logText.getText().toString())
                .putExtra(Intent.EXTRA_SUBJECT, "糯米日志 · " + currentTag);
        startActivity(Intent.createChooser(intent, "分享日志"));
    }
}
