package com.fsolauncher.app;

import android.app.Activity;
import android.content.*;
import android.content.res.Resources;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.text.TextUtils;
import android.util.Log;
import android.util.TypedValue;
import android.view.*;
import android.widget.*;
import androidx.appcompat.content.res.AppCompatResources;
import com.google.gson.*;
import com.shivansps.fsowrapper.R;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Renders compact shared links and the optional per-TC community column. */
final class CommunityLinksUi {
    private CommunityLinksUi() {}
    private static final class SharedConfig { List<CommunityLink> links; }

    static List<CommunityLink> shared(Activity activity) {
        try (Reader reader = new InputStreamReader(
                activity.getAssets().open("shared-community-links.json"), StandardCharsets.UTF_8)) {
            SharedConfig config = new Gson().fromJson(reader,SharedConfig.class);
            return valid(config == null ? null : config.links);
        } catch (IOException | JsonParseException e) {
            Log.w("FSOCommunity","Cannot load shared community links",e);
            return Collections.emptyList();
        }
    }

    private static List<CommunityLink> valid(List<CommunityLink> input) {
        List<CommunityLink> links = new ArrayList<>();
        if (input != null) for (CommunityLink link : input) {
            if (link != null && link.isWebLink()) links.add(link);
            else Log.w("FSOCommunity","Ignored an invalid community link");
        }
        return links;
    }

    static View tcColumn(Activity activity, List<CommunityLink> input) {
        List<CommunityLink> links = valid(input);
        if (links.isEmpty()) return null;
        LinearLayout column = new LinearLayout(activity);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setGravity(Gravity.CENTER_HORIZONTAL);
        column.setPadding(0,dp(activity,16),0,dp(activity,4));
        for (CommunityLink link : links) {
            Button button = new Button(activity);
            button.setText(link.label.trim());
            button.setAllCaps(false);
            button.setTextColor(Color.WHITE);
            button.setTextSize(14);
            button.setMinWidth(dp(activity,220));
            button.setMaxWidth(dp(activity,280));
            button.setMinHeight(dp(activity,48));
            button.setMaxLines(2);
            button.setPadding(dp(activity,16),dp(activity,10),dp(activity,16),dp(activity,10));
            Drawable icon = icon(activity,link.icon,20,Color.WHITE);
            button.setCompoundDrawablesRelative(icon,null,null,null);
            button.setCompoundDrawablePadding(dp(activity,10));
            bind(activity,button,link);
            LinearLayout.LayoutParams layout = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,ViewGroup.LayoutParams.WRAP_CONTENT);
            layout.gravity = Gravity.CENTER_HORIZONTAL;
            layout.bottomMargin = dp(activity,6);
            column.addView(button,layout);
        }
        return column;
    }

    static View compactRow(Activity activity, List<CommunityLink> links) {
        if (links.isEmpty()) return null;
        CompactRow row = new CompactRow(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.START);
        row.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        for (CommunityLink link : links) {
            Button button = new Button(activity);
            button.setText(link.label.trim());
            button.setTextSize(9);
            button.setTypeface(Typeface.create("sans-serif-condensed",Typeface.NORMAL));
            button.setAllCaps(false);
            button.setSingleLine(true);
            button.setEllipsize(TextUtils.TruncateAt.END);
            button.setTextColor(Color.rgb(160,204,230));
            button.setIncludeFontPadding(false);
            button.setMinWidth(0); button.setMinimumWidth(0);
            button.setMinHeight(0); button.setMinimumHeight(0);
            button.setPadding(dp(activity,2),dp(activity,4),dp(activity,2),dp(activity,4));
            button.setGravity(Gravity.CENTER);
            TypedValue background = new TypedValue();
            if (activity.getTheme().resolveAttribute(android.R.attr.selectableItemBackgroundBorderless,background,true))
                button.setBackgroundResource(background.resourceId);
            button.setCompoundDrawables(null,icon(activity,link.icon,18,Color.rgb(160,204,230)),null,null);
            button.setCompoundDrawablePadding(dp(activity,3));
            bind(activity,button,link);
            row.addView(button,new LinearLayout.LayoutParams(0,ViewGroup.LayoutParams.MATCH_PARENT,1f));
        }
        return row;
    }

    private static void bind(Activity activity, View button, CommunityLink link) {
        button.setContentDescription(link.accessibleLabel());
        button.setTooltipText(link.accessibleLabel());
        button.setOnClickListener(view -> openBrowser(activity,link.url));
    }

    private static void openBrowser(Activity activity, String url) {
        Intent destination = new Intent(Intent.ACTION_VIEW,Uri.parse(url))
            .addCategory(Intent.CATEGORY_BROWSABLE);
        // Resolve a generic web address so verified Discord/GitHub app links do not
        // divert these buttons into their native apps instead of the user's browser.
        Intent browserSelector = new Intent(Intent.ACTION_VIEW,Uri.parse("https:"))
            .addCategory(Intent.CATEGORY_BROWSABLE);
        destination.setSelector(browserSelector);
        try { activity.startActivity(destination); }
        catch (ActivityNotFoundException e) {
            Toast.makeText(activity,"No web browser is available.",Toast.LENGTH_SHORT).show();
        }
    }

    private static Drawable icon(Activity activity, String name, int size, int color) {
        if (name == null || name.trim().isEmpty()) return null;
        int resource;
        switch (name) {
            case "github": resource = R.drawable.ic_community_github; break;
            case "discord": resource = R.drawable.ic_community_discord; break;
            case "web": resource = R.drawable.ic_community_web; break;
            case "wiki": resource = R.drawable.ic_community_wiki; break;
            case "forum": resource = R.drawable.ic_community_forum; break;
            default: resource = activity.getResources().getIdentifier(name,"drawable",activity.getPackageName());
        }
        if (resource == 0) { Log.w("FSOCommunity","Unknown community icon: " + name); return null; }
        try {
            Drawable icon = AppCompatResources.getDrawable(activity,resource);
            if (icon == null) return null;
            icon = icon.mutate();
            icon.setTint(color);
            icon.setBounds(0,0,dp(activity,size),dp(activity,size));
            return icon;
        } catch (Resources.NotFoundException | IllegalArgumentException e) {
            // An optional icon must never prevent the launcher from opening.
            Log.w("FSOCommunity","Cannot load community icon: " + name,e);
            return null;
        }
    }

    private static int dp(Activity activity, int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }

    /** The complete shared row always occupies at most 45% of the available viewport. */
    private static final class CompactRow extends LinearLayout {
        CompactRow(Activity activity) { super(activity); }
        @Override protected void onMeasure(int widthSpec,int heightSpec) {
            int available = MeasureSpec.getSize(widthSpec);
            int viewport = available;
            if (getParent() instanceof View) {
                View parent = (View)getParent();
                viewport += parent.getPaddingLeft() + parent.getPaddingRight();
            }
            int preferred = Math.round(48 * getChildCount() * getResources().getDisplayMetrics().density);
            int width = Math.min(preferred,Math.max(1,(int)(viewport * .45f)));
            if (MeasureSpec.getMode(widthSpec) != MeasureSpec.UNSPECIFIED) width = Math.min(width,available);
            super.onMeasure(MeasureSpec.makeMeasureSpec(width,MeasureSpec.EXACTLY),heightSpec);
        }
    }
}
