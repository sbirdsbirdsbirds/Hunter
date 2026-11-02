package net.osmand.plus.plugins.scout;

import static net.osmand.plus.quickaction.QuickActionIds.SCOUT_MARK_ACTION_ID;

import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import net.osmand.plus.R;
import net.osmand.plus.activities.MapActivity;
import net.osmand.plus.plugins.PluginsHelper;
import net.osmand.plus.quickaction.QuickAction;
import net.osmand.plus.quickaction.QuickActionType;
import net.osmand.plus.utils.UiUtilities;

/** Map button: pick a kind of sign or setup and drop it at your GPS position. */
public class ScoutMarkAction extends QuickAction {

	public static final QuickActionType TYPE = new QuickActionType(SCOUT_MARK_ACTION_ID,
			"scout.mark", ScoutMarkAction.class)
			.nameRes(R.string.scout_plugin_name)
			.iconRes(R.drawable.ic_action_flag)
			.nonEditable()
			.category(QuickActionType.MY_PLACES)
			.nameActionRes(R.string.scout_mark_action);

	public ScoutMarkAction() {
		super(TYPE);
	}

	public ScoutMarkAction(QuickAction quickAction) {
		super(quickAction);
	}

	@Override
	public void execute(@NonNull MapActivity mapActivity, @Nullable Bundle params) {
		ScoutPlugin plugin = PluginsHelper.getActivePlugin(ScoutPlugin.class);
		if (plugin != null) {
			plugin.showMarkPickerAtMyLocation(mapActivity);
		}
	}

	@Override
	public void drawUI(@NonNull ViewGroup parent, @NonNull MapActivity mapActivity, boolean nightMode) {
		View view = UiUtilities.inflate(parent.getContext(), nightMode, R.layout.quick_action_with_text, parent, false);
		((TextView) view.findViewById(R.id.text)).setText(R.string.scout_mark_action_descr);
		parent.addView(view);
	}
}
