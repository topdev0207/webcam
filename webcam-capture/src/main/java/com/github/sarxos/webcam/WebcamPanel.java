package com.github.sarxos.webcam;

import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.swing.JPanel;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * Simply implementation of JPanel allowing users to render pictures taken with
 * webcam.
 * 
 * @author Bartosz Firyn (SarXos)
 */
public class WebcamPanel extends JPanel implements WebcamListener {

	/**
	 * Interface of the painter used to draw image in panel.
	 * 
	 * @author Bartosz Firyn (SarXos)
	 */
	public static interface Painter {

		/**
		 * Paints panel without image.
		 * 
		 * @param g2 the graphics 2D object used for drawing
		 */
		void paintPanel(WebcamPanel owner, Graphics2D g2);

		/**
		 * Paints webcam image in panel.
		 * 
		 * @param g2 the graphics 2D object used for drawing
		 */
		void paintImage(WebcamPanel owner, BufferedImage image, Graphics2D g2);
	}

	/**
	 * Default painter used to draw image in panel.
	 * 
	 * @author Bartosz Firyn (SarXos)
	 */
	public class DefaultPainter implements Painter {

		private String name = null;

		@Override
		public void paintPanel(WebcamPanel owner, Graphics2D g2) {

			g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			g2.setBackground(Color.BLACK);
			g2.fillRect(0, 0, getWidth(), getHeight());

			int cx = (getWidth() - 70) / 2;
			int cy = (getHeight() - 40) / 2;

			g2.setStroke(new BasicStroke(2));
			g2.setColor(Color.LIGHT_GRAY);
			g2.fillRoundRect(cx, cy, 70, 40, 10, 10);
			g2.setColor(Color.WHITE);
			g2.fillOval(cx + 5, cy + 5, 30, 30);
			g2.setColor(Color.LIGHT_GRAY);
			g2.fillOval(cx + 10, cy + 10, 20, 20);
			g2.setColor(Color.WHITE);
			g2.fillOval(cx + 12, cy + 12, 16, 16);
			g2.fillRoundRect(cx + 50, cy + 5, 15, 10, 5, 5);
			g2.fillRect(cx + 63, cy + 25, 7, 2);
			g2.fillRect(cx + 63, cy + 28, 7, 2);
			g2.fillRect(cx + 63, cy + 31, 7, 2);

			g2.setColor(Color.DARK_GRAY);
			g2.setStroke(new BasicStroke(3));
			g2.drawLine(0, 0, getWidth(), getHeight());
			g2.drawLine(0, getHeight(), getWidth(), 0);

			String str = starting ? "Initializing" : "No Image";
			FontMetrics metrics = g2.getFontMetrics(getFont());
			int w = metrics.stringWidth(str);
			int h = metrics.getHeight();

			g2.setColor(Color.WHITE);
			g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
			g2.drawString(str, (getWidth() - w) / 2, cy - h);

			if (name == null) {
				name = webcam.getName();
			}

			str = name;
			w = metrics.stringWidth(str);
			h = metrics.getHeight();

			g2.drawString(str, (getWidth() - w) / 2, cy - 2 * h);
		}

		@Override
		public void paintImage(WebcamPanel owner, BufferedImage image, Graphics2D g2) {

			int w = getWidth();
			int h = getHeight();

			if (fillArea && image.getWidth() != w && image.getHeight() != h) {

				BufferedImage resized = new BufferedImage(w, h, BufferedImage.TYPE_3BYTE_BGR);
				Graphics2D gr = resized.createGraphics();
				gr.setComposite(AlphaComposite.Src);
				gr.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
				gr.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
				gr.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
				gr.drawImage(image, 0, 0, w, h, null);
				gr.dispose();
				resized.flush();

				image = resized;
			}

			g2.drawImage(image, 0, 0, null);
		}
	}

	/**
	 * S/N used by Java to serialize beans.
	 */
	private static final long serialVersionUID = 5792962512394656227L;

	/**
	 * Logger.
	 */
	private static final Logger LOG = LoggerFactory.getLogger(WebcamPanel.class);

	/**
	 * Minimum FPS frequency.
	 */
	public static final double MIN_FREQUENCY = 0.016; // 1 frame per minute

	/**
	 * Maximum FPS frequency.
	 */
	private static final double MAX_FREQUENCY = 25; // 25 frames per second

	/**
	 * Repainter reads images from camera and forces panel repainting.
	 * 
	 * @author Bartosz Firyn (SarXos)
	 */
	private class Repainter extends Thread {

		public Repainter() {
			setDaemon(true);
		}

		@Override
		public void run() {

			while (starting) {
				repaint();
				try {
					Thread.sleep(50);
				} catch (InterruptedException e) {
					LOG.error("Nasty interrupted exception");
				}
			}

			if (!webcam.isOpen()) {
				webcam.open();
			}

			while (webcam.isOpen()) {

				BufferedImage tmp = webcam.getImage();

				try {

					if (tmp == null) {

						if (webcam.isOpen()) {
							break;
						}

						LOG.error("Image is null");

					} else {

						image = tmp;

						while (paused) {
							synchronized (this) {
								this.wait(250);
							}
						}
					}

					Thread.sleep((long) (1000 / frequency));

				} catch (InterruptedException e) {
					LOG.error("Nasty interrupted exception");
				}

				repaint();
			}
		}
	}

	private boolean fillArea = false;

	/**
	 * Painting frequency.
	 */
	private double frequency = 5; // FPS

	/**
	 * Webcam object used to fetch images.
	 */
	private Webcam webcam = null;

	/**
	 * Image currently being displayed.
	 */
	private BufferedImage image = null;

