package net.osmand.plus.plugins.scout;

import static net.osmand.aidlapi.OsmAndCustomizationConstants.MAP_CONTEXT_MENU_ACTIONS;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;

import net.osmand.IndexConstants;
import net.osmand.Location;
import net.osmand.PlatformUtil;
import net.osmand.data.FavouritePoint;
import net.osmand.data.LatLon;
import net.osmand.plus.OsmandApplication;
import net.osmand.plus.R;
import net.osmand.plus.activities.MapActivity;
import net.osmand.plus.myplaces.favorites.FavouritesHelper;
import net.osmand.plus.myplaces.favorites.add.AddFavoriteOptions;
import net.osmand.plus.plugins.OsmandPlugin;
import net.osmand.plus.quickaction.QuickActionType;
import net.osmand.plus.render.RenderingIcons;
import net.osmand.plus.shared.SharedUtil;
import net.osmand.plus.track.GpxSelectionParams;
import net.osmand.plus.widgets.ctxmenu.ContextMenuAdapter;
import net.osmand.plus.widgets.ctxmenu.callback.ItemClickListener;
import net.osmand.plus.widgets.ctxmenu.data.ContextMenuItem;
import net.osmand.shared.gpx.GpxDataItem;
import net.osmand.shared.gpx.GpxFile;
import net.osmand.shared.gpx.primitives.Track;
import net.osmand.shared.gpx.primitives.TrkSegment;
import net.osmand.shared.gpx.primitives.WptPt;
import net.osmand.shared.io.KFile;
import net.osmand.util.MapUtils;

import org.apache.commons.logging.Log;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Scout: field recording for the hunting app.
 *
 * - Your own walk is OsmAnd's normal trip recording (the default "human trail").
 * - Sign and setups are dropped as favourites in "Scout/..." folders, from the map's
 *   long-press menu or from a quick-action button at your GPS position.
 * - Deer trails are walked and saved as their own GPX lines in tracks/Scout/.
 *
 * Stand Planner will move into this same plugin and read these favourites and tracks in-process.
 */
public class ScoutPlugin extends OsmandPlugin {

	private static final Log LOG = PlatformUtil.getLog(ScoutPlugin.class);

	public static final String PLUGIN_ID = "hunting.scout";
	public static final String TRACKS_SUBDIR = "Scout";
	private static final String CONTEXT_MENU_MARK_ID = MAP_CONTEXT_MENU_ACTIONS + "scout_mark";
	private static final int CONTEXT_MENU_ORDER = 10600;

	/** Minimum spacing between recorded deer-trail points, in metres. */
	private static final double DEER_TRAIL_MIN_STEP_M = 3.0;
	/** Ignore fixes worse than this, in metres. */
	private static final float DEER_TRAIL_MAX_ACCURACY_M = 25f;

	private boolean deerTrailActive;
	private final List<WptPt> deerTrailPoints = new ArrayList<>();
	private long deerTrailStart;

	public ScoutPlugin(@NonNull OsmandApplication app) {
		super(app);
	}

	@Override
	public String getId() {
		return PLUGIN_ID;
	}

	@Override
	public String getName() {
		return app.getString(R.string.scout_plugin_name);
	}

	@Override
	public CharSequence getDescription(boolean linksEnabled) {
		return app.getString(R.string.scout_plugin_description);
	}

	@Override
	public int getLogoResourceId() {
		return R.drawable.ic_action_flag;
	}

	@Override
	public boolean isEnableByDefault() {
		return true;
	}

	@Override
	protected List<QuickActionType> getQuickActionTypes() {
		List<QuickActionType> types = new ArrayList<>();
		types.add(ScoutMarkAction.TYPE);
		types.add(ScoutDeerTrailAction.TYPE);
		return types;
	}

	// ---- Marks ---------------------------------------------------------------------------

	@Override
	protected void registerMapContextMenuActions(@NonNull MapActivity mapActivity,
	                                             double latitude, double longitude,
	                                             @NonNull ContextMenuAdapter adapter,
	                                             Object selectedObj, boolean configureMenu) {
		ItemClickListener listener = (uiAdapter, view, item, isChecked) -> {
			showMarkPicker(mapActivity, latitude, longitude);
			return true;
		};
		adapter.addItem(new ContextMenuItem(CONTEXT_MENU_MARK_ID)
				.setTitleId(R.string.scout_mark_here, mapActivity)
				.setIcon(R.drawable.ic_action_flag)
				.setOrder(CONTEXT_MENU_ORDER)
				.setListener(listener));
	}

	public void showMarkPicker(@NonNull MapActivity mapActivity, double latitude, double longitude) {
		ScoutMark[] marks = ScoutMark.values();
		new AlertDialog.Builder(mapActivity)
				.setTitle(R.string.scout_mark_here)
				.setItems(ScoutMark.labels(), (dialog, which) -> addMark(marks[which], latitude, longitude))
				.setNegativeButton(R.string.shared_string_cancel, null)
				.show();
	}

