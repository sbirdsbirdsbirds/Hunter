package net.osmand.plus.plugins.scout;

import static net.osmand.plus.quickaction.QuickActionIds.SCOUT_STAND_PLANNER_ACTION_ID;

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

/** Map button: open Stand Planner. */
public class StandPlannerAction extends QuickAction {

	public static final QuickActionType TYPE = new QuickActionType(SCOUT_STAND_PLANNER_ACTION_ID,
			"scout.stand_planner", StandPlannerAction.class)
			.nameRes(R.string.scout_stand_planner)
			.iconRes(R.drawable.ic_action_flag)
			.nonEditable()
			.category(QuickActionType.MY_PLACES)
			.nameActionRes(R.string.shared_string_open);

	public StandPlannerAction() {
		super(TYPE);
	}

	public StandPlannerAction(QuickAction quickAction) {
		super(quickAction);
	}

	@Override
	public void execute(@NonNull MapActivity mapActivity, @Nullable Bundle params) {
		ScoutPlugin plugin = PluginsHelper.getActivePlugin(ScoutPlugin.class);
		if (plugin != null) {
			plugin.openStandPlanner(mapActivity);
		}
	}

	@Override
	public void drawUI(@NonNull ViewGroup parent, @NonNull MapActivity mapActivity, boolean nightMode) {
		View view = UiUtilities.inflate(parent.getContext(), nightMode, R.layout.quick_action_with_text, parent, false);
		((TextView) view.findViewById(R.id.text)).setText(R.string.scout_stand_planner_descr);
		parent.addView(view);
	}
}
