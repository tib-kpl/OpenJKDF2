package org.openjkdf2.app;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.DocumentsContract;
import android.provider.Settings;
import android.widget.Toast;

import java.io.File;

/**
    Launcher entry. Some launchers (e.g. Cocoon) show a single icon per
    package, so instead of one launcher entry per game this:
    - asks which game to start when both Jedi Knight and Mysteries of the Sith
      are installed (with an entry to change the game folders), otherwise
      starts the installed one directly;
    - on first launch, or when no game is found, lets the user pick each
      game's folder anywhere on the device storage (see GameFolders). That
      needs the "All files access" permission, since the engine opens its
      files with plain paths. Without picked folders, the games run from the
      app's own data dir as before (JK's install helper when nothing is there).
*/
public class ChooserActivity extends Activity {
    private static final int REQUEST_PICK_FOLDER = 1;
    private static final int REQUEST_STORAGE_PERMISSION = 2;
    private static final String PREF_SETUP_DONE = "setup_done";

    private AlertDialog mDialog;
    private Boolean mPendingPickMots;   // game whose folder is being picked
    private boolean mAwaitingPermission; // back from the permission settings screen
    private boolean mReturnToSetup;      // back from the folder picker

    @Override
    protected void onResume() {
        super.onResume();

        if (mDialog != null && mDialog.isShowing()) {
            return;
        }

        if (mAwaitingPermission) {
            mAwaitingPermission = false;
            if (hasStoragePermission() && mPendingPickMots != null) {
                openFolderPicker();
            } else {
                showSetup();
            }
        } else if (mReturnToSetup) {
            mReturnToSetup = false;
            showSetup();
        } else if (mPendingPickMots == null) {
            route();
        }
    }

    private void route() {
        boolean hasJk = GameFolders.resolve(this, false) != null;
        boolean hasMots = GameFolders.resolve(this, true) != null;
        boolean setupDone = getPreferences(MODE_PRIVATE).getBoolean(PREF_SETUP_DONE, false);

        if (hasJk && hasMots) {
            showChooser();
        } else if (hasJk || hasMots) {
            startGame(hasMots);
        } else if (!setupDone) {
            showSetup();
        } else {
            startGame(false); // JK, for its install helper
        }
    }

    private void showChooser() {
        CharSequence[] items = {
            getString(R.string.chooser_jk),
            getString(R.string.chooser_mots),
            getString(R.string.chooser_change_folders),
        };

        mDialog = new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(R.string.chooser_title)
            .setItems(items, (d, which) -> {
                if (which == 2) {
                    showSetup();
                } else {
                    startGame(which == 1);
                }
            })
            .setOnCancelListener(d -> finish())
            .show();
    }

    private void showSetup() {
        CharSequence[] items = {
            getString(R.string.chooser_jk) + "\n" + describeFolder(false),
            getString(R.string.chooser_mots) + "\n" + describeFolder(true),
        };

        mDialog = new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(R.string.setup_title)
            .setItems(items, (d, which) -> pickFolder(which == 1))
            .setPositiveButton(R.string.setup_continue, (d, which) -> {
                getPreferences(MODE_PRIVATE).edit().putBoolean(PREF_SETUP_DONE, true).commit();
                route();
            })
            .setNegativeButton(android.R.string.cancel, (d, which) -> finish())
            .setOnCancelListener(d -> finish())
            .show();
    }

    private String describeFolder(boolean bMots) {
        File dir = GameFolders.resolve(this, bMots);
        if (dir == null) {
            return getString(R.string.setup_not_found);
        }
        if (dir.equals(GameFolders.appDataDir(this, bMots))) {
            return getString(R.string.setup_app_folder);
        }
        return dir.getPath();
    }

    private void pickFolder(boolean bMots) {
        mPendingPickMots = bMots;
        if (hasStoragePermission()) {
            openFolderPicker();
        } else {
            requestStoragePermission();
        }
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

        mDialog = new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(R.string.permission_title)
            .setMessage(R.string.permission_message)
            .setPositiveButton(R.string.permission_open_settings, (d, which) -> {
                mAwaitingPermission = true;
                try {
                    startActivity(new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                             Uri.parse("package:" + getPackageName())));
                } catch (ActivityNotFoundException e) {
                    startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
                }
            })
            .setNegativeButton(android.R.string.cancel, (d, which) -> {
                mPendingPickMots = null;
                showSetup();
            })
            .setOnCancelListener(d -> {
                mPendingPickMots = null;
                showSetup();
            })
            .show();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQUEST_STORAGE_PERMISSION) {
            return;
        }
        if (hasStoragePermission() && mPendingPickMots != null) {
            openFolderPicker();
        } else {
            mPendingPickMots = null;
            mReturnToSetup = true;
        }
    }

    private void openFolderPicker() {
        try {
            startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), REQUEST_PICK_FOLDER);
        } catch (ActivityNotFoundException e) {
            mPendingPickMots = null;
            showSetup();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_PICK_FOLDER || mPendingPickMots == null) {
            return;
        }

        boolean bMots = mPendingPickMots;
        mPendingPickMots = null;
        mReturnToSetup = true;

        if (resultCode != RESULT_OK || data == null || data.getData() == null) {
            return;
        }

        String path = treeUriToPath(data.getData());
        if (path == null) {
            Toast.makeText(this, R.string.folder_bad_location, Toast.LENGTH_LONG).show();
        } else if (!GameFolders.isGameDir(new File(path), bMots)) {
            Toast.makeText(this, bMots ? R.string.folder_not_mots : R.string.folder_not_jk, Toast.LENGTH_LONG).show();
        } else {
            GameFolders.setPicked(this, bMots, path);
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

    private void startGame(boolean bMots) {
        startActivity(new Intent(this, bMots ? MotsActivity.class : GameActivity.class));
        finish();
    }
}
