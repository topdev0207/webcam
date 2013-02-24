package com.github.sarxos.webcam;

import java.awt.Dimension;
import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.github.sarxos.webcam.WebcamDevice.BufferAccess;
import com.github.sarxos.webcam.ds.buildin.WebcamDefaultDevice;
import com.github.sarxos.webcam.ds.buildin.WebcamDefaultDriver;
import com.github.sarxos.webcam.ds.cgt.WebcamCloseTask;
import com.github.sarxos.webcam.ds.cgt.WebcamDisposeTask;
import com.github.sarxos.webcam.ds.cgt.WebcamOpenTask;
import com.github.sarxos.webcam.ds.cgt.WebcamReadBufferTask;
import com.github.sarxos.webcam.ds.cgt.WebcamReadImageTask;
import com.github.sarxos.webcam.util.ImageUtils;


/**
 * Webcam class. It wraps webcam device obtained from webcam driver.
 * 
 * @author Bartosz Firyn (bfiryn)
 */
public class Webcam {

	/**
	 * Logger instance.
	 */
	private static final Logger LOG = LoggerFactory.getLogger(Webcam.class);

	/**
	 * List of default drivers to search in classpath.
	 */
	// @formatter:off
	private static final String[] DRIVERS_DEFAULT = new String[] {
		"com.github.sarxos.webcam.ds.openimaj.OpenImajDriver",
		"com.github.sarxos.webcam.ds.civil.LtiCivilDriver",
		"com.github.sarxos.webcam.ds.jmf.JmfDriver",
	};
	// @formatter:on

	private static final List<String> DRIVERS_LIST = new ArrayList<String>(Arrays.asList(DRIVERS_DEFAULT));

	private static final List<Class<?>> DRIVERS_CLASS_LIST = new ArrayList<Class<?>>();

	private static final List<WebcamDiscoveryListener> DISCOVERY_LISTENERS = Collections.synchronizedList(new ArrayList<WebcamDiscoveryListener>());

	/**
	 * Webcam driver (LtiCivil, JMF, FMJ, JQT, OpenCV, VLCj, etc).
	 */
	private static WebcamDriver driver = null;

	/**
	 * Webcam discovery service.
	 */
	private static volatile WebcamDiscoveryService discovery = null;

	/**
	 * Is automated deallocation on TERM signal enabled.
	 */
	private static boolean deallocOnTermSignal = false;

	/**
	 * Is auto-open feature enabled?
	 */
	private static boolean autoOpen = false;

	/**
	 * Webcam listeners.
	 */
	private List<WebcamListener> listeners = Collections.synchronizedList(new ArrayList<WebcamListener>());

	/**
	 * List of custom resolution sizes supported by webcam instance.
	 */
	private List<Dimension> customSizes = new ArrayList<Dimension>();

	/**
	 * Shutdown hook.
	 */
	private WebcamShutdownHook hook = null;

	/**
	 * Underlying webcam device.
	 */
	private WebcamDevice device = null;

	/**
	 * Is webcam open?
	 */
	private AtomicBoolean open = new AtomicBoolean(false);

	/**
	 * Is webcam already disposed?
	 */
	private AtomicBoolean disposed = new AtomicBoolean(false);

	/**
	 * Is non-blocking (asynchronous) access enabled?
	 */
	private AtomicBoolean asynchronous = new AtomicBoolean(false);

	/**
	 * Webcam class.
	 * 
	 * @param device - device to be used as webcam
	 * @throws IllegalArgumentException when device argument is null
	 */
	protected Webcam(WebcamDevice device) {
		if (device == null) {
			throw new IllegalArgumentException("Webcam device cannot be null");
		}
		this.device = device;
	}

	/**
	 * Open the webcam in blocking (synchronous) mode.
	 * 
	 * @see #open(boolean)
	 */
	public void open() {
		open(false);
	}

