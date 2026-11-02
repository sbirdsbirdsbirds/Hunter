package net.osmand.plus.plugins.scout.planner;

import android.webkit.JavascriptInterface;
import android.webkit.WebView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.DialogFragment;

import net.osmand.PlatformUtil;
import net.osmand.plus.OsmandApplication;

import org.apache.commons.logging.Log;
import org.json.JSONObject;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * window.OsmAndBridge for the planner page.
 *
 * Calls are asynchronous: JS calls request(id, method, argsJson) and gets the answer through
 * window.__oaResolve(id, ok, json), so slow map reads never freeze the page.
 */
public class PlannerBridge {

	private static final Log LOG = PlatformUtil.getLog(PlannerBridge.class);

	private final OsmandApplication app;
	private final PlannerData data;
	private final ExecutorService executor = Executors.newSingleThreadExecutor();
	@Nullable
	private WebView webView;
	@Nullable
	private DialogFragment host;

	PlannerBridge(@NonNull OsmandApplication app, @NonNull WebView webView, @NonNull DialogFragment host) {
		this.app = app;
		this.data = new PlannerData(app);
		this.webView = webView;
		this.host = host;
	}

	@JavascriptInterface
	public int version() {
		return 1;
	}

	@JavascriptInterface
	public void request(final int id, final String method, final String argsJson) {
		executor.execute(() -> {
			try {
				JSONObject args = argsJson == null || argsJson.isEmpty() ? new JSONObject() : new JSONObject(argsJson);
				Object result = dispatch(method, args);
				resolve(id, true, result == null ? "null" : result.toString());
			} catch (Throwable e) {
				LOG.error("Stand Planner bridge: " + method + " failed", e);
				String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
				resolve(id, false, JSONObject.quote(msg));
			}
		});
	}

	@Nullable
	private Object dispatch(@NonNull String method, @NonNull JSONObject args) throws Exception {
		switch (method) {
			case "getPlanData":
				return data.collectPlanData();
			case "getMapFeatures":
				return data.queryMapFeatures(args.getDouble("south"), args.getDouble("west"),
						args.getDouble("north"), args.getDouble("east"));
			case "saveResults":
				return data.saveResults(args);
			case "saveFile":
				return JSONObject.quote(data.saveFile(args.getString("name"), args.getString("text")));
			case "toast":
				app.showShortToastMessage(args.optString("text"));
				return null;
			case "close":
				app.runInUIThread(() -> {
					DialogFragment h = host;
					if (h != null && h.isAdded()) {
						h.dismissAllowingStateLoss();
					}
				});
				return null;
			default:
				throw new IllegalArgumentException("Unknown method " + method);
		}
	}

	private void resolve(int id, boolean ok, @NonNull String json) {
		app.runInUIThread(() -> {
			WebView wv = webView;
			if (wv != null) {
				wv.evaluateJavascript("window.__oaResolve&&window.__oaResolve(" + id + "," + ok + "," + json + ")", null);
			}
		});
	}

	void shutdown() {
		webView = null;
		host = null;
		executor.shutdownNow();
	}
}
