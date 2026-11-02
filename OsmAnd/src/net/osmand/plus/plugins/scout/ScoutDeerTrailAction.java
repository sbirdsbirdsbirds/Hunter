package net.osmand.plus.plugins.scout;

import static net.osmand.plus.quickaction.QuickActionIds.SCOUT_DEER_TRAIL_ACTION_ID;

import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import net.osmand.plus.OsmandApplication;
import net.osmand.plus.R;
import net.osmand.plus.activities.MapActivity;
import net.osmand.plus.plugins.PluginsHelper;
import net.osmand.plus.quickaction.QuickAction;
import net.osmand.plus.quickaction.QuickActionType;
import net.osmand.plus.utils.UiUtilities;

/** Map button: start walking a deer trail, tap again to save it as its own line. */
public class ScoutDeerTrailAction extends QuickAction {

	public static final QuickActionType TYPE = new QuickActionType(SCOUT_DEER_TRAIL_ACTION_ID,
			"scout.deer_trail", ScoutDeerTrailAction.class)
			.nameRes(R.string.scout_deer_trail)
			.iconRes(R.drawable.ic_action_track_recordable)
			.nonEditable()
			.category(QuickActionType.MY_PLACES)
			.nameActionRes(R.string.shared_string_record);

	public ScoutDeerTrailAction() {
		super(TYPE);
	}

	public ScoutDeerTrailAction(QuickAction quickAction) {
		super(quickAction);
	}

	@Override
	public void execute(@NonNull MapActivity mapActivity, @Nullable Bundle params) {
		ScoutPlugin plugin = PluginsHelper.getActivePlugin(ScoutPlugin.class);
		if (plugin != null) {
			plugin.toggleDeerTrail(mapActivity);
		}
	}

	@Override
	public boolean isActionWithSlash(@NonNull OsmandApplication app) {
		ScoutPlugin plugin = PluginsHelper.getActivePlugin(ScoutPlugin.class);
		return plugin != null && plugin.isDeerTrailActive();
	}

	@Override
	public String getActionText(@NonNull OsmandApplication app) {
		return app.getString(isActionWithSlash(app) ? R.string.scout_deer_trail_stop : R.string.scout_deer_trail_start);
	}

	@Override
	public void drawUI(@NonNull ViewGroup parent, @NonNull MapActivity mapActivity, boolean nightMode) {
		View view = UiUtilities.inflate(parent.getContext(), nightMode, R.layout.quick_action_with_text, parent, false);
		((TextView) view.findViewById(R.id.text)).setText(R.string.scout_deer_trail_descr);
		parent.addView(view);
	}
}
