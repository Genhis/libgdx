/*******************************************************************************
 * Copyright 2011 See AUTHORS file.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 ******************************************************************************/

package com.badlogic.gdx.backends.lwjgl;

import java.awt.Canvas;
import java.awt.Toolkit;
import java.nio.ByteBuffer;

import org.lwjgl.LWJGLException;
import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.ContextAttribs;
import org.lwjgl.opengl.Display;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.PixelFormat;

import com.badlogic.gdx.Application;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Graphics;
import com.badlogic.gdx.graphics.Cursor.SystemCursor;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.GL30;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Pixmap.Blending;
import com.badlogic.gdx.graphics.Pixmap.Format;
import com.badlogic.gdx.graphics.glutils.GLVersion;
import com.badlogic.gdx.utils.Array;
import com.badlogic.gdx.utils.GdxRuntimeException;
import com.badlogic.gdx.utils.SharedLibraryLoader;

/** An implementation of the {@link Graphics} interface based on Lwjgl.
 * @author mzechner */
public class LwjglGraphics implements Graphics {

	/** The suppored OpenGL extensions */
	public static Array<String> extensions;
	public static GLVersion glVersion;

	protected GL20 gl20;
	protected GL30 gl30;
	protected long frameId = -1;
	protected float deltaTime = 0;
	protected long frameStart = 0;
	protected int frames = 0;
	protected int fps;
	protected long lastTime = System.nanoTime();
	protected Canvas canvas;
	protected boolean vsync = false;
	protected boolean resize = false;
	LwjglApplicationConfiguration config;
	protected BufferFormat bufferFormat = new BufferFormat(8, 8, 8, 8, 16, 8, 0, false);
	protected volatile boolean isContinuous = true;
	protected volatile boolean requestRendering = false;
	protected boolean softwareMode;
	protected boolean usingGL30;

	protected LwjglGraphics (LwjglApplicationConfiguration config) {
		this.config = config;
	}

	protected LwjglGraphics (Canvas canvas) {
		this.config = new LwjglApplicationConfiguration();
		this.config.width = canvas.getWidth();
		this.config.height = canvas.getHeight();
		this.canvas = canvas;
	}

	protected LwjglGraphics (Canvas canvas, LwjglApplicationConfiguration config) {
		this.config = config;
		this.canvas = canvas;
	}

	@Override
	public int getHeight () {
		if (this.canvas != null)
			return Math.max(1, this.canvas.getHeight());
		else
			return (int)(Display.getHeight() * Display.getPixelScaleFactor());
	}

	@Override
	public int getWidth () {
		if (this.canvas != null)
			return Math.max(1, this.canvas.getWidth());
		else
			return (int)(Display.getWidth() * Display.getPixelScaleFactor());
	}

	@Override
	public int getBackBufferWidth () {
		return this.getWidth();
	}

	@Override
	public int getBackBufferHeight () {
		return this.getHeight();
	}

	@Override
	public long getFrameId () {
		return this.frameId;
	}

	@Override
	public float getDeltaTime () {
		return this.deltaTime;
	}

	@Override
	public float getRawDeltaTime () {
		return this.deltaTime;
	}

	@Override
	public GraphicsType getType () {
		return GraphicsType.LWJGL;
	}

	@Override
	public GLVersion getGLVersion () {
		return LwjglGraphics.glVersion;
	}

	public boolean isGL20Available () {
		return gl20 != null;
	}

	public GL20 getGL20 () {
		return gl20;
	}

	@Override
	public void setGL20 (GL20 gl20) {
		this.gl20 = gl20;
		if (gl30 == null) {
			Gdx.gl = gl20;
			Gdx.gl20 = gl20;
		}
	}

	@Override
	public boolean isGL30Available () {
		return gl30 != null;
	}

	@Override
	public GL30 getGL30 () {
		return gl30;
	}

