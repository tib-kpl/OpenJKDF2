package org.openjkdf2.app;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.DocumentsContract;
import android.provider.Settings;
import android.text.InputType;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Toast;

import java.io.File;

/**
    Launcher entry of each APK (runs in its own process, see AndroidManifest.xml).
    On every launch it checks that the saved game folder still holds the game
    files; if not (or if none was ever given) it asks for the folder path, then
    starts the game, which runs straight from that folder.
*/
public class SetupActivity extends Activity {
    static final String EXTRA_FORCE_PICK = "force_pick";

    private static final int REQUEST_PICK_FOLDER = 1;
    private static final int REQUEST_STORAGE_PERMISSION = 2;

    private boolean mMots;
    private boolean mForcePick;
    private boolean mAwaitingPermission;
    private AlertDialog mDialog;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mMots = getResources().getBoolean(R.bool.is_mots);
        mForcePick = getIntent().getBooleanExtra(EXTRA_FORCE_PICK, false);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (mDialog != null && mDialog.isShowing()) {
            return;
        }
        if (mAwaitingPermission) {
            mAwaitingPermission = false;
            if (!hasStoragePermission()) {
                Toast.makeText(this, R.string.permission_message, Toast.LENGTH_LONG).show();
                finish();
                return;
            }
        }
        route();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (mDialog != null) {
            mDialog.dismiss();
            mDialog = null;
        }
    }

    private void route() {
        if (!hasStoragePermission()) {
            requestStoragePermission();
            return;
        }
        String saved = GameFolders.getPicked(this, mMots);
        if (!mForcePick && saved != null && GameFolders.isGameDir(new File(saved), mMots)) {
            startGame();
            return;
        }
        showPathDialog(saved, saved != null && !mForcePick);
    }

    private void showPathDialog(String initial, boolean bWasInvalid) {
        final EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        input.setText(initial != null ? initial : Environment.getExternalStorageDirectory().getPath());
        input.setSelection(input.getText().length());
        LinearLayout box = new LinearLayout(this);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        box.setPadding(pad, pad / 2, pad, 0);
        box.addView(input, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        String message = getString(mMots ? R.string.setup_message_mots : R.string.setup_message_jk);
        if (bWasInvalid) {
            message = getString(R.string.setup_folder_invalid, initial) + "\n\n" + message;
        }
        mDialog = new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(R.string.setup_title)
            .setMessage(message)
            .setView(box)
            .setCancelable(false)
            .setPositiveButton(android.R.string.ok, null) // overridden below: keep the dialog open on error
            .setNeutralButton(R.string.setup_browse, (d, w) -> openFolderPicker())
            .setNegativeButton(android.R.string.cancel, (d, w) -> finish())
            .create();
        mDialog.show();
        mDialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String path = input.getText().toString().trim();
            while (path.length() > 1 && path.endsWith("/")) {
                path = path.substring(0, path.length() - 1);
            }
            if (!GameFolders.isGameDir(new File(path), mMots)) {
                Toast.makeText(this, mMots ? R.string.folder_not_mots : R.string.folder_not_jk,
                               Toast.LENGTH_LONG).show();
                return;
            }
            GameFolders.setPicked(this, mMots, path);
            startGame();
        });
    }

    private void startGame() {
        Intent intent = new Intent();
        intent.setComponent(new ComponentName(this, mMots ? MotsActivity.class : GameActivity.class));
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(intent);
        finish();
    }

    private boolean hasStoragePermission() {
        if (Build.VERSION.SDK_INT >= 30) {
            return Environment.isExternalStorageManager();
        }
        if (Build.VERSION.SDK_INT >= 23) {
            return checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
        }
        return true;
    }

    private void requestStoragePermission() {
        if (Build.VERSION.SDK_INT < 30) {
            requestPermissions(new String[] {
                Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.WRITE_EXTERNAL_STORAGE,
            }, REQUEST_STORAGE_PERMISSION);
            return;
        }
        mAwaitingPermission = true;
        Toast.makeText(this, R.string.permission_message, Toast.LENGTH_LONG).show();
        try {
            startActivity(new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                     Uri.parse("package:" + getPackageName())));
        } catch (ActivityNotFoundException e) {
            startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_STORAGE_PERMISSION && !hasStoragePermission()) {
            Toast.makeText(this, R.string.permission_message, Toast.LENGTH_LONG).show();
            finish();
        }
        // granted: onResume runs next and routes
    }

    private void openFolderPicker() {
        try {
            startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), REQUEST_PICK_FOLDER);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, R.string.folder_bad_location, Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode != REQUEST_PICK_FOLDER) {
            super.onActivityResult(requestCode, resultCode, data);
            return;
        }
        if (resultCode != RESULT_OK || data == null || data.getData() == null) {
            return; // onResume shows the path dialog again
        }
        String path = treeUriToPath(data.getData());
        if (path == null) {
            Toast.makeText(this, R.string.folder_bad_location, Toast.LENGTH_LONG).show();
        } else if (!GameFolders.isGameDir(new File(path), mMots)) {
            Toast.makeText(this, mMots ? R.string.folder_not_mots : R.string.folder_not_jk,
                           Toast.LENGTH_LONG).show();
        } else {
            GameFolders.setPicked(this, mMots, path);
            mForcePick = false; // onResume then starts the game
        }
    }

    /** Filesystem path of a folder picked with ACTION_OPEN_DOCUMENT_TREE, if it has one. */
    private static String treeUriToPath(Uri treeUri) {
        if (!"com.android.externalstorage.documents".equals(treeUri.getAuthority())) {
            return null;
        }
        String docId = DocumentsContract.getTreeDocumentId(treeUri); // e.g. "primary:Games/JK"
        int colon = docId.indexOf(':');
        if (colon < 0) {
            return null;
        }
        String volume = docId.substring(0, colon);
        String relPath = docId.substring(colon + 1);
        String base = "primary".equalsIgnoreCase(volume)
            ? Environment.getExternalStorageDirectory().getPath()
            : "/storage/" + volume;
        return relPath.isEmpty() ? base : base + "/" + relPath;
    }
}
