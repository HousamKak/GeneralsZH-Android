package com.generalsx.zerohour;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.view.ContextThemeWrapper;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

/**
 * First screen until the device is activated (LicenseGate): one field for the key the owner
 * gave, one button. On success it hands over to the launcher.
 *
 * <p>GeneralsX @feature ZH Commander 10/10/2026 Keys arrive in a chat message, so the field takes
 * them from the clipboard: a Paste icon inside it, and a copied key filled in on its own when the
 * screen opens. Whatever is pasted (the whole message, lower case, spaces) is reduced to the key
 * the way the server reads it (server/license/src/codes.ts). The seller's number stays
 * left-to-right in every language, and a button opens the WhatsApp chat to buy a key.
 */
public class ActivationActivity extends Activity {

    // The server's alphabet: Crockford-style base32 without I, L, O, U (codes.ts).
    private static final String KEY_ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ";
    private static final int KEY_LENGTH = 12;
    // A key inside any text: GZH, then 12 code characters with optional separators between groups.
    private static final Pattern KEY_IN_TEXT = Pattern.compile(
        "GZH[\\s-]*([0-9A-Z]{4})[\\s-]*([0-9A-Z]{4})[\\s-]*([0-9A-Z]{4})");
    // A phone number inside a sentence: a + and digits, with spaces between the groups.
    private static final Pattern PHONE_IN_TEXT = Pattern.compile("\\+?\\d[\\d ]{6,}\\d");
    // Unicode isolates: the number is laid out left-to-right even inside a right-to-left sentence.
    private static final String LTR_ISOLATE = "⁦";
    private static final String END_ISOLATE = "⁩";

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
        UiKit.button(content, UiKit.BTN_TONAL, R.drawable.ic_gzh_share,
            getString(R.string.activation_whatsapp), this::onWhatsApp);

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
        // The key reads GZH-... left to right in Arabic too.
        keyEdit.setTextDirection(View.TEXT_DIRECTION_LTR);
        keyEdit.setImeOptions(EditorInfo.IME_ACTION_DONE);
        keyEdit.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                onActivate();
                return true;
            }
            return false;
        });
        field.setEndIconMode(TextInputLayout.END_ICON_CUSTOM);
        field.setEndIconDrawable(R.drawable.ic_gzh_paste);
        field.setEndIconContentDescription(R.string.activation_paste);
        field.setEndIconTintList(UiKit.tint(this, R.color.gzh_primary));
        field.setEndIconOnClickListener(v -> pasteKey(true));
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

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        // Android lets an app read the clipboard only while it has focus, hence here and not in
        // onResume. Only an empty field is filled, so nothing typed is ever replaced.
        if (hasFocus && keyEdit != null && keyEdit.length() == 0) {
            pasteKey(false);
        }
    }

    /** Puts the key found on the clipboard in the field; asked = the Paste icon was pressed. */
    private void pasteKey(boolean asked) {
        ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        ClipData clip = cm != null ? cm.getPrimaryClip() : null;
        CharSequence text = clip != null && clip.getItemCount() > 0 ? clip.getItemAt(0).coerceToText(this) : null;
        // Filled in unasked only from text that names a key (GZH...), never from any 12 letters.
        String key = text != null ? keyFromText(text.toString(), asked) : null;
        if (key == null) {
            if (asked) {
                statusText.setText(R.string.activation_clipboard_empty);
            }
            return;
        }
        keyEdit.setText(key);
        keyEdit.setSelection(key.length());
        statusText.setText(R.string.activation_pasted);
    }

    /**
     * The key in any text (a whole chat message, lower case, spaces), formatted; or null.
     * bareCode also accepts the twelve characters without GZH, for what the player typed or
     * chose to paste.
     */
    static String keyFromText(String text, boolean bareCode) {
        Matcher m = KEY_IN_TEXT.matcher(text.toUpperCase(Locale.ROOT));
        if (m.find()) {
            String code = normalizeCode(m.group(1) + m.group(2) + m.group(3));
            if (code != null) {
                return format(code);
            }
        }
        if (!bareCode) {
            return null;
        }
        String code = normalizeCode(text);
        return code != null ? format(code) : null;
    }

    /** As the server reads a key (codes.ts normalizeCode): the 12 code characters, or null. */
    private static String normalizeCode(String input) {
        String code = input.toUpperCase(Locale.ROOT).replaceAll("[^0-9A-Z]", "");
        if (code.startsWith("GZH")) {
            code = code.substring(3);
        }
        code = code.replace('O', '0').replace('I', '1').replace('L', '1');
        if (code.length() != KEY_LENGTH) {
            return null;
        }
        for (int i = 0; i < code.length(); i++) {
            if (KEY_ALPHABET.indexOf(code.charAt(i)) < 0) {
                return null;
            }
        }
        return code;
    }

    private static String format(String code) {
        return "GZH-" + code.substring(0, 4) + "-" + code.substring(4, 8) + "-" + code.substring(8);
    }

    /** Opens a WhatsApp chat with the number in the offer text, with a first message filled in. */
    private void onWhatsApp() {
        Matcher m = PHONE_IN_TEXT.matcher(getString(R.string.activation_buy, "0", "0"));
        if (!m.find()) {
            return;
        }
        String digits = m.group().replaceAll("[^0-9]", "");
        Uri chat = Uri.parse("https://wa.me/" + digits + "?text="
            + Uri.encode(getString(R.string.activation_whatsapp_message)));
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, chat));
            ZHTelemetry.track("activation_buy_chat");
        } catch (android.content.ActivityNotFoundException e) {
            statusText.setText(ltrNumbers(getString(R.string.activation_whatsapp_missing, m.group())));
        }
    }

    private void onActivate() {
        String typed = keyEdit.getText() != null ? keyEdit.getText().toString().trim() : "";
        String key = keyFromText(typed, true);
        if (key == null) {
            statusText.setText(R.string.activation_error_invalid_key);
            return;
        }
        keyEdit.setText(key);
        keyEdit.setSelection(key.length());
        activateButton.setEnabled(false);
        statusText.setText(R.string.activation_working);
        new Thread(() -> {
            LicenseGate.Result result = LicenseGate.activate(getApplicationContext(), key);
            ZHTelemetry.track("activation", "result", result.error == null ? "ok" : result.error);
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
        return ltrNumbers(getString(R.string.activation_buy, sale, regular));
    }

    /** Wraps each phone number in left-to-right isolates, so Arabic and Persian keep its order. */
    private static String ltrNumbers(String text) {
        Matcher m = PHONE_IN_TEXT.matcher(text);
        StringBuffer out = new StringBuffer();
        while (m.find()) {
            m.appendReplacement(out, Matcher.quoteReplacement(LTR_ISOLATE + m.group() + END_ISOLATE));
        }
        m.appendTail(out);
        return out.toString();
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
