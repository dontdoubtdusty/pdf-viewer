package dev.lean.pdf;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.Toast;

import java.io.File;

public final class MainActivity extends Activity implements PdfView.Listener {

    private static final int REQ_PICK = 1;

    private PdfView view;
    private Uri uri;

    @Override protected void onCreate(Bundle state) {
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
        }
        if (u != null) open(u); else pick();
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
        if (result == RESULT_OK && data != null && data.getData() != null) open(data.getData());
        else if (uri == null) finish();
    }

    private void open(Uri u) {
        uri = u;
        view.open(getContentResolver(), u, new File(getCacheDir(), "copy.pdf"), this);
    }

    @Override public void onLoaded() { }

    @Override public void onError(String message) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
        finish();
    }

    @Override protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putParcelable("uri", uri);
        out.putFloat("pos", view.progress());
    }

    @Override protected void onDestroy() {
        view.close();
        super.onDestroy();
    }
}
