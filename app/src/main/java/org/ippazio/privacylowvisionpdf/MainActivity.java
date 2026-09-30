package org.ippazio.privacylowvisionpdf;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.pdf.viewer.fragment.PdfViewerFragment;

public class MainActivity extends AppCompatActivity {
    private static final String PDF_FRAGMENT_TAG = "pdf_viewer";

    private PdfViewerFragment pdfViewerFragment;
    private View emptyState;

    private final ActivityResultLauncher<String[]> openPdfLauncher =
            registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> {
                if (uri != null) {
                    openPdf(uri, true);
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        emptyState = findViewById(R.id.empty_state);
        pdfViewerFragment = (PdfViewerFragment) getSupportFragmentManager()
                .findFragmentByTag(PDF_FRAGMENT_TAG);

        Button openButton = findViewById(R.id.open_button);
        Button searchButton = findViewById(R.id.search_button);

        openButton.setOnClickListener(v ->
                openPdfLauncher.launch(new String[]{"application/pdf"}));

        searchButton.setOnClickListener(v -> {
            if (pdfViewerFragment == null || pdfViewerFragment.getDocumentUri() == null) {
                Toast.makeText(this, R.string.open_pdf_first, Toast.LENGTH_SHORT).show();
                return;
            }
            pdfViewerFragment.setTextSearchActive(
                    !pdfViewerFragment.isTextSearchActive());
        });

        Intent launchIntent = getIntent();
        if (launchIntent != null
                && Intent.ACTION_VIEW.equals(launchIntent.getAction())
                && launchIntent.getData() != null) {
            openPdf(launchIntent.getData(), false);
        }
    }

    private void ensurePdfViewer() {
        if (pdfViewerFragment != null) {
            return;
        }

        pdfViewerFragment = new PdfViewerFragment();
        getSupportFragmentManager()
                .beginTransaction()
                .replace(R.id.pdf_container, pdfViewerFragment, PDF_FRAGMENT_TAG)
                .commitNow();

        // This first version is a viewer only: no annotation/editing toolbox.
        pdfViewerFragment.setToolboxVisible(false);
    }

    private void openPdf(Uri uri, boolean tryPersistPermission) {
        if (tryPersistPermission && "content".equals(uri.getScheme())) {
            try {
                getContentResolver().takePersistableUriPermission(
                        uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (SecurityException ignored) {
                // Some document providers do not offer persistable permission.
                // The current read grant is still enough to open the document.
            }
        }

        try {
            ensurePdfViewer();
            pdfViewerFragment.setDocumentUri(uri);
            emptyState.setVisibility(View.GONE);
        } catch (RuntimeException error) {
            Toast.makeText(this,
                    getString(R.string.pdf_open_error, error.getClass().getSimpleName()),
                    Toast.LENGTH_LONG).show();
        }
    }
}
