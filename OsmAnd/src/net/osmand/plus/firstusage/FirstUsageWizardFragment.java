package net.osmand.plus.firstusage;

import android.Manifest;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.StatFs;
import android.provider.Settings.Secure;
import android.support.annotation.Nullable;
import android.support.v4.app.ActivityCompat;
import android.support.v4.app.Fragment;
import android.support.v4.app.FragmentActivity;
import android.support.v7.app.AlertDialog;
import android.support.v7.widget.AppCompatButton;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ProgressBar;
import android.widget.TextView;
import net.osmand.AndroidNetworkUtils;
import net.osmand.Location;
import net.osmand.ValueHolder;
import net.osmand.binary.BinaryMapDataObject;
import net.osmand.map.OsmandRegions;
import net.osmand.map.WorldRegion;
import net.osmand.plus.AppInitializer;
import net.osmand.plus.AppInitializer.AppInitializeListener;
import net.osmand.plus.OsmAndLocationProvider;
import net.osmand.plus.OsmAndLocationProvider.OsmAndLocationListener;
import net.osmand.plus.OsmandApplication;
import net.osmand.plus.OsmandSettings;
import net.osmand.plus.R;
import net.osmand.plus.Version;
import net.osmand.plus.activities.MapActivity;
import net.osmand.plus.download.DownloadActivity;
import net.osmand.plus.download.DownloadActivityType;
import net.osmand.plus.download.DownloadIndexesThread;
import net.osmand.plus.download.DownloadIndexesThread.DownloadEvents;
import net.osmand.plus.download.DownloadValidationManager;
import net.osmand.plus.download.IndexItem;
import net.osmand.plus.download.ui.DataStoragePlaceDialogFragment;
import net.osmand.plus.helpers.AndroidUiHelper;
import net.osmand.plus.resources.ResourceManager;
import net.osmand.util.Algorithms;
import net.osmand.util.MapUtils;

import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;

