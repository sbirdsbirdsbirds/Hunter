package net.osmand.plus.plugins.scout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * The kinds of field sign and setups Scout can drop on the map.
 * Each kind is stored as an OsmAnd favourite in its own folder under "Scout/",
 * so it shows up everywhere OsmAnd shows favourites and Stand Planner can read it in-process.
 */
public enum ScoutMark {

	// Sign
	SCRAPE("scrape", "Scrape", "Scout/Scrapes", "hunting", 0xFF8D5524, true),
	RUB("rub", "Rub", "Scout/Rubs", "tree", 0xFFB5651D, true),
	BED("bed", "Bed", "Scout/Beds", "bed", 0xFF6B8E23, true),
	TRACKS("tracks", "Tracks", "Scout/Tracks", "dog_track", 0xFF795548, true),
	CROSSING("crossing", "Trail crossing", "Scout/Crossings", "wildlife_crossing", 0xFFE65100, true),
	SIGHTING("sighting", "Sighting", "Scout/Sightings", "hunting", 0xFFD50000, true),

	// Setups
	CAMERA("camera", "Trail camera", "Scout/Cameras", "surveillance", 0xFF1565C0, false),
	BLIND("blind", "Blind", "Scout/Blinds", "hunting_stand", 0xFF2E7D32, false),
	BAIT("bait", "Bait", "Scout/Bait", "feeding_place", 0xFFF9A825, false),
	SALT_LICK("salt_lick", "Salt lick", "Scout/Salt licks", "salt_yes", 0xFF9E9E9E, false);

	/** Written into the favourite description so the planner can classify points without parsing names. */
	public static final String TAG_PREFIX = "scout:kind=";

	public final String key;
	public final String label;
	public final String folder;
	public final String iconName;
	public final int color;
	public final boolean isSign;

	ScoutMark(String key, String label, String folder, String iconName, int color, boolean isSign) {
		this.key = key;
		this.label = label;
		this.folder = folder;
		this.iconName = iconName;
		this.color = color;
		this.isSign = isSign;
	}

	@NonNull
	public String tag() {
		return TAG_PREFIX + key;
	}

	@Nullable
	public static ScoutMark fromKey(@Nullable String key) {
		for (ScoutMark m : values()) {
			if (m.key.equals(key)) {
				return m;
			}
		}
		return null;
	}

	/** Recovers the kind from a favourite description written by {@link #tag()}. */
	@Nullable
	public static ScoutMark fromDescription(@Nullable String description) {
		if (description == null) {
			return null;
		}
		int i = description.indexOf(TAG_PREFIX);
		if (i < 0) {
			return null;
		}
		int start = i + TAG_PREFIX.length();
		int end = start;
		while (end < description.length() && !Character.isWhitespace(description.charAt(end))) {
			end++;
		}
		return fromKey(description.substring(start, end));
	}

	@NonNull
	public static String[] labels() {
		ScoutMark[] v = values();
		String[] out = new String[v.length];
		for (int i = 0; i < v.length; i++) {
			out[i] = v[i].label;
		}
		return out;
	}
}
