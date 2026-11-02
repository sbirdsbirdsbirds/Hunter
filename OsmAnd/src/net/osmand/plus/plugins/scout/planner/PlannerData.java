package net.osmand.plus.plugins.scout.planner;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import net.osmand.IndexConstants;
import net.osmand.PlatformUtil;
import net.osmand.binary.BinaryMapDataObject;
import net.osmand.binary.BinaryMapIndexReader;
import net.osmand.binary.BinaryMapIndexReader.MapIndex;
import net.osmand.binary.BinaryMapIndexReader.SearchRequest;
import net.osmand.binary.BinaryMapIndexReader.TagValuePair;
import net.osmand.data.FavouritePoint;
import net.osmand.plus.OsmandApplication;
import net.osmand.plus.myplaces.favorites.FavouritesHelper;
import net.osmand.plus.myplaces.favorites.add.AddFavoriteOptions;
import net.osmand.plus.myplaces.favorites.add.AddFavoriteResult;
import net.osmand.plus.plugins.scout.ScoutMark;
import net.osmand.plus.plugins.scout.ScoutPlugin;
import net.osmand.plus.resources.BinaryMapReaderResource;
import net.osmand.plus.shared.SharedUtil;
import net.osmand.plus.track.GpxSelectionParams;
import net.osmand.plus.track.helpers.SelectedGpxFile;
import net.osmand.shared.gpx.GpxDataItem;
import net.osmand.shared.gpx.GpxFile;
import net.osmand.shared.gpx.primitives.Track;
import net.osmand.shared.gpx.primitives.TrkSegment;
import net.osmand.shared.gpx.primitives.WptPt;
import net.osmand.shared.io.KFile;
import net.osmand.util.MapUtils;

import org.apache.commons.logging.Log;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.RandomAccessFile;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import gnu.trove.list.array.TIntArrayList;
import gnu.trove.iterator.TIntObjectIterator;

/**
 * Everything Stand Planner reads from and writes to OsmAnd, in-process.
 *
 * Rule: the planner uses whatever favourites and tracks are currently shown on the OsmAnd map,
 * except its own output (planned blinds and walk-in routes), so hiding a folder or track in
 * OsmAnd removes it from the plan.
 */
public class PlannerData {

	private static final Log LOG = PlatformUtil.getLog(PlannerData.class);

	public static final String PLANNED_BLINDS_FOLDER = "Scout/Planned blinds";
	public static final String WALK_INS_SUBDIR = ScoutPlugin.TRACKS_SUBDIR + "/Walk-ins";
	public static final String PLANNER_FILES_DIR = "standplanner";

	/** Detailed zoom: picks the most detailed map level and the contour lines. */
	private static final int QUERY_ZOOM = 15;
	private static final int MAX_FEATURES = 20000;

	private final OsmandApplication app;

	public PlannerData(@NonNull OsmandApplication app) {
		this.app = app;
	}

	// ---- Plan data: points + lines -------------------------------------------------------

	@NonNull
	public JSONObject collectPlanData() throws JSONException {
		JSONObject out = new JSONObject();
		out.put("pois", collectPoints());
		out.put("tracks", collectTracks());
		return out;
	}

	@NonNull
	private JSONArray collectPoints() throws JSONException {
		JSONArray pois = new JSONArray();
		FavouritesHelper helper = app.getFavoritesHelper();
		for (FavouritePoint p : new ArrayList<>(helper.getVisibleFavouritePoints())) {
			String category = p.getCategory() == null ? "" : p.getCategory();
			if (category.startsWith(PLANNED_BLINDS_FOLDER) || p.isSpecialPoint()) {
				continue;
			}
			JSONObject o = new JSONObject();
			// Stable across renames: creation time when known, otherwise name+folder.
			String key = p.getTimestamp() > 0 ? "t" + p.getTimestamp() : p.getKey();
			o.put("key", key);
			o.put("name", p.getName());
			o.put("folder", category);
			o.put("lat", round6(p.getLatitude()));
			o.put("lon", round6(p.getLongitude()));
			if (!Double.isNaN(p.getAltitude()) && p.getAltitude() != 0) {
				o.put("ele", Math.round(p.getAltitude() * 10) / 10.0);
			}
			if (p.getTimestamp() > 0) {
				o.put("time", p.getTimestamp());
			}
			ScoutMark mark = ScoutMark.fromDescription(p.getDescription());
			if (mark != null) {
				o.put("scout", mark.key);
				o.put("type", mark.plannerType);
			}
			pois.put(o);
		}
		return pois;
	}