	/**
	 * Open the webcam in either blocking (synchronous) or non-blocking
	 * (asynchronous) mode.The difference between those two modes lies in the
	 * image acquisition mechanism.<br>
	 * <br>
	 * In blocking mode, when user calls {@link #getImage()} method, device is
	 * being queried for new image buffer and user have to wait for it to be
	 * available.<br>
	 * <br>
	 * In non-blocking mode, there is a special thread running in the background
	 * which constantly fetch new images and cache them internally for further
	 * use. This cached instance is returned every time when user request new
	 * image. Because of that it can be used when timeing is very important,
	 * because all users calls for new image do not have to wait on device
	 * response. By using this mode user should be aware of the fact that in
	 * some cases, when two consecutive calls to get new image are executed more
	 * often than webcam device can serve them, the same image instance will be
	 * returned. User should use {@link #isImageNew()} method to distinguish if
	 * returned image is not the same as the previous one.
	 * 
	 * @param asynchronous true for non-blocking mode, false for blocking
	 */
	public void open(boolean async) {

		if (asynchronous.compareAndSet(false, true)) {
			// TODO: start async thread
		}

		if (open.compareAndSet(false, true)) {

			WebcamOpenTask task = new WebcamOpenTask(driver, device);
			task.open();

			LOG.debug("Webcam is now open {}", getName());

			// install shutdown hook

			Runtime.getRuntime().addShutdownHook(hook = new WebcamShutdownHook(this));

			// notify listeners

			WebcamEvent we = new WebcamEvent(this);
			for (WebcamListener l : getWebcamListeners()) {
				try {
					l.webcamOpen(we);
				} catch (Exception e) {
					LOG.error(String.format("Notify webcam open, exception when calling listener %s", l.getClass()), e);
				}
			}

			return;
		}

		LOG.debug("Webcam is already open {}", getName());
	}

	/**
	 * Close the webcam.
	 */
	public void close() {

		if (open.compareAndSet(true, false)) {

			// close webcam

			WebcamCloseTask task = new WebcamCloseTask(driver, device);
			task.close();

			// remove shutdown hook (it's not more necessary)

			Runtime.getRuntime().removeShutdownHook(hook);

			// notify listeners

			WebcamEvent we = new WebcamEvent(this);
			for (WebcamListener l : getWebcamListeners()) {
				try {
					l.webcamClosed(we);
				} catch (Exception e) {
					LOG.error(String.format("Notify webcam closed, exception when calling %s listener", l.getClass()), e);
				}
			}

			return;
		}

		LOG.debug("Webcam is already closed {}", getName());
	}

	/**
	 * Is webcam open?
	 * 
	 * @return true if open, false otherwise
	 */
	public boolean isOpen() {
		return open.get();
	}

	/**
	 * Get current webcam resolution in pixels.
	 * 
	 * @return Webcam resolution (picture size) in pixels.
	 */
	public Dimension getViewSize() {
		return device.getResolution();
	}

	/**
	 * Return list of supported view sizes. It can differ between vary webcam
	 * data sources.
	 * 
	 * @return
	 */
	public Dimension[] getViewSizes() {
		return device.getResolutions();
	}

	/**
	 * Set custom resolution. If you are using this method you have to make sure
	 * that your webcam device can support this specific resolution.
	 * 
	 * @param sizes the array of custom resolutions to be supported by webcam
	 */
	public void setCustomViewSizes(Dimension[] sizes) {
		if (sizes == null) {
			customSizes.clear();
			return;
		}
		customSizes = Arrays.asList(sizes);
	}

	public Dimension[] getCustomViewSizes() {
		return customSizes.toArray(new Dimension[customSizes.size()]);
	}