	@Override
	public void setGL30 (GL30 gl30) {
		this.gl30 = gl30;
		if (gl30 != null) {
			this.gl20 = gl30;

			Gdx.gl = gl20;
			Gdx.gl20 = gl20;
			Gdx.gl30 = gl30;
		}
	}

	@Override
	public int getFramesPerSecond () {
		return this.fps;
	}

	protected void updateTime () {
		long time = System.nanoTime();
		this.deltaTime = (time - this.lastTime) / 1000000000.0f;
		this.lastTime = time;

		if (time - this.frameStart >= 1000000000) {
			this.fps = this.frames;
			this.frames = 0;
			this.frameStart = time;
		}
		this.frames++;
	}

	protected void setupDisplay () throws LWJGLException {
		if (this.config.useHDPI) System.setProperty("org.lwjgl.opengl.Display.enableHighDPI", "true");

		if (this.canvas != null)
			Display.setParent(this.canvas);
		else {
			boolean displayCreated = false;

			if (!this.config.fullscreen)
				displayCreated = this.setWindowedMode(this.config.width, this.config.height);
			else {
				DisplayMode bestMode = null;
				for (DisplayMode mode : this.getDisplayModes())
					if (mode.width == this.config.width && mode.height == this.config.height)
						if (bestMode == null || bestMode.refreshRate < this.getDisplayMode().refreshRate) bestMode = mode;
				if (bestMode == null) bestMode = this.getDisplayMode();
				displayCreated = this.setFullscreenMode(bestMode);
			}
			if (!displayCreated) {
				if (this.config.setDisplayModeCallback != null) {
					this.config = this.config.setDisplayModeCallback.onFailure(this.config);
					if (this.config != null) displayCreated = this.setWindowedMode(this.config.width, this.config.height);
				}
				if (!displayCreated) throw new GdxRuntimeException("Couldn't set display mode " + this.config.width + "x"
					+ this.config.height + ", fullscreen: " + this.config.fullscreen);
			}
			if (this.config.iconPaths.size > 0) {
				ByteBuffer[] icons = new ByteBuffer[this.config.iconPaths.size];
				for (int i = 0, n = this.config.iconPaths.size; i < n; i++) {
					Pixmap pixmap = new Pixmap(
						Gdx.files.getFileHandle(this.config.iconPaths.get(i), this.config.iconFileTypes.get(i)));
					if (pixmap.getFormat() != Format.RGBA8888) {
						Pixmap rgba = new Pixmap(pixmap.getWidth(), pixmap.getHeight(), Format.RGBA8888);
						rgba.setBlending(Blending.None);
						rgba.drawPixmap(pixmap, 0, 0);
						pixmap.dispose();
						pixmap = rgba;
					}
					icons[i] = ByteBuffer.allocateDirect(pixmap.getPixels().limit());
					icons[i].put(pixmap.getPixels()).flip();
					pixmap.dispose();
				}
				Display.setIcon(icons);
			}
		}
		Display.setTitle(this.config.title);
		Display.setResizable(this.config.resizable);
		Display.setInitialBackground(this.config.initialBackgroundColor.r, this.config.initialBackgroundColor.g,
			this.config.initialBackgroundColor.b);

		Display.setLocation(this.config.x, this.config.y);
		this.createDisplayPixelFormat(this.config.useGL30, this.config.gles30ContextMajorVersion,
			this.config.gles30ContextMinorVersion);
		this.initiateGL();
	}

	/** Only needed when setupDisplay() is not called. */
	protected void initiateGL () {
		LwjglGraphics.extractVersion();
		LwjglGraphics.extractExtensions();
		this.initiateGLInstances();
	}

	private static void extractVersion () {
		String versionString = org.lwjgl.opengl.GL11.glGetString(GL11.GL_VERSION);
		String vendorString = org.lwjgl.opengl.GL11.glGetString(GL11.GL_VENDOR);
		String rendererString = org.lwjgl.opengl.GL11.glGetString(GL11.GL_RENDERER);
		LwjglGraphics.glVersion = new GLVersion(Application.ApplicationType.Desktop, versionString, vendorString, rendererString);
	}