	@NonNull
	private JSONArray collectTracks() throws JSONException {
		JSONArray tracks = new JSONArray();
		File tracksDir = app.getAppPath(IndexConstants.GPX_INDEX_DIR);
		String scoutDir = new File(tracksDir, ScoutPlugin.TRACKS_SUBDIR).getAbsolutePath() + File.separator;
		String walkInsDir = new File(tracksDir, WALK_INS_SUBDIR).getAbsolutePath() + File.separator;
		String recDir = new File(tracksDir, "rec").getAbsolutePath() + File.separator;

		for (SelectedGpxFile selected : new ArrayList<>(app.getSelectedGpxHelper().getSelectedGPXFiles())) {
			GpxFile gpx = selected.getGpxFile();
			if (gpx == null) {
				continue;
			}
			boolean current = selected.isShowCurrentTrack();
			String path = current ? "current-recording" : gpx.getPath();
			if (path.startsWith(walkInsDir)) {
				continue; // our own output
			}
			String role = null; // null = let the planner guess from the name
			if (current || path.startsWith(recDir)) {
				role = "route"; // your own walks
			} else if (path.startsWith(scoutDir)) {
				role = "trail"; // Scout deer trails
			}
			String fileTitle = current ? "Current recording" : new File(path).getName().replaceAll("(?i)\\.gpx$", "");
			List<Track> trks = gpx.getTracks();
			for (int ti = 0; ti < trks.size(); ti++) {
				Track trk = trks.get(ti);
				List<TrkSegment> segs = trk.getSegments();
				for (int si = 0; si < segs.size(); si++) {
					List<WptPt> pts = segs.get(si).getPoints();
					if (pts.size() < 2) {
						continue;
					}
					String name = trk.getName() != null && !trk.getName().isEmpty() ? trk.getName() : fileTitle;
					if (segs.size() > 1) {
						name += " " + (si + 1);
					}
					JSONObject o = new JSONObject();
					o.put("key", path + "#" + ti + "." + si);
					o.put("name", name);
					if (role != null) {
						o.put("role", role);
					}
					o.put("pts", encodePoints(pts));
					tracks.put(o);
				}
			}
		}
		return tracks;
	}

	@NonNull
	private static JSONArray encodePoints(@NonNull List<WptPt> pts) throws JSONException {
		// Same thinning as the planner's GPX import: at most ~2500 points per line.
		int step = Math.max(1, (int) Math.ceil(pts.size() / 2500.0));
		JSONArray arr = new JSONArray();
		for (int i = 0; i < pts.size(); i++) {
			if (i % step != 0 && i != pts.size() - 1) {
				continue;
			}
			WptPt p = pts.get(i);
			JSONArray q = new JSONArray();
			q.put(round6(p.getLat()));
			q.put(round6(p.getLon()));
			if (!Double.isNaN(p.getEle())) {
				q.put(Math.round(p.getEle() * 10) / 10.0);
			}
			arr.put(q);
		}
		return arr;
	}

	// ---- Map features from OsmAnd's own map files -----------------------------------------

	/**
	 * Water, wetlands, woods, open ground and contour lines inside the box, from every
	 * map file OsmAnd has loaded (regional maps and terrain/contour files).
	 * Returns objects shaped like the planner's obf.js extract(): {id, area, tags, pts, inner}.
	 */
	@NonNull
	public JSONObject queryMapFeatures(double south, double west, double north, double east) throws JSONException {
		int left = MapUtils.get31TileNumberX(west);
		int right = MapUtils.get31TileNumberX(east);
		int top = MapUtils.get31TileNumberY(north);
		int bottom = MapUtils.get31TileNumberY(south);

		JSONArray results = new JSONArray();
		JSONArray files = new JSONArray();
		Set<String> seen = new HashSet<>();
		int total = 0;

		for (BinaryMapReaderResource res : app.getResourceManager().getFileReaders()) {
			BinaryMapIndexReader shallow = res.getShallowReader();
			if (shallow == null || !shallow.containsMapData(left, top, right, bottom, QUERY_ZOOM)) {
				continue;
			}
			BinaryMapIndexReader reader = null;
			try {
				reader = new BinaryMapIndexReader(new RandomAccessFile(res.getFile(), "r"), shallow);
				SearchRequest<BinaryMapDataObject> req = BinaryMapIndexReader.buildSearchRequest(
						left, right, top, bottom, QUERY_ZOOM, PlannerData::acceptTypes);
				List<BinaryMapDataObject> objs = reader.searchMapIndex(req);
				int fromFile = 0;
				for (BinaryMapDataObject obj : objs) {
					String uid = res.getFileName() + ":" + obj.getId();
					if (!seen.add(uid)) {
						continue;
					}
					JSONObject o = encodeObject(obj);
					if (o == null) {
						continue;
					}
					o.put("id", uid);
					results.put(o);
					fromFile++;
					if (++total >= MAX_FEATURES) {
						break;
					}
				}
				if (fromFile > 0) {
					files.put(res.getFileName());
				}
			} catch (Exception e) {
				LOG.error("Stand Planner: failed to read " + res.getFileName(), e);
			} finally {
				if (reader != null) {
					try {
						reader.close();
					} catch (Exception ignore) {
					}
				}
			}
			if (total >= MAX_FEATURES) {
				break;
			}
		}
		JSONObject out = new JSONObject();
		out.put("results", results);
		out.put("files", files);
		out.put("truncated", total >= MAX_FEATURES);
		return out;
	}