	/**
	 * Set new view size. New size has to exactly the same as one of the default
	 * sized or exactly the same as one of the custom ones.
	 * 
	 * @param size the new view size to be set
	 * @see Webcam#setCustomViewSizes(Dimension[])
	 * @see Webcam#getViewSizes()
	 */
	public void setViewSize(Dimension size) {

		if (size == null) {
			throw new IllegalArgumentException("Resolution cannot be null!");
		}

		if (open.get()) {
			throw new IllegalStateException("Cannot change resolution when webcam is open, please close it first");
		}

		// check if new resolution is the same as current one

		Dimension current = getViewSize();
		if (current != null && current.width == size.width && current.height == size.height) {
			return;
		}

		// check if new resolution is valid

		Dimension[] predefined = getViewSizes();
		Dimension[] custom = getCustomViewSizes();

		boolean ok = false;
		for (Dimension d : predefined) {
			if (d.width == size.width && d.height == size.height) {
				ok = true;
				break;
			}
		}
		if (!ok) {
			for (Dimension d : custom) {
				if (d.width == size.width && d.height == size.height) {
					ok = true;
					break;
				}
			}
		}

		if (!ok) {
			StringBuilder sb = new StringBuilder("Incorrect dimension [");
			sb.append(size.width).append("x").append(size.height).append("] ");
			sb.append("possible ones are ");
			for (Dimension d : predefined) {
				sb.append("[").append(d.width).append("x").append(d.height).append("] ");
			}
			for (Dimension d : custom) {
				sb.append("[").append(d.width).append("x").append(d.height).append("] ");
			}
			throw new IllegalArgumentException(sb.toString());
		}

		LOG.debug("Setting new resolution {}x{}", size.width, size.height);

		device.setResolution(size);
	}

	/**
	 * Capture image from webcam and return it. Will return image object or null
	 * if webcam is closed or has been already disposed by JVM.<br>
	 * <br>
	 * <b>IMPORTANT NOTE!!!</b><br>
	 * <br>
	 * There are two possible behaviors of what webcam should do when you try to
	 * get image and webcam is actually closed. Normally it will return null,
	 * but there is a special flag which can be statically set to switch all
	 * webcams to auto open mode. In this mode, webcam will be automatically
	 * open, when you try to get image from closed webcam. Please be aware of
	 * some side effects! In case of multi-threaded applications, there is no
	 * guarantee that one thread will not try to open webcam even if it was
	 * manually closed in different thread.
	 * 
	 * @return Captured image or null if webcam is closed or disposed by JVM
	 */
	public BufferedImage getImage() {

		if (!isReady()) {
			return null;
		}

		return new WebcamReadImageTask(driver, device).getImage();
	}

	/**
	 * Get RAW image ByteBuffer. It will always return buffer with 3 x 1 bytes
	 * per each pixel, where RGB components are on (0, 1, 2) offsets with color
	 * space sRGB.<br>
	 * <br>
	 * 
	 * <b>IMPORTANT!</b><br>
	 * Some drivers can return direct ByteBuffer, so there is no guarantee that
	 * underlying bytes will not be released in next read image operation.
	 * Therefore, to avoid potential bugs you should convert this ByteBuffer to
	 * bytes array before you fetch next image.
	 * 
	 * @return Byte buffer
	 */
	public ByteBuffer getImageBytes() {

		if (!isReady()) {
			return null;
		}

		// some devices can support direct image buffers, and for those call
		// processor task, and for those which does not support direct image
		// buffers, just convert image to RGB byte array

		if (device instanceof BufferAccess) {
			return new WebcamReadBufferTask(driver, device).getBuffer();
		} else {
			return ByteBuffer.wrap(ImageUtils.toRawByteArray(getImage()));
		}
	}

	/**
	 * Is webcam ready to be read.
	 * 
	 * @return True if ready, false otherwise
	 */
	private boolean isReady() {

		if (disposed.get()) {
			LOG.warn("Cannot get image, webcam has been already disposed");
			return false;
		}

		if (!open.get()) {
			if (autoOpen) {
				open();
			} else {
				return false;
			}
		}

		return true;
	}

	public boolean isImageNew() {
		if (!asynchronous.get()) {
			// TODO: for dshow this is not true, need to add special check
			return true;
		} else {
			// TODO: implement
			return false;
		}
	}

	/**
	 * Get list of webcams to use. This method will wait predefined time
	 * interval for webcam devices to be discovered. By default this time is set
	 * to 1 minute.
	 * 
	 * @return List of webcams existing in the ssytem
	 * @throws WebcamException when something is wrong
	 * @see Webcam#getWebcams(long, TimeUnit)
	 */
	public static List<Webcam> getWebcams() throws WebcamException {

		// timeout exception below will never be caught since user would have to
		// wait around three hundreds billion years for it to occur

		try {
			return getWebcams(Long.MAX_VALUE);
		} catch (TimeoutException e) {
			throw new RuntimeException(e);
		}
	}