	private static void extractExtensions () {
		LwjglGraphics.extensions = new Array<String>();
		if (LwjglGraphics.glVersion.isVersionEqualToOrHigher(3, 2)) {
			int numExtensions = GL11.glGetInteger(GL30.GL_NUM_EXTENSIONS);
			for (int i = 0; i < numExtensions; ++i)
				LwjglGraphics.extensions.add(org.lwjgl.opengl.GL30.glGetStringi(GL20.GL_EXTENSIONS, i));
		} else
			LwjglGraphics.extensions.addAll(org.lwjgl.opengl.GL11.glGetString(GL20.GL_EXTENSIONS).split(" "));
	}

	/** @return whether the supported OpenGL (not ES) version is compatible with OpenGL ES 3.x. */
	private static boolean fullCompatibleWithGLES3 () {
		// OpenGL ES 3.0 is compatible with OpenGL 4.3 core, see http://en.wikipedia.org/wiki/OpenGL_ES#OpenGL_ES_3.0
		return LwjglGraphics.glVersion.isVersionEqualToOrHigher(4, 3);
	}

	/** @return whether the supported OpenGL (not ES) version is compatible with OpenGL ES 2.x. */
	private static boolean fullCompatibleWithGLES2 () {
		// OpenGL ES 2.0 is compatible with OpenGL 4.1 core
		// see https://www.opengl.org/registry/specs/ARB/ES2_compatibility.txt
		return LwjglGraphics.glVersion.isVersionEqualToOrHigher(4, 1)
			|| LwjglGraphics.extensions.contains("GL_ARB_ES2_compatibility", false);
	}

	private static boolean supportsFBO () {
		// FBO is in core since OpenGL 3.0, see https://www.opengl.org/wiki/Framebuffer_Object
		return LwjglGraphics.glVersion.isVersionEqualToOrHigher(3, 0)
			|| LwjglGraphics.extensions.contains("GL_EXT_framebuffer_object", false)
			|| LwjglGraphics.extensions.contains("GL_ARB_framebuffer_object", false);
	}

	private void createDisplayPixelFormat (boolean useGL30, int gles30ContextMajor, int gles30ContextMinor) {
		try {
			if (useGL30) {
				ContextAttribs context = new ContextAttribs(gles30ContextMajor, gles30ContextMinor).withForwardCompatible(false)
					.withProfileCore(true);
				try {
					Display.create(new PixelFormat(this.config.r + this.config.g + this.config.b, this.config.a, this.config.depth,
						this.config.stencil, this.config.samples), context);
				} catch (Exception e) {
					System.out.println("LwjglGraphics: OpenGL " + gles30ContextMajor + "." + gles30ContextMinor
						+ "+ core profile (GLES 3.0) not supported.");
					this.createDisplayPixelFormat(false, gles30ContextMajor, gles30ContextMinor);
					return;
				}
				System.out.println("LwjglGraphics: created OpenGL " + gles30ContextMajor + "." + gles30ContextMinor
					+ "+ core profile (GLES 3.0) context. This is experimental!");
				this.usingGL30 = true;
			} else {
				Display.create(new PixelFormat(this.config.r + this.config.g + this.config.b, this.config.a, this.config.depth,
					this.config.stencil, this.config.samples));
				this.usingGL30 = false;
			}
			this.bufferFormat = new BufferFormat(this.config.r, this.config.g, this.config.b, this.config.a, this.config.depth,
				this.config.stencil, this.config.samples, false);
		} catch (Exception ex) {
			Display.destroy();
			try {
				Thread.sleep(200);
			} catch (InterruptedException ignored) {
			}
			try {
				Display.create(new PixelFormat(0, 16, 8));
				if (this.getDisplayMode().bitsPerPixel == 16) this.bufferFormat = new BufferFormat(5, 6, 5, 0, 16, 8, 0, false);
				if (this.getDisplayMode().bitsPerPixel == 24) this.bufferFormat = new BufferFormat(8, 8, 8, 0, 16, 8, 0, false);
				if (this.getDisplayMode().bitsPerPixel == 32) this.bufferFormat = new BufferFormat(8, 8, 8, 8, 16, 8, 0, false);
			} catch (Exception ex2) {
				Display.destroy();
				try {
					Thread.sleep(200);
				} catch (InterruptedException ignored) {
				}
				try {
					Display.create(new PixelFormat());
				} catch (Exception ex3) {
					if (!this.softwareMode && this.config.allowSoftwareMode) {
						this.softwareMode = true;
						System.setProperty("org.lwjgl.opengl.Display.allowSoftwareOpenGL", "true");
						this.createDisplayPixelFormat(useGL30, gles30ContextMajor, gles30ContextMinor);
						return;
					}
					throw new GdxRuntimeException("OpenGL is not supported by the video driver.", ex3);
				}
				if (this.getDisplayMode().bitsPerPixel == 16) this.bufferFormat = new BufferFormat(5, 6, 5, 0, 8, 0, 0, false);
				if (this.getDisplayMode().bitsPerPixel == 24) this.bufferFormat = new BufferFormat(8, 8, 8, 0, 8, 0, 0, false);
				if (this.getDisplayMode().bitsPerPixel == 32) this.bufferFormat = new BufferFormat(8, 8, 8, 8, 8, 0, 0, false);
			}
		}
	}

