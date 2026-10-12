package it.hero3.remote;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.*;
import android.widget.*;

/** Shared photo/video preview frame and bottom action bar. */
final class PreviewUi {
    final LinearLayout root;
    final FrameLayout content;
    final TextView status;
    PreviewUi(Context context, String title, Runnable download, Runnable close) {
        root = new LinearLayout(context); root.setOrientation(LinearLayout.VERTICAL);
        int padding = dp(context, 16); root.setPadding(padding, padding, padding, padding);
        GradientDrawable background = new GradientDrawable(); background.setColor(0xff1a2028); background.setCornerRadius(dp(context, 12)); root.setBackground(background);
        LinearLayout header = new LinearLayout(context); header.setGravity(Gravity.CENTER_VERTICAL); header.setPadding(0, 0, 0, dp(context, 12)); root.addView(header);
        TextView heading = new TextView(context); heading.setText(title); heading.setTextColor(0xfff4f7fb); heading.setTextSize(20); heading.setGravity(Gravity.CENTER_VERTICAL); header.addView(heading, new LinearLayout.LayoutParams(0, dp(context, 48), 1));
        ImageButton dismiss = new ImageButton(context); dismiss.setImageResource(R.drawable.close); dismiss.setBackgroundColor(Color.TRANSPARENT); dismiss.setPadding(dp(context, 12), dp(context, 12), dp(context, 12), dp(context, 12)); dismiss.setContentDescription("Chiudi anteprima");
        dismiss.setOnClickListener(v -> close.run()); header.addView(dismiss, new LinearLayout.LayoutParams(dp(context, 48), dp(context, 48)));
        content = new FrameLayout(context); content.setBackgroundColor(Color.BLACK); root.addView(content, new LinearLayout.LayoutParams(-1, dp(context, 230)));
        status = new TextView(context); status.setTextSize(13); status.setTextColor(0xffacb9ca); status.setPadding(0, dp(context, 8), 0, dp(context, 8)); status.setMinLines(1); status.setMinHeight(dp(context, 36)); root.addView(status);
        if (download != null) {
            LinearLayout actions = new LinearLayout(context); actions.setGravity(Gravity.END | Gravity.CENTER_VERTICAL); root.addView(actions);
            Button button = action(context, "Scarica"); button.setOnClickListener(v -> download.run()); actions.addView(button);
        }
    }
    private static Button action(Context context, String label) {
        Button button = new Button(context, null, android.R.attr.buttonBarButtonStyle);
        button.setText(label); button.setAllCaps(true); button.setTextColor(0xff65caff); button.setMinHeight(dp(context, 48)); return button;
    }
    static int dp(Context context, int value) { return Math.round(value * context.getResources().getDisplayMetrics().density); }
    static int width(Context context) { return Math.min(context.getResources().getDisplayMetrics().widthPixels - dp(context, 24), dp(context, 760)); }
    static int height(Context context, float aspect) {
        return Math.max(dp(context, 150), Math.min(Math.round((width(context) - dp(context, 32)) / aspect), Math.round(context.getResources().getDisplayMetrics().heightPixels * .60f)));
    }
}