	/** Marks at the current GPS fix, falling back to the map centre if there is no fix yet. */
	public void showMarkPickerAtMyLocation(@NonNull MapActivity mapActivity) {
		Location loc = app.getLocationProvider().getLastKnownLocation();
		if (loc != null) {
			showMarkPicker(mapActivity, loc.getLatitude(), loc.getLongitude());
		} else {
			LatLon centre = mapActivity.getMapLocation();
			app.showShortToastMessage(R.string.scout_no_gps_using_map_centre);
			showMarkPicker(mapActivity, centre.getLatitude(), centre.getLongitude());
		}
	}

	@Nullable
	public FavouritePoint addMark(@NonNull ScoutMark mark, double latitude, double longitude) {
		long now = System.currentTimeMillis();
		String stamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date(now));
		String name = mark.label + " " + stamp;

		FavouritePoint point = new FavouritePoint(latitude, longitude, name, mark.folder, Double.NaN, now);
		point.setColor(mark.color);
		if (RenderingIcons.containsBigIcon(mark.iconName)) {
			point.setIconIdFromName(mark.iconName);
		}
		point.setDescription(mark.tag());

		FavouritesHelper helper = app.getFavoritesHelper();
		helper.ensureFavoriteGroup(mark.folder);
		AddFavoriteOptions options = new AddFavoriteOptions().setSortAndSave(true).setSaveAsync(true);
		switch (helper.addFavourite(point, options)) {
			case ADDED:
				app.showShortToastMessage(app.getString(R.string.scout_mark_added, mark.label));
				return point;
			default:
				app.showShortToastMessage(R.string.scout_mark_failed);
				return null;
		}
	}

	// ---- Deer trails ---------------------------------------------------------------------

	public boolean isDeerTrailActive() {
		return deerTrailActive;
	}

	public void toggleDeerTrail(@NonNull MapActivity mapActivity) {
		if (deerTrailActive) {
			finishDeerTrail(mapActivity);
		} else {
			startDeerTrail();
		}
	}

	private void startDeerTrail() {
		deerTrailPoints.clear();
		deerTrailStart = System.currentTimeMillis();
		deerTrailActive = true;
		Location loc = app.getLocationProvider().getLastKnownLocation();
		if (loc != null) {
			addDeerTrailPoint(loc);
		}
		app.showShortToastMessage(R.string.scout_deer_trail_started);
	}

	@Override
	public void updateLocation(Location location) {
		if (deerTrailActive && location != null) {
			addDeerTrailPoint(location);
		}
	}

	private void addDeerTrailPoint(@NonNull Location loc) {
		if (loc.hasAccuracy() && loc.getAccuracy() > DEER_TRAIL_MAX_ACCURACY_M) {
			return;
		}
		if (!deerTrailPoints.isEmpty()) {
			WptPt last = deerTrailPoints.get(deerTrailPoints.size() - 1);
			double d = MapUtils.getDistance(last.getLat(), last.getLon(), loc.getLatitude(), loc.getLongitude());
			if (d < DEER_TRAIL_MIN_STEP_M) {
				return;
			}
		}
		WptPt pt = new WptPt(loc.getLatitude(), loc.getLongitude());
		pt.setTime(loc.getTime());
		if (loc.hasAltitude()) {
			pt.setEle(loc.getAltitude());
		}
		if (loc.hasAccuracy()) {
			pt.setHdop(loc.getAccuracy());
		}
		deerTrailPoints.add(pt);
	}

	private void finishDeerTrail(@NonNull MapActivity mapActivity) {
		deerTrailActive = false;
		if (deerTrailPoints.size() < 2) {
			deerTrailPoints.clear();
			app.showShortToastMessage(R.string.scout_deer_trail_too_short);
			return;
		}
		String stamp = new SimpleDateFormat("yyyy-MM-dd_HH-mm", Locale.US).format(new Date(deerTrailStart));
		String title = "Deer trail " + stamp;

		File dir = new File(app.getAppPath(IndexConstants.GPX_INDEX_DIR), TRACKS_SUBDIR);
		if (!dir.exists() && !dir.mkdirs()) {
			app.showShortToastMessage(R.string.scout_mark_failed);
			return;
		}
		File file = new File(dir, "deer-trail_" + stamp + IndexConstants.GPX_FILE_EXT);

		GpxFile gpx = new GpxFile("OsmAnd Scout");
		Track track = new Track();
		track.setName(title);
		TrkSegment segment = new TrkSegment();
		segment.getPoints().addAll(deerTrailPoints);
		track.getSegments().add(segment);
		gpx.getTracks().add(track);
		gpx.setColor(ScoutMark.CROSSING.color);
		gpx.setPath(file.getAbsolutePath());

		Exception error = SharedUtil.writeGpxFile(file, gpx);
		deerTrailPoints.clear();
		if (error != null) {
			LOG.error("Failed to save deer trail", error);
			app.showShortToastMessage(R.string.scout_mark_failed);
			return;
		}

		GpxDataItem item = new GpxDataItem(new KFile(gpx.getPath()));
		item.readGpxParams(gpx);
		app.getGpxDbHelper().add(item);
		app.getSelectedGpxHelper().selectGpxFile(gpx, GpxSelectionParams.getDefaultSelectionParams());
		mapActivity.refreshMap();
		app.showShortToastMessage(app.getString(R.string.scout_deer_trail_saved, file.getName()));
	}
}
