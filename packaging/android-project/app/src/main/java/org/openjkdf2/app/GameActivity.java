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
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.InputDevice;
import android.widget.Toast;

import java.io.File;

import org.libsdl.app.SDLControllerManager;
import org.libsdl.app.SDLActivity;

/**
    SDL Activity for Jedi Knight.
    Handles game folder setup on first launch (previously done by ChooserActivity).
*/
public class GameActivity extends SDLActivity {
    private static final int REQUEST_PICK_FOLDER = 1;
    private static final int REQUEST_STORAGE_PERMISSION = 2;
    private static final String PREF_SETUP_DONE = "setup_done";

    private AlertDialog mDialog;
    private boolean mAwaitingPermission;
    private boolean mReturnToSetup;

    // Overlay settings menu (Unreal-Android style)
    private View mSettingsGear;
    private GameSettingsMenu mSettingsMenu;
    private boolean mSelectHeld, mStartHeld;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // Before SDL starts the native main: tell it where the game is
        GameFolders.exportToEnvironment(this);
        super.onCreate(savedInstanceState);

        // Install the overlay settings gear (touch) - JK uses blue theme
        mSettingsGear = AndroidSettingsGear.install(this, this::openSettingsMenu, false);
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
        boolean hasJk = GameFolders.resolve(this, false) != null;
        boolean setupDone = getPreferences(MODE_PRIVATE).getBoolean(PREF_SETUP_DONE, false);

        if (hasJk) {
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
            getString(R.string.chooser_jk) + "\n" + describeFolder(false),
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
        } else if (!GameFolders.isGameDir(new File(path), false)) {
            Toast.makeText(this, R.string.folder_not_jk, Toast.LENGTH_LONG).show();
        } else {
            GameFolders.setPicked(this, false, path);
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

    private void startGame() {
        // Game starts automatically via SDL
    }

    private void openSettingsMenu() {
        if (mSettingsMenu != null && mSettingsMenu.isShowing()) return;
        mSettingsMenu = new GameSettingsMenu(this, false);
        buildSettingsPages();
        mSettingsMenu.setResultCallback(result -> handleSettingsResult(result));
        mSettingsMenu.show(-1);
    }

    private void handleSettingsResult(int result) {
        if (result == GameSettingsMenu.CHANGE_FOLDER) {
            openFolderPicker();
        }
        // Other results handled inline
    }

    private void buildSettingsPages() {
        mSettingsMenu.page(getString(R.string.menu_controls), () -> buildControlsPage());
        mSettingsMenu.page(getString(R.string.menu_comfort), () -> buildComfortPage());
        mSettingsMenu.page(getString(R.string.menu_video), () -> buildVideoPage());
        mSettingsMenu.page(getString(R.string.menu_game), () -> buildGamePage());
    }

    private void buildControlsPage() {
        mSettingsMenu.section(getString(R.string.menu_touch_controls));
        mSettingsMenu.note(getString(R.string.menu_touch_note));
        mSettingsMenu.note(getString(R.string.menu_controller_note));
    }

    private void buildComfortPage() {
        mSettingsMenu.section(getString(R.string.menu_camera_comfort));
        mSettingsMenu.note(getString(R.string.menu_camera_note));
    }

    private void buildVideoPage() {
        mSettingsMenu.section(getString(R.string.menu_display));
        mSettingsMenu.note(getString(R.string.menu_display_note));
    }

    private void buildGamePage() {
        mSettingsMenu.section(getString(R.string.menu_game_data));
        File root = GameFolders.resolve(this, false);
        mSettingsMenu.path(root != null ? root.getAbsolutePath() : getString(R.string.setup_not_found));
        mSettingsMenu.button(getString(R.string.menu_change_folder), () -> {
            mSettingsMenu.confirm(getString(R.string.menu_confirm_change_folder),
                    getString(R.string.menu_continue), () -> {
                        mSettingsMenu.setResult(GameSettingsMenu.CHANGE_FOLDER);
                        mSettingsMenu.dismiss();
                        openFolderPicker();
                    });
        });
        mSettingsMenu.section(getString(R.string.menu_defaults));
        mSettingsMenu.button(getString(R.string.menu_reset_settings), () ->
                mSettingsMenu.confirm(getString(R.string.menu_confirm_reset),
                        getString(R.string.menu_reset), () -> {
                            mSettingsMenu.setResult(GameSettingsMenu.RESET_SETTINGS);
                            mSettingsMenu.dismiss();
                            // Reset preferences
                            getPreferences(MODE_PRIVATE).edit().clear().commit();
                        }));
    }

    // Select + Start chord detection (Unreal-Android style)
    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int code = event.getKeyCode();
        boolean down = event.getAction() == KeyEvent.ACTION_DOWN;

        // Handle Select + Start chord to open/close settings menu
        if (code == KeyEvent.KEYCODE_BUTTON_SELECT || code == KeyEvent.KEYCODE_BUTTON_START) {
            if (!down || event.getRepeatCount() == 0) {
                if (code == KeyEvent.KEYCODE_BUTTON_SELECT) mSelectHeld = down;
                else mStartHeld = down;
                if (mSelectHeld && mStartHeld) {
                    if (mSettingsMenu != null && mSettingsMenu.isShowing()) {
                        mSettingsMenu.dismiss();
                    } else {
                        openSettingsMenu();
                    }
                }
            }
            return true;
        }

        // Let the settings menu handle its own keys if showing
        if (mSettingsMenu != null && mSettingsMenu.isShowing()) {
            if (mSettingsMenu.dispatchKeyEvent(event)) return true;
        }

        // Pass other keys to SDL
        if (SDLControllerManager.handleKeyEvent(event)) return true;

        return super.dispatchKeyEvent(event);
    }

    @Override
    public boolean dispatchGenericMotionEvent(MotionEvent event) {
        // Let the settings menu handle controller motion if showing
        if (mSettingsMenu != null && mSettingsMenu.isShowing()) {
            if (mSettingsMenu.dispatchGenericMotionEvent(event)) return true;
        }
        // Pass to SDL
        if (event.getSource() == InputDevice.SOURCE_JOYSTICK) {
            if (SDLControllerManager.handleJoystickMotionEvent(event)) {
                return true;
            }
        }
        return super.dispatchGenericMotionEvent(event);
    }

    @Override
    protected void onPause() {
        super.onPause();
        // Reset chord state
        mSelectHeld = false;
        mStartHeld = false;
        // Dismiss settings menu if showing
        if (mSettingsMenu != null && mSettingsMenu.isShowing()) {
            mSettingsMenu.dismiss();
        }
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
}