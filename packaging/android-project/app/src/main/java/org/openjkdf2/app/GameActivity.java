package org.openjkdf2.app;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.InputDevice;

import java.io.File;

import org.libsdl.app.SDLControllerManager;
import org.libsdl.app.SDLActivity;

/**
    SDL Activity for Jedi Knight.
    Handles game folder setup on first launch (previously done by ChooserActivity).
*/
public class GameActivity extends SDLActivity {
    // Overlay settings menu (Unreal-Android style)
    private View mSettingsGear;
    private GameSettingsMenu mSettingsMenu;
    private boolean mSelectHeld, mStartHeld;
    private boolean mRestarting;
    private ControllerProfiles mControllers;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // Before SDL starts the native main: tell it where the game is
        GameFolders.exportToEnvironment(this);
        super.onCreate(savedInstanceState);
        mControllers = new ControllerProfiles(this);

        mSettingsGear = AndroidSettingsGear.install(this, this::openSettingsMenu, false);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Resumed (recents list...) without going through SetupActivity: the
        // folder may have vanished since the last launch.
        String path = GameFolders.getPicked(this, false);
        if (path == null || !GameFolders.isGameDir(new File(path), false)) {
            restartThroughSetup(false);
        }
    }

    /** Back to the folder setup; this process is killed so the game restarts with the new folder. */
    private void restartThroughSetup(boolean forcePick) {
        if (mRestarting) return;
        mRestarting = true;
        Intent intent = new Intent(this, SetupActivity.class);
        intent.putExtra(SetupActivity.EXTRA_FORCE_PICK, forcePick);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(intent);
        finish();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (mRestarting) {
            android.os.Process.killProcess(android.os.Process.myPid());
        }
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
            restartThroughSetup(true);
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
        mControllers.buildSection(mSettingsMenu);
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
        String root = GameFolders.getPicked(this, false);
        mSettingsMenu.path(root != null ? root : getString(R.string.setup_not_found));
        mSettingsMenu.button(getString(R.string.menu_change_folder), () -> {
            mSettingsMenu.confirm(getString(R.string.menu_confirm_change_folder),
                    getString(R.string.menu_continue), () -> {
                        mSettingsMenu.setResult(GameSettingsMenu.CHANGE_FOLDER);
                        mSettingsMenu.dismiss();
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
        // Per-controller button mapping (settings menu > Controls)
        int output = mControllers.translate(event);
        if (output == ControllerProfiles.DISABLED) return true;
        if (SDLActivity.handleKeyEvent(getWindow().getDecorView(), output, event, null)) return true;

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
        mControllers.clearHeld();
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