	/**
	 * Get list of webcams to use. This method will wait given time interval for
	 * webcam devices to be discovered. Time argument is given in milliseconds.
	 * 
	 * @param timeout the time to wait for webcam devices to be discovered
	 * @return List of webcams existing in the ssytem
	 * @throws WebcamException when something is wrong
	 * @see Webcam#getWebcams(long, TimeUnit)
	 */
	public static List<Webcam> getWebcams(long timeout) throws TimeoutException, WebcamException {
		return getWebcams(timeout, TimeUnit.MILLISECONDS);
	}

	/**
	 * Get list of webcams to use. This method will wait given time interval for
	 * webcam devices to be discovered.
	 * 
	 * @param timeout the devices discovery timeout
	 * @param tunit the time unit
	 * @return List of webcams
	 * @throws TimeoutException when timeout has been exceeded
	 * @throws WebcamException when something is wrong
	 */
	public static synchronized List<Webcam> getWebcams(long timeout, TimeUnit tunit) throws TimeoutException, WebcamException {

		WebcamDiscoveryService discovery = getDiscoveryService();
		List<Webcam> webcams = discovery.getWebcams(timeout, tunit);

		if (!discovery.isRunning()) {
			discovery.start();
		}

		return webcams;
	}

	/**
	 * Will discover and return first webcam available in the system.
	 * 
	 * @return Default webcam (first from the list)
	 * @throws WebcamException if something is really wrong
	 * @see Webcam#getWebcams()
	 */
	public static Webcam getDefault() throws WebcamException {

		try {
			return getDefault(Long.MAX_VALUE);
		} catch (TimeoutException e) {
			// this should never happen since user would have to wait 300000000
			// years for it to occur
			throw new RuntimeException(e);
		}
	}

	/**
	 * Will discover and return first webcam available in the system.
	 * 
	 * @param timeout the webcam discovery timeout (1 minute by default)
	 * @return Default webcam (first from the list)
	 * @throws TimeoutException when discovery timeout has been exceeded
	 * @throws WebcamException if something is really wrong
	 * @see Webcam#getWebcams(long)
	 */
	public static Webcam getDefault(long timeout) throws TimeoutException, WebcamException {
		return getDefault(timeout, TimeUnit.MILLISECONDS);
	}

	/**
	 * Will discover and return first webcam available in the system.
	 * 
	 * @param timeout the webcam discovery timeout (1 minute by default)
	 * @param tunit the time unit
	 * @return Default webcam (first from the list)
	 * @throws TimeoutException when discovery timeout has been exceeded
	 * @throws WebcamException if something is really wrong
	 * @see Webcam#getWebcams(long, TimeUnit)
	 */
	public static Webcam getDefault(long timeout, TimeUnit tunit) throws TimeoutException, WebcamException {

		if (timeout < 0) {
			throw new IllegalArgumentException("Timeout cannot be negative");
		}
		if (tunit == null) {
			throw new IllegalArgumentException("Time unit cannot be null!");
		}

		List<Webcam> webcams = getWebcams(timeout, tunit);

		if (!webcams.isEmpty()) {
			return webcams.get(0);
		}

		LOG.warn("No webcam has been detected!");

		return null;
	}

	/**
	 * Get webcam name (device name). The name of device depends on the value
	 * returned by the underlying data source, so in some cases it can be
	 * human-readable value and sometimes it can be some strange number.
	 * 
	 * @return Name
	 */
	public String getName() {
		return device.getName();
	}

	@Override
	public String toString() {
		return String.format("Webcam %s", getName());
	}

	/**
	 * Add webcam listener.
	 * 
	 * @param l the listener to be added
	 * @throws IllegalArgumentException when argument is null
	 */
	public boolean addWebcamListener(WebcamListener l) {
		if (l == null) {
			throw new IllegalArgumentException("Webcam listener cannot be null!");
		}
		return listeners.add(l);
	}

