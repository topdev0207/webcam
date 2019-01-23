package com.github.sarxos.webcam.ds.raspberrypi;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;

import org.apache.commons.io.FileUtils;

import junit.framework.TestCase;

public class TestImageInputStream extends TestCase {
	int RUN = 1000;

	@Override
	protected void setUp() throws Exception {
		super.setUp();
		mockMultiImageForStream("png");
	}

	public void testPNG() throws Exception {
		testType("png");
	}

	public void testBasePNGDecoder() throws Exception {

		File f = new File("src/etc/resources/1.png");
		InputStream in = new FileInputStream(f);
		PNGDecoder decoder = new PNGDecoder(in);

		BufferedImage bi = decoder.decode();
		bi.flush();
		assertNotNull(bi);
		in.close();

		// checked image is so good! //ImageIO.write(bi, "png", new File("out.png"));
	}

	public void testPNGStream() throws Exception {
		testType("png");
	}

	private void testType(String type) throws Exception {
		File mutilImage = new File("src/etc/resources/" + type + ".chunk");

		InputStream in = new FileInputStream(mutilImage);
		byte[] bytes = FileUtils.readFileToByteArray(new File("src/etc/resources/kc." + type));
		byte[] chunkBytes = FileUtils.readFileToByteArray(mutilImage);
		assertEquals(bytes.length, chunkBytes.length / 10);

		for (int i = 0; i < 10; i++) {
			BufferedImage image = new PNGDecoder(in).decode();
			in.skip(16);
			assertNotNull(image);
			System.out.println("decoded No." + i + " image");
		}

		in.close();
	}

	@Override
	protected void tearDown() throws Exception {
		super.tearDown();
		deleteChunk("png");
	}

	private static void mockMultiImageForStream(String type) throws IOException {
		File mutilImage = deleteChunk(type);
		byte[] bytes = FileUtils.readFileToByteArray(new File("src/etc/resources/kc." + type));
		for (int i = 0; i < 10; i++) {
			FileUtils.writeByteArrayToFile(mutilImage, bytes, true);
		}
	}

	private static File deleteChunk(String type) {
		File mutilImage = new File("src/etc/resources/" + type + ".chunk");
		if (mutilImage.exists()) {
			mutilImage.delete();
		}
		return mutilImage;
	}
}
