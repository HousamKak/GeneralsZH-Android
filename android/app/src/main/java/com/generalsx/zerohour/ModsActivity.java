package com.generalsx.zerohour;

import android.app.Activity;
import android.os.Bundle;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.google.android.material.materialswitch.MaterialSwitch;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Optional packs (mods) published with the game data (site/upload-data.py, _packs/): one switch
 * each. Apply restarts the app into the game-data download, which fetches the packs turned on and
 * removes the files of those turned off (DataPack.wanted / commit). Opened from the game's ZH
 * COMMANDER screen (MODS).
 *
 * <p>A gameplay pack changes what the simulation reads; every player in an online match needs
 * the same, so the screen says so and the game's Online button warns while one is on.
 */
public class ModsActivity extends Activity {

    private final Map<String, MaterialSwitch> switches = new LinkedHashMap<>();
    private Set<String> initial;
    private TextView status;

    @Override
    protected void attachBaseContext(android.content.Context newBase) {
        super.attachBaseContext(LocaleHelper.wrap(newBase));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle("Mods");
        initial = DataPack.enabledPacks(this);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(UiKit.color(this, R.color.gzh_background));
        UiKit.appBar(root, getString(R.string.setup_window_title), "Mods", 0, null, null);
        LinearLayout page = UiKit.scrollingPage(root);

        DataPack.Manifest m = DataPack.lastManifest(this);
        boolean arabic = "ar".equals(java.util.Locale.getDefault().getLanguage());
        if (m == null || m.packs.isEmpty()) {
            LinearLayout card = UiKit.card(page);
            UiKit.body(card, "No mods have been published yet. They will appear here, each with a switch.");
        } else {
            LinearLayout card = UiKit.card(page);
            UiKit.helpText(card, "Turn a pack on to download it, off to remove its files. Changes apply when "
                + "the game starts again.");
            for (DataPack.Pack p : m.packs) {
                UiKit.divider(card);
                String description = (arabic ? p.descriptionAr : p.descriptionEn)
                    + "\n" + Math.max(1, p.size >> 20) + " MB"
                    + (p.gameplay ? " · Changes gameplay: online, every player needs the same" : "");
                MaterialSwitch sw = UiKit.switchRow(card, arabic ? p.titleAr : p.titleEn, description);
                sw.setChecked(initial.contains(p.id));
                switches.put(p.id, sw);
            }
        }

        LinearLayout actions = UiKit.card(page);
        status = UiKit.helpText(actions, "");
        UiKit.button(actions, UiKit.BTN_PRIMARY, R.drawable.ic_gzh_check, "Apply", this::onApply);
        UiKit.button(actions, UiKit.BTN_TONAL, R.drawable.ic_gzh_play, "Back", this::finish);

        setContentView(root);
        InsetUtil.applySafeInsets(root);
    }

    private void onApply() {
        Set<String> on = new TreeSet<>();
        for (Map.Entry<String, MaterialSwitch> e : switches.entrySet()) {
            if (e.getValue().isChecked()) {
                on.add(e.getKey());
            }
        }
        if (on.equals(initial)) {
            finish();
            return;
        }
        DataPack.setEnabledPacks(this, on);
        ZHTelemetry.track("mods_changed", "enabled", String.join(",", on));
        // The game holds its archives open, so the download runs in a fresh start of the app.
        if (ZHBridge.gameActivity() != null) {
            status.setText("Restarting to apply…");
            ZHBridge.restartInto(DataDownloadActivity.class);
        } else {
            startActivity(new android.content.Intent(this, DataDownloadActivity.class));
            finish();
        }
    }
}