	/** Same selection as the planner's want(): water, wetland, woods, open ground, contours. */
	static boolean wanted(@Nullable String tag, @Nullable String value) {
		if (tag == null) {
			return false;
		}
		String v = value == null ? "" : value;
		switch (tag) {
			case "waterway":
			case "contour":
				return true;
			case "natural":
				return v.matches("water|wetland|wood|scrub|grassland|heath");
			case "landuse":
				return v.matches("forest|meadow|farmland|grass|orchard|clearcut|logging");
			default:
				return false;
		}
	}

	private static boolean acceptTypes(TIntArrayList types, MapIndex index) {
		for (int i = 0; i < types.size(); i++) {
			TagValuePair tp = index.decodeType(types.get(i));
			if (tp != null && wanted(tp.tag, tp.value)) {
				return true;
			}
		}
		return false;
	}

	@Nullable
	private static JSONObject encodeObject(@NonNull BinaryMapDataObject obj) throws JSONException {
		MapIndex index = obj.getMapIndex();
		if (index == null) {
			return null;
		}
		JSONObject tags = new JSONObject();
		boolean keep = false;
		for (int[] list : new int[][] {obj.getTypes(), obj.getAdditionalTypes()}) {
			if (list == null) {
				continue;
			}
			for (int t : list) {
				TagValuePair tp = index.decodeType(t);
				if (tp == null || tp.tag == null) {
					continue;
				}
				tags.put(tp.tag, tp.value == null ? "" : tp.value);
				if (wanted(tp.tag, tp.value)) {
					keep = true;
				}
			}
		}
		if (!keep) {
			return null;
		}
		if (obj.getObjectNames() != null) {
			TIntObjectIterator<String> it = obj.getObjectNames().iterator();
			while (it.hasNext()) {
				it.advance();
				TagValuePair tp = index.decodeType(it.key());
				if (tp != null && tp.tag != null) {
					tags.put(tp.tag, it.value());
				}
			}
		}
		JSONObject o = new JSONObject();
		o.put("area", obj.isArea());
		o.put("tags", tags);
		o.put("pts", decodeCoords(obj.getCoordinates()));
		JSONArray inner = new JSONArray();
		int[][] holes = obj.getPolygonInnerCoordinates();
		if (holes != null) {
			for (int[] h : holes) {
				if (h != null && h.length >= 6) {
					inner.put(decodeCoords(h));
				}
			}
		}
		o.put("inner", inner);
		return o;
	}

	@NonNull
	private static JSONArray decodeCoords(@Nullable int[] c) throws JSONException {
		JSONArray arr = new JSONArray();
		if (c == null) {
			return arr;
		}
		for (int i = 0; i + 1 < c.length; i += 2) {
			JSONArray q = new JSONArray();
			q.put(round6(MapUtils.get31LatitudeY(c[i + 1])));
			q.put(round6(MapUtils.get31LongitudeX(c[i])));
			arr.put(q);
		}
		return arr;
	}

	// ---- Results back into OsmAnd ---------------------------------------------------------

