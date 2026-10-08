package com.generalsx.zerohour;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.text.InputType;
import android.view.ContextThemeWrapper;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

/**
 * First screen until the device is activated (LicenseGate): one field for the key the owner
 * gave, one button. On success it hands over to the launcher.
 */
public class ActivationActivity extends Activity {

    private TextInputEditText keyEdit;
    private MaterialButton activateButton;
    private TextView statusText;

    @Override
    protected void attachBaseContext(android.content.Context newBase) {
        super.attachBaseContext(LocaleHelper.wrap(newBase));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (LicenseGate.isActivated(this)) {
            openLauncher();
            return;
        }
        setTitle(R.string.activation_title);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(UiKit.color(this, R.color.gzh_background));
        UiKit.appBar(root, getString(R.string.setup_window_title),
            getString(R.string.activation_title), 0, null, null);

        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        int gutter = UiKit.dim(this, R.dimen.gzh_gutter);
        page.setPadding(gutter, 0, gutter, 0);
        root.addView(page, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        LinearLayout content = UiKit.card(page);
        UiKit.sectionHeader(content, R.drawable.ic_gzh_account, getString(R.string.activation_card_title), false);
        UiKit.helpText(content, getString(R.string.activation_help));
        // GeneralsX @feature Codex 08/10/2026 Read prices from the signed settings manifest.
        TextView buyText = UiKit.helpText(content, activationOffer());
        buyText.setTextIsSelectable(true);
        android.content.Context app = getApplicationContext();
        new Thread(() -> {
            UpdateManager.check(app, false);
            runOnUiThread(() -> {
                if (!isFinishing() && !isDestroyed()) {
                    buyText.setText(activationOffer());
                }
            });
        }, "GXActivationPrice").start();

        TextInputLayout field = new TextInputLayout(
            new ContextThemeWrapper(this, R.style.ThemeOverlay_GeneralsZH_OutlinedField));
        field.setHint(R.string.activation_key_hint);
        field.setBoxStrokeColor(UiKit.color(this, R.color.gzh_primary));
        field.setHintTextColor(UiKit.tint(this, R.color.gzh_on_surface_variant));
        keyEdit = new TextInputEditText(field.getContext());
        keyEdit.setSingleLine(true);
        keyEdit.setTextColor(UiKit.color(this, R.color.gzh_on_surface));
        keyEdit.setInputType(InputType.TYPE_CLASS_TEXT
            | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
            | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        field.addView(keyEdit, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams fieldLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        fieldLp.topMargin = UiKit.dim(this, R.dimen.gzh_item_gap_tight);
        content.addView(field, fieldLp);

        activateButton = UiKit.button(content, UiKit.BTN_PRIMARY, R.drawable.ic_gzh_check,
            getString(R.string.activation_button), this::onActivate);

        statusText = UiKit.helpText(content, "");

        setContentView(root);
        InsetUtil.applySafeInsets(root);
    }

    private void onActivate() {
        String key = keyEdit.getText() != null ? keyEdit.getText().toString().trim() : "";
        if (key.isEmpty()) {
            statusText.setText(R.string.activation_error_invalid_key);
            return;
        }
        activateButton.setEnabled(false);
        statusText.setText(R.string.activation_working);
        new Thread(() -> {
            LicenseGate.Result result = LicenseGate.activate(getApplicationContext(), key);
            runOnUiThread(() -> {
                if (isFinishing()) {
                    return;
                }
                if (result.error == null) {
                    openLauncher();
                    return;
                }
                activateButton.setEnabled(true);
                statusText.setText(errorText(result.error));
            });
        }, "GXActivation").start();
    }

    private int errorText(String error) {
        switch (error) {
            case "invalid_key":
            case "bad_request":
                return R.string.activation_error_invalid_key;
            case "key_used":
                return R.string.activation_error_key_used;
            case "revoked_key":
                return R.string.activation_error_revoked;
            case "network":
                return R.string.activation_error_network;
            default:
                return R.string.activation_error_server;
        }
    }

    private String activationOffer() {
        String sale = priceSetting("activation_sale_price_usd", "10");
        String regular = priceSetting("activation_regular_price_usd", "15");
        return getString(R.string.activation_buy, sale, regular);
    }

    private String priceSetting(String key, String fallback) {
        String value = UpdateManager.remoteConfig(this, key, fallback);
        return value.matches("[0-9]{1,4}(?:\\.[0-9]{1,2})?") ? value : fallback;
    }

    private void openLauncher() {
        startActivity(new Intent(this, SetupActivity.class));
        finish();
    }
}