public class FirstUsageWizardFragment extends Fragment implements OsmAndLocationListener,
		AppInitializeListener, DownloadEvents {
	public static final String TAG = "FirstUsageWizardFrag";
	public static final int FIRST_USAGE_LOCATION_PERMISSION = 300;
	public static final int FIRST_USAGE_REQUEST_WRITE_EXTERNAL_STORAGE_PERMISSION = 400;
	public static final String WIZARD_TYPE_KEY = "wizard_type_key";
	public static final String SEARCH_LOCATION_BY_IP_KEY = "search_location_by_ip_key";

	private View view;
	private DownloadIndexesThread downloadThread;
	private DownloadValidationManager validationManager;
	private MessageFormat formatGb = new MessageFormat("{0, number,#.##} GB", Locale.US);

	private static WizardType wizardType;
	private static final WizardType DEFAULT_WIZARD_TYPE = WizardType.SEARCH_LOCATION;
	private static boolean searchLocationByIp;

	private Timer locationSearchTimer;
	private boolean waitForIndexes;
	List<IndexItem> indexItems = new ArrayList<>();

	private static Location location;
	private static WorldRegion localDownloadRegion;
	private static IndexItem localMapIndexItem;
	private static IndexItem baseMapIndexItem;
	private static boolean firstMapDownloadCancelled;
	private static boolean secondMapDownloadCancelled;
	private static boolean wizardClosed;

	enum WizardType {
		SEARCH_LOCATION,
		NO_INTERNET,
		NO_LOCATION,
		SEARCH_MAP,
		MAP_FOUND,
		MAP_DOWNLOAD
	}

	@Override
	public void onCreate(@Nullable Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		downloadThread = getMyApplication().getDownloadThread();
		validationManager = new DownloadValidationManager(getMyApplication());
		Bundle args = getArguments();
		if (args != null) {
			wizardType = WizardType.valueOf(args.getString(WIZARD_TYPE_KEY, DEFAULT_WIZARD_TYPE.name()));
			searchLocationByIp = args.getBoolean(SEARCH_LOCATION_BY_IP_KEY, false);
		}
	}

	@Nullable
	@Override
	public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
		view = inflater.inflate(R.layout.first_usage_wizard_fragment, container, false);

		if (!AndroidUiHelper.isOrientationPortrait(getActivity()) && !AndroidUiHelper.isXLargeDevice(getActivity())) {
			TextView wizardDescription = (TextView) view.findViewById(R.id.wizard_description);
			wizardDescription.setMinimumHeight(0);
			wizardDescription.setMinHeight(0);
		}

		AppCompatButton skipButton = (AppCompatButton) view.findViewById(R.id.skip_button);
		skipButton.setOnClickListener(new View.OnClickListener() {
			@Override
			public void onClick(View v) {
				AlertDialog.Builder builder = new AlertDialog.Builder(getContext());
				builder.setTitle(getString(R.string.skip_map_downloading));
				builder.setMessage(getString(R.string.skip_map_downloading_desc, getString(R.string.index_settings)));
				builder.setNegativeButton(R.string.shared_string_skip, new DialogInterface.OnClickListener() {
					@Override
					public void onClick(DialogInterface dialog, int which) {
						closeWizard();
					}
				});
				builder.setNeutralButton(R.string.shared_string_cancel, null);
				builder.setPositiveButton(R.string.shared_string_select, new DialogInterface.OnClickListener() {
					@Override
					public void onClick(DialogInterface dialog, int which) {
						searchCountryMap();
					}
				});
				builder.show();
			}
		});

		view.findViewById(R.id.action_button).setVisibility(View.GONE);

		switch (wizardType) {
			case SEARCH_LOCATION:
				view.findViewById(R.id.search_location_card).setVisibility(View.VISIBLE);
				view.findViewById(R.id.search_location_action_button).setEnabled(false);
				break;
			case NO_INTERNET:
				view.findViewById(R.id.no_inet_card).setVisibility(View.VISIBLE);
				view.findViewById(R.id.no_inet_action_button).setOnClickListener(new View.OnClickListener() {
					@Override
					public void onClick(View v) {
						startWizard(getActivity());
					}
				});
				break;
			case NO_LOCATION:
				view.findViewById(R.id.no_location_card).setVisibility(View.VISIBLE);
				view.findViewById(R.id.no_location_action_button).setOnClickListener(new View.OnClickListener() {
					@Override
					public void onClick(View v) {
						findLocation(getActivity(), false);
					}
				});
				break;
			case SEARCH_MAP:
				view.findViewById(R.id.search_map_card).setVisibility(View.VISIBLE);
				view.findViewById(R.id.search_map_action_button).setEnabled(false);
				break;
			case MAP_FOUND:
				TextView mapTitle = (TextView) view.findViewById(R.id.map_download_title);
				TextView mapDescription = (TextView) view.findViewById(R.id.map_download_desc);
				final IndexItem indexItem = localMapIndexItem != null ? localMapIndexItem : baseMapIndexItem;
				if (indexItem != null) {
					mapTitle.setText(indexItem.getVisibleName(getContext(), getMyApplication().getRegions(), false));
					mapDescription.setText(indexItem.getSizeDescription(getContext()));
				}
				view.findViewById(R.id.map_download_action_button).setOnClickListener(new View.OnClickListener() {
					@Override
					public void onClick(View v) {
						boolean spaceEnoughForBoth = validationManager.isSpaceEnoughForDownload(getActivity(), false, localMapIndexItem, baseMapIndexItem);
						boolean spaceEnoughForLocal = validationManager.isSpaceEnoughForDownload(getActivity(), true, localMapIndexItem);
						if (!spaceEnoughForBoth) {
							baseMapIndexItem = null;
						}
						if (spaceEnoughForLocal) {
							showMapDownloadFragment(getActivity());
						}
					}
				});
				view.findViewById(R.id.map_download_card).setVisibility(View.VISIBLE);
				final AppCompatButton searchCountryButton = (AppCompatButton) view.findViewById(R.id.search_country_button);
				searchCountryButton.setVisibility(View.VISIBLE);
				searchCountryButton.setOnClickListener(new View.OnClickListener() {
					@Override
					public void onClick(View v) {
						searchCountryMap();
					}
				});
				break;
			case MAP_DOWNLOAD:
				if (localMapIndexItem != null) {
					indexItems.add(localMapIndexItem);
				}
				if (baseMapIndexItem != null) {
					indexItems.add(baseMapIndexItem);
				}

				if (indexItems.size() > 0) {
					final IndexItem item = indexItems.get(0);
					String mapName = item.getVisibleName(getContext(), getMyApplication().getRegions(), false);
					TextView mapNameTextView = (TextView) view.findViewById(R.id.map_downloading_title);
					mapNameTextView.setText(mapName);
					final TextView mapDescriptionTextView = (TextView) view.findViewById(R.id.map_downloading_desc);
					final View progressPadding = view.findViewById(R.id.map_download_padding);
					final View progressLayout = view.findViewById(R.id.map_download_progress_layout);
					mapDescriptionTextView.setText(item.getSizeDescription(getContext()));
					final ImageButton redownloadButton = (ImageButton) view.findViewById(R.id.map_redownload_button);
					redownloadButton.setOnClickListener(new View.OnClickListener() {
						@Override
						public void onClick(View v) {
							if (!downloadThread.isDownloading(item) && !item.isDownloaded() && firstMapDownloadCancelled) {
								progressPadding.setVisibility(View.GONE);
								progressLayout.setVisibility(View.VISIBLE);
								redownloadButton.setVisibility(View.GONE);
								validationManager.startDownload(getActivity(), item);
								firstMapDownloadCancelled = false;
							}
						}
					});
					view.findViewById(R.id.map_download_progress_button).setOnClickListener(new View.OnClickListener() {
						@Override
						public void onClick(View v) {
							downloadThread.cancelDownload(item);
							firstMapDownloadCancelled = true;
							mapDescriptionTextView.setText(item.getSizeDescription(getContext()));
							progressPadding.setVisibility(View.VISIBLE);
							progressLayout.setVisibility(View.GONE);
							redownloadButton.setVisibility(View.VISIBLE);
						}
					});
					if (item.isDownloaded() || firstMapDownloadCancelled) {
						progressPadding.setVisibility(View.VISIBLE);
						progressLayout.setVisibility(View.GONE);
						redownloadButton.setVisibility(firstMapDownloadCancelled ? View.VISIBLE : View.GONE);
					}
					view.findViewById(R.id.map_downloading_layout).setVisibility(View.VISIBLE);
				} else {
					view.findViewById(R.id.map_downloading_layout).setVisibility(View.GONE);
					view.findViewById(R.id.map_downloading_divider).setVisibility(View.GONE);
				}
				if (indexItems.size() > 1) {
					final IndexItem item = indexItems.get(1);
					String mapName = item.getVisibleName(getContext(), getMyApplication().getRegions(), false);
					TextView mapNameTextView = (TextView) view.findViewById(R.id.map2_downloading_title);
					mapNameTextView.setText(mapName);
					final TextView mapDescriptionTextView = (TextView) view.findViewById(R.id.map2_downloading_desc);
					final View progressPadding = view.findViewById(R.id.map2_download_padding);
					final View progressLayout = view.findViewById(R.id.map2_download_progress_layout);
					mapDescriptionTextView.setText(item.getSizeDescription(getContext()));
					final ImageButton redownloadButton = (ImageButton) view.findViewById(R.id.map2_redownload_button);
					redownloadButton.setOnClickListener(new View.OnClickListener() {
						@Override
						public void onClick(View v) {
							if (!downloadThread.isDownloading(item) && !item.isDownloaded() && secondMapDownloadCancelled) {
								progressPadding.setVisibility(View.GONE);
								progressLayout.setVisibility(View.VISIBLE);
								redownloadButton.setVisibility(View.GONE);
								validationManager.startDownload(getActivity(), item);
								secondMapDownloadCancelled = false;
							}
						}
					});
					view.findViewById(R.id.map2_download_progress_button).setOnClickListener(new View.OnClickListener() {
						@Override
						public void onClick(View v) {
							downloadThread.cancelDownload(item);
							secondMapDownloadCancelled = true;
							mapDescriptionTextView.setText(item.getSizeDescription(getContext()));
							progressPadding.setVisibility(View.VISIBLE);
							progressLayout.setVisibility(View.GONE);
							redownloadButton.setVisibility(View.VISIBLE);
						}
					});
					if (item.isDownloaded() || secondMapDownloadCancelled) {
						progressPadding.setVisibility(View.VISIBLE);
						progressLayout.setVisibility(View.GONE);
						redownloadButton.setVisibility(secondMapDownloadCancelled ? View.VISIBLE : View.GONE);
					}
					view.findViewById(R.id.map2_downloading_layout).setVisibility(View.VISIBLE);
				} else {
					view.findViewById(R.id.map_downloading_divider).setVisibility(View.GONE);
					view.findViewById(R.id.map2_downloading_layout).setVisibility(View.GONE);
				}

				view.findViewById(R.id.map_downloading_action_button).setOnClickListener(new View.OnClickListener() {
					@Override
					public void onClick(View v) {
						MapActivity mapActivity = (MapActivity) getActivity();
						mapActivity.setMapLocation(location.getLatitude(), location.getLongitude());
						mapActivity.getMapView().setIntZoom(13);
						closeWizard();
					}
				});
				view.findViewById(R.id.map_downloading_card).setVisibility(View.VISIBLE);
				break;
		}

		updateStorageView();

		return view;
	}

	@Override
	public void onStart() {
		super.onStart();

		final OsmandApplication app = getMyApplication();

		switch (wizardType) {
			case SEARCH_LOCATION:
			if (searchLocationByIp) {
				final Map<String, String> pms = new LinkedHashMap<>();
				pms.put("version", Version.getFullVersion(app));
				try {
					pms.put("aid", Secure.getString(app.getContentResolver(), Secure.ANDROID_ID));
				} catch (Exception e) {
					e.printStackTrace();
				}
				new AsyncTask<Void, Void, String>() {

					@Override
					protected String doInBackground(Void... params) {
						try {
							return AndroidNetworkUtils.sendRequest(app, "http://osmand.net/api/geo-ip", pms,
									"Requesting location by IP...", false, false);

						} catch (Exception e) {
							logError("Requesting location by IP error: ", e);
							return null;
						}
					}

					@Override
					protected void onPostExecute(String response) {
						if (response != null) {
							try {
								JSONObject obj = new JSONObject(response);
								double latitude = obj.getDouble("latitude");
								double longitude = obj.getDouble("longitude");
								if (latitude == 0 && longitude == 0) {
									showNoLocationFragment(getActivity());
								} else {
									location = new Location("geo-ip");
									location.setLatitude(latitude);
									location.setLongitude(longitude);
									showSearchMapFragment(getActivity());
								}
							} catch (Exception e) {
								logError("JSON parsing error: ", e);
								showNoLocationFragment(getActivity());
							}
						} else {
							showNoLocationFragment(getActivity());
						}
					}
				}.execute();

			} else {
					FragmentActivity activity = getActivity();
					if (!OsmAndLocationProvider.isLocationPermissionAvailable(activity)) {
						ActivityCompat.requestPermissions(activity,
								new String[]{Manifest.permission.ACCESS_FINE_LOCATION},
								FIRST_USAGE_LOCATION_PERMISSION);
					} else {
						app.getLocationProvider().addLocationListener(this);
						locationSearchTimer = new Timer();
						locationSearchTimer.schedule(new TimerTask() {
							@Override
							public void run() {
								FragmentActivity a = getActivity();
								if (a != null) {
									showNoLocationFragment(a);
								}
							}
						}, 1000 * 10);
					}
				}
				break;
			case NO_INTERNET:
				break;
			case NO_LOCATION:
				break;
			case SEARCH_MAP:
				if (app.isApplicationInitializing()) {
					app.getAppInitializer().addListener(this);
				} else {
					if (!downloadThread.getIndexes().isDownloadedFromInternet) {
						waitForIndexes = true;
						downloadThread.runReloadIndexFilesSilent();
					} else {
						searchMap();
					}
				}
				break;
			case MAP_FOUND:
				break;
			case MAP_DOWNLOAD:
				if (!startDownload(0)) {
					startDownload(1);
				}
				if (localDownloadRegion != null) {
					downloadThread.initSettingsFirstMap(localDownloadRegion);
				}
				break;
		}
	}

	@Override
	public void onStop() {
		super.onStop();
		OsmandApplication app = getMyApplication();
		cancelLocationSearchTimer();
		app.getLocationProvider().removeLocationListener(this);
		app.getAppInitializer().removeListener(this);
	}

	@Override
	public void onResume() {
		super.onResume();
		((MapActivity)getActivity()).disableDrawer();
	}

	@Override
	public void onPause() {
		super.onPause();
		((MapActivity)getActivity()).enableDrawer();
	}

	@Override
	public void updateLocation(final Location loc) {
		final OsmandApplication app = getMyApplication();
		if (loc != null) {
			app.runInUIThread(new Runnable() {
				@Override
				public void run() {
					cancelLocationSearchTimer();
					app.getLocationProvider().removeLocationListener(FirstUsageWizardFragment.this);
					if (location == null) {
						location = new Location(loc);
						showSearchMapFragment(getActivity());
					}
				}
			});
		}
	}

	@Override
	public void onProgress(AppInitializer init, AppInitializer.InitEvents event) {
	}

	@Override
	public void onFinish(AppInitializer init) {
		if (!downloadThread.getIndexes().isDownloadedFromInternet) {
			waitForIndexes = true;
			downloadThread.runReloadIndexFilesSilent();
		} else {
			searchMap();
		}
	}

	@Override
	public void newDownloadIndexes() {
		if (waitForIndexes && wizardType == WizardType.SEARCH_MAP) {
			waitForIndexes = false;
			searchMap();
		}
	}

	@Override
	public void downloadInProgress() {
		IndexItem indexItem = downloadThread.getCurrentDownloadingItem();
		if (indexItem != null && !indexItem.isDownloaded()) {
			int progress = downloadThread.getCurrentDownloadingItemProgress();
			double mb = indexItem.getArchiveSizeMB();
			String v;
			if (progress != -1) {
				v = getString(R.string.value_downloaded_of_max, mb * progress / 100, mb);
			} else {
				v = getString(R.string.file_size_in_mb, mb);
			}

			int index = indexItems.indexOf(indexItem);
			if (index == 0) {
				if (!firstMapDownloadCancelled) {
					final TextView mapDescriptionTextView = (TextView) view.findViewById(R.id.map_downloading_desc);
					ProgressBar progressBar = (ProgressBar) view.findViewById(R.id.map_download_progress_bar);
					mapDescriptionTextView.setText(v);
					progressBar.setProgress(progress < 0 ? 0 : progress);
				}
			} else if (index == 1) {
				if (!secondMapDownloadCancelled) {
					final TextView mapDescriptionTextView = (TextView) view.findViewById(R.id.map2_downloading_desc);
					ProgressBar progressBar = (ProgressBar) view.findViewById(R.id.map2_download_progress_bar);
					mapDescriptionTextView.setText(v);
					progressBar.setProgress(progress < 0 ? 0 : progress);
				}
			}
		}
		updateDownloadedItems();
	}

	@Override
	public void downloadHasFinished() {
		updateDownloadedItems();
		startDownload(1);
	}

	private boolean startDownload(int itemIndex) {
		boolean downloadStarted = false;
		if (itemIndex == 0 && indexItems.size() > 0) {
			IndexItem indexItem = indexItems.get(0);
			if (!downloadThread.isDownloading(indexItem)
					&& !indexItem.isDownloaded()
					&& !firstMapDownloadCancelled) {
				validationManager.startDownload(getActivity(), indexItem);
				downloadStarted = true;
			}
		} else if (itemIndex == 1 && indexItems.size() > 1) {
			IndexItem indexItem = indexItems.get(1);
			if (!downloadThread.isDownloading(indexItem)
					&& !indexItem.isDownloaded()
					&& !secondMapDownloadCancelled) {
				validationManager.startDownload(getActivity(), indexItem);
				downloadStarted = true;
			}
		}
		return downloadStarted;
	}

	private void updateDownloadedItems() {
		int i = 0;
		for (IndexItem indexItem : indexItems) {
			if (indexItem.isDownloaded()) {
				final View progressLayout = view.findViewById(R.id.map_download_progress_layout);
				final View progressLayout2 = view.findViewById(R.id.map2_download_progress_layout);
				if (i == 0 && progressLayout.getVisibility() == View.VISIBLE) {
					final TextView mapDescriptionTextView = (TextView) view.findViewById(R.id.map_downloading_desc);
					mapDescriptionTextView.setText(indexItem.getSizeDescription(getContext()));
					view.findViewById(R.id.map_download_padding).setVisibility(View.VISIBLE);
					progressLayout.setVisibility(View.GONE);
				} else if (i == 1 && progressLayout2.getVisibility() == View.VISIBLE) {
					final TextView mapDescriptionTextView = (TextView) view.findViewById(R.id.map2_downloading_desc);
					mapDescriptionTextView.setText(indexItem.getSizeDescription(getContext()));
					view.findViewById(R.id.map2_download_padding).setVisibility(View.VISIBLE);
					progressLayout2.setVisibility(View.GONE);
				}
			}
			i++;
		}
	}

	private void searchCountryMap() {
		closeWizard();
		final Intent intent = new Intent(getActivity(), DownloadActivity.class);
		intent.putExtra(DownloadActivity.TAB_TO_OPEN, DownloadActivity.DOWNLOAD_TAB);
		getActivity().startActivity(intent);
	}

	private void searchMap() {
		if (location != null) {
			int point31x = MapUtils.get31TileNumberX(location.getLongitude());
			int point31y = MapUtils.get31TileNumberY(location.getLatitude());

			ResourceManager rm = getMyApplication().getResourceManager();
			OsmandRegions osmandRegions = rm.getOsmandRegions();

			List<BinaryMapDataObject> mapDataObjects = null;
			try {
				mapDataObjects = osmandRegions.queryBbox(point31x, point31x, point31y, point31y);
			} catch (IOException e) {
				e.printStackTrace();
			}

			String selectedFullName = "";
			if (mapDataObjects != null) {
				Iterator<BinaryMapDataObject> it = mapDataObjects.iterator();
				while (it.hasNext()) {
					BinaryMapDataObject o = it.next();
					if (!osmandRegions.contain(o, point31x, point31y)) {
						it.remove();
					}
				}
				for (BinaryMapDataObject o : mapDataObjects) {
					String fullName = osmandRegions.getFullName(o);
					if (fullName.length() > selectedFullName.length()) {
						selectedFullName = fullName;
					}
				}
			}

			if (!Algorithms.isEmpty(selectedFullName)) {
				WorldRegion downloadRegion = osmandRegions.getRegionData(selectedFullName);
				if (downloadRegion != null && downloadRegion.isRegionMapDownload()) {
					localDownloadRegion = downloadRegion;
					List<IndexItem> indexItems = new LinkedList<>(downloadThread.getIndexes().getIndexItems(downloadRegion));
					for (IndexItem item : indexItems) {
						if (item.getType() == DownloadActivityType.NORMAL_FILE) {
							localMapIndexItem = item;
							break;
						}
					}
				}
			}
			baseMapIndexItem = downloadThread.getIndexes().getWorldBaseMapItem();

			if (localMapIndexItem != null || baseMapIndexItem != null) {
				showMapFoundFragment(getActivity());
			} else {
				closeWizard();
			}

		} else {
			showNoLocationFragment(getActivity());
		}
	}

	private void cancelLocationSearchTimer() {
		if (locationSearchTimer != null) {
			locationSearchTimer.cancel();
			locationSearchTimer = null;
		}
	}

	public static void startWizard(FragmentActivity activity) {
		OsmandApplication app = (OsmandApplication) activity.getApplication();
		if (!app.getSettings().isInternetConnectionAvailable()) {
			showNoInternetFragment(activity);
		} else if (location == null) {
			findLocation(activity, true);
		} else {
			showSearchMapFragment(activity);
		}
	}

	public void closeWizard() {
		getActivity().getSupportFragmentManager().beginTransaction()
				.remove(FirstUsageWizardFragment.this).commit();
		location = null;
		localDownloadRegion = null;
		localMapIndexItem = null;
		baseMapIndexItem = null;
		wizardClosed = true;
	}

	public void processLocationPermission(boolean granted) {
		if (granted) {
			findLocation(getActivity(), false);
		} else {
			showNoLocationFragment(getActivity());
		}
	}

	public void processStoragePermission(boolean granted) {
		if (granted) {
			DataStoragePlaceDialogFragment.showInstance(getActivity().getSupportFragmentManager(), false);
		}
	}

	private static void findLocation(FragmentActivity activity, boolean searchLocationByIp) {
		OsmandApplication app = (OsmandApplication) activity.getApplication();
		if (searchLocationByIp) {
			showSearchLocationFragment(activity, true);
		} else if (OsmAndLocationProvider.isLocationPermissionAvailable(activity)) {
			Location loc = app.getLocationProvider().getLastKnownLocation();
			if (loc == null) {
				showSearchLocationFragment(activity, false);
			} else {
				location = new Location(loc);
				showSearchMapFragment(activity);
			}
		} else {
			showSearchLocationFragment(activity, false);
		}
	}

	public void updateStorageView() {
		updateStorageView(view.findViewById(R.id.storage_layout));
	}

	private void updateStorageView(View storageView) {
		if (storageView != null) {
			TextView title = (TextView) storageView.findViewById(R.id.storage_title);
			OsmandSettings settings = getMyApplication().getSettings();
			int type;
			if (settings.getExternalStorageDirectoryTypeV19() >= 0) {
				type = settings.getExternalStorageDirectoryTypeV19();
			} else {
				ValueHolder<Integer> vh = new ValueHolder<>();
				settings.getExternalStorageDirectory(vh);
				if (vh.value != null && vh.value >= 0) {
					type = vh.value;
				} else {
					type = 0;
				}
			}
			title.setText(getString(R.string.storage_place_description, getStorageName(type)));

			TextView freeSpace = (TextView) storageView.findViewById(R.id.storage_free_space);
			TextView freeSpaceValue = (TextView) storageView.findViewById(R.id.storage_free_space_value);
			String freeSpaceStr = getString(R.string.storage_free_space) + ": ";
			freeSpace.setText(freeSpaceStr);
			freeSpaceValue.setText(getFreeSpace(settings.getExternalStorageDirectory()));

			AppCompatButton changeStorageButton = (AppCompatButton) storageView.findViewById(R.id.storage_change_button);
			if (wizardType == WizardType.MAP_DOWNLOAD) {
				changeStorageButton.setEnabled(false);
			} else {
				changeStorageButton.setOnClickListener(new View.OnClickListener() {
					@Override
					public void onClick(View v) {
						if (!DownloadActivity.hasPermissionToWriteExternalStorage(getContext())) {
							ActivityCompat.requestPermissions(getActivity(),
									new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE},
									FIRST_USAGE_REQUEST_WRITE_EXTERNAL_STORAGE_PERMISSION);

						} else {
							DataStoragePlaceDialogFragment.showInstance(getActivity().getSupportFragmentManager(), false);
						}
					}
				});
			}
		}
	}

	private String getStorageName(int type) {
		if (type == OsmandSettings.EXTERNAL_STORAGE_TYPE_INTERNAL_FILE) {
			return getString(R.string.storage_directory_internal_app);
		} else if (type == OsmandSettings.EXTERNAL_STORAGE_TYPE_DEFAULT) {
			return getString(R.string.storage_directory_shared);
		} else if (type == OsmandSettings.EXTERNAL_STORAGE_TYPE_EXTERNAL_FILE) {
			return getString(R.string.storage_directory_external);
		} else if (type == OsmandSettings.EXTERNAL_STORAGE_TYPE_OBB) {
			return getString(R.string.storage_directory_multiuser);
		} else if (type == OsmandSettings.EXTERNAL_STORAGE_TYPE_SPECIFIED) {
			return getString(R.string.storage_directory_manual);
		} else {
			return getString(R.string.storage_directory_manual);
		}
	}

	private String getFreeSpace(File dir) {
		if (dir.canRead()) {
			StatFs fs = new StatFs(dir.getAbsolutePath());
			return formatGb.format(new Object[]{(float) (fs.getAvailableBlocks()) * fs.getBlockSize() / (1 << 30)});
		}
		return "";
	}

	public static void showSearchLocationFragment(FragmentActivity activity, boolean searchByIp) {
		Fragment fragment = new FirstUsageWizardFragment();
		Bundle args = new Bundle();
		args.putString(WIZARD_TYPE_KEY, WizardType.SEARCH_LOCATION.name());
		args.putBoolean(SEARCH_LOCATION_BY_IP_KEY, searchByIp);
		fragment.setArguments(args);
		showFragment(activity, fragment);
	}

	public static void showSearchMapFragment(FragmentActivity activity) {
		Fragment fragment = new FirstUsageWizardFragment();
		Bundle args = new Bundle();
		args.putString(WIZARD_TYPE_KEY, WizardType.SEARCH_MAP.name());
		fragment.setArguments(args);
		showFragment(activity, fragment);
	}

	public static void showMapFoundFragment(FragmentActivity activity) {
		Fragment fragment = new FirstUsageWizardFragment();
		Bundle args = new Bundle();
		args.putString(WIZARD_TYPE_KEY, WizardType.MAP_FOUND.name());
		fragment.setArguments(args);
		showFragment(activity, fragment);
	}

	public static void showMapDownloadFragment(FragmentActivity activity) {
		Fragment fragment = new FirstUsageWizardFragment();
		Bundle args = new Bundle();
		args.putString(WIZARD_TYPE_KEY, WizardType.MAP_DOWNLOAD.name());
		fragment.setArguments(args);
		showFragment(activity, fragment);
	}

	public static void showNoInternetFragment(FragmentActivity activity) {
		Fragment fragment = new FirstUsageWizardFragment();
		Bundle args = new Bundle();
		args.putString(WIZARD_TYPE_KEY, WizardType.NO_INTERNET.name());
		fragment.setArguments(args);
		showFragment(activity, fragment);
	}

	public static void showNoLocationFragment(FragmentActivity activity) {
		Fragment fragment = new FirstUsageWizardFragment();
		Bundle args = new Bundle();
		args.putString(WIZARD_TYPE_KEY, WizardType.NO_LOCATION.name());
		fragment.setArguments(args);
		showFragment(activity, fragment);
	}

	private static void showFragment(FragmentActivity activity, Fragment fragment) {
		if (!wizardClosed) {
			activity.getSupportFragmentManager()
					.beginTransaction()
					.replace(R.id.fragmentContainer, fragment, FirstUsageWizardFragment.TAG)
					.commitAllowingStateLoss();
		}
	}

	private OsmandApplication getMyApplication() {
		return (OsmandApplication) getActivity().getApplication();
	}

	private static void logError(String msg, Throwable e) {
		Log.e(TAG, "Error: " + msg, e);
	}
}