	public void initiateGLInstances () {
		if (this.usingGL30) {
			this.gl30 = new LwjglGL30();
			this.gl20 = this.gl30;
		} else
			this.gl20 = new LwjglGL20();

		if (!LwjglGraphics.glVersion.isVersionEqualToOrHigher(2, 0))
			throw new GdxRuntimeException("OpenGL 2.0 or higher with the FBO extension is required. OpenGL version: "
				+ GL11.glGetString(GL11.GL_VERSION) + "\n" + LwjglGraphics.glVersion.getDebugVersionString());

		if (!LwjglGraphics.supportsFBO())
			throw new GdxRuntimeException("OpenGL 2.0 or higher with the FBO extension is required. OpenGL version: "
				+ GL11.glGetString(GL11.GL_VERSION) + ", FBO extension: false\n" + LwjglGraphics.glVersion.getDebugVersionString());

		Gdx.gl = this.gl20;
		Gdx.gl20 = this.gl20;
		Gdx.gl30 = this.gl30;
	}

	@Override
	public float getPpiX () {
		return Toolkit.getDefaultToolkit().getScreenResolution();
	}

	@Override
	public float getPpiY () {
		return Toolkit.getDefaultToolkit().getScreenResolution();
	}

	@Override
	public float getPpcX () {
		return Toolkit.getDefaultToolkit().getScreenResolution() / 2.54f;
	}

	@Override
	public float getPpcY () {
		return Toolkit.getDefaultToolkit().getScreenResolution() / 2.54f;
	}

	@Override
	public float getDensity () {
		if (this.config.overrideDensity != -1) return this.config.overrideDensity / 160f;
		return Toolkit.getDefaultToolkit().getScreenResolution() / 160f;
	}

	@Override
	public boolean supportsDisplayModeChange () {
		return true;
	}

	@Override
	public Monitor getPrimaryMonitor () {
		return new LwjglMonitor(0, 0, "Primary Monitor");
	}

	@Override
	public Monitor getMonitor () {
		return this.getPrimaryMonitor();
	}

	@Override
	public Monitor[] getMonitors () {
		return new Monitor[] {this.getPrimaryMonitor()};
	}

	@Override
	public DisplayMode[] getDisplayModes (Monitor monitor) {
		return this.getDisplayModes();
	}

	@Override
	public DisplayMode getDisplayMode (Monitor monitor) {
		return this.getDisplayMode();
	}

