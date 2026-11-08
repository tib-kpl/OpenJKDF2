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

import org.libsdl.app.SDLControllerManager;
import org.libsdl.app.SDLActivity;

/**
    SDL Activity for Mysteries of the Sith.
    Runs in its own process (":mots", see AndroidManifest.xml) because SDL
    only supports one instance per process, and with its own taskAffinity so
    it appears as a separate entry in the recents picker that can be closed
    independently. Handles game folder setup on first launch.
*/
public class MotsActivity extends SDLActivity {
    private static final int REQUEST_PICK_FOLDER = 1;
    private static final int REQUEST_STORAGE_PERMISSION = 2;
    private static final String PREF_SETUP_DONE = "setup_done";

    private AlertDialog mDialog;
    private boolean mAwaitingPermission;
    private boolean mReturnToSetup;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // Before SDL starts the native main: tell it where the game is
        GameFolders.exportToEnvironment(this);
        super.onCreate(savedInstanceState);
    }

    @Override
    protected void onResume() {
        super.onResume();

        if (mDialog != null && mDialog.isShowing()) {
            return;
        }

        if (mAwaitingPermission) {
            mAwaitingPermission = false;
            if (hasStoragePermission()) {
                openFolderPicker();
            } else {
                showSetup();
            }
        } else if (mReturnToSetup) {
            mReturnToSetup = false;
            showSetup();
        } else {
            route();
        }
    }

    private void route() {
        boolean hasMots = GameFolders.resolve(this, true) != null;
        boolean setupDone = getPreferences(MODE_PRIVATE).getBoolean(PREF_SETUP_DONE, false);

        if (hasMots) {
            startGame();
        } else if (!setupDone) {
            showSetup();
        } else {
            // No folder picked and setup was done before, start anyway (install helper)
            startGame();
        }
    }

    private void showSetup() {
        CharSequence[] items = {
            getString(R.string.chooser_mots) + "\n" + describeFolder(true),
        };

        mDialog = new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(R.string.setup_title)
            .setItems(items, (d, which) -> pickFolder())
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

    private void pickFolder() {
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
            .setNegativeButton(android.R.string.cancel, (d, which) -> showSetup())
            .setOnCancelListener(d -> showSetup())
            .show();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQUEST_STORAGE_PERMISSION) {
            return;
        }
        if (hasStoragePermission()) {
            openFolderPicker();
        } else {
            mReturnToSetup = true;
        }
    }

    private void openFolderPicker() {
        try {
            startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), REQUEST_PICK_FOLDER);
        } catch (ActivityNotFoundException e) {
            showSetup();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (InstallHelperSAF.onActivityResult(requestCode, resultCode, data)) {
            return;
        }
        if (requestCode != REQUEST_PICK_FOLDER) {
            super.onActivityResult(requestCode, resultCode, data);
            return;
        }

        mReturnToSetup = true;

        if (resultCode != RESULT_OK || data == null || data.getData() == null) {
            return;
        }

        String path = treeUriToPath(data.getData());
        if (path == null) {
            Toast.makeText(this, R.string.folder_bad_location, Toast.LENGTH_LONG).show();
        } else if (!GameFolders.isGameDir(new File(path), true)) {
            Toast.makeText(this, R.string.folder_not_mots, Toast.LENGTH_LONG).show();
        } else {
            GameFolders.setPicked(this, true, path);
        }
    }

    /** Filesystem path of a folder picked with ACTION_OPEN_DOCUMENT_TREE, if it has one. */
    private static String treeUriToPath(Uri treeUri) {
        if (!"com.android.externalstorage.documents".equals(treeUri.getAuthority())) {
            return null;
        }
        String docId = DocumentsContract.getTreeDocumentId(treeUri); // e.g. "primary:Games/MotS"
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

    private void startGame() {
        // Game starts automatically via SDL with -motsCompat argument
    }

    // Added: fallback joystick-motion dispatch. SDL's generic-motion listener lives on
    // SDLSurface only; while the text-input dummy view (SDLDummyEdit) holds window focus
    // (every on-screen keyboard session, e.g. the save-game name box), motion events are
    // delivered to that view instead and die there -- gamepad axes freeze at their last
    // value while button key-events (activity-dispatched) keep working. This fallback
    // only runs when the view chain did not consume the event.
    @Override
    public boolean onGenericMotionEvent(android.view.MotionEvent event) {
        if (event.getSource() == android.view.InputDevice.SOURCE_JOYSTICK) {
            if (SDLControllerManager.handleJoystickMotionEvent(event)) {
                return true;
            }
        }
        return super.onGenericMotionEvent(event);
    }

    @Override
    protected String[] getArguments() {
        return new String[] { "-motsCompat" };
    }
}