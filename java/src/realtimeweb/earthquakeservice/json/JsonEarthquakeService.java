package realtimeweb.earthquakeservice.json;

import realtimeweb.earthquakeservice.domain.History;
import realtimeweb.earthquakeservice.domain.Threshold;
import realtimeweb.earthquakeservice.exceptions.ConnectEarthquakeException;
import realtimeweb.earthquakeservice.exceptions.EarthquakeException;
import realtimeweb.earthquakeservice.exceptions.ParseEarthquakeException;
import realtimeweb.earthquakeservice.main.AbstractEarthquakeService;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;

import com.google.gson.Gson;

import realtimeweb.earthquakeservice.util.Util;

/**
 * Used to get data as a raw string.
 */
public class JsonEarthquakeService implements AbstractEarthquakeService {
	private static JsonEarthquakeService instance;
	protected boolean local;
	private ClientStore clientStore;
	private HashMap<History, HashMap<Threshold, Integer>> clock;

	/**
	 * Initializes the clock to have all possible inputs be 0. This way we know
	 * how many times we've called each possible input pair. We're definitely
	 * abusing the small Domain here!
	 */
	private void initializeClock() {
		this.clock = new HashMap<History, HashMap<Threshold, Integer>>();
		for (History h : History.values()) {
			this.clock.put(h, new HashMap<Threshold, Integer>());
			for (Threshold t : Threshold.values()) {
				this.clock.get(h).put(t, 0);
			}
		}
	}
	
	/**
	 * Advance the clock for the possible input by one time unit.
	 * @param h
	 * @param t
	 */
	private void advanceClock(History h, Threshold t) {
		int currentTime = 1 + this.clock.get(h).get(t);
		this.clock.get(h).put(t, currentTime);
	}
	
	/**
	 * Return the clock for that possible input, e.g. how many times it's been called.
	 * @param h
	 * @param t
	 * @return
	 */
	private int getClock(History h, Threshold t) {
		return this.clock.get(h).get(t);
	}

	/**
	 * **For internal use only!** Protected Constructor guards against
	 * instantiation.
	 * 
	 * @return
	 */
	protected JsonEarthquakeService() {
		connect();
		this.initializeClock();
		try {
			this.clientStore = new ClientStore();
		} catch (IOException e) {
			System.err
					.println("Couldn't find internal cache! Your Jar might be corrupt.");
			e.printStackTrace();
		}
	}

	protected JsonEarthquakeService(InputStream localData) throws IOException {
		disconnect();
		this.initializeClock();
		this.clientStore = new ClientStore(localData);
	}

	/**
	 * Retrieves the singleton instance.
	 * 
	 * @return JsonEarthquakeService
	 */
	public static JsonEarthquakeService getInstance() {
		if (instance == null) {
			synchronized (JsonEarthquakeService.class) {
				if (instance == null) {
					instance = new JsonEarthquakeService();
				}
			}

		}
		return instance;
	}

	/**
	 * Establishes a connection to the online service. Requires an internet
	 * connection.
	 */
	@Override
	public void connect() {
		this.local = false;
	}

	/**
	 * Establishes that Business Search data should be retrieved locally. This
	 * does not require an internet connection.<br>
	 * <br>
	 * If data is being retrieved locally, you must be sure that your parameters
	 * match locally stored data. Otherwise, you will get nothing in return.
	 */
	@Override
	public void disconnect() {
		this.local = true;
	}

	/**
	 * **For internal use only!** The ClientStore is the internal cache where
	 * offline data is stored.
	 * 
	 * @return ClientStore
	 */
	public ClientStore getClientStore() {
		return this.clientStore;
	}

	/**
	 * Retrieves information about earthquakes around the world, and prints both the input URL and the output data.
	 * 
	 * @param threshold
	 *            What kind of earthquakes to report. Note that this is a
	 *            minimum - earthquakes at or ABOVE this level will be reported!
	 * @param time
	 *            The historical time range of earthquakes to report.
	 * @param recording
	 *            Whether or not the inputs and outputs should be printed to stdin.
	 * @return String
	 */
	public String getEarthquakes(Threshold threshold, History time, boolean recording)
			throws EarthquakeException {
		String url = String
				.format("http://earthquake.usgs.gov/earthquakes/feed/v1.0/summary/%s_%s.geojson",
						String.valueOf(threshold), String.valueOf(time));
		HashMap<String, String> parameters = new HashMap<String, String>();
		// If we're local, hit the client cache
		String hash = "";
		if (this.local || recording) {
			// But first, advance our clock and use it to index the inputs.
			parameters.put("time", Integer.toString(this.getClock(time, threshold)));
			this.advanceClock(time, threshold);
			hash = Util.hashRequest(url, parameters);
			if (this.local) {
				return clientStore.getData(hash);
			}
		}
		String jsonResponse = "";
		try {
			jsonResponse = Util.get(url, parameters);
			if (jsonResponse.startsWith("<")) {
				throw new ParseEarthquakeException(jsonResponse);
			}
			if (recording) {
				return hash + ":" + jsonResponse;
			} else {
				return jsonResponse;
			}
		} catch (IOException e) {
			throw new ConnectEarthquakeException(e.toString());
		} catch (Exception e) {
			throw new EarthquakeException(e.toString());
		}
	}
	
	/**
	 * Retrieves information about earthquakes around the world.
	 * 
	 * @param threshold
	 *            What kind of earthquakes to report. Note that this is a
	 *            minimum - earthquakes at or ABOVE this level will be reported!
	 * @param time
	 *            The historical time range of earthquakes to report.
	 * @return String
	 */
	public String getEarthquakes(Threshold threshold, History time) throws EarthquakeException {
		return getEarthquakes(threshold, time, false);
	}

	/**
	 * Retrieves information about earthquakes around the world.
	 * 
	 * @param threshold
	 *            What kind of earthquakes to report. Note that this is a
	 *            minimum - earthquakes at or ABOVE this level will be reported!
	 * @param time
	 *            The historical time range of earthquakes to report.
	 * @param callback
	 *            The listener that will be given the data (or error).
	 */
	public void getEarthquakes(final Threshold threshold, final History time,
			final JsonGetEarthquakesListener callback) {

		Thread thread = new Thread() {
			@Override
			public void run() {
				try {
					callback.getEarthquakesCompleted(JsonEarthquakeService
							.getInstance().getEarthquakes(threshold, time));
				} catch (Exception e) {
					callback.getEarthquakesFailed(e);
				}
			}
		};
		thread.start();

	}

	/**
	 * Retrieves the singleton instance, and if it doesn't exist, sets it to use the given local data stream.
	 * @param localFile A data stream that will be used instead of live data.
	 * @return
	 * @throws IOException 
	 */
	public static JsonEarthquakeService getInstance(InputStream localFile) throws IOException {
		if (instance == null) {
			synchronized (JsonEarthquakeService.class) {
				if (instance == null) {
					instance = new JsonEarthquakeService(localFile);
				}
			}

		}
		return instance;
	}

}