	@Override
	public boolean setFullscreenMode (DisplayMode displayMode) {
		org.lwjgl.opengl.DisplayMode mode = ((LwjglDisplayMode)displayMode).mode;
		try {
			if (!mode.isFullscreenCapable())
				Display.setDisplayMode(mode);
			else
				Display.setDisplayModeAndFullscreen(mode);
			float scaleFactor = Display.getPixelScaleFactor();
			this.config.width = (int)(mode.getWidth() * scaleFactor);
			this.config.height = (int)(mode.getHeight() * scaleFactor);
			if (Gdx.gl != null) Gdx.gl.glViewport(0, 0, this.config.width, this.config.height);
			this.resize = true;
			return true;
		} catch (LWJGLException e) {
			return false;
		}
	}

	/** Kindly stolen from http://lwjgl.org/wiki/index.php?title=LWJGL_Basics_5_(Fullscreen), not perfect but will do. */
	@Override
	public boolean setWindowedMode (int width, int height) {
		if (this.getWidth() == width && this.getHeight() == height && !Display.isFullscreen()) return true;

		try {
			org.lwjgl.opengl.DisplayMode targetDisplayMode = null;
			boolean fullscreen = false;

			if (fullscreen) {
				org.lwjgl.opengl.DisplayMode[] modes = Display.getAvailableDisplayModes();
				int freq = 0;

				for (int i = 0; i < modes.length; i++) {
					org.lwjgl.opengl.DisplayMode current = modes[i];

					if (current.getWidth() == width && current.getHeight() == height) {
						if (targetDisplayMode == null || current.getFrequency() >= freq)
							if (targetDisplayMode == null || current.getBitsPerPixel() > targetDisplayMode.getBitsPerPixel()) {
							targetDisplayMode = current;
							freq = targetDisplayMode.getFrequency();
							}

						// if we've found a match for bpp and frequence against the
						// original display mode then it's probably best to go for this one
						// since it's most likely compatible with the monitor
						if (current.getBitsPerPixel() == Display.getDesktopDisplayMode().getBitsPerPixel()
							&& current.getFrequency() == Display.getDesktopDisplayMode().getFrequency()) {
							targetDisplayMode = current;
							break;
						}
					}
				}
			} else
				targetDisplayMode = new org.lwjgl.opengl.DisplayMode(width, height);

			if (targetDisplayMode == null) return false;

			boolean resizable = !fullscreen && this.config.resizable;

			Display.setDisplayMode(targetDisplayMode);
			Display.setFullscreen(fullscreen);
			// Workaround for bug in LWJGL whereby resizable state is lost on DisplayMode change
			if (resizable == Display.isResizable()) Display.setResizable(!resizable);
			Display.setResizable(resizable);

			float scaleFactor = Display.getPixelScaleFactor();
			this.config.width = (int)(targetDisplayMode.getWidth() * scaleFactor);
			this.config.height = (int)(targetDisplayMode.getHeight() * scaleFactor);
			if (Gdx.gl != null) Gdx.gl.glViewport(0, 0, this.config.width, this.config.height);
			this.resize = true;
			return true;
		} catch (LWJGLException e) {
			return false;
		}
	}

	@Override
	public DisplayMode[] getDisplayModes () {
		try {
			org.lwjgl.opengl.DisplayMode[] availableDisplayModes = Display.getAvailableDisplayModes();
			DisplayMode[] modes = new DisplayMode[availableDisplayModes.length];

			int idx = 0;
			for (org.lwjgl.opengl.DisplayMode mode : availableDisplayModes)
				if (mode.isFullscreenCapable()) modes[idx++] = new LwjglDisplayMode(mode.getWidth(), mode.getHeight(),
					mode.getFrequency(), mode.getBitsPerPixel(), mode);

			return modes;
		} catch (LWJGLException e) {
			throw new GdxRuntimeException("Couldn't fetch available display modes", e);
		}
	}

