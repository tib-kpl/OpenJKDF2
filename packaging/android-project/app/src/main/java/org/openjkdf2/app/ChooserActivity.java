package org.openjkdf2.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;

import java.io.File;

/**
    Launcher entry. Some launchers (e.g. Cocoon) show a single icon per
    package, so instead of one launcher entry per game, this asks which game
    to start when both are installed, and otherwise starts the installed one
    directly (JK when neither is, so its install helper runs).

    A game counts as installed when its data dir (see
    InstallHelper_GetLocalDataDir: <external files dir>/jk1 or /mots) has
    resource/jk_.cd, the same check the engine does.
*/
public class ChooserActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        boolean hasJk = isGameInstalled("jk1");
        boolean hasMots = isGameInstalled("mots");

        if (hasJk && hasMots) {
            showChooser();
        } else {
            startGame(hasMots && !hasJk ? MotsActivity.class : GameActivity.class);
        }
    }

    private boolean isGameInstalled(String dirName) {
        File base = getExternalFilesDir(null);
        return base != null && new File(base, dirName + "/resource/jk_.cd").isFile();
    }

    private void showChooser() {
        CharSequence[] games = {
            getString(R.string.chooser_jk),
            getString(R.string.chooser_mots),
        };

        AlertDialog dialog = new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(R.string.chooser_title)
            .setItems(games, (d, which) ->
                startGame(which == 0 ? GameActivity.class : MotsActivity.class))
            .setOnCancelListener(d -> finish())
            .create();
        dialog.show();
    }

    private void startGame(Class<? extends Activity> gameActivity) {
        startActivity(new Intent(this, gameActivity));
        finish();
    }
}