	/**
	 * @return All webcam listeners
	 */
	public WebcamListener[] getWebcamListeners() {
		return listeners.toArray(new WebcamListener[listeners.size()]);
	}

	/**
	 * Removes webcam listener.
	 * 
	 * @param l the listener to be removed
	 * @return True if listener has been removed, false otherwise
	 */
	public boolean removeWebcamListener(WebcamListener l) {
		return listeners.remove(l);
	}

	/**
	 * Return webcam driver. Perform search if necessary.<br>
	 * <br>
	 * <b>This method is not thread-safe!</b>
	 * 
	 * @return Webcam driver
	 */
	public static synchronized WebcamDriver getDriver() {

		if (driver == null) {
			driver = WebcamDriverUtils.findDriver(DRIVERS_LIST, DRIVERS_CLASS_LIST);
		}

		if (driver == null) {
			LOG.info("Webcam driver has not been found, default one will be used!");
			driver = new WebcamDefaultDriver();
		}

		return driver;
	}

	/**
	 * Set new video driver to be used by webcam.<br>
	 * <br>
	 * <b>This method is not thread-safe!</b>
	 * 
	 * @param driver new webcam driver to be used (e.g. LtiCivil, JFM, FMJ, QTJ)
	 * @throws IllegalArgumentException when argument is null
	 */
	public static synchronized void setDriver(WebcamDriver driver) {

		if (driver == null) {
			throw new IllegalArgumentException("Webcam driver cannot be null!");
		}

		resetDriver();

		Webcam.driver = driver;
	}

	/**
	 * Set new video driver class to be used by webcam. Class given in the
	 * argument shall extend {@link WebcamDriver} interface and should have
	 * public default constructor, so instance can be created by reflection.<br>
	 * <br>
	 * <b>This method is not thread-safe!</b>
	 * 
	 * @param driver new video driver class to use
	 * @throws IllegalArgumentException when argument is null
	 */
	public static synchronized void setDriver(Class<? extends WebcamDriver> driverClass) {

		resetDriver();

		if (driverClass == null) {
			throw new IllegalArgumentException("Webcam driver class cannot be null!");
		}

		try {
			driver = driverClass.newInstance();
		} catch (InstantiationException e) {
			throw new WebcamException(e);
		} catch (IllegalAccessException e) {
			throw new WebcamException(e);
		}
	}

	/**
	 * Reset webcam driver.<br>
	 * <br>
	 * <b>This method is not thread-safe!</b>
	 */
	public static synchronized void resetDriver() {

		DRIVERS_LIST.clear();
		DRIVERS_LIST.addAll(Arrays.asList(DRIVERS_DEFAULT));

		if (discovery != null) {
			discovery.shutdown();
			discovery = null;
		}

		driver = null;
	}

	/**
	 * Register new webcam video driver.
	 * 
	 * @param clazz webcam video driver class
	 * @throws IllegalArgumentException when argument is null
	 */
	public static void registerDriver(Class<? extends WebcamDriver> clazz) {
		if (clazz == null) {
			throw new IllegalArgumentException("Webcam driver class to register cannot be null!");
		}
		DRIVERS_CLASS_LIST.add(clazz);
		registerDriver(clazz.getCanonicalName());
	}

	/**
	 * Register new webcam video driver.
	 * 
	 * @param clazzName webcam video driver class name
	 * @throws IllegalArgumentException when argument is null
	 */
	public static void registerDriver(String clazzName) {
		if (clazzName == null) {
			throw new IllegalArgumentException("Webcam driver class name to register cannot be null!");
		}
		DRIVERS_LIST.add(clazzName);
	}

	/**
	 * Return underlying webcam device. Depending on the driver used to discover
	 * devices, this method can return instances of different class. By default
	 * {@link WebcamDefaultDevice} is returned when no external driver is used.
	 * 
	 * @return Underlying webcam device instance
	 */
	public WebcamDevice getDevice() {
		return device;
	}