	/**
	 * Repainter is used to fetch images from camera and force panel repaint
	 * when image is ready.
	 */
	private Repainter repainter = new Repainter();

	/**
	 * Webcam is currently starting.
	 */
	private volatile boolean starting = false;

	/**
	 * Painting is paused.
	 */
	private volatile boolean paused = false;

	/**
	 * Webcam has been started.
	 */
	private AtomicBoolean started = new AtomicBoolean(false);

	/**
	 * Painter used to draw image in panel.
	 * 
	 * @see #setPainter(Painter)
	 * @see #getPainter()
	 */
	private Painter painter = new DefaultPainter();

	/**
	 * Creates webcam panel and automatically start webcam.
	 * 
	 * @param webcam the webcam to be used to fetch images
	 */
	public WebcamPanel(Webcam webcam) {
		this(webcam, true);
	}

	/**
	 * Creates new webcam panel which display image from camera in you your
	 * Swing application.
	 * 
	 * @param webcam the webcam to be used to fetch images
	 * @param start true if webcam shall be automatically started
	 */
	public WebcamPanel(Webcam webcam, boolean start) {
		this(webcam, null, start);
	}

/**
	 * Creates new webcam panel which display image from camera in you your
	 * Swing application. If panel size argument is null, then image size will
	 * be used. If you would like to fill panel area with image even if its size 
	 * is different, then you can use {@link #setFillArea(boolean)) method to 
	 * configure this.
	 * 
	 * @param webcam the webcam to be used to fetch images
	 * @param size the size of panel
	 * @param start true if webcam shall be automatically started
	 * @see WebcamPanel#setFillArea(boolean)
	 */
	public WebcamPanel(Webcam webcam, Dimension size, boolean start) {

		if (webcam == null) {
			throw new IllegalArgumentException(String.format("Webcam argument in %s constructor cannot be null!", getClass().getSimpleName()));
		}

		this.webcam = webcam;
		this.webcam.addWebcamListener(this);

		repainter.setName(String.format("%s-repainter", webcam.getName()));

		if (size == null) {
			Dimension r = webcam.getViewSize();
			if (r == null) {
				r = webcam.getViewSizes()[0];
			}
			setPreferredSize(r);
		} else {
			setPreferredSize(size);
		}

		if (start) {
			if (!webcam.isOpen()) {
				webcam.open();
			}
			repainter.start();
		}
	}

	/**
	 * Set new painter. Painter is a class which pains image visible when
	 * 
	 * @param painter the painter object to be set
	 */
	public void setPainter(Painter painter) {
		this.painter = painter;
	}

	/**
	 * Get painter used to draw image in webcam panel.
	 * 
	 * @return Painter object
	 */
	public Painter getPainter() {
		return painter;
	}

	@Override
	protected void paintComponent(Graphics g) {
		Graphics2D g2 = (Graphics2D) g;
		if (image == null) {
			painter.paintPanel(this, g2);
		} else {
			painter.paintImage(this, image, g2);
		}
	}

	@Override
	public void webcamOpen(WebcamEvent we) {
		if (repainter == null) {
			repainter = new Repainter();
			repainter.start();
		}
		setPreferredSize(webcam.getViewSize());
	}

	@Override
	public void webcamClosed(WebcamEvent we) {
		if (repainter != null) {
			if (repainter.isAlive()) {
				try {
					repainter.join(1000);
				} catch (InterruptedException e) {
					throw new WebcamException("Thread interrupted", e);
				}
			}
			repainter = null;
		}
	}

	@Override
	public void webcamDisposed(WebcamEvent we) {
		webcamClosed(we);
	}

	/**
	 * Open webcam and start rendering.
	 */
	public void start() {
		if (started.compareAndSet(false, true)) {

			starting = true;

			if (repainter == null) {
				repainter = new Repainter();
			}

			repainter.start();
			webcam.open();
			starting = false;
		}
	}

	/**
	 * Stop rendering and close webcam.
	 */
	public void stop() {
		if (started.compareAndSet(true, false)) {
			image = null;
			webcam.close();
		}
	}

	/**
	 * Pause rendering.
	 */
	public void pause() {
		if (paused) {
			return;
		}
		paused = true;
	}

	/**
	 * Resume rendering.
	 */
	public void resume() {
		if (!paused) {
			return;
		}
		paused = false;
		synchronized (repainter) {
			repainter.notifyAll();
		}
	}

	/**
	 * Get rendering frequency in FPS (equivalent to Hz).
	 * 
	 * @return Rendering frequency
	 */
	public double getFrequency() {
		return frequency;
	}

	/**
	 * Set rendering frequency (in Hz or FPS). Minimum frequency is 0.016 (1
	 * frame per minute) and maximum is 25 (25 frames per second).
	 * 
	 * @param frequency the frequency
	 */
	public void setFPS(double frequency) {
		if (frequency > MAX_FREQUENCY) {
			frequency = MAX_FREQUENCY;
		}
		if (frequency < MIN_FREQUENCY) {
			frequency = MIN_FREQUENCY;
		}
		this.frequency = frequency;
	}

	/**
	 * Is webcam starting.
	 * 
	 * @return
	 */
	public boolean isStarting() {
		return starting;
	}

	/**
	 * Image will be resized to fill panel area if true. If false then image
	 * will be rendered as it was obtained from webcam instance.
	 * 
	 * @param fillArea shall image be resided to fill panel area
	 */
	public void setFillArea(boolean fillArea) {
		this.fillArea = fillArea;
	}

	/**
	 * Get value of fill area setting. Image will be resized to fill panel area
	 * if true. If false then image will be rendered as it was obtained from
	 * webcam instance.
	 * 
	 * @return True if image is being resized, false otherwise
	 */
	public boolean isFillArea() {
		return fillArea;
	}
}