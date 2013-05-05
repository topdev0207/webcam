package com.github.sarxos.webcam.ds.ipcam;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.github.sarxos.webcam.WebcamDevice;
import com.github.sarxos.webcam.WebcamException;


/**
 * Class used to register IP camera devices.
 * 
 * @author Bartosz Firyn (SarXos)
 */
public class IpCamDeviceRegistry {

	/**
	 * Contains IP cameras.
	 */
	private static final List<IpCamDevice> DEVICES = new ArrayList<IpCamDevice>();

	/**
	 * Register IP camera.
	 * 
	 * @param ipcam the IP camera to be register
	 */
	public static IpCamDevice register(IpCamDevice ipcam) {
		for (WebcamDevice d : DEVICES) {
			String name = ipcam.getName();
			if (d.getName().equals(name)) {
				throw new WebcamException(String.format("Name '%s' is already in use", name));
			}
		}
		DEVICES.add(ipcam);
		return ipcam;
	}

	public static IpCamDevice register(String name, String url, IpCamMode mode) throws MalformedURLException {
		return register(new IpCamDevice(name, url, mode));
	}

	public static IpCamDevice register(String name, URL url, IpCamMode mode) {
		return register(new IpCamDevice(name, url, mode));
	}

	public static IpCamDevice register(String name, String url, IpCamMode mode, IpCamAuth auth) throws MalformedURLException {
		return register(new IpCamDevice(name, url, mode, auth));
	}

	public static IpCamDevice register(String name, URL url, IpCamMode mode, IpCamAuth auth) {
		return register(new IpCamDevice(name, url, mode, auth));
	}

	public static boolean isRegistered(IpCamDevice ipcam) {
		for (IpCamDevice d : DEVICES) {
			if (d.getName().equals(ipcam.getName())) {
				return true;
			}
		}
		return false;
	}

	public static boolean isRegistered(String name) {
		for (IpCamDevice d : DEVICES) {
			if (d.getName().equals(name)) {
				return true;
			}
		}
		return false;
	}

	public static boolean isRegistered(URL url) {
		for (IpCamDevice d : DEVICES) {
			if (d.getURL().equals(url)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Unregister IP camera.
	 * 
	 * @param ipcam the IP camera to be unregister
	 */
	public static void unregister(IpCamDevice ipcam) {
		DEVICES.remove(ipcam);
	}

	/**
	 * Get all registered IP cameras.
	 * 
	 * @return Collection of registered IP cameras
	 */
	public static List<IpCamDevice> getIpCameras() {
		return Collections.unmodifiableList(DEVICES);
	}
}