	/**
	 * Completely dispose capture device. After this operation webcam cannot be
	 * used any more and full reinstantiation is required.
	 */
	protected void dispose() {

		if (!disposed.compareAndSet(false, true)) {
			return;
		}

		open.set(false);

		LOG.info("Disposing webcam {}", getName());

		WebcamDisposeTask task = new WebcamDisposeTask(driver, device);
		task.dispose();

		WebcamEvent we = new WebcamEvent(this);
		for (WebcamListener l : listeners) {
			try {
				l.webcamClosed(we);
				l.webcamDisposed(we);
			} catch (Exception e) {
				LOG.error(String.format("Notify webcam disposed, exception when calling %s listener", l.getClass()), e);
			}
		}

		// hook can be null because there is a possibility that webcam has never
		// been open and therefore hook was not created
		if (hook != null) {
			try {
				Runtime.getRuntime().removeShutdownHook(hook);
			} catch (IllegalStateException e) {
				LOG.trace("Shutdown in progress, cannot remove hook");
			}
		}

		LOG.debug("Webcam disposed {}", getName());
	}

	/**
	 * <b>CAUTION!!!</b><br>
	 * <br>
	 * This is experimental feature to be used mostly in in development phase.
	 * After you set handle term signal to true, and fetch capture devices,
	 * Webcam Capture API will listen for TERM signal and try to close all
	 * devices after it has been received. <b>This feature can be unstable on
	 * some systems!</b>
	 * 
	 * @param on signal handling will be enabled if true, disabled otherwise
	 */
	public static void setHandleTermSignal(boolean on) {
		if (on) {
			LOG.warn("Automated deallocation on TERM signal is now enabled! Make sure to not use it in production!");
		}
		deallocOnTermSignal = on;
	}

	/**
	 * Is TERM signal handler enabled.
	 * 
	 * @return True if enabled, false otherwise
	 */
	public static boolean isHandleTermSignal() {
		return deallocOnTermSignal;
	}

	/**
	 * Switch all webcams to auto open mode. In this mode, each webcam will be
	 * automatically open whenever user will try to get image from instance
	 * which has not yet been open. Please be aware of some side effects! In
	 * case of multi-threaded applications, there is no guarantee that one
	 * thread will not try to open webcam even if it was manually closed in
	 * different thread.
	 * 
	 * @param on true to enable, false to disable
	 */
	public static void setAutoOpenMode(boolean on) {
		autoOpen = on;
	}

	/**
	 * Is auto open mode enabled. Auto open mode will will automatically open
	 * webcam whenever user will try to get image from instance which has not
	 * yet been open. Please be aware of some side effects! In case of
	 * multi-threaded applications, there is no guarantee that one thread will
	 * not try to open webcam even if it was manually closed in different
	 * thread.
	 * 
	 * @return True if mode is enabled, false otherwise
	 */
	public static boolean isAutoOpenMode() {
		return autoOpen;
	}

	/**
	 * Add new webcam discovery listener.
	 * 
	 * @param l the listener to be added
	 * @return True, if listeners list size has been changed, false otherwise
	 * @throws IllegalArgumentException when argument is null
	 */
	public static boolean addDiscoveryListener(WebcamDiscoveryListener l) {
		if (l == null) {
			throw new IllegalArgumentException("Webcam discovery listener cannot be null!");
		}
		return DISCOVERY_LISTENERS.add(l);
	}

	public static WebcamDiscoveryListener[] getDiscoveryListeners() {
		return DISCOVERY_LISTENERS.toArray(new WebcamDiscoveryListener[DISCOVERY_LISTENERS.size()]);
	}

	/**
	 * Remove discovery listener
	 * 
	 * @param l the listener to be removed
	 * @return True if listeners list contained the specified element
	 */
	public static boolean removeDiscoveryListener(WebcamDiscoveryListener l) {
		return DISCOVERY_LISTENERS.remove(l);
	}

	/**
	 * Return discovery service.
	 * 
	 * @return Discovery service
	 */
	public static synchronized WebcamDiscoveryService getDiscoveryService() {
		if (discovery == null) {
			discovery = new WebcamDiscoveryService(getDriver());
		}
		return discovery;
	}
}