	@Override
	public DisplayMode getDisplayMode () {
		org.lwjgl.opengl.DisplayMode mode = Display.getDesktopDisplayMode();
		return new LwjglDisplayMode(mode.getWidth(), mode.getHeight(), mode.getFrequency(), mode.getBitsPerPixel(), mode);
	}

	@Override
	public void setTitle (String title) {
		Display.setTitle(title);
	}

	/** Display must be reconfigured via {@link #setWindowedMode(int, int)} for the changes to take effect. */
	@Override
	public void setUndecorated (boolean undecorated) {
		System.setProperty("org.lwjgl.opengl.Window.undecorated", undecorated ? "true" : "false");
	}

	/** Display must be reconfigured via {@link #setWindowedMode(int, int)} for the changes to take effect. */
	@Override
	public void setResizable (boolean resizable) {
		this.config.resizable = resizable;
		Display.setResizable(resizable);
	}

	@Override
	public BufferFormat getBufferFormat () {
		return this.bufferFormat;
	}

	@Override
	public void setVSync (boolean vsync) {
		this.vsync = vsync;
		Display.setVSyncEnabled(vsync);
	}

	@Override
	public boolean supportsExtension (String extension) {
		return LwjglGraphics.extensions.contains(extension, false);
	}

	@Override
	public void setContinuousRendering (boolean isContinuous) {
		this.isContinuous = isContinuous;
	}

	@Override
	public boolean isContinuousRendering () {
		return this.isContinuous;
	}

	@Override
	public void requestRendering () {
		synchronized (this) {
			this.requestRendering = true;
		}
	}

	public boolean shouldRender () {
		synchronized (this) {
			boolean rq = this.requestRendering;
			this.requestRendering = false;
			return rq || this.isContinuous || Display.isDirty();
		}
	}

	@Override
	public boolean isFullscreen () {
		return Display.isFullscreen();
	}

	public boolean isSoftwareMode () {
		return this.softwareMode;
	}

	/** A callback used by LwjglApplication when trying to create the display */
	public interface SetDisplayModeCallback {
		/** If the display creation fails, this method will be called. Suggested usage is to modify the passed configuration to use
		 * a common width and height, and set fullscreen to false.
		 * @return the configuration to be used for a second attempt at creating a display. A null value results in NOT attempting
		 *         to create the display a second time */
		public LwjglApplicationConfiguration onFailure (LwjglApplicationConfiguration initialConfig);
	}

	@Override
	public com.badlogic.gdx.graphics.Cursor newCursor (Pixmap pixmap, int xHotspot, int yHotspot) {
		return new LwjglCursor(pixmap, xHotspot, yHotspot);
	}

	@Override
	public void setCursor (com.badlogic.gdx.graphics.Cursor cursor) {
		if (this.canvas != null && SharedLibraryLoader.isMac) return;
		try {
			Mouse.setNativeCursor(((LwjglCursor)cursor).lwjglCursor);
		} catch (LWJGLException e) {
			throw new GdxRuntimeException("Could not set cursor image.", e);
		}
	}

	@Override
	public void setSystemCursor (SystemCursor systemCursor) {
		if (this.canvas != null && SharedLibraryLoader.isMac) return;
		try {
			Mouse.setNativeCursor(null);
		} catch (LWJGLException e) {
			throw new GdxRuntimeException("Couldn't set system cursor");
		}
	}

	private class LwjglDisplayMode extends DisplayMode {
		org.lwjgl.opengl.DisplayMode mode;

		public LwjglDisplayMode (int width, int height, int refreshRate, int bitsPerPixel, org.lwjgl.opengl.DisplayMode mode) {
			super(width, height, refreshRate, bitsPerPixel);
			this.mode = mode;
		}
	}

	private class LwjglMonitor extends Monitor {
		protected LwjglMonitor (int virtualX, int virtualY, String name) {
			super(virtualX, virtualY, name);
		}
	}
}