	/**
	 * Replaces the previous plan's output: blinds become favourites in "Scout/Planned blinds",
	 * walk-in routes become one GPX in tracks/Scout/Walk-ins/, shown on the map.
	 * Input: {blinds:[{name,lat,lon,desc}], routes:[{name,pts:[[lat,lon],...]}], title}
	 */
	@NonNull
	public JSONObject saveResults(@NonNull JSONObject in) throws Exception {
		JSONArray blinds = in.optJSONArray("blinds");
		JSONArray routes = in.optJSONArray("routes");
		String title = in.optString("title", "Stand plan");

		int[] added = {0};
		runOnUiAndWait(() -> {
			FavouritesHelper helper = app.getFavoritesHelper();
			helper.deleteFavoriteFolderSubtree(PLANNED_BLINDS_FOLDER, false);
			if (blinds != null && blinds.length() > 0) {
				helper.ensureFavoriteGroup(PLANNED_BLINDS_FOLDER);
				long now = System.currentTimeMillis();
				for (int i = 0; i < blinds.length(); i++) {
					JSONObject b = blinds.optJSONObject(i);
					if (b == null) {
						continue;
					}
					FavouritePoint p = new FavouritePoint(b.optDouble("lat"), b.optDouble("lon"),
							b.optString("name", "Blind " + (i + 1)), PLANNED_BLINDS_FOLDER, Double.NaN, now + i);
					p.setDescription(b.optString("desc", ""));
					p.setColor(ScoutMark.BLIND.color);
					p.setIconIdFromName(ScoutMark.BLIND.iconName);
					if (helper.addFavourite(p, new AddFavoriteOptions()) == AddFavoriteResult.ADDED) {
						added[0]++;
					}
				}
			}
			helper.saveCurrentPointsIntoFile(true);
		});

		File dir = new File(app.getAppPath(IndexConstants.GPX_INDEX_DIR), WALK_INS_SUBDIR);
		File file = new File(dir, "walk-ins" + IndexConstants.GPX_FILE_EXT);
		int routeCount = 0;
		if (routes != null && routes.length() > 0) {
			if (!dir.exists() && !dir.mkdirs()) {
				throw new IllegalStateException("Can't create " + dir);
			}
			GpxFile gpx = new GpxFile("OsmAnd Scout - Stand Planner");
			for (int i = 0; i < routes.length(); i++) {
				JSONObject r = routes.optJSONObject(i);
				JSONArray pts = r == null ? null : r.optJSONArray("pts");
				if (pts == null || pts.length() < 2) {
					continue;
				}
				Track track = new Track();
				track.setName(r.optString("name", "Walk-in " + (i + 1)));
				TrkSegment seg = new TrkSegment();
				for (int k = 0; k < pts.length(); k++) {
					JSONArray q = pts.optJSONArray(k);
					if (q != null && q.length() >= 2) {
						seg.getPoints().add(new WptPt(q.optDouble(0), q.optDouble(1)));
					}
				}
				track.getSegments().add(seg);
				gpx.getTracks().add(track);
				routeCount++;
			}
			gpx.setColor(0xFFFF6D00);
			gpx.setPath(file.getAbsolutePath());
			Exception err = SharedUtil.writeGpxFile(file, gpx);
			if (err != null) {
				throw err;
			}
			runOnUiAndWait(() -> {
				GpxDataItem item = new GpxDataItem(new KFile(gpx.getPath()));
				item.readGpxParams(gpx);
				app.getGpxDbHelper().add(item);
				SelectedGpxFile old = app.getSelectedGpxHelper().getSelectedFileByPath(gpx.getPath());
				if (old != null) {
					old.setGpxFile(gpx, app);
				} else {
					app.getSelectedGpxHelper().selectGpxFile(gpx, GpxSelectionParams.getDefaultSelectionParams());
				}
			});
		} else if (file.exists()) {
			runOnUiAndWait(() -> {
				SelectedGpxFile old = app.getSelectedGpxHelper().getSelectedFileByPath(file.getAbsolutePath());
				if (old != null) {
					app.getSelectedGpxHelper().selectGpxFile(old.getGpxFile(), GpxSelectionParams.newInstance().hideFromMap().syncGroup().saveSelection());
				}
			});
			//noinspection ResultOfMethodCallIgnored
			file.delete();
		}

		JSONObject out = new JSONObject();
		out.put("blinds", added[0]);
		out.put("routes", routeCount);
		out.put("title", title);
		return out;
	}

	/** Writes a backup or GPX into OsmAnd's folder: <OsmAnd>/standplanner/<name>. */
	@NonNull
	public String saveFile(@NonNull String name, @NonNull String text) throws Exception {
		String safe = name.replaceAll("[^A-Za-z0-9._ -]", "_");
		File dir = app.getAppPath(PLANNER_FILES_DIR);
		if (!dir.exists() && !dir.mkdirs()) {
			throw new IllegalStateException("Can't create " + dir);
		}
		File f = new File(dir, safe);
		try (Writer w = new OutputStreamWriter(new FileOutputStream(f), StandardCharsets.UTF_8)) {
			w.write(text);
		}
		return f.getAbsolutePath();
	}

	// ---- helpers --------------------------------------------------------------------------

	private interface UiTask {
		void run() throws Exception;
	}

	private void runOnUiAndWait(@NonNull UiTask task) throws Exception {
		CountDownLatch latch = new CountDownLatch(1);
		Exception[] error = new Exception[1];
		app.runInUIThread(() -> {
			try {
				task.run();
			} catch (Exception e) {
				error[0] = e;
			} finally {
				latch.countDown();
			}
		});
		if (!latch.await(30, TimeUnit.SECONDS)) {
			throw new IllegalStateException("OsmAnd UI thread busy");
		}
		if (error[0] != null) {
			throw error[0];
		}
	}

	private static double round6(double v) {
		return Math.round(v * 1e6) / 1e6;
	}
}
