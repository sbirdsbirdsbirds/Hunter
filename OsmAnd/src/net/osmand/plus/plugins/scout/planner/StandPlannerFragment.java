package net.osmand.plus.plugins.scout.planner;

import android.annotation.SuppressLint;
import android.app.Dialog;
import android.content.res.AssetManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.DialogFragment;
import androidx.fragment.app.FragmentActivity;
import androidx.fragment.app.FragmentManager;

import net.osmand.PlatformUtil;
import net.osmand.plus.OsmandApplication;
import net.osmand.plus.widgets.WebViewEx;

import org.apache.commons.logging.Log;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Full-screen Stand Planner inside OsmAnd.
 *
 * The planner's own files ship in assets/standplanner/ and are served at a private https origin
 * (so its saved state persists), every other request is refused (nothing leaves the phone),
 * and window.OsmAndBridge gives it OsmAnd's data in-process.
 */
public class StandPlannerFragment extends DialogFragment {

	public static final String TAG = "StandPlannerFragment";
	private static final Log LOG = PlatformUtil.getLog(StandPlannerFragment.class);

	static final String ORIGIN_HOST = "standplanner.osmand.local";
	private static final String ORIGIN = "https://" + ORIGIN_HOST + "/";
	private static final String ASSET_DIR = "standplanner/";

	private static final Map<String, String> MIME = new HashMap<>();

	static {
		MIME.put("html", "text/html");
		MIME.put("js", "application/javascript");
		MIME.put("css", "text/css");
		MIME.put("png", "image/png");
		MIME.put("json", "application/json");
		MIME.put("webmanifest", "application/manifest+json");
	}

	@Nullable
	private WebView webView;
	@Nullable
	private PlannerBridge bridge;
	@Nullable
	private ValueCallback<Uri[]> pendingFileCallback;

	private final ActivityResultLauncher<String[]> filePicker =
			registerForActivityResult(new ActivityResultContracts.OpenMultipleDocuments(), this::onFilesPicked);

	public static void showInstance(@NonNull FragmentActivity activity) {
		FragmentManager fm = activity.getSupportFragmentManager();
		if (fm.findFragmentByTag(TAG) == null && !fm.isStateSaved()) {
			new StandPlannerFragment().show(fm, TAG);
		}
	}

	@Override
	public void onCreate(@Nullable Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setStyle(STYLE_NO_FRAME, android.R.style.Theme_DeviceDefault_Light_NoActionBar);
	}

	@NonNull
	@Override
	public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
		Dialog dialog = super.onCreateDialog(savedInstanceState);
		dialog.setOnKeyListener((d, keyCode, event) -> {
			if (keyCode == KeyEvent.KEYCODE_BACK && event.getAction() == KeyEvent.ACTION_UP
					&& webView != null && webView.canGoBack()) {
				webView.goBack();
				return true;
			}
			return false;
		});
		return dialog;
	}

	@Override
	public void onStart() {
		super.onStart();
		Dialog dialog = getDialog();
		if (dialog != null && dialog.getWindow() != null) {
			dialog.getWindow().setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
		}
	}

	@SuppressLint({"SetJavaScriptEnabled", "JavascriptInterface"})
	@Nullable
	@Override
	public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
	                         @Nullable Bundle savedInstanceState) {
		OsmandApplication app = (OsmandApplication) requireActivity().getApplication();
		WebViewEx wv = new WebViewEx(requireContext());
		wv.setBackgroundColor(Color.WHITE);

		WebSettings s = wv.getSettings();
		s.setJavaScriptEnabled(true);
		s.setDomStorageEnabled(true);
		s.setAllowFileAccess(false);
		s.setAllowContentAccess(true);
		s.setBlockNetworkLoads(false); // requests are answered locally in shouldInterceptRequest
		s.setSupportZoom(false);
		s.setMediaPlaybackRequiresUserGesture(true);

		AssetManager assets = requireContext().getAssets();
		wv.setWebViewClient(new WebViewClient() {
			@Nullable
			@Override
			public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
				return serve(assets, request.getUrl());
			}

			@Override
			public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
				// Stay inside the planner; never open outside pages.
				return !ORIGIN_HOST.equals(request.getUrl().getHost());
			}
		});
		wv.setWebChromeClient(new WebChromeClient() {
			@Override
			public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
				if (pendingFileCallback != null) {
					pendingFileCallback.onReceiveValue(null);
				}
				pendingFileCallback = callback;
				try {
					filePicker.launch(new String[] {"*/*"});
					return true;
				} catch (Exception e) {
					LOG.error("Stand Planner: no file picker", e);
					pendingFileCallback = null;
					return false;
				}
			}
		});

		bridge = new PlannerBridge(app, wv, this);
		wv.addJavascriptInterface(bridge, "OsmAndBridge");
		wv.loadUrl(ORIGIN + "index.html");
		webView = wv;
		return wv;
	}

	@Nullable
	private static WebResourceResponse serve(@NonNull AssetManager assets, @NonNull Uri url) {
		if (!ORIGIN_HOST.equals(url.getHost())) {
			return blocked();
		}
		String path = url.getPath();
		if (path == null || path.equals("/") || path.isEmpty()) {
			path = "/index.html";
		}
		path = path.substring(1);
		if (path.contains("..")) {
			return blocked();
		}
		int dot = path.lastIndexOf('.');
		String ext = dot >= 0 ? path.substring(dot + 1) : "";
		String mime = MIME.containsKey(ext) ? MIME.get(ext) : "application/octet-stream";
		try {
			InputStream in = assets.open(ASSET_DIR + path);
			WebResourceResponse r = new WebResourceResponse(mime, "UTF-8", in);
			Map<String, String> headers = new HashMap<>();
			headers.put("Cache-Control", "no-cache");
			r.setResponseHeaders(headers);
			return r;
		} catch (Exception e) {
			WebResourceResponse r = new WebResourceResponse("text/plain", "UTF-8", new ByteArrayInputStream(new byte[0]));
			r.setStatusCodeAndReasonPhrase(404, "Not Found");
			return r;
		}
	}

	@NonNull
	private static WebResourceResponse blocked() {
		WebResourceResponse r = new WebResourceResponse("text/plain", "UTF-8", new ByteArrayInputStream(new byte[0]));
		r.setStatusCodeAndReasonPhrase(403, "Blocked: Stand Planner stays offline");
		return r;
	}

	private void onFilesPicked(@Nullable List<Uri> uris) {
		ValueCallback<Uri[]> cb = pendingFileCallback;
		pendingFileCallback = null;
		if (cb != null) {
			cb.onReceiveValue(uris == null || uris.isEmpty() ? null : uris.toArray(new Uri[0]));
		}
	}

	@Override
	public void onDestroyView() {
		if (pendingFileCallback != null) {
			pendingFileCallback.onReceiveValue(null);
			pendingFileCallback = null;
		}
		if (bridge != null) {
			bridge.shutdown();
			bridge = null;
		}
		if (webView != null) {
			webView.removeJavascriptInterface("OsmAndBridge");
			webView.destroy();
			webView = null;
		}
		super.onDestroyView();
	}
}
