package dev.lean.pdf;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Process;
import android.os.SystemClock;
import android.text.InputType;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.Toast;

import java.io.File;

public final class MainActivity extends Activity implements PdfView.Listener {

    private static final int REQ_PICK = 1;

    // False until the first screen of a freshly started app; used to time from the tap, not from onCreate.
    private static boolean launched;

    private PdfView view;
    private Uri uri;

    @Override protected void onCreate(Bundle state) {
        long startedAt = SystemClock.uptimeMillis();
        if (!launched) {
            launched = true;
            startedAt = Process.getStartUptimeMillis(); // cold start: include Android launching the app
        }
        super.onCreate(state);
        // Draw edge-to-edge on every version; PdfView pads itself using window insets.
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        view = new PdfView(this);
        setContentView(view);

        Uri u = getIntent().getData();
        if (state != null) {
            Uri saved = state.getParcelable("uri");
            if (saved != null) u = saved;
            view.setPendingProgress(state.getFloat("pos", 0f));
            if (state.containsKey("night")) view.setNight(state.getBoolean("night")); // kept until the app closes
        }
        if (u != null) open(u, startedAt); else pick();
    }

    private void pick() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("application/pdf");
        try {
            startActivityForResult(i, REQ_PICK);
        } catch (ActivityNotFoundException e) {
            finish();
        }
    }

    @Override protected void onActivityResult(int req, int result, Intent data) {
        if (req != REQ_PICK) return;
        if (result == RESULT_OK && data != null && data.getData() != null) {
            open(data.getData(), SystemClock.uptimeMillis()); // time from the pick, not the picker
        } else if (uri == null) {
            finish();
        }
    }

    private void open(Uri u, long startedAt) {
        uri = u;
        view.open(getContentResolver(), u, new File(getCacheDir(), "copy.pdf"), startedAt, this);
    }

    @Override public void onLoaded() { }

    @Override public void onError(String message) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
        finish();
    }

    /** "Go to page" box, opened by tapping the page pill. */
    @Override public void onPageTap(int current, int count) {
        final EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        input.setImeOptions(EditorInfo.IME_ACTION_GO);
        input.setHint("1 - " + count);
        input.setText(String.valueOf(current + 1));
        input.selectAll();
        FrameLayout box = new FrameLayout(this);
        int pad = (int) (24 * getResources().getDisplayMetrics().density);
        box.setPadding(pad, pad / 2, pad, 0);
        box.addView(input);

        final AlertDialog d = new AlertDialog.Builder(this)
                .setTitle("Go to page")
                .setView(box)
                .setPositiveButton("Go", (x, w) -> goTo(input, count))
                .setNegativeButton("Cancel", null)
                .create();
        input.setOnEditorActionListener((v, action, ev) -> {
            goTo(input, count);
            d.dismiss();
            return true;
        });
        d.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
        d.show();
        input.requestFocus();
    }

    private void goTo(EditText input, int count) {
        try {
            int page = Integer.parseInt(input.getText().toString().trim());
            view.goToPage(Math.max(1, Math.min(count, page)) - 1);
        } catch (NumberFormatException ignored) { }
    }

    @Override protected void onPause() {
        view.savePosition();
        super.onPause();
    }

    @Override protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putParcelable("uri", uri);
        out.putFloat("pos", view.progress());
        out.putBoolean("night", view.isNight());
    }

    @Override protected void onDestroy() {
        view.close();
        super.onDestroy();
    }
}
